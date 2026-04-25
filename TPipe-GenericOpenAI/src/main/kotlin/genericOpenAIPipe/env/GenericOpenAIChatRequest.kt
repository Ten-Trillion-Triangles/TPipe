package genericOpenAIPipe.env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for Generic OpenAI-compatible /v1/chat/completions endpoint.
 * OpenAI-compatible with standard parameters.
 *
 * @property model OpenAI model ID (e.g., "gpt-4o")
 * @property messages List of conversation messages
 * @property temperature Sampling temperature (0-2 range)
 * @property topP Nucleus sampling parameter (0-1 range)
 * @property topK Top-k sampling
 * @property maxTokens Maximum tokens to generate (alternative to maxCompletionTokens)
 * @property maxCompletionTokens Maximum completion tokens (alternative to maxTokens)
 * @property stream Enable streaming (SSE response)
 * @property stop List of stop sequences (up to 4)
 * @property presencePenalty Presence penalty (-2.0 to 2.0)
 * @property frequencyPenalty Frequency penalty (-2.0 to 2.0)
 * @property repetitionPenalty Repetition penalty (0.0 to 2.0)
 * @property seed Random seed for deterministic sampling
 * @property logitBias Logit bias for specific tokens
 * @property logprobs Whether to return log probabilities
 * @property topLogprobs Number of top log probabilities to return
 * @property minP MinP sampling parameter (0.0 to 1.0)
 * @property topA TopA sampling parameter (0.0 to 1.0)
 * @property tools Function calling tool definitions
 * @property toolChoice Tool selection mode
 * @property parallelToolCalls Enable parallel function calling
 * @property responseFormat Response format constraint (text, json_object, json_schema)
 * @property structuredOutputs Enable structured outputs via json_schema
 * @property modalities Output modalities (text, image, audio)
 * @property reasoning Reasoning model configuration
 * @property cacheControl Anthropic-style caching with ttl
 * @property user End-user identifier for abuse detection
 * @property n Number of completions to generate
 */
@Serializable
data class GenericOpenAIChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("top_k")
    val topK: Int? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    val stream: Boolean = false,
    val stop: List<String>? = null,
    @SerialName("presence_penalty")
    val presencePenalty: Double? = null,
    @SerialName("frequency_penalty")
    val frequencyPenalty: Double? = null,
    @SerialName("repetition_penalty")
    val repetitionPenalty: Double? = null,
    val seed: Int? = null,
    @SerialName("logit_bias")
    val logitBias: Map<Int, Double>? = null,
    val logprobs: Boolean? = null,
    @SerialName("top_logprobs")
    val topLogprobs: Int? = null,
    @SerialName("min_p")
    val minP: Double? = null,
    @SerialName("top_a")
    val topA: Double? = null,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean? = null,
    @SerialName("response_format")
    val responseFormat: ResponseFormat? = null,
    @SerialName("structured_outputs")
    val structuredOutputs: Boolean? = null,
    val modalities: List<String>? = null,
    val reasoning: ReasoningConfig? = null,
    @SerialName("cache_control")
    val cacheControl: CacheControl? = null,
    val user: String? = null,
    val n: Int? = null
)