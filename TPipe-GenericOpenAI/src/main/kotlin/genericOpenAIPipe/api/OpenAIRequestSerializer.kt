package genericOpenAIPipe.api

import com.TTT.Util.serialize
import genericOpenAIPipe.env.GenericOpenAIChatRequest

class OpenAIRequestSerializer : RequestSerializer
{
    override fun serialize(request: GenericOpenAIChatRequest, apiMode: ApiMode): String
    {
        return when(apiMode)
        {
            is ApiMode.OpenAI -> serialize(request, encodedefault = false)
            is ApiMode.Anthropic ->
            {
                val anthropicRequest = AnthropicMessagesRequest.fromGenericOpenAI(request)
                serialize(anthropicRequest, encodedefault = false)
            }
        }
    }
}

fun AnthropicMessagesRequest.Companion.fromGenericOpenAI(request: GenericOpenAIChatRequest): AnthropicMessagesRequest
{
    val systemMessage = request.messages.filter { it.role == "system" }.joinToString("\n") { (it.content as? genericOpenAIPipe.env.MessageContent.TextContent)?.text ?: "" }
    val nonSystemMessages = request.messages.filter { it.role != "system" }

    val anthropicMessages = nonSystemMessages.map { msg ->
        when(msg.role)
        {
            "user" -> AnthropicMessage.UserMessage(
                content = listOf(AnthropicContentBlock.TextBlock((msg.content as genericOpenAIPipe.env.MessageContent.TextContent).text))
            )
            "assistant" -> AnthropicMessage.AssistantMessage(
                content = listOf(AnthropicContentBlock.TextBlock((msg.content as genericOpenAIPipe.env.MessageContent.TextContent).text))
            )
            else -> throw IllegalArgumentException("Unsupported role: ${msg.role}")
        }
    }

    return AnthropicMessagesRequest(
        model = request.model,
        messages = anthropicMessages,
        system = systemMessage.takeIf { it.isNotEmpty() },
        maxTokens = request.maxTokens ?: request.maxCompletionTokens ?: 4096,
        stream = request.stream
    )
}