package genericOpenAIPipe.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for Anthropic /v1/messages endpoint.
 * Anthropic's Messages API supports multi-modal input (text + images).
 *
 * @property model Anthropic model ID (e.g., "claude-3-5-sonnet-20241022")
 * @property messages List of conversation messages (user/assistant roles only)
 * @property system System prompt (passed separately, not in messages)
 * @property maxTokens Maximum tokens to generate (REQUIRED by Anthropic)
 * @property stream Enable streaming (SSE response) - not supported for /v1/messages
 * @property systemPparam Optional system-level parameters
 * @property sessionId Session identifier for follow-up requests
 */
@Serializable
data class AnthropicMessagesRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val stream: Boolean = false,
    @SerialName("system_pparam")
    val systemPparam: Map<String, String>? = null,
    @SerialName("session_id")
    val sessionId: String? = null
)

/**
 * Anthropic message with role and content blocks.
 * Note: Anthropic only supports "user" and "assistant" roles.
 * System prompts are passed separately in the request, not in messages.
 */
@Serializable
sealed class AnthropicMessage {

    /**
     * User message from the conversation.
     *
     * @property content List of content blocks (text and/or images)
     */
    @Serializable
    @SerialName("user")
    data class UserMessage(
        val content: List<AnthropicContentBlock>
    ) : AnthropicMessage()

    /**
     * Assistant message from the model.
     *
     * @property content List of content blocks (text and/or tool use results)
     */
    @Serializable
    @SerialName("assistant")
    data class AssistantMessage(
        val content: List<AnthropicContentBlock>
    ) : AnthropicMessage()
}

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