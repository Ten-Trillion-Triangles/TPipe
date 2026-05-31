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

    val anthropicMessages = nonSystemMessages.mapNotNull { msg ->
        val textContent = (msg.content as? genericOpenAIPipe.env.MessageContent.TextContent)?.text ?: return@mapNotNull null
        when(msg.role)
        {
            "user" -> AnthropicMessage(role = "user", content = listOf(AnthropicContentBlock.TextBlock(text = textContent)))
            "assistant" -> AnthropicMessage(role = "assistant", content = listOf(AnthropicContentBlock.TextBlock(text = textContent)))
            else -> null
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