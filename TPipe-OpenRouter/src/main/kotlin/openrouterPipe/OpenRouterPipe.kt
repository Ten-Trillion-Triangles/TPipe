package openrouterPipe

import com.TTT.Debug.*
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Enums.ProviderName
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import env.ChatMessage
import env.SseParser
import env.OpenRouterChatRequest
import env.OpenRouterEnv
import env.OpenRouterChatResponse
import env.OpenRouterErrorResponse
import env.StreamingChunk
import com.TTT.PipeContextProtocol.PcPRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * TPipe abstraction for OpenRouter. Provides access to OpenRouter's unified API
 * that aggregates 300+ LLM models from various providers.
 *
 * This implementation supports the /v1/chat/completions endpoint with OpenAI-compatible
 * request/response formats and OpenRouter-specific extensions.
 *
 * @see <a href="https://openrouter.ai/docs">OpenRouter Documentation</a>
 */
/** Shared lenient JSON parser for OpenRouter wire payloads (created once, not per call). */
private val orJson: Json = Json { isLenient = true }

@kotlinx.serialization.Serializable
class OpenRouterPipe : Pipe()
{

//=========================================Properties===================================================================

    /**
     * OpenRouter API key for authentication.
     * Required for all API calls.
     * Marked @Transient to prevent API key from being serialized to disk.
     */
    @kotlinx.serialization.Transient
    private var apiKey: String = ""

    /**
     * OpenRouter API base URL.
     * Defaults to "https://openrouter.ai/api/v1"
     */
    @kotlinx.serialization.Serializable
    private var baseUrl: String = "https://openrouter.ai/api/v1"

    /**
     * HTTP Referer header for OpenRouter API requests.
     * Used for site traffic tracking and analytics.
     */
    @kotlinx.serialization.Serializable
    private var httpReferer: String = ""

    /**
     * OpenRouter Title header for OpenRouter API requests.
     * Used to identify your application to OpenRouter.
     */
    @kotlinx.serialization.Serializable
    private var openRouterTitle: String = ""

    /**
     * HTTP client for OpenRouter API calls.
     * Initialized in init() and closed in abort().
     */
    @kotlinx.serialization.Transient
    private var httpClient: HttpClient? = null

    /**
     * Whether streaming mode is enabled.
     */
    @kotlinx.serialization.Serializable
    override var streamingEnabled: Boolean = false

// OpenRouter-specific extended parameters
    /**
     * Function calling tool definitions.
     */
    @kotlinx.serialization.Transient
    private var tools: List<env.ToolDefinition>? = null

    /**
     * Tool choice mode for function calling.
     * Values: "auto", "none", "required"
     */
    @kotlinx.serialization.Transient
    private var toolChoice: String? = null

    /**
     * Provider routing preferences.
     */
    @kotlinx.serialization.Transient
    private var providerPreferences: env.ProviderPreferences? = null

    /**
     * Cache control with TTL for Anthropic-style caching.
     */
    @kotlinx.serialization.Transient
    private var cacheControl: env.CacheControl? = null

    /**
     * OpenRouter plugins (web search, file parsing, etc.).
     */
    @kotlinx.serialization.Transient
    private var plugins: List<env.Plugin>? = null

    /**
     * Response format for structured output.
     */
    @kotlinx.serialization.Transient
    private var responseFormat: env.ResponseFormat? = null

    /**
     * Service tier for request priority.
     * Values: "auto", "default", "flex", "priority", "scale"
     */
    @kotlinx.serialization.Transient
    private var serviceTier: String? = null

    /**
     * Session ID for request grouping/observability.
     */
    @kotlinx.serialization.Transient
    private var sessionId: String? = null

    /**
     * Frequency penalty for reducing repetition (-2.0 to 2.0).
     */
    @kotlinx.serialization.Transient
    private var frequencyPenalty: Double? = null

    /**
     * Whether to return log probabilities.
     */
    @kotlinx.serialization.Transient
    private var logprobs: Boolean? = null

    /**
     * Number of top log probabilities to return (0-20).
     */
    @kotlinx.serialization.Transient
    private var topLogprobs: Int? = null

    /**
     * MinP sampling parameter (0.0 to 1.0).
     */
    @kotlinx.serialization.Transient
    private var minP: Double? = null

    /**
     * TopA sampling parameter (0.0 to 1.0).
     */
    @kotlinx.serialization.Transient
    private var topA: Double? = null

    /**
     * TopK sampling parameter (1-255).
     * Not available for OpenAI models.
     */
    @kotlinx.serialization.Transient
    private var openRouterTopK: Int? = null

    /**
     * Repetition penalty for reducing repetition (0.0 to 2.0).
     * OpenRouter-specific parameter distinct from frequencyPenalty.
     */
    @kotlinx.serialization.Transient
    private var openRouterRepetitionPenalty: Double? = null

    /**
     * Whether to enable parallel function calling.
     * Default is true.
     */
    @kotlinx.serialization.Transient
    private var parallelToolCalls: Boolean? = null

    /**
     * Whether to enable structured outputs via json_schema.
     */
    @kotlinx.serialization.Transient
    private var structuredOutputs: Boolean? = null

    /**
     * Response verbosity level.
     * Values: "low", "medium", "high", "max"
     */
    @kotlinx.serialization.Transient
    private var verbosity: String? = null

    /**
     * End-user identifier for abuse detection.
     */
    @kotlinx.serialization.Transient
    private var openRouterUser: String? = null

    /**
     * Reasoning configuration for reasoning-capable models.
     * Contains effort, maxTokens, exclude, and enabled.
     */
    @kotlinx.serialization.Transient
    private var reasoningConfig: env.ReasoningConfig? = null

    /**
     * Flat top-level `reasoning_effort` request field (separate from the `reasoning` object).
     */
    @kotlinx.serialization.Transient
    private var flatReasoningEffort: String? = null

    /**
     * Streaming options (e.g. include_usage to emit a final usage chunk).
     */
    @kotlinx.serialization.Transient
    private var streamOptions: env.StreamOptions? = null

    /** OR-K: image generation configuration. */
    @kotlinx.serialization.Transient
    private var openRouterImageConfig: JsonObject? = null

    /** OR-K: the model's predicted response (for prefill-style requests). */
    @kotlinx.serialization.Transient
    private var openRouterPrediction: JsonObject? = null

    /** OR-K: key to cache the prompt under. */
    @kotlinx.serialization.Transient
    private var promptCacheKey: String? = null

    /** OR-K: prompt-cache tuning options. */
    @kotlinx.serialization.Transient
    private var promptCacheOptions: JsonObject? = null

    /** OR-K: request metadata (observability). */
    @kotlinx.serialization.Transient
    private var openRouterMetadata: JsonObject? = null

    /** OR-K: fallback model slugs tried in order. */
    @kotlinx.serialization.Transient
    private var fallbackModels: List<String>? = null

    /**
     * OR-L: when true, [generateContent] routes to the OpenRouter `/responses`
     * (Responses API) path instead of the chat-completions path. Defaults to
     * false so the existing chat-completions behavior is byte-identical.
     */
    private var responsesMode: Boolean = false

    /** OR-K: conditions for stopping server-side tool loops. */
    @kotlinx.serialization.Transient
    private var stopServerToolsWhen: JsonObject? = null

//=========================================Builder Methods=============================================================

    /**
     * Sets the OpenRouter API key.
     * @param key The API key from openrouter.ai
     * @return This pipe instance for fluent chaining
     */
    fun setApiKey(key: String): OpenRouterPipe
    {
        apiKey = key
        return this
    }

    /**
     * Sets the OpenRouter base URL.
     * @param url The base URL for the API (defaults to OpenRouter's official endpoint)
     * @return This pipe instance for fluent chaining
     */
    fun setBaseUrl(url: String): OpenRouterPipe
    {
        baseUrl = url
        return this
    }

    /**
     * Sets the HTTP Referer header for OpenRouter API requests.
     * @param referer The referer URL to send with requests
     * @return This pipe instance for fluent chaining
     */
    fun setHttpReferer(referer: String): OpenRouterPipe
    {
        httpReferer = referer
        return this
    }

    /**
     * Sets the OpenRouter Title header for OpenRouter API requests.
     * @param title The application title to identify this integration
     * @return This pipe instance for fluent chaining
     */
    fun setOpenRouterTitle(title: String): OpenRouterPipe
    {
        openRouterTitle = title
        return this
    }

    /**
     * Enables or disables streaming mode.
     * @param enabled True to enable streaming
     * @return This pipe instance for fluent chaining
     */
    override fun setStreamingEnabled(enabled: Boolean): OpenRouterPipe
    {
        streamingEnabled = enabled
        return this
    }

    /**
     * Registers a callback for streaming response chunks.
     * Automatically enables streaming mode.
     * @param callback Suspendable callback receiving text chunks
     * @return This pipe instance for fluent chaining
     */
    fun setStreamingCallback(callback: suspend (String) -> Unit): OpenRouterPipe
    {
        this.streamingEnabled = true
        obtainStreamingCallbackManager().addCallback(callback)
        return this
    }

    /**
     * Enables streaming with optional callback registration and reasoning
     * election.
     *
     * The provider-unified reasoning election surface:
     * [bedrockPipe.BedrockPipe.enableStreaming],
     * [genericOpenAIPipe.GenericOpenAIPipe.enableStreaming] and
     * [ollamaPipe.OllamaPipe.enableStreaming] expose the same
     * `(callback, showReasoning, streamReasoning)` shape, so a caller can
     * elect internal-reasoning streaming uniformly across every provider
     * module through one method.
     *
     * @param callback Optional suspending callback receiving text chunks
     * @param showReasoning If true, attempts to propagate streaming settings
     *   to CoT reasoning pipes. Accepted for signature parity; OpenRouter's
     *   binder does not carry a separate reasoning-pipe wire.
     * @param streamReasoning If true, internal model reasoning chunks are
     *   emitted to streaming callbacks via the base reasoning hooks; the
     *   base [com.TTT.Pipe.Pipe.setStreamModelReasoning] flag is the single
     *   election point providers consult. Defaults to true (Bedrock parity).
     * @return This pipe instance for method chaining
     */
    fun enableStreaming(
        callback: (suspend (String) -> Unit)? = null,
        showReasoning: Boolean = false,
        streamReasoning: Boolean = true
    ): OpenRouterPipe
    {
        this.streamingEnabled = true
        this.streamModelReasoning = streamReasoning

        if(callback != null)
        {
            setStreamingCallback(callback)
        }

        // [showReasoning] is accepted for Bedrock-signature parity; OpenRouter's
        // streaming binder does not carry a separate reasoning-pipe wire, so
        // no additional work is required. The streamReasoning election above
        // is what routes reasoning deltas through the base hooks.
        @Suppress("UNUSED_PARAMETER")
        val unusedShowReasoning = showReasoning

        return this
    }

    /**
     * Sets the tools for function calling.
     * @param tools List of tool definitions
     * @return This pipe instance for fluent chaining
     */
    fun setTools(tools: List<env.ToolDefinition>): OpenRouterPipe
    {
        this.tools = tools
        return this
    }

    /**
     * Sets the tool choice mode for function calling.
     * @param choice Tool choice: "auto", "none", or "required"
     * @return This pipe instance for fluent chaining
     */
    fun setToolChoice(choice: String): OpenRouterPipe
    {
        this.toolChoice = choice
        return this
    }

    /**
     * Sets the reasoning effort for reasoning-capable models.
     * @param effort Reasoning effort: "xhigh", "high", "medium", "low", "minimal", or "none"
     * @return This pipe instance for fluent chaining
     */
    /**
     * Sets the reasoning configuration for reasoning-capable models.
     * @param config Reasoning configuration with effort, maxTokens, exclude, enabled
     * @return This pipe instance for fluent chaining
     */
    fun setReasoningConfig(config: env.ReasoningConfig): OpenRouterPipe
    {
        this.reasoningConfig = config
        return this
    }

    /**
     * Sets reasoning effort for reasoning-capable models.
     * Convenience method that creates ReasoningConfig with only effort.
     * @param effort Reasoning effort: "xhigh", "high", "medium", "low", "minimal", "none"
     * @return This pipe instance for fluent chaining
     */
    fun setReasoningEffort(effort: String): OpenRouterPipe
    {
        this.reasoningConfig = env.ReasoningConfig(effort = effort)
        return this
    }

    /**
     * Sets the flat top-level `reasoning_effort` request field (distinct from the `reasoning`
     * object). The current OpenRouter spec exposes a standalone `reasoning_effort` string.
     * @param effort The reasoning effort value (e.g. "high", "low", "medium").
     * @return This pipe instance for fluent chaining
     */
    fun setFlatReasoningEffort(effort: String): OpenRouterPipe
    {
        this.flatReasoningEffort = effort
        return this
    }

    /**
     * Sets streaming options. When [includeUsage] is true, the stream ends with a final
     * chunk carrying the [env.UsageInfo] usage object (cost, tokens).
     * @param includeUsage Whether to request the final usage chunk.
     * @return This pipe instance for fluent chaining
     */
    fun setStreamOptions(includeUsage: Boolean): OpenRouterPipe
    {
        this.streamOptions = env.StreamOptions(includeUsage = includeUsage)
        return this
    }

    /** OR-K: sets the image generation configuration. */
    fun setImageConfig(config: JsonObject): OpenRouterPipe
    {
        this.openRouterImageConfig = config
        return this
    }

    /** OR-K: sets the predicted response payload (prefill-style requests). */
    fun setPrediction(prediction: JsonObject): OpenRouterPipe
    {
        this.openRouterPrediction = prediction
        return this
    }

    /** OR-K: sets the prompt-cache key. */
    fun setPromptCacheKey(key: String): OpenRouterPipe
    {
        this.promptCacheKey = key
        return this
    }

    /** OR-K: sets the prompt-cache tuning options. */
    fun setPromptCacheOptions(options: JsonObject): OpenRouterPipe
    {
        this.promptCacheOptions = options
        return this
    }

    /** OR-K: sets request metadata for observability. */
    fun setMetadata(metadata: JsonObject): OpenRouterPipe
    {
        this.openRouterMetadata = metadata
        return this
    }

    /** OR-K: sets the fallback model slugs (tried in order when the primary is unavailable). */
    fun setFallbackModels(models: List<String>): OpenRouterPipe
    {
        this.fallbackModels = models
        return this
    }

    /** OR-K: sets the stop-server-tools-when conditions. */
    fun setStopServerToolsWhen(conditions: JsonObject): OpenRouterPipe
    {
        this.stopServerToolsWhen = conditions
        return this
    }

    /**
     * Sets provider routing preferences.
     * @param prefs Provider preferences configuration
     * @return This pipe instance for fluent chaining
     */
    fun setProviderPreferences(prefs: env.ProviderPreferences): OpenRouterPipe
    {
        this.providerPreferences = prefs
        return this
    }

    /**
     * Sets cache control with TTL for Anthropic-style caching.
     * @param ttl Cache TTL (e.g., "5m", "1h", "24h")
     * @return This pipe instance for fluent chaining
     */
    fun setCacheControl(ttl: String): OpenRouterPipe
    {
        this.cacheControl = env.CacheControl(type = "ephemeral", ttl = ttl)
        return this
    }

    /**
     * Sets OpenRouter plugins for extended functionality.
     * @param plugins List of plugins to enable
     * @return This pipe instance for fluent chaining
     */
    fun setPlugins(plugins: List<env.Plugin>): OpenRouterPipe
    {
        this.plugins = plugins
        return this
    }

    /**
     * Sets the response format for structured output.
     * @param type Format type: "text", "json_object", or "json_schema"
     * @param schema Optional JSON schema for json_schema type
     * @return This pipe instance for fluent chaining
     */
    fun setResponseFormat(type: String, schema: kotlinx.serialization.json.JsonObject? = null): OpenRouterPipe
    {
        this.responseFormat = env.ResponseFormat(type = type, jsonSchema = schema)
        return this
    }

    /**
     * Sets the service tier for request priority.
     * @param tier Service tier: "auto", "default", "flex", "priority", or "scale"
     * @return This pipe instance for fluent chaining
     */
    fun setServiceTier(tier: String): OpenRouterPipe
    {
        this.serviceTier = tier
        return this
    }

    /**
     * Sets the session ID for request grouping/observability.
     * @param id Session identifier
     * @return This pipe instance for fluent chaining
     */
    fun setSessionId(id: String): OpenRouterPipe
    {
        this.sessionId = id
        return this
    }

    /**
     * Sets the frequency penalty for reducing repetition.
     * @param penalty Frequency penalty (-2.0 to 2.0)
     * @return This pipe instance for fluent chaining
     */
    fun setFrequencyPenalty(penalty: Double): OpenRouterPipe
    {
        this.frequencyPenalty = penalty
        return this
    }

    /**
     * Sets whether to return log probabilities.
     * @param enabled True to return log probabilities
     * @return This pipe instance for fluent chaining
     */
    fun setLogprobs(enabled: Boolean): OpenRouterPipe
    {
        this.logprobs = enabled
        return this
    }

    /**
     * Sets the number of top log probabilities to return.
     * @param count Number of top log probabilities (0-20)
     * @return This pipe instance for fluent chaining
     */
    fun setTopLogprobs(count: Int): OpenRouterPipe
    {
        this.topLogprobs = count
        return this
    }

    /**
     * Sets the minP sampling parameter.
     * @param p MinP value (0.0 to 1.0)
     * @return This pipe instance for fluent chaining
     */
    fun setMinP(p: Double): OpenRouterPipe
    {
        this.minP = p
        return this
    }

    /**
     * Sets the topA sampling parameter.
     * @param a topA value (0.0 to 1.0)
     * @return This pipe instance for fluent chaining
     */
    fun setTopA(a: Double): OpenRouterPipe
    {
        this.topA = a
        return this
    }

    /**
     * Sets the topK sampling parameter.
     * Not available for OpenAI models.
     * @param k TopK value (1-255)
     * @return This pipe instance for fluent chaining
     */
    fun setOpenRouterTopK(k: Int): OpenRouterPipe
    {
        this.openRouterTopK = k
        return this
    }

    /**
     * Sets the repetition penalty for reducing repetition.
     * OpenRouter-specific parameter distinct from frequencyPenalty.
     * @param penalty Repetition penalty (0.0 to 2.0)
     * @return This pipe instance for fluent chaining
     */
    fun setOpenRouterRepetitionPenalty(penalty: Double): OpenRouterPipe
    {
        this.openRouterRepetitionPenalty = penalty
        return this
    }

    /**
     * Sets whether to enable parallel function calling.
     * @param enabled True to enable parallel calls (default true)
     * @return This pipe instance for fluent chaining
     */
    fun setParallelToolCalls(enabled: Boolean): OpenRouterPipe
    {
        this.parallelToolCalls = enabled
        return this
    }

    /**
     * Sets whether to enable structured outputs via json_schema.
     * @param enabled True to enable structured outputs
     * @return This pipe instance for fluent chaining
     */
    @Deprecated("structured_outputs was removed from the current OpenRouter request; use response_format json_schema instead. No-op on the wire.")
    fun setStructuredOutputs(enabled: Boolean): OpenRouterPipe
    {
        this.structuredOutputs = enabled
        return this
    }

    /**
     * Sets the response verbosity level.
     * @param level Verbosity: "low", "medium", "high", "max"
     * @return This pipe instance for fluent chaining
     */
    @Deprecated("verbosity was removed from the current OpenRouter request. No-op on the wire.")
    fun setVerbosity(level: String): OpenRouterPipe
    {
        this.verbosity = level
        return this
    }

    /**
     * Sets the end-user identifier for abuse detection.
     * @param userId User identifier
     * @return This pipe instance for fluent chaining
     */
    fun setOpenRouterUser(userId: String): OpenRouterPipe
    {
        this.openRouterUser = userId
        return this
    }

//=========================================Pipe Lifecycle Methods======================================================

    /**
     * Initializes the OpenRouter pipe.
     * Validates configuration and sets up the HTTP client.
     * @return This pipe instance
     * @throws IllegalStateException if apiKey is not set
     */
    override suspend fun init(): Pipe
    {
        super.init()

        trace(TraceEventType.PIPE_START, TracePhase.INITIALIZATION,
              metadata = mapOf(
                  "provider" to "OpenRouter",
                  "baseUrl" to baseUrl,
                  "model" to model
              ))

        if(apiKey.isBlank())
        {
            val resolvedKey = OpenRouterEnv.resolveApiKey()
            if(resolvedKey.isBlank())
            {
                throw IllegalStateException("OpenRouter API key is required. Call setApiKey(), openrouterEnv.setApiKey(), or set OPENROUTER_API_KEY environment variable before init().")
            }
            apiKey = resolvedKey
        }

        provider = ProviderName.OpenRouter

        httpClient = createHttpClient()

        trace(TraceEventType.PIPE_SUCCESS, TracePhase.INITIALIZATION,
              metadata = mapOf("initialized" to true))

        return this
    }

    /**
     * Aborts any active generation and cleans up resources.
     */
    override suspend fun abort()
    {
        trace(TraceEventType.PIPE_FAILURE, TracePhase.EXECUTION,
              metadata = mapOf("action" to "abort", "provider" to "OpenRouter"))

        httpClient?.close()
        httpClient = createHttpClient()

        super.abort()
    }

//=========================================Generation Methods=========================================================

    /**
     * Generates text using the configured OpenRouter model.
     * @param promptInjector Text to inject into the prompt
     * @return Generated response text
     */
    override suspend fun generateText(promptInjector: String): String
    {
        val client = httpClient ?: throw IllegalStateException("OpenRouterPipe not initialized. Call init() first.")

        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "provider" to "OpenRouter",
                  "model" to model,
                  "baseUrl" to baseUrl,
                  "promptLength" to promptInjector.length,
                  "streaming" to streamingEnabled
              ))

        return try
        {
            val jsonRequest = buildChatRequestJson(promptInjector)

            if(streamingEnabled)
            {
                val response = withContext(Dispatchers.IO)
                {
                    client.post("$baseUrl/chat/completions")
                    {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer $apiKey")

                        if(httpReferer.isNotEmpty())
                        {
                            header("HTTP-Referer", httpReferer)
                        }
                        if(openRouterTitle.isNotEmpty())
                        {
                            header("X-OpenRouter-Title", openRouterTitle)
                        }

                        setBody(jsonRequest)
                    }
                }

                return executeStreaming(response)
            }
            else
            {
                val (resultText, _) = executeNonStreamResult(jsonRequest)
                return resultText
            }
        }
        catch(e: Exception)
        {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  error = e,
                  metadata = mapOf(
                      "errorType" to (e::class.simpleName ?: "Unknown"),
                      "errorMessage" to (e.message ?: "Unknown error"),
                      "streaming" to streamingEnabled
                  ))

            when(e)
            {
                is HttpRequestTimeoutException -> throw P2PException(P2PError.transport, "Request timeout", e)
                is java.net.SocketTimeoutException -> throw P2PException(P2PError.transport, "Socket timeout", e)
                is java.net.ConnectException -> throw P2PException(P2PError.transport, "Connection failed", e)
                else -> throw e
            }
        }
    }

    /**
     * Builds the OpenRouter chat-completions request JSON for [promptInjector] using the
     * pipe's current configuration (system prompt, sampling, tools, reasoning, provider, etc.).
     * @param promptInjector The user-turn text to send.
     * @return The serialized request body.
     */
    private fun buildChatRequestJson(promptInjector: String): String
    {
        return serialize(buildChatRequestObject(promptInjector, streamingEnabled), encodedefault = false)
    }

    /**
     * Builds the in-process [OpenRouterChatRequest] from the pipe's configured
     * parameters. Extracted so the chat-completions path and the responses path
     * share one request construction (the responses mode converts this object
     * to the Responses wire spec).
     * @param promptInjector The user prompt to append as the final message.
     * @param stream Whether the request is streaming.
     * @return The constructed request object.
     */
    private fun buildChatRequestObject(promptInjector: String, stream: Boolean): OpenRouterChatRequest
    {
        val messages = mutableListOf<ChatMessage>()

        if(systemPrompt.isNotEmpty())
        {
            messages.add(ChatMessage(role = "system", content = systemPrompt))
        }

        messages.add(ChatMessage(role = "user", content = promptInjector))

        return OpenRouterChatRequest(
            model = model,
            messages = messages,
            temperature = if(temperature > 0.0) temperature else null,
            topP = if(topP > 0.0) topP else null,
            topK = openRouterTopK,
            maxTokens = if(maxTokens > 0) maxTokens else null,
            presencePenalty = if(presencePenalty != 0.0) presencePenalty else null,
            frequencyPenalty = frequencyPenalty,
            repetitionPenalty = openRouterRepetitionPenalty,
            seed = seed,
            stop = stopSequences.takeIf { it.isNotEmpty() },
            tools = tools,
            toolChoice = toolChoice,
            parallelToolCalls = parallelToolCalls,
            responseFormat = responseFormat,
            reasoning = reasoningConfig,
            reasoningEffort = flatReasoningEffort,
            provider = providerPreferences,
            cacheControl = cacheControl,
            plugins = plugins,
            logitBias = logitBias.takeIf { it.isNotEmpty() },
            logprobs = logprobs,
            topLogprobs = topLogprobs,
            minP = minP,
            topA = topA,
            serviceTier = serviceTier,
            user = openRouterUser,
            sessionId = sessionId,
            stream = streamingEnabled,
            streamOptions = streamOptions,
            imageConfig = openRouterImageConfig,
            prediction = openRouterPrediction,
            promptCacheKey = promptCacheKey,
            promptCacheOptions = promptCacheOptions,
            metadata = openRouterMetadata,
            models = fallbackModels,
            stopServerToolsWhen = stopServerToolsWhen
        )
    }

    /**
     * Executes a non-streaming chat-completions request.
     * @param jsonRequest The serialized request body.
     * @return A pair of (resultText, reasoning): the surfaced response text (content + PCP
     *   tool-call JSON) and the flattened model reasoning (empty string when none).
     */
    private suspend fun executeNonStreamResult(jsonRequest: String): Pair<String, String>
    {
        val client = httpClient ?: throw IllegalStateException("OpenRouterPipe not initialized. Call init() first.")
        val responseText = withContext(Dispatchers.IO)
        {
            client.post("$baseUrl/chat/completions")
            {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")

                if(httpReferer.isNotEmpty())
                {
                    header("HTTP-Referer", httpReferer)
                }
                if(openRouterTitle.isNotEmpty())
                {
                    header("X-OpenRouter-Title", openRouterTitle)
                }

                setBody(jsonRequest)
            }.bodyAsText()
        }

        // Check for error responses BEFORE deserializing as success
        val errorResponse = try { deserialize<OpenRouterErrorResponse>(responseText) } catch(e: Exception) { null }
        if(errorResponse != null)
        {
            val p2pError = mapOpenRouterError(errorResponse.error)
            throw P2PException(p2pError, "OpenRouter error: ${errorResponse.error.message}", Exception(errorResponse.error.message))
        }

        val response: OpenRouterChatResponse = deserialize(responseText)
            ?: throw P2PException(P2PError.json, "Failed to deserialize OpenRouter chat response: $responseText", Exception("Deserialization failed"))

        var resultText = response.choices.firstOrNull()?.message?.content ?: ""

        // OR-B: surface tool_calls (function calls) through PCP, matching the Ollama pattern.
        val responseToolCalls = response.choices.firstOrNull()?.message?.toolCalls
        if(!responseToolCalls.isNullOrEmpty())
        {
            val pcpRequests = responseToolCalls.map { call -> mapOpenRouterToolCallToPcp(call) }
            val toolCallJson = serialize(pcpRequests)
            resultText = if(resultText.isEmpty()) toolCallJson else "$resultText\n\n$toolCallJson"
        }

        // OR-C: flatten model reasoning from the non-stream message.
        val reasoning = response.choices.firstOrNull()?.message?.let { flattenOpenRouterReasoning(it) } ?: ""

        // Extract token usage for tracing
        val usage = response.usage
        val inputTokens = usage?.promptTokens ?: 0
        val outputTokens = usage?.completionTokens ?: 0
        val totalTokens = usage?.totalTokens ?: 0

        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              metadata = mapOf(
                  "inputTokens" to inputTokens,
                  "outputTokens" to outputTokens,
                  "totalTokens" to totalTokens,
                  "cost" to (usage?.cost ?: 0.0),
                  "isByok" to (usage?.isByok ?: false),
                  "responseLength" to resultText.length,
                  "reasoningLength" to reasoning.length,
                  "model" to response.model,
                  "success" to true,
                  "apiType" to "ChatAPI"
              ))

        return Pair(resultText, reasoning)
    }

    /**
     * OR-C: flattens the model reasoning of a non-stream response [message] into a single
     * string, mirroring the streaming precedence (plain reasoning first, else the concatenated
     * [env.ReasoningDetail] text/summary). Mirrors Bedrock's non-stream reasoning-flatten.
     * @param message The response message.
     * @return The flattened reasoning, or an empty string when none.
     */
    private fun flattenOpenRouterReasoning(message: ChatMessage): String
    {
        val plain = message.reasoning?.takeIf { it.isNotEmpty() }
        if(!plain.isNullOrEmpty()) return plain

        val details = message.reasoningDetails
            ?.map { it.text?.takeIf { t -> t.isNotEmpty() } ?: it.summary?.takeIf { s -> s.isNotEmpty() } }
            ?.filterNotNull()
            ?.joinToString("")
        return details ?: ""
    }

    /**
     * OR-C: override the base multimodal generation so the non-stream path surfaces model
     * reasoning into [MultimodalContent.modelReasoning] (the base default drops it). Streaming
     * reasoning is already delivered through the base reasoning hooks, so that path delegates.
     * @param content The multimodal request content.
     * @return The result with text + flattened reasoning.
     */
    override suspend fun generateContent(content: MultimodalContent): MultimodalContent
    {
        if(responsesMode)
        {
            return generateResponses(content)
        }

        if(streamingEnabled)
        {
            val text = generateText(content.text)
            return MultimodalContent(text = text)
        }
        val jsonRequest = buildChatRequestJson(content.text)
        val (resultText, reasoning) = executeNonStreamResult(jsonRequest)
        return MultimodalContent(text = resultText, modelReasoning = reasoning)
    }

    /**
     * OR-L: executes a non-streaming OpenRouter Responses API call (`/responses`)
     * and projects the result onto the P2P surface: the visible answer (+ PCP
     * tool-call JSON), and the flattened reasoning onto
     * [MultimodalContent.modelReasoning]. This is an isolated path — it is only
     * entered via [setResponsesMode] and does not touch the chat-completions
     * flow.
     * @param content The in-process request content.
     * @return The populated multimodal result.
     */
    private suspend fun generateResponses(content: MultimodalContent): MultimodalContent
    {
        val client = httpClient ?: throw IllegalStateException("OpenRouterPipe not initialized. Call init() first.")
        val serializer = OpenRouterResponsesRequestSerializer()
        val jsonRequest = serializer.serialize(buildChatRequestObject(content.text, stream = false), stream = false)

        val responseText = withContext(Dispatchers.IO)
        {
            client.post("$baseUrl/responses")
            {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                if(httpReferer.isNotEmpty())
                {
                    header("HTTP-Referer", httpReferer)
                }
                if(openRouterTitle.isNotEmpty())
                {
                    header("X-OpenRouter-Title", openRouterTitle)
                }
                setBody(jsonRequest)
            }.bodyAsText()
        }

        val parser = OpenRouterResponsesResponseParser()
        val responsesResponse = parser.parse(responseText)
        val flattened = parser.flatten(responsesResponse)

        var resultText = flattened.text
        if(flattened.functionCalls.isNotEmpty())
        {
            val pcpRequests = flattened.functionCalls.map { call -> mapResponsesFunctionCallToPcp(call) }
            val toolCallJson = serialize(pcpRequests)
            resultText = if(resultText.isEmpty()) toolCallJson else "$resultText\n\n$toolCallJson"
        }

        val usage = flattened.usage
        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              metadata = mapOf(
                  "inputTokens" to (usage?.inputTokens ?: 0),
                  "outputTokens" to (usage?.outputTokens ?: 0),
                  "totalTokens" to (usage?.totalTokens ?: 0),
                  "reasoningLength" to flattened.reasoning.length,
                  "functionCallCount" to flattened.functionCalls.size,
                  "responseLength" to resultText.length,
                  "model" to responsesResponse.model,
                  "success" to true,
                  "apiType" to "ResponsesAPI"
              ))

        return MultimodalContent(text = resultText, modelReasoning = flattened.reasoning)
    }

    /**
     * OR-L: maps a Responses `function_call` item onto a [PcPRequest], mirroring
     * the chat-completions tool-call mapping ([mapOpenRouterToolCallToPcp]).
     * @param call The function-call output item.
     * @return The PCP request describing the tool call.
     */
    private fun mapResponsesFunctionCallToPcp(call: env.OpenRouterResponsesOutputItem.FunctionCall): PcPRequest
    {
        val request = PcPRequest()
        val functionName = call.name
        if(functionName.isNullOrBlank()) return request

        val argsObj = call.arguments?.let { raw -> runCatching { orJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject }
            ?: buildJsonObject { }

        if(functionName.startsWith("call_agent_"))
        {
            val agentName = functionName.removePrefix("call_agent_")
            val prompt = argsObj["prompt"]?.jsonPrimitive?.content ?: ""
            request.argumentsOrFunctionParams = listOf(agentName, prompt)
        }
        else
        {
            request.tPipeContextOptions.functionName = functionName
            request.argumentsOrFunctionParams = argsObj.values.map { value ->
                if(value is JsonPrimitive) value.content else value.toString()
            }
        }

        return request
    }

    /**
     * OR-L: selects the OpenRouter Responses API mode. When enabled,
     * [generateContent] routes to `/responses` instead of `/chat/completions`.
     * @param enabled True to use the Responses API; false (default) uses chat-completions.
     * @return This pipe instance.
     */
    fun setResponsesMode(enabled: Boolean): OpenRouterPipe
    {
        this.responsesMode = enabled
        return this
    }

    /**
     * Executes a streaming request and accumulates the response.
     * @param httpResponse The HTTP response from the streaming endpoint
     * @return Accumulated response text
     */
    private suspend fun executeStreaming(httpResponse: HttpResponse): String
    {
        val channel = httpResponse.bodyAsChannel()
        val textBuilder = StringBuilder()
        val toolCallAccumulator = mutableListOf<env.ChatToolCall>()
        var streamUsage: env.UsageInfo? = null

        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "step" to "streamingStart",
                  "streaming" to true
              ))

        while(!channel.isClosedForRead)
        {
            val line = channel.readUTF8Line() ?: break

            // Use SseParser for line type detection
            val sseLine = SseParser.parseLine(line)

            when(sseLine)
            {
                is SseParser.SseLine.Done -> break
                is SseParser.SseLine.Empty, is SseParser.SseLine.Comment -> continue
                is SseParser.SseLine.Data ->
                {
                    // Check for SSE error events before attempting StreamingChunk deserialization
                    val sseError = try { deserialize<OpenRouterErrorResponse>(sseLine.content) } catch(e: Exception) { null }
                    if(sseError != null)
                    {
                        val p2pError = mapOpenRouterError(sseError.error)
                        throw P2PException(p2pError, "OpenRouter streaming error: ${sseError.error.message}", Exception(sseError.error.message))
                    }

                    val chunk = SseParser.parseChunk(sseLine.content) ?: continue
                    chunk.choices.firstOrNull()?.delta?.toolCalls?.let { toolCallAccumulator.addAll(it) }
                    chunk.usage?.let { streamUsage = it }
                    handleStreamDataChunk(chunk, textBuilder)
                }
                is SseParser.SseLine.Invalid -> continue
            }
        }

        var resultText = textBuilder.toString()

        // OR-B: surface accumulated stream tool_calls through PCP, matching the non-stream path.
        if(toolCallAccumulator.isNotEmpty())
        {
            val pcpRequests = toolCallAccumulator.map { call -> mapOpenRouterToolCallToPcp(call) }
            val toolCallJson = serialize(pcpRequests)
            resultText = if(resultText.isEmpty()) toolCallJson else "$resultText\n\n$toolCallJson"
        }

        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              metadata = mapOf(
                  "responseLength" to resultText.length,
                  "model" to model,  // Already known before request
                  "streaming" to true,
                  "inputTokens" to (streamUsage?.promptTokens ?: 0),
                  "outputTokens" to (streamUsage?.completionTokens ?: 0),
                  "totalTokens" to (streamUsage?.totalTokens ?: 0),
                  "cost" to (streamUsage?.cost ?: 0.0),
                  "success" to true,
                  "apiType" to "ChatAPI"
              ))

        // Stream ended successfully (via [DONE] sentinel or channel EOF).
        emitStreamEnd()

        return resultText
    }

    /**
     * Processes one parsed SSE data chunk: delivers any reasoning delta on
     * the reasoning streaming channel, then delivers visible content on the
     * streaming channel.
     *
     * Reasoning precedence: `delta.reasoning` (the plain string) when
     * present; otherwise the concatenated non-empty `text`/`summary` fields
     * of `delta.reasoning_details` (entries fall back from `text` to
     * `summary`). The reasoning→content transition calls
     * [emitReasoningStreamingEnd] ahead of the first visible content
     * delta — idempotent, so later content deltas are no-ops there and the
     * close marker is emitted exactly once.
     *
     * This is the seam [executeStreaming]'s Data branch delegates to, and
     * the unit-test entry point for reasoning-stream delivery.
     *
     * @param chunk The parsed SSE data chunk
     * @param visibleText Visible-response accumulator maintained by executeStreaming
     */
    suspend fun handleStreamDataChunk(chunk: StreamingChunk, visibleText: StringBuilder)
    {
        val delta = chunk.choices.firstOrNull()?.delta ?: return
        val contentDelta = delta.content ?: ""

        val reasoningText = delta.reasoning?.takeIf { it.isNotEmpty() }
            ?: delta.reasoningDetails
                ?.map { detail -> detail.text?.takeIf { it.isNotEmpty() } ?: detail.summary?.takeIf { it.isNotEmpty() } }
                ?.filterNotNull()
                ?.joinToString("")

        if(!reasoningText.isNullOrEmpty())
        {
            emitReasoningStreamingChunk(reasoningText)
        }

        if(contentDelta.isNotEmpty())
        {
            emitReasoningStreamingEnd()
            visibleText.append(contentDelta)
            emitStreamingChunk(contentDelta)
        }
    }

    /**
     * Maps an OpenRouter [ChatToolCall] to a PCP [PcPRequest], mirroring the Ollama
     * tool-call mapping. OpenAI-compatible `function.arguments` is a JSON-encoded
     * string (unlike Ollama's object), so it is parsed to a [JsonObject] before the
     * parameter values are flattened. The `call_agent_` prefix routes to a TPipe
     * agent invocation, matching the Ollama convention.
     * @param call The tool call to map.
     * @return The PCP request describing the function call.
     */
    private fun mapOpenRouterToolCallToPcp(call: env.ChatToolCall): com.TTT.PipeContextProtocol.PcPRequest
    {
        val request = com.TTT.PipeContextProtocol.PcPRequest()
        val fn = call.function ?: return request
        val functionName = fn.name
        if(functionName.isNullOrBlank()) return request

        val argsObj = fn.arguments?.let { raw -> runCatching { parseJsonOrEmpty(raw) }.getOrNull() }
            ?: buildJsonObject { }

        if(functionName.startsWith("call_agent_"))
        {
            val agentName = functionName.removePrefix("call_agent_")
            val prompt = argsObj["prompt"]?.jsonPrimitive?.content ?: ""
            request.argumentsOrFunctionParams = listOf(agentName, prompt)
        }
        else
        {
            request.tPipeContextOptions.functionName = functionName
            request.argumentsOrFunctionParams = argsObj.values.map { value ->
                if(value is JsonPrimitive) value.content else value.toString()
            }
        }

        return request
    }

    /** Parses a JSON string into a [JsonObject]; returns an empty object on any failure. */
    private fun parseJsonOrEmpty(raw: String): JsonObject
    {
        val parsed = runCatching { orJson.parseToJsonElement(raw) }.getOrNull()
        return parsed as? JsonObject ?: buildJsonObject { }
    }

    /**
     * OR-G: maps an [env.OpenRouterError] to a [P2PError]. Keys off the current-spec fields
     * (integer-or-string `code` + `metadata`) while retaining the legacy `type` branches for
     * older error payloads. Rate-limit (429 / limit_source) -> transport; 402 (payment/billing)
     * -> configuration; 401/400 class -> auth/prompt; 5xx -> transport.
     * @param error The OpenRouter error envelope.
     * @return The mapped P2P error classification.
     */
    private fun mapOpenRouterError(error: env.OpenRouterError): P2PError
    {
        @Suppress("DEPRECATION")
        val errorType = error.type
        val errorCode = error.code?.let { (it as? JsonPrimitive)?.content }
        val limitSource = error.metadata?.get("limit_source")?.let { (it as? JsonPrimitive)?.content }
        val rateLimit = errorType == "rate_limit_error" || errorCode == "429" || limitSource == "rate_limit"

        return when {
            errorType == "auth_error" || errorCode == "401" -> P2PError.auth
            rateLimit -> P2PError.transport
            errorType == "invalid_request_error" || errorType == "invalid_api_key" || errorCode == "400" -> P2PError.prompt
            errorCode == "402" || errorType == "payment_required" -> P2PError.configuration
            errorType == "api_error" || errorType == "server_error" || errorCode?.startsWith("5") == true -> P2PError.transport
            else -> P2PError.transport
        }
    }

    /**
     * OR-J: fetches the OpenRouter model catalog (`GET /models`), giving per-model
     * context_length + supported_parameters + pricing for auto-calibration.
     * @return The list of models, or null on failure.
     */
    suspend fun fetchModelCatalog(): List<env.OpenRouterModel>?
    {
        val client = httpClient ?: throw IllegalStateException("OpenRouterPipe not initialized. Call init() first.")
        return try
        {
            val text = withContext(Dispatchers.IO)
            {
                client.get("$baseUrl/models")
                {
                    header("Authorization", "Bearer $apiKey")
                    if(httpReferer.isNotEmpty()) header("HTTP-Referer", httpReferer)
                    if(openRouterTitle.isNotEmpty()) header("X-OpenRouter-Title", openRouterTitle)
                }.bodyAsText()
            }
            deserialize<env.OpenRouterModelsResponse>(text)?.data
        }
        catch(e: Exception)
        {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION, error = e,
                  metadata = mapOf("endpoint" to "GET /models", "errorType" to (e::class.simpleName ?: "Unknown")))
            null
        }
    }

    /**
     * OR-J: fetches a single model's metadata (`GET /models/{modelId}`) for context-window
     * and supported-parameter calibration.
     * @param modelId The OpenRouter model slug (e.g. "anthropic/claude-3-5-sonnet").
     * @return The model entry, or null on failure.
     */
    suspend fun fetchModelInfo(modelId: String): env.OpenRouterModel?
    {
        val client = httpClient ?: throw IllegalStateException("OpenRouterPipe not initialized. Call init() first.")
        return try
        {
            val text = withContext(Dispatchers.IO)
            {
                client.get("$baseUrl/models/${modelId.replace("/", "%2F")}")
                {
                    header("Authorization", "Bearer $apiKey")
                    if(httpReferer.isNotEmpty()) header("HTTP-Referer", httpReferer)
                    if(openRouterTitle.isNotEmpty()) header("X-OpenRouter-Title", openRouterTitle)
                }.bodyAsText()
            }
            deserialize<env.OpenRouterModelResponse>(text)?.data
        }
        catch(e: Exception)
        {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION, error = e,
                  metadata = mapOf("endpoint" to "GET /models/$modelId", "errorType" to (e::class.simpleName ?: "Unknown")))
            null
        }
    }

    /**
     * OR-J: calibrates this pipe's context window from a catalog-fetched [contextLength],
     * replacing the hardcoded family heuristic for that model.
     * @param contextLength The model's context window in tokens (from [fetchModelInfo]).
     * @return This pipe instance.
     */
    fun setContextLengthFromCatalog(contextLength: Int): OpenRouterPipe
    {
        setContextWindowSize(contextLength)
        return this
    }

//=========================================Context Management==========================================================

    /**
     * Truncates module context using conservative token estimation.
     * OpenRouter supports diverse models from multiple providers. This implementation
     * uses optimized settings for known model families: anthropic/, openai/, google/,
     * deepseek/, and meta-llama/. Other models use conservative defaults.
     * @return This pipe instance
     */
    override fun truncateModuleContext(): Pipe
    {
        val lowerModel = model.lowercase()
        when
        {
            lowerModel.contains("anthropic/") -> {
                // Calibrated for Claude tokenizers (736 tokens for DEFAULT_TEST_STRING)
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = true
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = true
                countSubWordsIfSplit = true
                nonWordSplitCount = 1
                tokenCountingBias = -0.0528
            }
            lowerModel.contains("openai/") -> {
                // Calibrated for GPT-4o/o200k_base tokenizer (648 tokens for DEFAULT_TEST_STRING)
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = false
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = false
                nonWordSplitCount = 2
                tokenCountingBias = -0.0107
            }
            lowerModel.contains("google/") -> {
                // Google Gemini - uses SentencePiece, calibrated conservatively
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = false
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = false
                nonWordSplitCount = 2
                tokenCountingBias = 0.0
            }
            lowerModel.contains("deepseek/") -> {
                // DeepSeek - calibrated for DeepSeek tokenizers
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
                tokenCountingBias = 0.0103
            }
            lowerModel.contains("meta-llama/") -> {
                // Meta Llama - calibrated for Llama tokenizers
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
                tokenCountingBias = 0.0
            }
            lowerModel.contains("mistralai/") -> {
                // Mistral - calibrated for Mistral tokenizers
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
                tokenCountingBias = 0.0
            }
            lowerModel.contains("cohere/") -> {
                // Cohere - calibrated for Command/M-command tokenizers
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
                tokenCountingBias = 0.0
            }
            lowerModel.contains("qwen/") -> {
                // Qwen - calibrated for Qwen tokenizers
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
                tokenCountingBias = 0.0
            }
            lowerModel.contains("minimax/") -> {
                // MiniMax - calibrated conservatively
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
                tokenCountingBias = 0.0
            }
            lowerModel.contains("nvidia/") -> {
                // NVIDIA NIM - uses OpenAI-compatible tokenizers
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = false
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = false
                nonWordSplitCount = 2
                tokenCountingBias = -0.0107
            }
            lowerModel.contains("cognitivecomputations/") -> {
                // Dolphin models - similar to Llama
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
                tokenCountingBias = 0.0
            }
            lowerModel.contains("liquid/") -> {
                // Liquid models
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
                tokenCountingBias = 0.0
            }
            lowerModel.contains("arcee-ai/") -> {
                // Arcee AI
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
                tokenCountingBias = 0.0
            }
            lowerModel.contains("z-ai/") -> {
                // Z-AI models
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
                tokenCountingBias = 0.0
            }
            lowerModel.contains("nousresearch/") -> {
                // Nous Research models - similar to Llama
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
                tokenCountingBias = 0.0
            }
            lowerModel.contains("openrouter/free") -> {
                // OpenRouter free models - conservative defaults
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = false
                nonWordSplitCount = 3
                tokenCountingBias = 0.0
            }
            lowerModel.contains("ai21/") -> {
                // AI21 Jurassic models
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
                tokenCountingBias = 0.0
            }
        }
        if(truncateContextAsString)
        {
            contextWindow.combineAndTruncateAsString(userPrompt, contextWindowSize, multiplyWindowSizeBy, contextWindowTruncation, countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound, splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount)
        }
        else
        {
            contextWindow.selectAndTruncateContext(userPrompt, contextWindowSize, multiplyWindowSizeBy, contextWindowTruncation, countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound, splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount)
        }
        return this
    }

    /**
     * Suspendable truncation delegate.
     * @return This pipe instance
     */
    override suspend fun truncateModuleContextSuspend(): Pipe
    {
        return truncateModuleContext()
    }

//=========================================HTTP Client Factory=======================================================

    /**
     * Factory for the HTTP client. Extracted to enable abort() to recreate the client
     * instead of nulling it — matching the pattern established in GenericOpenAIPipe.kt:837-838.
     * The prior regression (nulling the client after close) left the pipe in a dead state where
     * the next generateText() call threw IllegalStateException without a retry path.
     */
    private fun createHttpClient(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

//=========================================ProviderInterface==========================================================

    /**
     * Cleans prompt text for OpenRouter compatibility.
     * OpenRouter is OpenAI-compatible, so no special cleanup is needed.
     * @param content The content to clean
     * @return The cleaned content
     */
    override fun cleanPromptText(content: MultimodalContent): MultimodalContent
    {
        return content
    }
}
