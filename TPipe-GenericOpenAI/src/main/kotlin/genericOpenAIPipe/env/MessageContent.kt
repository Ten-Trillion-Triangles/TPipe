package genericOpenAIPipe.env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class MessageContent {
    @Serializable
    @SerialName("text")
    data class TextContent(val text: String) : MessageContent()

    @Serializable
    @SerialName("multi")
    data class MultimodalContent(val blocks: List<ContentBlock>) : MessageContent()

    /**
     * Handles plain string content returned by some providers (e.g., MiniMax).
     * Some OpenAI-compatible APIs return `"content": "plain string"` instead of
     * the structured `{"text": "..."}` format.
     */
    @Serializable
    @SerialName("plain")
    data class PlainContent(val content: String) : MessageContent()
}