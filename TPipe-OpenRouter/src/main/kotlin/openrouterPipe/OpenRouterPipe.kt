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
    private var streamingEnabled: Boolean = false

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
    fun setStreamingEnabled(enabled: Boolean): OpenRouterPipe
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

        httpClient = HttpClient(CIO)
        {
            install(HttpTimeout)
            {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
        }

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
        httpClient = null

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
            val messages = mutableListOf<ChatMessage>()

            if(systemPrompt.isNotEmpty())
            {
                messages.add(ChatMessage(role = "system", content = systemPrompt))
            }

            messages.add(ChatMessage(role = "user", content = promptInjector))

            val request = OpenRouterChatRequest(
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
                structuredOutputs = structuredOutputs,
                reasoning = reasoningConfig,
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
                verbosity = verbosity,
                sessionId = sessionId,
                stream = streamingEnabled
            )

            val jsonRequest = serialize(request, encodedefault = false)

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
                if(errorResponse != null && (errorResponse.error.type != null || errorResponse.error.code != null))
                {
                    val errorType = errorResponse.error.type
                    val errorCode = errorResponse.error.code

                    val p2pError = when {
                        errorType == "auth_error" || errorCode == "401" -> P2PError.auth
                        errorType == "rate_limit_error" || errorCode == "429" -> P2PError.transport
                        errorType == "invalid_request_error" || errorType == "invalid_api_key" || errorCode == "400" -> P2PError.prompt
                        errorType == "api_error" || errorType == "server_error" || errorCode?.startsWith("5") == true -> P2PError.transport
                        else -> P2PError.transport
                    }
                    throw P2PException(p2pError, "OpenRouter error: ${errorResponse.error.message}", Exception(errorResponse.error.message))
                }

                val response: OpenRouterChatResponse = deserialize(responseText)
                    ?: throw P2PException(P2PError.json, "Failed to deserialize OpenRouter chat response: $responseText", Exception("Deserialization failed"))

                val resultText = response.choices.firstOrNull()?.message?.content ?: ""

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
                          "responseLength" to resultText.length,
                          "model" to response.model,
                          "success" to true,
                          "apiType" to "ChatAPI"
                      ))

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
     * Executes a streaming request and accumulates the response.
     * @param httpResponse The HTTP response from the streaming endpoint
     * @return Accumulated response text
     */
    private suspend fun executeStreaming(httpResponse: HttpResponse): String
    {
        val channel = httpResponse.bodyAsChannel()
        val textBuilder = StringBuilder()

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
                    if(sseError != null && sseError.error.type != null)
                    {
                        val p2pError = when(sseError.error.type)
                        {
                            "auth_error" -> P2PError.auth
                            "rate_limit_error" -> P2PError.transport
                            "invalid_request_error", "invalid_api_key" -> P2PError.prompt
                            "api_error", "server_error" -> P2PError.transport
                            else -> P2PError.transport
                        }
                        throw P2PException(p2pError, "OpenRouter streaming error: ${sseError.error.message}", Exception(sseError.error.message))
                    }

                    val chunk = SseParser.parseChunk(sseLine.content) ?: continue
                    val contentDelta = SseParser.extractContent(chunk)

                    if(contentDelta.isNotEmpty())
                    {
                        textBuilder.append(contentDelta)
                        emitStreamingChunk(contentDelta)
                    }
                }
                is SseParser.SseLine.Invalid -> continue
            }
        }

        val resultText = textBuilder.toString()

        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              metadata = mapOf(
                  "responseLength" to resultText.length,
                  "model" to model,  // Already known before request
                  "streaming" to true,
                  "success" to true,
                  "apiType" to "ChatAPI"
              ))

        return resultText
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
