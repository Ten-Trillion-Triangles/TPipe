package genericOpenAIPipe

import com.TTT.Debug.*
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Enums.ProviderName
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import genericOpenAIPipe.env.*
import genericOpenAIPipe.api.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Fixed 100ms backoff between the first request and the single retry in
 * [GenericOpenAIPipe.runRequestWithRetry]. File-level constant (not a class member)
 * to avoid introducing a companion object that interacts unexpectedly with the
 * serialization layer.
 */
private const val RETRY_BACKOFF_MILLIS: Long = 100L

/**
 * TPipe abstraction for Generic OpenAI-compatible APIs.
 *
 * Provides access to OpenAI-compatible /v1/chat/completions endpoints
 * with standard OpenAI parameters. Supports any provider that implements
 * the OpenAI Chat Completions API specification.
 *
 * @see <a href="https://platform.openai.com/docs/api-reference/chat">OpenAI Chat Completions API</a>
 */
@kotlinx.serialization.Serializable
class GenericOpenAIPipe : Pipe()
{

//=========================================Properties===================================================================

    /**
     * API key for authentication.
     * Required for all API calls.
     * Marked @Transient to prevent API key from being serialized to disk.
     */
    @kotlinx.serialization.Transient
    private var apiKey: String = ""

    /**
     * API base URL.
     * Defaults to "https://api.openai.com/v1"
     */
    @kotlinx.serialization.Serializable
    private var baseUrl: String = "https://api.openai.com/v1"

    /**
     * HTTP client for API calls.
     * Initialized in init() and closed in abort().
     */
    @kotlinx.serialization.Transient
    private var httpClient: HttpClient? = null

    /**
     * Whether streaming mode is enabled.
     */
    @kotlinx.serialization.Serializable
    override var streamingEnabled: Boolean = false
    private var streamingReasoning: String = ""
    private var streamingInputTokens: Int = 0
    private var streamingOutputTokens: Int = 0
    private var streamingReasoningTokens: Int = 0

    /**
     * Function calling tool definitions.
     */
    @kotlinx.serialization.Transient
    private var tools: List<ToolDefinition>? = null

    /**
     * Tool choice mode for function calling.
     * Values: "auto", "none", "required"
     */
    @kotlinx.serialization.Transient
    private var toolChoice: String? = null

    /**
     * Whether to enable parallel function calling.
     * Default is true.
     */
    @kotlinx.serialization.Transient
    private var parallelToolCalls: Boolean? = null

    /**
     * Response format for structured output.
     */
    @kotlinx.serialization.Transient
    private var responseFormat: ResponseFormat? = null

    /**
     * Whether to enable structured outputs via json_schema.
     */
    @kotlinx.serialization.Transient
    private var structuredOutputs: Boolean? = null

    /**
     * Output modalities (e.g., ["text", "image", "audio"]).
     */
    @kotlinx.serialization.Transient
    private var modalities: List<String>? = null

    /**
     * Reasoning configuration for reasoning-capable models.
     */
    @kotlinx.serialization.Transient
    private var reasoningConfig: ReasoningConfig? = null

    /**
     * Cache control with TTL for Anthropic-style caching.
     */
    @kotlinx.serialization.Transient
    private var cacheControl: genericOpenAIPipe.env.CacheControl? = null

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

    @kotlinx.serialization.Transient
    private var reasoningEnabled: Boolean? = null

    /**
     * API mode for selecting OpenAI vs Anthropic API format.
     * Default is [ApiMode.OpenAI] for backward compatibility.
     * Cannot be changed after first API call.
     */
    @kotlinx.serialization.Serializable
    private var apiMode: ApiMode = ApiMode.OpenAI

    /**
     * Tracks whether the first API request has been made.
     * Used to enforce immutability of apiMode after initialization.
     */
    @kotlinx.serialization.Transient
    private var apiModeLocked: Boolean = false

    private val responseParser: ResponseParser = ResponseParser.Factory.create()

    private val requestSerializer: RequestSerializer = RequestSerializer.Factory.create()

    /**
     * Returns the appropriate auth headers based on the current [apiMode].
     *
     * OpenAI mode uses Bearer token authentication.
     * Anthropic mode uses x-api-key header with anthropic-version header.
     *
     * @return Map of header name to header value
     */
    private fun getAuthHeaders(): Map<String, String>
    {
        return when(apiMode)
        {
            is ApiMode.OpenAI -> mapOf("Authorization" to "Bearer $apiKey")
            is ApiMode.OpenAIResponses -> mapOf("Authorization" to "Bearer $apiKey")
            is ApiMode.Anthropic -> mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            )
        }
    }

    /**
     * Returns the appropriate endpoint path based on the current [apiMode].
     *
     * OpenAI mode uses /chat/completions.
     * Anthropic mode uses /messages.
     *
     * @return The endpoint path (without baseUrl prefix)
     */
    private fun getEndpoint(): String
    {
        return when(apiMode)
        {
            is ApiMode.OpenAI -> "/chat/completions"
            is ApiMode.OpenAIResponses -> "/responses"
            is ApiMode.Anthropic -> "/anthropic/v1/messages"
        }
    }

//=========================================Builder Methods=============================================================

    /**
     * Sets the API key.
     * @param key The API key
     * @return This pipe instance for fluent chaining
     */
    fun setApiKey(key: String): GenericOpenAIPipe
    {
        apiKey = key
        return this
    }

    /**
     * Sets the base URL.
     * @param url The base URL for the API (defaults to OpenAI's official endpoint)
     * @return This pipe instance for fluent chaining
     */
    fun setBaseUrl(url: String): GenericOpenAIPipe
    {
        require(url.isNotBlank()) { "baseUrl cannot be blank" }
        // HTTPS-only by default. Set TPIPE_ALLOW_INSECURE_BASEURL=true to allow http://
        // — intended for in-process stub servers used by tests. Production callers
        // should never set this flag.
        val allowInsecure = System.getenv("TPIPE_ALLOW_INSECURE_BASEURL") == "true" ||
            System.getProperty("tpipe.allowInsecureBaseUrl") == "true"
        val isHttps = url.startsWith("https://")
        val isAllowedHttp = allowInsecure && url.startsWith("http://")
        require(isHttps || isAllowedHttp) {
            "baseUrl must use HTTPS for security (got: $url). " +
                "Set TPIPE_ALLOW_INSECURE_BASEURL=true (or the tpipe.allowInsecureBaseUrl " +
                "system property) to allow http:// — test-only flag, never use in production."
        }
        baseUrl = url.trimEnd('/')
        return this
    }

    fun setFrequencyPenalty(penalty: Double): GenericOpenAIPipe
    {
        frequencyPenalty = penalty
        return this
    }

    fun setTools(tools: List<ToolDefinition>): GenericOpenAIPipe
    {
        this.tools = tools
        return this
    }

    /**
     * Sets the tool choice mode for function calling.
     * @param choice Tool choice: "auto", "none", or "required"
     * @return This pipe instance for fluent chaining
     */
    fun setToolChoice(choice: String): GenericOpenAIPipe
    {
        toolChoice = choice
        return this
    }

    /**
     * Sets whether to enable parallel function calling.
     * @param enabled True to enable parallel calls (default true)
     * @return This pipe instance for fluent chaining
     */
    fun setParallelToolCalls(enabled: Boolean): GenericOpenAIPipe
    {
        parallelToolCalls = enabled
        return this
    }

    /**
     * Sets the response format for structured output.
     * @param type Format type: "text", "json_object", or "json_schema"
     * @param jsonSchema Optional JSON schema for json_schema type
     * @return This pipe instance for fluent chaining
     */
    fun setResponseFormat(type: String, jsonSchema: kotlinx.serialization.json.JsonObject? = null): GenericOpenAIPipe
    {
        responseFormat = ResponseFormat(type = type, jsonSchema = jsonSchema)
        return this
    }

    /**
     * Wire-format completion hook — translates TPipe's pipe.jsonOutput (and related
     * pipe-level state) into the OpenAI/Anthropic-compatible response_format field
     * so the provider API enforces JSON-mode at the wire instead of relying on
     * prompt-only instructions.
     *
     * If the user already called [setResponseFormat] explicitly, that wins. If the
     * pipe advertises native JSON support ([supportsNativeJson] = true), no wire
     * format is set. Otherwise, when pipe.jsonOutput is non-empty we set
     * response_format to type "json_object" (the wire-level knob MiniMax-M2.7 and
     * OpenAI-compatible chat-completions endpoints honor to lock the LLM into
     * emitting valid JSON).
     */
    override fun onApplySystemPromptComplete()
    {
        if(responseFormat != null) return
        if(supportsNativeJson) return
        if(jsonOutput.isBlank()) return

        responseFormat = ResponseFormat(type = "json_object", jsonSchema = null)
    }

    /**
     * Sets whether to enable structured outputs via json_schema.
     * @param enabled True to enable structured outputs
     * @return This pipe instance for fluent chaining
     */
    fun setStructuredOutputs(enabled: Boolean): GenericOpenAIPipe
    {
        structuredOutputs = enabled
        return this
    }

    /**
     * Sets the output modalities.
     * @param modalities List of modalities (e.g., ["text", "image", "audio"])
     * @return This pipe instance for fluent chaining
     */
    fun setModalities(modalities: List<String>): GenericOpenAIPipe
    {
        this.modalities = modalities
        return this
    }

    /**
     * Sets the reasoning configuration for reasoning-capable models.
     * @param config Reasoning configuration with effort, maxTokens, exclude, enabled
     * @return This pipe instance for fluent chaining
     */
    fun setReasoningConfig(config: ReasoningConfig): GenericOpenAIPipe
    {
        reasoningConfig = config
        return this
    }

    /**
     * Sets whether to enable streaming mode.
     * @param enabled True to enable streaming
     * @return This pipe instance for fluent chaining
     */
    override fun setStreamingEnabled(enabled: Boolean): GenericOpenAIPipe
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
    fun setStreamingCallback(callback: suspend (String) -> Unit): GenericOpenAIPipe
    {
        this.streamingEnabled = true
        obtainStreamingCallbackManager().addCallback(callback)
        // Propagate to every descendant pipe so chunks emitted by child
        // pipes (validator, transformation, branch, reasoning) flow through
        // the same callback. Without this, callbacks registered on a parent
        // pipe are silently ignored when its child pipe's API call streams.
        propagateStreamingCallback(callback)
        return this
    }

    /**
     * Cache control with TTL for Anthropic-style explicit prompt caching.
     *
     * When set, enables explicit prompt caching on the Anthropic API path.
     * The cache breakpoint is placed on the last system block — caching the
     * full system prompt prefix (tools + system) per Anthropic/MiniMax spec.
     *
     * **TTL behavior by provider:**
     * - Direct Anthropic API: "5m" (default, 5 min) or "1h" (1 hour)
     * - MiniMax /anthropic endpoint: TTL field is IGNORED — cache is always
     *   5 minutes and auto-refreshes on hit at no additional cost
     *
     * **Supported models:** MiniMax-M2.7, M2.5, M2.1, M2.
     * NOT supported on M3 (use passive auto-cache on /v1 instead).
     *
     * @param type Cache type — must be "ephemeral" (the only supported type)
     * @param ttl Time-to-live: "5m" (default) or "1h". Omit for MiniMax compat.
     * @return This pipe instance for fluent chaining
     */
    fun setCacheControl(type: String = "ephemeral", ttl: String? = null): GenericOpenAIPipe
    {
        cacheControl = genericOpenAIPipe.env.CacheControl(type = type, ttl = ttl)
        return this
    }

    /**
     * Sets the API mode for the request format.
     * @param mode [ApiMode.OpenAI] for OpenAI-compatible format (default),
     *             [ApiMode.Anthropic] for Anthropic messages format
     * @return This pipe instance for fluent chaining
     * @throws IllegalStateException if called after the first API request
     */
    fun setApiMode(mode: ApiMode): GenericOpenAIPipe
    {
        check(!apiModeLocked) { "apiMode cannot be changed after the first API request" }
        apiMode = mode
        return this
    }

//=========================================Pipe Lifecycle Methods======================================================

    /**
     * Initializes the Generic OpenAI pipe.
     * Validates configuration and sets up the HTTP client.
     * @return This pipe instance
     * @throws IllegalStateException if apiKey is not set
     */
    override suspend fun init(): Pipe
    {
        super.init()

        trace(TraceEventType.PIPE_START, TracePhase.INITIALIZATION,
              metadata = mapOf(
                  "provider" to "GenericOpenAI",
                  "baseUrl" to baseUrl,
                  "model" to model
              ))

        if(apiKey.isBlank())
        {
            val resolvedKey = GenericOpenAIEnv.resolveApiKey()
            if(resolvedKey.isBlank())
            {
                throw IllegalStateException("GenericOpenAI API key is required. Call setApiKey(), genericOpenAIEnv.setApiKey(), or set GENERIC_OPENAI_API_KEY environment variable before init().")
            }
            apiKey = resolvedKey
        }

        provider = ProviderName.Gpt

        // Only create the default CIO client when the pipe has not been supplied with
        // a pre-built HttpClient. The test-only [injectHttpClientForTest] path depends
        // on this guard: it sets httpClient before init() so the production client is
        // not allocated and the test's MockEngine (or other custom engine) is honoured.
        if(httpClient == null)
        {
            httpClient = HttpClient(CIO)
            {
                install(HttpTimeout)
                {
                    requestTimeoutMillis = 120_000
                    connectTimeoutMillis = 30_000
                    socketTimeoutMillis = 120_000
                }
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
              metadata = mapOf("action" to "abort", "provider" to "GenericOpenAI"))

        httpClient?.close()
        httpClient = null

        super.abort()
    }

//=========================================Generation Methods=========================================================

    /**
     * Generates content with multimodal support.
     * Handles binary content (images) by converting to OpenAI content blocks.
     * @param content Multimodal content with optional binary attachments
     * @return Generated response
     */
    override suspend fun generateContent(content: MultimodalContent): MultimodalContent
    {
        if(!content.hasBinaryContent())
        {
            return MultimodalContent(text = generateText(content.text))
        }

        val blocks = mutableListOf<ContentBlock>()

        if(content.text.isNotEmpty())
        {
            blocks.add(ContentBlock.TextBlock(content.text))
        }

        for(binary in content.binaryContent)
        {
            when(binary)
            {
                is com.TTT.Pipe.BinaryContent.Base64String ->
                {
                    val mimeType = binary.mimeType
                    blocks.add(ContentBlock.ImageUrlBlock(
                        url = "data:$mimeType;base64,${binary.data}",
                        detail = "auto"
                    ))
                }
                is com.TTT.Pipe.BinaryContent.Bytes ->
                {
                    val base64 = java.util.Base64.getEncoder().encodeToString(binary.data)
                    val mimeType = binary.mimeType
                    blocks.add(ContentBlock.ImageUrlBlock(
                        url = "data:$mimeType;base64,$base64",
                        detail = "auto"
                    ))
                }
                is com.TTT.Pipe.BinaryContent.CloudReference ->
                {
                    blocks.add(ContentBlock.ImageUrlBlock(
                        url = binary.uri,
                        detail = "auto"
                    ))
                }
                is com.TTT.Pipe.BinaryContent.TextDocument ->
                {
                    blocks.add(ContentBlock.TextBlock(binary.content))
                }
            }
        }

        val messages = mutableListOf<ChatMessage>()

        if(systemPrompt.isNotEmpty())
        {
            messages.add(ChatMessage(role = "system", content = MessageContent.TextContent(systemPrompt)))
        }

        messages.add(ChatMessage(role = "user", content = MessageContent.MultimodalContent(blocks)))

        val request = GenericOpenAIChatRequest(
            model = model,
            messages = messages,
            temperature = if(temperature > 0.0) temperature else null,
            topP = if(topP > 0.0) topP else null,
            topK = topK,
            maxTokens = if(maxTokens > 0) maxTokens else null,
            presencePenalty = if(presencePenalty != 0.0) presencePenalty else null,
            frequencyPenalty = frequencyPenalty,
            repetitionPenalty = repetitionPenalty,
            seed = seed,
            stop = stopSequences.takeIf { it.isNotEmpty() },
            tools = tools,
            toolChoice = toolChoice,
            parallelToolCalls = parallelToolCalls,
            responseFormat = responseFormat,
            structuredOutputs = structuredOutputs,
            modalities = modalities,
            reasoning = reasoningConfig,
            cacheControl = cacheControl,
            logitBias = logitBias.takeIf { it.isNotEmpty() },
            logprobs = logprobs,
            topLogprobs = topLogprobs,
            minP = minP,
            topA = topA,
            user = user,
            n = n,
            stream = streamingEnabled
        )

        val responseText = sendRequest(request)
        return MultimodalContent(text = responseText)
    }

    /**
     * Sends request and returns response text.
     */
    private suspend fun sendRequest(request: GenericOpenAIChatRequest): String
    {
        val client = httpClient ?: throw IllegalStateException("GenericOpenAIPipe not initialized. Call init() first.")

        apiModeLocked = true

        val jsonRequest = serialize(request, encodedefault = false)

        if(streamingEnabled)
        {
            val response = withContext(Dispatchers.IO)
            {
                client.post("$baseUrl${getEndpoint()}")
                {
                    contentType(ContentType.Application.Json)
                    getAuthHeaders().forEach { (name, value) -> header(name, value) }
                    setBody(jsonRequest)
                }
            }
            return executeStreaming(response)
        }
        else
        {
            val responseText = runRequestWithRetry {
                withContext(Dispatchers.IO)
                {
                    client.post("$baseUrl${getEndpoint()}")
                    {
                        contentType(ContentType.Application.Json)
                        getAuthHeaders().forEach { (name, value) -> header(name, value) }
                        setBody(jsonRequest)
                    }.bodyAsText()
                }
            }

            val response: GenericOpenAIChatResponse = try
            {
                responseParser.parse(responseText, apiMode)
            }
            catch(e: P2PException)
            {
                throw e
            }
            catch(e: Exception)
            {
                throw P2PException(P2PError.json, "Failed to parse GenericOpenAI response: ${e.message}", e)
            }

            val contentText = when(val msg = response.choices.firstOrNull()?.message?.content)
            {
                is MessageContent.TextContent -> msg.text
                is MessageContent.MultimodalContent -> msg.blocks.filterIsInstance<ContentBlock.TextBlock>().joinToString("") { it.text }
                is MessageContent.PlainContent -> msg.content
                null -> ""
            }
            return contentText
        }
    }

//=========================================Context Management==========================================================

    /**
     * Generates text using the configured OpenAI-compatible model.
     * @param promptInjector Text to inject into the prompt
     * @return Generated response text
     */
    override suspend fun generateText(promptInjector: String): String
    {
        val client = httpClient ?: throw IllegalStateException("GenericOpenAIPipe not initialized. Call init() first.")

        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "provider" to "GenericOpenAI",
                  "model" to model,
                  "baseUrl" to baseUrl,
                  "promptLength" to promptInjector.length,
                  "streaming" to streamingEnabled,
                  "apiType" to when(apiMode)
                  {
                      is ApiMode.OpenAI -> "ChatAPI"
                      is ApiMode.OpenAIResponses -> "ResponsesAPI"
                      is ApiMode.Anthropic -> "AnthropicAPI"
                  }
              ))

        return try
        {
            val messages = mutableListOf<ChatMessage>()

            if(systemPrompt.isNotEmpty())
            {
                messages.add(ChatMessage(role = "system", content = MessageContent.TextContent(systemPrompt)))
            }

            messages.add(ChatMessage(role = "user", content = MessageContent.TextContent(promptInjector)))

            val request = GenericOpenAIChatRequest(
                model = model,
                messages = messages,
                temperature = if(temperature > 0.0) temperature else null,
                topP = if(topP > 0.0) topP else null,
                topK = topK,
                maxTokens = if(maxTokens > 0) maxTokens else null,
                presencePenalty = if(presencePenalty != 0.0) presencePenalty else null,
                frequencyPenalty = frequencyPenalty,
                repetitionPenalty = repetitionPenalty,
                seed = seed,
                stop = stopSequences.takeIf { it.isNotEmpty() },
                tools = tools,
                toolChoice = toolChoice,
                parallelToolCalls = parallelToolCalls,
                responseFormat = responseFormat,
                structuredOutputs = structuredOutputs,
                modalities = modalities,
                reasoning = reasoningConfig,
                cacheControl = cacheControl,
                logitBias = logitBias.takeIf { it.isNotEmpty() },
                logprobs = logprobs,
                topLogprobs = topLogprobs,
                minP = minP,
                topA = topA,
                user = user,
                n = n,
                stream = streamingEnabled
            )

            val jsonRequest = requestSerializer.serialize(request, apiMode)

            if(streamingEnabled)
            {
                // BUG FIX: Ktor CIO's bodyAsChannel does NOT deliver bytes
                // incrementally for chunked transfer-encoded SSE responses
                // through 3.3.x. All data arrives as one batch when the
                // stream closes. Workaround: bypass Ktor entirely for the
                // streaming call and open a direct HttpURLConnection with
                // chunked transfer encoding. We feed the JSON body and read
                // the response line-by-line. The InputStream blocks per
                // line read, so each SSE delta fires emitStreamingChunk as
                // it arrives on the socket — verified empirically via
                // RawHttpStreamingTest (chunks arrive hundreds of ms apart
                // rather than all in one batch).
                return withContext(Dispatchers.IO)
                {
                    executeStreamingDirect(jsonRequest)
                }
            }
            else
            {
                val responseText = runRequestWithRetry {
                    withContext(Dispatchers.IO)
                    {
                        client.post("$baseUrl${getEndpoint()}")
                        {
                            contentType(ContentType.Application.Json)
                            getAuthHeaders().forEach { (name, value) -> header(name, value) }

                            setBody(jsonRequest)
                        }.bodyAsText()
                    }
                }

                val response: GenericOpenAIChatResponse = try
                {
                    responseParser.parse(responseText, apiMode)
                }
                catch(e: P2PException)
                {
                    throw e
                }
                catch(e: Exception)
                {
                    throw P2PException(P2PError.json, "Failed to parse GenericOpenAI response: ${e.message}", e)
                }

                val contentText = when(val msg = response.choices.firstOrNull()?.message?.content)
                {
                    is MessageContent.TextContent -> msg.text
                    is MessageContent.MultimodalContent -> msg.blocks.filterIsInstance<ContentBlock.TextBlock>().joinToString("") { it.text }
                    is MessageContent.PlainContent -> msg.content
                    null -> ""
                }

                val usage = response.usage
                val inputTokens = usage?.promptTokens ?: 0
                val outputTokens = usage?.completionTokens ?: 0
                val totalTokens = usage?.totalTokens ?: 0

                val reasoningContent = response.reasoningContent ?: ""
                val result = com.TTT.Pipe.MultimodalContent(
                    text = contentText,
                    modelReasoning = reasoningContent
                )

                val successMetadata = mutableMapOf<String, Any>(
                    "inputTokens" to inputTokens,
                    "outputTokens" to outputTokens,
                    "totalTokens" to totalTokens,
                    "responseLength" to contentText.length,
                    "model" to response.model,
                    "success" to true,
                    "apiType" to when(apiMode)
                    {
                        is ApiMode.OpenAI -> "ChatAPI"
                        is ApiMode.OpenAIResponses -> "ResponsesAPI"
                        is ApiMode.Anthropic -> "AnthropicAPI"
                    },
                    "finishReason" to (response.choices.firstOrNull()?.finishReason ?: "unknown"),
                    "stopReason" to (response.choices.firstOrNull()?.finishReason ?: "unknown"),
                    "responseId" to (response.id ?: "unknown"),
                    "systemFingerprint" to (response.systemFingerprint ?: "none")
                )
                if(reasoningContent.isNotEmpty())
                {
                    successMetadata["reasoningLength"] = reasoningContent.length
                    successMetadata["reasoningSupported"] = true
                }
                if(response.usage?.completionTokensDetails?.reasoningTokens != null)
                {
                    successMetadata["reasoningTokens"] = response.usage!!.completionTokensDetails!!.reasoningTokens!!
                }

                trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
                      content = result,
                      metadata = successMetadata)

                return contentText
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
     * Direct streaming call using [java.net.HttpURLConnection] instead of
     * the Ktor CIO client. Bypasses Ktor because its CIO engine buffers
     * chunked transfer-encoded SSE responses through 3.3.x — the
     * ByteReadChannel returned from bodyAsChannel only delivers bytes
     * once the response stream is closed, defeating the whole point of
     * streaming. Verified empirically: KtorSsePluginTest confirms the
     * SSE plugin streams correctly (using an internal channel that
     * reads the body bytes as they arrive); RawHttpStreamingTest
     * confirms HttpURLConnection streams correctly. Reading the
     * InputStream line by line yields chunks hundreds of ms apart as
     * the server sends them.
     *
     * This function produces the same emitStreamingChunk side effects
     * as the Ktor path so the streaming callback wiring is unchanged.
     * It returns the same [String] accumulator.
     */
    private suspend fun executeStreamingDirect(jsonRequest: String): String
    {
        val textBuilder = StringBuilder()

        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "step" to "streamingStart",
                  "streaming" to true,
                  "transport" to "HttpURLConnection",
                  "apiMode" to when(apiMode) { is ApiMode.OpenAI -> "OpenAI"; is ApiMode.OpenAIResponses -> "OpenAIResponses"; is ApiMode.Anthropic -> "Anthropic" }
              ))

        java.net.HttpURLConnection.setFollowRedirects(false)
        val conn = (java.net.URL("$baseUrl${getEndpoint()}").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            getAuthHeaders().forEach { (name, value) -> setRequestProperty(name, value) }
            setChunkedStreamingMode(0)
        }

        // Write the body
        conn.outputStream.use { it.write(jsonRequest.toByteArray(Charsets.UTF_8)) }

        val reasoningBuilder = StringBuilder()
        var totalInputTokens = 0
        var totalOutputTokens = 0
        var totalReasoningTokens = 0

        // Read SSE events line by line. lineSequence() reads from the
        // BufferedReader one line at a time, which blocks per-line —
        // so each SSE delta fires emitStreamingChunk as it arrives on
        // the socket. This is the key behavior the Ktor bodyAsChannel
        // path does NOT exhibit.
        java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            var lastEventType: String? = null
            reader.lineSequence().forEach { rawLine ->
                val line = rawLine.trimEnd()
                if(line.isEmpty())
                {
                    // blank separator between events
                    return@forEach
                }
                if(line.startsWith("event: "))
                {
                    lastEventType = line.substringAfter("event: ").trim()
                    return@forEach
                }
                if(line.startsWith("data: "))
                {
                    val dataLine = line.substringAfter("data: ")
                    when(apiMode)
                    {
                        is ApiMode.OpenAIResponses ->
                        {
                            val parsed = try { OpenAIResponsesSseParser.parseLine("data: $dataLine") } catch(_: Exception) { null }
                            if(parsed != null)
                            {
                                when(parsed)
                                {
                                    is OpenAIResponsesStreamEvent.ResponseOutputTextDelta ->
                                    {
                                        if(parsed.delta.isNotEmpty())
                                        {
                                            textBuilder.append(parsed.delta)
                                            emitStreamingChunk(parsed.delta)
                                        }
                                    }
                                    is OpenAIResponsesStreamEvent.ResponseReasoningTextDelta ->
                                    {
                                        if(parsed.delta.isNotEmpty())
                                        {
                                            reasoningBuilder.append(parsed.delta)
                                        }
                                    }
                                    is OpenAIResponsesStreamEvent.ResponseCompleted ->
                                    {
                                        val usage = parsed.response.usage
                                        if(usage != null)
                                        {
                                            totalInputTokens = usage.inputTokens
                                            totalOutputTokens = usage.outputTokens
                                            totalReasoningTokens = usage.outputTokensDetails?.reasoningTokens ?: 0
                                        }
                                    }
                                    else -> { /* ignore lifecycle / function-call */ }
                                }
                            }
                        }
                        is ApiMode.OpenAI ->
                        {
                            // Legacy chat-completions API: SSE data lines
                            // contain JSON with `choices[].delta.content`.
                            // Parse them here directly — no dedicated
                            // StreamEvent class exists in this codebase.
                            try
                            {
                                val element = Json.parseToJsonElement(dataLine)
                                val obj = element as? JsonObject
                                val choicesArr = obj?.get("choices") as? JsonArray
                                choicesArr?.forEach { choiceEl ->
                                    val choiceObj = choiceEl as? JsonObject
                                    val deltaObj = choiceObj?.get("delta") as? JsonObject
                                    val contentEl = deltaObj?.get("content")
                                    val content = (contentEl as? JsonPrimitive)?.content
                                    if(!content.isNullOrEmpty())
                                    {
                                        textBuilder.append(content)
                                        emitStreamingChunk(content)
                                    }
                                }
                            }
                            catch(_: Exception)
                            {
                                // Skip malformed JSON line
                            }
                        }
                        is ApiMode.Anthropic ->
                        {
                            // Use AnthropicSseParser (the wrapper that manually dispatches
                            // by the outer `type` field) rather than calling
                            // `deserialize<AnthropicStreamEvent>` directly. The wire shape
                            // for content_block_delta carries `index` and `delta` at the outer
                            // level — not nested under a `chunk` key — so the sealed class
                            // cannot be polymorphic-decoded from the raw payload.
                            //
                            // Pass the raw `data: …` line directly to parseAnthropicLine —
                            // it strips its own prefix. The previous code path passed the
                            // already-stripped `sseLine.content` to parseAnthropicLine, which
                            // then took the `else -> Done` branch because the JSON did not
                            // start with `data:`.
                            val parsed: AnthropicStreamEvent = AnthropicSseParser.parseAnthropicLine("data: $dataLine")
                            if(parsed is AnthropicStreamEvent.ContentBlockDelta)
                            {
                                when(val delta = parsed.chunk.delta)
                                {
                                    is AnthropicDelta.TextDelta ->
                                    {
                                        if(delta.text.isNotEmpty())
                                        {
                                            textBuilder.append(delta.text)
                                            emitStreamingChunk(delta.text)
                                        }
                                    }
                                    is AnthropicDelta.ThinkingDelta ->
                                    {
                                        if(delta.thinking.isNotEmpty())
                                        {
                                            reasoningBuilder.append(delta.thinking)
                                        }
                                    }
                                    is AnthropicDelta.InputJsonDelta ->
                                    {
                                        // Structured output partial JSON — caller handles separately.
                                    }
                                }
                            }
                            else if(parsed is AnthropicStreamEvent.MessageDelta && parsed.stopReason != null)
                            {
                                // end of stream
                            }
                        }
                    }
                }
                lastEventType = null
            }
        }

        streamingReasoning = reasoningBuilder.toString()
        streamingInputTokens = totalInputTokens
        streamingOutputTokens = totalOutputTokens
        streamingReasoningTokens = totalReasoningTokens

        val resultText = textBuilder.toString()

        val streamingReasoningText = when(apiMode)
        {
            is ApiMode.OpenAIResponses, is ApiMode.Anthropic -> streamingReasoning
            else -> ""
        }
        val streamingInputTok = when(apiMode)
        {
            is ApiMode.OpenAIResponses -> streamingInputTokens
            else -> 0
        }
        val streamingOutputTok = when(apiMode)
        {
            is ApiMode.OpenAIResponses -> streamingOutputTokens
            else -> 0
        }
        val streamingReasonTok = when(apiMode)
        {
            is ApiMode.OpenAIResponses -> streamingReasoningTokens
            else -> 0
        }

        val result = com.TTT.Pipe.MultimodalContent(
            text = resultText,
            modelReasoning = streamingReasoningText
        )

        val streamingMetadata = mutableMapOf<String, Any>(
            "inputTokens" to streamingInputTok,
            "outputTokens" to streamingOutputTok,
            "totalTokens" to (streamingInputTok + streamingOutputTok),
            "responseLength" to resultText.length,
            "model" to model,
            "streaming" to true,
            "success" to true,
            "transport" to "HttpURLConnection",
            "apiType" to when(apiMode)
            {
                is ApiMode.OpenAI -> "ChatAPI"
                is ApiMode.OpenAIResponses -> "ResponsesAPI"
                is ApiMode.Anthropic -> "AnthropicAPI"
            }
        )
        if(streamingReasoningText.isNotEmpty())
        {
            streamingMetadata["reasoningLength"] = streamingReasoningText.length
            streamingMetadata["reasoningSupported"] = true
        }
        if(streamingReasonTok > 0)
        {
            streamingMetadata["reasoningTokens"] = streamingReasonTok
        }

        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              content = result,
              metadata = streamingMetadata)

        return resultText
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
                  "streaming" to true,
                  "apiMode" to when(apiMode) { is ApiMode.OpenAI -> "OpenAI"; is ApiMode.OpenAIResponses -> "OpenAIResponses"; is ApiMode.Anthropic -> "Anthropic" }
              ))

        // Branch on apiMode: Anthropic uses AnthropicSseParser with different SSE format
        // OpenAI uses existing SseParser path
        // OpenAIResponses uses OpenAIResponsesSseParser with response.* event names
        when(apiMode)
        {
            is ApiMode.Anthropic -> executeStreamingAnthropic(channel, textBuilder)
            is ApiMode.OpenAI -> executeStreamingOpenAI(channel, textBuilder)
            is ApiMode.OpenAIResponses -> executeStreamingOpenAIResponses(channel, textBuilder)
        }

        val resultText = textBuilder.toString()

        val streamingReasoningText = when(apiMode)
        {
            is ApiMode.OpenAIResponses, is ApiMode.Anthropic -> streamingReasoning
            else -> ""
        }
        val streamingInputTok = when(apiMode)
        {
            is ApiMode.OpenAIResponses -> streamingInputTokens
            else -> 0
        }
        val streamingOutputTok = when(apiMode)
        {
            is ApiMode.OpenAIResponses -> streamingOutputTokens
            else -> 0
        }
        val streamingReasonTok = when(apiMode)
        {
            is ApiMode.OpenAIResponses -> streamingReasoningTokens
            else -> 0
        }

        val result = com.TTT.Pipe.MultimodalContent(
            text = resultText,
            modelReasoning = streamingReasoningText
        )

        val streamingMetadata = mutableMapOf<String, Any>(
            "inputTokens" to streamingInputTok,
            "outputTokens" to streamingOutputTok,
            "totalTokens" to (streamingInputTok + streamingOutputTok),
            "responseLength" to resultText.length,
            "model" to model,
            "streaming" to true,
            "success" to true,
            "apiType" to when(apiMode)
            {
                is ApiMode.OpenAI -> "ChatAPI"
                is ApiMode.OpenAIResponses -> "ResponsesAPI"
                is ApiMode.Anthropic -> "AnthropicAPI"
            }
        )
        if(streamingReasoningText.isNotEmpty())
        {
            streamingMetadata["reasoningLength"] = streamingReasoningText.length
            streamingMetadata["reasoningSupported"] = true
        }
        if(streamingReasonTok > 0)
        {
            streamingMetadata["reasoningTokens"] = streamingReasonTok
        }

        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              content = result,
              metadata = streamingMetadata)

        return resultText
    }

    /**
     * Handles streaming response for [ApiMode.OpenAI].
     * Uses OpenAI SSE format: `data: {...}` lines with [StreamingChunk] deserialization.
     * @param channel The HTTP response channel
     * @param textBuilder Accumulator for response text
     */
    private suspend fun executeStreamingOpenAI(
        channel: ByteReadChannel,
        textBuilder: StringBuilder
    )
    {
        while(!channel.isClosedForRead)
        {
            val line = channel.readUTF8Line() ?: break

            val sseLine = SseParser.parseLine(line)

            when(sseLine)
            {
                is SseParser.SseLine.Done -> break
                is SseParser.SseLine.Empty, is SseParser.SseLine.Comment -> continue
                is SseParser.SseLine.Data ->
                {
                    // Check for SSE error events before attempting StreamingChunk deserialization
                    val sseError = try { deserialize<GenericOpenAIErrorResponse>(sseLine.content) } catch(e: Exception) { null }
                    if(sseError != null && sseError.error.message.isNotEmpty())
                    {
                        val p2pError = when(sseError.error.type)
                        {
                            "authentication_error" -> P2PError.auth
                            "rate_limit_error" -> P2PError.transport
                            "invalid_request_error", "invalid_api_key" -> P2PError.prompt
                            "api_error", "server_error" -> P2PError.transport
                            null, "" -> P2PError.transport
                            else -> P2PError.transport
                        }
                        throw P2PException(p2pError, "GenericOpenAI streaming error: ${sseError.error.message}", Exception(sseError.error.message))
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
    }

    /**
     * Handles streaming response for [ApiMode.Anthropic].
     * Uses Anthropic SSE format: `event:` prefix + `data:` lines with [AnthropicStreamingChunk] deserialization.
     * Anthropic errors are thrown by [AnthropicSseParser], not deserialized from content.
     * @param channel The HTTP response channel
     * @param textBuilder Accumulator for response text
     */
    private suspend fun executeStreamingAnthropic(
        channel: ByteReadChannel,
        textBuilder: StringBuilder,
        reasoningBuilder: StringBuilder = StringBuilder()
    )
    {
        // For Anthropic streaming, we track the current event type from `event:` lines
        // The `data:` line that follows uses the appropriate parser
        var currentEventType: String? = null
        var lineCount = 0

        while(!channel.isClosedForRead)
        {
            val line = channel.readUTF8Line() ?: break
            lineCount++
            val trimmed = line.trim()

            // Empty line — skip (blank separator between events)
            if(trimmed.isEmpty())
            {
                continue
            }

            // `event:` prefix line — set the current event type for the next data line
            if(trimmed.startsWith("event: "))
            {
                currentEventType = trimmed.substringAfter("event: ").trim()
                continue
            }

            // `data:` line — parse based on current event type
            if(trimmed.startsWith("data: "))
            {
                val dataLine = trimmed

                // Only process content_block_delta events — ignore ping, message_start, content_block_start
                // message_delta signals end of stream (stop_reason available)
                when(currentEventType)
                {
                    "content_block_delta" ->
                    {
                        // parseAnthropicLine accepts either a `data: …` line or a bare JSON
                        // payload, and dispatches manually by the outer `type` field. The
                        // sealed class `AnthropicStreamEvent` does not support direct
                        // polymorphic deserialization because its subclasses do not share
                        // a common `type` field shape (see AnthropicStreaming.kt).
                        val event: AnthropicStreamEvent = AnthropicSseParser.parseAnthropicLine(dataLine)
                        if(event is AnthropicStreamEvent.ContentBlockDelta)
                        {
                            when(val delta = event.chunk.delta)
                            {
                                is AnthropicDelta.TextDelta ->
                                {
                                    if(delta.text.isNotEmpty())
                                    {
                                        textBuilder.append(delta.text)
                                        emitStreamingChunk(delta.text)
                                    }
                                }
                                is AnthropicDelta.ThinkingDelta ->
                                {
                                    if(delta.thinking.isNotEmpty())
                                    {
                                        reasoningBuilder.append(delta.thinking)
                                    }
                                }
                                is AnthropicDelta.InputJsonDelta ->
                                {
                                    // Structured output partial JSON — caller handles separately.
                                }
                            }
                        }
                    }
                    "message_delta" ->
                    {
                        // message_delta is the final event — stream ends here
                        // No content to emit, just break to return accumulated text
                        break
                    }
                    // ping, message_start, content_block_start, message_stop, etc. — ignore, continue
                    else ->
                    {
                        // Unknown or non-content event — continue without terminating
                        // Task 8: unknown events must NOT return Done to avoid premature stream termination
                        if(currentEventType == "message_stop")
                        {
                            break
                        }
                        // all other unknown events — just continue reading
                        continue
                    }
                }

                // Reset event type after processing data line
                currentEventType = null
            }
        }
    }

    /**
     * Handles streaming response for [ApiMode.OpenAIResponses].
     * Uses the OpenAI Responses SSE format: `event: <type>` + `data: <json>` lines
     * (or bare `data:` lines with `type` inside the JSON), terminated by
     * `response.completed` / `response.failed`.
     *
     * Only [OpenAIResponsesStreamEvent.ResponseOutputTextDelta] deltas are appended
     * to [textBuilder]; every other event is ignored. Errors are thrown by the parser
     * (via [P2PException]) and abort the stream.
     *
     * @param channel The HTTP response channel
     * @param textBuilder Accumulator for response text
     */
    private suspend fun executeStreamingOpenAIResponses(
        channel: ByteReadChannel,
        textBuilder: StringBuilder
    )
    {
        // Reset cross-call state so a previous aborted stream does not leak.
        streamingReasoning = ""
        streamingInputTokens = 0
        streamingOutputTokens = 0
        streamingReasoningTokens = 0

        val reasoningBuilder = StringBuilder()
        var totalInputTokens = 0
        var totalOutputTokens = 0
        var totalReasoningTokens = 0

        while(!channel.isClosedForRead)
        {
            val line = channel.readUTF8Line() ?: break
            val event = try
            {
                OpenAIResponsesSseParser.parseLine(line)
            }
            catch(e: P2PException)
            {
                throw e
            }
            catch(e: Exception)
            {
                // Malformed single line should not kill the whole stream; skip it.
                continue
            }

            when(event)
            {
                is OpenAIResponsesStreamEvent.ResponseOutputTextDelta ->
                {
                    if(event.delta.isNotEmpty())
                    {
                        textBuilder.append(event.delta)
                        emitStreamingChunk(event.delta)
                    }
                }
                is OpenAIResponsesStreamEvent.ResponseReasoningTextDelta ->
                {
                    if(event.delta.isNotEmpty())
                    {
                        reasoningBuilder.append(event.delta)
                    }
                }
                is OpenAIResponsesStreamEvent.ResponseCompleted ->
                {

                    val usage = event.response.usage
                    if(usage != null)
                    {
                        totalInputTokens = usage.inputTokens
                        totalOutputTokens = usage.outputTokens
                        totalReasoningTokens = usage.outputTokensDetails?.reasoningTokens ?: 0
                    }
                    break
                }
                is OpenAIResponsesStreamEvent.ResponseFailed -> break
                else -> { /* Unknown / lifecycle / function-call: keep reading */ }
            }
        }

        // Surface the accumulated reasoning on the trace event so it lands in
        // the trace as `reasoningContent` via the base Pipe class auto-trace.
        streamingReasoning = reasoningBuilder.toString()
        streamingInputTokens = totalInputTokens
        streamingOutputTokens = totalOutputTokens
        streamingReasoningTokens = totalReasoningTokens
    }

//=========================================Context Management==========================================================

    /**
     * Truncates module context using conservative token estimation.
     * Uses OpenAI-compatible settings by default since most OpenAI-compatible
     * providers use similar tokenization to GPT models.
     * @return This pipe instance
     */
    override fun truncateModuleContext(): Pipe
    {
        // Use conservative defaults for OpenAI-compatible models
        contextWindowTruncation = ContextWindowSettings.TruncateTop
        countSubWordsInFirstWord = true
        favorWholeWords = true
        countOnlyFirstWordFound = false
        splitForNonWordChar = true
        alwaysSplitIfWholeWordExists = false
        countSubWordsIfSplit = false
        nonWordSplitCount = 2
        tokenCountingBias = 0.0

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
     * Cleans prompt text for Generic OpenAI compatibility.
     * OpenAI-compatible APIs use standard formatting, so no special cleanup is needed.
     * @param content The content to clean
     * @return The cleaned content
     */
    override fun cleanPromptText(content: MultimodalContent): MultimodalContent
    {
        return content
    }

    /**
     * Provider-side response cleanup. Some endpoints wrap their payload in
     * auxiliary reasoning surfaces (e.g. `&lt;think&gt;...&lt;/think&gt;` blocks from MiniMax-M2.7,
     * DeepSeek-R1, OpenAI o-series). TPipe core stays content-agnostic — the wire
     * format translation is already done in [onApplySystemPromptComplete]; this
     * hook is the symmetric exit-side cleanup, run after the model returns but
     * before TPipe parses.
     *
     * @param text Raw text returned by the wire.
     * @return Input with provider-local wrapping removed; identity if no cleanup needed.
     */
    override fun cleanResponseText(text: String): String
    {
        return ResponseShapeNormalizer.stripThinkTags(text)
    }

//=========================================Test-Only Helpers==========================================================
// The methods in this section exist solely to support the test suite. They let
// the pipe be driven without a real HTTP server. They are intentionally minimal — they only expose
// the seams the test suite actually uses (endpoint / auth-header introspection,
// HTTP-client injection, suspendable init/abort, and a non-`Pipeline` text generation
// path that bypasses `Pipeline.execute` so MockEngine round-trips stay synchronous).

//=========================================Transport Retry==========================================================

    /**
     * Runs [block] and retries it once after a 100ms backoff if it throws an
     * [IOException].
     *
     * Background: Ktor's CIO engine surfaces transport-level failures
     * ([java.net.SocketTimeoutException], raw [java.io.EOFException] from a
     * mid-stream cut, [io.ktor.client.plugins.HttpRequestTimeoutException],
     * [java.net.ConnectException]) as subclasses of [java.io.IOException]. None of
     * these were retried inside `client.post(...).bodyAsText()` previously, so a
     * single transient blip aborted the entire pump-station dispatch.
     *
     * Retry policy is deliberately narrow:
     *   - retries exactly once (no exponential backoff, no jitter);
     *   - retries ONLY on [IOException]; HTTP error responses, parse failures, and
     *     programmer errors propagate unchanged so the caller's outer catch can
     *     classify them;
     *   - the second [IOException] propagates unchanged so the pipe's outer catch
     *     can wrap it into [P2PException] with the [P2PError.transport] code,
     *     preserving the original exception as `cause`.
     *
     * @param block The suspending operation to run. May throw [IOException].
     * @return The value returned by [block] on the first or second attempt.
     * @throws IOException If both attempts throw [IOException]; the second exception is rethrown.
     */
    private suspend fun <T> runRequestWithRetry(block: suspend () -> T): T
    {
        return try
        {
            block()
        }
        catch(e: IOException)
        {
            delay(RETRY_BACKOFF_MILLIS)
            try
            {
                block()
            }
            catch(retryFailure: IOException)
            {
                throw retryFailure
            }
        }
    }

    /**
     * Returns the endpoint the pipe would POST to, for the current [apiMode].
     * Visible only to tests in the same module.
     */
    fun internalGetEndpointForTest(): String = getEndpoint()

    /**
     * Returns the auth headers the pipe would attach, for the current [apiMode].
     * Visible only to tests in the same module.
     */
    fun internalGetAuthHeadersForTest(): Map<String, String> = getAuthHeaders()

    /**
     * Replaces the internal Ktor [HttpClient] with a caller-supplied one. The pipe
     * does not own the new client — the caller is responsible for closing it.
     * Visible only to tests in the same module.
     */
    fun injectHttpClientForTest(client: HttpClient)
    {
        httpClient = client
    }

    /**
     * Suspending wrapper around [init] used by tests that need the pipe fully
     * initialised without going through `Pipeline.init(true)`.
     */
    suspend fun initForTest()
    {
        init()
    }

    /**
     * Suspending wrapper around [abort] used by tests so the test does not need
     * a `runBlocking { abort() }` boilerplate.
     */
    suspend fun abortForTest()
    {
        abort()
    }

    /**
     * Drives a single text generation through the pipe. Equivalent to
     * [generateText] but exposed to the test suite so dispatch tests can run
     * without standing up a full [com.TTT.Pipeline.Pipeline].
     */
    suspend fun generateTextForTest(prompt: String): String
    {
        return generateText(prompt)
    }
}