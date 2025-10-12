# TPipe Tracing System Implementation Plan

## Overview
This document outlines the complete implementation plan for a comprehensive pipe tracing system that will allow developers to visually track the execution path of pipes and identify failure points in the TPipe pipeline system.

## System Architecture

### Core Components
**All classes must be located in the Debug package: `TPipe/src/main/kotlin/Debug/`**

#### 1. TraceEvent (Data Class)
**File**: `TPipe/src/main/kotlin/Debug/TraceEvent.kt`
```kotlin
data class TraceEvent(
    val timestamp: Long,
    val pipeId: String,
    val pipeName: String,
    val eventType: TraceEventType,
    val phase: TracePhase,
    val content: MultimodalContent?,
    val contextSnapshot: ContextWindow?,
    val metadata: Map<String, Any> = emptyMap(),
    val error: Throwable? = null
)
```

#### 2. TraceEventType (Enum)
**File**: `TPipe/src/main/kotlin/Debug/TraceEventType.kt`
```kotlin
enum class TraceEventType {
    PIPE_START,
    PIPE_END,
    PIPE_SUCCESS,
    PIPE_FAILURE,
    CONTEXT_PULL,
    CONTEXT_TRUNCATE,
    VALIDATION_START,
    VALIDATION_SUCCESS,
    VALIDATION_FAILURE,
    TRANSFORMATION_START,
    TRANSFORMATION_SUCCESS,
    TRANSFORMATION_FAILURE,
    API_CALL_START,
    API_CALL_SUCCESS,
    API_CALL_FAILURE,
    BRANCH_PIPE_TRIGGERED,
    PIPELINE_TERMINATION
}
```

#### 3. TracePhase (Enum)
**File**: `TPipe/src/main/kotlin/Debug/TracePhase.kt`
```kotlin
enum class TracePhase {
    INITIALIZATION,
    CONTEXT_PREPARATION,
    PRE_VALIDATION,
    EXECUTION,
    POST_PROCESSING,
    VALIDATION,
    TRANSFORMATION,
    CLEANUP
}
```

#### 4. PipeTracer (Core Tracing Class)
**File**: `TPipe/src/main/kotlin/Debug/PipeTracer.kt`
```kotlin
object PipeTracer {
    private val traces = mutableMapOf<String, MutableList<TraceEvent>>()
    private var isEnabled = false
    private var maxTraceHistory = 1000
    
    fun enable()
    fun disable()
    fun startTrace(pipelineId: String)
    fun addEvent(pipelineId: String, event: TraceEvent)
    fun getTrace(pipelineId: String): List<TraceEvent>
    fun clearTrace(pipelineId: String)
    fun exportTrace(pipelineId: String, format: TraceFormat): String
    fun getFailureAnalysis(pipelineId: String): FailureAnalysis
}
```

#### 5. TraceFormat (Enum)
**File**: `TPipe/src/main/kotlin/Debug/TraceFormat.kt`
```kotlin
enum class TraceFormat {
    JSON,
    HTML,
    MARKDOWN,
    CONSOLE
}
```

#### 6. FailureAnalysis (Data Class)
**File**: `TPipe/src/main/kotlin/Debug/FailureAnalysis.kt`
```kotlin
data class FailureAnalysis(
    val lastSuccessfulPipe: String?,
    val failurePoint: TraceEvent?,
    val failureReason: String,
    val contextAtFailure: ContextWindow?,
    val suggestedFixes: List<String>,
    val executionPath: List<String>
)
```

#### 7. TraceVisualizer (Visualization Class)
**File**: `TPipe/src/main/kotlin/Debug/TraceVisualizer.kt`

#### 8. TraceConfig (Configuration Class)
**File**: `TPipe/src/main/kotlin/Debug/TraceConfig.kt`
```kotlin
data class TraceConfig(
    val enabled: Boolean = false,
    val maxHistory: Int = 1000,
    val outputFormat: TraceFormat = TraceFormat.CONSOLE,
    val detailLevel: TraceDetailLevel = TraceDetailLevel.NORMAL,
    val autoExport: Boolean = false,
    val exportPath: String = "~/.TPipe-Debug/traces/",
    val includeContext: Boolean = true,
    val includeMetadata: Boolean = true
)
```

#### 9. TraceDetailLevel (Enum)
**File**: `TPipe/src/main/kotlin/Debug/TraceDetailLevel.kt`
```kotlin
enum class TraceDetailLevel {
    MINIMAL,    // Only failures and major events
    NORMAL,     // Standard tracing
    VERBOSE,    // All events including metadata
    DEBUG       // Everything including internal state
}
```

#### 10. TracingBuilder (Configuration Builder)
**File**: `TPipe/src/main/kotlin/Debug/TracingBuilder.kt`
```kotlin
class TracingBuilder {
    private var config = TraceConfig()
    
    fun enabled(enabled: Boolean = true): TracingBuilder
    fun maxHistory(count: Int): TracingBuilder
    fun outputFormat(format: TraceFormat): TracingBuilder
    fun detailLevel(level: TraceDetailLevel): TracingBuilder
    fun autoExport(enabled: Boolean = true, path: String = "~/.TPipe-Debug/traces/"): TracingBuilder
    fun includeContext(include: Boolean = true): TracingBuilder
    fun includeMetadata(include: Boolean = true): TracingBuilder
    fun build(): TraceConfig
}
```
```kotlin
class TraceVisualizer {
    fun generateFlowChart(trace: List<TraceEvent>): String
    fun generateTimeline(trace: List<TraceEvent>): String
    fun generateConsoleOutput(trace: List<TraceEvent>): String
    fun generateHtmlReport(trace: List<TraceEvent>): String
}
```

## Implementation Steps

### Step 1: Create Base Tracing Infrastructure
1. **Create TraceEvent data class** - Core event structure
2. **Create TraceEventType enum** - Event type definitions
3. **Create TracePhase enum** - Execution phase definitions
4. **Create TraceFormat enum** - Output format options
5. **Create PipeTracer object** - Central tracing coordinator

### Step 2: Integrate Tracing into Pipe Base Class
**File**: `TPipe/src/main/kotlin/Pipe/Pipe.kt`

**Modifications Required**:
1. Add import for Debug package:
```kotlin
import com.TTT.Debug.*
```

2. Add tracing properties:
```kotlin
protected var tracingEnabled = false
protected var traceConfig = TraceConfig()
protected var pipeId = UUID.randomUUID().toString()
protected var currentPipelineId: String? = null
```

3. Add tracing methods:
```kotlin
fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipe {
    this.tracingEnabled = true
    this.traceConfig = config
    return this
}

fun disableTracing(): Pipe {
    this.tracingEnabled = false
    return this
}

private fun trace(eventType: TraceEventType, phase: TracePhase, content: MultimodalContent? = null, metadata: Map<String, Any> = emptyMap(), error: Throwable? = null) {
    if (!tracingEnabled) return
    
    val event = TraceEvent(
        timestamp = System.currentTimeMillis(),
        pipeId = pipeId,
        pipeName = this::class.simpleName ?: "UnknownPipe",
        eventType = eventType,
        phase = phase,
        content = if (traceConfig.includeContext) content else null,
        contextSnapshot = if (traceConfig.includeContext) contextWindow else null,
        metadata = if (traceConfig.includeMetadata) metadata else emptyMap(),
        error = error
    )
    
    currentPipelineId?.let { pipelineId ->
        PipeTracer.addEvent(pipelineId, event)
    }
}
```

3. **Modify execute method** to add comprehensive tracing:
```kotlin
override suspend fun execute(content: MultimodalContent): MultimodalContent {
    trace(TraceEventType.PIPE_START, TracePhase.INITIALIZATION, content)
    
    try {
        // Context preparation phase
        trace(TraceEventType.CONTEXT_PULL, TracePhase.CONTEXT_PREPARATION)
        handleContextOperations()
        
        // Pre-validation phase
        if (preValidationFunction != null) {
            trace(TraceEventType.VALIDATION_START, TracePhase.PRE_VALIDATION)
            // Execute pre-validation
            trace(TraceEventType.VALIDATION_SUCCESS, TracePhase.PRE_VALIDATION)
        }
        
        // Execution phase
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION, content)
        val result = generateContent(content)
        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, result)
        
        // Validation phase
        if (validatorFunction != null) {
            trace(TraceEventType.VALIDATION_START, TracePhase.VALIDATION, result)
            val isValid = validatorFunction!!.invoke(result)
            if (isValid) {
                trace(TraceEventType.VALIDATION_SUCCESS, TracePhase.VALIDATION, result)
            } else {
                trace(TraceEventType.VALIDATION_FAILURE, TracePhase.VALIDATION, result)
                return handleValidationFailure(content, result)
            }
        }
        
        // Transformation phase
        val finalResult = if (transformationFunction != null) {
            trace(TraceEventType.TRANSFORMATION_START, TracePhase.TRANSFORMATION, result)
            val transformed = transformationFunction!!.invoke(result)
            trace(TraceEventType.TRANSFORMATION_SUCCESS, TracePhase.TRANSFORMATION, transformed)
            transformed
        } else result
        
        trace(TraceEventType.PIPE_SUCCESS, TracePhase.CLEANUP, finalResult)
        return finalResult
        
    } catch (e: Exception) {
        trace(TraceEventType.PIPE_FAILURE, TracePhase.CLEANUP, content, error = e)
        return MultimodalContent("").terminate()
    }
}
```

### Step 3: Integrate Tracing into Pipeline Class
**File**: `TPipe/src/main/kotlin/Pipeline/Pipeline.kt`

**Modifications Required**:
1. Add import for Debug package:
```kotlin
import com.TTT.Debug.*
```

2. Add pipeline tracing properties:
```kotlin
private var tracingEnabled = false
private var traceConfig = TraceConfig()
private val pipelineId = UUID.randomUUID().toString()
```

3. Add tracing methods:
```kotlin
fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipeline {
    this.tracingEnabled = true
    this.traceConfig = config
    return this
}

fun getTraceReport(format: TraceFormat = traceConfig.outputFormat): String {
    return PipeTracer.exportTrace(pipelineId, format)
}

fun getFailureAnalysis(): FailureAnalysis? {
    return if (tracingEnabled) PipeTracer.getFailureAnalysis(pipelineId) else null
}

fun getTraceId(): String = pipelineId
```

3. **Modify executeMultimodal method**:
```kotlin
private suspend fun executeMultimodal(initialContent: MultimodalContent): MultimodalContent = coroutineScope {
    if (tracingEnabled) {
        PipeTracer.startTrace(pipelineId)
    }
    
    var generatedContent = initialContent
    
    for (pipe in pipes) {
        if (tracingEnabled) {
            pipe.enableTracing()
            pipe.currentPipelineId = pipelineId
        }
        
        val result: Deferred<MultimodalContent> = async {
            pipe.execute(generatedContent)
        }
        
        generatedContent = result.await()
        
        if (generatedContent.shouldTerminate()) {
            if (tracingEnabled) {
                PipeTracer.addEvent(pipelineId, TraceEvent(
                    timestamp = System.currentTimeMillis(),
                    pipeId = pipe.pipeId,
                    pipeName = pipe.javaClass.simpleName,
                    eventType = TraceEventType.PIPELINE_TERMINATION,
                    phase = TracePhase.CLEANUP,
                    content = generatedContent
                ))
            }
            break
        }
    }
    
    return@coroutineScope generatedContent
}
```

### Step 4: Enhance Provider-Specific Pipes

**Critical**: All provider pipes must override both `generateText()` and `generateContent()` methods with comprehensive tracing since these are the core interface methods that make provider-specific API calls.

#### BedrockPipe Modifications
**File**: `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`

**Modifications Required**:
1. **Add Debug package import**:
```kotlin
import com.TTT.Debug.*
```

2. **Override generateText method** with comprehensive tracing:
```kotlin
override suspend fun generateText(promptInjector: String): String {
    trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
          metadata = mapOf(
              "method" to "generateText",
              "model" to model,
              "region" to region,
              "useConverseApi" to useConverseApi,
              "promptLength" to promptInjector.length
          ))
    
    return try {
        // Existing implementation with internal tracing
        val client = bedrockClient ?: run {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  metadata = mapOf("error" to "Client not initialized"))
            return ""
        }
        
        val modelId = model.ifEmpty { "anthropic.claude-3-sonnet-20240229-v1:0" }
        
        // Trace model-specific request building
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf("step" to "buildRequest", "modelId" to modelId))
        
        val requestJson = when {
            modelId.contains("openai.gpt-oss") -> buildGptOssRequest(promptInjector)
            modelId.contains("anthropic.claude") -> buildClaudeRequest(promptInjector)
            // ... other model types
            else -> buildGenericRequest(promptInjector)
        }
        
        // Trace API call
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf("step" to "invokeModel", "requestSize" to requestJson.length))
        
        val invokeRequest = InvokeModelRequest {
            this.modelId = modelId
            body = requestJson.toByteArray()
            contentType = "application/json"
        }
        
        val response = client.invokeModel(invokeRequest)
        val responseBody = response.body?.let { String(it) } ?: ""
        val result = extractTextFromResponse(responseBody, modelId)
        
        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              metadata = mapOf(
                  "responseLength" to result.length,
                  "responseBodySize" to responseBody.length,
                  "success" to true
              ))
        result
        
    } catch (e: Exception) {
        trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
              error = e,
              metadata = mapOf(
                  "errorType" to e::class.simpleName,
                  "errorMessage" to (e.message ?: "Unknown error")
              ))
        ""
    }
}
```

3. **Override generateContent method** for multimodal support:
```kotlin
override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
    // For base BedrockPipe, delegate to generateText for text-only content
    trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
          content = content,
          metadata = mapOf(
              "method" to "generateContent",
              "hasText" to content.text.isNotEmpty(),
              "hasBinaryContent" to content.binaryContent.isNotEmpty(),
              "delegateToGenerateText" to true
          ))
    
    return try {
        val textResult = generateText(content.text)
        val result = MultimodalContent(text = textResult)
        
        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, result)
        result
        
    } catch (e: Exception) {
        trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION, error = e)
        MultimodalContent("")
    }
}
```

4. **Override truncateModuleContext method**:
```kotlin
override fun truncateModuleContext(): Pipe {
    trace(TraceEventType.CONTEXT_TRUNCATE, TracePhase.CONTEXT_PREPARATION,
          metadata = mapOf(
              "contextWindowSize" to contextWindowSize,
              "truncateAsString" to truncateContextAsString,
              "contextWindowTruncation" to contextWindowTruncation.name
          ))
    
    // existing implementation
    
    return this
}
```

#### BedrockMultimodalPipe Modifications
**File**: `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockMultimodalPipe.kt`

**Modifications Required**:
1. **Add Debug package import**:
```kotlin
import com.TTT.Debug.*
```

2. **Override generateContent method** with comprehensive multimodal tracing:
```kotlin
override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
    trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
          content = content,
          metadata = mapOf(
              "method" to "generateContent",
              "hasText" to content.text.isNotEmpty(),
              "binaryContentCount" to content.binaryContent.size,
              "binaryContentTypes" to content.binaryContent.map { it.getMimeType() },
              "useConverseApi" to useConverseApi,
              "model" to model
          ))
    
    return try {
        val client = bedrockClient ?: run {
            trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
                  metadata = mapOf("error" to "Client not initialized"))
            return MultimodalContent("")
        }
        
        val modelId = model.ifEmpty { "anthropic.claude-3-sonnet-20240229-v1:0" }
        
        val result = if (useConverseApi) {
            trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
                  metadata = mapOf("apiType" to "ConverseAPI"))
            generateMultimodalWithConverseApi(client, modelId, content)
        } else {
            trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
                  metadata = mapOf("apiType" to "InvokeAPI"))
            generateMultimodalWithInvokeApi(client, modelId, content)
        }
        
        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              result,
              metadata = mapOf(
                  "resultTextLength" to result.text.length,
                  "resultBinaryCount" to result.binaryContent.size
              ))
        result
        
    } catch (e: Exception) {
        trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION,
              error = e,
              metadata = mapOf(
                  "errorType" to e::class.simpleName,
                  "errorMessage" to (e.message ?: "Unknown error")
              ))
        MultimodalContent("")
    }
}
```

3. **Add tracing to internal methods**:
```kotlin
private suspend fun generateMultimodalWithConverseApi(
    client: BedrockRuntimeClient, 
    modelId: String, 
    content: MultimodalContent
): MultimodalContent {
    trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
          metadata = mapOf("internalMethod" to "generateMultimodalWithConverseApi"))
    
    // existing implementation with additional tracing
    val contentBlocks = mutableListOf<ContentBlock>()
    
    if (content.text.isNotEmpty()) {
        contentBlocks.add(ContentBlock.Text(content.text))
    }
    
    content.binaryContent.forEach { binaryContent ->
        trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION,
              metadata = mapOf(
                  "step" to "convertBinaryContent",
                  "mimeType" to binaryContent.getMimeType(),
                  "size" to when(binaryContent) {
                      is BinaryContent.Bytes -> binaryContent.data.size
                      is BinaryContent.Base64String -> binaryContent.data.length
                      else -> 0
                  }
              ))
        
        val contentBlock = convertBinaryToContentBlock(binaryContent)
        if (contentBlock != null) {
            contentBlocks.add(contentBlock)
        }
    }
    
    // Continue with existing implementation...
}
```

#### OllamaPipe Modifications
**File**: `TPipe/TPipe-Ollama/src/main/kotlin/ollamaPipe/OllamaPipe.kt`

**Modifications Required**:
1. **Add Debug package import**:
```kotlin
import com.TTT.Debug.*
```

2. **Override init method** with Ollama-specific tracing:
```kotlin
override suspend fun init(): Pipe {
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
        
        val isRunning = coroutineScope {
            async { getVersion() }
        }
        
        val serverOnline = isRunning.await()
        
        if (serverOnline == null || serverOnline.version == "") {
            trace(TraceEventType.API_CALL_START, TracePhase.INITIALIZATION,
                  metadata = mapOf("step" to "startOllamaServer"))
            serve()
        }
        
        trace(TraceEventType.PIPE_SUCCESS, TracePhase.INITIALIZATION,
              metadata = mapOf("serverVersion" to (serverOnline?.version ?: "unknown")))
        
    } catch (e: Exception) {
        trace(TraceEventType.PIPE_FAILURE, TracePhase.INITIALIZATION, error = e)
    }
    
    return this
}
```

3. **Implement generateText method** with comprehensive tracing:
```kotlin
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
        val result = "" // Implementation needed based on Ollama response format
        
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
                  "errorType" to e::class.simpleName,
                  "endpoint" to "$ip:$port"
              ))
        ""
    }
}
```

4. **Override generateContent method**:
```kotlin
override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
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
        val textResult = generateText(content.text)
        val result = MultimodalContent(text = textResult)
        
        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, result)
        result
        
    } catch (e: Exception) {
        trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION, error = e)
        MultimodalContent("")
    }
}
```

### Step 5: Add Core Interface Method Tracing

**Critical Implementation Note**: The `generateText()` and `generateContent()` methods are the core interface methods that all provider pipes must implement. These methods contain the actual provider-specific API calls and are the most critical points for tracing failures.

#### Required Tracing Points for All Provider Pipes:

1. **Method Entry Tracing**:
   - Log method name, input parameters, and provider-specific metadata
   - Include model, endpoint, and configuration details

2. **Request Building Tracing**:
   - Log request construction steps
   - Include request size, format, and model-specific parameters

3. **API Call Tracing**:
   - Log actual HTTP/SDK calls with timing
   - Include endpoint, request size, and authentication status

4. **Response Processing Tracing**:
   - Log response parsing and extraction steps
   - Include response size, format, and extraction success

5. **Error Handling Tracing**:
   - Log all exceptions with full context
   - Include error type, message, and recovery attempts

#### Abstract Method Requirements:

All provider pipes must implement these methods with tracing:

```kotlin
// Required in all provider pipes
abstract suspend fun generateText(promptInjector: String): String
abstract suspend fun generateContent(content: MultimodalContent): MultimodalContent
abstract suspend fun init(): Pipe
abstract fun truncateModuleContext(): Pipe
```

#### Tracing Metadata Standards:

**For generateText():**
- `method`: "generateText"
- `provider`: Provider name (e.g., "AWS Bedrock", "Ollama")
- `model`: Model identifier
- `endpoint`: API endpoint or server address
- `promptLength`: Input prompt character count
- `requestSize`: Request payload size in bytes
- `responseLength`: Response text character count

**For generateContent():**
- `method`: "generateContent"
- `hasText`: Boolean indicating text content presence
- `binaryContentCount`: Number of binary attachments
- `binaryContentTypes`: Array of MIME types
- `totalContentSize`: Combined size of all content
- `apiType`: Specific API used (e.g., "ConverseAPI", "InvokeAPI")

### Step 6: Create Visualization Components

#### Console Visualizer
**File**: `TPipe/src/main/kotlin/Debug/ConsoleVisualizer.kt`
```kotlin
class ConsoleVisualizer {
    fun printTrace(trace: List<TraceEvent>)
    fun printFailureAnalysis(analysis: FailureAnalysis)
    fun printExecutionSummary(trace: List<TraceEvent>)
}
```

#### HTML Report Generator
**File**: `TPipe/src/main/kotlin/Debug/HtmlReportGenerator.kt`
```kotlin
class HtmlReportGenerator {
    fun generateReport(trace: List<TraceEvent>): String
    fun generateFailureReport(analysis: FailureAnalysis): String
}
```

### Step 7: Create Utility Extensions

#### Pipe Extensions
**File**: `TPipe/src/main/kotlin/Debug/PipeExtensions.kt`
```kotlin
fun Pipe.withTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipe = this.enableTracing(config)
fun Pipeline.withTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipeline = this.enableTracing(config)
```

#### Analysis Extensions
**File**: `TPipe/src/main/kotlin/Debug/AnalysisExtensions.kt`
```kotlin
fun List<TraceEvent>.findLastSuccess(): TraceEvent?
fun List<TraceEvent>.findFirstFailure(): TraceEvent?
fun List<TraceEvent>.getExecutionPath(): List<String>
```

#### Test Extensions
**File**: `TPipe/src/test/kotlin/Debug/TestExtensions.kt`
```kotlin
fun createMockTraceEvent(): TraceEvent
fun createTestPipeline(): Pipeline
fun assertTraceContains(trace: List<TraceEvent>, eventType: TraceEventType)
```

## Usage Examples

### Basic Tracing
```kotlin
// Simple enable with defaults
val pipeline = Pipeline()
    .enableTracing()
    .add(BedrockPipe().setModel("claude-3-sonnet").enableTracing())
    .add(BedrockPipe().setModel("claude-3-haiku").enableTracing())

val result = pipeline.execute("Test prompt")

// Get trace report
val report = pipeline.getTraceReport()
println(report)

// Get failure analysis if needed
val analysis = pipeline.getFailureAnalysis()
analysis?.let { println(it) }
```

### Advanced Tracing with Custom Configuration
```kotlin
// Custom configuration
val traceConfig = TracingBuilder()
    .enabled()
    .detailLevel(TraceDetailLevel.VERBOSE)
    .outputFormat(TraceFormat.HTML)
    .autoExport(true, "~/.TPipe-Debug/traces/")
    .maxHistory(10000)
    .build()

val pipeline = Pipeline()
    .enableTracing(traceConfig)
    .add(BedrockPipe().setModel("claude-3-sonnet").enableTracing(traceConfig))
    .add(BedrockPipe().setModel("claude-3-haiku").enableTracing(traceConfig))

val result = pipeline.execute(content)

// Get trace ID for later analysis
val traceId = pipeline.getTraceId()
println("Trace ID: $traceId")

// Generate different output formats
val htmlReport = pipeline.getTraceReport(TraceFormat.HTML)
val consoleOutput = pipeline.getTraceReport(TraceFormat.CONSOLE)
val jsonExport = pipeline.getTraceReport(TraceFormat.JSON)
```

### Extension Method Usage
```kotlin
// Using extension methods for cleaner syntax
val pipeline = Pipeline()
    .withTracing(TracingBuilder().detailLevel(TraceDetailLevel.DEBUG).build())
    .add(BedrockPipe().setModel("claude-3-sonnet").withTracing())
    .add(BedrockPipe().setModel("claude-3-haiku").withTracing())
```

## Testing Strategy

**All test files must be located in: `TPipe/src/test/kotlin/Debug/`**

### Unit Tests Required
1. **TraceEvent creation and serialization**
   - **File**: `TPipe/src/test/kotlin/Debug/TraceEventTest.kt`
   - Test event creation, serialization, and metadata handling

2. **PipeTracer event collection and retrieval**
   - **File**: `TPipe/src/test/kotlin/Debug/PipeTracerTest.kt`
   - Test trace storage, retrieval, and cleanup

3. **Pipe tracing integration**
   - **File**: `TPipe/src/test/kotlin/Debug/PipeTracingTest.kt`
   - Test tracing method integration in base Pipe class

4. **Pipeline tracing integration**
   - **File**: `TPipe/src/test/kotlin/Debug/PipelineTracingTest.kt`
   - Test pipeline-level tracing and coordination

5. **Failure analysis accuracy**
   - **File**: `TPipe/src/test/kotlin/Debug/FailureAnalysisTest.kt`
   - Test failure detection and analysis generation

6. **Visualization output correctness**
   - **File**: `TPipe/src/test/kotlin/Debug/TraceVisualizerTest.kt`
   - Test HTML, console, and other output formats

7. **Configuration system**
   - **File**: `TPipe/src/test/kotlin/Debug/TracingBuilderTest.kt`
   - Test configuration builder and file loading

8. **Provider-specific tracing**
   - **File**: `TPipe/src/test/kotlin/Debug/ProviderTracingTest.kt`
   - Test generateText() and generateContent() tracing

### Integration Tests Required
1. **End-to-end pipeline tracing**
   - **File**: `TPipe/src/test/kotlin/Debug/EndToEndTracingTest.kt`
   - Test complete pipeline execution with tracing

2. **Multi-pipe failure scenarios**
   - **File**: `TPipe/src/test/kotlin/Debug/FailureScenarioTest.kt`
   - Test various failure points and recovery

3. **Context truncation tracing**
   - **File**: `TPipe/src/test/kotlin/Debug/ContextTracingTest.kt`
   - Test context operations and truncation tracing

4. **Validation failure tracing**
   - **File**: `TPipe/src/test/kotlin/Debug/ValidationTracingTest.kt`
   - Test validation and transformation tracing

5. **Branch pipe tracing**
   - **File**: `TPipe/src/test/kotlin/Debug/BranchPipeTracingTest.kt`
   - Test branch pipe execution and tracing

## Performance Considerations

### Optimization Strategies
1. **Lazy trace collection** - Only collect when enabled
2. **Configurable trace depth** - Control detail level
3. **Async trace writing** - Non-blocking trace operations
4. **Memory management** - Automatic trace cleanup
5. **Conditional compilation** - Debug-only tracing in production

## Configuration System

### Configuration File Location
**File**: `~/.TPipe-Debug/tpipe-trace-config.json` (user home directory)
```json
{
  "enabled": true,
  "maxHistory": 1000,
  "outputFormat": "HTML",
  "detailLevel": "VERBOSE",
  "autoExport": true,
  "exportPath": "~/.TPipe-Debug/traces/",
  "includeContext": true,
  "includeMetadata": true
}
```

**Directory Structure**:
```
~/.TPipe-Debug/
├── tpipe-trace-config.json
├── traces/
│   ├── trace-2024-01-15-14-30-45.json
│   ├── trace-2024-01-15-14-35-12.html
│   └── ...
└── logs/
    └── tracer.log
```

### Programmatic Configuration (Primary Method)
```kotlin
// Method 1: Simple enable with defaults
val pipeline = Pipeline()
    .enableTracing()
    .add(pipe1)
    .add(pipe2)

// Method 2: Custom configuration via builder
val traceConfig = TracingBuilder()
    .enabled()
    .detailLevel(TraceDetailLevel.VERBOSE)
    .outputFormat(TraceFormat.HTML)
    .autoExport(true, "~/.TPipe-Debug/traces/")
    .maxHistory(5000)
    .build()

val pipeline = Pipeline()
    .enableTracing(traceConfig)
    .add(pipe1)
    .add(pipe2)

// Method 3: Individual pipe tracing
val pipe = BedrockPipe()
    .setModel("claude-3-sonnet")
    .enableTracing(TracingBuilder().detailLevel(TraceDetailLevel.DEBUG).build())
```

### Environment Variables (Override)
- `TPIPE_TRACING_ENABLED` - Global tracing toggle
- `TPIPE_TRACE_MAX_HISTORY` - Maximum trace events to keep
- `TPIPE_TRACE_OUTPUT_FORMAT` - Default output format
- `TPIPE_TRACE_DETAIL_LEVEL` - Trace verbosity level
- `TPIPE_TRACE_EXPORT_PATH` - Default export directory (default: ~/.TPipe-Debug/traces/)

### CLI Tool (Optional - Phase 3)
**File**: `TPipe/src/main/kotlin/Debug/TraceCLI.kt`
```kotlin
class TraceCLI {
    fun generateConfig(args: Array<String>): TraceConfig
    fun exportTrace(traceId: String, format: TraceFormat, outputPath: String)
    fun analyzeTrace(traceId: String)
}
```

Usage:
```bash
# Generate config interactively
kotlin TraceCLI.kt --generate-config

# Export specific trace
kotlin TraceCLI.kt --export-trace <trace-id> --format HTML --output ~/.TPipe-Debug/reports/

# Analyze failure
kotlin TraceCLI.kt --analyze <trace-id>
```

## Implementation Priority

### Phase 1 (Critical)
1. TraceEvent, TraceEventType, TracePhase classes
2. PipeTracer core functionality
3. Basic Pipe class integration
4. Pipeline class integration
5. Console visualization

### Phase 2 (Important)
1. Provider-specific pipe enhancements
2. FailureAnalysis implementation
3. HTML report generation
4. Advanced visualization

### Phase 3 (Enhancement)
1. Configuration system
2. Performance optimizations
3. Advanced analysis features
4. Export/import functionality

This comprehensive plan provides a complete roadmap for implementing a robust pipe tracing system that will significantly improve debugging capabilities in the TPipe framework.