# Timeout and Retry System

TPipe provides a timeout and retry system that protects pipelines from hanging LLM calls and enables automatic recovery from transient failures. The system uses coroutine-based timers, snapshot-based state restoration, and configurable retry strategies.

## Table of Contents
- [Core Components](#core-components)
- [Configuration](#configuration)
- [How it Works](#how-it-works)
- [Retry Strategies](#retry-strategies)
- [Side Effects and Idempotency](#side-effects-and-idempotency)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## Core Components

### PipeTimeoutStrategy
Three strategies control timeout behavior:
*   **`Fail`**: Terminate the mainline immediately on timeout (default).
*   **`Retry`**: Automatically restore a snapshot and retry the call.
*   **`CustomLogic`**: Decided by a developer-defined Kotlin function.

### PipeTimeoutManager
A singleton object that tracks active timers using coroutine Jobs. It maintains thread-safe retry counters and handles the signals required to abort or restart a pipe.

---

## Configuration

### Pipe-Level Configuration
```kotlin
pipe.enablePipeTimeout(
    applyRecursively = true,    // Propagate settings to child pipes/inspectors
    duration = 300000,          // Timeout in milliseconds (5 min default)
    autoRetry = true,           // Enable automatic retry
    retryLimit = 5              // Max retry attempts before permanent failure
)
```

### Pipeline-Level Configuration
Pipeline configuration propagates to all valves in the mainline during the `init()` phase.
```kotlin
pipeline.enablePipeTimeout(autoRetry = true, retryLimit = 3)
```

---

## How it Works

1.  **Initialization**: `PipeTimeoutManager` starts a coroutine timer when the pipe begins.
2.  **Snapshot**: TPipe takes a deep-copy snapshot of the `MultimodalContent`.
3.  **Execution**: The model API call executes while the timer runs concurrently.
4.  **Timeout**: If the timer expires, TPipe calls `pipe.abort()` to cancel the active network job.
5.  **Recovery**: If `Retry` is enabled and attempts are remaining:
    - The system retrieves the preserved snapshot.
    - It set `repeatPipe = true`.
    - The pipe executes again from the beginning with the original state.
6.  **Cleanup**: Once the call succeeds or permanently fails, the timer is stopped and counters are reset.

---

## Retry Strategies

### 1. Automatic Retry
Simplest approach—automatically attempts the call again with the exact same parameters.
- **Use when**: Dealing with transient network failures or unstable LLM endpoints.

### 2. Custom Logic
Allows you to analyze the failure and decide if a retry is appropriate.
```kotlin
pipe.enablePipeTimeout(
    customLogic = { pipe, content ->
        println("Mainline clogged. Waiting 5 seconds...")
        delay(5000) // Industrial backoff
        true // Return true to signal a retry
    }
)
```
- **Use when**: Implementing exponential backoff or switching models on failure.

---

## ⚠️ Side Effects and Idempotency

**IMPORTANT**: A retry re-executes the ENTIRE pipe from the beginning, including all pre-execution Developer-in-the-Loop functions:
- `preInitFunction`
- `preValidationFunction`
- `preValidationMiniBankFunction`

> [!CAUTION]
> **Duplicate Writes**: If your pre-validation function writes to a database or updates the `ContextBank`, a retry will cause that write to happen again. **Always ensure your pre-flow functions are Read-Only or Idempotent.**

---

## Best Practices

*   **Set Realistic Limits**: Don't set a 5-second timeout for a 100,000-token generation; give the model enough pipe length to finish.
*   **Use SNAPSHOTS**: If you are using complex branching, call `.forceSaveSnapshot()` to ensure you always have a recovery point.
*   **Recursive Propagation**: Set `applyRecursively = true` so that your timeout protection also covers validator and transformation pipes.
*   **Monitor the Gauges**: Use `PipeTracer` to see exactly how many times a valve retried and how much latency each attempt added to the total flow.

---

## Next Steps

Now that your system can handle hanging valves, learn how to monitor the healthy flow of your infrastructure.

**→ [Tracing and Debugging](tracing-and-debugging.md)** - Monitoring and troubleshooting the infrastructure.
