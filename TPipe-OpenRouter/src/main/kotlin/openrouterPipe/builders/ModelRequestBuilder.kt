package openrouterPipe.builders

import env.*

/**
 * Interface for OpenRouter model-specific request builders.
 * Each provider family can customize request formatting and parameter handling.
 */
interface OpenRouterModelBuilder {
    fun buildRequest(
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
    ): OpenRouterChatRequest

    fun canHandle(model: String): Boolean
}

/**
 * Base implementation with common request building logic.
 */
abstract class BaseOpenRouterModelBuilder : OpenRouterModelBuilder {
    protected fun buildBaseRequest(
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
        logitBias: Map<Int, Double>?,
        logprobs: Boolean?,
        topLogprobs: Int?,
        minP: Double?,
        topA: Double?,
        stream: Boolean
    ): OpenRouterChatRequest {
        return OpenRouterChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            presencePenalty = presencePenalty,
            frequencyPenalty = frequencyPenalty,
            seed = seed,
            stop = stopSequences,
            logitBias = logitBias,
            logprobs = logprobs,
            topLogprobs = topLogprobs,
            minP = minP,
            topA = topA,
            stream = stream
        )
    }
}

/**
 * Factory for creating the appropriate model builder based on model ID.
 */
object OpenRouterRequestBuilderFactory {
    private val builders = listOf<OpenRouterModelBuilder>(
        AnthropicBuilder(),
        OpenAIBuilder(),
        GoogleBuilder(),
        DeepSeekBuilder(),
        MetaLlamaBuilder(),
        MistralBuilder(),
        CohereBuilder(),
        QwenBuilder(),
        MiniMaxBuilder(),
        NousResearchBuilder(),
        NvidiaBuilder(),
        CognitiveComputationsBuilder(),
        LiquidBuilder(),
        ArceeAIBuilder(),
        ZAIBuilder(),
        OpenRouterFreeBuilder(),
        GenericBuilder()
    )

    fun getBuilder(model: String): OpenRouterModelBuilder {
        return builders.find { it.canHandle(model.lowercase()) } ?: GenericBuilder()
    }
}