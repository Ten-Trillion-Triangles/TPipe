package genericOpenAIPipe.api

import com.TTT.Util.serialize
import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.GenericOpenAIChatRequest
import genericOpenAIPipe.env.MessageContent
import genericOpenAIPipe.env.ReasoningConfig

class AnthropicRequestSerializer : RequestSerializer
{
    override fun serialize(
        request: GenericOpenAIChatRequest,
        apiMode: ApiMode,
        options: RequestSerializationOptions,
    ): String
    {
        // options is intentionally unused — Anthropic wire has its own cache
        // control mechanism (request.cacheControl) and does not honor Mantle
        // GPT-5.6 prompt-cache extensions.
        @Suppress("UNUSED_PARAMETER")
        val ignored = options
        require(apiMode is ApiMode.Anthropic)
        { "AnthropicRequestSerializer only supports ApiMode.Anthropic, got $apiMode" }

        val maxTokens = request.maxTokens
            ?: request.maxCompletionTokens
            ?: throw IllegalStateException(
                "maxTokens is REQUIRED for Anthropic API. " +
                "Neither maxTokens nor maxCompletionTokens was provided in the request."
            )

        val (system, systemBlocks) = buildSystemContent(request)

        val anthropicMessages = request.messages
            .filter { it.role != "system" }
            .map { chatMessage -> convertToAnthropicMessage(chatMessage) }

        val cacheControl = request.cacheControl?.let {
            AnthropicCacheControl(type = it.type, ttl = it.ttl)
        }
        val reasoning = request.reasoning.toAnthropicReasoning(maxTokens)

        val anthropicRequest = AnthropicMessagesRequest(
            model = request.model,
            messages = anthropicMessages,
            system = system,
            systemBlocks = systemBlocks,
            maxTokens = maxTokens,
            thinking = reasoning.thinking,
            outputConfig = reasoning.outputConfig,
            stream = request.stream,
            cacheControl = cacheControl,
            sessionId = null
        )

        val jsonStr = serialize(anthropicRequest, encodedefault = false)
        return jsonStr
    }

    /**
     * Builds the system content for the Anthropic request.
     *
     * When [GenericOpenAIChatRequest.cacheControl] is set, uses [systemBlocks]
     * (structured list) so the cache control can be applied to a specific block.
     * When no cache control is set, falls back to plain string [system] for
     * backward compatibility.
     *
     * Cache breakpoint placement: when cacheControl is set, it is applied to the
     * LAST system block. Per MiniMax and Anthropic spec, cache is cumulative from
     * all preceding blocks — placing it on the last block caches the full system.
     *
     * **MiniMax note**: TTL field is not supported by MiniMax's /anthropic endpoint.
     * The serializer omits ttl when targeting MiniMax, resulting in default 5-min
     * cache lifetime with auto-refresh on hit. Anthropic's direct API supports
     * explicit "5m" or "1h" TTL.
     */
    private fun buildSystemContent(request: GenericOpenAIChatRequest): Pair<String?, List<AnthropicSystemBlock>?>
    {
        val systemMessages = request.messages.filter { it.role == "system" }
        val systemText = systemMessages.firstOrNull()
            ?.let { extractTextContent(it.content) }
            ?.takeIf { it.isNotBlank() }

        if(request.cacheControl == null)
        {
            return systemText to null
        }

        val blocks = systemMessages.mapNotNull { msg ->
            val text = extractTextContent(msg.content) ?: return@mapNotNull null
            AnthropicSystemBlock(text = text)
        }.toMutableList()

        if(blocks.isEmpty() && systemText != null)
        {
            blocks.add(AnthropicSystemBlock(text = systemText))
        }

        if(blocks.isEmpty())
        {
            return null to null
        }

        // Apply cache control to the LAST system block.
        // This is the standard pattern: cache the full system prompt prefix.
        // Per Anthropic/MiniMax spec: cache is cumulative from all preceding blocks.
        val lastIndex = blocks.lastIndex
        blocks[lastIndex] = blocks[lastIndex].copy(
            cacheControl = AnthropicCacheControl(
                type = "ephemeral",
                ttl = request.cacheControl.ttl
            )
        )

        return null to blocks
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

/**
 * Provider-specific Anthropic reasoning fields produced from the shared
 * reasoning configuration.
 */
internal data class AnthropicReasoningSettings(
    val thinking: AnthropicThinkingConfig?,
    val outputConfig: AnthropicOutputConfig?
)

/**
 * Maps the shared reasoning configuration to the Anthropic Messages API.
 *
 * Boolean and string overloads use the current adaptive-thinking contract.
 * The integer overload intentionally uses Anthropic's legacy/manual thinking
 * contract because that is the only wire representation with a reasoning
 * budget. Anthropic requires that budget to be at least 1024 and below the
 * request's total max_tokens value.
 */
internal fun ReasoningConfig?.toAnthropicReasoning(maxTokens: Int): AnthropicReasoningSettings
{
    val config = this ?: return AnthropicReasoningSettings(null, null)

    if(config.effort != null)
    {
        require(config.effort in ANTHROPIC_REASONING_EFFORTS)
        {
            "Unsupported Anthropic reasoning effort '${config.effort}'."
        }
        return AnthropicReasoningSettings(
            thinking = AnthropicThinkingConfig(type = "adaptive"),
            outputConfig = AnthropicOutputConfig(effort = config.effort)
        )
    }

    if(config.maxTokens != null)
    {
        require(config.maxTokens >= ANTHROPIC_MIN_REASONING_BUDGET)
        {
            "Anthropic reasoning budget must be at least $ANTHROPIC_MIN_REASONING_BUDGET tokens."
        }
        require(config.maxTokens < maxTokens)
        {
            "Anthropic reasoning budget must be less than max_tokens."
        }
        return AnthropicReasoningSettings(
            thinking = AnthropicThinkingConfig(
                type = "enabled",
                budgetTokens = config.maxTokens
            ),
            outputConfig = null
        )
    }

    return when(config.enabled)
    {
        true -> AnthropicReasoningSettings(
            thinking = AnthropicThinkingConfig(type = "adaptive"),
            outputConfig = null
        )
        false -> AnthropicReasoningSettings(
            thinking = AnthropicThinkingConfig(type = "disabled"),
            outputConfig = null
        )
        null -> AnthropicReasoningSettings(null, null)
    }
}

private val ANTHROPIC_REASONING_EFFORTS = setOf("low", "medium", "high", "xhigh", "max")
private const val ANTHROPIC_MIN_REASONING_BUDGET = 1024
