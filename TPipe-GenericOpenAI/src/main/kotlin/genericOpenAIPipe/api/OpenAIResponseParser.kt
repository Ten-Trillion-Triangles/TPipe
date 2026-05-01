package genericOpenAIPipe.api

import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import com.TTT.Util.deserialize
import genericOpenAIPipe.env.GenericOpenAIChatResponse
import genericOpenAIPipe.env.GenericOpenAIErrorResponse

/**
 * OpenAI-compatible [ResponseParser] implementation.
 *
 * Parses raw HTTP response bodies from OpenAI-compatible /v1/chat/completions endpoints
 * into [GenericOpenAIChatResponse] objects. Handles both success and error responses,
 * mapping API error types to appropriate [P2PError] codes.
 *
 * This parser follows the standard OpenAI response format and is suitable for most
 * OpenAI-compatible providers. For providers with significantly different response
 * formats, a custom [ResponseParser] implementation may be required.
 *
 * @see ApiMode.OpenAI for the default OpenAI-compatible mode
 * @see GenericOpenAIChatResponse for the normalized response format
 */
class OpenAIResponseParser : ResponseParser
{

    /**
     * Parses a raw HTTP response body into a normalized [GenericOpenAIChatResponse].
     *
     * @param response The raw JSON response body string from the HTTP call
     * @param apiMode The target [ApiMode] determining which API format to parse
     * @return A [GenericOpenAIChatResponse] normalized for the Pipe base class
     * @throws P2PException If parsing fails or response indicates an error
     */
    override fun parse(response: String, apiMode: ApiMode): GenericOpenAIChatResponse
    {
        // Check for error responses BEFORE deserializing as success
        val errorResponse = try { deserialize<GenericOpenAIErrorResponse>(response) } catch(e: Exception) { null }
        if(errorResponse != null && errorResponse.error.message.isNotEmpty())
        {
            val errorMessage = errorResponse.error.message
            val errorType = errorResponse.error.type
            val errorCode = errorResponse.error.code

            val p2pError = when
            {
                errorType == "authentication_error" || errorCode == "401" -> P2PError.auth
                errorType == "rate_limit_error" || errorCode == "429" -> P2PError.transport
                errorType == "invalid_request_error" || errorType == "invalid_api_key" || errorCode == "400" -> P2PError.prompt
                errorType == "api_error" || errorType == "server_error" || errorCode?.startsWith("5") == true -> P2PError.transport
                else -> P2PError.transport
            }
            throw P2PException(p2pError, "GenericOpenAI API error: ${errorResponse.error.type}", Exception(errorMessage))
        }

        val parsedResponse: GenericOpenAIChatResponse = deserialize(response)
            ?: throw P2PException(P2PError.json, "Failed to deserialize GenericOpenAI chat response: $response", Exception("Deserialization failed"))

        return parsedResponse
    }
}

/**
 * Error response structure from GenericOpenAI-compatible APIs.
 *
 * @property error The error details returned by the API
 */
@kotlinx.serialization.Serializable
data class GenericOpenAIErrorResponse(
    val error: GenericOpenAIError
)

/**
 * Individual error detail from API error responses.
 *
 * @property message Human-readable error message
 * @property type Error type identifier (e.g., "authentication_error", "rate_limit_error")
 * @property code Machine-readable error code (e.g., "401", "429", "400")
 */
@kotlinx.serialization.Serializable
data class GenericOpenAIError(
    val message: String,
    val type: String = "",
    val code: String? = null
)