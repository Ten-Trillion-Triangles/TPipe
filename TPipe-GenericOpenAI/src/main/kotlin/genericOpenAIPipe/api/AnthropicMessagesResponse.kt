package genericOpenAIPipe.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from Anthropic /v1/messages API.
 *
 * Anthropic's response format differs from OpenAI:
 * - Uses `content` array directly (not `choices[].message`)
 * - Uses `stop_reason` instead of `finish_reason`
 * - Uses `input_tokens` and `output_tokens` instead of `prompt_tokens` and `completion_tokens`
 *
 * @property id Unique identifier for this message
 * @property type Object type (always "message" for non-streaming)
 * @property role Role of the message sender (always "assistant")
 * @property content List of content blocks (text, thinking, etc.)
 * @property model Model ID used for this completion
 * @property stopReason The reason why generation stopped (e.g., "end_turn", "max_tokens")
 * @property usage Token usage statistics
 * @property sessionId Session identifier for follow-up requests (not from API, added for convenience)
 */
@Serializable
data class AnthropicMessagesResponse(
    val id: String,
    val type: String = "message",
    val role: String = "assistant",
    val content: List<ResponseContentBlock>,
    val model: String,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    val usage: UsageInfo,
    // Not from API response - added for session tracking
    @kotlinx.serialization.Transient
    val sessionId: String? = null
)

/**
 * Sealed class representing different types of content blocks in Anthropic response.
 *
 * Anthropic supports multiple content block types:
 * - [TextContentBlock]: Plain text content
 * - [ThinkingBlock]: Thinking content (for models that support it)
 *
 * @property type The type of content block
 */
@Serializable
sealed class ResponseContentBlock
{
    /**
     * A text content block.
     *
     * @property type Content type (always "text")
     * @property text The text content
     */
    @Serializable
    data class TextContentBlock(
        val type: String = "text",
        val text: String
    ) : ResponseContentBlock()

    /**
     * A thinking content block (for models that support extended thinking).
     *
     * @property type Content type (e.g., "thinking")
     * @property thinking The thinking content
     */
    @Serializable
    data class ThinkingBlock(
        val type: String,
        val thinking: String
    ) : ResponseContentBlock()
}

/**
 * Token usage statistics for Anthropic API requests.
 *
 * @property inputTokens Number of tokens in the input prompt
 * @property outputTokens Number of tokens in the model response
 */
@Serializable
data class UsageInfo(
    @SerialName("input_tokens")
    val inputTokens: Int,
    @SerialName("output_tokens")
    val outputTokens: Int
)