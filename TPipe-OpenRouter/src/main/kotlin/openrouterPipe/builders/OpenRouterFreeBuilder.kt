package openrouterPipe.builders

import env.*

/**
 * Request builder for OpenRouter free models via OpenRouter.
 * Handles openrouter/free prefix models.
 */
class OpenRouterFreeBuilder : BaseOpenRouterModelBuilder() {
    override fun canHandle(model: String): Boolean = model.contains("openrouter/free")

    override fun buildRequest(
        model: String,
        messages: List<ChatMessage>,
        systemPrompt: String,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int?,
        presencePenalty: Double?,
        frequencyPenalty: Double?,
        seed: Int?,
        stopSequences: List<String>?,
        tools: List<ToolDefinition>?,
        toolChoice: String?,
        responseFormat: ResponseFormat?,
        reasoning: ReasoningConfig?,
        provider: ProviderPreferences?,
        cacheControl: CacheControl?,
        plugins: List<Plugin>?,
        logitBias: Map<Int, Double>?,
        logprobs: Boolean?,
        topLogprobs: Int?,
        minP: Double?,
        topA: Double?,
        stream: Boolean
    ): OpenRouterChatRequest {
        val request = buildBaseRequest(model, messages, systemPrompt, temperature, topP, maxTokens, presencePenalty, frequencyPenalty, seed, stopSequences, logitBias, logprobs, topLogprobs, minP, topA, stream)
        return request.copy(
            tools = tools,
            toolChoice = toolChoice,
            reasoning = reasoning
        )
    }
}