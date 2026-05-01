package genericOpenAIPipe.api

import genericOpenAIPipe.env.GenericOpenAIChatResponse
import kotlinx.serialization.json.Json

/**
 * Strategy interface for parsing raw HTTP response bodies from GenericOpenAI-compatible APIs.
 *
 * Different API providers (OpenAI, Anthropic, etc.) may return responses in slightly
 * different formats. This interface allows pluggable parsing strategies so the pipe
 * can work with any OpenAI-compatible provider.
 *
 * Implementations are responsible for:
 * - Converting the raw JSON response string into a [GenericOpenAIChatResponse]
 * - Normalizing provider-specific response formats to the standard format
 * - Handling provider-specific error response structures
 *
 * The interface does NOT handle HTTP transport - that remains the responsibility
 * of [GenericOpenAIPipe].
 *
 * @see ApiMode for supported API modes
 * @see GenericOpenAIChatResponse for the normalized response format
 */
interface ResponseParser
{
    /**
     * Parses a raw HTTP response body into a normalized [GenericOpenAIChatResponse].
     *
     * @param response The raw JSON response body string from the HTTP call
     * @param apiMode The target [ApiMode] determining which API format to parse
     * @return A [GenericOpenAIChatResponse] normalized for the Pipe base class
     * @throws P2PException If parsing fails or response indicates an error
     */
    fun parse(response: String, apiMode: ApiMode): GenericOpenAIChatResponse

    companion object Factory
    {
        private val openAIResponseParser = OpenAIResponseParser()
        private val anthropicResponseParser = AnthropicResponseParser(Json {

        })

        fun create(): ResponseParser = object : ResponseParser
        {
            override fun parse(response: String, apiMode: ApiMode): GenericOpenAIChatResponse
            {
                return when(apiMode)
                {
                    is ApiMode.OpenAI -> openAIResponseParser.parse(response, apiMode)
                    is ApiMode.Anthropic -> anthropicResponseParser.parse(response, apiMode)
                }
            }
        }
    }
}