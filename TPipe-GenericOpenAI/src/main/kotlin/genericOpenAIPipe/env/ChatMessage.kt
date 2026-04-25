package genericOpenAIPipe.env

import kotlinx.serialization.Serializable

/**
 * Represents a single message in a conversation.
 *
 * @property role Message role: system, user, assistant, developer, or tool
 * @property content Message content (text or can be extended for multimodal)
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)