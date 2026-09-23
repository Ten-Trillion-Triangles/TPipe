package genericOpenAIPipe.api

import com.TTT.Util.serialize
import genericOpenAIPipe.env.GenericOpenAIChatRequest
import genericOpenAIPipe.env.ReasoningConfig

class OpenAIRequestSerializer : RequestSerializer
{
    override fun serialize(
        request: GenericOpenAIChatRequest,
        apiMode: ApiMode,
        options: RequestSerializationOptions,
    ): String
    {
        // options is intentionally unused — this serializer owns the Chat
        // Completions and Anthropic wires, neither of which carries the
        // Mantle GPT-5.6 prompt-cache extensions. The Responses extension
        // lives in OpenAIResponsesRequestSerializer and reads options there.
        @Suppress("UNUSED_PARAMETER")
        val ignored = options
        return when(apiMode)
        {
            is ApiMode.OpenAI -> serialize(
                request.toOpenAIChatCompletionsRequest(),
                encodedefault = false
            )
            is ApiMode.Anthropic ->
            {
                val anthropicRequest = fromGenericOpenAI(request)
                serialize(anthropicRequest, encodedefault = false)
            }
            is ApiMode.OpenAIResponses ->
            {
                // OpenAIRequestSerializer does not own the Responses wire spec —
                // delegate to the dedicated Responses serializer so callers that
                // accidentally route Responses requests through this class still
                // emit a well-formed body.
                val responsesSerializer = OpenAIResponsesRequestSerializer()
                responsesSerializer.serialize(request, apiMode, options)
            }
        }
    }
}

/**
 * Converts provider-neutral reasoning state to the official Chat Completions
 * request fields.
 *
 * Chat Completions has no nested `reasoning` object and has no independent
 * reasoning-token budget. A token overload therefore selects the normal
 * reasoning effort and uses the requested value as the API's total completion
 * ceiling. That ceiling includes both reasoning and visible output according
 * to the OpenAI API contract.
 */
private fun GenericOpenAIChatRequest.toOpenAIChatCompletionsRequest(): GenericOpenAIChatRequest
{
    val reasoning = reasoning
    val mappedReasoningEffort = reasoning?.toChatCompletionsEffort() ?: this.reasoningEffort
    val completionBudget = when
    {
        reasoning?.maxTokens != null -> reasoning.maxTokens
        maxCompletionTokens != null -> maxCompletionTokens
        else -> null
    }
    val legacyMaxTokens = if(reasoning?.maxTokens != null) null else maxTokens

    return copy(
        maxTokens = legacyMaxTokens,
        maxCompletionTokens = completionBudget,
        reasoning = null,
        reasoningEffort = mappedReasoningEffort
    )
}

/**
 * Maps the shared reasoning representation to Chat Completions' effort values.
 */
private fun ReasoningConfig.toChatCompletionsEffort(): String? = when
{
    effort != null -> effort
    maxTokens != null -> "medium"
    enabled == true -> "medium"
    enabled == false -> "none"
    else -> null
}

/**
 * Converts a [GenericOpenAIChatRequest] to [AnthropicMessagesRequest] format.
 *
 * This is used when routing Anthropic-mode requests through the OpenAIRequestSerializer
 * (e.g., SDK-style clients that use OpenAIRequestSerializer as their serializer).
 *
 * When [GenericOpenAIChatRequest.cacheControl] is set, uses [systemBlocks] with
 * per-block cache_control on the last system block. Otherwise uses plain [system]
 * string for backward compatibility.
 *
 * **MiniMax note**: TTL field is not supported by MiniMax's /anthropic endpoint —
 * cache is always 5 minutes and auto-refreshes on hit. Direct Anthropic API
 * supports TTL of "5m" (default) or "1h".
 */
fun fromGenericOpenAI(request: GenericOpenAIChatRequest): AnthropicMessagesRequest
{
    val systemMessages = request.messages.filter { it.role == "system" }
    val systemMessage = systemMessages.joinToString("\n") { (it.content as? genericOpenAIPipe.env.MessageContent.TextContent)?.text ?: "" }
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

    // Apply cache control to the last system block when present.
    // This mirrors the logic in AnthropicRequestSerializer.buildSystemContent().
    // For MiniMax: cache_control has no TTL support — always 5 minutes.
    // For direct Anthropic: TTL "5m" (default) or "1h" is supported.
    val (systemStr, systemBlocks) = if(request.cacheControl != null)
    {
        val blocks = systemMessages.mapNotNull { msg ->
            val text = (msg.content as? genericOpenAIPipe.env.MessageContent.TextContent)?.text ?: return@mapNotNull null
            AnthropicSystemBlock(text = text)
        }.toMutableList()

        if(blocks.isEmpty() && systemMessage.isNotEmpty())
        {
            blocks.add(AnthropicSystemBlock(text = systemMessage))
        }

        if(blocks.isNotEmpty())
        {
            val lastIdx = blocks.lastIndex
            blocks[lastIdx] = blocks[lastIdx].copy(
                cacheControl = AnthropicCacheControl(
                    type = request.cacheControl.type,
                    ttl = request.cacheControl.ttl
                )
            )
            null to blocks
        }
        else
        {
            null to null
        }
    }
    else
    {
        systemMessage.takeIf { it.isNotEmpty() } to null
    }

    val maxTokens = request.maxTokens ?: request.maxCompletionTokens ?: 4096
    val reasoning = request.reasoning.toAnthropicReasoning(maxTokens)

    return AnthropicMessagesRequest(
        model = request.model,
        messages = anthropicMessages,
        system = systemStr,
        systemBlocks = systemBlocks,
        maxTokens = maxTokens,
        thinking = reasoning.thinking,
        outputConfig = reasoning.outputConfig,
        stream = request.stream,
        cacheControl = null  // cacheControl applied via systemBlocks; not a top-level field
    )
}
