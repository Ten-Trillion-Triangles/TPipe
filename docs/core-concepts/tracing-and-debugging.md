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
    .setSystemPrompt("You are a helpful assistant.")
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
    .setSystemPrompt("You are a helpful assistant.")
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
    .setSystemPrompt("You are a helpful assistant.")
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
    .setSystemPrompt("You are a helpful assistant with access to context.")
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
val pipe = BedrockPipe()
    .setSystemPrompt("Generate a JSON response.")
    .setJsonOutput(UserProfile("", "", 0))
    .requireJsonPromptInjection()
    .setValidatorFunction { content ->
        val isValid = try {
            Json.decodeFromString<UserProfile>(content.text)
            true
        } catch (e: Exception) {
            false
        }
        isValid
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
    .setSystemPrompt("You are a helpful assistant.")
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
    .setSystemPrompt("You are a helpful assistant.")
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
    .setSystemPrompt("You are a helpful assistant.")
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
    .setSystemPrompt("You are a helpful assistant.")
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
    .setSystemPrompt("You are a helpful assistant.")
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
    .setSystemPrompt("You are a helpful assistant.")
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
    .setSystemPrompt("You are a helpful assistant.")
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
    .setSystemPrompt("You are a helpful assistant.")
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
    .setSystemPrompt("You are a helpful assistant.")
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

## Next Steps

Now that you understand monitoring and troubleshooting, explore AWS Bedrock integration:

**→ [Getting Started with TPipe-Bedrock](../bedrock/getting-started.md)** - Setup, configuration, and first steps
