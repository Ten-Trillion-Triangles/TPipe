package genericOpenAIPipe.api

import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import com.TTT.Util.deserialize
import genericOpenAIPipe.env.ChatChoice
import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.CompletionTokensDetails
import genericOpenAIPipe.env.GenericOpenAIChatResponse
import genericOpenAIPipe.env.GenericOpenAIErrorResponse
import genericOpenAIPipe.env.MessageContent
import genericOpenAIPipe.env.OpenAIResponsesContentPart
import genericOpenAIPipe.env.OpenAIResponsesOutputItem
import genericOpenAIPipe.env.OpenAIResponsesResponse
import genericOpenAIPipe.env.UsageInfo

/**
 * [ResponseParser] implementation for the OpenAI `/v1/responses` endpoint.
 *
 * Projects the Responses wire spec into the [GenericOpenAIChatResponse] shape the
 * rest of the pipe already consumes, so the existing `generateText` / `generateContent`
 * pipeline can stay mode-agnostic.
 *
 * Mapping rules:
 *  - The first `message` output item contributes the assistant content.
 *    If the item contains an `output_text` part the text is concatenated across parts;
 *    if it contains a `refusal` part the content is empty and `finish_reason` is `refusal`.
 *  - Every `reasoning` output item contributes its chain-of-thought via
 *    [extractReasoningText]. The concatenated text is exposed as
 *    [GenericOpenAIChatResponse.reasoningContent] so the pipe can surface it on
 *    `MultimodalContent.modelReasoning` and the base `Pipe.trace` will then
 *    auto-include `reasoningContent` in the trace metadata (Pipe.kt:4675).
 *  - `status` = `incomplete` maps to `finish_reason = "incomplete"`.
 *  - `status` = `failed` throws a [P2PException] with [P2PError.transport].
 *  - An error-only body (`{ "error": { ... } }`) is mapped to a [P2PException] with
 *    the right [P2PError] based on the error `type` / `code` (auth / prompt / transport).
 *  - Empty `output` lists yield an empty `TextContent("")` choice so callers always
 *    see a non-null content.
 */
class OpenAIResponsesResponseParser : ResponseParser
{

    /**
     * Parses a raw Responses JSON body into a [GenericOpenAIChatResponse].
     *
     * @param response The raw JSON response body
     * @param apiMode Must be [ApiMode.OpenAIResponses]
     * @return A [GenericOpenAIChatResponse] normalised to the chat-completions shape
     * @throws P2PException if the response is an error or indicates a failure
     */
    override fun parse(response: String, apiMode: ApiMode): GenericOpenAIChatResponse
    {
        require(apiMode is ApiMode.OpenAIResponses)
        { "OpenAIResponsesResponseParser only supports ApiMode.OpenAIResponses, got $apiMode" }

        // 1. Error-only body (e.g. 4xx/5xx) — try to map to a P2PException first.
        val errorResponse = try
        {
            deserialize<GenericOpenAIErrorResponse>(response)
        }
        catch(e: Exception)
        {
            null
        }
        if(errorResponse != null && !errorResponse.error.message.isNullOrEmpty())
        {
            throw mapErrorResponse(errorResponse)
        }

        // 2. Happy path: parse the responses body and project it.
        val parsed = (try
        {
            deserialize<OpenAIResponsesResponse>(response)
        }
        catch(e: Exception)
        {
            null
        }) ?: throw P2PException(
                P2PError.json,
                "Failed to deserialize OpenAI Responses body: $response",
                Exception("Deserialization returned null")
            )

        // 3. Server-side failure status -> P2PException.transport
        if(parsed.status == "failed")
        {
            val errMessage = parsed.error?.message
                ?: "OpenAI Responses call failed (status=failed, id=${parsed.id})"
            throw P2PException(P2PError.transport, errMessage, Exception(errMessage))
        }

        return projectToGeneric(parsed)
    }

    /**
     * Translates the Responses-API [OpenAIResponsesResponse] into the
     * chat-completions-shaped [GenericOpenAIChatResponse].
     */
    private fun projectToGeneric(parsed: OpenAIResponsesResponse): GenericOpenAIChatResponse
    {
        val (textContent, hasRefusal) = extractTextAndRefusal(parsed.output)
        val extractedReasoning = extractReasoningText(parsed.output)
        val finishReason = pickFinishReason(parsed.status, hasRefusal)
        val usage = projectUsage(parsed)

        return GenericOpenAIChatResponse(
            id = parsed.id,
            objectType = parsed.objectType,
            created = parsed.createdAt,
            model = parsed.model,
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = MessageContent.TextContent(textContent)
                    ),
                    finishReason = finishReason,
                    logprobs = null
                )
            ),
            usage = usage,
            systemFingerprint = null,
            reasoningContent = extractedReasoning.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Walks the `output` list, concatenating all `output_text` parts and detecting
     * whether any part was a `refusal` (which suppresses the text and forces
     * `finish_reason = "refusal"`).
     */
    private fun extractTextAndRefusal(items: List<OpenAIResponsesOutputItem>): Pair<String, Boolean>
    {
        val sb = StringBuilder()
        var hasRefusal = false

        for(item in items)
        {
            if(item is OpenAIResponsesOutputItem.Message)
            {
                for(part in item.content)
                {
                    when(part)
                    {
                        is OpenAIResponsesContentPart.OutputText -> sb.append(part.text)
                        is OpenAIResponsesContentPart.Refusal ->
                        {
                            hasRefusal = true
                        }
                        is OpenAIResponsesContentPart.ReasoningText -> Unit
                    }
                }
            }
        }

        val text = if(hasRefusal) "" else sb.toString()
        return text to hasRefusal
    }

    /**
     * Concatenates the `reasoning_text` content from every `reasoning` output item
     * in the order they appear, separated by newlines so two distinct reasoning
     * items do not visually run into each other in the trace.
     *
     * Returns an empty string when no reasoning items are present so callers can
     * treat `null` and `""` interchangeably.
     */
    private fun extractReasoningText(items: List<OpenAIResponsesOutputItem>): String
    {
        val parts = mutableListOf<String>()
        for(item in items)
        {
            if(item is OpenAIResponsesOutputItem.Reasoning)
            {
                for(part in item.content)
                {
                    if(part is OpenAIResponsesContentPart.ReasoningText && part.text.isNotEmpty())
                    {
                        parts.add(part.text)
                    }
                }
            }
        }
        return parts.joinToString("\n")
    }

    /**
     * Picks the right `finish_reason` value:
     *  - refusal content wins over the status,
     *  - `incomplete` -> `incomplete`,
     *  - everything else (including `completed`) -> `stop`.
     */
    private fun pickFinishReason(status: String, hasRefusal: Boolean): String
    {
        if(hasRefusal) return "refusal"
        return when(status)
        {
            "incomplete" -> "incomplete"
            else -> "stop"
        }
    }

    /**
     * Maps the Responses-API usage object into the chat-completions-shaped
     * [UsageInfo], carrying the `reasoning_tokens` detail through.
     */
    private fun projectUsage(parsed: OpenAIResponsesResponse): UsageInfo?
    {
        val src = parsed.usage ?: return null
        val details = src.outputTokensDetails?.reasoningTokens?.let {
            CompletionTokensDetails(reasoningTokens = it)
        }
        return UsageInfo(
            promptTokens = src.inputTokens,
            completionTokens = src.outputTokens,
            totalTokens = src.totalTokens,
            promptTokensDetails = null,
            completionTokensDetails = details
        )
    }

    /**
     * Maps a [GenericOpenAIErrorResponse] (the chat-completions error shape, which the
     * Responses API also uses for HTTP errors) into a [P2PException] with the right
     * [P2PError] family.
     */
    private fun mapErrorResponse(err: GenericOpenAIErrorResponse): P2PException
    {
        val errorMessage = err.error.message
        val errorType = err.error.type
        val errorCode = err.error.code

        val p2pError = when
        {
            errorType == "invalid_api_key" || errorType == "authentication_error" -> P2PError.auth
            errorType == "rate_limit_error" || errorType == "rate_limit_exceeded" -> P2PError.transport
            errorType == "invalid_request_error" || errorCode == "invalid_request" -> P2PError.prompt
            errorType == "api_error" || errorType == "server_error" || (errorType?.startsWith("server_") == true) -> P2PError.transport
            else -> P2PError.transport
        }
        return P2PException(p2pError, "OpenAI Responses error: $errorMessage", Exception(errorMessage))
    }
}
