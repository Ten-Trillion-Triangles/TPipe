package genericOpenAIPipe.api

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * Endpoint paths used by [genericOpenAIPipe.GenericOpenAIPipe] for each wire mode.
 *
 * The default profile uses the standard Anthropic `/v1/messages` route.
 * [localV1] matches local OpenAI-compatible servers that expose every protocol
 * below beneath a shared `/v1` prefix.
 *
 * @param chatCompletionsPath Path for [ApiMode.OpenAI] chat completions.
 * @param responsesPath Path for [ApiMode.OpenAIResponses].
 * @param anthropicMessagesPath Path for [ApiMode.Anthropic] messages.
 */
@Serializable
data class GenericOpenAIEndpointProfile(
    val chatCompletionsPath: String = "/chat/completions",
    val responsesPath: String = "/responses",
    val anthropicMessagesPath: String = "/v1/messages"
)
{
    init
    {
        validatePath("chatCompletionsPath", chatCompletionsPath)
        validatePath("responsesPath", responsesPath)
        validatePath("anthropicMessagesPath", anthropicMessagesPath)
    }

    private fun validatePath(name: String, path: String)
    {
        require(path.isNotBlank()) { "$name cannot be blank" }
        require(path.startsWith("/") && !path.startsWith("//")) {
            "$name must be an absolute path"
        }
        require(!path.contains('?') && !path.contains('#') && !path.contains("://")) {
            "$name must not contain a query, fragment, or host component"
        }
        val uri = runCatching { URI(path) }.getOrNull()
        require(uri != null && uri.scheme == null && uri.rawAuthority == null) {
            "$name must be a valid absolute path without a host component"
        }
    }

    companion object
    {
        /**
         * Standard GenericOpenAI endpoint defaults.
         */
        val DEFAULT: GenericOpenAIEndpointProfile = GenericOpenAIEndpointProfile()

        /**
         * Returns paths commonly exposed by local OpenAI-compatible servers.
         *
         * @return Profile using `/v1`-prefixed chat, Responses, and Anthropic paths.
         */
        fun localV1(): GenericOpenAIEndpointProfile = GenericOpenAIEndpointProfile(
            chatCompletionsPath = "/v1/chat/completions",
            responsesPath = "/v1/responses",
            anthropicMessagesPath = "/v1/messages"
        )

        /**
         * Returns the explicit Anthropic-compatible route used by MiniMax.
         *
         * @return Profile using `/anthropic/v1/messages` for Anthropic mode.
         */
        fun miniMax(): GenericOpenAIEndpointProfile = DEFAULT.copy(
            anthropicMessagesPath = "/anthropic/v1/messages"
        )
    }
}
