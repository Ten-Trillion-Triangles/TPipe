package genericOpenAIPipe.api

import com.TTT.Util.serialize
import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.GenericOpenAIChatRequest
import genericOpenAIPipe.env.MessageContent

class AnthropicRequestSerializer : RequestSerializer
{
    override fun serialize(request: GenericOpenAIChatRequest, apiMode: ApiMode): String
    {
        require(apiMode is ApiMode.Anthropic)
        { "AnthropicRequestSerializer only supports ApiMode.Anthropic, got $apiMode" }

        val maxTokens = request.maxTokens
            ?: request.maxCompletionTokens
            ?: throw IllegalStateException(
                "maxTokens is REQUIRED for Anthropic API. " +
                "Neither maxTokens nor maxCompletionTokens was provided in the request."
            )

        val systemMessages = request.messages.filter { it.role == "system" }
        val systemMessage = systemMessages.firstOrNull()
            ?.let { extractTextContent(it.content) }
            ?.takeIf { it.isNotBlank() }

        val anthropicMessages = request.messages
            .filter { it.role != "system" }
            .map { chatMessage -> convertToAnthropicMessage(chatMessage) }

        val anthropicRequest = AnthropicMessagesRequest(
            model = request.model,
            messages = anthropicMessages,
            system = systemMessage,
            maxTokens = maxTokens,
            stream = request.stream,
            sessionId = null
        )

        val jsonStr = serialize(anthropicRequest, encodedefault = false)
        return jsonStr
    }

private fun convertToAnthropicMessage(chatMessage: ChatMessage): AnthropicMessage
    {
        val content = extractContentBlocks(chatMessage.content)

        return when(chatMessage.role)
        {
            "user" -> AnthropicMessage(role = "user", content = content)
            "assistant" -> AnthropicMessage(role = "assistant", content = content)
            else -> throw IllegalArgumentException(
                "Anthropic only supports 'user' and 'assistant' roles in messages. "
                + "Got role: '${chatMessage.role}'"
            )
        }
    }

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
                content.blocks.mapNotNull { block ->
                    when(block)
                    {
                        is genericOpenAIPipe.env.ContentBlock.TextBlock ->
                            AnthropicContentBlock.TextBlock(text = block.text)
                        is genericOpenAIPipe.env.ContentBlock.ImageUrlBlock ->
                            null
                    }
                }.ifEmpty {
                    listOf(AnthropicContentBlock.TextBlock(text = ""))
                }
            }
            is MessageContent.PlainContent ->
            {
                listOf(AnthropicContentBlock.TextBlock(text = content.content))
            }
        }
    }

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
            is MessageContent.PlainContent -> content.content
        }
    }
}