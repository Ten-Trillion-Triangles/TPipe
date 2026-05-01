package genericOpenAIPipe.api

import kotlinx.serialization.Serializable

/**
 * API mode for GenericOpenAI pipe to support different provider APIs.
 *
 * Sealed class allows future extension with associated data if needed,
 * unlike enum which cannot be extended without breaking binary compatibility.
 *
 * Default is [OpenAI] for backward compatibility with existing usage.
 */
@Serializable
sealed class ApiMode
{
    /**
     * OpenAI-compatible API mode (default).
     * Uses standard OpenAI chat completions format.
     */
    data object OpenAI : ApiMode()
    {
        /**
         * Default instance for backward compatibility.
         */
        val default: OpenAI = OpenAI
    }

    /**
     * Anthropic API mode.
     * Uses Anthropic messages format with different request/response structure.
     */
    data object Anthropic : ApiMode()

    companion object
    {
        /**
         * Default API mode for backward compatibility.
         */
        val DEFAULT: ApiMode = OpenAI
    }
}