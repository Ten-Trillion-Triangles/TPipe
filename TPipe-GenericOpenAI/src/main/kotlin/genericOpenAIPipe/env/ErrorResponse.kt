package genericOpenAIPipe.env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Error response from Generic OpenAI-compatible API.
 *
 * @property error Error details
 */
@Serializable
data class GenericOpenAIErrorResponse(
    val error: GenericOpenAIError
)

/**
 * Generic OpenAI error details.
 *
 * @property message Human-readable error message
 * @property code Error code (if available)
 * @property type Error type
 * @property param Parameter that caused the error (if available)
 */
@Serializable
data class GenericOpenAIError(
    val message: String,
    val code: String? = null,
    val type: String? = null,
    val param: String? = null
)