package genericOpenAIPipe.env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Streaming chunk from Anthropic's /v1/messages SSE stream.
 *
 * Each chunk follows the format:
 * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
 *
 * @property type The event type (e.g., "content_block_delta")
 * @property index The index of the content block this delta belongs to
 * @property delta The delta content (text_delta or input_json_delta)
 */
@Serializable
data class AnthropicStreamingChunk(
    val type: String,
    val index: Int,
    val delta: AnthropicDelta
)

/**
 * Sealed class representing different types of deltas in Anthropic streaming responses.
 *
 * @property type The delta type discriminator
 */
@Serializable
sealed class AnthropicDelta
{
    /**
     * A text delta containing a fragment of the assistant's response.
     *
     * @property type Delta type (always "text_delta")
     * @property text The text content delta
     */
    @Serializable
    @SerialName("text_delta")
    data class TextDelta(
        val type: String = "text_delta",
        val text: String
    ) : AnthropicDelta()

    /**
     * A JSON delta for partial JSON structured output.
     *
     * @property type Delta type (always "input_json_delta")
     * @property partialJson The partial JSON string being accumulated
     */
    @Serializable
    @SerialName("input_json_delta")
    data class InputJsonDelta(
        val type: String = "input_json_delta",
        @SerialName("partial_json")
        val partialJson: String
    ) : AnthropicDelta()

    /**
     * A thinking delta containing a fragment of the model's thinking (for models like MiniMax-M2.7).
     *
     * @property type Delta type (always "thinking_delta")
     * @property thinking The thinking content delta
     */
    @Serializable
    @SerialName("thinking_delta")
    data class ThinkingDelta(
        val type: String = "thinking_delta",
        val thinking: String
    ) : AnthropicDelta()
}

/**
 * Sealed class representing all possible events in an Anthropic SSE stream.
 */
@Serializable
sealed class AnthropicStreamEvent
{
    /**
     * A content block delta event — carries text or JSON deltas for a specific block index.
     *
     * @property chunk The streaming chunk containing the delta
     */
    data class ContentBlockDelta(
        val chunk: AnthropicStreamingChunk
    ) : AnthropicStreamEvent()

    /**
     * A message delta event — signals the end of a message with final usage stats.
     *
     * @property stopReason The reason generation stopped (e.g., "end_turn", "max_tokens")
     * @property usage Token usage statistics for the complete message
     */
    data class MessageDelta(
        @SerialName("stop_reason")
        val stopReason: String?,
        val usage: AnthropicUsageInfo?
    ) : AnthropicStreamEvent()

    /**
     * An error event from the Anthropic streaming endpoint.
     *
     * @property type The error type (e.g., "invalid_request_error")
     * @property error The error message
     */
    data class Error(
        val type: String,
        val error: String
    ) : AnthropicStreamEvent()

    /**
     * The terminal [DONE] signal indicating the stream has ended normally.
     */
    @Serializable
    data object Done : AnthropicStreamEvent()

    /**
     * An unknown or unrecognized event type.
     * This allows the stream to continue rather than prematurely terminating
     * when Anthropic introduces new event types.
     */
    @Serializable
    data object Unknown : AnthropicStreamEvent()
}

/**
 * Token usage statistics for an Anthropic streaming message.
 *
 * @property inputTokens Number of tokens in the input prompt
 * @property outputTokens Number of tokens in the model response
 */
@Serializable
data class AnthropicUsageInfo(
    @SerialName("input_tokens")
    val inputTokens: Int,
    @SerialName("output_tokens")
    val outputTokens: Int
)

/**
 * Error response from Anthropic's streaming endpoint.
 *
 * @property error The error details
 */
@Serializable
data class AnthropicErrorResponse(
    val error: AnthropicErrorDetail
)

/**
 * Anthropic error detail fields.
 *
 * @property type The error type (e.g., "invalid_request_error", "authentication_error")
 * @property message Human-readable error message
 */
@Serializable
data class AnthropicErrorDetail(
    val type: String,
    val message: String
)