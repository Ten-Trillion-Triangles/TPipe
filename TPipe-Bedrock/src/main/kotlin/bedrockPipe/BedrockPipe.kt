package bedrockPipe

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelRequest
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.SystemContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.InferenceConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.ResponseStream
import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailStreamConfiguration
import aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngine
import aws.smithy.kotlin.runtime.content.Document
import com.TTT.Debug.*
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.MultimodalContent
import env.bedrockEnv
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * AWS Bedrock provider implementation for TPipe framework.
 * 
 * Supports all AWS Bedrock foundation models including Claude, Titan, Llama, Mistral,
 * Cohere, AI21 Jamba, and DeepSeek models. Automatically handles model-specific request
 * formats and response parsing. Integrates with inference profiles for models requiring
 * provisioned throughput.
 * 
 * @property region AWS region for Bedrock API calls (default: us-east-1)
 * @property bedrockClient AWS Bedrock Runtime client instance
 */
@kotlinx.serialization.Serializable
open class BedrockPipe : Pipe() {

//=========================================Provider interface===========================================================

    /**
     * Aws does not allow for chars outside of this pattern: ^[a-zA-Z0-9](-*[a-zA-Z0-9])*
     * We need to obliterate all non-conforming chars in this case.
     */

//=========================================Properties===================================================================

    /**
     * AWS region for Bedrock API calls.
     * 
     * Specifies the AWS region where Bedrock services will be accessed.
     * Different regions may have different model availability and pricing.
     * Some models like GPT-OSS are only available in specific regions (us-west-2).
     * 
     * @see setRegion for updating this value
     * @see init for region validation and auto-correction
     */
    @kotlinx.serialization.Serializable
    private var region: String = "us-east-1"
    
    /**
     * Read timeout in seconds for Bedrock API calls.
     * 
     * Controls how long the client will wait for a response from Bedrock before timing out.
     * Default is 3600 seconds (60 minutes) to support Claude 3.7 Sonnet and Claude 4 models
     * which can take up to 60 minutes for complex inference tasks.
     * 
     * Lower values may cause timeouts for long-running inference operations.
     * Higher values increase resource usage but prevent premature timeouts.
     * 
     * @see setReadTimeout for updating this value
     * @see init for client configuration with this timeout
     */
    @kotlinx.serialization.Serializable
    private var readTimeoutSeconds: Long = 3600

    /**
     * AWS Bedrock Runtime client instance.
     * 
     * The primary client used for all Bedrock API interactions including
     * InvokeModel, InvokeModelWithResponseStream, Converse, and ConverseStream operations.
     * Initialized during init() with region-specific configuration and timeout settings.
     * 
     * Null until init() is called, after which it remains active for the pipe's lifetime.
     * Uses OkHttpEngine for HTTP transport with configurable timeouts.
     * 
     * @see init for client initialization
     * @see generateText for client usage in API calls
     */
    @kotlinx.serialization.Serializable
    protected var bedrockClient: BedrockRuntimeClient? = null

    /**
     * Flag to enable Converse API instead of legacy Invoke API.
     * 
     * When true, uses the newer Converse API which provides:
     * - Unified interface across all model families
     * - Better support for multimodal content
     * - Structured message handling with system/user/assistant roles
     * - Enhanced tool calling capabilities
     * 
     * When false, uses the legacy Invoke API with model-specific JSON formats.
     * Some models may have better compatibility with one API over the other.
     * 
     * @see useConverseApi for enabling this mode
     * @see handleConverseGeneration for Converse API execution
     */
    @kotlinx.serialization.Serializable
    protected var useConverseApi: Boolean = false
    
    /**
     * Flag to enable streaming response mode.
     * 
     * When true, uses streaming APIs (InvokeModelWithResponseStream or ConverseStream)
     * to receive response tokens incrementally as they are generated. This provides:
     * - Lower perceived latency for long responses
     * - Real-time token delivery via callbacks
     * - Better user experience for interactive applications
     * 
     * When false, uses standard APIs that return complete responses.
     * Streaming may not be supported by all models or in all regions.
     * 
     * @see enableStreaming for activating streaming mode
     * @see setStreamingCallback for handling streamed tokens
     * @see executeInvokeStream for streaming implementation
     */
    @kotlinx.serialization.Serializable
    protected var streamingEnabled: Boolean = false

    /**
     * Suspendable callback function for handling streaming response tokens.
     * 
     * Invoked for each token/chunk received during streaming operations.
     * The callback receives individual text deltas as they arrive from the model.
     * Must be suspendable to handle async operations within the callback.
     * 
     * Null when streaming is disabled or no callback is registered.
     * Automatically enables streaming when set via setStreamingCallback().
     * 
     * @see setStreamingCallback for registering callbacks
     * @see emitStreamingChunk for callback invocation
     */
    @kotlinx.serialization.Transient
    private var streamingCallback: (suspend (String) -> Unit)? = null
    
    /**
     * Tool definitions for Claude function calling capabilities.
     * 
     * Contains JSON objects defining available tools/functions that Claude models
     * can invoke during conversation. Each tool definition includes:
     * - Tool name and description
     * - Input schema with parameter definitions
     * - Required vs optional parameters
     * 
     * Empty list disables tool calling. Only supported by Claude models.
     * Tools are passed in the request and Claude can choose to invoke them.
     * 
     * @see setTools for configuring tool definitions
     * @see buildClaudeRequest for tool integration in requests
     */
    @kotlinx.serialization.Serializable
    private var toolUse: List<JsonObject> = emptyList()
    /**
     * Tool choice strategy for Claude function calling.
     * 
     * Controls how Claude selects and uses available tools:
     * - "auto": Claude decides whether to use tools based on context
     * - "any": Claude must use at least one tool in its response
     * - "tool": Claude must use the specific tool named in the choice
     * - Specific tool name: Forces Claude to use that particular tool
     * 
     * Null allows Claude full autonomy in tool usage decisions.
     * Only effective when toolUse contains tool definitions.
     * 
     * @see setToolChoice for configuring tool selection strategy
     * @see buildClaudeRequest for tool choice integration
     */
    @kotlinx.serialization.Serializable
    private var toolChoice: String? = null
    /**
     * Flag to enable prompt caching for supported models.
     * 
     * When true, enables caching of system prompts and context to reduce
     * processing time and costs for repeated similar requests. Caching:
     * - Stores processed prompt representations on AWS servers
     * - Reduces latency for subsequent requests with cached content
     * - Lowers token processing costs for cached portions
     * 
     * Currently supported by Claude models with "ephemeral" cache control.
     * Cache duration and policies are managed by AWS Bedrock.
     * 
     * @see enableCaching for activating cache mode
     * @see cacheControl for cache policy configuration
     */
    @kotlinx.serialization.Serializable
    private var enableCaching: Boolean = false
    /**
     * Cache control policy for prompt caching.
     * 
     * Specifies the caching behavior when enableCaching is true:
     * - "ephemeral": Short-term caching for immediate reuse within session
     * - Future policies may include "persistent" or custom durations
     * 
     * Null when caching is disabled. The cache policy affects:
     * - How long cached content remains available
     * - Cost implications for cache storage and retrieval
     * - Performance characteristics of cached vs uncached requests
     * 
     * @see enableCaching for cache activation with policy setting
     * @see buildClaudeRequest for cache control integration
     */
    @kotlinx.serialization.Serializable
    private var cacheControl: String? = null
    
    /**
     * Sets the AWS region for Bedrock API calls.
     * 
     * Configures which AWS region will be used for all Bedrock operations.
     * Different regions have different model availability, pricing, and latency.
     * Some models are region-restricted (e.g., GPT-OSS only in us-west-2).
     * 
     * Must be called before init() to take effect. Region changes after
     * initialization require re-initialization of the Bedrock client.
     * 
     * @param region AWS region code (e.g., "us-east-1", "eu-west-1", "ap-southeast-1")
     * @return This pipe instance for method chaining
     * @see init for region validation and client initialization
     */
    fun setRegion(region: String): BedrockPipe {
        // Store the region for later use during client initialization
        this.region = region
        return this
    }
    
    /**
     * Sets the read timeout for Bedrock API calls.
     * 
     * Controls how long the HTTP client waits for responses before timing out.
     * Critical for models with long inference times like Claude 3.7 Sonnet and Claude 4.
     * 
     * Recommended values:
     * - 60-300 seconds: Fast models (Nova Micro, Llama small)
     * - 600-1800 seconds: Standard models (Claude Sonnet, GPT-OSS)
     * - 3600+ seconds: Slow models (Claude 3.7 Sonnet, Claude 4)
     * 
     * Must be set before init() to take effect.
     * 
     * @param timeoutSeconds Timeout in seconds (minimum 30, recommended 3600 for safety)
     * @return This pipe instance for method chaining
     * @see init for timeout application to HTTP client
     */
    open fun setReadTimeout(timeoutSeconds: Long): BedrockPipe {
        // Store timeout for HTTP client configuration during init()
        this.readTimeoutSeconds = timeoutSeconds
        return this
    }
    
    /**
     * Enables Converse API for supported models.
     * 
     * Switches from legacy Invoke API to the newer Converse API which provides:
     * - Unified request/response format across all model families
     * - Better structured conversation handling with roles
     * - Enhanced multimodal content support
     * - Improved tool calling and function integration
     * - More consistent error handling and response parsing
     * 
     * Some models may have better compatibility or features with Converse API.
     * Can be used with streaming via ConverseStream endpoint.
     * 
     * @return This pipe instance for method chaining
     * @see handleConverseGeneration for Converse API implementation
     * @see generateWithConverseApi for unified Converse handling
     */
    fun useConverseApi(): BedrockPipe {
        // Enable Converse API mode for all subsequent requests
        this.useConverseApi = true
        return this
    }
    
    /**
     * Adds tool definitions for Claude function calling.
     * 
     * Configures available tools that Claude models can invoke during conversation.
     * Each tool definition should be a JSON object containing:
     * - "name": Tool identifier
     * - "description": What the tool does
     * - "input_schema": JSON schema defining parameters
     * 
     * Example tool definition:
     * ```json
     * {
     *   "name": "calculator",
     *   "description": "Performs mathematical calculations",
     *   "input_schema": {
     *     "type": "object",
     *     "properties": {
     *       "expression": {"type": "string", "description": "Math expression to evaluate"}
     *     },
     *     "required": ["expression"]
     *   }
     * }
     * ```
     * 
     * Only supported by Claude models. Tools are included in requests automatically.
     * 
     * @param tools List of tool definitions as JSON objects
     * @return This pipe instance for method chaining
     * @see setToolChoice for controlling tool selection behavior
     * @see buildClaudeRequest for tool integration in requests
     */
    fun setTools(tools: List<JsonObject>): BedrockPipe {
        // Store tool definitions for inclusion in Claude requests
        this.toolUse = tools
        return this
    }
    
    /**
     * Sets tool choice strategy for Claude.
     * 
     * Controls how Claude selects and uses available tools from the toolUse list:
     * 
     * - "auto": Claude autonomously decides whether to use tools based on context
     * - "any": Claude must use at least one available tool in its response
     * - "tool": Claude must use a tool (specific tool can be named)
     * - Specific tool name: Forces Claude to use that exact tool
     * 
     * Tool choice only applies when tools are defined via setTools().
     * Invalid tool names or choices may cause request failures.
     * 
     * @param choice Tool selection strategy ("auto", "any", "tool", or specific tool name)
     * @return This pipe instance for method chaining
     * @see setTools for defining available tools
     * @see buildClaudeRequest for tool choice implementation
     */
    fun setToolChoice(choice: String): BedrockPipe {
        // Store tool choice strategy for Claude requests
        this.toolChoice = choice
        return this
    }
    
    /**
     * Enables prompt caching for Claude.
     * 
     * Activates caching of system prompts and context to improve performance
     * and reduce costs for repeated similar requests. Caching benefits:
     * 
     * - Faster response times for cached content
     * - Lower token processing costs for cached portions
     * - Reduced computational overhead on AWS servers
     * 
     * Currently supports "ephemeral" caching which stores content temporarily
     * for reuse within the same session or short time window.
     * 
     * Cache effectiveness depends on prompt similarity and reuse patterns.
     * Not all content may be cacheable depending on AWS policies.
     * 
     * @param control Cache control type ("ephemeral" is currently supported)
     * @return This pipe instance for method chaining
     * @see buildClaudeRequest for cache control integration
     * @see buildNovaRequest for Nova caching support
     */
    fun enableCaching(control: String = "ephemeral"): BedrockPipe {
        // Enable caching with specified control policy
        this.enableCaching = true
        this.cacheControl = control
        return this
    }

    /**
     * Enables streaming mode without assigning a chunk callback.
     * 
     * Activates streaming response delivery where tokens are received incrementally
     * as the model generates them, rather than waiting for the complete response.
     * 
     * Benefits of streaming:
     * - Lower perceived latency for long responses
     * - Real-time token delivery for interactive applications
     * - Better user experience with progressive content display
     * 
     * Without a callback, streaming still improves response timing but tokens
     * are collected internally and returned as a complete string.
     * 
     * @return This pipe instance for method chaining
     * @see setStreamingCallback for handling individual tokens
     * @see executeInvokeStream for streaming implementation
     */
    fun enableStreaming(): BedrockPipe {
        // Enable streaming mode without callback assignment
        this.streamingEnabled = true
        return this
    }

    /**
     * Disables streaming mode and clears any registered callbacks.
     * 
     * Switches back to standard (non-streaming) API calls where the complete
     * response is received in a single operation. This mode:
     * 
     * - Uses InvokeModel instead of InvokeModelWithResponseStream
     * - Uses Converse instead of ConverseStream
     * - Returns complete responses without incremental delivery
     * - May have higher perceived latency for long responses
     * 
     * Also clears any previously registered streaming callbacks to prevent
     * memory leaks or unexpected callback invocations.
     * 
     * @return This pipe instance for method chaining
     * @see enableStreaming for re-enabling streaming mode
     */
    fun disableStreaming(): BedrockPipe {
        // Disable streaming and clear callback to prevent memory leaks
        this.streamingEnabled = false
        this.streamingCallback = null
        return this
    }

    /**
     * Registers a suspendable callback invoked for each streamed text delta.
     * 
     * Sets up a callback function that receives individual token chunks as they
     * arrive from the streaming API. The callback must be suspendable to handle
     * async operations like UI updates, logging, or further processing.
     * 
     * Callback characteristics:
     * - Invoked once per token/chunk received
     * - Receives raw text deltas (not cumulative text)
     * - Must handle suspending operations properly
     * - Should be fast to avoid blocking the stream
     * 
     * Automatically enables streaming mode when a callback is registered.
     * Exceptions in the callback are caught and traced but don't stop streaming.
     * 
     * @param callback Suspendable function that processes each text chunk
     * @return This pipe instance for method chaining
     * @see emitStreamingChunk for callback invocation mechanism
     * @see executeInvokeStream for streaming implementation
     */
    fun setStreamingCallback(callback: suspend (String) -> Unit): BedrockPipe {
        // Register suspendable callback and enable streaming
        this.streamingCallback = callback
        this.streamingEnabled = true
        return this
    }

    /**
     * Convenience overload for non-suspending callbacks.
     * 
     * Wraps a regular (non-suspending) callback function to work with the
     * streaming system. Useful for simple callbacks that don't need async operations:
     * 
     * - UI updates that are synchronous
     * - Simple logging or printing
     * - Basic text accumulation or processing
     * 
     * The callback is automatically wrapped in a suspending lambda for compatibility
     * with the streaming infrastructure. For async operations, use the suspending
     * version of setStreamingCallback instead.
     * 
     * @param callback Regular function that processes each text chunk
     * @return This pipe instance for method chaining
     * @see setStreamingCallback for suspendable callback version
     */
    fun setStreamingCallback(callback: (String) -> Unit): BedrockPipe {
        // Wrap non-suspending callback in suspending lambda
        this.streamingCallback = { chunk -> callback(chunk) }
        this.streamingEnabled = true
        return this
    }
    

    
    /**
     * Initializes the Bedrock client and resolves model configuration.
     * 
     * Performs comprehensive setup for AWS Bedrock integration:
     * 
     * 1. **Inference Profile Resolution**: Loads ~/.aws/inference.txt mappings
     *    and resolves model IDs to inference profile ARNs for provisioned throughput
     * 
     * 2. **Model Validation**: Sets default model if none specified and validates
     *    region requirements for specific models (e.g., GPT-OSS requires us-west-2)
     * 
     * 3. **Client Initialization**: Creates BedrockRuntimeClient with:
     *    - Configured region and timeout settings
     *    - OkHttpEngine for reliable HTTP transport
     *    - Extended timeouts for slow models (Claude 3.7 Sonnet, Claude 4)
     * 
     * Must be called before any text generation operations. Subsequent calls
     * will reinitialize the client with current configuration.
     * 
     * @return This pipe instance for method chaining
     * @throws Exception If client initialization fails or credentials are invalid
     * @see bedrockEnv.loadInferenceConfig for inference profile loading
     * @see generateText for client usage in API calls
     */
    override suspend fun init(): Pipe
    {
        super.init()

        // Load inference profile mappings from ~/.aws/inference.txt
        // This allows mapping model IDs to provisioned throughput ARNs
        bedrockEnv.loadInferenceConfig()
        
        // Resolve model to inference profile if configured
        if (model.isNotEmpty())
        {
            // Check if this model has a configured inference profile
            val inferenceId = bedrockEnv.getInferenceProfileId(model)
            if (!inferenceId.isNullOrEmpty())
            {
                // Use inference profile ARN instead of direct model ID
                // This enables provisioned throughput for better performance
                model = inferenceId
            }
        }

        else
        {
            // Set default model if none specified
            // Claude 3 Sonnet provides good balance of capability and speed
            model = "anthropic.claude-3-sonnet-20240229-v1:0"
        }
        
        // Validate GPT-OSS region requirement
        if (model.contains("openai.gpt-oss") && region.lowercase() != "us-west-2")
        {
            // GPT-OSS models are only available in us-west-2 region
            // Force region change to ensure API calls succeed
            region = "us-west-2" //Force to us-west-2 since that's the only supported region for gpt.
        }
        
        // Initialize AWS Bedrock Runtime client with timeout configuration
        // For Claude 3.7 Sonnet and Claude 4 models, AWS recommends increasing
        // the read timeout to at least 60 minutes (3600 seconds) as inference can take up to 60 minutes.
        bedrockClient = BedrockRuntimeClient {
            // Set the AWS region for all API calls
            region = this@BedrockPipe.region
            
            // Configure HTTP client with extended timeouts for slow models
            httpClient(OkHttpEngine) {
                // Socket read timeout for receiving response data
                socketReadTimeout = readTimeoutSeconds.seconds
                // Connection timeout for establishing initial connection
                connectTimeout = 60.seconds
            }
        }

        return this
    }




    /**
     * Generates text using AWS Bedrock foundation models with automatic API selection.
     * 
     * **Core Functionality:**
     * - Supports both Invoke API and Converse API endpoints with automatic fallback
     * - Handles model-specific request formatting and response parsing
     * - Provides comprehensive error recovery and graceful degradation
     * - Supports streaming and non-streaming response modes
     * 
     * **API Selection Logic:**
     * - Uses Converse API when useConverseApi is enabled
     * - Falls back to Invoke API for DeepSeek models if Converse fails
     * - Automatically handles model-specific request/response formats
     * 
     * **Supported Models:**
     * - Anthropic Claude (all versions including 3.7 Sonnet, Claude 4)
     * - Amazon Nova (Micro, Lite, Pro, Premier)
     * - OpenAI GPT-OSS with Harmony reasoning
     * - DeepSeek R1 with reasoning content
     * - Meta Llama, Mistral, Cohere Command, AI21 Jamba
     * - Amazon Titan, Qwen3 with thinking mode
     * 
     * @param promptInjector User input text to be processed by the model
     * @return Generated text response from the model, or empty string on error
     * @throws Exception Catches all exceptions and returns empty string for graceful degradation
     * @see handleConverseGeneration for Converse API implementation
     * @see extractTextFromResponse for response parsing
     * @see isMaxTokenStopReason for overflow detection
     */
    override suspend fun generateText(promptInjector: String): String
    {
        // Initialize comprehensive tracing for this API call
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "method" to "generateText",
                  "model" to model,
                  "region" to region,
                  "useConverseApi" to useConverseApi,
                  "promptLength" to promptInjector.length
              ))
        
        return try 
        {
            // Ensure the Bedrock client was properly initialized during init()
            // If not initialized, fail gracefully rather than throwing exceptions
            val client = bedrockClient ?: run {
                trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                      metadata = mapOf("error" to "Client not initialized"))
                return ""
            }
            
            // Use the prompt as-is (context truncation happens earlier in the pipeline)
            val fullPrompt = promptInjector
            
            // Default to Claude Sonnet if no model specified
            val modelId = model.ifEmpty { "anthropic.claude-3-sonnet-20240229-v1:0" }
            
            // Use Converse API for all models when enabled
            if (useConverseApi)
            {
                return generateWithConverseApi(client, modelId, fullPrompt)
            }
            
            // For all other models, use the standard Invoke API approach
            // Each model family has different JSON request/response formats
            trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
                  metadata = mapOf("step" to "buildRequest", "modelId" to modelId))
            
            // Build model-specific JSON request based on the model family
            // Each model has different parameter names and structures
            val requestJson = when
            {
                // OpenAI GPT-OSS models use OpenAI-compatible format with reasoning support
                modelId.contains("openai.gpt-oss") -> buildGptOssRequest(fullPrompt)
                
                // Amazon Nova models use Converse-style format with inferenceConfig
                modelId.contains("amazon.nova") -> buildNovaRequest(fullPrompt)
                
                // Anthropic Claude models use Messages API format with advanced features
                modelId.contains("anthropic.claude") -> buildClaudeRequest(fullPrompt)
                
                // Amazon Titan models use simple inputText + textGenerationConfig format
                modelId.contains("amazon.titan") -> buildTitanRequest(fullPrompt)
                
                // AI21 Jurassic models use basic prompt + parameters format
                modelId.contains("ai21.j2") -> buildJurassicRequest(fullPrompt)
                
                // Cohere Command models use prompt + generation parameters (p/k naming)
                modelId.contains("cohere.command") -> buildCohereRequest(fullPrompt)
                
                // Meta Llama models use prompt + max_gen_len format
                modelId.contains("meta.llama") -> buildLlamaRequest(fullPrompt)
                
                // Mistral models use prompt + parameters with 'stop' instead of 'stop_sequences'
                modelId.contains("mistral") -> buildMistralRequest(fullPrompt)
                
                // Qwen3 models support thinking mode and multilingual capabilities  
                modelId.contains("qwen") -> buildQwenRequest(fullPrompt)
                
                modelId.contains("deepseek") -> 
                {
                    // DeepSeek has different request formats for Converse vs Invoke API
                    if (useConverseApi)
                    {
                        buildDeepSeekConverseRequest(fullPrompt)
                    }
                    else
                    {
                        buildDeepSeekRequest(fullPrompt)
                    }
                }
                
                // Fallback for unknown models using common parameter names
                else -> buildGenericRequest(fullPrompt)
            }

            // Attempt streaming if enabled and callback is available
            if (streamingEnabled)
            {
                val streamingResult = executeInvokeStream(client, modelId, requestJson)
                if (streamingResult != null)
                {
                    return streamingResult
                }
            }
            
            // Execute the Invoke API call with the model-specific JSON
            // This is the legacy but stable API that all models support
            trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
                  metadata = mapOf("step" to "invokeModel", "requestSize" to requestJson.length))
            
            // Create the InvokeModel request with JSON payload
            val invokeRequest = InvokeModelRequest {
                this.modelId = modelId
                body = requestJson.toByteArray()
                contentType = "application/json"
            }

            // Get the raw JSON response from Bedrock
            val response = client.invokeModel(invokeRequest)
            val responseBody = response.body?.let { String(it) } ?: ""
            
            // Parse the model-specific JSON response to extract the generated text
            // Each model family returns responses in different JSON structures
            val extractedText = extractTextFromResponse(responseBody, modelId)
            
            // Collect comprehensive metadata about the response for tracing
            val responseMetadata = mutableMapOf<String, Any>(
                "responseLength" to extractedText.length,
                "responseBodySize" to responseBody.length,
                "success" to true
            )
            
            // Extract reasoning content if the model produced any (DeepSeek R1, GPT-OSS)
            // This happens regardless of useModelReasoning flag - we always capture it
            val reasoningContent = extractReasoningContent(responseBody, modelId)
            
            // Extract stop reason to understand why the model stopped generating
            val stopReason = extractStopReasonFromInvokeResponse(responseBody, modelId)
            
            // Add reasoning metadata if reasoning content was found
            if (reasoningContent.isNotEmpty()) {
                responseMetadata["reasoningContent"] = reasoningContent
                responseMetadata["modelSupportsReasoning"] = true
                responseMetadata["reasoningEnabled"] = useModelReasoning
            }
            
            // Add stop reason if available (helps with debugging incomplete responses)
            if (stopReason.isNotEmpty()) {
                responseMetadata["stopReason"] = stopReason
            }
            
            // Check for max token overflow condition using our detection function
            val isMaxTokenOverflow = isMaxTokenStopReason(stopReason)
            if (isMaxTokenOverflow)
            {
                // Mark this response as having encountered max token overflow
                responseMetadata["maxTokenOverflow"] = true
                
                // If allowMaxTokenOverflow is enabled and we have actual content (not just reasoning)
                if (allowMaxTokenOverflow && extractedText.isNotEmpty())
                {
                    // Allow the overflow to pass through with success tracing
                    trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, 
                          metadata = responseMetadata + mapOf("overflowAllowed" to true))
                }

                else if (!allowMaxTokenOverflow)
                {
                    // Treat as error if overflow not allowed - this is the default behavior
                    trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                          error = RuntimeException("Max tokens exceeded"),
                          metadata = responseMetadata + mapOf("overflowAllowed" to false))
                    return ""
                }

                else
                {
                    // No actual content despite overflow being allowed - still treat as error
                    trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                          error = RuntimeException("Max tokens exceeded with no content output"),
                          metadata = responseMetadata + mapOf("overflowAllowed" to true, "noContent" to true))
                    return ""
                }
            }
            
            // Extract token usage information for cost tracking and optimization
            extractTokenUsageFromInvokeResponse(responseBody, modelId)?.let { usage ->
                responseMetadata.putAll(usage)
            }
            
            // Trace successful completion with all collected metadata
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, metadata = responseMetadata)
            extractedText
        } 
        catch (e: Exception) 
        {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  error = e,
                  metadata = mapOf(
                      "errorType" to (e::class.simpleName ?: "Unknown"),
                      "errorMessage" to (e.message ?: "Unknown error")
                  ))
            ""
        }
    }
    
    /**
     * Generates multimodal content using AWS Bedrock foundation models.
     * 
     * Processes multimodal input and returns both generated text and reasoning content.
     * Uses the same model-specific request building logic as generateText but populates
     * both text and modelReasoning fields in the returned MultimodalContent.
     * 
     * For reasoning-capable models (Qwen3, DeepSeek R1, GPT-OSS), the reasoning
     * content is extracted and included regardless of the useModelReasoning flag.
     * 
     * @param content Multimodal content containing text and/or binary data
     * @return Generated multimodal content with both text and modelReasoning populated
     * @see generateText for core generation logic
     * @see extractReasoningContent for reasoning extraction
     */
    override suspend fun generateContent(content: MultimodalContent): MultimodalContent
    {
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              content = content,
              metadata = mapOf(
                  "method" to "generateContent",
                  "hasText" to content.text.isNotEmpty(),
                  "hasBinaryContent" to content.binaryContent.isNotEmpty(),
                  "delegateToGenerateText" to false
              ))
        
        return try {
            // Ensure the Bedrock client was properly initialized during init()
            val client = bedrockClient ?: run {
                trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                      metadata = mapOf("error" to "Client not initialized"))
                return MultimodalContent("")
            }
            
            val fullPrompt = content.text
            val modelId = model.ifEmpty { "anthropic.claude-3-sonnet-20240229-v1:0" }
            
            // Use Converse API when enabled (same as generateText)
            if (useConverseApi)
            {
                val textResult = generateWithConverseApi(client, modelId, fullPrompt)
                val result = MultimodalContent(textResult)
                result.modelReasoning = extractReasoningContent(textResult, modelId)
                return result
            }
            
            // Build model-specific JSON request (same logic as generateText)
            val requestJson = when
            {
                modelId.contains("openai.gpt-oss") -> buildGptOssRequest(fullPrompt)
                modelId.contains("amazon.nova") -> buildNovaRequest(fullPrompt)
                modelId.contains("anthropic.claude") -> buildClaudeRequest(fullPrompt)
                modelId.contains("amazon.titan") -> buildTitanRequest(fullPrompt)
                modelId.contains("ai21.j2") -> buildJurassicRequest(fullPrompt)
                modelId.contains("cohere.command") -> buildCohereRequest(fullPrompt)
                modelId.contains("meta.llama") -> buildLlamaRequest(fullPrompt)
                modelId.contains("mistral") -> buildMistralRequest(fullPrompt)
                modelId.contains("qwen") -> buildQwenRequest(fullPrompt)
                modelId.contains("deepseek") -> 
                {
                    if (useConverseApi)
                    {
                        buildDeepSeekConverseRequest(fullPrompt)
                    }

                    else
                    {
                        buildDeepSeekRequest(fullPrompt)
                    }
                }
                else -> buildGenericRequest(fullPrompt)
            }
            
            // Execute the Invoke API call (same logic as generateText)
            val invokeRequest = InvokeModelRequest {
                this.modelId = modelId
                body = requestJson.toByteArray()
                contentType = "application/json"
            }

            val response = client.invokeModel(invokeRequest)
            val responseBody = response.body?.let { String(it) } ?: ""
            
            // Extract BOTH text and reasoning content
            val extractedText = extractTextFromResponse(responseBody, modelId)
            val reasoningContent = extractReasoningContent(responseBody, modelId)
            
            // Create result with BOTH fields populated
            val result = MultimodalContent(
                text = extractedText,
                modelReasoning = reasoningContent
            )
            
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, result,
                  metadata = mapOf(
                      "responseLength" to extractedText.length,
                      "reasoningLength" to reasoningContent.length,
                      "hasReasoning" to reasoningContent.isNotEmpty()
                  ))
            result
            
        } catch (e: Exception) {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION, error = e)
            MultimodalContent("")
        }
    }
    
    /**
     * Configures context window truncation settings based on the current model's capabilities.
     * 
     * Sets model-specific context window sizes and truncation strategies to maximize
     * context utilization while staying within model limits. Each model family has
     * different context window sizes and optimal truncation approaches.
     * 
     * Context window sizes (in thousands of tokens):
     * - Claude models: 200K tokens
     * - Nova Micro: 128K, Nova Lite/Pro: 300K, Nova Premier: 1M
     * - Llama models: 8K tokens  
     * - GPT-OSS: 128K tokens
     * - Qwen3: 32K (small) or 128K (large) tokens
     * 
     * @return This pipe instance for method chaining
     * @see ContextWindowSettings for available truncation strategies
     */
    override fun truncateModuleContext(): Pipe {
        trace(TraceEventType.CONTEXT_TRUNCATE, TracePhase.CONTEXT_PREPARATION,
              metadata = mapOf(
                  "contextWindowSize" to contextWindowSize,
                  "truncateAsString" to truncateContextAsString,
                  "contextWindowTruncation" to contextWindowTruncation.name
              ))
        
        // Use default Claude model if none specified
        val modelId = model.ifEmpty { "anthropic.claude-3-sonnet-20240229-v1:0" }
        
        // Configure truncation settings based on model family capabilities
        when
        {
            modelId.contains("anthropic.claude") -> {
                contextWindowSize = 200
                multiplyWindowSizeBy = 1000
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 4
            }
            modelId.contains("amazon.nova-micro") -> {
                contextWindowSize = 128
                multiplyWindowSizeBy = 1000
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 4
            }
            modelId.contains("amazon.nova-lite") || modelId.contains("amazon.nova-pro") -> {
                contextWindowSize = 300
                multiplyWindowSizeBy = 1000
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 4
            }
            modelId.contains("amazon.nova-premier") -> {
                contextWindowSize = 1000
                multiplyWindowSizeBy = 1000
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 4
            }
            modelId.contains("meta.llama") -> {
                contextWindowSize = 8
                multiplyWindowSizeBy = 1024
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 4
            }
            modelId.contains("ai21.jamba") -> {
                contextWindowSize = 256
                multiplyWindowSizeBy = 1000
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 4
            }
                modelId.contains("qwen") -> {
                contextWindowSize = if (modelId.contains("-0.6b-") || modelId.contains("-1.7b-") || modelId.contains("-4b-")) {
                    32  // 32K for small models
                } else {
                    128 // 128K for large models and MoE
                }
                multiplyWindowSizeBy = 1000
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = true
                countSubWordsIfSplit = true
                nonWordSplitCount = 2
            }
            modelId.contains("deepseek") -> {
                multiplyWindowSizeBy = 1000
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = false
                nonWordSplitCount = 3
            }
            modelId.contains("writer.palmyra-x4") -> {
                contextWindowSize = 128
                multiplyWindowSizeBy = 1024
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 4
            }
            modelId.contains("writer.palmyra-x5") -> {
                contextWindowSize = 1000
                multiplyWindowSizeBy = 1000
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 4
            }
            modelId.contains("openai.gpt-oss") -> {
                contextWindowSize = 128
                multiplyWindowSizeBy = 1000
                contextWindowTruncation = ContextWindowSettings.TruncateTop
                countSubWordsInFirstWord = true
                favorWholeWords = true
                countOnlyFirstWordFound = false
                splitForNonWordChar = true
                alwaysSplitIfWholeWordExists = false
                countSubWordsIfSplit = true
                nonWordSplitCount = 4
            }
        }
        
        if (truncateContextAsString)
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
     * Builds request JSON for Amazon Nova models.
     * 
     * Uses Nova's Converse API format with system and messages arrays.
     * Maps TPipe parameters to Nova's inferenceConfig structure.
     * Supports prompt caching for Nova models.
     * 
     * @param prompt The formatted prompt text
     * @return JSON request string for Nova models
     */
    fun buildNovaRequest(prompt: String): String
    {
        val messages = mutableListOf<JsonObject>()
        
        messages.add(buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("text", prompt)
                    if (enableCaching && cacheControl != null)
                    {
                        putJsonObject("cache_control") {
                            put("type", cacheControl)
                        }
                    }
                })
            }
        })
        
        return buildJsonObject {
            if (systemPrompt.isNotEmpty())
            {
                putJsonArray("system") {
                    add(buildJsonObject {
                        put("text", systemPrompt)
                        if (enableCaching && cacheControl != null)
                        {
                            putJsonObject("cache_control") {
                                put("type", cacheControl)
                            }
                        }
                    })
                }
            }
            put("messages", JsonArray(messages))
            putJsonObject("inferenceConfig") {
                put("maxTokens", maxTokens)
                if (temperature > 0) put("temperature", temperature)
                if (topP < 1.0) put("topP", topP)
                if (topK > 0) put("topK", topK)
                if (stopSequences.isNotEmpty()) put("stopSequences", JsonArray(stopSequences.map { JsonPrimitive(it) }))
            }
        }.toString()
    }
    
    /**
     * Builds request JSON for Anthropic Claude models.
     * 
     * Creates Messages API format with system and user messages.
     * Includes generation parameters and Claude-specific features like
     * tools, caching, and tool choice configuration.
     * 
     * @param prompt The formatted prompt text
     * @return JSON request string for Claude models
     * @see setTools for tool configuration
     * @see enableCaching for cache control
     */
    fun buildClaudeRequest(prompt: String): String {
        val messages = mutableListOf<JsonObject>()

        messages.add(buildJsonObject {
            put("role", "user")
put("content", if (enableCaching && cacheControl != null) {
                JsonArray(listOf(buildJsonObject {
                    put("type", "text")
                    put("text", prompt)
                    putJsonObject("cache_control") {
                        put("type", cacheControl)
                    }
                }))
            } else {
                JsonPrimitive(prompt)
            })
        })
        
        return buildJsonObject {
            put("anthropic_version", "bedrock-2023-05-31")
            put("max_tokens", maxTokens)
            if (systemPrompt.isNotEmpty()) {
put("system", if (enableCaching && cacheControl != null) {
                    JsonArray(listOf(buildJsonObject {
                        put("type", "text")
                        put("text", systemPrompt)
                        putJsonObject("cache_control") {
                            put("type", cacheControl)
                        }
                    }))
                } else {
                    JsonPrimitive(systemPrompt)
                })
            }
            put("messages", JsonArray(messages))
            if (toolUse.isNotEmpty()) put("tools", JsonArray(toolUse))
            if (toolChoice != null) put("tool_choice", buildJsonObject {
                put("type", toolChoice)
            })
            if (temperature > 0) put("temperature", temperature)
            if (topP < 1.0) put("top_p", topP)
            if (stopSequences.isNotEmpty()) put("stop_sequences", JsonArray(stopSequences.map { JsonPrimitive(it) }))
        }.toString()
    }
    
    /**
     * Builds request JSON for Amazon Titan models.
     * 
     * Uses Titan's text generation format with inputText and textGenerationConfig.
     * Maps TPipe parameters to Titan-specific parameter names.
     * 
     * @param prompt The formatted prompt text
     * @return JSON request string for Titan models
     */
    private fun buildTitanRequest(prompt: String): String
    {
        return buildJsonObject {
            put("inputText", prompt)
            putJsonObject("textGenerationConfig") {
                put("maxTokenCount", maxTokens)
                if (temperature > 0) put("temperature", temperature)
                if (topP < 1.0) put("topP", topP)
                if (stopSequences.isNotEmpty()) put("stopSequences", JsonArray(stopSequences.map { JsonPrimitive(it) }))
            }
        }.toString()
    }
    
    /**
     * Builds request JSON for AI21 Jurassic models.
     * 
     * Uses AI21's simple prompt format with generation parameters.
     * 
     * @param prompt The formatted prompt text
     * @return JSON request string for Jurassic models
     */
    private fun buildJurassicRequest(prompt: String): String
    {
        return buildJsonObject {
            put("prompt", prompt)
            put("maxTokens", maxTokens)
            if (temperature > 0) put("temperature", temperature)
            if (topP < 1.0) put("topP", topP)
            if (stopSequences.isNotEmpty()) put("stopSequences", JsonArray(stopSequences.map { JsonPrimitive(it) }))
        }.toString()
    }
    
    /**
     * Builds request JSON for Cohere Command models.
     * 
     * Uses Cohere's generation format with prompt and parameters.
     * Maps topP to 'p' and topK to 'k' per Cohere API requirements.
     * 
     * @param prompt The formatted prompt text
     * @return JSON request string for Cohere models
     */
    private fun buildCohereRequest(prompt: String): String
    {
        return buildJsonObject {
            put("prompt", prompt)
            put("max_tokens", maxTokens)
            if (temperature > 0) put("temperature", temperature)
            if (topP < 1.0) put("p", topP)
            if (topK > 0) put("k", topK)
            if (stopSequences.isNotEmpty()) put("stop_sequences", JsonArray(stopSequences.map { JsonPrimitive(it) }))
        }.toString()
    }
    
    /**
     * Builds request JSON for Meta Llama models.
     * 
     * Uses Llama's generation format with prompt and max_gen_len parameter.
     * 
     * @param prompt The formatted prompt text
     * @return JSON request string for Llama models
     */
    private fun buildLlamaRequest(prompt: String): String
    {
        return buildJsonObject {
            put("prompt", prompt)
            put("max_gen_len", maxTokens)
            if (temperature > 0) put("temperature", temperature)
            if (topP < 1.0) put("top_p", topP)
        }.toString()
    }
    
    /**
     * Builds request JSON for Mistral models.
     * 
     * Uses Mistral's generation format with prompt and generation parameters.
     * Uses 'stop' instead of 'stop_sequences' for stop tokens.
     * 
     * @param prompt The formatted prompt text
     * @return JSON request string for Mistral models
     */
    private fun buildMistralRequest(prompt: String): String {
        return buildJsonObject {
            put("prompt", prompt)
            put("max_tokens", maxTokens)
            if (temperature > 0) put("temperature", temperature)
            if (topP < 1.0) put("top_p", topP)
            if (topK > 0) put("top_k", topK)
            if (stopSequences.isNotEmpty()) put("stop", JsonArray(stopSequences.map { JsonPrimitive(it) }))
        }.toString()
    }
    
    /**
     * Builds request JSON for OpenAI GPT-OSS models.
     * 
     * Uses OpenAI Chat Completions format with system/user roles.
     * Supports Harmony reasoning with developer role for PCP context.
     * Includes reasoning_effort parameter for controlling reasoning intensity.
     * 
     * @param prompt The formatted prompt text
     * @return JSON request string for GPT-OSS models
     * @see modelReasoningSettingsV3 for reasoning effort configuration
     */
    fun buildGptOssRequest(prompt: String): String {
        val systemBlocks = mutableListOf<JsonObject>()
        val messages = mutableListOf<JsonObject>()

        // Add system prompt to system array
        if (systemPrompt.isNotEmpty()) {
            systemBlocks.add(buildJsonObject {
                put("text", systemPrompt)
            })
        }

        // Add PCP context as system instruction (developer role equivalent)
        if (!pcpContext.tpipeOptions.isEmpty()) {
            val pcpInstructions = "Available tools: ${com.TTT.Util.serialize(pcpContext, false)}"
            systemBlocks.add(buildJsonObject {
                put("text", pcpInstructions)
            })
        }

        // Add user message
        messages.add(buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("text", prompt)
                })
            })
        })

        // Build main request object
        return buildJsonObject {
            put("modelId", model) // Note: different field name
            if (systemBlocks.isNotEmpty()) {
                put("system", JsonArray(systemBlocks))
            }
            put("messages", JsonArray(messages))

            // Inference configuration
            put("inferenceConfig", buildJsonObject {
                if (temperature > 0) put("temperature", temperature)
                if (maxTokens > 0) put("maxTokens", maxTokens)
                if (topP < 1.0) put("topP", topP)
                if (stopSequences.isNotEmpty()) {
                    put("stopSequences", JsonArray(stopSequences.map { JsonPrimitive(it) }))
                }
            })



            // Model-specific parameters
            put("additionalModelRequestFields", buildJsonObject {
                put("reasoning_effort", if (modelReasoningSettingsV3.isNotEmpty()) "${modelReasoningSettingsV3}" else "low")
            })
        }.toString()
    }

    /**
     * Builds a Converse API request object for GPT-OSS models using the same
     * structure as the Invoke API request builder.
     */
    fun buildGptOssConverseRequest(modelId: String, contentBlocks: List<ContentBlock>): ConverseRequest {
        // For ContentBlocks, we need to extract text to reuse existing JSON logic
        val promptText = contentBlocks.filterIsInstance<ContentBlock.Text>()
            .joinToString("\n") { it.value }
        
        // First build the JSON structure to reuse existing logic
        val requestJson = buildGptOssRequest(promptText)
        val requestObj = Json.parseToJsonElement(requestJson).jsonObject

        // Create messages array with ContentBlocks
        val messages = mutableListOf<Message>()
        messages.add(Message {
            role = ConversationRole.User
            content = contentBlocks
        })

        // Extract system blocks from JSON structure
        val systemBlocks = mutableListOf<SystemContentBlock>()
        requestObj["system"]?.jsonArray?.forEach { systemBlock ->
            val text = systemBlock.jsonObject["text"]?.jsonPrimitive?.content
            if (!text.isNullOrEmpty()) {
                systemBlocks.add(SystemContentBlock.Text(text))
            }
        }

        // Build the ConverseRequest with extracted configuration
        return ConverseRequest {
            this.modelId = modelId
            this.messages = messages
            if (systemBlocks.isNotEmpty()) {
                this.system = systemBlocks
            }

            // Map inference configuration from JSON structure
            val inferenceConfigObj = requestObj["inferenceConfig"]?.jsonObject
            inferenceConfig = InferenceConfiguration {
                maxTokens = inferenceConfigObj?.get("maxTokens")?.jsonPrimitive?.int ?: this@BedrockPipe.maxTokens
                inferenceConfigObj?.get("temperature")?.jsonPrimitive?.double?.let { temperature = it.toFloat() }
                inferenceConfigObj?.get("topP")?.jsonPrimitive?.double?.let { topP = it.toFloat() }
                inferenceConfigObj?.get("stopSequences")?.jsonArray?.let { stopSeqs ->
                    stopSequences = stopSeqs.map { it.jsonPrimitive.content }
                }
            }

            // Handle additional model fields using reflection for GPT-OSS specific parameters
            requestObj["additionalModelRequestFields"]?.jsonObject?.let { additionalFields ->
                val documentMap = mutableMapOf<String, Any>()
                additionalFields.forEach { (key, value) ->
                    documentMap[key] = value.jsonPrimitive.content
                }

                try {
                    // Use reflection to create Document instance since constructor is not public
                    val documentClass = Document::class.java
                    val constructor = documentClass.getDeclaredConstructor()
                    constructor.isAccessible = true
                    val document = constructor.newInstance()

                    // Set the internal map field
                    val mapField = documentClass.getDeclaredField("value")
                    mapField.isAccessible = true
                    mapField.set(document, documentMap)

                    additionalModelRequestFields = document
                } catch (_: Exception) {
                    // If reflection fails we silently fall back to the JSON payload.
                }
            }
        }
    }

    private fun buildGptOssConverseRequest(modelId: String, prompt: String): ConverseRequest {
        return buildGptOssConverseRequest(modelId, listOf(ContentBlock.Text(prompt)))
    }
    
    /**
     * Builds request JSON for Qwen3 models.
     * 
     * Qwen3 models support hybrid thinking modes and multilingual capabilities.
     * Uses OpenAI-compatible format with enable_thinking parameter for reasoning.
     * Includes PCP context support for tool calling integration.
     * 
     * @param prompt The formatted prompt text
     * @return JSON request string for Qwen3 models
     * @see useModelReasoning for thinking mode control
     */
    fun buildQwenRequest(prompt: String): String
    {
        return buildJsonObject {
            val messages = mutableListOf<JsonElement>()
            
            if (systemPrompt.isNotEmpty()) {
                messages.add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            
            messages.add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
            
            put("messages", JsonArray(messages))
            put("max_tokens", maxTokens)
            if (temperature > 0) put("temperature", temperature)
            if (topP < 1.0) put("top_p", topP)
            if (stopSequences.isNotEmpty()) put("stop", JsonArray(stopSequences.map { JsonPrimitive(it) }))
            put("stream", false)
            
        }.toString()
    }
    
    /**
     * Builds request JSON for DeepSeek R1 models using Invoke API.
     * 
     * Uses DeepSeek's simple prompt format for Invoke API.
     * Maps TPipe parameters to DeepSeek's request structure.
     * 
     * @param prompt The formatted prompt text
     * @return JSON request string for DeepSeek models
     */
    private fun buildDeepSeekRequest(prompt: String): String
    {
        val fullPrompt = if (systemPrompt.isNotEmpty()) "$systemPrompt\n\n$prompt" else prompt
        
        return buildJsonObject {
            put("prompt", fullPrompt)
            put("max_tokens", maxTokens)
            if (temperature > 0) put("temperature", temperature)
            if (topP < 1.0) put("top_p", topP)
            if (stopSequences.isNotEmpty()) put("stop", JsonArray(stopSequences.map { JsonPrimitive(it) }))
        }.toString()
    }

    /**
     * Builds ConverseRequest object for DeepSeek models.
     * 
     * Creates a properly structured ConverseRequest with messages and system blocks
     * for DeepSeek models using the Converse API. Maps TPipe parameters to
     * InferenceConfiguration format.
     * 
     * @param modelId DeepSeek model identifier
     * @param prompt User prompt text
     * @return ConverseRequest configured for DeepSeek models
     */
    fun buildDeepSeekConverseRequestObject(modelId: String, contentBlocks: List<ContentBlock>): ConverseRequest {
        val messages = mutableListOf<Message>()
        messages.add(Message {
            role = ConversationRole.User
            content = contentBlocks
        })

        val systemBlocks = if (systemPrompt.isNotEmpty()) {
            listOf(SystemContentBlock.Text(systemPrompt))
        } else emptyList()

        return ConverseRequest {
            this.modelId = modelId
            this.messages = messages
            if (systemBlocks.isNotEmpty()) this.system = systemBlocks
            inferenceConfig = InferenceConfiguration {
                maxTokens = this@BedrockPipe.maxTokens
                if (this@BedrockPipe.temperature > 0) temperature = this@BedrockPipe.temperature.toFloat()
                if (this@BedrockPipe.topP < 1.0) topP = this@BedrockPipe.topP.toFloat()
                if (this@BedrockPipe.stopSequences.isNotEmpty()) stopSequences = this@BedrockPipe.stopSequences
            }
        }
    }

    private fun buildDeepSeekConverseRequestObject(modelId: String, prompt: String): ConverseRequest {
        return buildDeepSeekConverseRequestObject(modelId, listOf(ContentBlock.Text(prompt)))
    }

    /**
     * Converts a standard ConverseRequest to a ConverseStreamRequest for streaming calls.
     * 
     * Extension function that copies all fields from a ConverseRequest to create
     * a ConverseStreamRequest for use with streaming APIs. Handles guardrail
     * configuration conversion and preserves all request parameters.
     * 
     * @return ConverseStreamRequest with identical configuration
     */
    private fun ConverseRequest.toStreamRequest(): ConverseStreamRequest {
        val original = this
        return ConverseStreamRequest {
            modelId = original.modelId
            messages = original.messages
            inferenceConfig = original.inferenceConfig
            system = original.system
            additionalModelRequestFields = original.additionalModelRequestFields
            additionalModelResponseFieldPaths = original.additionalModelResponseFieldPaths
            performanceConfig = original.performanceConfig
            promptVariables = original.promptVariables
            requestMetadata = original.requestMetadata
            toolConfig = original.toolConfig
            original.guardrailConfig?.let { config ->
                guardrailConfig = GuardrailStreamConfiguration {
                    guardrailIdentifier = config.guardrailIdentifier
                    guardrailVersion = config.guardrailVersion
                    trace = config.trace
                }
            }
        }
    }
    

    
    /**
     * Generates text using the Converse API for GPT-OSS models and returns both text and response object.
     */
    protected suspend fun generateGptOssWithConverseApiAndResponse(client: BedrockRuntimeClient, modelId: String, prompt: String): Pair<String, aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse?>
    {
        val (text, response) = generateGptOssWithConverseApiInternal(client, modelId, prompt)
        return Pair(text, response)
    }
    
    /**
     * Legacy method for backward compatibility.
     */
    protected suspend fun generateGptOssWithConverseApi(client: BedrockRuntimeClient, modelId: String, prompt: String): String
    {
        return generateGptOssWithConverseApiInternal(client, modelId, prompt).first
    }

    /**
     * Internal method that generates text using the Converse API for GPT-OSS models.
     * 
     * Handles GPT-OSS specific Converse API calls and response parsing including
     * reasoning content extraction. Returns both the generated text and the raw
     * response object for further processing.
     * 
     * @param client BedrockRuntimeClient instance
     * @param modelId GPT-OSS model identifier
     * @param prompt User prompt text
     * @return Pair of (generated text, raw ConverseResponse) - response may be null on error
     */
    private suspend fun generateGptOssWithConverseApiInternal(client: BedrockRuntimeClient, modelId: String, prompt: String): Pair<String, aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse?>
    {
        val converseRequest = buildGptOssConverseRequest(modelId, prompt)

        val response = client.converse(converseRequest)
        val text = response.output?.asMessage()?.content?.mapNotNull { contentBlock ->
            when (contentBlock)
            {
                is ContentBlock.Text -> contentBlock.value
                is ContentBlock.ReasoningContent -> {
                    // Extract the final message from reasoning content
                    val reasoningBlock = contentBlock.value
                    try {
                        val clazz = reasoningBlock.javaClass
                        val fields = clazz.declaredFields

                        for (field in fields) {
                            field.isAccessible = true
                            val value = field.get(reasoningBlock)

                            if (value is String && value.length > 10 && !value.contains("Sensitive Data Redacted")) {
                                return@mapNotNull value
                            }
                        }
                        null
                    } catch (e: Exception) {
                        null
                    }
                }
                else -> null
            }
        }?.joinToString("\n") ?: ""
        return Pair(text, response)
    }
    
    /**
     * Generates text using the Converse API for DeepSeek models.
     * 
     * Handles DeepSeek-specific Converse API calls with advanced content block parsing
     * including reasoning content extraction using reflection. Falls back to empty
     * string on parsing failures to trigger Invoke API fallback.
     * 
     * @param client BedrockRuntimeClient instance
     * @param modelId DeepSeek model identifier
     * @param prompt User prompt text
     * @return Generated text or empty string on failure
     */
    protected suspend fun generateTextWithConverseApi(client: BedrockRuntimeClient, modelId: String, prompt: String): String
    {
        return try {
            val converseRequest = buildDeepSeekConverseRequestObject(modelId, prompt)
            
            val response = client.converse(converseRequest)
            val outputMessage = response.output?.asMessage()
            val content = outputMessage?.content
            
            // Extract stop reason from Converse API response
            val converseStopReason = response.stopReason?.value ?: ""
            val isConverseOverflow = isMaxTokenStopReason(converseStopReason)
            
            // Extract text content, including from SdkUnknown blocks
            val extractedText = content?.mapNotNull { contentBlock ->
                when (contentBlock) {
                    is ContentBlock.Text -> contentBlock.value
                    is ContentBlock.ReasoningContent -> {
                        // Try to extract text from reasoning content using reflection
                        try {
                            val reasoningBlock = contentBlock.value
                            val clazz = reasoningBlock.javaClass
                            val fields = clazz.declaredFields
                            
                            // Look for fields that might contain the actual content
                            for (field in fields) {
                                field.isAccessible = true
                                val value = field.get(reasoningBlock)
                                
                                // If the field value is a string and looks like content, use it
                                if (value is String && value.length > 100 && !value.contains("Sensitive Data Redacted")) {
                                    return@mapNotNull value
                                }
                                
                                // If the field value is a ReasoningTextBlock, dive deeper
                                if (value != null && value.javaClass.simpleName.contains("ReasoningTextBlock")) {
                                    // Access fields of the ReasoningTextBlock
                                    val textBlockClass = value.javaClass
                                    val textBlockFields = textBlockClass.declaredFields
                                    
                                    for (textField in textBlockFields) {
                                        textField.isAccessible = true
                                        try {
                                            val textValue = textField.get(value)
                                            
                                            // If this field contains actual text content
                                            if (textValue is String && textValue.length > 100 && !textValue.contains("Sensitive Data Redacted")) {
                                                return@mapNotNull textValue
                                            }
                                        } catch (e: Exception) {
                                            // Continue to next field
                                        }
                                    }
                                }
                                
                                // If the field value is a collection or object, try to extract text from it
                                if (value != null && value !is String) {
                                    val valueString = value.toString()
                                    if (valueString.length > 100 && !valueString.contains("Sensitive Data Redacted")) {
                                        return@mapNotNull valueString
                                    }
                                }
                            }
                            
                            "" // Return empty to trigger fallback
                        } catch (e: Exception) {
                            "" // Return empty to trigger fallback
                        }
                    }
                    is ContentBlock.SdkUnknown -> {
                        // Try to extract text from unknown content block using reflection
                        try {
                            val clazz = contentBlock.javaClass
                            val fields = clazz.declaredFields
                            
                            // Look for fields that might contain the actual content
                            for (field in fields) {
                                field.isAccessible = true
                                val value = field.get(contentBlock)
                                
                                // If the field value is a string and looks like content, use it
                                if (value is String && value.length > 10) {
                                    return@mapNotNull value
                                }
                                
                                // If the field value is a map or object, try to extract text from it
                                if (value != null) {
                                    val valueString = value.toString()
                                    if (valueString.contains("text") && valueString.length > 50) {
                                        // Try to parse as JSON-like structure
                                        val textPattern = """"text"\s*:\s*"([^"]+)"""
                                        val textMatch = Regex(textPattern).find(valueString)
                                        if (textMatch != null) {
                                            return@mapNotNull textMatch.groupValues[1]
                                        }
                                    }
                                }
                            }
                            
                            "" // Return empty string to trigger fallback to Invoke API
                        } catch (e: Exception) {
                            null
                        }
                    }
                    else -> null
                }
            }?.joinToString("\n") ?: ""
            
            // Handle max token overflow for Converse API using same logic as Invoke API
            if (isConverseOverflow)
            {
                if (allowMaxTokenOverflow && extractedText.isNotEmpty())
                {
                    // Allow overflow with content - return the partial response
                    return extractedText
                }

                else if (!allowMaxTokenOverflow)
                {
                    // Treat as error - return empty to trigger fallback to Invoke API
                    return ""
                }

                else
                {
                    // No content despite overflow being allowed - still treat as error
                    return ""
                }
            }
            
            extractedText
            
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Builds request JSON for DeepSeek R1 models using Converse API.
     * 
     * Uses DeepSeek's Converse API format with system and messages arrays.
     * Maps TPipe parameters to DeepSeek's inferenceConfig structure.
     * 
     * @param prompt The formatted prompt text
     * @return JSON request string for DeepSeek Converse API
     */
    private fun buildDeepSeekConverseRequest(prompt: String): String
    {
        val messages = mutableListOf<JsonObject>()
        
        messages.add(buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("text", prompt)
                })
            }
        })
        
        return buildJsonObject {
            if (systemPrompt.isNotEmpty())
            {
                putJsonArray("system") {
                    add(buildJsonObject {
                        put("text", systemPrompt)
                    })
                }
            }
            put("messages", JsonArray(messages))
            putJsonObject("inferenceConfig") {
                put("maxTokens", maxTokens)
                if (temperature > 0) put("temperature", temperature)
                if (topP < 1.0) put("topP", topP)
                if (stopSequences.isNotEmpty()) put("stopSequences", JsonArray(stopSequences.map { JsonPrimitive(it) }))
            }
        }.toString()
    }
    
    /**
     * Builds generic request JSON for unknown or unsupported models.
     * 
     * Uses a common format that works with most text generation models.
     * Fallback option when model-specific builders are not available.
     * 
     * @param prompt The formatted prompt text
     * @return JSON request string using generic format
     */
    fun buildGenericRequest(prompt: String): String
    {
        return buildJsonObject {
            put("prompt", prompt)
            put("max_tokens", maxTokens)
            if (temperature > 0) put("temperature", temperature)
            if (topP < 1.0) put("top_p", topP)
            if (topK > 0) put("top_k", topK)
            if (stopSequences.isNotEmpty()) put("stop_sequences", JsonArray(stopSequences.map { JsonPrimitive(it) }))
        }.toString()
    }
    
    /**
     * Extracts reasoning content from Converse API response.
     * 
     * Handles ReasoningContent blocks from models that produce reasoning output.
     * Uses the proper AWS SDK structure to access ReasoningTextBlock.text property.
     */
    protected fun extractReasoningFromConverseResponse(response: aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse): String
    {
        return try {
            response.output?.asMessage()?.content?.mapNotNull { contentBlock ->
                when (contentBlock) {
                    is ContentBlock.ReasoningContent -> {
                        val reasoningBlock = contentBlock.value
                        
                        // Use the proper AWS SDK structure to extract reasoning
                        when (reasoningBlock) {
                            is aws.sdk.kotlin.services.bedrockruntime.model.ReasoningContentBlock.ReasoningText -> {
                                // Access the ReasoningTextBlock and get its text property
                                val reasoningTextBlock = reasoningBlock.value
                                reasoningTextBlock.text
                            }
                            is aws.sdk.kotlin.services.bedrockruntime.model.ReasoningContentBlock.RedactedContent -> {
                                // Content was redacted by AWS for safety
                                "[Reasoning content redacted by AWS for safety]"
                            }
                            is aws.sdk.kotlin.services.bedrockruntime.model.ReasoningContentBlock.SdkUnknown -> {
                                // Unknown reasoning content type
                                "[Unknown reasoning content type]"
                            }
                        }
                    }
                    else -> null
                }
            }?.joinToString("\n") ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Builds Converse API request for Claude models.
     * Maps TPipe parameters to Claude's Converse API structure.
     * 
     * @param prompt The formatted prompt text
     * @return ConverseRequest for Claude models
     */
    fun buildClaudeConverseRequest(contentBlocks: List<ContentBlock>): ConverseRequest
    {
        val messages = mutableListOf<Message>()
        
        messages.add(Message {
            role = ConversationRole.User
            content = contentBlocks
        })
        
        val systemBlocks = if (systemPrompt.isNotEmpty()) {
            listOf(SystemContentBlock.Text(systemPrompt))
        } else emptyList()
        
        return ConverseRequest {
            this.modelId = model
            this.messages = messages
            if (systemBlocks.isNotEmpty()) this.system = systemBlocks
            
            inferenceConfig = InferenceConfiguration {
                maxTokens = this@BedrockPipe.maxTokens
                if (this@BedrockPipe.temperature > 0) temperature = this@BedrockPipe.temperature.toFloat()
                if (this@BedrockPipe.topP < 1.0) topP = this@BedrockPipe.topP.toFloat()
                if (this@BedrockPipe.stopSequences.isNotEmpty()) stopSequences = this@BedrockPipe.stopSequences
            }
        }
    }

    private fun buildClaudeConverseRequest(prompt: String): ConverseRequest {
        return buildClaudeConverseRequest(listOf(ContentBlock.Text(prompt)))
    }

    /**
     * Builds Converse API request for Nova models.
     * Maps TPipe parameters to Nova's Converse API structure.
     * 
     * @param prompt The formatted prompt text
     * @return ConverseRequest for Nova models
     */
    fun buildNovaConverseRequest(contentBlocks: List<ContentBlock>): ConverseRequest
    {
        val messages = mutableListOf<Message>()
        
        messages.add(Message {
            role = ConversationRole.User
            content = contentBlocks
        })
        
        val systemBlocks = if (systemPrompt.isNotEmpty()) {
            listOf(SystemContentBlock.Text(systemPrompt))
        } else emptyList()
        
        return ConverseRequest {
            this.modelId = model
            this.messages = messages
            if (systemBlocks.isNotEmpty()) this.system = systemBlocks
            
            inferenceConfig = InferenceConfiguration {
                maxTokens = this@BedrockPipe.maxTokens
                if (this@BedrockPipe.temperature > 0) temperature = this@BedrockPipe.temperature.toFloat()
                if (this@BedrockPipe.topP < 1.0) topP = this@BedrockPipe.topP.toFloat()
                if (this@BedrockPipe.stopSequences.isNotEmpty()) stopSequences = this@BedrockPipe.stopSequences
            }
            
            // Nova-specific additional model fields
            if (topK > 0) {
                try {
                    val documentMap = mutableMapOf<String, Any>()
                    documentMap["top_k"] = this@BedrockPipe.topK
                    
                    val documentClass = Document::class.java
                    val document = documentClass.getDeclaredConstructor().newInstance()
                    val mapField = documentClass.getDeclaredField("value")
                    mapField.isAccessible = true
                    mapField.set(document, documentMap)
                    
                    additionalModelRequestFields = document
                } catch (e: Exception) {
                    // Fallback: skip additional fields if reflection fails
                }
            }
        }
    }

    private fun buildNovaConverseRequest(prompt: String): ConverseRequest {
        return buildNovaConverseRequest(listOf(ContentBlock.Text(prompt)))
    }

    /**
     * Builds Converse API request for Titan models.
     * Maps TPipe parameters to Titan's Converse API structure.
     * 
     * @param prompt The formatted prompt text
     * @return ConverseRequest for Titan models
     */
    fun buildTitanConverseRequest(contentBlocks: List<ContentBlock>): ConverseRequest
    {
        val messages = mutableListOf<Message>()
        
        messages.add(Message {
            role = ConversationRole.User
            content = contentBlocks
        })
        
        val systemBlocks = if (systemPrompt.isNotEmpty()) {
            listOf(SystemContentBlock.Text(systemPrompt))
        } else emptyList()
        
        return ConverseRequest {
            this.modelId = model
            this.messages = messages
            if (systemBlocks.isNotEmpty()) this.system = systemBlocks
            
            inferenceConfig = InferenceConfiguration {
                maxTokens = this@BedrockPipe.maxTokens
                if (this@BedrockPipe.temperature > 0) temperature = this@BedrockPipe.temperature.toFloat()
                if (this@BedrockPipe.topP < 1.0) topP = this@BedrockPipe.topP.toFloat()
                if (this@BedrockPipe.stopSequences.isNotEmpty()) stopSequences = this@BedrockPipe.stopSequences
            }
            
            // Titan-specific additional model fields
            if (topK > 0) {
                try {
                    val documentMap = mutableMapOf<String, Any>()
                    documentMap["topK"] = this@BedrockPipe.topK
                    
                    val documentClass = Document::class.java
                    val document = documentClass.getDeclaredConstructor().newInstance()
                    val mapField = documentClass.getDeclaredField("value")
                    mapField.isAccessible = true
                    mapField.set(document, documentMap)
                    
                    additionalModelRequestFields = document
                } catch (e: Exception) {
                    // Fallback: skip additional fields if reflection fails
                }
            }
        }
    }

    private fun buildTitanConverseRequest(prompt: String): ConverseRequest {
        return buildTitanConverseRequest(listOf(ContentBlock.Text(prompt)))
    }

    /**
     * Builds Converse API request for AI21 Jurassic models.
     * 
     * @param prompt The formatted prompt text
     * @return ConverseRequest for AI21 models
     */
    fun buildAI21ConverseRequest(contentBlocks: List<ContentBlock>): ConverseRequest
    {
        val messages = mutableListOf<Message>()
        
        messages.add(Message {
            role = ConversationRole.User
            content = contentBlocks
        })
        
        return ConverseRequest {
            this.modelId = model
            this.messages = messages
            
            inferenceConfig = InferenceConfiguration {
                maxTokens = this@BedrockPipe.maxTokens
                if (this@BedrockPipe.temperature > 0) temperature = this@BedrockPipe.temperature.toFloat()
                if (this@BedrockPipe.topP < 1.0) topP = this@BedrockPipe.topP.toFloat()
                if (this@BedrockPipe.stopSequences.isNotEmpty()) stopSequences = this@BedrockPipe.stopSequences
            }
            
            // AI21-specific additional model fields
            try {
                val documentMap = mutableMapOf<String, Any>()
                if (this@BedrockPipe.topK > 0) documentMap["topK"] = this@BedrockPipe.topK
                documentMap["countPenalty"] = mapOf("scale" to 0.0)
                documentMap["presencePenalty"] = mapOf("scale" to 0.0)
                documentMap["frequencyPenalty"] = mapOf("scale" to 0.0)
                
                val documentClass = Document::class.java
                val document = documentClass.getDeclaredConstructor().newInstance()
                val mapField = documentClass.getDeclaredField("value")
                mapField.isAccessible = true
                mapField.set(document, documentMap)
                
                additionalModelRequestFields = document
            } catch (e: Exception) {
                // Fallback: skip additional fields if reflection fails
            }
        }
    }

    private fun buildAI21ConverseRequest(prompt: String): ConverseRequest {
        return buildAI21ConverseRequest(listOf(ContentBlock.Text(prompt)))
    }

    /**
     * Builds Converse API request for Cohere Command models.
     * 
     * @param prompt The formatted prompt text
     * @return ConverseRequest for Cohere models
     */
    fun buildCohereConverseRequest(contentBlocks: List<ContentBlock>): ConverseRequest
    {
        val messages = mutableListOf<Message>()
        
        messages.add(Message {
            role = ConversationRole.User
            content = contentBlocks
        })
        
        return ConverseRequest {
            this.modelId = model
            this.messages = messages
            
            inferenceConfig = InferenceConfiguration {
                maxTokens = this@BedrockPipe.maxTokens
                if (this@BedrockPipe.temperature > 0) temperature = this@BedrockPipe.temperature.toFloat()
                if (this@BedrockPipe.topP < 1.0) topP = this@BedrockPipe.topP.toFloat()
                if (this@BedrockPipe.stopSequences.isNotEmpty()) stopSequences = this@BedrockPipe.stopSequences
            }
            
            // Cohere-specific additional model fields
            try {
                val documentMap = mutableMapOf<String, Any>()
                if (this@BedrockPipe.topK > 0) documentMap["k"] = this@BedrockPipe.topK
                documentMap["return_likelihoods"] = "NONE"
                documentMap["truncate"] = "END"
                
                val documentClass = Document::class.java
                val document = documentClass.getDeclaredConstructor().newInstance()
                val mapField = documentClass.getDeclaredField("value")
                mapField.isAccessible = true
                mapField.set(document, documentMap)
                
                additionalModelRequestFields = document
            } catch (e: Exception) {
                // Fallback: skip additional fields if reflection fails
            }
        }
    }

    private fun buildCohereConverseRequest(prompt: String): ConverseRequest {
        return buildCohereConverseRequest(listOf(ContentBlock.Text(prompt)))
    }

    /**
     * Builds Converse API request for Meta Llama models.
     * 
     * @param prompt The formatted prompt text
     * @return ConverseRequest for Llama models
     */
    fun buildLlamaConverseRequest(contentBlocks: List<ContentBlock>): ConverseRequest
    {
        val messages = mutableListOf<Message>()
        
        messages.add(Message {
            role = ConversationRole.User
            content = contentBlocks
        })
        
        val systemBlocks = if (systemPrompt.isNotEmpty()) {
            listOf(SystemContentBlock.Text(systemPrompt))
        } else emptyList()
        
        return ConverseRequest {
            this.modelId = model
            this.messages = messages
            if (systemBlocks.isNotEmpty()) this.system = systemBlocks
            
            inferenceConfig = InferenceConfiguration {
                maxTokens = this@BedrockPipe.maxTokens
                if (this@BedrockPipe.temperature > 0) temperature = this@BedrockPipe.temperature.toFloat()
                if (this@BedrockPipe.topP < 1.0) topP = this@BedrockPipe.topP.toFloat()
                if (this@BedrockPipe.stopSequences.isNotEmpty()) stopSequences = this@BedrockPipe.stopSequences
            }
            
            // Llama-specific additional model fields
            if (topK > 0) {
                try {
                    val documentMap = mutableMapOf<String, Any>()
                    documentMap["top_k"] = this@BedrockPipe.topK
                    
                    val documentClass = Document::class.java
                    val document = documentClass.getDeclaredConstructor().newInstance()
                    val mapField = documentClass.getDeclaredField("value")
                    mapField.isAccessible = true
                    mapField.set(document, documentMap)
                    
                    additionalModelRequestFields = document
                } catch (e: Exception) {
                    // Fallback: skip additional fields if reflection fails
                }
            }
        }
    }

    private fun buildLlamaConverseRequest(prompt: String): ConverseRequest {
        return buildLlamaConverseRequest(listOf(ContentBlock.Text(prompt)))
    }

    /**
     * Builds Converse API request for Mistral models.
     * 
     * @param prompt The formatted prompt text
     * @return ConverseRequest for Mistral models
     */
    fun buildMistralConverseRequest(contentBlocks: List<ContentBlock>): ConverseRequest
    {
        val messages = mutableListOf<Message>()
        
        messages.add(Message {
            role = ConversationRole.User
            content = contentBlocks
        })
        
        val systemBlocks = if (systemPrompt.isNotEmpty()) {
            listOf(SystemContentBlock.Text(systemPrompt))
        } else emptyList()
        
        return ConverseRequest {
            this.modelId = model
            this.messages = messages
            if (systemBlocks.isNotEmpty()) this.system = systemBlocks
            
            inferenceConfig = InferenceConfiguration {
                maxTokens = this@BedrockPipe.maxTokens
                if (this@BedrockPipe.temperature > 0) temperature = this@BedrockPipe.temperature.toFloat()
                if (this@BedrockPipe.topP < 1.0) topP = this@BedrockPipe.topP.toFloat()
                if (this@BedrockPipe.stopSequences.isNotEmpty()) stopSequences = this@BedrockPipe.stopSequences
            }
            
            // Mistral-specific additional model fields
            try {
                val documentMap = mutableMapOf<String, Any>()
                if (this@BedrockPipe.topK > 0) documentMap["top_k"] = this@BedrockPipe.topK
                documentMap["safe_prompt"] = false
                
                val documentClass = Document::class.java
                val document = documentClass.getDeclaredConstructor().newInstance()
                val mapField = documentClass.getDeclaredField("value")
                mapField.isAccessible = true
                mapField.set(document, documentMap)
                
                additionalModelRequestFields = document
            } catch (e: Exception) {
                // Fallback: skip additional fields if reflection fails
            }
        }
    }

    private fun buildMistralConverseRequest(prompt: String): ConverseRequest {
        return buildMistralConverseRequest(listOf(ContentBlock.Text(prompt)))
    }

    /**
     * Builds generic Converse API request for unknown models.
     * Fallback when model-specific builders are not available.
     * 
     * @param prompt The formatted prompt text
     * @return ConverseRequest using generic format
     */
    fun buildGenericConverseRequest(contentBlocks: List<ContentBlock>): ConverseRequest
    {
        val messages = mutableListOf<Message>()
        
        messages.add(Message {
            role = ConversationRole.User
            content = contentBlocks
        })
        
        val systemBlocks = if (systemPrompt.isNotEmpty()) {
            listOf(SystemContentBlock.Text(systemPrompt))
        } else emptyList()
        
        return ConverseRequest {
            this.modelId = model
            this.messages = messages
            if (systemBlocks.isNotEmpty()) this.system = systemBlocks
            
            inferenceConfig = InferenceConfiguration {
                maxTokens = this@BedrockPipe.maxTokens
                if (this@BedrockPipe.temperature > 0) temperature = this@BedrockPipe.temperature.toFloat()
                if (this@BedrockPipe.topP < 1.0) topP = this@BedrockPipe.topP.toFloat()
                if (this@BedrockPipe.stopSequences.isNotEmpty()) stopSequences = this@BedrockPipe.stopSequences
            }
        }
    }

    private fun buildGenericConverseRequest(prompt: String): ConverseRequest {
        return buildGenericConverseRequest(listOf(ContentBlock.Text(prompt)))
    }

    /**
     * Builds Converse API request for Qwen3 models.
     * Maps TPipe parameters to Qwen3's Converse API structure.
     * 
     * @param prompt The formatted prompt text
     * @return ConverseRequest for Qwen3 models
     */
    fun buildQwenConverseRequest(contentBlocks: List<ContentBlock>): ConverseRequest
    {
        val messages = mutableListOf<Message>()
        
        messages.add(Message {
            role = ConversationRole.User
            content = contentBlocks
        })
        

        val systemBlocks = mutableListOf<SystemContentBlock>()
        
        // Add main system prompt
        if (systemPrompt.isNotEmpty()) {
            systemBlocks.add(SystemContentBlock.Text(systemPrompt))
        }

        /** Bind PCP context if the model directly supports tool calls.
         * This will happen regardless of weather prompt injection is enabled. Disable prompt injection for
         * models that don't need it.
         */
        if (!pcpContext.tpipeOptions.isEmpty()) {
            val pcpInstructions = "Available tools: ${com.TTT.Util.serialize(pcpContext, false)}"
            systemBlocks.add(SystemContentBlock.Text(pcpInstructions))
        }
        
        return ConverseRequest {
            this.modelId = model
            this.messages = messages
            if (systemBlocks.isNotEmpty()) this.system = systemBlocks
            
            inferenceConfig = InferenceConfiguration {
                maxTokens = this@BedrockPipe.maxTokens
                if (this@BedrockPipe.temperature > 0) temperature = this@BedrockPipe.temperature.toFloat()
                if (this@BedrockPipe.topP < 1.0) topP = this@BedrockPipe.topP.toFloat()
                // stopSequences handled in additionalModelRequestFields for Qwen models
            }
            
            // Qwen3-specific additional model fields for reasoning and topK
            if (useModelReasoning || topK > 0 || this@BedrockPipe.stopSequences.isNotEmpty()) {
                try {
                    val documentMap = mutableMapOf<String, Any>()
                    if (this@BedrockPipe.topK > 0) documentMap["top_k"] = this@BedrockPipe.topK
                    if (this@BedrockPipe.stopSequences.isNotEmpty()) documentMap["stop"] = this@BedrockPipe.stopSequences
                    if (useModelReasoning) {
                        documentMap["enable_thinking"] = true
                        // TODO: If AWS adds thinking budget parameters, add them here
                    } else {
                        documentMap["enable_thinking"] = false
                    }
                    
                    val documentClass = Document::class.java
                    val document = documentClass.getDeclaredConstructor().newInstance()
                    val mapField = documentClass.getDeclaredField("value")
                    mapField.isAccessible = true
                    mapField.set(document, documentMap)
                    
                    additionalModelRequestFields = document
                } catch (e: Exception) {
                    // Fallback: skip additional fields if reflection fails
                }
            }
        }
    }

    private fun buildQwenConverseRequest(prompt: String): ConverseRequest {
        return buildQwenConverseRequest(listOf(ContentBlock.Text(prompt)))
    }

    /**
     * Generates text using Converse API for all supported models.
     * Uses model-specific Converse request builders.
     * 
     * @param client BedrockRuntimeClient instance
     * @param modelId Model identifier
     * @param prompt The formatted prompt text
     * @return Generated text response
     */
    private suspend fun generateWithConverseApi(client: BedrockRuntimeClient, modelId: String, prompt: String): String
    {
        return try {
            val converseRequest = when
            {
                modelId.contains("qwen") -> buildQwenConverseRequest(prompt)
                modelId.contains("anthropic.claude") -> buildClaudeConverseRequest(prompt)
                modelId.contains("amazon.nova") -> buildNovaConverseRequest(prompt)
                modelId.contains("amazon.titan") -> buildTitanConverseRequest(prompt)
                modelId.contains("ai21.j2") -> buildAI21ConverseRequest(prompt)
                modelId.contains("cohere.command") -> buildCohereConverseRequest(prompt)
                modelId.contains("meta.llama") -> buildLlamaConverseRequest(prompt)
                modelId.contains("mistral") -> buildMistralConverseRequest(prompt)
                modelId.contains("deepseek") -> buildDeepSeekConverseRequestObject(modelId, prompt)
                else -> buildGenericConverseRequest(prompt) // Fallback
            }
            
            // Check for streaming first
            if (streamingEnabled) {
                val streamingResult = executeConverseStream(client, modelId, converseRequest, "ConverseStream")
                if (streamingResult != null) {
                    return streamingResult
                }
            }
            
            val response = client.converse(converseRequest)
            val outputMessage = response.output?.asMessage()
            val content = outputMessage?.content
            
            // Extract stop reason from Converse API response
            val converseStopReason = response.stopReason?.value ?: ""
            val isConverseOverflow = isMaxTokenStopReason(converseStopReason)
            
            // Extract text content from response
            val extractedText = content?.mapNotNull { contentBlock ->
                when (contentBlock) {
                    is ContentBlock.Text -> contentBlock.value
                    else -> null
                }
            }?.joinToString("\\n") ?: ""
            
            // Handle max token overflow for Converse API
            if (isConverseOverflow)
            {
                if (allowMaxTokenOverflow && extractedText.isNotEmpty())
                {
                    // Allow overflow with content
                    return extractedText
                }

                else if (!allowMaxTokenOverflow)
                {
                    // Treat as error - return empty to trigger fallback
                    return ""
                }

                else
                {
                    // No content despite overflow being allowed
                    return ""
                }
            }
            
            extractedText
            
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Builds ConverseRequest for streaming based on model type.
     * 
     * Factory method that creates appropriate ConverseRequest objects for different
     * model families when using streaming APIs. Routes to model-specific builders.
     * 
     * @param modelId Model identifier to determine request format
     * @param prompt User prompt text
     * @return ConverseRequest for the specified model or null if unsupported
     */
    private fun buildConverseRequestForStreaming(modelId: String, prompt: String): ConverseRequest? {
        return when {
            modelId.contains("openai.gpt-oss") -> buildGptOssConverseRequest(modelId, prompt)
            modelId.contains("deepseek" ) -> buildDeepSeekConverseRequestObject(modelId, prompt)
            modelId.contains("qwen") -> buildQwenConverseRequest(prompt)
            modelId.contains("anthropic.claude") -> buildClaudeConverseRequest(prompt)
            modelId.contains("amazon.nova") -> buildNovaConverseRequest(prompt)
            modelId.contains("amazon.titan") -> buildTitanConverseRequest(prompt)
            modelId.contains("ai21.j2") -> buildAI21ConverseRequest(prompt)
            modelId.contains("cohere.command") -> buildCohereConverseRequest(prompt)
            modelId.contains("meta.llama") -> buildLlamaConverseRequest(prompt)
            modelId.contains("mistral") -> buildMistralConverseRequest(prompt)
            else -> buildGenericConverseRequest(prompt)
        }
    }

    /**
     * Handles Converse API generation with model-specific routing.
     * 
     * Routes Converse API calls to appropriate handlers based on model type.
     * Supports streaming and non-streaming modes with automatic fallback
     * for models that have compatibility issues.
     * 
     * @param client BedrockRuntimeClient instance
     * @param modelId Model identifier for routing
     * @param prompt User prompt text
     * @param originalPrompt Original prompt for fallback scenarios
     * @return Generated text response
     */
    // DEPRECATED: Legacy wrapper function - no longer used
    /**
     * Handles GPT-OSS Converse API calls with streaming support.
     * 
     * Manages GPT-OSS specific Converse API interactions including streaming
     * via ConverseStream when enabled. Includes comprehensive tracing for
     * debugging and monitoring.
     * 
     * @param client BedrockRuntimeClient instance
     * @param modelId GPT-OSS model identifier
     * @param prompt User prompt text
     * @return Generated text response
     */
    private suspend fun handleGptOssConverse(
        client: BedrockRuntimeClient,
        modelId: String,
        prompt: String
    ): String {
        if (streamingEnabled) {
            val streamingRequest = buildGptOssConverseRequest(modelId, prompt)
            val streamingResult = executeConverseStream(client, modelId, streamingRequest, "GPT-OSS ConverseStream")
            if (streamingResult != null) {
                return streamingResult
            }
        }

        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf("apiType" to "GPT-OSS ConverseAPI", "modelId" to modelId, "streaming" to false))
        val result = generateGptOssWithConverseApi(client, modelId, prompt)
        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              metadata = mapOf("responseLength" to result.length, "modelId" to modelId, "streaming" to false))
        return result
    }

    /**
     * Handles DeepSeek Converse API calls with automatic fallback.
     * 
     * Manages DeepSeek specific Converse API interactions with streaming support
     * and automatic fallback to Invoke API when Converse fails or returns
     * SdkUnknown content blocks.
     * 
     * @param client BedrockRuntimeClient instance
     * @param modelId DeepSeek model identifier
     * @param prompt User prompt text
     * @param originalPrompt Original prompt for Invoke API fallback
     * @return Generated text response
     */
    private suspend fun handleDeepSeekConverse(
        client: BedrockRuntimeClient,
        modelId: String,
        prompt: String,
        originalPrompt: String
    ): String {
        if (streamingEnabled) {
            val streamingRequest = buildDeepSeekConverseRequestObject(modelId, prompt)
            val streamingResult = executeConverseStream(client, modelId, streamingRequest, "DeepSeek ConverseStream")
            if (!streamingResult.isNullOrEmpty() && !streamingResult.contains("SdkUnknown")) {
                return streamingResult
            }

            if (streamingResult != null && streamingResult.contains("SdkUnknown")) {
                trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                      metadata = mapOf(
                          "apiType" to "DeepSeek ConverseStream",
                          "modelId" to modelId,
                          "streaming" to true,
                          "fallbackReason" to "Converse stream returned SdkUnknown"
                      ))
            }
        }

        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf("apiType" to "DeepSeek ConverseAPI", "modelId" to modelId, "streaming" to false))
        val converseResult = generateWithConverseApi(client, modelId, prompt)

        if (converseResult.isEmpty() || converseResult.contains("SdkUnknown")) {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  metadata = mapOf(
                      "apiType" to "DeepSeek ConverseAPI",
                      "modelId" to modelId,
                      "streaming" to false,
                      "fallbackReason" to "Converse API failed"
                  ))

            val originalUseConverseApi = useConverseApi
            useConverseApi = false
            val invokeResult = generateText(originalPrompt)
            useConverseApi = originalUseConverseApi
            return invokeResult
        }

        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              metadata = mapOf("responseLength" to converseResult.length, "modelId" to modelId, "streaming" to false))
        return converseResult
    }

    /**
     * Handles generic Converse API calls for all other models.
     * 
     * Manages Converse API interactions for models other than GPT-OSS and DeepSeek.
     * Supports streaming via ConverseStream when enabled and includes tracing.
     * 
     * @param client BedrockRuntimeClient instance
     * @param modelId Model identifier
     * @param prompt User prompt text
     * @return Generated text response
     */
    private suspend fun handleGenericConverse(
        client: BedrockRuntimeClient,
        modelId: String,
        prompt: String
    ): String {
        if (streamingEnabled) {
            val streamingRequest = buildConverseRequestForStreaming(modelId, prompt)
            if (streamingRequest != null) {
                val streamingResult = executeConverseStream(client, modelId, streamingRequest, "ConverseStream")
                if (streamingResult != null) {
                    return streamingResult
                }
            }
        }

        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf("apiType" to "ConverseAPI", "modelId" to modelId, "streaming" to false))
        val result = generateWithConverseApi(client, modelId, prompt)
        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              metadata = mapOf("responseLength" to result.length, "modelId" to modelId, "streaming" to false))
        return result
    }

    protected suspend fun executeConverseStream(
        client: BedrockRuntimeClient,
        modelId: String,
        request: ConverseRequest,
        apiLabel: String
    ): String? {
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf("apiType" to apiLabel, "modelId" to modelId, "streaming" to true))

        // Initialize builders for collecting streaming content
        val textBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        var stopReason = ""
        var overflowDetected = false
        val usageMetadata = mutableMapOf<String, Any>()

        return try {
            // Execute the streaming Converse API call
            val finalText = client.converseStream(request.toStreamRequest()) { response ->
                // Process the streaming response events
                response.stream?.collect { event ->
                    // Handle content block deltas (text and reasoning chunks)
                    event.asContentBlockDeltaOrNull()?.let { deltaEvent ->
                        // Extract text deltas and emit to callback
                        deltaEvent.delta?.asTextOrNull()?.let { deltaText ->
                            textBuilder.append(deltaText)
                            emitStreamingChunk(deltaText)
                        }
                        // Extract reasoning deltas for models that support it
                        deltaEvent.delta?.asReasoningContentOrNull()?.asTextOrNull()?.let { reasoningDelta ->
                            reasoningBuilder.append(reasoningDelta)
                        }
                    }

                    // Handle message stop events to capture stop reason
                    event.asMessageStopOrNull()?.let { stopEvent ->
                        stopEvent.stopReason?.value?.let {
                            stopReason = it
                            overflowDetected = isMaxTokenStopReason(it)
                        }
                    }

                    // Handle metadata events for token usage tracking
                    event.asMetadataOrNull()?.usage?.let { usage ->
                        usageMetadata["inputTokens"] = usage.inputTokens
                        usageMetadata["outputTokens"] = usage.outputTokens
                        usageMetadata["totalTokens"] = usage.totalTokens
                        usage.cacheReadInputTokens?.let { usageMetadata["cacheReadInputTokens"] = it }
                        usage.cacheWriteInputTokens?.let { usageMetadata["cacheWriteInputTokens"] = it }
                    }
                }

                // Return the accumulated text
                textBuilder.toString()
            }

            // Build comprehensive metadata for tracing
            val metadata = mutableMapOf<String, Any>(
                "responseLength" to finalText.length,
                "modelId" to modelId,
                "apiType" to apiLabel,
                "streaming" to true,
                "success" to true
            )

            // Add stop reason if available
            if (stopReason.isNotEmpty()) {
                metadata["stopReason"] = stopReason
            }

            // Add reasoning content if captured
            if (reasoningBuilder.isNotEmpty()) {
                metadata["reasoningContent"] = reasoningBuilder.toString()
                metadata["modelSupportsReasoning"] = true
                metadata["reasoningEnabled"] = useModelReasoning
            }

            // Add token usage metrics if available
            if (usageMetadata.isNotEmpty()) {
                metadata.putAll(usageMetadata)
            }

            // Handle max token overflow scenarios
            if (overflowDetected) {
                metadata["maxTokenOverflow"] = true
                return when {
                    allowMaxTokenOverflow && finalText.isNotEmpty() -> {
                        metadata["overflowAllowed"] = true
                        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, metadata = metadata)
                        finalText
                    }
                    !allowMaxTokenOverflow -> {
                        metadata["overflowAllowed"] = false
                        trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION, metadata = metadata)
                        null
                    }
                    else -> {
                        metadata["overflowAllowed"] = true
                        metadata["noContent"] = true
                        trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION, metadata = metadata)
                        null
                    }
                }
            }

            // Trace successful completion
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, metadata = metadata)
            finalText

        } catch (e: Exception) {
            // Trace streaming failure
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  error = e,
                  metadata = mapOf("apiType" to apiLabel, "modelId" to modelId, "streaming" to true))
            null
        }
    }

    private suspend fun executeInvokeStream(
        client: BedrockRuntimeClient,
        modelId: String,
        requestJson: String
    ): String? {
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "apiType" to "InvokeModelWithResponseStream",
                  "modelId" to modelId,
                  "streaming" to true,
                  "requestSize" to requestJson.length
              ))

        // Initialize builders for collecting streaming content
        val textBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()

        // Create the streaming invoke request
        val request = InvokeModelWithResponseStreamRequest {
            this.modelId = modelId
            body = requestJson.toByteArray()
            contentType = "application/json"
            accept = "application/json"
        }

        return try {
            // Execute the streaming Invoke API call
            val finalText = client.invokeModelWithResponseStream(request) { response ->
                // Process the streaming response body
                response.body?.collect { event ->
                    // Handle chunk events containing JSON data
                    event.asChunkOrNull()?.let { chunk ->
                        val bytes = chunk.bytes
                        if (bytes != null && bytes.isNotEmpty()) {
                            // Decode chunk bytes to string and extract deltas
                            val chunkString = bytes.decodeToString()
                            val (textDelta, reasoningDelta) = extractInvokeStreamDeltas(chunkString, modelId)
                            
                            // Accumulate text deltas and emit to callback
                            if (textDelta.isNotEmpty()) {
                                textBuilder.append(textDelta)
                                emitStreamingChunk(textDelta)
                            }
                            
                            // Accumulate reasoning deltas for tracing
                            if (reasoningDelta.isNotEmpty()) {
                                reasoningBuilder.append(reasoningDelta)
                            }
                        }
                    }
                }
                // Return the accumulated text
                textBuilder.toString()
            }

            // Build metadata for tracing
            val metadata = mutableMapOf<String, Any>(
                "responseLength" to finalText.length,
                "modelId" to modelId,
                "apiType" to "InvokeModelWithResponseStream",
                "streaming" to true,
                "success" to true
            )

            // Add reasoning content if captured
            if (reasoningBuilder.isNotEmpty()) {
                metadata["reasoningContent"] = reasoningBuilder.toString()
                metadata["modelSupportsReasoning"] = true
                metadata["reasoningEnabled"] = useModelReasoning
            }

            // Trace successful streaming completion
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, metadata = metadata)
            finalText

        } catch (e: Exception) {
            // Trace streaming failure
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  error = e,
                  metadata = mapOf(
                      "apiType" to "InvokeModelWithResponseStream",
                      "modelId" to modelId,
                      "streaming" to true
                  ))
            null
        }
    }

    /**
     * Emits a streaming chunk to the registered callback.
     * 
     * Safely invokes the streaming callback with the provided text chunk.
     * Handles exceptions within the callback to prevent stream interruption.
     * Returns immediately if no callback is registered.
     * 
     * @param chunk Text chunk to emit to the callback
     */
    private suspend fun emitStreamingChunk(chunk: String) {
        val callback = streamingCallback ?: return
        try {
            callback(chunk)
        } catch (e: Exception) {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  error = e,
                  metadata = mapOf("streamingCallback" to true))
        }
    }

    /**
     * Extracts text and reasoning deltas from streaming JSON chunks.
     * 
     * Parses individual JSON chunks from InvokeModelWithResponseStream to extract
     * incremental text and reasoning content. Handles various JSON formats from
     * different models and gracefully handles malformed chunks.
     * 
     * @param chunkJson Raw JSON chunk from streaming response
     * @param modelId Model identifier (currently unused but available for model-specific parsing)
     * @return Pair of (text delta, reasoning delta) - both can be empty strings
     */
    private fun extractInvokeStreamDeltas(chunkJson: String, modelId: String): Pair<String, String> {
        // Clean up the chunk and handle empty input
        val trimmed = chunkJson.trim()
        if (trimmed.isEmpty()) {
            return "" to ""
        }

        return try {
            // Parse the JSON chunk
            val element = Json.parseToJsonElement(trimmed)
            if (element !is JsonObject) {
                // Handle non-JSON text chunks
                if (!trimmed.startsWith("{")) trimmed to "" else "" to ""
            } else {
                val obj = element

                // Extract text content from various possible field locations
                var text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                if (text.isEmpty()) {
                    text = obj["completion"]?.jsonPrimitive?.contentOrNull ?: ""
                }
                if (text.isEmpty()) {
                    text = obj["outputText"]?.jsonPrimitive?.contentOrNull ?: ""
                }
                
                // Check delta object for text content
                obj["delta"]?.jsonObject?.let { deltaObj ->
                    if (text.isEmpty()) {
                        text = deltaObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                    if (text.isEmpty()) {
                        text = deltaObj["completion"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                }
                
                // Check content array for text blocks
                if (text.isEmpty()) {
                    obj["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
                        text = it
                    }
                }
                
                // Fallback to raw text if not JSON structured
                if (text.isEmpty() && !trimmed.startsWith("{")) {
                    text = trimmed
                }

                // Extract reasoning content from various possible locations
                var reasoning = obj["reasoning"]?.jsonPrimitive?.contentOrNull ?: ""
                obj["delta"]?.jsonObject?.let { deltaObj ->
                    if (reasoning.isEmpty()) {
                        reasoning = deltaObj["reasoning"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                    if (reasoning.isEmpty()) {
                        reasoning = deltaObj["reasoningText"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                    // Check for reasoning content in nested structure
                    deltaObj["reasoningContent"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
                        reasoning = it
                    }
                }

                // Check for guardrail traces that might contain reasoning
                obj["amazon-bedrock-trace"]?.jsonObject?.get("guardrail")?.let {
                    if (reasoning.isEmpty()) {
                        reasoning = it.toString()
                    }
                }

                text to reasoning
            }
        } catch (_: Exception) {
            // Handle JSON parsing errors gracefully
            if (!trimmed.startsWith("{")) trimmed to "" else "" to ""
        }
    }

    /**
     * Determines if the stop reason indicates the model stopped due to max token limit.
     * 
     * This function checks various stop reason strings that different AWS Bedrock models
     * use to indicate they stopped generating due to reaching the maximum token limit.
     * Different model families use different terminology for this condition.
     * 
     * @param stopReason The stop reason extracted from the model response
     * @return True if the stop reason indicates max token overflow
     * 
     * @since This should be called after extracting stop reasons from model responses
     * @see extractStopReasonFromInvokeResponse
     */
    private fun isMaxTokenStopReason(stopReason: String): Boolean
    {
        // Convert to lowercase for case-insensitive matching across different model responses
        return when (stopReason.lowercase()) 
        {
            "length" -> true           // GPT-OSS, DeepSeek models use this
            "max_tokens" -> true       // Claude models use this specific term
            "max_length" -> true       // Some other models may use this variant
            "token_limit" -> true      // Potential alternative used by some providers
            else -> false              // Unknown stop reason, assume not token overflow
        }
    }
    
    /**
     * Extracts stop reason from Invoke API response.
     * 
     * Parses model-specific JSON response formats to extract the reason why
     * the model stopped generating tokens. Different models use different
     * field names and locations for stop reasons:
     * 
     * - GPT-OSS: choices[0].finish_reason
     * - Claude: stop_reason (top-level)
     * - DeepSeek: choices[0].finish_reason
     * 
     * Used for detecting max token overflow and other completion conditions.
     * 
     * @param responseBody Raw JSON response from Bedrock API
     * @param modelId Model identifier to determine response format
     * @return Stop reason string or empty string if not found/parseable
     * @see isMaxTokenStopReason for overflow detection
     */
    private fun extractStopReasonFromInvokeResponse(responseBody: String, modelId: String): String
    {
        return try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            when {
                modelId.contains("openai.gpt-oss") -> {
                    json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.content ?: ""
                }
                modelId.contains("anthropic.claude") -> {
                    json["stop_reason"]?.jsonPrimitive?.content ?: ""
                }
                modelId.contains("deepseek") -> {
                    json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.content ?: ""
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Extracts token usage from Invoke API response.
     * 
     * Parses model-specific JSON response formats to extract token consumption
     * metrics for cost tracking and optimization. Different models report
     * usage in different formats:
     * 
     * - GPT-OSS: usage.prompt_tokens, usage.completion_tokens, usage.total_tokens
     * - Claude: usage.input_tokens, usage.output_tokens
     * - DeepSeek: usage.prompt_tokens, usage.completion_tokens, usage.total_tokens
     * 
     * Returns standardized field names (inputTokens, outputTokens, totalTokens)
     * for consistent usage tracking across all models.
     * 
     * @param responseBody Raw JSON response from Bedrock API
     * @param modelId Model identifier to determine response format
     * @return Map of token usage metrics or null if not available/parseable
     */
    private fun extractTokenUsageFromInvokeResponse(responseBody: String, modelId: String): Map<String, Any>?
    {
        return try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            val usage = mutableMapOf<String, Any>()
            
            when {
                modelId.contains("openai.gpt-oss") -> {
                    json["usage"]?.jsonObject?.let { usageObj ->
                        usageObj["prompt_tokens"]?.jsonPrimitive?.int?.let { usage["inputTokens"] = it }
                        usageObj["completion_tokens"]?.jsonPrimitive?.int?.let { usage["outputTokens"] = it }
                        usageObj["total_tokens"]?.jsonPrimitive?.int?.let { usage["totalTokens"] = it }
                    }
                }
                modelId.contains("anthropic.claude") -> {
                    json["usage"]?.jsonObject?.let { usageObj ->
                        usageObj["input_tokens"]?.jsonPrimitive?.int?.let { usage["inputTokens"] = it }
                        usageObj["output_tokens"]?.jsonPrimitive?.int?.let { usage["outputTokens"] = it }
                    }
                }
                modelId.contains("deepseek") -> {
                    json["usage"]?.jsonObject?.let { usageObj ->
                        usageObj["prompt_tokens"]?.jsonPrimitive?.int?.let { usage["inputTokens"] = it }
                        usageObj["completion_tokens"]?.jsonPrimitive?.int?.let { usage["outputTokens"] = it }
                        usageObj["total_tokens"]?.jsonPrimitive?.int?.let { usage["totalTokens"] = it }
                    }
                }
            }
            
            if (usage.isNotEmpty()) usage else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Extracts reasoning content from model responses for tracing and debugging.
     * 
     * This method searches for reasoning/thinking content in model responses regardless
     * of the useModelReasoning flag setting. Models like DeepSeek R1 and GPT-OSS can
     * produce reasoning content even when not explicitly requested, and we want to
     * capture this for complete tracing information.
     * 
     * @param responseBody Raw JSON response from Bedrock API
     * @param modelId Model identifier to determine response format
     * @return Extracted reasoning content or empty string if not available
     */
    protected fun extractReasoningContent(responseBody: String, modelId: String): String {
        return try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            when {
                modelId.contains("qwen") -> {
                    // Qwen3 supports thinking mode with reasoning content
                    json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.let { choice ->
                        choice.get("message")?.jsonObject?.get("thinking")?.jsonPrimitive?.content
                            ?: choice.get("reasoning")?.jsonPrimitive?.content
                            ?: choice.get("thinking")?.jsonPrimitive?.content
                    } ?: json["thinking"]?.jsonPrimitive?.content ?: ""
                }
                
                modelId.contains("openai.gpt-oss") -> {
                    // GPT-OSS can return reasoning in multiple locations within the response
                    // Check the most common locations: message.reasoning, choice.reasoning, or top-level
                    json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.let { choice ->
                        choice.get("message")?.jsonObject?.get("reasoning")?.jsonPrimitive?.content
                            ?: choice.get("reasoning")?.jsonPrimitive?.content
                            ?: choice.get("thinking")?.jsonPrimitive?.content
                    } ?: json["reasoning"]?.jsonPrimitive?.content ?: ""
                }
                modelId.contains("anthropic.claude") -> {
                    // Claude models can include thinking blocks in their content array
                    // These are separate from the main response text
                    json["content"]?.jsonArray?.mapNotNull { contentItem ->
                        val contentObj = contentItem.jsonObject
                        when {
                            contentObj.containsKey("type") && 
                            contentObj["type"]?.jsonPrimitive?.content == "thinking" -> 
                                contentObj["thinking"]?.jsonPrimitive?.content
                            else -> null
                        }
                    }?.joinToString("\n") ?: ""
                }
                modelId.contains("deepseek") -> {
                    // DeepSeek has different reasoning formats for Converse vs Invoke API
                    if (useConverseApi) {
                        // Converse API embeds reasoning in content blocks
                        val message = json["output"]?.jsonObject?.get("message")?.jsonObject
                        val content = message?.get("content")?.jsonArray
                        content?.mapNotNull { contentItem ->
                            val contentObj = contentItem.jsonObject
                            when {
                                contentObj.containsKey("reasoningContent") -> 
                                    contentObj["reasoningContent"]?.jsonObject?.get("reasoningText")?.jsonPrimitive?.content
                                contentObj.containsKey("thinking") -> 
                                    contentObj["thinking"]?.jsonPrimitive?.content
                                contentObj.containsKey("reasoning") -> 
                                    contentObj["reasoning"]?.jsonPrimitive?.content
                                else -> null
                            }
                        }?.joinToString("\n") ?: ""
                    } else {
                        // Invoke API puts reasoning in various top-level or choice-level fields
                        json["reasoning"]?.jsonPrimitive?.content 
                            ?: json["thinking"]?.jsonPrimitive?.content 
                            ?: json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("reasoning")?.jsonPrimitive?.content 
                            ?: ""
                    }
                }
                else -> "" // Unknown model, no reasoning extraction
            }
        } catch (e: Exception) {
            "" // Fail silently if JSON parsing fails
        }
    }
    
    /**
     * Extracts the main generated text from model-specific JSON response formats.
     * 
     * Each model family returns responses in different JSON structures with different
     * field names and nesting levels. This method handles the parsing complexity and
     * extracts only the main response text (not reasoning content).
     * 
     * @param responseBody Raw JSON response from Bedrock API
     * @param modelId Model identifier to determine response format
     * @return Extracted text content or empty string on error
     */
    protected fun extractTextFromResponse(responseBody: String, modelId: String): String
    {
        return try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            when {
                modelId.contains("qwen") -> {
                    // Qwen3 uses choices array with message content structure
                    json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
                }
                
                modelId.contains("openai.gpt-oss") -> {
                    // GPT-OSS uses OpenAI-compatible format: choices[0].message.content
                    json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
                }
                modelId.contains("amazon.nova") -> {
                    // Nova uses nested structure: output.message.content[0].text
                    json["output"]?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                }
                modelId.contains("anthropic.claude") -> {
                    // Claude uses content array: content[0].text
                    json["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                }
                modelId.contains("amazon.titan") -> {
                    // Titan uses results array: results[0].outputText
                    json["results"]?.jsonArray?.firstOrNull()?.jsonObject?.get("outputText")?.jsonPrimitive?.content ?: ""
                }
                modelId.contains("ai21.j2") -> {
                    // AI21 Jurassic uses nested structure: completions[0].data.text
                    json["completions"]?.jsonArray?.firstOrNull()?.jsonObject?.get("data")?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                }
                modelId.contains("cohere.command") -> {
                    // Cohere uses generations array: generations[0].text
                    json["generations"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                }
                modelId.contains("meta.llama") -> {
                    // Llama uses simple structure: generation
                    json["generation"]?.jsonPrimitive?.content ?: ""
                }
                modelId.contains("mistral") -> {
                    // Mistral uses outputs array: outputs[0].text
                    json["outputs"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                }
                modelId.contains("deepseek") -> {
                    if (useConverseApi) {
                        // DeepSeek Converse API uses message.content array structure
                        val message = json["output"]?.jsonObject?.get("message")?.jsonObject
                        val content = message?.get("content")?.jsonArray
                        
                        // Extract only text content blocks, skip reasoning content
                        // (reasoning is extracted separately by extractReasoningContent)
                        content?.mapNotNull { contentItem ->
                            val contentObj = contentItem.jsonObject
                            when {
                                contentObj.containsKey("text") -> contentObj["text"]?.jsonPrimitive?.content
                                // Only include reasoning in main text if explicitly requested
                                contentObj.containsKey("reasoningContent") && useModelReasoning -> 
                                    contentObj["reasoningContent"]?.jsonObject?.get("reasoningText")?.jsonPrimitive?.content
                                else -> null
                            }
                        }?.joinToString("\n") ?: ""
                    } else {
                        // DeepSeek Invoke API uses choices array: choices[0].text
                        json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                    }
                }

                else -> {
                    // Generic fallback for unknown models - try common field names
                    json["text"]?.jsonPrimitive?.content ?: json["output"]?.jsonPrimitive?.content ?: json["response"]?.jsonPrimitive?.content ?: ""
                }
            }
        } catch (e: Exception) {
            "" // Fail silently if JSON parsing fails
        }
    }
}
