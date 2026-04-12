package env

/**
 * OpenRouter API endpoint constants.
 * OpenRouter provides a unified API aggregating 300+ LLM models from various providers.
 * @see <a href="https://openrouter.ai/docs/api-reference/overview">OpenRouter API Docs</a>
 */
object Endpoints {
    /**
     * OpenRouter API base URL.
     * All API calls are made to this base URL with provider-specific paths appended.
     */
    const val BASE_URL = "https://openrouter.ai/api/v1"

    /**
     * Chat completions endpoint path.
     * OpenAI-compatible endpoint for generating chat completions.
     */
    const val CHAT_COMPLETIONS_PATH = "/chat/completions"

    /**
     * Full chat completions URL.
     * @see <a href="https://openrouter.ai/docs/api/api-reference/chat/send-chat-completion-request">Chat Completions API</a>
     */
    val chatCompletionsUrl: String
        get() = BASE_URL + CHAT_COMPLETIONS_PATH

    /**
     * Models listing endpoint path.
     * Returns available models from OpenRouter.
     */
    const val MODELS_PATH = "/models"

    /**
     * Full models listing URL.
     * @see <a href="https://openrouter.ai/docs/api/api-reference/models/get-models">Models API</a>
     */
    val modelsUrl: String
        get() = BASE_URL + MODELS_PATH
}