package ollamaPipe

import com.TTT.Debug.*
import com.TTT.Enums.ProviderName
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.BinaryContent
import com.TTT.Pipe.StreamingCallbackBuilder
import com.TTT.PipeContextProtocol.*
import com.TTT.Util.*
import env.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * TPipe abstraction for Ollama. Provides access to the Ollama api and TPipe system.
 *
 * This implementation supports both /api/generate and /api/chat endpoints,
 * defaulting to the modern /api/chat API for better structured conversation
 * and tool calling support.
 */
@kotlinx.serialization.Serializable
class OllamaPipe : Pipe()
{
//================================================ Properties ========================================================//

    /**
     * IP address where Ollama server is running.
     */
    @kotlinx.serialization.Serializable
    private var ip: String = "127.0.0.1"

    /**
     * Port number where Ollama server is running.
     */
    @kotlinx.serialization.Serializable
    private var port: Int = 11434

    /**
     * Whether to use the modern /api/chat endpoint (true) or legacy /api/generate (false).
     */
    @kotlinx.serialization.Serializable
    private var useChatApi: Boolean = true

    /**
     * Whether to use streaming for responses.
     */
    @kotlinx.serialization.Serializable
    private var streamingEnabled: Boolean = false

    /**
     * How long to keep the model loaded in memory after the request.
     * Default is "5m". Set to "0" to unload immediately, or "-1" to keep indefinitely.
     */
    @kotlinx.serialization.Serializable
    private var keepAlive: String = "5m"

    /**
     * Whether to enable model thinking/reasoning.
     */
    @kotlinx.serialization.Serializable
    private var think: Boolean = false
    
    /**
     * Number of tokens to keep from the initial prompt.
     */
    @kotlinx.serialization.Serializable
    private var numKeep: Int? = null
    
    /**
     * Minimum probability for a token to be considered.
     */
    @kotlinx.serialization.Serializable
    private var minP: Float? = null
    
    /**
     * Typical probability for sampling.
     */
    @kotlinx.serialization.Serializable
    private var typicalP: Float? = null
    
    /**
     * Number of tokens to consider for the repeat penalty.
     */
    @kotlinx.serialization.Serializable
    private var repeatLastN: Int? = null

    /**
     * Penalty for new tokens based on their frequency in the text so far.
     */
    @kotlinx.serialization.Serializable
    private var frequencyPenalty: Float? = null
    
    /**
     * Controls the algorithm used for text generation (0=disabled, 1=Mirostat, 2=Mirostat 2.0).
     */
    @kotlinx.serialization.Serializable
    private var mirostat: Int? = null
    
    /**
     * Target entropy for Mirostat algorithm.
     */
    @kotlinx.serialization.Serializable
    private var mirostatTau: Float? = null
    
    /**
     * Learning rate for Mirostat algorithm.
     */
    @kotlinx.serialization.Serializable
    private var mirostatEta: Float? = null
    
    /**
     * Whether to penalize newline tokens.
     */
    @kotlinx.serialization.Serializable
    private var penalizeNewline: Boolean? = null
    
    /**
     * Whether to use NUMA optimization.
     */
    @kotlinx.serialization.Serializable
    private var numa: Boolean? = null
    
    /**
     * Context window size in tokens.
     */
    @kotlinx.serialization.Serializable
    private var numCtx: Int? = null
    
    /**
     * Batch size for prompt processing.
     */
    @kotlinx.serialization.Serializable
    private var numBatch: Int? = null
    
    /**
     * Number of GPU layers to offload to.
     */
    @kotlinx.serialization.Serializable
    private var numGpu: Int? = null
    
    /**
     * Main GPU to use.
     */
    @kotlinx.serialization.Serializable
    private var mainGpu: Int? = null
    
    /**
     * Whether to use low VRAM mode.
     */
    @kotlinx.serialization.Serializable
    private var lowVram: Boolean? = null
    
    /**
     * Whether to only load the vocabulary (no weights).
     */
    @kotlinx.serialization.Serializable
    private var vocabOnly: Boolean? = null
    
    /**
     * Whether to use memory-mapped files.
     */
    @kotlinx.serialization.Serializable
    private var useMmap: Boolean? = null
    
    /**
     * Whether to lock the model in memory.
     */
    @kotlinx.serialization.Serializable
    private var useMlock: Boolean? = null
    
    /**
     * Number of threads to use for generation.
     */
    @kotlinx.serialization.Serializable
    private var numThread: Int? = null

//================================================ Builder ===========================================================//

    /**
     * Set the IP address of the Ollama server.
     * @param ip The IP address to set.
     * @return This pipe instance.
     */
    fun setIP(ip: String): OllamaPipe
    {
        this.ip = ip
        Endpoints.init(this.ip, this.port)
        return this
    }

    /**
     * Set the port number that the Ollama server is listening on.
     * @param port The port number to set.
     * @return This pipe instance.
     */
    fun setPort(port: Int): OllamaPipe
    {
        this.port = port
        Endpoints.init(this.ip, this.port)
        return this
    }

    /**
     * Switches to the legacy /api/generate endpoint.
     * @return This pipe instance.
     */
    fun useLegacyApi(): OllamaPipe
    {
        this.useChatApi = false
        return this
    }

    /**
     * Switches to the modern /api/chat endpoint.
     * @return This pipe instance.
     */
    fun useChatApi(): OllamaPipe
    {
        this.useChatApi = true
        return this
    }

    /**
     * Enables streaming mode for responses.
     * @param callback Optional callback for received chunks.
     * @param showReasoning Whether to enable streaming for reasoning pipes.
     * @param streamReasoning Whether to emit reasoning chunks to callbacks.
     * @return This pipe instance.
     */
    fun enableStreaming(
        callback: (suspend (String) -> Unit)? = null,
        showReasoning: Boolean = false,
        streamReasoning: Boolean = true
    ): OllamaPipe
    {
        this.streamingEnabled = true
        this.streamModelReasoning = streamReasoning

        if(callback != null)
        {
            setStreamingCallback(callback)
        }

        if(showReasoning)
        {
            val abstractPipe = reasoningPipe as? OllamaPipe
            abstractPipe?.enableStreaming(callback, true, streamReasoning)
        }

        return this
    }

    /**
     * Disables streaming mode.
     * @return This pipe instance.
     */
    fun disableStreaming(): OllamaPipe
    {
        this.streamingEnabled = false
        streamingCallbackManager?.clearCallbacks()
        return this
    }

    /**
     * Registers a suspendable callback for streaming chunks.
     * @param callback The callback function.
     * @return This pipe instance.
     */
    fun setStreamingCallback(callback: suspend (String) -> Unit): OllamaPipe
    {
        this.streamingEnabled = true
        obtainStreamingCallbackManager().addCallback(callback)
        return this
    }

    /**
     * Configures multiple streaming callbacks using a builder.
     * @param builder The configuration builder.
     * @return This pipe instance.
     */
    fun streamingCallbacks(builder: StreamingCallbackBuilder.() -> Unit): OllamaPipe
    {
        val callbackBuilder = StreamingCallbackBuilder()
        callbackBuilder.builder()
        streamingCallbackManager = callbackBuilder.build()
        streamingEnabled = true
        return this
    }

    /**
     * Sets how long to keep the model loaded in memory.
     * @param duration Duration string (e.g., "5m", "1h", "0", "-1").
     * @return This pipe instance.
     */
    fun setKeepAlive(duration: String): OllamaPipe
    {
        this.keepAlive = duration
        return this
    }

    /**
     * Enables model thinking/reasoning.
     * @return This pipe instance.
     */
    fun enableThink(): OllamaPipe
    {
        this.think = true
        this.useModelReasoning = true
        return this
    }
    
    /**
     * Sets the minimum probability for token consideration during sampling.
     * @param minP The minimum probability.
     * @return This pipe instance.
     */
    fun setMinP(minP: Float): OllamaPipe
    {
        this.minP = minP
        return this
    }
    
    /**
     * Sets the typical probability for token sampling.
     * @param typicalP The typical probability.
     * @return This pipe instance.
     */
    fun setTypicalP(typicalP: Float): OllamaPipe
    {
        this.typicalP = typicalP
        return this
    }
    
    /**
     * Enables and configures Mirostat sampling.
     * @param mode 0 (disabled), 1 (Mirostat), 2 (Mirostat 2.0).
     * @param eta Learning rate.
     * @param tau Target entropy.
     * @return This pipe instance.
     */
    fun setMirostat(mode: Int, eta: Float? = null, tau: Float? = null): OllamaPipe
    {
        this.mirostat = mode
        eta?.let { this.mirostatEta = it }
        tau?.let { this.mirostatTau = it }
        return this
    }
    
    /**
     * Sets the number of tokens to consider for repeat penalty.
     * @param n Number of tokens.
     * @return This pipe instance.
     */
    fun setRepeatLastN(n: Int): OllamaPipe
    {
        this.repeatLastN = n
        return this
    }
    
    /**
     * Sets the presence penalty for new tokens.
     * @param penalty The penalty value.
     * @return This pipe instance.
     */
    fun setPresencePenalty(penalty: Float): OllamaPipe
    {
        this.presencePenalty = penalty.toDouble()
        return this
    }
    
    /**
     * Sets the frequency penalty for new tokens.
     * @param penalty The penalty value.
     * @return This pipe instance.
     */
    fun setFrequencyPenalty(penalty: Float): OllamaPipe
    {
        this.frequencyPenalty = penalty
        return this
    }
    
    /**
     * Configures GPU settings for model execution.
     * @param numGpu Number of GPU layers to offload to.
     * @param mainGpu Main GPU to use.
     * @return This pipe instance.
     */
    fun setGpuSettings(numGpu: Int, mainGpu: Int? = null): OllamaPipe
    {
        this.numGpu = numGpu
        mainGpu?.let { this.mainGpu = it }
        return this
    }
    
    /**
     * Sets the number of threads for generation.
     * @param numThread Number of threads.
     * @return This pipe instance.
     */
    fun setNumThread(numThread: Int): OllamaPipe
    {
        this.numThread = numThread
        return this
    }
    
    /**
     * Sets the batch size for prompt processing.
     * @param batchSize The batch size.
     * @return This pipe instance.
     */
    fun setBatchSize(batchSize: Int): OllamaPipe
    {
        this.numBatch = batchSize
        return this
    }
    
    /**
     * Configures whether to use NUMA optimization.
     * @param useNuma Whether to enable NUMA.
     * @return This pipe instance.
     */
    fun setNuma(useNuma: Boolean): OllamaPipe
    {
        this.numa = useNuma
        return this
    }
    
    /**
     * Configures the context window size in tokens.
     * @param numCtx The context size.
     * @return This pipe instance.
     */
    fun setNumCtx(numCtx: Int): OllamaPipe
    {
        this.numCtx = numCtx
        return this
    }
    
    /**
     * Configures whether to use low VRAM mode.
     * @param lowVram Whether to enable low VRAM.
     * @return This pipe instance.
     */
    fun setLowVram(lowVram: Boolean): OllamaPipe
    {
        this.lowVram = lowVram
        return this
    }
    
    /**
     * Configures whether to only load the vocabulary.
     * @param vocabOnly Whether to enable vocab only.
     * @return This pipe instance.
     */
    fun setVocabOnly(vocabOnly: Boolean): OllamaPipe
    {
        this.vocabOnly = vocabOnly
        return this
    }
    
    /**
     * Configures whether to use memory-mapped files.
     * @param useMmap Whether to use mmap.
     * @return This pipe instance.
     */
    fun setUseMmap(useMmap: Boolean): OllamaPipe
    {
        this.useMmap = useMmap
        return this
    }
    
    /**
     * Configures whether to lock the model in memory.
     * @param useMlock Whether to use mlock.
     * @return This pipe instance.
     */
    fun setUseMlock(useMlock: Boolean): OllamaPipe
    {
        this.useMlock = useMlock
        return this
    }
    
    /**
     * Configures whether to penalize newline tokens during generation.
     * @param penalize Whether to penalize newlines.
     * @return This pipe instance.
     */
    fun setPenalizeNewline(penalize: Boolean): OllamaPipe
    {
        this.penalizeNewline = penalize
        return this
    }

//================================================ Ollama Functions ==================================================//

    /**
     * Initializes the Ollama pipe, checking if the server is running.
     * @return This pipe instance.
     */
    override suspend fun init(): Pipe
    {
        Endpoints.init(this.ip, this.port)

        trace(TraceEventType.PIPE_START, TracePhase.INITIALIZATION,
              metadata = mapOf(
                  "provider" to "Ollama",
                  "ip" to ip,
                  "port" to port,
                  "model" to model,
                  "useChatApi" to useChatApi
              ))
        
        try
        {
            provider = ProviderName.Ollama

            // Check if the Ollama server is running.
            val versionInfo = withContext(Dispatchers.IO)
            {
                try
                {
                    getVersion()
                }
                catch(e: Exception)
                {
                    null
                }
            }

            // If the Ollama server is not running, attempt to start it.
            if(versionInfo == null || versionInfo.version.isNullOrEmpty())
            {
                trace(TraceEventType.API_CALL_START, TracePhase.INITIALIZATION,
                      metadata = mapOf("step" to "startOllamaServer"))
                serve()

                // Wait a bit for server to start
                delay(2000)
            }
            
            trace(TraceEventType.PIPE_SUCCESS, TracePhase.INITIALIZATION,
                  metadata = mapOf("serverVersion" to (versionInfo?.version ?: "unknown") as Any))
            
        }
        catch(e: Exception)
        {
            trace(TraceEventType.PIPE_FAILURE, TracePhase.INITIALIZATION, error = e)
        }

        return this
    }

    /**
     * Truncates context according to model family settings.
     * @return This pipe instance.
     */
    override fun truncateModuleContext(): Pipe
    {
        // Configure truncation settings based on model family if known
        when
        {
            model.contains("llama") ->
            {
                countSubWordsInFirstWord = true
                favorWholeWords = true
                splitForNonWordChar = true
                nonWordSplitCount = 2
            }
            model.contains("deepseek") ->
            {
                countSubWordsInFirstWord = true
                favorWholeWords = true
                splitForNonWordChar = true
                nonWordSplitCount = 2
            }
        }

        if(truncateContextAsString)
        {
            contextWindow.combineAndTruncateAsString(
                userPrompt,
                contextWindowSize,
                multiplyWindowSizeBy,
                contextWindowTruncation,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount
            )
        }
        else
        {
            contextWindow.selectAndTruncateContext(
                userPrompt,
                contextWindowSize,
                multiplyWindowSizeBy,
                contextWindowTruncation,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount
            )
        }

        return this
    }

    /**
     * Suspend-safe context truncation used during execution so remote-aware lorebook selection can participate in
     * provider-managed prompt assembly.
     *
     * @return This pipe instance.
     */
    override suspend fun truncateModuleContextSuspend(): Pipe
    {
        when
        {
            model.contains("llama") ->
            {
                countSubWordsInFirstWord = true
                favorWholeWords = true
                splitForNonWordChar = true
                nonWordSplitCount = 2
            }
            model.contains("deepseek") ->
            {
                countSubWordsInFirstWord = true
                favorWholeWords = true
                splitForNonWordChar = true
                nonWordSplitCount = 2
            }
        }

        if(truncateContextAsString)
        {
            val truncationSettings = com.TTT.Pipe.TruncationSettings(
                multiplyWindowSizeBy = multiplyWindowSizeBy,
                countSubWordsInFirstWord = countSubWordsInFirstWord,
                favorWholeWords = favorWholeWords,
                countOnlyFirstWordFound = countOnlyFirstWordFound,
                splitForNonWordChar = splitForNonWordChar,
                alwaysSplitIfWholeWordExists = alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit = countSubWordsIfSplit,
                nonWordSplitCount = nonWordSplitCount
            )
            val combinedContext = contextWindow.combineAndTruncateAsStringWithSettingsSuspend(
                userPrompt,
                contextWindowSize,
                truncationSettings,
                contextWindowTruncation,
                multiplyWindowSizeBy
            )
            contextWindow.clear()
            contextWindow.contextElements.add(combinedContext)
        }

        else
        {
            contextWindow.selectAndTruncateContextSuspend(
                userPrompt,
                contextWindowSize,
                multiplyWindowSizeBy,
                contextWindowTruncation,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount
            )
        }

        return this
    }

    /**
     * Aborts the active generation job.
     */
    override suspend fun abort()
    {
        trace(TraceEventType.PIPE_FAILURE, TracePhase.EXECUTION, 
              metadata = mapOf("action" to "abort", "provider" to "Ollama"))
        super.abort()
    }

    /**
     * Starts the Ollama server in the background.
     */
    fun serve()
    {
        // Run in background to not block
        CoroutineScope(Dispatchers.IO).launch {
            executeBashCommand("ollama serve")
        }
    }

    /**
     * Generates Ollama options from Pipe properties.
     * @return Populated OllamaOptions.
     */
    private fun generateOptions(): OllamaOptions
    {
        return OllamaOptions(
            numKeep = numKeep,
            seed = seed?.toLong(),
            numPredict = if(maxTokens > 0) maxTokens else null,
            topK = if(topK > 0) topK else null,
            topP = if(topP > 0.0) topP else null,
            minP = minP,
            typicalP = typicalP,
            repeatLastN = repeatLastN,
            temperature = if(temperature > 0.0) temperature else null,
            repeatPenalty = if(repetitionPenalty > 0.0) repetitionPenalty.toFloat() else null,
            presencePenalty = if(presencePenalty != 0.0) presencePenalty.toFloat() else null,
            frequencyPenalty = frequencyPenalty,
            mirostat = mirostat,
            mirostatTau = mirostatTau,
            mirostatEta = mirostatEta,
            penalizeNewline = penalizeNewline,
            stop = stopSequences.takeIf { it.isNotEmpty() },
            numa = numa,
            numCtx = if(numCtx != null) numCtx else (if(contextWindowSize > 0) contextWindowSize else null),
            numBatch = numBatch,
            numGpu = numGpu,
            mainGpu = mainGpu,
            lowVram = lowVram,
            vocabOnly = vocabOnly,
            useMmap = useMmap,
            useMlock = useMlock,
            numThread = numThread
        )
    }

    /**
     * Generates text using the configured model.
     * @param promptInjector Text to append/inject into the prompt.
     * @return Generated response text.
     */
    override suspend fun generateText(promptInjector: String): String
    {
        val content = MultimodalContent(text = promptInjector)
        val result = generateContent(content)
        return result.text
    }

    /**
     * Generates content including multimodal support and reasoning extraction.
     * @param content Input content with text and binary data.
     * @return Generated multimodal content.
     */
    override suspend fun generateContent(content: MultimodalContent): MultimodalContent
    {
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              content = content,
              metadata = mapOf(
                  "method" to "generateContent",
                  "model" to model,
                  "useChatApi" to useChatApi,
                  "streaming" to streamingEnabled,
                  "promptLength" to content.text.length,
                  "hasBinaryContent" to content.binaryContent.isNotEmpty()
              ))
        
        return try
        {
            val options = generateOptions()
            var result = if(useChatApi)
            {
                executeChatApi(content, options)
            }
            else
            {
                executeGenerateApi(content, options)
            }

            // Extract reasoning content from tags if present (e.g. <think> for DeepSeek R1)
            result = splitInterleavedReasoning(result)
            
            val responseMetadata = mutableMapOf<String, Any>(
                "responseLength" to result.text.length,
                "success" to true
            )
            
            if(result.modelReasoning.isNotEmpty())
            {
                responseMetadata["reasoningContent"] = result.modelReasoning
                responseMetadata["modelSupportsReasoning"] = true
                responseMetadata["reasoningEnabled"] = useModelReasoning
                responseMetadata["reasoningLength"] = result.modelReasoning.length
                responseMetadata["hasReasoning"] = true
            }
            else
            {
                responseMetadata["hasReasoning"] = false
            }
            
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, result, metadata = responseMetadata)
            result
            
        }
        catch(e: Exception)
        {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION, 
                  error = e,
                  metadata = mapOf(
                      "errorType" to (e::class.simpleName ?: "Unknown"),
                      "errorMessage" to (e.message ?: "Unknown error")
                  ))
            MultimodalContent("")
        }
    }

    /**
     * Internal execution of the chat API.
     * @param content Input content.
     * @param options Inference options.
     * @return Result content.
     */
    private suspend fun executeChatApi(content: MultimodalContent, options: OllamaOptions): MultimodalContent
    {
        val messages = mutableListOf<OllamaMessage>()

        // Handle system prompt
        if(systemPrompt.isNotEmpty())
        {
            messages.add(OllamaMessage(role = "system", content = systemPrompt))
        }

        // Convert all binary content to base64 for Ollama
        val base64Images = extractBase64Images(content)

        // Attempt to parse text as conversation history if it looks like JSON
        val history = deserialize<com.TTT.Context.ConverseHistory>(content.text, useRepair = false)
        if(history != null && history.history.isNotEmpty())
        {
            history.history.forEachIndexed { index, entry ->
                val entryImages = if(index == history.history.size - 1) base64Images else null
                messages.add(OllamaMessage(
                    role = entry.role.name,
                    content = entry.content.text,
                    images = entryImages
                ))
            }
        }
        else
        {
            // Default to single user message
            messages.add(OllamaMessage(
                role = "user",
                content = content.text,
                images = base64Images
            ))
        }

        val ollamaTools = buildOllamaTools()
        val format = if(!supportsNativeJson && jsonOutput.isNotEmpty())
        {
            try {
                Json.parseToJsonElement(jsonOutput)
            }
            catch(e: Exception)
            {
                JsonPrimitive("json")
            }
        } else null

        val request = ChatRequest(
            model = model,
            messages = messages,
            options = options,
            stream = streamingEnabled,
            keepAlive = keepAlive,
            format = format,
            tools = ollamaTools
        )

        return if(streamingEnabled)
        {
            executeChatStream(request)
        }
        else
        {
            val jsonRequest = serialize(request)

            trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
                  metadata = mapOf(
                      "step" to "chatApiRequest",
                      "requestSize" to jsonRequest.length,
                      "hasTools" to (ollamaTools != null),
                      "toolCount" to (ollamaTools?.size ?: 0),
                      "messageCount" to messages.size
                  ))

            val client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 600000 // 10 minutes
                    connectTimeoutMillis = 30000
                    socketTimeoutMillis = 600000
                }
            }
            
            return try {
                val responseText = try {
                    val response: HttpResponse = client.post(Endpoints.chatEndpoint) {
                        contentType(ContentType.Application.Json)
                        setBody(jsonRequest)
                    }
                    response.bodyAsText()
                } finally {
                    client.close()
                }
                
                val response = deserialize<ChatResponse>(responseText) ?: throw Exception("Failed to deserialize Ollama chat response: $responseText")

                var resultText = response.message?.content ?: ""
                
                val responseMetadata = extractOllamaMetadata(response).toMutableMap()
                responseMetadata["responseLength"] = resultText.length
                responseMetadata["success"] = true
                
                if(response.message?.toolCalls != null && response.message.toolCalls.isNotEmpty())
                {
                    val pcpRequests = response.message.toolCalls.map { call -> mapOllamaToolCallToPcp(call) }
                    val toolCallJson = serialize(pcpRequests)
                    resultText = if(resultText.isEmpty()) toolCallJson else "$resultText\n\n$toolCallJson"
                    
                    responseMetadata["toolCallsDetected"] = true
                    responseMetadata["toolCallCount"] = response.message.toolCalls.size
                }
                else
                {
                    responseMetadata["toolCallsDetected"] = false
                }

                val result = MultimodalContent(text = resultText)
                trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, result, metadata = responseMetadata)
                result
            }
            catch(e: Exception)
            {
                trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                      error = e,
                      metadata = mapOf(
                          "errorType" to (e::class.simpleName ?: "Unknown"),
                          "errorMessage" to (e.message ?: "Unknown error")
                      ))
                throw e
            }
        }
    }

    /**
     * Executes a streaming chat request.
     * @param request The chat request.
     * @return Accumulated multimodal content.
     */
    private suspend fun executeChatStream(request: ChatRequest): MultimodalContent
    {
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "step" to "chatStreamStart",
                  "streaming" to true,
                  "hasTools" to (request.tools != null),
                  "toolCount" to (request.tools?.size ?: 0)
              ))
        
        val client = HttpClient(CIO)
        {
            install(HttpTimeout)
            {
                requestTimeoutMillis = 300000 // 5 minutes
            }
        }

        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<OllamaToolCall>()

        return try
        {
            client.preparePost(Endpoints.chatEndpoint)
            {
                contentType(ContentType.Application.Json)
                setBody(serialize(request))
            }.execute { response ->
                val channel = response.bodyAsChannel()
                while(!channel.isClosedForRead)
                {
                    val line = channel.readUTF8Line() ?: break
                    if(line.isEmpty()) continue

                    val chunk = deserialize<ChatResponse>(line) ?: continue
                    val contentDelta = chunk.message?.content ?: ""

                    if(contentDelta.isNotEmpty())
                    {
                        textBuilder.append(contentDelta)
                        emitStreamingChunk(contentDelta)
                    }

                    chunk.message?.toolCalls?.let { calls ->
                        toolCalls.addAll(calls)
                    }

                    if(chunk.done) break
                }
            }
            
            var resultText = textBuilder.toString()
            
            val responseMetadata = mutableMapOf<String, Any>(
                "responseLength" to resultText.length,
                "success" to true,
                "streaming" to true,
                "apiType" to "ChatStreamAPI"
            )
            
            if(toolCalls.isNotEmpty())
            {
                val pcpRequests = toolCalls.map { call -> mapOllamaToolCallToPcp(call) }
                val toolCallJson = serialize(pcpRequests)
                resultText = if(resultText.isEmpty()) toolCallJson else "$resultText\n\n$toolCallJson"
                
                responseMetadata["toolCallsDetected"] = true
                responseMetadata["toolCallCount"] = toolCalls.size
            }
            else
            {
                responseMetadata["toolCallsDetected"] = false
            }
            
            val result = MultimodalContent(text = resultText)
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, result, metadata = responseMetadata)
            result
        }
        catch(e: Exception)
        {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  error = e,
                  metadata = mapOf(
                      "errorType" to (e::class.simpleName ?: "Unknown"),
                      "errorMessage" to (e.message ?: "Unknown error"),
                      "streaming" to true
                  ))
            throw e
        }
        finally
        {
            client.close()
        }
    }

    /**
     * Internal execution of the generate API.
     * @param content Input content.
     * @param options Inference options.
     * @return Result content.
     */
    private suspend fun executeGenerateApi(content: MultimodalContent, options: OllamaOptions): MultimodalContent
    {
        val base64Images = extractBase64Images(content)
        val format = if(!supportsNativeJson && jsonOutput.isNotEmpty())
        {
            try {
                Json.parseToJsonElement(jsonOutput)
            }
            catch(e: Exception)
            {
                JsonPrimitive("json")
            }
        } else null

        val request = GeneratedRequest(
            model = model,
            prompt = content.text,
            system = systemPrompt.takeIf { it.isNotEmpty() },
            options = options,
            stream = streamingEnabled,
            keepAlive = keepAlive,
            images = base64Images,
            format = format
        )

        return if(streamingEnabled)
        {
            executeGenerateStream(request)
        }
        else
        {
            val jsonRequest = serialize(request)
            
            trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
                  metadata = mapOf(
                      "step" to "generateApiRequest",
                      "requestSize" to jsonRequest.length,
                      "hasImages" to (base64Images?.isNotEmpty() == true)
                  ))
            
            val client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 600000 // 10 minutes
                    connectTimeoutMillis = 30000
                    socketTimeoutMillis = 600000
                }
            }

            try {
                val responseText = try {
                    val response: HttpResponse = client.post(Endpoints.generateEndpoint) {
                        contentType(ContentType.Application.Json)
                        setBody(jsonRequest)
                    }
                    response.bodyAsText()
                } finally {
                    client.close()
                }

                val response = deserialize<GeneratedResponse>(responseText) ?: throw Exception("Failed to deserialize Ollama generate response: $responseText")
                val resultText = response.response ?: ""
                
                val responseMetadata = extractOllamaMetadata(response).toMutableMap()
                responseMetadata["responseLength"] = resultText.length
                responseMetadata["success"] = true
                
                val result = MultimodalContent(text = resultText)
                trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, result, metadata = responseMetadata)
                result
            }
            catch(e: Exception)
            {
                trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                      error = e,
                      metadata = mapOf(
                          "errorType" to (e::class.simpleName ?: "Unknown"),
                          "errorMessage" to (e.message ?: "Unknown error")
                      ))
                throw e
            }
        }
    }

    /**
     * Executes a streaming generate request.
     * @param request The generate request.
     * @return Accumulated multimodal content.
     */
    private suspend fun executeGenerateStream(request: GeneratedRequest): MultimodalContent
    {
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "step" to "generateStreamStart",
                  "streaming" to true,
                  "hasImages" to (request.images?.isNotEmpty() == true)
              ))
        
        val client = HttpClient(CIO)
        {
            install(HttpTimeout)
            {
                requestTimeoutMillis = 300000
            }
        }
        
        val textBuilder = StringBuilder()

        return try
        {
            client.preparePost(Endpoints.generateEndpoint)
            {
                contentType(ContentType.Application.Json)
                setBody(serialize(request))
            }.execute { response ->
                val channel = response.bodyAsChannel()
                while(!channel.isClosedForRead)
                {
                    val line = channel.readUTF8Line() ?: break
                    if(line.isEmpty()) continue

                    val chunk = deserialize<GeneratedResponse>(line) ?: continue
                    val contentDelta = chunk.response ?: ""

                    if(contentDelta.isNotEmpty())
                    {
                        textBuilder.append(contentDelta)
                        emitStreamingChunk(contentDelta)
                    }

                    if(chunk.done) break
                }
            }
            
            val resultText = textBuilder.toString()
            val responseMetadata = mutableMapOf<String, Any>(
                "responseLength" to resultText.length,
                "success" to true,
                "streaming" to true,
                "apiType" to "GenerateStreamAPI"
            )
            
            val result = MultimodalContent(text = resultText)
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, result, metadata = responseMetadata)
            result
        }
        catch(e: Exception)
        {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  error = e,
                  metadata = mapOf(
                      "errorType" to (e::class.simpleName ?: "Unknown"),
                      "errorMessage" to (e.message ?: "Unknown error"),
                      "streaming" to true
                  ))
            throw e
        }
        finally
        {
            client.close()
        }
    }

    /**
     * Maps PCP and P2P tools to Ollama native tools.
     * @return List of OllamaTool or null.
     */
    private fun buildOllamaTools(): List<OllamaTool>?
    {
        val tools = mutableListOf<OllamaTool>()

        // Map PCP tools
        pcpContext.tpipeOptions.forEach { option ->
            val properties = mutableMapOf<String, JsonElement>()
            val required = mutableListOf<String>()
            
            option.params.forEach { (name, triple) ->
                val (type, description, _) = triple
                properties[name] = buildJsonObject {
                    put("type", mapPcpTypeToOllamaType(type))
                    put("description", description)
                }
                required.add(name)
            }

            val parameters = buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(properties))
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }

            tools.add(OllamaTool(
                function = OllamaFunctionDefinition(
                    name = option.functionName,
                    description = option.description,
                    parameters = parameters
                )
            ))
        }

        // Map P2P agents
        p2pAgentDescriptors?.forEach { agent ->
            tools.add(OllamaTool(
                function = OllamaFunctionDefinition(
                    name = "call_agent_${agent.agentName}",
                    description = agent.description,
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("prompt", buildJsonObject {
                                put("type", "string")
                                put("description", "The prompt to send to the agent")
                            })
                        })
                        put("required", buildJsonArray { add("prompt") })
                    }
                )
            ))
        }

        return if(tools.isNotEmpty()) tools else null
    }

    /**
     * Maps TPipe parameter types to JSON schema types.
     * @param type The TPipe param type.
     * @return The JSON schema type string.
     */
    private fun mapPcpTypeToOllamaType(type: com.TTT.PipeContextProtocol.ParamType): String
    {
        return when(type)
        {
            com.TTT.PipeContextProtocol.ParamType.String -> "string"
            com.TTT.PipeContextProtocol.ParamType.Int -> "integer"
            com.TTT.PipeContextProtocol.ParamType.Bool -> "boolean"
            com.TTT.PipeContextProtocol.ParamType.Float -> "number"
            com.TTT.PipeContextProtocol.ParamType.List -> "array"
            com.TTT.PipeContextProtocol.ParamType.Map, com.TTT.PipeContextProtocol.ParamType.Object -> "object"
            else -> "string"
        }
    }

    /**
     * Maps an Ollama tool call back to a PcPRequest.
     * @param call The Ollama tool call.
     * @return The populated PcPRequest.
     */
    private fun mapOllamaToolCallToPcp(call: OllamaToolCall): com.TTT.PipeContextProtocol.PcPRequest
    {
        val request = com.TTT.PipeContextProtocol.PcPRequest()
        val functionName = call.function.name

        if(functionName.startsWith("call_agent_"))
        {
            val agentName = functionName.removePrefix("call_agent_")
            val prompt = call.function.arguments["prompt"]?.jsonPrimitive?.content ?: ""
            request.argumentsOrFunctionParams = listOf(agentName, prompt)
        }
        else
        {
            request.tPipeContextOptions.functionName = functionName
            request.argumentsOrFunctionParams = call.function.arguments.values.map {
                if(it is JsonPrimitive) it.content else it.toString()
            }
        }

        return request
    }

    /**
     * Extracts base64 image data from multimodal content.
     * @param content Input multimodal content.
     * @return List of base64 strings or null.
     */
    private fun extractBase64Images(content: MultimodalContent): List<String>?
    {
        val images = mutableListOf<String>()
        for(binary in content.binaryContent)
        {
            when(binary)
            {
                is BinaryContent.Base64String -> images.add(binary.data)
                is BinaryContent.Bytes -> images.add(binary.toBase64().data)
                else -> {}
            }
        }
        return if(images.isNotEmpty()) images else null
    }

    /**
     * Splits interleaved reasoning from text if present (e.g. <think> tags).
     * @param content The content to process.
     * @return Updated multimodal content.
     */
    private fun splitInterleavedReasoning(content: MultimodalContent): MultimodalContent
    {
        if(content.modelReasoning.isNotEmpty()) return content

        val text = content.text
        if(text.contains("</think>", ignoreCase = true))
        {
            val thinkRegex = "<think>(?s:.*?)</think>".toRegex(RegexOption.IGNORE_CASE)
            val segments = thinkRegex.findAll(text).map { it.value.removeSurrounding("<think>", "</think>").trim() }.toList()
            val thinking = segments.joinToString("\n")

            var cleanedText = text.replace("(?si)<think>.*?</think>".toRegex(), "")
            cleanedText = cleanedText.replace("(?si)^.*?</think>".toRegex(), "").trim()

            content.text = cleanedText
            content.modelReasoning = thinking
        }
        return content
    }

    /**
     * Extracts metadata from ChatResponse for tracing.
     * @param response The ChatResponse to extract from.
     * @return Map of metadata fields.
     */
    private fun extractOllamaMetadata(response: ChatResponse): Map<String, Any>
    {
        val metadata = mutableMapOf<String, Any>()
        response.promptEvalCount?.let { metadata["inputTokens"] = it }
        response.evalCount?.let { metadata["outputTokens"] = it }
        if(response.promptEvalCount != null && response.evalCount != null)
        {
            metadata["totalTokens"] = response.promptEvalCount + response.evalCount
        }
        response.totalDuration?.let { metadata["totalDuration"] = it }
        response.loadDuration?.let { metadata["loadDuration"] = it }
        response.promptEvalDuration?.let { metadata["promptEvalDuration"] = it }
        response.evalDuration?.let { metadata["evalDuration"] = it }
        response.doneReason?.let { metadata["stopReason"] = it }
        metadata["apiType"] = "ChatAPI"
        return metadata
    }

    /**
     * Extracts metadata from GeneratedResponse for tracing.
     * @param response The GeneratedResponse to extract from.
     * @return Map of metadata fields.
     */
    private fun extractOllamaMetadata(response: GeneratedResponse): Map<String, Any>
    {
        val metadata = mutableMapOf<String, Any>()
        response.promptEvalCount?.let { metadata["inputTokens"] = it }
        response.evalCount?.let { metadata["outputTokens"] = it }
        if(response.promptEvalCount != null && response.evalCount != null)
        {
            metadata["totalTokens"] = response.promptEvalCount + response.evalCount
        }
        response.totalDuration?.let { metadata["totalDuration"] = it }
        response.loadDuration?.let { metadata["loadDuration"] = it }
        response.promptEvalDuration?.let { metadata["promptEvalDuration"] = it }
        response.evalDuration?.let { metadata["evalDuration"] = it }
        metadata["apiType"] = "GenerateAPI"
        return metadata
    }

    /**
     * Checks the version of the Ollama server.
     * @return versionResponce or null.
     */
    suspend fun getVersion(): versionResponce?
    {
        val response = httpGet(Endpoints.versionEndpoint)
        return deserialize<versionResponce>(response)
    }

    /**
     * Queries the Ollama server for the currently running models.
     * @return List of models as JsonElement or null.
     */
    suspend fun getRunningModels(): JsonElement?
    {
        val response = httpGet(Endpoints.runningEndpoint)
        return deserialize<JsonElement>(response)
    }

    /**
     * Lists all models available on the Ollama server.
     * @return List of models as JsonElement or null.
     */
    suspend fun listModels(): JsonElement?
    {
        val response = httpGet(Endpoints.listEndpoint)
        return deserialize<JsonElement>(response)
    }
}
