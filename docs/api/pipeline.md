# Pipeline Class API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
  - [Configuration](#configuration)
  - [Token Tracking](#token-tracking)
  - [Content & Context](#content--context)
- [Public Functions](#public-functions)
  - [Configuration](#configuration-1)
  - [Pipe Management](#pipe-management)
  - [Pause and Resume Control](#pause-and-resume-control)
  - [Tracing](#tracing)
  - [Execution](#execution)
  - [Utilities](#utilities)
  - [P2P Interface](#p2p-interface)

## Overview

The `Pipeline` class orchestrates sequential execution of multiple `Pipe` objects, managing context flow, token tracking, and P2P integration.

```kotlin
class Pipeline : P2PInterface
```

## Public Properties

### Configuration

**`pipelineContainer`**
```kotlin
var pipelineContainer: Any? = null
```
Reference to container holding this pipeline. Used for advanced tracing in Splitters, Manifolds, and DistributionGrids.

**`pipelineName`**
```kotlin
var pipelineName: String = ""
```
Optional name for debugging and monitoring purposes.

### Token Tracking

**`inputTokensSpent`**
```kotlin
var inputTokensSpent: Int = 0
```
Counter for total input tokens consumed across all pipes in the pipeline.

**`outputTokensSpent`**
```kotlin
var outputTokensSpent: Int = 0
```
Counter for total output tokens generated across all pipes in the pipeline.

### Content & Context

**`content`**
```kotlin
var content: MultimodalContent = MultimodalContent()
```
Current multimodal content being processed through the pipeline.

**`context`**
```kotlin
var context: ContextWindow = ContextWindow()
```
Context window for storing and manipulating context from AI interactions. Shared across pipes that enable pipeline context pulling.

**`miniBank`**
```kotlin
var miniBank: MiniBank = MiniBank()
```
Multi-page context storage for complex scenarios requiring multiple context domains.

**`pipeMetaData`**
```kotlin
var pipeMetaData: MutableMap<Any, Any> = mutableMapOf()
```
Metadata storage for pipeline-level information and state tracking.

**`pipeCompletionCallback`**
```kotlin
var pipeCompletionCallback: (suspend (Pipe, MultimodalContent) -> Unit)? = null
```
Callback function executed after each individual pipe completes execution.

**`pipelineCompletionCallBack`**
```kotlin
var pipelineCompletionCallBack: (suspend (Pipeline, MultimodalContent) -> Unit)? = null
```
Callback function executed when the entire pipeline completes execution.

## Public Functions

### Configuration

#### `setPipelineName(name: String): Pipeline`
Sets the pipeline name for debugging and identification.

#### `setPreValidationFunction(func: suspend (context: ContextWindow, miniBank: MiniBank, content: MultimodalContent) -> Unit): Pipeline`
Sets a pre-validation function that executes before any pipes in the pipeline.

**Behavior:** The function is called with the pipeline's context window, mini bank, and initial content before pipe execution begins. Useful for pipeline-level validation, content preprocessing, or dynamic context setup that applies to all pipes. Function execution is traced when tracing is enabled, showing `VALIDATION_START`, `VALIDATION_SUCCESS`, or `VALIDATION_FAILURE` events during the `PRE_VALIDATION` phase.

```kotlin
val pipeline = Pipeline()
    .setPreValidationFunction { context, miniBank, content ->
        // Validate input meets pipeline requirements
        if (content.text.isEmpty()) {
            throw IllegalArgumentException("Pipeline requires non-empty input")
        }
        
        // Add dynamic context based on input
        context.addContextElement("inputType", detectInputType(content.text))
    }
```

#### `setPipelineCompletionCallback(func: suspend (Pipeline, MultimodalContent) -> Unit): Pipeline`
Sets callback function executed when the entire pipeline completes execution.

**Parameters:**
- `func`: Function that receives the pipeline instance and final content when pipeline execution completes

**Behavior:** Called after all pipes have completed execution and the pipeline is about to return its final result. Useful for logging, cleanup operations, or triggering downstream processes. The callback receives both the pipeline instance and the final processed content.

**Usage Example:**
```kotlin
pipeline.setPipelineCompletionCallback { pipeline, content ->
    println("Pipeline '${pipeline.pipelineName}' completed")
    println("Final content length: ${content.text.length}")
    // Custom completion logic
}
```

#### `useGlobalContext(page: String = ""): Pipeline`
Enables global context sharing with the ContextBank system.

**Behavior:** Sets `updateGlobalContext = true` and optionally specifies a page key for context isolation. When enabled, the pipeline's context is shared globally and can be accessed by other pipelines and pipes.

#### `setContextWindow(contextWindow: ContextWindow): Pipeline`
Sets the pipeline's context window with safe deep copying.

```kotlin
val pipeline = Pipeline()
val contextWindow = ContextWindow()
contextWindow.addLoreBookEntry("character", "Main protagonist details", 10)
pipeline.setContextWindow(contextWindow)
```

#### `setMiniBank(miniBank: MiniBank): Pipeline`
Sets the pipeline's mini bank with safe deep copying.

```kotlin
val pipeline = Pipeline()
val miniBank = MiniBank()
miniBank.contextMap["worldState"] = ContextWindow()
miniBank.contextMap["playerData"] = ContextWindow()
pipeline.setMiniBank(miniBank)
```

#### `enablePipeTimeout(applyRecursively: Boolean = true, duration: Long = 300000, autoRetry: Boolean = false, retryLimit: Int = 5, customLogic: (suspend(pipe: Pipe, content: MultimodalContent) -> Boolean)? = null): Pipeline`
Enables timeout tracking and retry behavior for all pipes in this pipeline.

**Parameters:**
- **`applyRecursively`**: If true, propagates settings to child pipes within each pipe
- **`duration`**: Timeout duration in milliseconds (default 300000 = 5 minutes)
- **`autoRetry`**: If true, enables automatic retry on timeout
- **`retryLimit`**: Maximum retry attempts per pipe (default 5)
- **`customLogic`**: Optional custom retry function for all pipes

**Behavior:** Configures timeout and retry settings that are applied to all pipes during `init()`. Each pipe receives:
- `enablePipeTimeout = true`
- `pipeTimeout = duration`
- `maxRetryAttempts = retryLimit`
- `timeoutStrategy` based on `autoRetry` and `customLogic` parameters
- `pipeRetryFunction = customLogic` if provided

If `applyRecursively = true`, settings also propagate to child pipes (validator, branch, transformation, reasoning pipes).

**Example:**
```kotlin
pipeline.enablePipeTimeout(
    applyRecursively = true,
    duration = 120000,  // 2 minutes
    autoRetry = true,
    retryLimit = 3
)
```

> ⚠️ **Warning:** Retry re-executes pre-execution DITL functions. Ensure these functions are read-only and don't write to ContextBank or program memory. See [Timeout and Retry](../core-concepts/timeout-and-retry.md) for details.

---

### Pipe Management

#### `add(pipe: Pipe): Pipeline`
Adds a pipe to the pipeline execution sequence.

**Behavior:** Pipes are executed in the order they are added. Appends pipe to internal list if not already present. Pipeline reference is set during `init()` method execution, not during `add()`. Duplicate pipes are ignored.

#### `addAll(pipes: List<Pipe>): Pipeline`
Adds multiple pipes to the pipeline in sequence.

**Behavior:** Calls `add()` for each pipe in the list, maintaining order. Pipeline references are set during `init()` method execution.

#### `getPipes(): List<Pipe>`
Returns all pipes in the pipeline.

**Behavior:** Returns immutable view of the internal pipe list. Useful for manual pipe configuration or inspection.

#### `getPipeByName(name: String): Pair<Int, Pipe?>`
Finds a pipe by its name and returns its index and reference.

**Behavior:** Returns `Pair(index, pipe)` if found, `Pair(-1, null)` if not found. Used internally for pipe jumping and P2P schema binding.

#### `getNextPipe(content: MultimodalContent): Pipe?`
Determines the next pipe to execute based on content state and jump targets.

**Behavior:** Complex logic handling:
- Jump targets set via `content.setJumpToPipe()`
- Sequential execution through pipe list
- Pipeline termination conditions
- Pipe skipping and branching logic

---

### Pause and Resume Control

#### `pauseBeforePipes(): Pipeline`
Enables pause points before each pipe execution.

**Behavior:** Automatically sets `pausingEnabled = true`. Pipeline will pause before executing each pipe and wait for manual resume. Useful for step-by-step debugging or human review workflows.

#### `pauseAfterPipes(): Pipeline`
Enables pause points after each pipe execution.

**Behavior:** Automatically sets `pausingEnabled = true`. Pipeline will pause after each pipe completes and wait for manual resume. Allows inspection of intermediate results.

#### `pauseBeforeJumps(): Pipeline`
Enables pause points before jump operations.

**Behavior:** Automatically sets `pausingEnabled = true`. Pipeline will pause when a pipe requests a jump to another pipe, allowing review of jump decisions.

#### `pauseAfterRepeats(): Pipeline`
Enables pause points after repeat operations.

**Behavior:** Automatically sets `pausingEnabled = true`. Pipeline will pause after completing repeat cycles, useful for monitoring iterative processing.

#### `pauseOnCompletion(): Pipeline`
Enables pause points when pipeline completes.

**Behavior:** Automatically sets `pausingEnabled = true`. Pipeline will pause when all processing is complete but before returning results, allowing final review.

#### `enablePausing(): Pipeline`
Enables pause functionality without declaring specific pause points.

**Behavior:** Sets `pausingEnabled = true` allowing manual `pause()` calls to work. Useful when you want external control over pausing without predefined pause points.

#### `enablePausePoints(): Pipeline`
Convenience method to enable common pause points.

**Behavior:** Equivalent to calling `pauseBeforePipes().pauseOnCompletion()`. Enables pausing at pipe boundaries and completion.

#### `pauseWhen(condition: suspend (Pipe, MultimodalContent) -> Boolean): Pipeline`
Sets conditional pause function for dynamic pause control.

**Behavior:** Automatically sets `pausingEnabled = true`. The condition function is evaluated at each pause point. Pipeline pauses when the function returns `true`. Enables sophisticated conditional pausing based on content analysis or pipe state.

```kotlin
pipeline.pauseWhen { pipe, content ->
    pipe.pipeName == "validator" && content.text.contains("error")
}
```

#### `onPause(callback: suspend (Pipe?, MultimodalContent) -> Unit): Pipeline`
Sets callback function executed when pipeline pauses.

**Behavior:** Callback receives the current pipe (or null if paused at completion) and current content. Useful for logging, user notifications, or content inspection during pauses.

#### `onResume(callback: suspend (Pipe?, MultimodalContent) -> Unit): Pipeline`
Sets callback function executed when pipeline resumes.

**Behavior:** Callback receives the current pipe (or null if resuming from completion) and current content. Useful for logging resume events or updating UI state.

#### `suspend fun pause()`
Manually pauses pipeline execution.

**Behavior:** If `pausingEnabled = false`, this is a no-op. Otherwise, sets internal pause state and blocks execution using channel-based synchronization until `resume()` is called. Automatically triggers pause callbacks and tracing events.

#### `suspend fun resume()`
Manually resumes pipeline execution.

**Behavior:** Sends resume signal through internal channel, unblocking paused execution. Automatically triggers resume callbacks and tracing events.

#### `isPaused(): Boolean`
Checks if pipeline is currently paused.

**Behavior:** Returns `true` if pipeline is currently in paused state, `false` otherwise. Useful for UI state management and status monitoring.

#### `canPause(): Boolean`
Checks if pipeline has pause functionality enabled.

**Behavior:** Returns `true` if any pause points have been declared (automatically sets `pausingEnabled = true`), `false` otherwise. Indicates whether pause/resume operations are available.

---

### Tracing

#### `enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipeline`
Enables execution tracing for the pipeline and all its pipes.

**Behavior:** Propagates tracing configuration to all pipes in the pipeline. Enables comprehensive execution monitoring including pipe transitions, context changes, and performance metrics. Beware that sub-pipe tracing is less consistent.

#### `getTraceReport(format: TraceFormat = traceConfig.outputFormat): String`
Returns formatted trace report for the last execution.

**Behavior:** Exports trace data in specified format (JSON, HTML, etc.). Only available if tracing was enabled during execution.

#### `getFailureAnalysis(): FailureAnalysis?`
Returns failure analysis if tracing is enabled and failures occurred.

**Behavior:** Provides detailed analysis of execution failures including failure points, error types, and suggested remediation.

#### `getTraceId(): String`
Returns the unique trace identifier for this pipeline.

---

### Error Handling

#### `hasError(): Boolean`
Checks if any pipe in the pipeline failed during execution.

**Returns:** `true` if a pipe failure was captured, `false` otherwise.

**Example:**
```kotlin
pipeline.execute("input")
if (pipeline.hasError()) {
    println("Pipeline failed at: ${pipeline.getFailedPipeName()}")
    println("Error: ${pipeline.getErrorMessage()}")
}
```

#### `getErrorMessage(): String`
Gets the error message from the failed pipe.

**Returns:** Error message string, or empty string if no error.

#### `getFailedPipeName(): String`
Gets the name of the pipe that caused the pipeline to fail.

**Returns:** Pipe name string, or empty string if no failure.

#### `getFullErrorContext(): String`
Gets formatted error information including pipe name, execution phase, and error message.

**Returns:** Formatted string like: "Pipe 'PipeName' failed in EXECUTION phase: Error message"

#### `wasTerminatedByError(): Boolean`
Checks if the pipeline ended due to an error rather than normal completion.

**Returns:** `true` if pipeline was terminated by error, `false` otherwise.

#### `clearErrors()`
Clears all error information from the pipeline.

**Usage:** Call before reusing a pipeline to reset error state.

#### `lastFailedPipe: Pipe?`
Direct access to the pipe instance that failed.

#### `lastError: PipeError?`
Direct access to the complete error object from the failed pipe.

> ℹ️ **Note:** Pipeline automatically captures errors from pipes during execution. Errors persist until explicitly cleared or the pipeline executes successfully.

---

### Execution

#### `init(initPipes: Boolean = false): Pipeline`
Initializes the pipeline and optionally all its pipes.

**Behavior:** Calls `init()` on all pipes if `initPipes = true`. Used for setup operations that require async initialization. Pipeline can be executed without explicit initialization.

#### `execute(initialPrompt: String = ""): String`
Executes the pipeline with string input, returns string output.

**Behavior:** Legacy method that wraps input in `MultimodalContent` and extracts text from the result. Provided for backward compatibility.

**Error Handling:** On failure, returns empty string. Check `pipeline.hasError()` for error details.

#### `execute(initialContent: MultimodalContent): MultimodalContent`
Executes the pipeline with multimodal content.

**Behavior:** Main execution method that:
- Processes content through each pipe sequentially
- Handles pipe jumping and branching
- Manages context flow between pipes
- Updates token counters
- Handles pipeline termination conditions
- Supports retry logic for failed pipes
- Manages global context updates
- **Captures pipe failures** and stores in `lastFailedPipe` and `lastError`

**Error Handling:** On pipe failure, captures error information and continues or terminates based on pipe configuration. Check `pipeline.hasError()` after execution.

---

### Utilities

#### `getTokenCount(): String`
Returns formatted string with input and output token counts.

**Behavior:** Returns human-readable format: "Input tokens: X \n Output Tokens: Y". Useful for cost estimation and performance monitoring.

---

### P2P Interface

#### `setP2pDescription(description: P2PDescriptor)`
Sets P2P agent descriptor for pipeline registration.

#### `getP2pDescription(): P2PDescriptor?`
Returns the P2P agent descriptor.

#### `setP2pTransport(transport: P2PTransport)`
Sets P2P transport configuration.

#### `getP2pTransport(): P2PTransport?`
Returns the P2P transport configuration.

#### `setP2pRequirements(requirements: P2PRequirements)`
Sets P2P security and compatibility requirements.

#### `getP2pRequirements(): P2PRequirements?`
Returns the P2P requirements.

#### `setContainerObject(container: Any)`
Sets reference to parent container.

#### `getContainerObject(): Any?`
Returns reference to parent container.

#### `getPipelinesFromInterface(): List<Pipeline>`
Returns list containing this pipeline (P2P interface requirement).

#### `executeP2PRequest(request: P2PRequest): P2PResponse?`
Executes P2P requests with advanced features.

**Behavior:** Complex P2P execution handling:
- **Pipeline Copying:** Creates temporary pipeline copy if request modifies schemas or context to prevent drift
- **Context Binding:** Applies request context to pipeline if security allows
- **Schema Modification:** Dynamically modifies pipe JSON schemas based on request
- **Custom Instructions:** Applies per-pipe custom instructions from request
- **Security Enforcement:** Respects P2P requirements for allowed modifications
- **Response Generation:** Returns structured P2P response with execution results

The P2P execution supports dynamic pipeline modification, allowing external agents to customize behavior within security constraints. This enables flexible agent-to-agent communication while maintaining pipeline integrity.
## Next Steps

- [MultimodalContent API](multimodal-content.md) - Continue into content handling.
