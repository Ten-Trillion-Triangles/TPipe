package genericOpenAIPipe.api

import com.TTT.Util.serialize
import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.ContentBlock
import genericOpenAIPipe.env.GenericOpenAIChatRequest
import genericOpenAIPipe.env.MessageContent
import genericOpenAIPipe.env.OpenAIResponsesInputPart
import genericOpenAIPipe.env.OpenAIResponsesMessageItem
import genericOpenAIPipe.env.OpenAIResponsesReasoning
import genericOpenAIPipe.env.OpenAIResponsesRequest
import genericOpenAIPipe.env.OpenAIResponsesTextConfig
import genericOpenAIPipe.env.OpenAIResponsesTextFormat
import genericOpenAIPipe.env.OpenAIResponsesTool
import genericOpenAIPipe.env.ReasoningConfig
import genericOpenAIPipe.env.ResponseFormat
import genericOpenAIPipe.env.ToolDefinition

/**
 * [RequestSerializer] implementation that emits the OpenAI Responses wire spec
 * (`/v1/responses`).
 *
 * The Responses API is structurally different from the chat-completions API:
 *  - system messages are hoisted into a top-level `instructions` string,
 *  - `input` is a list of `Message` items whose `content` is a list of typed parts
 *    (`input_text`, `input_image`),
 *  - `max_tokens` becomes `max_output_tokens`,
 *  - `response_format` is wrapped as `text.format`,
 *  - tools and reasoning pass through with the same field names.
 *
 * The serializer is stateless — instances are safe to share across requests. The
 * [ApiMode.OpenAIResponses] variant is the only one this class supports; any other
 * mode raises an [IllegalArgumentException] so a misconfiguration is caught at the
 * first request, not silently.
 *
 * @see <a href="https://platform.openai.com/docs/api-reference/responses/create">POST /v1/responses</a>
 */
class OpenAIResponsesRequestSerializer : RequestSerializer
{

    /**
     * Serializes a normalised [GenericOpenAIChatRequest] into the OpenAI Responses
     * wire-spec JSON string.
     *
     * @param request The normalised in-process request
     * @param apiMode Must be [ApiMode.OpenAIResponses]
     * @return JSON string ready for HTTP POST body
     * @throws IllegalArgumentException if [apiMode] is not [ApiMode.OpenAIResponses]
     */
    override fun serialize(request: GenericOpenAIChatRequest, apiMode: ApiMode): String
    {
        require(apiMode is ApiMode.OpenAIResponses)
        { "OpenAIResponsesRequestSerializer only supports ApiMode.OpenAIResponses, got $apiMode" }

        val responsesRequest = convert(request)
        return serialize(responsesRequest, encodedefault = false)
    }

    /**
     * Pure conversion: [GenericOpenAIChatRequest] -> [OpenAIResponsesRequest].
     *
     * Exposed package-private for unit-test reuse without re-serializing to JSON.
     */
    internal fun convert(request: GenericOpenAIChatRequest): OpenAIResponsesRequest
    {
        val (instructions, inputMessages) = splitSystemAndInput(request.messages)

        val input = inputMessages.map { convertMessage(it) }

        return OpenAIResponsesRequest(
            model = request.model,
            input = input,
            instructions = instructions,
            maxOutputTokens = request.maxTokens ?: request.maxCompletionTokens,
            temperature = request.temperature,
            topP = request.topP,
            stream = request.stream,
            tools = request.tools?.map { convertTool(it) },
            toolChoice = request.toolChoice,
            parallelToolCalls = request.parallelToolCalls,
            text = convertResponseFormat(request.responseFormat),
            reasoning = convertReasoning(request.reasoning),
            user = request.user
        )
    }

    /**
     * Splits the [GenericOpenAIChatRequest.messages] list into (instructions, input).
     *
     *  - Every `system` message contributes to the `instructions` string. Multiple
     *    system messages are joined with a blank line so the model sees them as
     *    distinct paragraphs. Blank system messages are dropped.
     *  - Everything else becomes a `Message` input item, in original order.
     */
    private fun splitSystemAndInput(
        messages: List<ChatMessage>
    ): Pair<String?, List<ChatMessage>>
    {
        val systemParts = messages
            .asSequence()
            .filter { it.role == "system" }
            .mapNotNull { extractText(it.content)?.takeIf { text -> text.isNotBlank() } }
            .toList()

        val instructions = systemParts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")

        val input = messages.filter { it.role != "system" }
        return instructions to input
    }

    /**
     * Converts a single non-system [ChatMessage] to a Responses [OpenAIResponsesMessageItem].
     */
    private fun convertMessage(message: ChatMessage): OpenAIResponsesMessageItem
    {
        val parts = convertContent(message.content)
        return OpenAIResponsesMessageItem(
            role = message.role,
            content = parts
        )
    }

    /**
     * Converts a [MessageContent] payload into a list of typed input parts.
     *
     *  - [MessageContent.TextContent] -> a single [OpenAIResponsesInputPart.InputTextPart]
     *  - [MessageContent.PlainContent] -> a single [OpenAIResponsesInputPart.InputTextPart]
     *  - [MessageContent.MultimodalContent] -> a list of typed parts preserving order,
     *    with text blocks mapped to [OpenAIResponsesInputPart.InputTextPart] and image
     *    blocks mapped to [OpenAIResponsesInputPart.InputImagePart]
     */
    private fun convertContent(content: MessageContent): List<OpenAIResponsesInputPart>
    {
        return when(content)
        {
            is MessageContent.TextContent -> listOf(OpenAIResponsesInputPart.InputTextPart(content.text))
            is MessageContent.PlainContent -> listOf(OpenAIResponsesInputPart.InputTextPart(content.content))
            is MessageContent.MultimodalContent -> content.blocks.map { block ->
                when(block)
                {
                    is ContentBlock.TextBlock -> OpenAIResponsesInputPart.InputTextPart(block.text)
                    is ContentBlock.ImageUrlBlock -> OpenAIResponsesInputPart.InputImagePart(
                        imageUrl = block.url,
                        detail = block.detail ?: "auto"
                    )
                }
            }
        }
    }

    /**
     * Converts the normalised [ResponseFormat] (chat-completions shape) to the Responses
     * `text.format` wrapper. Returns `null` for `null` input and a [OpenAIResponsesTextConfig]
     * wrapper for any of the three supported types.
     */
    private fun convertResponseFormat(format: ResponseFormat?): OpenAIResponsesTextConfig?
    {
        if(format == null) return null
        return OpenAIResponsesTextConfig(
            format = OpenAIResponsesTextFormat(
                type = format.type,
                schema = format.jsonSchema
            )
        )
    }

    /**
     * Converts the normalised [ReasoningConfig] to the Responses-API `reasoning` block.
     * Returns `null` if every field is null so the wire JSON does not carry an empty object.
     */
    private fun convertReasoning(reasoning: ReasoningConfig?): OpenAIResponsesReasoning?
    {
        if(reasoning == null) return null
        if(reasoning.effort == null && reasoning.maxTokens == null) return null
        return OpenAIResponsesReasoning(
            effort = reasoning.effort,
            maxTokens = reasoning.maxTokens
        )
    }

    /**
     * Converts a chat-completions [ToolDefinition] into the Responses-API [OpenAIResponsesTool]
     * wrapper (function schema is shared verbatim).
     */
    private fun convertTool(tool: ToolDefinition): OpenAIResponsesTool
    {
        return OpenAIResponsesTool(
            type = tool.type,
            function = tool.function
        )
    }

    /**
     * Extracts the text payload from a [MessageContent] for use in `instructions`.
     * Returns `null` for [MessageContent.MultimodalContent] so the caller can drop it.
     */
    private fun extractText(content: MessageContent): String?
    {
        return when(content)
        {
            is MessageContent.TextContent -> content.text
            is MessageContent.PlainContent -> content.content
            is MessageContent.MultimodalContent -> null
        }
    }
}
