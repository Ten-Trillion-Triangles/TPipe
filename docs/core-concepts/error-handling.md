# Error Handling and Propagation - Fixing Leaks

In any industrial plumbing system, you have to expect leaks, bursts, and pressure drops. TPipe's **Error Handling** system is the diagnostic kit that tells you exactly where the failure happened, what phase the pipe was in, and why the flow stopped.

Instead of just getting an empty response, TPipe provides deep, programmatic visibility into the failure chain, from the individual valve to the entire mainline.

## The Three Levels of Diagnostic Flow

Errors in TPipe aren't just thrown; they flow. When a failure occurs, diagnostic data is captured and propagated through three levels:

1.  **Valve Level (Pipe)**: Stored in `pipe.lastError`.
2.  **Water Level (Content)**: Attached to `MultimodalContent.pipeError`.
3.  **Mainline Level (Pipeline)**: Captured in `pipeline.lastError` and `pipeline.lastFailedPipe`.

---

## Basic Diagnostics: Checking the Gauges

### Checking a Single Pipe
```kotlin
val result = pipe.execute("Process data")

if (pipe.hasError()) {
    println("Status: Burst!")
    println("Message: ${pipe.getErrorMessage()}")
    println("Phase: ${pipe.lastError?.phase}") // e.g., API_CALL, VALIDATION
}
```

### Checking the Mainline (Pipeline)
When a pipeline fails, you need to know exactly which section "burst."

```kotlin
pipeline.execute("Start the flow")

if (pipeline.hasError()) {
    println("Mainline Failure at: ${pipeline.getFailedPipeName()}")
    println("Context: ${pipeline.getFullErrorContext()}")
    
    // Direct access to the failed valve and the error cargo
    val valve = pipeline.lastFailedPipe
    val diagnostic = pipeline.lastError
}
```

---

## Common Failure Types (Trace Events)

TPipe categorizes errors so you can handle them differently:

| Error Type | Meaning | Action |
| :--- | :--- | :--- |
| **`API_CALL_FAILURE`** | The model provider (e.g., AWS) failed. | Retry, check credentials, or check region. |
| **`VALIDATION_FAILURE`** | The output didn't meet your "Plumbing" specs. | Adjust the prompt or use a fallback. |
| **`TRANSFORMATION_FAILURE`** | Your post-processing code crashed. | Debug your transformation function. |
| **`PIPE_FAILURE`** | A general infrastructure failure. | Check system logs. |

---

## Advanced Patterns: Rerouting the Flow

### The Fallback Mainline
You can build a pipeline with "redundant lines." If the primary pipe fails, the pipeline can be designed to handle the error and provide a fallback.

```kotlin
pipeline.execute(input)

if (pipeline.hasError()) {
    when (pipeline.getFailedPipeName()) {
        "primary_auditor" -> {
            println("Primary line failed. Checking fallback results...")
        }
        "fallback_auditor" -> {
            println("Total system failure. Shutting down flow.")
        }
    }
}
```

### Intelligent Retries
If you detect an `API_CALL_FAILURE`, it might just be a transient network "clog." You can use the error type to decide whether to retry.

```kotlin
if (pipe.getErrorType() == TraceEventType.API_CALL_FAILURE) {
    println("Transient clog detected. Retrying with higher pressure...")
    pipe.clearError() // Reset the gauge
    pipe.execute(input)
}
```

---

## Best Practices for a Robust Infrastructure

*   **Check the Gauges Early**: Always check `pipeline.hasError()` after execution before you try to consume the results.
*   **Name Your Pipes**: Give every Pipe a `pipeName`. An error in "Pipe 3" is much harder to find than an error in the "SQL_Generator_Valve."
*   **Log the Context**: Use `getFullErrorContext()` in your production logs. It contains the Pipe Name, the Phase, and the Message in one concise string.
*   **Clear Before Reuse**: If you are reusing a Pipe or Pipeline object, call `clearError()` to reset the gauges before the next run.

---

## Next Steps

Now that you can handle failures, learn how to monitor the healthy flow of your system.

**→ [Tracing and Debugging](tracing-and-debugging.md)** - Monitoring and troubleshooting the infrastructure.
