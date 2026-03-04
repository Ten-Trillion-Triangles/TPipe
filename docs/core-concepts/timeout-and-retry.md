# Timeout and Retry System

> 💡 **Tip:** Timeout and retry mechanisms are your pressure relief valves. They ensure transient failures or blocked pipes don't take down the entire system.


## Overview

TPipe provides a timeout and retry system that protects pipelines from hanging LLM calls and enables automatic recovery from transient failures. The system uses coroutine-based timers, snapshot-based state restoration, and configurable retry strategies.

## Core Components

### PipeTimeoutStrategy

Three strategies control timeout behavior:

```kotlin
enum class PipeTimeoutStrategy {
    Fail,        // Terminate immediately on timeout (default)
    Retry,       // Automatically retry with snapshot restoration
    CustomLogic  // Developer-defined retry handling
}
```

### PipeTimeoutManager

Singleton object managing timeout tracking and retry attempts:

- Tracks active timers per pipe using coroutine Jobs
- Maintains retry attempt counters (thread-safe ConcurrentHashMap)
- Handles timeout signals and determines retry actions
- Manages snapshot restoration for retry attempts

## Configuration

### Pipe-Level Configuration

```kotlin
pipe.enablePipeTimeout(
    applyRecursively = true,    // Propagate to child pipes
    duration = 300000,          // Timeout in milliseconds (5 min default)
    autoRetry = true,           // Enable automatic retry
    retryLimit = 5              // Max retry attempts
)
```

### Pipeline-Level Configuration

```kotlin
pipeline.enablePipeTimeout(
    applyRecursively = true,
    duration = 300000,
    autoRetry = true,
    retryLimit = 5
)
```

Pipeline configuration propagates to all pipes during `init()`.

### Direct Property Access

```kotlin
pipe.enablePipeTimeout = true
pipe.pipeTimeout = 60000L           // 1 minute
pipe.timeoutStrategy = PipeTimeoutStrategy.Retry
pipe.maxRetryAttempts = 3
```

## How It Works

### Execution Flow

1. **Initialization**: `PipeTimeoutManager.startTracking()` starts coroutine timer
2. **Snapshot**: If retry enabled, `inputContent.saveSnapshot()` preserves state
3. **Execution**: LLM API call executes while timer runs concurrently
4. **Timeout**: Timer expires, calls `pipe.abort()` to cancel active job
5. **Signal Handling**: `handleTimeoutSignal()` checks retry eligibility
6. **Retry Decision**: Compares current attempts vs `maxRetryAttempts`
7. **State Restoration**: `getSnapshot()` retrieves preserved state
8. **Retry Loop**: `repeatPipe = true` triggers `execute()` while loop
9. **Re-execution**: `executeMultimodal()` called again with restored snapshot
10. **Cleanup**: `stopTracking()` cancels timer, `clearRetryCount()` resets counter

### Retry Loop Mechanism

```kotlin
suspend fun execute(content: MultimodalContent): MultimodalContent {
    var result = executeMultimodal(content)
    while (result.repeatPipe) {
        result = executeMultimodal(result)
    }
    return result
}
```

The `repeatPipe` flag in `MultimodalContent` controls retry iteration.

### Snapshot System

Retry requires state preservation:

```kotlin
// Automatic snapshot on retry-enabled pipes
inputContent.saveSnapshot()  // Stores deep copy in metadata

// Restoration on timeout
val snapshot = content.getSnapshot()
snapshot.repeatPipe = true
```

Without a snapshot, retry fails immediately with error.

## Retry Strategies

### Automatic Retry

Simplest approach - automatically retries on timeout:

```kotlin
pipe.enablePipeTimeout(
    autoRetry = true,
    retryLimit = 3,
    duration = 60000
)
```

**Behavior:**
- Restores snapshot on timeout
- Increments retry counter
- Re-executes from beginning
- Fails after exhausting attempts

### Custom Logic

Developer-controlled retry decisions:

```kotlin
pipe.enablePipeTimeout(
    customLogic = { pipe, content ->
        // Custom retry decision logic
        val shouldRetry = analyzeFailure(content)
        if (shouldRetry) {
            repairContent(content)
        }
        shouldRetry
    }
)
```

**Use cases:**
- Conditional retry based on error type
- Content repair before retry
- External system checks
- Dynamic retry limit adjustment

### Fail Strategy

Default behavior - no retry:

```kotlin
pipe.enablePipeTimeout(duration = 60000)
// timeoutStrategy defaults to Fail
```

Pipe terminates immediately on timeout.

## Integration with Error Handling

### Execution Order

Retry executes BEFORE normal error handling:

1. Timeout occurs → Retry system activates
2. Retry exhausted → Validation functions execute
3. Validation fails → Failure functions execute

### Bypass Behavior

When retry triggers:
- Returns early from `executeMultimodal()`
- Bypasses validation, transformation, failure functions
- Re-enters execution from beginning with restored state

Only after retry succeeds or exhausts do normal flows proceed.

### Exception Handling

```kotlin
catch(e: Exception) {
    if (e is CancellationException && PipeTimeoutManager.isTimeout(this)) {
        // Retry system handles this
        val result = PipeTimeoutManager.handleTimeoutSignal(this, inputContent)
        return result
    }
    // Other exceptions go to exceptionFunction
    exceptionFunction?.invoke(processedContent, e)
}
```

Timeout exceptions handled exclusively by retry system.

## Recursive Propagation

### Child Pipe Inheritance

When `applyRecursively = true`, settings propagate to:
- Validator pipes
- Branch pipes
- Transformation pipes
- Reasoning pipes

```kotlin
pipe.enablePipeTimeout(applyRecursively = true, autoRetry = true)
pipe.setBranchPipe(childPipe)
pipe.init()  // childPipe inherits timeout/retry settings
```

### Configuration Inheritance

Child pipes receive:
- `enablePipeTimeout`
- `pipeTimeout` duration
- `timeoutStrategy`
- `maxRetryAttempts`
- `pipeRetryFunction`
- `applyTimeoutRecursively`

## Tracing and Monitoring

### Trace Events

```kotlin
TraceEventType.PIPE_RETRY  // Logged on each retry attempt
```

Includes metadata:
- Current attempt number
- Pipe name
- Timeout duration

### Monitoring Retry Attempts

```kotlin
val attempts = PipeTimeoutManager.getRetryCount(pipe)
println("Pipe has retried $attempts times")
```

## Critical Warnings

### ⚠️ Pre-Execution DITL Function Side Effects

**IMPORTANT:** Retry re-executes the ENTIRE pipe from the beginning, including all pre-execution Developer-in-the-Loop functions:

- `preInitFunction`
- `preValidationFunction`
- `preValidationMiniBankFunction`

**Footgun:** If these functions write to program memory or ContextBank, retry will execute those writes multiple times, causing:
- Duplicate data in ContextBank
- Corrupted application state
- Unexpected memory modifications
- Non-idempotent behavior

**Example of problematic usage:**

```kotlin
pipe.preValidationFunction = { context, content ->
    // ❌ BAD: Writes to ContextBank on every execution
    ContextBank.addToBank("key", someData)
    context
}

pipe.enablePipeTimeout(autoRetry = true, retryLimit = 3)
// If timeout occurs, ContextBank.addToBank() runs 3+ times!
```

**Correct usage:**

```kotlin
pipe.preValidationFunction = { context, content ->
    // ✅ GOOD: Read-only operations
    val data = ContextBank.getContextFromBank("key")
    context.addEntry("retrieved", data.toString())
    context
}
```

**Design Intent:** Pre-execution DITL functions are designed for:
- Reading and retrieving data
- Context preparation and filtering
- Input validation and preprocessing
- Non-destructive transformations

They are NOT intended for:
- Writing to ContextBank
- Modifying global program state
- Database writes
- File system modifications
- Any side effects that shouldn't repeat

**Mitigation:** If you must perform side effects before LLM execution, use idempotent operations or check state before writing:

```kotlin
pipe.preValidationFunction = { context, content ->
    // Check before writing
    if (!ContextBank.hasKey("key")) {
        ContextBank.addToBank("key", someData)
    }
    context
}
```

## Best Practices

### When to Use Retry

- Transient network failures
- Rate limiting scenarios
- Unstable LLM endpoints
- Long-running operations prone to timeouts

### When to Use Custom Logic

- Conditional retry based on error analysis
- Content repair before retry
- Integration with external monitoring
- Dynamic timeout adjustment

### Snapshot Considerations

- Snapshots use deep copy - memory overhead for large content
- Automatic snapshot only created when retry enabled
- Manual snapshot via `content.saveSnapshot()` if needed
- Snapshot stored in `metadata["snapshot"]`

### Performance Impact

- Timer runs as lightweight coroutine (minimal overhead)
- Retry adds latency (full re-execution)
- Consider retry limit vs timeout duration tradeoff
- Monitor retry frequency to detect systemic issues

### DITL Function Guidelines

- Keep pre-execution functions read-only
- Perform writes in post-execution functions (`postGenerateFunction`, `transformationFunction`)
- Use idempotent operations if writes are unavoidable
- Document any side effects clearly

## Examples

### Basic Automatic Retry

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .enablePipeTimeout(
        autoRetry = true,
        retryLimit = 3,
        duration = 60000  // 1 minute
    )

val result = pipe.execute("Analyze this data...")
```

### Pipeline-Wide Retry

```kotlin
val pipeline = Pipeline()
    .enablePipeTimeout(
        applyRecursively = true,
        autoRetry = true,
        retryLimit = 5,
        duration = 120000  // 2 minutes
    )
    .addPipe(pipe1)
    .addPipe(pipe2)
    .init()

val result = pipeline.execute("Process this...")
```

### Custom Retry Logic

```kotlin
pipe.enablePipeTimeout(
    duration = 60000,
    customLogic = { pipe, content ->
        val errorType = analyzeTimeout(pipe)
        when (errorType) {
            TimeoutType.NETWORK -> {
                delay(5000)  // Wait before retry
                true
            }
            TimeoutType.RATE_LIMIT -> {
                delay(30000)  // Longer wait
                true
            }
            else -> false  // Don't retry
        }
    }
)
```

### Safe Pre-Execution with Retry

```kotlin
pipe.preValidationFunction = { context, content ->
    // ✅ Safe: Read-only operations
    val userData = fetchUserData()
    context.addEntry("user_context", userData)
    context
}
.enablePipeTimeout(autoRetry = true, retryLimit = 3)
```

## Advanced Usage

### Manual Retry Control

```kotlin
// Force snapshot creation
pipe.forceSaveSnapshot()

// Check retry count
val attempts = PipeTimeoutManager.getRetryCount(pipe)

// Manual retry reset
PipeTimeoutManager.clearRetryCount(pipe)
```

### Retry with Validation

```kotlin
pipe.enablePipeTimeout(autoRetry = true, retryLimit = 3)
    .setValidatorFunction { content ->
        // Validation only runs after retry succeeds
        content.text.contains("expected_output")
    }
    .setOnFailure { original, failed ->
        // Failure function only runs after retry exhausted
        println("Retry exhausted, validation failed")
        failed
    }
```

### Per-Pipe Timeout Tuning

```kotlin
val fastPipe = BedrockPipe()
    .enablePipeTimeout(duration = 30000, autoRetry = true)  // 30 seconds

val slowPipe = BedrockPipe()
    .enablePipeTimeout(duration = 600000, autoRetry = true)  // 10 minutes

pipeline.addPipe(fastPipe).addPipe(slowPipe)
```

## Troubleshooting

### Retry Not Triggering

**Symptom:** Pipe times out but doesn't retry

**Causes:**
- `autoRetry = false` (default)
- `maxRetryAttempts = 0`
- Snapshot not available
- `timeoutStrategy = Fail`

**Solution:** Verify configuration and ensure retry enabled.

### Infinite Retry Loop

**Symptom:** Pipe retries indefinitely

**Causes:**
- `maxRetryAttempts` set too high
- Timeout duration too short for operation
- Systemic LLM endpoint issues

**Solution:** Reduce retry limit, increase timeout duration, check endpoint health.

### Snapshot Restoration Failure

**Symptom:** Error "No snapshot available to restore state"

**Causes:**
- Retry enabled but snapshot not created
- Snapshot cleared before retry
- Memory pressure cleared metadata

**Solution:** Ensure `saveSnapshot()` called before timeout, or use `forceSaveSnapshot()`.

### Unexpected Behavior on Retry

**Symptom:** Duplicate data, corrupted state, or unexpected side effects after retry

**Causes:**
- Pre-execution DITL functions writing to ContextBank or program memory
- Non-idempotent operations in `preInitFunction`, `preValidationFunction`, or `preValidationMiniBankFunction`

**Solution:** Ensure pre-execution functions are read-only. Move writes to post-execution functions.

## See Also

- [Pipe API Reference](../api/pipe.md) - Complete method signatures
- [Pipeline API Reference](../api/pipeline.md) - Pipeline-level configuration
- [Tracing and Debugging](tracing-and-debugging.md) - Monitoring retry attempts
- [Developer-in-the-Loop](developer-in-the-loop.md) - Custom validation patterns
