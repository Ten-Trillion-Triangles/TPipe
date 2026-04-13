package openrouterPipe.builders

import env.*

/**
 * Request builder for Nvidia models via OpenRouter.
 * Handles nvidia/ prefix models.
 */
class NvidiaBuilder : BaseOpenRouterModelBuilder() {
    override fun canHandle(model: String): Boolean = model.contains("nvidia/")

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