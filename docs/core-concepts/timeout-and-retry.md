# Timeout and Retry System - Pressure Relief and Recovery

In any industrial plumbing system, you have to account for "clogs" or "hanging valves." Sometimes an AI provider takes too long to respond, or a network burst causes a temporary blockage. TPipe's **Timeout and Retry System** is the pressure relief valve that prevents your entire mainline from freezing when a single pipe hangs.

It automatically monitors the "flow rate" (response time) and can perform a System Reset (retry) using saved snapshots if a pipe exceeds its time limit.

## Why use Timeout and Retry?

*   **Prevent Freezing**: Don't let a single slow API call hang your entire application.
*   **Automatic Recovery**: Recover from transient network errors or rate limits without manual intervention.
*   **Deterministic Reliability**: Set strict "Service Level Agreements" (SLAs) for every stage of your pipeline.
*   **Recursive Protection**: Apply timeout rules once at the pipeline level and have them "leak down" to every valve and inspector.

---

## Configuration: Setting the Time Limit

You can enable timeouts on a single Pipe or across an entire Pipeline.

```kotlin
pipe.enablePipeTimeout(
    duration = 30000,   // 30 seconds limit
    autoRetry = true,    // Automatically try again on timeout
    retryLimit = 3       // Try up to 3 times before failing
)
```

### The Three Recovery Strategies:
1.  **Fail (Default)**: If the pipe hangs, the valve shuts, and the pipeline terminates with an error.
2.  **Retry**: TPipe restores the content to its Pre-flow state (via a snapshot) and tries the call again.
3.  **CustomLogic**: You provide a Kotlin function to decide whether to retry, wait, or switch to a different model.

---

## How it Works: The Snapshot System

For a **Retry** to work, TPipe needs to know what the "water" looked like before it entered the pipe. When auto-retry is enabled, TPipe automatically takes a **Snapshot** of your `MultimodalContent`.

1.  **Snapshot**: Content state is saved.
2.  **Flow**: The model call begins.
3.  **Timeout**: The timer expires; the active call is "aborted."
4.  **Reset**: The Snapshot is restored, and the pipe executes again from the beginning.

---

## ⚠️ Critical Warning: Side Effects

Because a **Retry** re-executes the pipe from the very beginning, it also re-runs your **Pre-execution DITL functions** (like `preInit` or `preValidation`).

> [!CAUTION]
> **Duplicate Writes**: If your pre-validation function writes to a database or updates the `ContextBank`, a retry will cause that write to happen again. **Always ensure your pre-flow functions are Read-Only or Idempotent.**

---

## Advanced: Custom Recovery Logic

Sometimes you don't want to retry immediately. You might want to wait for the "pressure" to subside (exponential backoff) or try a different "refinery" (provider).

```kotlin
pipe.enablePipeTimeout(
    customLogic = { pipe, content ->
        println("Mainline clogged. Waiting 5 seconds before retry...")
        delay(5000)
        true // Return true to signal a retry
    }
)
```

---

## Monitoring the Recovery

Every retry event is logged in the **Pressure Gauges (Tracing)**. You can see exactly how many times a pipe retried and how long each attempt took.

```kotlin
// Check the retry count manually if needed
val attempts = PipeTimeoutManager.getRetryCount(pipe)
```

---

## Best Practices

*   **Set Realistic Limits**: Don't set a 5-second timeout for a 100,000-token generation; give the model enough "pipe length" to finish.
*   **Use SNAPSHOTS**: If you are using complex branching, call `.forceSaveSnapshot()` to ensure you always have a recovery point.
*   **Offload Writes**: Perform database or ContextBank writes in **Post-execution** functions (`transformationFunction`) to ensure they only happen after a successful, non-timed-out generation.

---

## Next Steps

Now that your system can handle hanging valves, learn how to monitor the healthy flow of your infrastructure.

**→ [Tracing and Debugging](tracing-and-debugging.md)** - Monitoring and troubleshooting the infrastructure.
