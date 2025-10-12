package ollamaPipe

import com.TTT.Debug.*
import com.TTT.Debug.EventPriorityMapper
import com.TTT.Enums.ProviderName
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.BinaryContent
import com.TTT.Util.*
import env.Endpoints
import env.InputParams
import env.Model
import env.OllamaOptions
import env.versionResponce
import kotlinx.coroutines.*


/**
 * TPipe abstraction for Ollama. Provides access to the Ollama api and TPipe system.
 */
@kotlinx.serialization.Serializable
class OllamaPipe : Pipe() {
//================================================ Properties ========================================================//

    /**
     * IP address where Ollama server is running. Commonly this is the hardware that TPipe is running on.
     * However, it may not be and may need to be changed.
     */
    @kotlinx.serialization.Serializable
    private var ip = "127.0.0.1"

    /**
     * Port number where Ollama server is running.
     */
    @kotlinx.serialization.Serializable
    private var port = 11434
    
    /**
     * Number of tokens to keep from the initial prompt
     */
    @kotlinx.serialization.Serializable
    private var numKeep: Int? = null
    
    /**
     * Random number seed for reproducibility
     */
    @kotlinx.serialization.Serializable
    private var seed: Long? = null
    
    /**
     * Maximum number of tokens to predict when generating text
     */
    @kotlinx.serialization.Serializable
    private var numPredict: Int? = null
    

    
    /**
     * Minimum probability for a token to be considered
     */
    @kotlinx.serialization.Serializable
    private var minP: Float? = 0.05f
    
    /**
     * Typical probability for sampling
     */
    @kotlinx.serialization.Serializable
    private var typicalP: Float? = 1.0f
    
    /**
     * Number of tokens to consider for the repeat penalty
     */
    @kotlinx.serialization.Serializable
    private var repeatLastN: Int? = 64
    

    /**
     * Penalty for repeated tokens
     */
    @kotlinx.serialization.Serializable
    private var repeatPenalty: Float? = 1.2f
    
    /**
     * Penalty for new tokens based on presence in the text so far
     */
    @kotlinx.serialization.Serializable
    private var presencePenalty: Float? = 1.5f
    
    /**
     * Penalty for new tokens based on their frequency in the text so far
     */
    @kotlinx.serialization.Serializable
    private var frequencyPenalty: Float? = 1.0f
    
    /**
     * Controls the algorithm used for text generation (0=disabled, 1=Mirostat, 2=Mirostat 2.0)
     */
    @kotlinx.serialization.Serializable
    private var mirostat: Int? = 1
    
    /**
     * Target entropy for Mirostat algorithm
     */
    @kotlinx.serialization.Serializable
    private var mirostatTau: Float? = 0.8f
    
    /**
     * Learning rate for Mirostat algorithm
     */
    @kotlinx.serialization.Serializable
    private var mirostatEta: Float? = 0.6f
    
    /**
     * Whether to penalize newline tokens
     */
    @kotlinx.serialization.Serializable
    private var penalizeNewline: Boolean? = true
    
    /**
     * Whether to use NUMA optimization
     */
    @kotlinx.serialization.Serializable
    private var numa: Boolean? = false
    
    /**
     * Context window size in tokens
     */
    @kotlinx.serialization.Serializable
    private var numCtx: Int? = 1024
    
    /**
     * Batch size for prompt processing
     */
    @kotlinx.serialization.Serializable
    private var numBatch: Int? = 2
    
    /**
     * Number of GPUs to use
     */
    private var numGpu: Int? = 1
    
    /**
     * Main GPU to use
     */
    @kotlinx.serialization.Serializable
    private var mainGpu: Int? = 0
    
    /**
     * Whether to use low VRAM mode
     */
    @kotlinx.serialization.Serializable
    private var lowVram: Boolean? = false
    
    /**
     * Whether to only load the vocabulary (no weights)
     */
    @kotlinx.serialization.Serializable
    private var vocabOnly: Boolean? = false
    
    /**
     * Whether to use memory-mapped files
     */
    @kotlinx.serialization.Serializable
    private var useMmap: Boolean? = true
    
    /**
     * Whether to lock the model in memory
     */
    @kotlinx.serialization.Serializable
    private var useMlock: Boolean? = false
    
    /**
     * Number of threads to use for generation
     */
    @kotlinx.serialization.Serializable
    private var numThread: Int? = 8

//================================================ Builder ===========================================================//


    /**
     * Set the IP address of the Ollama server. This is the IP of the machine
     * that the Ollama server is running on. If this is not specified, it will
     * default to the IP of the machine that TPipe is running on.
     *
     * @param ip The IP address of the Ollama server.
     * @return This Pipe object for chaining.
     */
    fun setIP(ip: String): Pipe {
        this.ip = ip
        return this
    }

    /**
     * Set the port number that the Ollama server is listening on. This is the
     * port number that the Ollama server is listening on for incoming requests.
     * If this is not specified, it will default to 11434.
     *
     * @param port The port number that the Ollama server is listening on.
     * @return This Pipe object for chaining.
     */
    fun setPort(port: Int): Pipe {
        this.port = port
        return this
    }
    
    /**
     * Sets the minimum probability for token consideration during sampling.
     * @param minP Minimum probability value (0.0 to 1.0)
     * @return This Pipe object for chaining
     */
    fun setMinP(minP: Float): Pipe {
        this.minP = minP
        return this
    }
    
    /**
     * Sets the typical probability for token sampling.
     * @param typicalP Typical probability value (0.0 to 1.0)
     * @return This Pipe object for chaining
     */
    fun setTypicalP(typicalP: Float): Pipe {
        this.typicalP = typicalP
        return this
    }
    

    
    /**
     * Enables and configures Mirostat sampling.
     * @param mode 0 = disabled, 1 = Mirostat, 2 = Mirostat 2.0
     * @param eta Learning rate for Mirostat
     * @param tau Controls balance between coherence and diversity
     * @return This Pipe object for chaining
     */
    fun setMirostat(mode: Int, eta: Float? = null, tau: Float? = null): Pipe {
        this.mirostat = mode
        eta?.let { this.mirostatEta = it }
        tau?.let { this.mirostatTau = it }
        return this
    }
    
    /**
     * Sets the number of tokens to consider for repeat penalty.
     * @param n Number of tokens to consider
     * @return This Pipe object for chaining
     */
    fun setRepeatLastN(n: Int): Pipe {
        this.repeatLastN = n
        return this
    }
    
    /**
     * Sets the presence penalty for new tokens.
     * @param penalty Presence penalty value (0.0 to 2.0)
     * @return This Pipe object for chaining
     */
    fun setPresencePenalty(penalty: Float): Pipe {
        this.presencePenalty = penalty
        return this
    }
    
    /**
     * Sets the frequency penalty for new tokens.
     * @param penalty Frequency penalty value (0.0 to 2.0)
     * @return This Pipe object for chaining
     */
    fun setFrequencyPenalty(penalty: Float): Pipe {
        this.frequencyPenalty = penalty
        return this
    }
    
    /**
     * Configures GPU settings for model execution.
     * @param numGpu Number of GPU layers to use (-1 for all)
     * @param mainGpu Main GPU device to use
     * @return This Pipe object for chaining
     */
    fun setGpuSettings(numGpu: Int, mainGpu: Int? = null): Pipe {
        this.numGpu = numGpu
        mainGpu?.let { this.mainGpu = it }
        return this
    }
    
    /**
     * Sets the number of threads for generation.
     * @param numThread Number of threads to use
     * @return This Pipe object for chaining
     */
    fun setNumThread(numThread: Int): Pipe {
        this.numThread = numThread
        return this
    }
    
    /**
     * Sets the batch size for prompt processing.
     * @param batchSize Batch size to use
     * @return This Pipe object for chaining
     */
    fun setBatchSize(batchSize: Int): Pipe {
        this.numBatch = batchSize
        return this
    }
    

    
    /**
     * Sets the random number seed for reproducibility.
     * @param seed The random seed value
     * @return This Pipe object for chaining
     */
    fun setSeed(seed: Long): Pipe {
        this.seed = seed
        return this
    }
    
    /**
     * Sets the maximum number of tokens to predict when generating text.
     * @param numPredict Maximum number of tokens to generate
     * @return This Pipe object for chaining
     */
    fun setNumPredict(numPredict: Int): Pipe {
        this.numPredict = numPredict
        return this
    }


    /**
     * Configures the repeat penalty for tokens.
     * @param penalty The repeat penalty value
     * @return This Pipe object for chaining
     */
    fun setRepeatPenalty(penalty: Float): Pipe {
        this.repeatPenalty = penalty
        return this
    }
    
    /**
     * Configures whether to use NUMA optimization.
     * @param useNuma Whether to enable NUMA optimization
     * @return This Pipe object for chaining
     */
    fun setNuma(useNuma: Boolean): Pipe {
        this.numa = useNuma
        return this
    }
    
    /**
     * Configures the context window size in tokens.
     * @param numCtx Context window size
     * @return This Pipe object for chaining
     */
    fun setNumCtx(numCtx: Int): Pipe {
        this.numCtx = numCtx
        return this
    }
    
    /**
     * Configures whether to use low VRAM mode.
     * @param lowVram Whether to enable low VRAM mode
     * @return This Pipe object for chaining
     */
    fun setLowVram(lowVram: Boolean): Pipe {
        this.lowVram = lowVram
        return this
    }
    
    /**
     * Configures whether to only load the vocabulary.
     * @param vocabOnly Whether to only load vocabulary
     * @return This Pipe object for chaining
     */
    fun setVocabOnly(vocabOnly: Boolean): Pipe {
        this.vocabOnly = vocabOnly
        return this
    }
    
    /**
     * Configures whether to use memory-mapped files.
     * @param useMmap Whether to use memory-mapped files
     * @return This Pipe object for chaining
     */
    fun setUseMmap(useMmap: Boolean): Pipe {
        this.useMmap = useMmap
        return this
    }
    
    /**
     * Configures whether to lock the model in memory.
     * @param useMlock Whether to lock the model in memory
     * @return This Pipe object for chaining
     */
    fun setUseMlock(useMlock: Boolean): Pipe {
        this.useMlock = useMlock
        return this
    }
    
    /**
     * Configures whether to penalize newline tokens during generation.
     * @param penalize Whether to penalize newlines
     * @return This Pipe object for chaining
     */
    fun setPenalizeNewline(penalize: Boolean): Pipe {
        this.penalizeNewline = penalize
        return this
    }


//================================================ Ollama Functions ==================================================//

    /**
     * Assings pipe provider to Ollama, then activates the ollama server if it is not already running.
     * @return This Pipe object
     */
    override suspend fun init(): Pipe
    {
        trace(TraceEventType.PIPE_START, TracePhase.INITIALIZATION,
              metadata = mapOf(
                  "provider" to "Ollama",
                  "ip" to ip,
                  "port" to port,
                  "model" to model
              ))
        
        try {
            provider = ProviderName.Ollama

            trace(TraceEventType.API_CALL_START, TracePhase.INITIALIZATION,
                  metadata = mapOf("step" to "checkServerStatus"))
            
            // Check if the Ollama server is running.
            val isRunning = coroutineScope {
                return@coroutineScope async {
                    return@async getVersion()
                }
            }

            val serverOnline = isRunning.await()

            // If the Ollama server is not running, start it.
            if(serverOnline == null || serverOnline?.version == "")
            {
                trace(TraceEventType.API_CALL_START, TracePhase.INITIALIZATION,
                      metadata = mapOf("step" to "startOllamaServer"))
                serve()
            }
            
            trace(TraceEventType.PIPE_SUCCESS, TracePhase.INITIALIZATION,
                  metadata = mapOf("serverVersion" to (serverOnline?.version ?: "unknown") as Any))
            
        } catch (e: Exception) {
            trace(TraceEventType.PIPE_FAILURE, TracePhase.INITIALIZATION, error = e)
        }

        return this
    }


    override fun truncateModuleContext(): Pipe {
        TODO("Not yet implemented")
        return this
    }


    /**
     * Shell function to start the Ollama server in the event it has not been started already.
     * This function will start the Ollama server in the background.
     */
    fun serve()
    {
        executeBashCommand("ollama serve")
    }



    /**
     * Generate an [OllamaOptions] object based on the current properties of this Pipe.
     * This method is used to convert the properties of this Pipe to an object
     * that can be passed to the Ollama server.
     *
     * @return An [OllamaOptions] object that can be passed to the Ollama server.
     */
    private fun generateOptions(): OllamaOptions {
        return OllamaOptions(
            numKeep = numKeep,
            seed = seed,
            numPredict = maxTokens.takeIf { it > 0 },
            topK = topK,
            topP = topP,
            minP = minP,
            typicalP = typicalP,
            repeatLastN = repeatLastN,
            temperature = temperature,
            repeatPenalty = repeatPenalty,
            presencePenalty = presencePenalty,
            frequencyPenalty = frequencyPenalty,
            mirostat = mirostat,
            mirostatTau = mirostatTau,
            mirostatEta = mirostatEta,
            penalizeNewline = penalizeNewline,
            stop = stopSequences.ifEmpty { null },
            numa = numa,
            numCtx = numCtx,
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

    suspend fun generate(inputs: InputParams){
        var json:String = setJsonInput(inputs, false).jsonInput
        var post = httpPost(Endpoints.generateEndpoint, json)
    }

    
    suspend fun generateChat(inputs: InputParams){
        var json:String = setJsonInput(inputs, false).jsonInput
        var post = httpPost(Endpoints.chatEndpoint, json)
    }


    suspend fun createModel(model: Model){
        var json:String = setJsonInput(model, false).jsonInput
        var post = httpPost(Endpoints.createEndpoint, json)
    }


    suspend fun deleteModel(model: Model){
        var json:String = setJsonInput(model, false).jsonInput
        var post = httpDelete(Endpoints.deleteEndpoint, json)
    }


    suspend fun listModels(){
        var post = httpGet(Endpoints.listEndpoint)
    }


    suspend fun showModel(modelname: String, verbose: Boolean = false){
        var model = Model(modelname)
        model.verbose = verbose
        var json:String = setJsonInput(model, false).jsonInput
        var post = httpPost(Endpoints.showEndpoint, json)
    }


    suspend fun copyModel(source: String, destination: String){
        var model = Model(source)
        model.destination = destination
        var json:String = setJsonInput(model, false).jsonInput
        json.replace("\"model\"", "\"source\"")
        var post = httpPost(Endpoints.copyEndpoint, json)
    }


    suspend fun pullModel(modelname: String, insecure: Boolean = false, stream: Boolean = false){
        var model = Model(modelname)
        model.insecure = insecure
        model.stream = stream
        var json:String = setJsonInput(model, false).jsonInput
        var post = httpPost(Endpoints.pullEndpoint, json)
    }


    suspend fun pushModel(modelname: String, insecure: Boolean = false, stream: Boolean = false){
        var model = Model(modelname)
        model.insecure = insecure
        model.stream = stream
        var json:String = setJsonInput(model, false).jsonInput
        var post = httpPost(Endpoints.pushEndpoint, json)
    }


    //CHECK THIS ONE
    //will setting defaults to the same value cause serialization?
    //I'm not sure what's going on with Ollama options in this case. does it need default values?
    //check inside InputParams
    suspend fun generateEmbed(modelname: String, inputs: List<String>, truncate: Boolean = false,
                              options: OllamaOptions? = null, KeepAlive: Int = 300){
        var obj = InputParams(model)
        obj.truncate = truncate
        obj.options = options
        obj.keep_alive = KeepAlive
        var json:String = setJsonInput(inputs, false).jsonInput
        var post = httpPost(Endpoints.embeddingsEndpoint, json)
    }


    /**
     * Checks the version of the Ollama server
     *
     * @return Return the version of the Ollama server
     */
    suspend fun getVersion() : versionResponce?
    {
        var post = httpGet(Endpoints.versionEndpoint)
        var version = deserialize<versionResponce>(post)
        return version
    }

    /**
     * Queries the Ollama server for the currently running models.
     *
     * @return a [Model] object representing the currently running model, or null if no models are running.
     */
    suspend fun getRunningModels() : Model?
    {
        var post = httpGet(Endpoints.runningEndpoint)
        var running = deserialize<Model>(post)
        return running
    }


    override suspend fun generateText(promptInjector: String): String {
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "method" to "generateText",
                  "model" to model,
                  "endpoint" to "$ip:$port",
                  "promptLength" to promptInjector.length
              ))
        
        return try {
            val options = generateOptions()
            
            trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
                  metadata = mapOf(
                      "step" to "buildRequest",
                      "temperature" to temperature,
                      "maxTokens" to maxTokens,
                      "topP" to topP
                  ))
            
            val inputs = InputParams(model).apply {
                prompt = promptInjector
                this.options = options
                stream = false
            }
            
            val json = setJsonInput(inputs, false).jsonInput
            
            trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
                  metadata = mapOf("step" to "httpPost", "requestSize" to json.length))
            
            val response = httpPost(Endpoints.generateEndpoint, json)
            
            // Parse response and extract text
            val result = parseOllamaResponse(response)
            
            // Always extract reasoning content if present, regardless of useModelReasoning flag
            val reasoningContent = extractOllamaReasoning(response, model)
            if (reasoningContent.isNotEmpty()) {
                // Store reasoning in jsonOutput for accessibility
                jsonOutput = "{\"reasoning\": \"${reasoningContent.replace("\"", "\\\"")}\", \"response\": \"${result.replace("\"", "\\\"")}\"}" 
                
                if (tracingEnabled && traceConfig.detailLevel == TraceDetailLevel.DEBUG) {
                    trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
                          metadata = mapOf(
                              "reasoningContent" to reasoningContent,
                              "modelSupportsReasoning" to modelSupportsReasoning(model),
                              "reasoningEnabled" to useModelReasoning
                          ))
                }
            }
            
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
                  metadata = mapOf(
                      "responseLength" to result.length,
                      "success" to true
                  ))
            
            result
            
        } catch (e: Exception) {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  error = e,
                  metadata = mapOf(
                      "errorType" to (e::class.simpleName ?: "Unknown"),
                      "endpoint" to "$ip:$port"
                  ))
            ""
        }
    }

    /**
     * Generates multimodal content using Ollama.
     * Note: Ollama has limited multimodal support compared to other providers.
     * 
     * @param content Multimodal content containing text and/or binary data
     * @return Generated multimodal content from the model
     */
    override suspend fun generateContent(content: MultimodalContent): MultimodalContent
    {
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              content = content,
              metadata = mapOf(
                  "method" to "generateContent",
                  "hasText" to content.text.isNotEmpty(),
                  "hasBinaryContent" to content.binaryContent.isNotEmpty(),
                  "note" to "Ollama has limited multimodal support"
              ))
        
        return try {
            // For now, Ollama primarily supports text generation
            // Binary content support would need to be implemented based on specific model capabilities
            val textResult = generateText(content.text)
            val result = MultimodalContent(text = textResult)
            
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, result)
            result
            
        } catch (e: Exception) {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION, error = e)
            MultimodalContent("")
        }
    }
    
    /**
     * Parses Ollama response to extract generated text.
     * 
     * @param response Raw response from Ollama API
     * @return Extracted text content
     */
    protected fun parseOllamaResponse(response: String): String {
        return try {
            // Parse Ollama response format - implementation depends on actual response structure
            val json = kotlinx.serialization.json.Json.parseToJsonElement(response)
            if (json is kotlinx.serialization.json.JsonObject) {
                json["response"]?.let { element ->
                    if (element is kotlinx.serialization.json.JsonPrimitive) {
                        element.content
                    } else null
                } ?: response
            } else response
        } catch (e: Exception) {
            response // Return raw response if parsing fails
        }
    }
    
    /**
     * Extracts reasoning content from Ollama response for DEBUG level tracing.
     * 
     * @param response Raw response from Ollama API
     * @param modelName Name of the model being used
     * @return Extracted reasoning content or empty string if not available
     */
    protected fun extractOllamaReasoning(response: String, modelName: String): String {
        return try {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(response)
            if (json is kotlinx.serialization.json.JsonObject) {
                // Extract reasoning/thinking content if present in Ollama response
                json["reasoning"]?.let { element ->
                    if (element is kotlinx.serialization.json.JsonPrimitive) element.content else null
                } ?: json["thinking"]?.let { element ->
                    if (element is kotlinx.serialization.json.JsonPrimitive) element.content else null
                } ?: json["chain_of_thought"]?.let { element ->
                    if (element is kotlinx.serialization.json.JsonPrimitive) element.content else null
                } ?: ""
            } else ""
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Checks if the model supports reasoning/thinking modes.
     * 
     * @param modelName Name of the model to check
     * @return True if the model supports reasoning, false otherwise
     */
    protected fun modelSupportsReasoning(modelName: String): Boolean {
        return modelName.lowercase().contains("reasoning") || 
               modelName.lowercase().contains("cot") ||
               modelName.lowercase().contains("think")
    }

   
}