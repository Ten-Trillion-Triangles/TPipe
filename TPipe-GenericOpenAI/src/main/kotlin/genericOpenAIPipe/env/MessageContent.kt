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
}