package genericOpenAIPipe.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Request body for Anthropic /v1/messages endpoint.
 * Anthropic's Messages API supports multi-modal input (text + images).
 *
 * @property model Anthropic model ID (e.g., "claude-3-5-sonnet-20241022")
 * @property messages List of conversation messages (user/assistant roles only)
 * @property system System prompt as plain string (used when systemBlocks is null)
 * @property systemBlocks Structured system content blocks with optional per-block
 *        cache control. Takes precedence over [system] when non-null.
 * @property maxTokens Maximum tokens to generate (REQUIRED by Anthropic)
 * @property thinking Anthropic thinking configuration, when reasoning is
 *        explicitly enabled or disabled
 * @property outputConfig Adaptive-thinking effort configuration
 * @property stream Enable streaming (SSE response) - not supported for /v1/messages
 * @property cacheControl Optional cache control to apply at the API level.
 *        The serializer places this on the last system block for explicit caching.
 *        Supported on: MiniMax-M2.7, M2.5, M2.1, M2 (NOT M3).
 *        MiniMax does NOT support TTL — always 5 minutes. Anthropic supports
 *        TTL of "5m" (default) or "1h".
 * @property systemPparam Optional system-level parameters
 * @property sessionId Session identifier for follow-up requests
 */
@Serializable(with = AnthropicMessagesRequestSerializer::class)
data class AnthropicMessagesRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    val systemBlocks: List<AnthropicSystemBlock>? = null,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val thinking: AnthropicThinkingConfig? = null,
    @SerialName("output_config")
    val outputConfig: AnthropicOutputConfig? = null,
    val stream: Boolean = false,
    val cacheControl: AnthropicCacheControl? = null,
    @SerialName("system_pparam")
    val systemPparam: Map<String, String>? = null,
    @SerialName("session_id")
    val sessionId: String? = null
)

@Serializable
private data class AnthropicMessagesRequestSurrogate(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val thinking: AnthropicThinkingConfig? = null,
    @SerialName("output_config")
    val outputConfig: AnthropicOutputConfig? = null,
    val stream: Boolean = false,
    @SerialName("cache_control")
    val cacheControl: AnthropicCacheControl? = null,
    @SerialName("system_pparam")
    val systemPparam: Map<String, String>? = null,
    @SerialName("session_id")
    val sessionId: String? = null
)

/**
 * Custom serializer for [AnthropicMessagesRequest] that emits `"system"` as a
 * JSON array of [AnthropicSystemBlock] when [AnthropicMessagesRequest.systemBlocks]
 * is non-null (matching Anthropic and MiniMax `/anthropic/v1/messages` wire format),
 * and deserializes either a string or array `"system"` field cleanly.
 */
object AnthropicMessagesRequestSerializer : KSerializer<AnthropicMessagesRequest>
{
    private val surrogateSerializer = AnthropicMessagesRequestSurrogate.serializer()
    private val systemBlocksSerializer = ListSerializer(AnthropicSystemBlock.serializer())

    override val descriptor: SerialDescriptor = surrogateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: AnthropicMessagesRequest)
    {
        val surrogate = AnthropicMessagesRequestSurrogate(
            model = value.model,
            messages = value.messages,
            system = if(value.systemBlocks != null) null else value.system,
            maxTokens = value.maxTokens,
            thinking = value.thinking,
            outputConfig = value.outputConfig,
            stream = value.stream,
            cacheControl = value.cacheControl,
            systemPparam = value.systemPparam,
            sessionId = value.sessionId
        )

        if(encoder !is JsonEncoder || value.systemBlocks == null)
        {
            encoder.encodeSerializableValue(surrogateSerializer, surrogate)
            return
        }

        val baseObj = encoder.json.encodeToJsonElement(surrogateSerializer, surrogate) as JsonObject
        val blocksElem = encoder.json.encodeToJsonElement(systemBlocksSerializer, value.systemBlocks)
        val merged = buildJsonObject {
            for((k, v) in baseObj)
            {
                if(k != "system")
                {
                    put(k, v)
                }
            }
            put("system", blocksElem)
        }
        encoder.encodeJsonElement(merged)
    }

    override fun deserialize(decoder: Decoder): AnthropicMessagesRequest
    {
        if(decoder !is JsonDecoder)
        {
            val surrogate = decoder.decodeSerializableValue(surrogateSerializer)
            return AnthropicMessagesRequest(
                model = surrogate.model,
                messages = surrogate.messages,
                system = surrogate.system,
                systemBlocks = null,
                maxTokens = surrogate.maxTokens,
                thinking = surrogate.thinking,
                outputConfig = surrogate.outputConfig,
                stream = surrogate.stream,
                cacheControl = surrogate.cacheControl,
                systemPparam = surrogate.systemPparam,
                sessionId = surrogate.sessionId
            )
        }

        val element = decoder.decodeJsonElement()
        val obj = element as JsonObject
        val systemElem = obj["system"]
        val systemBlocks = if(systemElem is JsonArray)
        {
            decoder.json.decodeFromJsonElement(systemBlocksSerializer, systemElem)
        }
        else
        {
            null
        }
        val systemString = if(systemElem != null && systemElem !is JsonArray)
        {
            systemElem.jsonPrimitive.content
        }
        else
        {
            null
        }

        val filteredObj = buildJsonObject {
            for((k, v) in obj)
            {
                if(k != "system" && k != "systemBlocks")
                {
                    put(k, v)
                }
            }
        }
        val surrogate = decoder.json.decodeFromJsonElement(surrogateSerializer, filteredObj)
        return AnthropicMessagesRequest(
            model = surrogate.model,
            messages = surrogate.messages,
            system = systemString,
            systemBlocks = systemBlocks,
            maxTokens = surrogate.maxTokens,
            thinking = surrogate.thinking,
            outputConfig = surrogate.outputConfig,
            stream = surrogate.stream,
            cacheControl = surrogate.cacheControl,
            systemPparam = surrogate.systemPparam,
            sessionId = surrogate.sessionId
        )
    }
}

/**
 * Anthropic Messages thinking configuration.
 *
 * The API accepts `adaptive`, `enabled`, and `disabled` variants. The
 * `budget_tokens` field is valid only with the `enabled` variant.
 */
@Serializable
data class AnthropicThinkingConfig(
    val type: String,
    @SerialName("budget_tokens")
    val budgetTokens: Int? = null
)

/**
 * Anthropic adaptive-thinking output configuration.
 */
@Serializable
data class AnthropicOutputConfig(
    val effort: String? = null
)

/**
 * Anthropic-style cache control for explicit prompt caching.
 *
 * Per the Anthropic spec, cache_control is placed on individual content blocks
 * (system items, message blocks, tool defs). The TTL controls how long the
 * cached prefix remains valid.
 *
 * **MiniMax vs Anthropic:**
 * - Anthropic: `{"type": "ephemeral", "ttl": "5m"}` or `{"type": "ephemeral", "ttl": "1h"}`
 * - MiniMax: `{"type": "ephemeral"}` — TTL field is NOT supported; cache is
 *   always 5 minutes and auto-refreshes on hit at no additional cost.
 *
 * @property type Must be "ephemeral" — the only supported cache type
 * @property ttl Time-to-live: "5m" (default, 5 minutes) or "1h" (1 hour).
 *        Note: MiniMax ignores this field and always uses 5 minutes.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnthropicCacheControl(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("type")
    val type: String = "ephemeral",
    val ttl: String? = null
)

/**
 * A single block within the Anthropic `system` array.
 *
 * Supports text content with optional per-block cache control.
 * When [cacheControl] is set, this block (and all preceding blocks in the
 * system array) become the cache breakpoint — subsequent message content
 * is not cached.
 *
 * @property type Block type — currently only "text" is supported
 * @property text The text content of this system block
 * @property cacheControl Optional cache control marker for explicit caching.
 *        When set, this block marks the end of the cacheable prefix.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnthropicSystemBlock(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("type")
    val type: String = "text",
    val text: String,
    @SerialName("cache_control")
    val cacheControl: AnthropicCacheControl? = null
)

/**
 * Anthropic message with role and content blocks.
 * Uses `type` field with values "user" or "assistant" (Anthropic API format).
 */
@Serializable
data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentBlock>
)

/**
 * Content blocks within Anthropic messages.
 * Supports text and image content.
 */
@Serializable
sealed class AnthropicContentBlock {

    /**
     * Text content block.
     *
     * @property text The text content
     */
    @Serializable
    @SerialName("text")
    data class TextBlock(
        val text: String,
        @SerialName("cache_control")
        val cacheControl: AnthropicCacheControl? = null
    ) : AnthropicContentBlock()

    /**
     * Image content block for multi-modal input.
     *
     * @property source Image source (base64 or URL)
     * @property cacheControl Optional explicit cache control breakpoint.
     */
    @Serializable
    @SerialName("image")
    data class ImageBlock(
        val source: ImageSource,
        @SerialName("cache_control")
        val cacheControl: AnthropicCacheControl? = null
    ) : AnthropicContentBlock()
}

/**
 * Source of an image for multi-modal content blocks.
 *
 * @property type Image source type ("base64" or "url")
 * @property mediaType MIME type of the image (e.g., "image/jpeg", "image/png")
 * @property data Image data (base64 encoded string or URL)
 */
@Serializable
data class ImageSource(
    val type: String,
    @SerialName("media_type")
    val mediaType: String,
    val data: String
)
