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
     * OpenAI-compatible chat completions API mode (default).
     * Uses standard OpenAI chat completions format at `/v1/chat/completions`.
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
     * Uses Anthropic messages format at `/anthropic/v1/messages`.
     */
    data object Anthropic : ApiMode()

    /**
     * OpenAI Responses API mode.
     * Uses the OpenAI Responses wire spec at `/v1/responses` with `input` items,
     * top-level `instructions`, and a streaming protocol driven by
     * `response.created` / `response.output_text.delta` / `response.completed`
     * events. Same Bearer-token auth as the chat-completions mode.
     *
     * @see <a href="https://platform.openai.com/docs/api-reference/responses">OpenAI Responses API</a>
     */
    data object OpenAIResponses : ApiMode()

    companion object
    {
        /**
         * Default API mode for backward compatibility.
         */
        val DEFAULT: ApiMode by lazy { OpenAI }
    }
}
