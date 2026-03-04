# Pipe Class API

The `Pipe` class is the core abstraction for AI model interactions in TPipe. Think of it as a **Valve**: it's a single unit of configuration that controls the flow of data to and from an AI model. Every interaction in TPipe begins with a Pipe.

As an abstract base class, `Pipe` defines the standard interface that all provider-specific implementations (like `BedrockPipe` or `OllamaPipe`) follow.

```kotlin
abstract class Pipe : P2PInterface, ProviderInterface
```

## Table of Contents
- [Constants](#constants)
- [Public Properties](#public-properties)
  - [Configuration](#configuration)
  - [Function Hooks](#function-hooks)
  - [Integration](#integration)
- [Public Functions](#public-functions)
  - [Configuration](#configuration-1)
  - [Prompt Management](#prompt-management)
  - [Input/Output](#inputoutput)
  - [TodoList Integration](#todolist-integration)
  - [Model Parameters](#model-parameters)
  - [Context Management](#context-management)
  - [Token Counting](#token-counting)
  - [LoreBook](#lorebook)
  - [Model Reasoning](#model-reasoning)
  - [Streaming](#streaming)
  - [Function Hooks](#function-hooks-1)
  - [Pipe Chaining](#pipe-chaining)
  - [Protocols](#protocols)
  - [Tracing](#tracing)
  - [Getters](#getters)
  - [Execution](#execution)

## Public Properties

### Configuration

**`pipeName`**
```kotlin
var pipeName: String = ""
```
Optional name for debugging, tracing, and identification.

**`jsonInput`**
```kotlin
var jsonInput: String = ""
```
JSON schema for expected input structure. Used for validation and prompt injection.

**`jsonOutput`**
```kotlin
var jsonOutput: String = ""
```
JSON schema for expected output structure. Used for validation and response formatting.

**`saveSnapshot`**
```kotlin
var saveSnapshot: Boolean = false
```
If true, automatically saves a snapshot to the content object's snapshot system at pipe startup. Useful when you can't control the content object directly or need guaranteed snapshot preservation.

**`enablePipeTimeout`**
```kotlin
var enablePipeTimeout: Boolean = false
```
Enables timeout tracking for this pipe. When true, pipe execution is monitored and can be aborted if duration exceeds `pipeTimeout`.

**`pipeTimeout`**
```kotlin
var pipeTimeout: Long = 300000L
```
Timeout duration in milliseconds (default 5 minutes). Pipe aborts if execution exceeds this duration.

**`timeoutStrategy`**
```kotlin
var timeoutStrategy: PipeTimeoutStrategy = PipeTimeoutStrategy.Fail
```
Strategy for handling timeouts: `Fail` (terminate), `Retry` (automatic retry with snapshot), or `CustomLogic` (developer-defined).

**`maxRetryAttempts`**
```kotlin
var maxRetryAttempts: Int = 5
```
Maximum number of retry attempts when `timeoutStrategy = Retry`. Pipe fails after exhausting attempts.

**`applyTimeoutRecursively`**
```kotlin
var applyTimeoutRecursively: Boolean = true
```
If true, timeout and retry settings propagate to child pipes (validator, branch, transformation, reasoning).

### Function Hooks

**`preInitFunction`**
```kotlin
var preInitFunction: (suspend (MultimodalContent) -> Unit)? = null
```
Called before context loading. Allows input preprocessing.

**`preValidationFunction`**
```kotlin
var preValidationFunction: (suspend (ContextWindow, MultimodalContent?) -> ContextWindow)? = null
```
Called after context loading, before prompt injection. Allows context adjustments.

**`preValidationMiniBankFunction`**
```kotlin
var preValidationMiniBankFunction: (suspend (MiniBank, MultimodalContent?) -> MiniBank)? = null
```
Alternative to `preValidationFunction` for multi-page context scenarios.

**`preInvokeFunction`**
```kotlin
var preInvokeFunction: (suspend (MultimodalContent) -> Boolean)? = null
```
Determines if pipe should be skipped. Returns `true` to skip, `false` to continue.

**`postGenerateFunction`**
```kotlin
var postGenerateFunction: (suspend (MultimodalContent) -> Unit)? = null
```
Called immediately after AI generates content, before validation. Useful for caching or logging raw output.

**`exceptionFunction`**
```kotlin
var exceptionFunction: (suspend (MultimodalContent, Throwable) -> Unit)? = null
```
Called when exceptions occur during AI execution. Provides content state and exception for debugging.

### Integration

**`containerPtr`**
```kotlin
var containerPtr: P2PInterface? = null
```
Container object to execute instead of this pipe. Enables delegation to complex orchestration.

**`currentPipelineId`**
```kotlin
var currentPipelineId: String? = null
```
ID of currently executing pipeline. Used for tracking and coordination.

## Public Functions

### Configuration

#### `init(): Pipe`
Abstract initialization function that must be implemented by provider-specific pipe classes.

**Behavior:** Called to initialize provider-specific configurations, authentication, and model settings. Each provider implementation (BedrockPipe, OllamaPipe, etc.) must implement this method to set up their specific requirements. Returns the pipe instance for method chaining.

#### `setPipeName(name: String): Pipe`
Sets the pipe name for debugging and identification.

#### `setPipelineRef(pipeline: Pipeline?): Pipe`
Sets reference to parent pipeline.

#### `setProvider(provider: ProviderName): Pipe`
Sets the AI provider (AWS, OpenAI, etc.).

#### `setModel(modelName: String): Pipe`
Sets the AI model name.

#### `setPromptMode(promptMode: PromptMode): Pipe`
Sets how prompts are handled by the model.

**Behavior:** Affects context management and prompt construction. `singlePrompt` disables automatic context handling, `chat` relies on provider-side context, `internalContext` enables TPipe's context system.

#### `wrapContentWithConverse(role: ConverseRole = ConverseRole.agent): Pipe`
Enables automatic conversation history wrapping for pipeline chaining.

**Behavior:** When enabled, the pipe automatically detects if input content is in `ConverseHistory` format and continues building the conversation. The pipe's output is wrapped with the specified role and added to the conversation history. Essential for multi-turn conversations and agent-based systems. All pipes in a conversation chain should have this enabled.

#### `allowEmptyUserPrompt(): Pipe`
Disables safety guardrail that prevents empty user prompts.

**Behavior:** By default, TPipe blocks empty user prompts as they cause destructive bugs in 99% of cases. This method acts as a contractual promise that you've designed the pipe to handle empty prompts safely and won't cause catastrophic pipeline damage.

#### `allowEmptyContentObject(): Pipe`
Disables safety guardrail that prevents completely empty content objects.

**Behavior:** By default, TPipe blocks content objects with no user prompt, text input, binary content, or context data as they're footguns 99.99% of the time. This method is a contractual promise that this edge case is safe and has been properly handled.

#### `forceSaveSnapshot(): Pipe`
Forces the pipe to save a snapshot to the content object at startup.

**Behavior:** Sets `saveSnapshot = true` to ensure the content object is automatically snapshotted when the pipe begins execution. Useful when you can't control the content object directly or need guaranteed snapshot preservation for branch failure recovery.

#### `enablePipeTimeout(applyRecursively: Boolean = true, duration: Long = 300000, autoRetry: Boolean = false, retryLimit: Int = 5, customLogic: (suspend(pipe: Pipe, content: MultimodalContent) -> Boolean)? = null): Pipe`
Enables timeout tracking and configures retry behavior for this pipe.

**Parameters:**
- `applyRecursively` - If true, propagates settings to child pipes (validator, branch, transformation, reasoning)
- `duration` - Timeout duration in milliseconds (default 300000 = 5 minutes)
- `autoRetry` - If true, sets `timeoutStrategy = Retry` for automatic retry
- `retryLimit` - Maximum retry attempts (default 5)
- `customLogic` - Optional custom retry function for `CustomLogic` strategy

**Behavior:** Starts timeout tracking when pipe executes. On timeout, behavior depends on strategy:
- `Fail`: Terminates immediately (default if `autoRetry = false` and `customLogic = null`)
- `Retry`: Automatically restores snapshot and retries up to `retryLimit` times
- `CustomLogic`: Invokes `customLogic` function to determine retry action

> [!CAUTION]
> **Side Effects on Retry**: Retry re-executes ALL pre-execution DITL functions (`preInitFunction`, `preValidationFunction`, etc.). These functions MUST be read-only. Writing to ContextBank or program memory within these hooks will cause duplicate writes on retry.

#### `setRetryFunction(func: (suspend (pipe: Pipe, content: MultimodalContent) -> Boolean)?): Pipe`
Sets custom retry logic function for `CustomLogic` timeout strategy.

**Parameters:**
- `func` - Function receiving pipe reference and content, returns true to retry

---

### Prompt Management

#### `setSystemPrompt(prompt: String): Pipe`
Sets the system prompt for the AI model.

**Behavior:** Stores as `rawSystemPrompt` and rebuilds with injections when `applySystemPrompt()` is called. JSON schemas, PCP context, and P2P agents are automatically injected during system prompt application.

#### `setUserPrompt(prompt: String): Pipe`
Sets the user prompt prefix.

#### `setMiddlePrompt(prompt: String): Pipe`
Sets prompt injected between input and output JSON schemas in the system prompt.

#### `setFooterPrompt(prompt: String): Pipe`
Sets prompt added to the end of the system prompt after all other injections.

#### `copySystemPromptToUserPrompt(): Pipe`
Copies system prompt to user prompt for models that handle user prompts better.

#### `applySystemPrompt(): Pipe`
Rebuilds system prompt with all injections and configurations.

---

### Input/Output

#### `setMultimodalInput(content: MultimodalContent): Pipe`
Sets multimodal input content (text and binary data).

#### `setJsonInput(json: String): Pipe`
Sets JSON input schema as string.

#### `setJsonInput<T>(json: T, senddefaults: Boolean = true): Pipe`
Sets JSON input schema from Kotlin object using reflection.

#### `setJsonInput(kclass: KClass<*>): Pipe`
Sets JSON input schema from KClass directly.

#### `setJsonInputInstructions(instructions: String): Pipe`
Sets custom instructions for JSON input handling that override the default input format explanation.

#### `setJsonOutput(json: String): Pipe`
Sets JSON output schema as string.

#### `setJsonOutput<T>(json: T): Pipe`
Sets JSON output schema from Kotlin object using reflection.

#### `setJsonOutput(kclass: KClass<*>): Pipe`
Sets JSON output schema from KClass directly.

#### `setJsonOutputInstructions(instructions: String): Pipe`
Sets custom instructions for JSON output handling that override the default output format explanation.

#### `requireJsonPromptInjection(stripExternalText: Boolean = false): Pipe`
Forces JSON prompt injection for models without native JSON support.

---

### TodoList Integration

#### `setTodoListPageKey(key: String): Pipe`
Links this pipe to a TodoList stored in ContextBank.

**Behavior:** Sets the page key used to retrieve a TodoList from ContextBank. When `applySystemPrompt()` is called, the pipe automatically retrieves the TodoList, injects instructions, and appends the list to the system prompt.

#### `setTodoListInstructions(instructions: String): Pipe`
Overrides the default instructions for TodoList injection.

---

### Model Parameters

#### `setTemperature(temp: Double): Pipe`
Sets randomness/creativity level (0.0 to 2.0).

#### `setTopP(top: Double): Pipe`
Sets nucleus sampling probability threshold.

#### `setTopK(top: Int): Pipe`
Sets number of top tokens to consider.

#### `setMaxTokens(max: Int): Pipe`
Sets maximum output tokens.

#### `enableMaxTokenOverflow(): Pipe`
Allows incomplete output when hitting token limit instead of failing.

#### `setRepetitionPenalty(penalty: Double): Pipe`
Sets penalty for repetitive text generation.

#### `setStopSequences(seqs: List<String>): Pipe`
Sets sequences that stop text generation.

---

### Context Management

#### `setContextWindowSize(window: Int): Pipe`
Sets maximum input tokens for context.

#### `setContextWindowSettings(windowSettings: ContextWindowSettings): Pipe`
Sets context truncation method (TruncateTop, TruncateBottom, etc.).

#### `setTokenBudget(budget: TokenBudgetSettings): Pipe`
Sets advanced token budgeting configuration with support for dynamic user prompt allocation.

> [!TIP]
> **Space Optimization**: Set `preserveTextMatches = true` inside `TokenBudgetSettings` to keep context elements and conversation history entries that match keywords in the user prompt during truncation.

#### `pullGlobalContext(): Pipe`
Enables pulling context from global context bank.

#### `pullPipelineContext(): Pipe`
Enables pulling context from parent pipeline.

#### `pullBankedContext(): Pipe`
Enables pulling banked context regardless of page key configuration.

#### `updatePipelineContextOnExit(): Pipe`
Updates pipeline context when pipe completes.

#### `autoInjectContext(instruction: String): Pipe`
Automatically injects context into user prompt as text.

#### `autoTruncateContext(fillMode: Boolean = false): Pipe`
Enables automatic context truncation with optional fill mode selection.

#### `enableTextMatchingPreservation(): Pipe`
Ensures context elements containing words from the prompt survive truncation.

#### `setPageKey(key: String): Pipe`
Sets context bank page key for context isolation.

#### `enableDynamicFill(): Pipe`
Enables dynamic budget redistribution for multi-page token budgeting. **This is now the default strategy.**

#### `setMultiPageBudgetStrategy(strategy: MultiPageBudgetStrategy): Pipe`
Sets the multi-page budget allocation strategy.

#### `setPageWeights(weights: Map<String, Double>): Pipe`
Sets page weight overrides for WEIGHTED_SPLIT strategy.

---

### Token Counting

#### `enableComprehensiveTokenTracking(): Pipe`
Enables detailed token usage tracking for this pipe and its child pipes.

#### `disableComprehensiveTokenTracking(): Pipe`
Disables comprehensive token usage tracking and clears stored usage data.

#### `getTokenUsage(): TokenUsage`
Returns comprehensive usage data for this pipe and its children.

#### `getTotalInputTokens(): Int`
Returns total input tokens consumed by this pipe and nested pipes.

#### `getTotalOutputTokens(): Int`
Returns total output tokens consumed by this pipe and nested pipes.

#### `setMultiplyWindowSizeBy(value: Int): Pipe`
Sets token counting multiplier (conservative estimation).

#### `truncateContextAsString(): Pipe`
Enables aggressive string-based context truncation.

---

### LoreBook

#### `enableImmutableLoreBook(): Pipe`
Prevents lorebook modifications during execution.

#### `enableAppendLoreBookScheme(): Pipe`
Enables append-only lorebook updates (preserves history).

#### `enableLoreBookFillMode(): Pipe`
Enables the select-and-fill strategy for LoreBook selection.

---

### Model Reasoning

#### `setReasoning(): Pipe`
Enables basic model reasoning/thinking mode.

#### `setReasoning(tokens: Int): Pipe`
Enables reasoning with a specific token allocation.

#### `setReasoning(custom: String): Pipe`
Enables reasoning with vendor-specific custom settings.

---

### Streaming

#### `obtainStreamingCallbackManager(): StreamingCallbackManager`
Gets or creates the manager for handling multiple streaming callbacks.

#### `streamingCallbacks(builder: StreamingCallbackBuilder.() -> Unit): Pipe` (BedrockPipe)
Configures multiple streaming callbacks using a fluent builder.

#### `enableStreaming(callback: (suspend (String) -> Unit)? = null, showReasoning: Boolean = false): Pipe` (BedrockPipe)
Enables streaming mode with an optional callback.

#### `setStreamingCallback(callback: suspend (String) -> Unit): Pipe` (BedrockPipe)
Sets a single suspending callback for streaming chunks.

---

### Function Hooks (DITL)

#### `infix fun setValidatorFunction(func: suspend (MultimodalContent) -> Boolean): Pipe`
Sets a programmatic function to validate the AI output.

#### `setTransformationFunction(func: suspend (MultimodalContent) -> MultimodalContent): Pipe`
Sets a programmatic function to transform the AI output.

#### `setPreInitFunction(func: suspend (MultimodalContent) -> Unit): Pipe`
Sets a function called before context loading (Input Preprocessing).

#### `setPreInvokeFunction(func: suspend (MultimodalContent) -> Boolean): Pipe`
Sets a function to determine if the pipe should be skipped (Returning `true` skips).

#### `setPostGenerateFunction(func: suspend (MultimodalContent) -> Unit): Pipe`
Sets a function to execute immediately after generation, before validation.

#### `setOnFailure(func: suspend (MultimodalContent, MultimodalContent) -> MultimodalContent): Pipe`
Sets a function to handle validation failures when no branch pipe is set.

#### `setExceptionFunction(func: suspend (MultimodalContent, Throwable) -> Unit): Pipe`
Sets a function to handle exceptions during execution.

---

### Pipe Chaining

#### `setValidatorPipe(pipe: Pipe): Pipe`
Sets an AI-based pipe to validate the primary pipe's output.

#### `setTransformationPipe(pipe: Pipe): Pipe`
Sets an AI-based pipe to transform the primary pipe's output.

#### `infix fun setBranchPipe(pipe: Pipe): Pipe`
Sets a pipe to execute specifically when validation fails.

#### `setReasoningPipe(pipe: Pipe): Pipe`
Sets a pipe for chain-of-thought reasoning before the main call.

---

### Protocols

#### `setPcPContext(context: PcpContext): Pipe`
Sets Pipe Context Protocol (Tool Belt) configuration.

#### `processPcpResponse(llmResponse: String): PcpExecutionResult`
Parses and executes PCP tool calls from an LLM response.

#### `setP2PAgentList(agentList: MutableList<AgentDescriptor>): Pipe`
Sets the list of available P2P agents.

#### `setContainerPtr(ptr: P2PInterface): Pipe`
Sets a container to execute instead of this pipe (Delegation).

---

### Execution

#### `execute(content: MultimodalContent): MultimodalContent`
The main execution method. Runs the complete "Plumbing" pipeline:
1. `pre-init`
2. `context loading`
3. `pre-validation`
4. `pre-invoke check`
5. `reasoning`
6. `AI generation`
7. `validation`
8. `transformation`
9. `branch handling`

> [!IMPORTANT]
> **Error Handling**: On failure, the pipe returns a `MultimodalContent` object with the `pipeError` field populated. Always check `pipe.hasError()` or `result.hasError()` to handle faults gracefully.
