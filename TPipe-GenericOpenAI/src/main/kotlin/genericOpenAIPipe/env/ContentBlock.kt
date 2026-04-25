package genericOpenAIPipe.env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI content block types for multimodal messages.
 * @see <a href="https://platform.openai.com/docs/api-reference/chat/completions">OpenAI Chat Completions</a>
 */
@Serializable
sealed class ContentBlock {
    /**
     * Text content block.
     */
    @Serializable
    @SerialName("text")
    data class TextBlock(val text: String) : ContentBlock()

    /**
     * Image URL content block for multimodal input.
     * @property url Either a data URI (data:image/png;base64,...) or an HTTP URL
     * @property detail Detail level: "auto", "low", or "high"
     */
    @Serializable
    @SerialName("image_url")
    data class ImageUrlBlock(
        val url: String,
        val detail: String? = "auto"
    ) : ContentBlock()
}

/**
 * Container for content block serialization.
 * OpenAI API expects content as array with type discriminator.
 */
@Serializable
data class ContentBlockContainer(
    val type: String,
    val text: String? = null,
    @SerialName("image_url")
    val imageUrl: ImageUrlDetail? = null
)

/**
 * Image URL detail for image_url content blocks.
 */
@Serializable
data class ImageUrlDetail(
    val url: String,
    val detail: String? = "auto"
)