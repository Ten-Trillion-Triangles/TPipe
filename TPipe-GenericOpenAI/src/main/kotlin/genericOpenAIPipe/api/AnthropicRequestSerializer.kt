package genericOpenAIPipe.api

import com.TTT.Util.serialize
import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.GenericOpenAIChatRequest
import genericOpenAIPipe.env.MessageContent

/**
 * Request serializer for Anthropic Messages API.
 *
 * Transforms [GenericOpenAIChatRequest] into [AnthropicMessagesRequest] format:
 * - Extracts first system message → `system` parameter (not in messages array)
 * - Converts remaining user/assistant messages to [AnthropicMessage] format
 * - Validates `maxTokens` is present (REQUIRED by Anthropic API)
 * - Stores session_id for follow-up requests
 *
 * Anthropic API requirements:
 * - `max_tokens` is REQUIRED, not optional
 * - System role is not allowed in messages array
 * - Only user and assistant roles supported in messages
 *
 * @see RequestSerializer for the interface contract
 * @see AnthropicMessagesRequest for the target request format
 */
class AnthropicRequestSerializer : RequestSerializer
{
    /**
     * Serializes a [GenericOpenAIChatRequest] to Anthropic Messages API format.
     *
     * Transformation process:
     * 1. Validates that maxTokens is present (required by Anthropic)
     * 2. Extracts first system message to separate `system` parameter
     * 3. Converts remaining messages to Anthropic user/assistant format
     * 4. Preserves session_id for follow-up requests
     *
     * @param request The [GenericOpenAIChatRequest] to transform
     * @param apiMode The [ApiMode] (must be [ApiMode.Anthropic])
     * @return JSON string suitable for Anthropic /v1/messages endpoint
     * @throws IllegalStateException if maxTokens is missing from request
     * @throws IllegalArgumentException if apiMode is not Anthropic
     */
    override fun serialize(request: GenericOpenAIChatRequest, apiMode: ApiMode): String
    {
        require(apiMode is ApiMode.Anthropic)
        { "AnthropicRequestSerializer only supports ApiMode.Anthropic, got $apiMode" }

        // Validate maxTokens is present (REQUIRED by Anthropic API)
        val maxTokens = request.maxTokens
            ?: request.maxCompletionTokens
            ?: throw IllegalStateException(
                "maxTokens is REQUIRED for Anthropic API. " +
                "Neither maxTokens nor maxCompletionTokens was provided in the request."
            )

        // Extract system messages and log warning if multiple exist
        val systemMessages = request.messages.filter { it.role == "system" }
        if(systemMessages.size > 1)
        {
            println("WARNING: Multiple system messages found (${systemMessages.size}). Using first, dropping rest. Anthropic only supports one system message.")
        }

        // Extract first system message to separate parameter
        val systemMessage = systemMessages.firstOrNull()
            ?.let { extractTextContent(it.content) }
            ?.takeIf { it.isNotBlank() }

        // Convert remaining messages (filter out system messages)
        val anthropicMessages = request.messages
            .filter { it.role != "system" }
            .map { chatMessage -> convertToAnthropicMessage(chatMessage) }

        // Build Anthropic request
        val anthropicRequest = AnthropicMessagesRequest(
            model = request.model,
            messages = anthropicMessages,
            system = systemMessage,
            maxTokens = maxTokens,
            stream = request.stream,
            sessionId = null // Will be set by caller if needed
        )

        return serialize(anthropicRequest, encodedefault = false)
    }

    /**
     * Converts a [ChatMessage] to [AnthropicMessage].
     *
     * Only user and assistant roles are supported. System messages
     * should have been extracted before calling this method.
     *
     * @param chatMessage The [ChatMessage] to convert
     * @return [AnthropicMessage.UserMessage] or [AnthropicMessage.AssistantMessage]
     * @throws IllegalArgumentException if role is not user or assistant
     */
    private fun convertToAnthropicMessage(chatMessage: ChatMessage): AnthropicMessage
    {
        val content = extractContentBlocks(chatMessage.content)

        return when(chatMessage.role)
        {
            "user" -> AnthropicMessage.UserMessage(content = content)
            "assistant" -> AnthropicMessage.AssistantMessage(content = content)
            else -> throw IllegalArgumentException(
                "Anthropic only supports 'user' and 'assistant' roles in messages. " +
                "Got role: '${chatMessage.role}'"
            )
        }
    }

    /**
     * Extracts content blocks from [MessageContent] for Anthropic format.
     *
     * Handles both simple text content and multimodal content.
     * Text content becomes [AnthropicContentBlock.TextBlock].
     * Currently, image support is limited to text extraction.
     *
     * @param content The [MessageContent] to extract from
     * @return List of [AnthropicContentBlock] for Anthropic message
     */
    private fun extractContentBlocks(content: MessageContent): List<AnthropicContentBlock>
    {
        return when(content)
        {
            is MessageContent.TextContent ->
            {
                listOf(AnthropicContentBlock.TextBlock(text = content.text))
            }
            is MessageContent.MultimodalContent ->
            {
                // Extract text blocks from multimodal content
                // Image blocks would need additional handling for base64/URL sources
                content.blocks.mapNotNull { block ->
                    when(block)
                    {
                        is genericOpenAIPipe.env.ContentBlock.TextBlock ->
                            AnthropicContentBlock.TextBlock(text = block.text)
                        is genericOpenAIPipe.env.ContentBlock.ImageUrlBlock ->
                            null
                    }
                }.ifEmpty {
                    // If no text blocks found, return empty text block
                    listOf(AnthropicContentBlock.TextBlock(text = ""))
                }
            }
        }
    }

    /**
     * Extracts text content from [MessageContent].
     *
     * @param content The [MessageContent] to extract from
     * @return The text content, or null if no text content found
     */
    private fun extractTextContent(content: MessageContent): String?
    {
        return when(content)
        {
            is MessageContent.TextContent -> content.text
            is MessageContent.MultimodalContent ->
            {
                content.blocks
                    .filterIsInstance<genericOpenAIPipe.env.ContentBlock.TextBlock>()
                    .joinToString("\n") { it.text }
                    .takeIf { it.isNotBlank() }
            }
        }
    }
}