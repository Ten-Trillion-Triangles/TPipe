package genericOpenAIPipe.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 * @property stream Enable streaming (SSE response) - not supported for /v1/messages
 * @property cacheControl Optional cache control to apply at the API level.
 *        The serializer places this on the last system block for explicit caching.
 *        Supported on: MiniMax-M2.7, M2.5, M2.1, M2 (NOT M3).
 *        MiniMax does NOT support TTL — always 5 minutes. Anthropic supports
 *        TTL of "5m" (default) or "1h".
 * @property systemPparam Optional system-level parameters
 * @property sessionId Session identifier for follow-up requests
 */
@Serializable
data class AnthropicMessagesRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    val systemBlocks: List<AnthropicSystemBlock>? = null,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val stream: Boolean = false,
    val cacheControl: AnthropicCacheControl? = null,
    @SerialName("system_pparam")
    val systemPparam: Map<String, String>? = null,
    @SerialName("session_id")
    val sessionId: String? = null
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
@Serializable
data class AnthropicCacheControl(
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
@Serializable
data class AnthropicSystemBlock(
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
        val text: String
    ) : AnthropicContentBlock()

    /**
     * Image content block for multi-modal input.
     *
     * @property source Image source (base64 or URL)
     */
    @Serializable
    @SerialName("image")
    data class ImageBlock(
        val source: ImageSource
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