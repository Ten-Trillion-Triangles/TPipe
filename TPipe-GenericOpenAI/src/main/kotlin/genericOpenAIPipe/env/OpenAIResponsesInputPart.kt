package genericOpenAIPipe.env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One part of a `Message` content array in an OpenAI Responses input item.
 *
 * Mirrors the OpenAI Responses wire spec where the `content` of a `Message`
 * is a list of typed parts (`input_text`, `input_image`, etc.).
 *
 * @see <a href="https://platform.openai.com/docs/api-reference/responses">OpenAI Responses API</a>
 */
@Serializable
sealed class OpenAIResponsesInputPart
{
    /**
     * Plain text part of a `Message`.
     *
     * @property text The text content
     */
    @Serializable
    @SerialName("input_text")
    data class InputTextPart(val text: String) : OpenAIResponsesInputPart()

    /**
     * Image part of a `Message` (URL or `data:` URI).
     *
     * @property imageUrl Either a remote URL or a base64 `data:` URI
     * @property detail Detail level: "auto", "low", or "high"
     */
    @Serializable
    @SerialName("input_image")
    data class InputImagePart(
        val imageUrl: String,
        val detail: String? = "auto"
    ) : OpenAIResponsesInputPart()
}
