package genericOpenAIPipe.env

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: MessageContent
)