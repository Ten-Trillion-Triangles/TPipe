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
    private var streamingEnabled: Boolean = false

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
        require(url.startsWith("https://")) { "baseUrl must use HTTPS for security" }
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
    fun setStreamingEnabled(enabled: Boolean): GenericOpenAIPipe
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

        val jsonRequest = serialize(request, encodedefault = false)

        if(streamingEnabled)
        {
            val response = withContext(Dispatchers.IO)
            {
                client.post("$baseUrl/chat/completions")
                {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $apiKey")
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
                    setBody(jsonRequest)
                }.bodyAsText()
            }

            val errorResponse = try { deserialize<GenericOpenAIErrorResponse>(responseText) } catch(e: Exception) { null }
            if(errorResponse != null && errorResponse.error.message.isNotEmpty())
            {
                val errorMessage = errorResponse.error.message
                val errorType = errorResponse.error.type
                val errorCode = errorResponse.error.code

                val p2pError = when
                {
                    errorType == "authentication_error" || errorCode == "401" -> P2PError.auth
                    errorType == "rate_limit_error" || errorCode == "429" -> P2PError.transport
                    errorType == "invalid_request_error" || errorType == "invalid_api_key" || errorCode == "400" -> P2PError.prompt
                    errorType == "api_error" || errorType == "server_error" || errorCode?.startsWith("5") == true -> P2PError.transport
                    else -> P2PError.transport
                }
                throw P2PException(p2pError, "GenericOpenAI error: $errorMessage", Exception(errorMessage))
            }

            val response: GenericOpenAIChatResponse = deserialize(responseText)
                ?: throw P2PException(P2PError.json, "Failed to deserialize GenericOpenAI chat response: $responseText", Exception("Deserialization failed"))

            val contentText = when(val msg = response.choices.firstOrNull()?.message?.content)
            {
                is MessageContent.TextContent -> msg.text
                is MessageContent.MultimodalContent -> msg.blocks.filterIsInstance<ContentBlock.TextBlock>().joinToString("") { it.text }
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
                  "streaming" to streamingEnabled
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

            val jsonRequest = serialize(request, encodedefault = false)

            if(streamingEnabled)
            {
                val response = withContext(Dispatchers.IO)
                {
                    client.post("$baseUrl/chat/completions")
                    {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer $apiKey")

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

                        setBody(jsonRequest)
                    }.bodyAsText()
                }

                // Check for error responses BEFORE deserializing as success
                val errorResponse = try { deserialize<GenericOpenAIErrorResponse>(responseText) } catch(e: Exception) { null }
                if(errorResponse != null && errorResponse.error.message.isNotEmpty())
                {
                    val errorMessage = errorResponse.error.message
                    val errorType = errorResponse.error.type
                    val errorCode = errorResponse.error.code

                    val p2pError = when {
                        errorType == "authentication_error" || errorCode == "401" -> P2PError.auth
                        errorType == "rate_limit_error" || errorCode == "429" -> P2PError.transport
                        errorType == "invalid_request_error" || errorType == "invalid_api_key" || errorCode == "400" -> P2PError.prompt
                        errorType == "api_error" || errorType == "server_error" || errorCode?.startsWith("5") == true -> P2PError.transport
                        else -> P2PError.transport
                    }
                    throw P2PException(p2pError, "GenericOpenAI API error: ${errorResponse.error.type}", Exception(errorMessage))
                }

                val response: GenericOpenAIChatResponse = deserialize(responseText)
                    ?: throw P2PException(P2PError.json, "Failed to deserialize GenericOpenAI chat response: $responseText", Exception("Deserialization failed"))

                val contentText = when(val msg = response.choices.firstOrNull()?.message?.content)
                {
                    is MessageContent.TextContent -> msg.text
                    is MessageContent.MultimodalContent -> msg.blocks.filterIsInstance<ContentBlock.TextBlock>().joinToString("") { it.text }
                    null -> ""
                }

                val usage = response.usage
                val inputTokens = usage?.promptTokens ?: 0
                val outputTokens = usage?.completionTokens ?: 0
                val totalTokens = usage?.totalTokens ?: 0

                trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
                      metadata = mapOf(
                          "inputTokens" to inputTokens,
                          "outputTokens" to outputTokens,
                          "totalTokens" to totalTokens,
                          "responseLength" to contentText.length,
                          "model" to response.model,
                          "success" to true,
                          "apiType" to "ChatAPI",
                          "finishReason" to (response.choices.firstOrNull()?.finishReason ?: "unknown"),
                          "responseId" to (response.id ?: "unknown"),
                          "systemFingerprint" to (response.systemFingerprint ?: "none")
                      ))

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
                    val sseError = try { deserialize<GenericOpenAIErrorResponse>(sseLine.content) } catch(e: Exception) { null }
                    if(sseError != null && sseError.error.message.isNotEmpty())
                    {
                        val p2pError = when(sseError.error.type)
                        {
                            "authentication_error" -> P2PError.auth
                            "rate_limit_error" -> P2PError.transport
                            "invalid_request_error", "invalid_api_key" -> P2PError.prompt
                            "api_error", "server_error" -> P2PError.transport
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
}