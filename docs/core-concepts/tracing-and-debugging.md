# Tracing and Debugging

## Table of Contents
- [What is TPipe Tracing?](#what-is-tpipe-tracing)
- [Enabling Tracing](#enabling-tracing)
- [Trace Configuration](#trace-configuration)
- [Trace Events and Phases](#trace-events-and-phases)
- [Practical Debugging Examples](#practical-debugging-examples)
- [Pipeline Tracing](#pipeline-tracing)
- [Advanced Tracing Patterns](#advanced-tracing-patterns)
- [Recursive Tracing Propagation](#recursive-tracing-propagation)
- [Remote Trace Dispatch](#remote-trace-dispatch)
- [Performance Analysis](#performance-analysis)
- [Best Practices](#best-practices)

TPipe provides comprehensive tracing and debugging capabilities that allow you to monitor pipe execution, analyze performance, and troubleshoot issues in complex AI processing workflows.

## What is TPipe Tracing?

TPipe tracing is a built-in monitoring system that:
- **Tracks execution flow** through pipes and pipelines
- **Records timing information** for performance analysis
- **Captures context changes** and data transformations
- **Logs API calls** and responses
- **Provides detailed error information** for debugging
- **Enables performance optimization** through detailed metrics

## Enabling Tracing

### Basic Tracing
```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .enableTracing()  // Enable basic tracing
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")

val result = runBlocking { pipe.execute("Hello world") }
// Tracing output will be logged to console
```

### Pipeline Tracing
```kotlin
val pipeline = Pipeline()
    .enableTracing()  // Enable tracing for entire pipeline
    .add(BedrockPipe()
        .setSystemPrompt("Analyze the input.")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    )
    .add(BedrockPipe()
        .setSystemPrompt("Generate a response based on analysis.")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    )

val result = runBlocking { pipeline.execute("Complex input requiring analysis") }
```

### Conditional Tracing
```kotlin
val debugMode = System.getProperty("debug", "false").toBoolean()

val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .apply { 
        if (debugMode) {
            enableTracing()
        }
    }
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
```

## Trace Configuration

### TraceConfig Properties
```kotlin
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat

val traceConfig = TraceConfig(
    detailLevel = TraceDetailLevel.DEBUG,    // MINIMAL, NORMAL, VERBOSE, DEBUG
    outputFormat = TraceFormat.HTML,         // CONSOLE or HTML format  
    includeContext = true,                   // Include context in traces
    includeMetadata = true                   // Include metadata in traces
)

val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .enableTracing(traceConfig)
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
```

**Note**: The `enabled`, `maxHistory`, `autoExport`, and `exportPath` properties exist in TraceConfig but are **not used** by the actual tracing system.

### TraceDetailLevel Effects
```kotlin
// MINIMAL - Only critical events, no content/context
TraceDetailLevel.MINIMAL

// NORMAL - Standard events, no content/context  
TraceDetailLevel.NORMAL

// VERBOSE - Detailed events, includes content/context if includeContext=true
TraceDetailLevel.VERBOSE

// DEBUG - All events, includes content/context if includeContext=true
TraceDetailLevel.DEBUG
```

### TraceFormat Options
```kotlin
// JSON structured output (for programmatic processing)
TraceFormat.JSON

// HTML formatted output (better for complex traces)
TraceFormat.HTML

// Markdown formatted output (for documentation)
TraceFormat.MARKDOWN

// Console text output
TraceFormat.CONSOLE
```

## HTML Trace Report Features

When using `TraceFormat.HTML`, TPipe generates interactive HTML reports with enhanced visualization features for improved debugging and analysis.

### Expandable Content Sections

HTML trace reports include collapsible sections for verbose content, improving readability and performance when analyzing large traces:

```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .enableTracing(TraceConfig(
        detailLevel = TraceDetailLevel.DEBUG,
        outputFormat = TraceFormat.HTML
    ))
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")

runBlocking {
    pipe.init()
    val result = pipe.execute("Explain quantum computing")
    
    // Generate HTML report with expandable sections
    val htmlReport = pipe.getTraceReport(TraceFormat.HTML)
    File("trace-report.html").writeText(htmlReport)
}
```

**Expandable Sections Include:**
- **Input Content** (📥 Green) - User prompts and input text
- **Output Content** (📤 Blue) - Model-generated responses
- **Request Object** (📦 Gray) - API request details
- **Generated Content** (✨ Orange) - Raw model output before transformation
- **Full Prompt** (📝 Black) - Complete prompt sent to model
- **Content Text** (📄 Black) - Processed content text
- **Page Key** (🔑 Yellow) - Context bank page identifiers
- **Context Window** (🪟 Purple) - Context window contents

**Benefits:**
- Large content is hidden by default, reducing visual clutter
- Click to expand only the sections you need to inspect
- Color-coded icons provide quick visual identification
- Improves performance when viewing traces with large prompts or outputs

### Hexagon Entry Nodes

The first node in Mermaid flow diagrams renders as a hexagon, making the entry point of execution flows visually distinct:

```kotlin
val pipeline = Pipeline()
    .enableTracing(TraceConfig(outputFormat = TraceFormat.HTML))
    .add(BedrockPipe()
        .setPipeName("EntryPipe")  // Renders as hexagon
        .setSystemPrompt("Analyze input.")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    )
    .add(BedrockPipe()
        .setPipeName("ProcessorPipe")  // Renders as rectangle
        .setSystemPrompt("Process analysis.")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    )

runBlocking {
    pipeline.init()
    val result = pipeline.execute("Input text")
    
    // HTML report shows EntryPipe as hexagon, ProcessorPipe as rectangle
    val htmlReport = pipeline.getTraceReport(TraceFormat.HTML)
}
```

**Visual Flow:**
```
{{EntryPipe}} --> [ProcessorPipe]
```

The hexagon shape (Mermaid `{{}}` syntax) immediately identifies where execution begins, especially useful in complex pipelines with multiple branches and stages.

### Colored Token Counts

Token-related metadata displays with color coding and bold formatting for quick identification and analysis:

```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .enableTracing(TraceConfig(
        detailLevel = TraceDetailLevel.DEBUG,
        outputFormat = TraceFormat.HTML,
        includeMetadata = true
    ))
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")

runBlocking {
    pipe.init()
    val result = pipe.execute("Explain machine learning")
    
    // Token counts appear color-coded in HTML report
    val htmlReport = pipe.getTraceReport(TraceFormat.HTML)
}
```

**Color Scheme:**
- **Input Tokens** - Green (#28a745) - Tokens consumed from user input and context
- **Output Tokens** - Blue (#17a2b8) - Tokens generated by the model
- **Other Token Metrics** - Purple (#6f42c1) - Total tokens, budget utilization, etc.

**Benefits:**
- Quickly identify token usage patterns in trace reports
- Spot token budget issues at a glance
- Analyze input vs output token ratios
- Monitor token consumption across pipeline stages

### Generating HTML Reports

```kotlin
// Basic HTML report
val pipe = BedrockPipe()
    .enableTracing(TraceConfig(outputFormat = TraceFormat.HTML))

runBlocking {
    pipe.init()
    pipe.execute("Test input")
    
    // Get HTML report
    val htmlReport = pipe.getTraceReport(TraceFormat.HTML)
    File("trace.html").writeText(htmlReport)
}

// Pipeline HTML report with all features
val pipeline = Pipeline()
    .enableTracing(TraceConfig(
        detailLevel = TraceDetailLevel.DEBUG,
        outputFormat = TraceFormat.HTML,
        includeContext = true,
        includeMetadata = true
    ))
    .add(pipe1)
    .add(pipe2)

runBlocking {
    pipeline.init()
    pipeline.execute("Complex input")
    
    // HTML includes:
    // - Hexagon entry node for first pipe
    // - Expandable sections for all verbose content
    // - Color-coded token counts throughout
    val htmlReport = pipeline.getTraceReport(TraceFormat.HTML)
    File("pipeline-trace.html").writeText(htmlReport)
}
```

### Interactive Features

HTML trace reports include interactive JavaScript features:
- **Click nodes** in Mermaid diagrams to scroll to corresponding trace events
- **Expand/collapse** content sections to focus on relevant information
- **Hover effects** on trace events for better navigation
- **Responsive layout** adapts to different screen sizes

### Best Practices for HTML Traces

**1. Use DEBUG detail level for comprehensive reports:**
```kotlin
TraceConfig(
    detailLevel = TraceDetailLevel.DEBUG,
    outputFormat = TraceFormat.HTML,
    includeContext = true,
    includeMetadata = true
)
```

**2. Generate HTML reports for complex debugging sessions:**
```kotlin
// Development: Use HTML for detailed analysis
val devConfig = TraceConfig(outputFormat = TraceFormat.HTML)

// Production: Use JSON for programmatic processing
val prodConfig = TraceConfig(outputFormat = TraceFormat.JSON)
```

**3. Leverage expandable sections for large contexts:**
```kotlin
// Large context windows are automatically collapsible
val pipe = BedrockPipe()
    .pullGlobalContext()
    .autoInjectContext("Use context")
    .enableTracing(TraceConfig(
        outputFormat = TraceFormat.HTML,
        includeContext = true  // Context appears in expandable section
    ))
```

**4. Use color-coded tokens for performance analysis:**
```kotlin
// Monitor token usage across pipeline stages
val pipeline = Pipeline()
    .enableTracing(TraceConfig(
        outputFormat = TraceFormat.HTML,
        includeMetadata = true  // Includes token counts
    ))
    .add(stage1)
    .add(stage2)
    .add(stage3)

// HTML report shows token consumption per stage with color coding
```

## Trace Events and Phases

### Trace Event Types
```kotlin
// Common trace events you'll see in logs:
TraceEventType.PIPE_START          // Pipe execution begins
TraceEventType.CONTEXT_PULL        // Context retrieval from ContextBank
TraceEventType.VALIDATION_START    // Validation function execution
TraceEventType.VALIDATION_SUCCESS  // Validation passed
TraceEventType.VALIDATION_FAILURE  // Validation failed
TraceEventType.API_CALL_START      // AI model API call begins
TraceEventType.API_CALL_SUCCESS    // AI model API call succeeds
TraceEventType.TRANSFORMATION_START // Transformation function begins
TraceEventType.TRANSFORMATION_SUCCESS // Transformation completes
TraceEventType.PIPE_SUCCESS        // Pipe execution completes successfully
TraceEventType.PIPE_FAILURE        // Pipe execution fails
TraceEventType.BRANCH_PIPE_TRIGGERED // Branch pipe executed
```

### Trace Phases
```kotlin
TracePhase.INITIALIZATION    // Setup and configuration
TracePhase.CONTEXT_PREPARATION // Context loading and processing
TracePhase.PRE_VALIDATION   // Pre-validation functions
TracePhase.EXECUTION        // Main AI model execution
TracePhase.VALIDATION       // Output validation
TracePhase.TRANSFORMATION   // Output transformation
TracePhase.POST_PROCESSING  // Final processing steps
TracePhase.CLEANUP          // Resource cleanup
```

## Practical Debugging Examples

### Debugging Context Issues
```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are a Manuscript Orchestrator responsible for cross-referencing archival data.")
    .pullGlobalContext()
    .setPageKey("userProfile")
    .autoInjectContext("Use the provided context to personalize responses.")
    .enableTracing()  // Enable to see context loading
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")

val result = runBlocking { pipe.execute("What do you know about me?") }

// Trace output will show:
// - CONTEXT_PULL: Loading context from ContextBank
// - Context content and size
// - Context injection into prompt
```

### Debugging Validation Failures
```kotlin
import com.TTT.Util.extractJson

val pipe = BedrockPipe()
    .setSystemPrompt("Generate a JSON response.")
    .setJsonOutput(UserProfile("", "", 0))
    .setValidatorFunction { content ->
        extractJson<UserProfile>(content.text) != null
    }
    .enableTracing()  // See validation process
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")

val result = runBlocking { pipe.execute("Create a user profile for John Doe") }

// Trace output will show:
// - VALIDATION_START: Beginning validation
// - VALIDATION_FAILURE: If JSON parsing fails
// - Detailed error information
```

### Debugging Exception Handling
```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setExceptionFunction { content, exception ->
        println("=== Exception Debug Information ===")
        println("Content state when exception occurred:")
        println("  Text length: ${content.text.length}")
        println("  Has binary content: ${content.binaryContent.isNotEmpty()}")
        println("  Current pipe: ${content.currentPipe?.pipeName ?: "Unknown"}")
        
        println("Exception details:")
        println("  Type: ${exception::class.simpleName}")
        println("  Message: ${exception.message}")
        
        // Log specific AWS Bedrock errors
        when (exception) {
            is aws.sdk.kotlin.services.bedrockruntime.model.ThrottlingException -> {
                println("  → Rate limiting detected - consider retry logic")
            }
            is aws.sdk.kotlin.services.bedrockruntime.model.ValidationException -> {
                println("  → Request validation failed - check parameters")
            }
            is aws.sdk.kotlin.services.bedrockruntime.model.ResourceNotFoundException -> {
                println("  → Model not found - verify model ID and region")
            }
        }
        
        // Custom recovery logic
        if (exception.message?.contains("timeout") == true) {
            println("  → Timeout detected - consider increasing timeout settings")
        }
    }
    .enableTracing()  // Also enable tracing for full debugging context

val result = runBlocking { pipe.execute("Your prompt here") }

// Exception function provides detailed debugging information
// while tracing shows the execution context when the exception occurred
```

### Debugging Performance Issues
```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("Analyze this large document.")
    .setContextWindowSize(100000)
    .autoTruncateContext()
    .enableTracing()  // Monitor timing and token usage
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")

val largeDocument = "..." // Large text content

val result = runBlocking { pipe.execute(largeDocument) }

// Trace output will show:
// - Token counting time
// - Context truncation time
// - API call duration
// - Total execution time
```

## Pipeline Tracing

### Multi-Stage Pipeline Debugging
```kotlin
val analysisPipeline = Pipeline()
    .enableTracing()  // Enable for entire pipeline
    .add(BedrockPipe()
        .setPipeName("DocumentAnalyzer")  // Named for easier tracing
        .setSystemPrompt("Analyze the document structure and content.")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
        .updatePipelineContextOnExit()
    )
    .add(BedrockPipe()
        .setPipeName("SummaryGenerator")
        .setSystemPrompt("Generate a summary based on the analysis.")
        .pullPipelineContext()
        .autoInjectContext("Use the analysis results.")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    )

val result = runBlocking { analysisPipeline.execute("Large document content...") }

// Trace output will show:
// - Each pipe's execution with names
// - Context flow between pipes
// - Pipeline-level timing information
```

### Pipeline Context Flow Tracing
```kotlin
val contextFlowPipeline = Pipeline()
    .enableTracing()
    .add(BedrockPipe()
        .setPipeName("ContextBuilder")
        .setSystemPrompt("Extract key information.")
        .setTransformationFunction { content ->
            // Add context for next pipe
            content.context.addLoreBookEntry("extractedInfo", content.text, weight = 10)
            content.context.contextElements.add("Processing stage: extraction")
            content
        }
        .updatePipelineContextOnExit()
    )
    .add(BedrockPipe()
        .setPipeName("ContextConsumer")
        .setSystemPrompt("Use extracted information to generate response.")
        .pullPipelineContext()
        .autoInjectContext("Use the extracted information.")
    )

// Trace will show context being built and consumed
```

## Advanced Tracing Patterns

### Custom Trace Metadata
```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .enableTracing()
    .setTransformationFunction { content ->
        // Add custom trace information
        trace(TraceEventType.TRANSFORMATION_START, TracePhase.TRANSFORMATION, content,
            metadata = mapOf(
                "customMetric" to "value",
                "processingStage" to "enhancement",
                "contentLength" to content.text.length.toString()
            ))
        
        // Process content
        content.text = enhanceContent(content.text)
        
        trace(TraceEventType.TRANSFORMATION_SUCCESS, TracePhase.TRANSFORMATION, content,
            metadata = mapOf("enhancementApplied" to "true"))
        
        content
    }
```

### Conditional Detailed Tracing
```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .setValidatorFunction { content ->
        val isValid = validateContent(content.text)
        
        // Only enable detailed tracing on failures
        if (!isValid) {
            enableTracing(TraceConfig().apply {
                enableConsoleOutput = true
                enableFileOutput = true
                outputFilePath = "debug/validation-failure-${System.currentTimeMillis()}.log"
                includeFullContent = true
            })
        }
        
        isValid
    }
```

### Performance Profiling
```kotlin
class PerformanceProfiler {
    private val timings = mutableMapOf<String, Long>()
    
    fun startTiming(operation: String) {
        timings["${operation}_start"] = System.currentTimeMillis()
    }
    
    fun endTiming(operation: String) {
        val start = timings["${operation}_start"] ?: return
        val duration = System.currentTimeMillis() - start
        timings[operation] = duration
        println("$operation took ${duration}ms")
    }
}

val profiler = PerformanceProfiler()

val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .enableTracing()
    .setPreValidationFunction { contextWindow, content ->
        profiler.startTiming("contextProcessing")
        // Process context
        profiler.endTiming("contextProcessing")
        contextWindow
    }
    .setTransformationFunction { content ->
        profiler.startTiming("transformation")
        // Transform content
        profiler.endTiming("transformation")
        content
    }
```

## Performance Analysis

### Token Usage Analysis
```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .enableTracing()
    .setTokenBudget(TokenBudgetSettings(
        maxTokens = 2000,
        contextWindowSize = 100000,
        userPromptSize = 1000
    ))
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")

// Trace output will include:
// - Input token count
// - Context token usage
// - Output token count
// - Token budget utilization
```

### API Call Performance
```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .enableTracing()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")

// Trace output will show:
// - API_CALL_START with timestamp
// - Request payload size
// - API_CALL_SUCCESS with response time
// - Response payload size
```

## Best Practices

### 1. Environment-Based Tracing
```kotlin
// Enable tracing based on environment
val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .apply {
        if (System.getenv("ENVIRONMENT") == "development") {
            enableTracing()
        }
    }
```

### 2. Structured Log Output
```kotlin
val traceConfig = TraceConfig().apply {
    enableFileOutput = true
    outputFilePath = "logs/tpipe-${LocalDate.now()}.log"
    enableJsonFormat = true  // Structured JSON logs
    includeTimestamps = true
    includeThreadInfo = true
}

val pipe = BedrockPipe()
    .enableTracing(traceConfig)
```

### 3. Selective Tracing
```kotlin
// Only trace specific operations
val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .setValidatorFunction { content ->
        val isValid = validateContent(content.text)
        
        // Only trace validation failures
        if (!isValid) {
            trace(TraceEventType.VALIDATION_FAILURE, TracePhase.VALIDATION, content,
                metadata = mapOf("reason" to "Content validation failed"))
        }
        
        isValid
    }
```

### 4. Production Tracing
```kotlin
// Minimal tracing for production
val productionTraceConfig = TraceConfig().apply {
    enableConsoleOutput = false
    enableFileOutput = true
    outputFilePath = "logs/production-errors.log"
    onlyLogErrors = true  // Only log failures and errors
    maxLogFileSize = 50 * 1024 * 1024  // 50MB max
}

val pipe = BedrockPipe()
    .enableTracing(productionTraceConfig)
```

### 5. Debug Information Cleanup
```kotlin
// Clean up sensitive information in traces
val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .enableTracing(TraceConfig().apply {
        contentFilter = { content ->
            // Remove sensitive information from traces
            content.replace(Regex("\\b\\d{4}-\\d{4}-\\d{4}-\\d{4}\\b"), "[CARD_NUMBER]")
                   .replace(Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"), "[EMAIL]")
        }
    })
```

TPipe's tracing and debugging capabilities provide comprehensive visibility into AI processing workflows, enabling effective troubleshooting, performance optimization, and system monitoring in agent systems.

## Recursive Tracing Propagation

TPipe automatically propagates tracing configuration through nested child pipes, ensuring complete visibility into complex hierarchical structures.

### How Recursive Propagation Works

When tracing is enabled on a parent pipe, TPipe recursively propagates the tracing configuration to all child pipes:

```kotlin
// Parent pipe with nested child pipes
val nestedReasoning = BedrockPipe("Nested-Reasoning")
val primaryReasoning = BedrockPipe("Primary-Reasoning")
    .setReasoningPipe(nestedReasoning)  // Nested reasoning pipe

val rootPipe = BedrockPipe("Root")
    .setReasoningPipe(primaryReasoning)
    .setValidatorPipe(BedrockPipe("Validator"))
    .enableTracing()  // Automatically propagates to ALL nested pipes

// All pipes (Root, Primary-Reasoning, Nested-Reasoning, Validator) will trace
val result = rootPipe.execute("Complex reasoning task")
```

### Nested Pipe Support

TPipe supports unlimited nesting depth for all child pipe types:
- **Reasoning pipes** can have their own reasoning pipes
- **Validator pipes** can have nested child pipes
- **Transformation pipes** can contain complex hierarchies
- **Branch pipes** can include nested processing chains

### Cycle Detection

TPipe includes automatic cycle detection to prevent infinite recursion:

```kotlin
val pipeA = BedrockPipe("Pipe-A")
val pipeB = BedrockPipe("Pipe-B")

// Create circular reference
pipeA.setReasoningPipe(pipeB)
pipeB.setReasoningPipe(pipeA)  // Circular reference

pipeA.enableTracing()  // Safe - cycle detection prevents infinite recursion
```

### Trace Correlation

All nested pipes share the same pipeline ID, ensuring traces are properly correlated:

```kotlin
val pipeline = Pipeline()
    .enableTracing()
    .add(rootPipeWithNestedChildren)

// All trace events from nested pipes appear in the same trace report
val traceReport = pipeline.getTraceReport(TraceFormat.HTML)
// Contains chronologically ordered events from all nesting levels
```

### Benefits

- **Complete visibility** into nested pipe hierarchies
- **Automatic propagation** - no manual configuration needed
- **Chronological ordering** maintained across all nesting levels
- **Cycle protection** prevents infinite recursion
- **Unified trace reports** for complex structures

## Remote Trace Dispatch

TPipe can automatically push trace reports to a remote [TraceServer](../advanced-concepts/trace-server.md) so you can view execution traces from a centralized web dashboard instead of inspecting local files or console output.

### RemoteTraceConfig

`RemoteTraceConfig` is a singleton that controls where and how traces are dispatched:

```kotlin
import com.TTT.Debug.RemoteTraceConfig

// Point at your running TraceServer instance
RemoteTraceConfig.remoteServerUrl = "http://localhost:8081"

// Enable automatic dispatch on every exportTrace() call
RemoteTraceConfig.dispatchAutomatically = true

// Optional: set auth header for agent authentication
RemoteTraceConfig.authHeader = "Bearer my-agent-secret"
```

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `remoteServerUrl` | `String?` | `null` | TraceServer URL. Dispatch is skipped when null. |
| `dispatchAutomatically` | `Boolean` | `false` | When true, `PipeTracer.exportTrace()` also dispatches remotely. |
| `authHeader` | `String?` | `null` | Manual `Authorization` header. When null, resolved from `AuthRegistry`. |

### How Automatic Dispatch Works

When `dispatchAutomatically` is `true`, every call to `PipeTracer.exportTrace(pipelineId, format)` does two things:

1. Returns the formatted trace string as usual (JSON, HTML, Markdown, or Console)
2. Fires an async HTTP POST to `remoteServerUrl/api/traces` with the HTML version of the trace

The HTTP call runs on `Dispatchers.IO` and does not block the calling coroutine or thread.

### Authentication Resolution

`RemoteTraceDispatcher` resolves the `Authorization` header in this order:

1. **Explicit header** — uses `RemoteTraceConfig.authHeader` if set
2. **AuthRegistry lookup** — calls `AuthRegistry.getToken(remoteServerUrl)` and wraps it as `"Bearer <token>"`
3. **No auth** — sends the request without an `Authorization` header

This means you can register your TraceServer token once via `TPipeConfig.addRemoteAuth()` and all remote services (TraceServer, MemoryServer, PCP executors) will resolve it automatically:

```kotlin
import com.TTT.Config.TPipeConfig

TPipeConfig.addRemoteAuth("http://localhost:8081", "my-agent-secret")
```

See [TPipeConfig API — AuthRegistry](../api/tpipe-config.md#authregistry) for details on the unified authentication model.

### Manual Dispatch

You can dispatch a trace at any point without enabling automatic dispatch:

```kotlin
import com.TTT.Debug.RemoteTraceDispatcher

RemoteTraceDispatcher.dispatchTrace(
    pipelineId = "my-pipeline-run",
    name = "Research Task",
    status = "SUCCESS"
)
```

### End-to-End Example

```kotlin
import com.TTT.Debug.RemoteTraceConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.PipeTracer

// Configure remote dispatch
RemoteTraceConfig.remoteServerUrl = "http://trace-dashboard:8081"
RemoteTraceConfig.dispatchAutomatically = true
RemoteTraceConfig.authHeader = "Bearer agent-token"

// Build and run a traced pipeline
val pipeline = Pipeline()
    .enableTracing(TraceConfig(enabled = true))
    .add(myPipe)

pipeline.init()
val result = runBlocking { pipeline.execute("Hello") }

// This exports locally AND dispatches to TraceServer
val html = PipeTracer.exportTrace(pipeline.pipelineId, TraceFormat.HTML)
```

For full TraceServer setup including server configuration, dashboard authentication, and WebSocket streaming, see **[TraceServer - Remote Trace Dashboard](../advanced-concepts/trace-server.md)**.

## Next Steps

Now that you understand monitoring and troubleshooting, explore AWS Bedrock integration:

**→ [Getting Started with TPipe-Bedrock](../bedrock/getting-started.md)** - Setup, configuration, and first steps
