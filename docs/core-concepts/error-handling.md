# Error Handling and Propagation

## Overview

TPipe provides comprehensive error handling that captures failure information at the pipe level and propagates it through pipelines. This allows programmatic access to error details instead of just receiving empty content.

## Key Concepts

### Error Capture
Errors are automatically captured when pipes fail during execution. The error information includes:
- Exception details
- Failure type (API call, validation, transformation, etc.)
- Execution phase when failure occurred
- Pipe identification
- Timestamp

### Error Propagation
Errors flow through three levels:
1. **Pipe Level**: Stored in `pipe.lastError`
2. **Content Level**: Attached to `MultimodalContent.pipeError`
3. **Pipeline Level**: Captured in `pipeline.lastError` and `pipeline.lastFailedPipe`

## Basic Usage

### Checking Pipe Errors

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")

val result = pipe.execute("What is AI?")

if (pipe.hasError()) {
    println("Error: ${pipe.getErrorMessage()}")
    println("Type: ${pipe.getErrorType()}")
}
```

### Checking Pipeline Errors

```kotlin
val pipeline = Pipeline()
pipeline.add(pipe1)
pipeline.add(pipe2)
pipeline.add(pipe3)

pipeline.execute("input")

if (pipeline.hasError()) {
    println("Failed at: ${pipeline.getFailedPipeName()}")
    println(pipeline.getFullErrorContext())
    
    // Access the failed pipe
    val failedPipe = pipeline.lastFailedPipe
    // Access detailed error
    val error = pipeline.lastError
}
```

### Checking Content Errors

```kotlin
val content = MultimodalContent("input")
val result = pipe.execute(content)

if (result.hasError()) {
    val error = result.pipeError
    println("Pipe: ${error?.pipeName}")
    println("Phase: ${error?.phase}")
    println("Message: ${error?.message}")
}
```

## Error Types

Errors are categorized by `TraceEventType`:

- **PIPE_FAILURE**: General pipe execution failure
- **API_CALL_FAILURE**: LLM API call failed (network, auth, rate limit)
- **VALIDATION_FAILURE**: Validation function rejected output
- **TRANSFORMATION_FAILURE**: Transformation function failed

## Error Information

The `PipeError` data class contains:

```kotlin
data class PipeError(
    val exception: Throwable?,      // Original exception (transient)
    val eventType: TraceEventType,  // Type of failure
    val phase: TracePhase,          // When it failed
    val pipeName: String,           // Which pipe failed
    val pipeId: String,             // Unique pipe identifier
    val timestamp: Long             // When it happened
) {
    val message: String             // Human-readable error message
}
```

## Advanced Patterns

### Error Recovery in Pipelines

```kotlin
val pipeline = Pipeline()
pipeline.add(primaryPipe)
pipeline.add(fallbackPipe)

pipeline.execute("input")

if (pipeline.hasError()) {
    // Identify which pipe failed
    when (pipeline.getFailedPipeName()) {
        "primaryPipe" -> {
            // Primary failed, fallback should have run
            println("Primary failed, fallback result available")
        }
        "fallbackPipe" -> {
            // Both failed
            println("Complete failure: ${pipeline.getErrorMessage()}")
        }
    }
}
```

### Conditional Logic Based on Error Type

```kotlin
if (pipe.hasError()) {
    when (pipe.getErrorType()) {
        TraceEventType.API_CALL_FAILURE -> {
            // Retry with exponential backoff
            retryWithBackoff(pipe)
        }
        TraceEventType.VALIDATION_FAILURE -> {
            // Log validation issue
            logger.warn("Validation failed: ${pipe.getErrorMessage()}")
        }
        TraceEventType.TRANSFORMATION_FAILURE -> {
            // Use raw output instead
            useRawOutput()
        }
        else -> {
            // Generic error handling
            handleGenericError()
        }
    }
}
```

### Error Clearing for Pipe Reuse

```kotlin
val pipe = BedrockPipe()

// First execution fails
pipe.execute("bad input")
assert(pipe.hasError())

// Clear error before reuse
pipe.clearError()

// Second execution succeeds
pipe.execute("good input")
assert(!pipe.hasError())
```

### Accessing Detailed Error Context

```kotlin
if (pipeline.hasError()) {
    val error = pipeline.lastError
    
    println("Pipe: ${error?.pipeName} (${error?.pipeId})")
    println("Failed in: ${error?.phase}")
    println("Error type: ${error?.eventType}")
    println("Time: ${error?.timestamp}")
    println("Message: ${error?.message}")
    
    // Access original exception if available
    error?.exception?.let { ex ->
        println("Stack trace:")
        ex.printStackTrace()
    }
}
```

## Integration with Tracing

Error capture works seamlessly with TPipe's tracing system. When tracing is enabled, errors are captured in both the trace events and the error properties:

```kotlin
val pipe = BedrockPipe()
    .enableTracing(TraceConfig(enabled = true))

pipe.execute("input")

// Error available in pipe
if (pipe.hasError()) {
    println(pipe.getErrorMessage())
}

// Also available in trace
val trace = PipeTracer.getTrace(pipelineId)
val failures = trace.filter { it.eventType == TraceEventType.PIPE_FAILURE }
```

## Best Practices

1. **Always Check for Errors**: After pipeline execution, check `pipeline.hasError()` before processing results
2. **Use Specific Error Types**: Check `getErrorType()` to handle different failure modes appropriately
3. **Clear Errors on Reuse**: Call `clearError()` or `clearErrors()` when reusing pipes/pipelines
4. **Log Full Context**: Use `getFullErrorContext()` for comprehensive error logging
5. **Preserve Error Information**: Store `lastError` before clearing if you need it later

## Backward Compatibility

Error handling is fully backward compatible:
- Existing code continues to work without changes
- Empty content is still returned on failure
- Error information is additional, not required
- No performance impact when errors don't occur

## See Also

- [Pipe Class API](../api/pipe.md#error-handling)
- [Pipeline Class API](../api/pipeline.md#error-handling)
- [MultimodalContent API](../api/multimodal-content.md)
- [Tracing and Debugging](tracing-and-debugging.md)
