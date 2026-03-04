# Pipeline Class API

If a `Pipe` is a single valve, a `Pipeline` is the **Mainline**. It is the primary infrastructure that allows you to chain multiple pipes together, creating a sophisticated flow where the output of one pipe becomes the input for the next.

The `Pipeline` class orchestrates the sequential execution of `Pipe` objects, managing context flow, token tracking, and P2P integration.

```kotlin
class Pipeline : P2PInterface
```

## Table of Contents
- [Public Properties](#public-properties)
  - [Configuration](#configuration)
  - [Token Tracking](#token-tracking)
  - [Content & Context](#content--context)
- [Public Functions](#public-functions)
  - [Configuration](#configuration-1)
  - [Pipe Management](#pipe-management)
  - [Pause and Resume Control](#pause-and-resume-control)
  - [Tracing](#tracing)
  - [Error Handling](#error-handling)
  - [Execution](#execution)
  - [P2P Interface](#p2p-interface)

## Public Properties

### Configuration

**`pipelineContainer`**
```kotlin
var pipelineContainer: Any? = null
```
Reference to the container holding this pipeline. Used for advanced tracing in Splitters, Manifolds, and DistributionGrids.

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
The current multimodal content flowing through the pipeline.

**`context`**
```kotlin
var context: ContextWindow = ContextWindow()
```
The shared memory reservoir for the pipeline. Shared across pipes that enable pipeline context pulling.

**`miniBank`**
```kotlin
var miniBank: MiniBank = MiniBank()
```
Multi-page context storage for complex scenarios requiring multiple context domains.

**`pipeMetaData`**
```kotlin
var pipeMetaData: MutableMap<Any, Any> = mutableMapOf()
```
Metadata storage for pipeline-level state tracking.

**`pipeCompletionCallback`**
```kotlin
var pipeCompletionCallback: (suspend (Pipe, MultimodalContent) -> Unit)? = null
```
Callback executed after each individual "valve" (pipe) completes its flow.

**`pipelineCompletionCallBack`**
```kotlin
var pipelineCompletionCallBack: (suspend (Pipeline, MultimodalContent) -> Unit)? = null
```
Callback executed when the entire mainline completes.

## Public Functions

### Configuration

#### `setPipelineName(name: String): Pipeline`
Sets the pipeline name for debugging and identification.

#### `setPreValidationFunction(func: suspend (context: ContextWindow, miniBank: MiniBank, content: MultimodalContent) -> Unit): Pipeline`
Sets a function that executes before *any* pipes in the pipeline begin their work.

> [!TIP]
> Use this for pipeline-level validation, content preprocessing, or dynamic context setup that applies to the entire mainline.

#### `setPipelineCompletionCallback(func: suspend (Pipeline, MultimodalContent) -> Unit): Pipeline`
Sets the callback function executed when the entire pipeline completes execution.

#### `useGlobalContext(page: String = ""): Pipeline`
Enables sharing this pipeline's flow with the global **ContextBank** system.

#### `setContextWindow(contextWindow: ContextWindow): Pipeline`
Sets the pipeline's memory reservoir (ContextWindow) with safe deep copying.

#### `setMiniBank(miniBank: MiniBank): Pipeline`
Sets the pipeline's multi-page reservoir (MiniBank) with safe deep copying.

#### `enablePipeTimeout(applyRecursively: Boolean = true, duration: Long = 300000, autoRetry: Boolean = false, retryLimit: Int = 5, customLogic: (suspend(pipe: Pipe, content: MultimodalContent) -> Boolean)? = null): Pipeline`
Enables timeout tracking and retry behavior for **all pipes** in this mainline.

---

### Pipe Management

#### `add(pipe: Pipe): Pipeline`
Adds a pipe to the mainline execution sequence. Pipes are executed in the order they are added.

#### `addAll(pipes: List<Pipe>): Pipeline`
Adds multiple pipes to the pipeline in sequence.

#### `getPipes(): List<Pipe>`
Returns an immutable view of all pipes currently in the mainline.

#### `getPipeByName(name: String): Pair<Int, Pipe?>`
Finds a pipe by its name and returns its index and reference.

---

### Pause and Resume Control: The Pressure Valve

Pipelines support granular pause points, allowing for human-in-the-loop (DITL) verification at various stages of the flow.

#### `pauseBeforePipes(): Pipeline`
The flow stops automatically before executing each pipe.

#### `pauseAfterPipes(): Pipeline`
The flow stops automatically after each pipe completes.

#### `pauseOnCompletion(): Pipeline`
The flow stops once all pipes are done but before the results are returned.

#### `enablePausing(): Pipeline`
Enables pause functionality, allowing manual `.pause()` calls to work.

#### `pauseWhen(condition: suspend (Pipe, MultimodalContent) -> Boolean): Pipeline`
Sets a conditional pause for dynamic flow control. The pipeline only stops if the condition returns `true`.

#### `onPause(callback: suspend (Pipe?, MultimodalContent) -> Unit): Pipeline`
Sets a callback executed when the pipeline pauses. Use this to notify UIs or developers.

#### `suspend fun pause()`
Manually shuts the valve, pausing execution until `resume()` is called.

#### `suspend fun resume()`
Opens the valve, unblocking the paused flow.

---

### Tracing

#### `enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipeline`
Propagates tracing configuration to all pipes in the pipeline, enabling complete visibility into the mainline's execution.

---

### Error Handling

#### `hasError(): Boolean`
Checks if any pipe in the mainline failed during execution.

#### `getFailedPipeName(): String`
Returns the name of the "valve" that caused the flow to fail.

#### `getFullErrorContext(): String`
Returns formatted error info: "Pipe 'Name' failed in PHASE: Error message".

---

### Execution

#### `init(initPipes: Boolean = false): Pipeline`
Initializes the pipeline and optionally all its child pipes.

#### `execute(initialContent: MultimodalContent): MultimodalContent`
The main execution method. Processes content through each pipe sequentially, handling:
- **Jumping**: Redirecting flow to different pipes.
- **Branching**: Handling validation failures.
- **Context Flow**: Pumping memory between pipes.
- **Retries**: Automatically recovering from pipe-level timeouts.

> [!IMPORTANT]
> **Error Capture**: Pipeline automatically captures errors from pipes. Check `pipeline.hasError()` after execution to detect if the flow was interrupted.

---

### P2P Interface

#### `setP2pDescription(description: P2PDescriptor)`
Sets the agent's technical specifications for network discovery.

#### `executeP2PRequest(request: P2PRequest): P2PResponse?`
Handles incoming P2P requests. This supports **Pipeline Copying**, ensuring that an external request can customize the mainline (schemas, context) without permanently altering the original pipeline template.
