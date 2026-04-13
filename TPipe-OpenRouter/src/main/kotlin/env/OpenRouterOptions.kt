package env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request body for OpenRouter's /v1/chat/completions endpoint.
 * OpenAI-compatible with OpenRouter-specific extensions.
 *
 * @see <a href="https://openrouter.ai/docs/api/api-reference/chat/send-chat-completion-request">Chat Completions API</a>
 *
 * @property model OpenRouter model ID (e.g., "anthropic/claude-3-5-sonnet-20241022")
 * @property messages List of conversation messages
 * @property temperature Sampling temperature (0-2 range)
 * @property topP Nucleus sampling parameter (0-1 range)
 * @property maxTokens Maximum tokens to generate (alternative to maxCompletionTokens)
 * @property maxCompletionTokens Maximum completion tokens (alternative to maxTokens)
 * @property stream Enable streaming (SSE response)
 * @property stop List of stop sequences (up to 4)
 * @property presencePenalty Presence penalty (-2.0 to 2.0)
 * @property frequencyPenalty Frequency penalty (-2.0 to 2.0)
 * @property seed Random seed for deterministic sampling
 * @property logprobs Whether to return log probabilities
 * @property topLogprobs Number of top log probabilities to return
 * @property tools Function calling tool definitions
 * @property toolChoice Tool selection mode
 * @property responseFormat Response format constraint (text, json_object, json_schema)
 * @property modalities Output modalities (text, image, audio)
 * @property plugins OpenRouter plugins (web search, file parsing, etc.)
 * @property provider Routing preferences for provider selection
 * @property reasoning Reasoning model configuration (e.g., DeepSeek R1)
 * @property cacheControl Anthropic-style caching with ttl
 * @property serviceTier Service tier (auto, default, flex, priority, scale)
 * @property sessionId Request grouping for observability
 * @property trace Observability metadata
 */
@Serializable
data class OpenRouterChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
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
    @SerialName("response_format")
    val responseFormat: ResponseFormat? = null,
    val modalities: List<String>? = null,
    val plugins: List<Plugin>? = null,
    val provider: ProviderPreferences? = null,
    val reasoning: ReasoningConfig? = null,
    @SerialName("cache_control")
    val cacheControl: CacheControl? = null,
    @SerialName("service_tier")
    val serviceTier: String? = null,
    @SerialName("session_id")
    val sessionId: String? = null,
    val trace: TraceConfig? = null
)

/**
 * Represents a single message in a conversation.
 *
 * @property role Message role: system, user, assistant, developer, or tool
 * @property content Message content (text or can be extended for multimodal)
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

/**
 * Tool definition for function calling.
 *
 * @property type Tool type (currently only "function" is supported)
 * @property function Function schema definition
 */
@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionSchema
)

/**
 * Schema definition for a callable function.
 *
 * @property name Function name
 * @property description Function description for the model
 * @property parameters JSON Schema for function parameters
 */
@Serializable
data class FunctionSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

/**
 * Response format constraint.
 *
 * @property type Format type: "text", "json_object", or "json_schema"
 * @property jsonSchema JSON Schema definition (required for json_schema type)
 */
@Serializable
data class ResponseFormat(
    val type: String,
    val jsonSchema: JsonObject? = null
)

/**
 * OpenRouter plugin configuration.
 *
 * @property id Plugin identifier
 * @property jsonProps Additional plugin-specific properties
 */
@Serializable
data class Plugin(
    val id: String,
    @SerialName("json_props")
    val jsonProps: JsonObject? = null
)

/**
 * Provider routing preferences for OpenRouter.
 *
 * @property order Provider ordering hint
 * @property allowFallbacks Allow fallback to alternative providers
 * @property dataCollection Data collection setting
 * @property preferSuffix Preferred provider suffix
 */
@Serializable
data class ProviderPreferences(
    val order: List<String>? = null,
    @SerialName("allow_fallbacks")
    val allowFallbacks: Boolean? = null,
    @SerialName("data_collection")
    val dataCollection: String? = null,
    @SerialName("prefer_suffix")
    val preferSuffix: String? = null
)

/**
 * Reasoning model configuration (e.g., for DeepSeek R1).
 *
 * @property effort Reasoning effort: "xhigh", "high", "medium", "low", "minimal", "none"
 */
@Serializable
data class ReasoningConfig(
    val effort: String? = null
)

/**
 * Anthropic-style cache control configuration.
 *
 * @property type Cache type (e.g., "ephemeral")
 * @property ttl Cache TTL (e.g., "5m", "1h")
 */
@Serializable
data class CacheControl(
    val type: String,
    val ttl: String? = null
)

/**
 * Observability trace configuration.
 *
 * @property version Trace format version
 * @property metadata Trace metadata
 */
@Serializable
data class TraceConfig(
    val version: String? = null,
    val metadata: JsonObject? = null
)

/**
 * Routing mode for provider selection.
 * Determines how OpenRouter selects the underlying provider for model requests.
 */
enum class RoutingMode {
    /**
     * OpenRouter automatically selects the best available provider.
     */
    Auto,

    /**
     * Prefer the provider with lowest latency.
     */
    PreferFastest,

    /**
     * Prefer the provider with lowest cost.
     */
    PreferCheapest,

    /**
     * Prefer the provider with best output quality.
     */
    PreferQuality,

    /**
     * Use a specific provider preference (requires provider preference to be set).
     */
    SpecificProvider
}