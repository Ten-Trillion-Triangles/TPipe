package openrouterPipe.builders

import env.*

/**
 * Request builder for OpenAI models via OpenRouter.
 * Handles openai/ prefix models (GPT-4, GPT-3.5, etc.).
 */
class OpenAIBuilder : BaseOpenRouterModelBuilder() {
    override fun canHandle(model: String): Boolean = model.contains("openai/")

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
            responseFormat = responseFormat,
            reasoning = reasoning
        )
    }
}