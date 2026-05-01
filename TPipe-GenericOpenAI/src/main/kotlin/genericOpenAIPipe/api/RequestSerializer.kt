package genericOpenAIPipe.api

import genericOpenAIPipe.env.GenericOpenAIChatRequest

/**
 * Strategy interface for serializing [GenericOpenAIChatRequest] to JSON for different API modes.
 *
 * Implementations handle provider-specific serialization requirements:
 * - [ApiMode.OpenAI]: Standard OpenAI chat completions format
 * - [ApiMode.Anthropic]: Anthropic messages API format
 *
 * This follows the Strategy pattern, allowing runtime selection of serialization
 * behavior based on the target API provider.
 */
interface RequestSerializer
{
    /**
     * Serializes a chat request to a JSON string suitable for HTTP POST body.
     *
     * @param request The normalized [GenericOpenAIChatRequest] to serialize
     * @param apiMode The target [ApiMode] determining serialization format
     * @return JSON string ready for HTTP POST body
     */
    fun serialize(request: GenericOpenAIChatRequest, apiMode: ApiMode): String

    companion object Factory
    {
        private val openAIRequestSerializer = OpenAIRequestSerializer()
        private val anthropicRequestSerializer = AnthropicRequestSerializer()

        fun create(): RequestSerializer = object : RequestSerializer
        {
            override fun serialize(request: GenericOpenAIChatRequest, apiMode: ApiMode): String
            {
                return when(apiMode)
                {
                    is ApiMode.OpenAI -> openAIRequestSerializer.serialize(request, apiMode)
                    is ApiMode.Anthropic -> anthropicRequestSerializer.serialize(request, apiMode)
                }
            }
        }
    }
}