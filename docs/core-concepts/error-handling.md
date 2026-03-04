# Error Handling and Propagation

TPipe provides comprehensive error handling that captures failure information at the pipe level and propagates it through pipelines. This allows for detailed programmatic access to error details, execution phases, and failure types, ensuring robust fault management in production systems.

## Table of Contents
- [The Three Levels of Diagnostic Flow](#the-three-levels-of-diagnostic-flow)
- [Key Error Types](#key-error-types)
- [Basic Usage](#basic-usage)
- [Advanced Error Patterns](#advanced-error-patterns)
- [The PipeError Data Class](#the-pipeerror-data-class)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## The Three Levels of Diagnostic Flow

Errors in TPipe aren't just thrown; they flow through three distinct levels of the infrastructure:

1.  **Valve Level (Pipe)**: Stored in `pipe.lastError`.
2.  **Water Level (Content)**: Attached to `MultimodalContent.pipeError`.
3.  **Mainline Level (Pipeline)**: Captured in `pipeline.lastError` and `pipeline.lastFailedPipe`.

---

## Key Error Types

Errors are categorized by `TraceEventType` to help you determine the correct recovery action:

| Error Type | Meaning | Recommended Action |
| :--- | :--- | :--- |
| **`API_CALL_FAILURE`** | The model provider (e.g., AWS) failed. | Retry with backoff, check credentials, or verify region. |
| **`VALIDATION_FAILURE`** | The output didn't meet your specifications. | Adjust the prompt or trigger a fallback branch. |
| **`TRANSFORMATION_FAILURE`** | Your post-processing logic crashed. | Debug your transformation function. |
| **`PIPE_FAILURE`** | A general infrastructure failure. | Check system logs and resource availability. |

---

## Basic Usage

### Checking a Single Pipe
```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")

val result = pipe.execute("What is AI?")

if (pipe.hasError()) {
    println("Error: ${pipe.getErrorMessage()}")
    println("Type: ${pipe.getErrorType()}")
    println("Phase: ${pipe.lastError?.phase}")
}
```

### Checking the Mainline (Pipeline)
```kotlin
pipeline.execute("Start the flow")

if (pipeline.hasError()) {
    println("Mainline failure at: ${pipeline.getFailedPipeName()}")
    println("Context: ${pipeline.getFullErrorContext()}")
    
    // Access the failed pipe directly
    val failedValve = pipeline.lastFailedPipe
    // Access the detailed error cargo
    val errorInfo = pipeline.lastError
}
```

---

## Advanced Error Patterns

### The Fallback Mainline
You can identify exactly which pipe failed and decide whether to proceed with a fallback result.

```kotlin
pipeline.execute(input)

if (pipeline.hasError()) {
    when (pipeline.getFailedPipeName()) {
        "primary_auditor" -> println("Primary failed. Fallback result is available.")
        "fallback_auditor" -> println("Complete system blockage: ${pipeline.getErrorMessage()}")
    }
}
```

### Conditional Logic by Error Type
```kotlin
if (pipe.hasError()) {
    when (pipe.getErrorType()) {
        TraceEventType.API_CALL_FAILURE -> retryWithBackoff(pipe)
        TraceEventType.VALIDATION_FAILURE -> logger.warn("Valve leak: ${pipe.getErrorMessage()}")
        TraceEventType.TRANSFORMATION_FAILURE -> useRawOutput()
        else -> handleGenericError()
    }
}
```

---

## The PipeError Data Class

The `PipeError` object contains the comprehensive diagnostic cargo:

```kotlin
data class PipeError(
    val exception: Throwable?,      // Original exception details
    val eventType: TraceEventType,  // Failure category
    val phase: TracePhase,          // Execution phase (ENTER, EXECUTE, EXIT, etc.)
    val pipeName: String,           // Human-readable valve name
    val pipeId: String,             // Unique system identifier
    val timestamp: Long             // Occurrence time
) {
    val message: String             // Formatted error message
}
```

---

## Best Practices

*   **Always Check for Errors**: After pipeline execution, verify `pipeline.hasError()` before processing the resulting data.
*   **Name Your Pipes**: Use `.setPipeName()` so your logs identify exactly which valve failed (e.g., "SQL_Generator").
*   **Clear Before Reuse**: If reusing a Pipe or Pipeline object, call `clearError()` or `clearErrors()` to reset the gauges.
*   **Log Full Context**: Use `getFullErrorContext()` for comprehensive production logging.

---

## Next Steps

Now that you can handle failures, learn how to monitor the healthy flow of your system.

**→ [Tracing and Debugging](tracing-and-debugging.md)** - Monitoring and troubleshooting the infrastructure.
