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

## Streaming Stall Detector

TPipe also ships a **streaming stall detector** that watches inter-token arrival intervals during SSE streaming and triggers retry when the model falls silent abnormally — a different failure mode from wall-clock timeouts.

A stalled stream and a timed-out pipe are not the same thing. A timeout fires after a fixed duration regardless of progress. A stall detector fires when the most recent token arrived more than `N` standard deviations later than the rolling mean, after a warmup period. Both feed into `PipeTimeoutManager.handleStallSignal` and share the same retry infrastructure.

### Core Component

`StreamingStallDetector` tracks token arrival timestamps in a ring buffer and computes rolling population mean and standard deviation in O(1) per token. After `warmupTokenCount` tokens have been observed, the statistical test arms. Each subsequent token arrival checks:

```
silence_ms > max(mean + stddevMultiplier × stddev, stallMinSilenceMs)
```

If true, a stall is detected.

### Configuration

#### Pipe-Level

```kotlin
// Minimal: detect stalls with defaults
pipe.enableStallDetector()

// Custom thresholds
pipe.enableStallDetector(
    config = StreamingStallConfig(
        windowSize = 50,           // tokens to track (default 50)
        stddevMultiplier = 3.0,    // k in μ + kσ threshold (default 3.0)
        stallMinSilenceMs = 10_000L, // absolute floor in ms (default 10000)
        maxStallRetries = 3,       // max stall retries (default 3)
        warmupTokenCount = 20      // tokens before detection arms (default 20)
    )
)

// Register a callback for monitoring/logging
pipe.enableStallDetector(
    config = StreamingStallConfig(maxStallRetries = 3)
) { stallEvent ->
    println("STALL: ${stallEvent.tokensSeen} tokens, ${stallEvent.silenceMs}ms silence")
}

// Or set the callback separately
pipe.setStallCallback { stallEvent ->
    myMetricService.recordStall(stallEvent)
}
```

#### Pipeline-Level

```kotlin
pipeline.enableStallDetector(
    applyRecursively = true,  // propagate to all child pipes (default false)
    config = StreamingStallConfig(maxStallRetries = 2)
) { stallEvent ->
    println("Stall in pipeline: ${stallEvent.pipeName}")
}
```

When `applyRecursively = true`, every pipe added to the pipeline receives the stall config and callback during `init()`. Each pipe owns its own `StreamingStallDetector` instance with its own per-pipe statistics.

#### Recursive Across the Full Container Tree

For multi-container hierarchies (Manifold, Junction, Splitter, Connector, MultiConnector, DistributionGrid, PumpStation), use `enableStallDetectorRecursive` on any P2PInterface root. The call walks the entire container tree: each container override recurses into its own child P2PInterface references, and at the leaf each `Pipe` is wired via `Pipe.propagateStallDetection(config, callback)`, which further walks the pipe's validator / transformation / branch / reasoning child pipes.

```kotlin
// One call at the top of the agent tree, all leaves get stall detection.
val agentTree: P2PInterface = manifold { /* ... */ }
agentTree.enableStallDetectorRecursive(
    config = StreamingStallConfig(maxStallRetries = 2)
) { stallEvent ->
    metrics.recordStall(stallEvent.pipeName, stallEvent.silenceMs)
}
```

Semantics:

- **Parent override wins** — a child that was previously configured with a different `StreamingStallConfig` or callback has both replaced by the recursive call's arguments. This matches the direct-child propagation in `Pipeline.init` and gives the root caller a single source of truth for stall behavior across the whole tree.
- **Per-pipe stats invariant** — every leaf pipe keeps its own `StreamingStallDetector` instance with its own ring buffer. The recursive call wires more leaves to detectors, it does NOT share a single detector across pipes.
- **Cycle-safe** — `propagateStallDetection` uses a `visited` set keyed by `pipeId` so a pipe referenced from multiple containers is wired exactly once.
- **Default config** — calling `enableStallDetectorRecursive()` with no arguments propagates `StreamingStallConfig()` defaults and a `null` callback to every leaf.

### How It Works

```
Token arrives → update ring buffer & rolling stats → (if armed) check threshold
                                                              ↓
                                              silence > max(μ+kσ, stallMinSilenceMs)
                                                              ↓
                                                          STALL DETECTED
                                                              ↓
                                          GlobalScope.launch { onStall(StallEvent) }
                                                              ↓
                                          PipeTimeoutManager.handleStallSignal(...)
                                                              ↓
                                          attempts < maxStallRetries?
                                              YES → snapshot restored, repeatPipe=true, re-execute
                                              NO  → PIPE_FAILURE, stream terminated
```

The stall detector runs inside the streaming SSE callback loop. `onStall` fires asynchronously via `GlobalScope.launch(Dispatchers.Default)` so that a `suspend`-typed callback can call `pipe.abort()` without forcing the callback itself to be suspend. Failures in the callback are caught and never propagate into the chunk loop.

`StallEvent` fields:

| Field | Type | Description |
|:---|:---|:---|
| `pipeName` | `String` | Display name of the pipe experiencing the stall |
| `elapsedMs` | `Long` | Epoch milliseconds since the stream started |
| `tokensSeen` | `Int` | Token count at the moment of stall |
| `lastTokenTimestamp` | `Long` | Epoch ms of the last token received before the stall |
| `silenceMs` | `Long` | Gap that triggered the stall (ms) |
| `expectedIntervalMs` | `Double` | Rolling mean inter-token interval (μ) |
| `actualIntervalMs` | `Long` | Observed interval for the stalled period |
| `stddevMultiplier` | `Double` | k used in the μ + kσ threshold |
| `retryAttempt` | `Int` | Current stall retry attempt number (0 = first stall) |

### Relationship to Timeout Retry

Stall detection and wall-clock timeout operate independently and can be used together. They share `PipeTimeoutManager.handleStallSignal` but maintain separate retry counters (`stallRetryAttempts` vs `retryAttempts`) in separate `ConcurrentHashMap` entries.

```
Timeout fires → handleTimeoutSignal → uses retryAttempts counter
Stall fires   → handleStallSignal  → uses stallRetryAttempts counter
```

Both can fire on the same stream. After `maxStallRetries` exhausted, the stall path terminates with `PIPE_FAILURE`. After `maxRetryAttempts` exhausted, the timeout path terminates the same way. They do not share a counter.

### Tracing

```
PIPE_RETRY    — stall retry triggered, attempts incremented, snapshot restored
PIPE_FAILURE  — stall retry exhausted, stream terminated
```

Both events carry full stall metadata in the trace event.

### Threshold Selection

| Scenario | `stddevMultiplier` | `stallMinSilenceMs` | `warmupTokenCount` |
|:---|:---|:---|:---|
| Stable high-throughput models | 3.0 (default) | 10_000 (default) | 20 (default) |
| Variable/bursty models | 4.0–5.0 | 15_000–20_000 | 30–50 |
| Low-latency local models | 2.0–2.5 | 3_000–5_000 | 10–15 |
| High-latency remote APIs | 3.0–4.0 | 20_000–30_000 | 20–30 |

The `stallMinSilenceMs` floor is the primary guard against false positives from network jitter. The `stddevMultiplier` handles models whose token arrival rate varies legitimately. Set `warmupTokenCount` high enough that cold-start ramp-up does not trigger a false stall before the model reaches steady-state throughput.

### Examples

#### Basic Stall Detection with Automatic Retry

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-5-sonnet-20241022-v1:0")
    .setStreamingEnabled(true)
    .enableStallDetector(
        config = StreamingStallConfig(
            maxStallRetries = 3,
            stallMinSilenceMs = 15_000L,
            stddevMultiplier = 3.0
        )
    )

val result = pipe.execute("Explain the history of the internet.")
```

#### Stall Detection with Monitoring Callback

```kotlin
import com.TTT.Pipe.StallEvent
import com.TTT.Pipe.StallCallback

val myAlertService = MyAlertService()

val onStall: StallCallback = { event ->
    myAlertService.alert(
        level = if (event.retryAttempt >= 2) "critical" else "warning",
        message = "Stall on ${event.pipeName}: ${event.silenceMs}ms silence " +
            "after ${event.tokensSeen} tokens (μ=${event.expectedIntervalMs.toLong()}ms, " +
            "k=${event.stddevMultiplier})"
    )
}

val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-5-sonnet-20241022-v1:0")
    .setStreamingCallback { chunk -> print(chunk) }
    .enableStallDetector(
        config = StreamingStallConfig(maxStallRetries = 3),
        callback = onStall
    )
    .init()

pipe.execute("Give me a detailed history of computing.")
```

#### Pipeline-Level Propagation

```kotlin
val pipeline = Pipeline()
    .enableStallDetector(
        applyRecursively = true,
        config = StreamingStallConfig(maxStallRetries = 2)
    ) { event ->
        println("[${event.pipeName}] stalled: ${event.silenceMs}ms")
    }
    .addPipe(classifierPipe)
    .addPipe(generatorPipe)
    .addPipe(evaluatorPipe)
    .init()
```

Every pipe in the pipeline inherits the same stall config and callback. Each pipe maintains its own rolling statistics — a stall on `classifierPipe` does not reset the statistics on `generatorPipe`.

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
