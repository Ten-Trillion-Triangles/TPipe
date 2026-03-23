# Pipe Class API

## Table of Contents
- [Overview](#overview)
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
  - [Pipe State Inspection](#pipe-state-inspection)
  - [Execution](#execution)

## Overview

The `Pipe` class is the core abstraction for AI model interactions in TPipe.

```kotlin
abstract class Pipe : P2PInterface, ProviderInterface
```

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
- **`applyRecursively`**: If true, propagates settings to child pipes (validator, branch, transformation, reasoning)
- **`duration`**: Timeout duration in milliseconds (default 300000 = 5 minutes)
- **`autoRetry`**: If true, sets `timeoutStrategy = Retry` for automatic retry
- **`retryLimit`**: Maximum retry attempts (default 5)
- **`customLogic`**: Optional custom retry function for `CustomLogic` strategy

**Behavior:** Starts timeout tracking when pipe executes. On timeout, behavior depends on strategy:
- `Fail`: Terminates immediately (default if `autoRetry = false` and `customLogic = null`)
- `Retry`: Automatically restores snapshot and retries up to `retryLimit` times
- `CustomLogic`: Invokes `customLogic` function to determine retry action

> ⚠️ **Warning:** Retry re-executes ALL pre-execution DITL functions (`preInitFunction`, `preValidationFunction`, etc.). These functions MUST be read-only. Writing to ContextBank or program memory will cause duplicate writes on retry. See [Timeout and Retry](../core-concepts/timeout-and-retry.md) for details.

#### `setRetryFunction(func: (suspend (pipe: Pipe, content: MultimodalContent) -> Boolean)?): Pipe`
Sets custom retry logic function for `CustomLogic` timeout strategy.

**Parameters:**
- **`func`**: Function receiving pipe reference and content, returns true to retry

**Behavior:** Called when timeout occurs and `timeoutStrategy = CustomLogic`. Function can analyze failure, repair content, or make external checks before deciding to retry. Return `true` to retry, `false` to fail.

---

### Prompt Management

#### `setSystemPrompt(prompt: String): Pipe`
Sets the system prompt for the AI model.

**Behavior:** Stores as `rawSystemPrompt` and rebuilds with injections when `applySystemPrompt()` is called. JSON schemas, PCP context, and P2P agents are automatically injected during system prompt application.

#### `setUserPrompt(prompt: String): Pipe`
Sets the user prompt prefix.

#### `enableSemanticCompression(): Pipe`
Enables automatic semantic compression for the user prompt.

**Behavior:** Turns on the `TokenBudgetSettings.compressUserPrompt` path, creating or updating the pipe's
token-budget configuration without overwriting the other budget fields. Use this when you want TPipe to apply
legend-backed prompt reduction before truncation.

#### `enableSemanticDecompression(): Pipe`
Reserves the semantic decompression hook for system-prompt injection.

**Behavior:** Flips the internal flag that `applySystemPrompt()` checks before prepending the decompression
prelude. When semantic compression is also enabled, TPipe inserts a short instruction block at the very top of
the rebuilt system prompt that tells the model that the user prompt was compressed using TPipe Semantic
Compression, explains that the compressed text should be reconstructed as closely as possible to the original
intent and data, explains the `Legend:` / `code: phrase` format and the blank-line boundary, instructs it to
read the legend first, expand repeated proper-noun codes, restore omitted glue words and syntax as faithfully
as possible, preserve quoted spans, and then continue with the rest of the prompt.

#### `compressPrompt(prompt: String, settings: SemanticCompressionSettings = SemanticCompressionSettings()): SemanticCompressionResult`
Compresses a prompt string using TPipe's semantic compression rules.

**Behavior:** Returns a compressed prompt body plus a legend that maps the short codes back to the original
repeated proper nouns. Quoted spans are preserved verbatim, the legend codes advance as `AA`, `AB`, `AC`, and
the proper-noun thresholds follow the semantic-compression spec: single tokens never code, 2-token names need 6
repeats, 3-token names need 4, 4- and 5-token names need 3, and 6+ token names need 2. The helper does not
mutate pipe state. This is the public opt-in wrapper around the same compressor used by
`TokenBudgetSettings.compressUserPrompt`.

#### `setMiddlePrompt(prompt: String): Pipe`
Sets prompt injected between input and output JSON schemas in the system prompt.

**Behavior:** The middle prompt is inserted after JSON input schema injection but before JSON output schema injection during `applySystemPrompt()`. Useful for providing context-specific instructions that should appear between the input/output format specifications.

#### `setFooterPrompt(prompt: String): Pipe`
Sets prompt added to the end of the system prompt after all other injections.

**Behavior:** The footer prompt is appended as the final element when `applySystemPrompt()` is called, appearing after JSON schemas, PCP context, P2P agents, and all other automatic injections. Ideal for final instructions or formatting requirements.

#### `copySystemPromptToUserPrompt(): Pipe`
Copies system prompt to user prompt for models that handle user prompts better.

**Behavior:** Creates a `ConverseHistory` with system prompt as developer role and user prompt as user role. Useful for models that don't properly respect system prompts. The system prompt is cleared after copying to prevent duplication.

#### `applySystemPrompt(): Pipe`
Rebuilds system prompt with all injections and configurations.

**Behavior:** Critical function that reconstructs the system prompt by injecting the semantic decompression
prelude, JSON schemas, PCP context, P2P agents, context instructions, todo lists, and custom footer text in a
stable order. Must be called after changing JSON schemas or protocol settings to take effect.

---

### Input/Output

#### `setMultimodalInput(content: MultimodalContent): Pipe`
Sets multimodal input content (text and binary data).

#### `setJsonInput(json: String): Pipe`
Sets JSON input schema as string.

**Behavior:** Triggers automatic prompt injection if `supportsNativeJson` is false. Schema is injected into system prompt when `applySystemPrompt()` is called.

#### `setJsonInput<T>(json: T, senddefaults: Boolean = true): Pipe`
Sets JSON input schema from Kotlin object.

**Behavior:** Uses reflection to generate schema. If `senddefaults` is false, optional fields are excluded from the schema.

#### `setJsonInput(kclass: KClass<*>): Pipe`
Sets JSON input schema from KClass directly.

**Behavior:** Useful for primitives (e.g., `String::class`) or classes with private constructors that cannot be instantiated for the generic version.

#### `setJsonInputInstructions(instructions: String): Pipe`
Sets custom instructions for JSON input handling that override the default input format explanation.

**Behavior:** Replaces the default JSON input instructions that are injected into the system prompt when `applySystemPrompt()` is called. Use this to provide model-specific or domain-specific guidance on how to interpret the JSON input schema. If empty, uses TPipe's default input instructions.

#### `setJsonOutput(json: String): Pipe`
Sets JSON output schema as string.

**Behavior:** Triggers automatic prompt injection if `supportsNativeJson` is false. Schema is injected into system prompt when `applySystemPrompt()` is called.

#### `setJsonOutput<T>(json: T): Pipe`
Sets JSON output schema from Kotlin object.

#### `setJsonOutput(kclass: KClass<*>): Pipe`
Sets JSON output schema from KClass directly.

**Behavior:** Useful for primitives (e.g., `String::class`) or classes with private constructors that cannot be instantiated for the generic version.

#### `setJsonOutputInstructions(instructions: String): Pipe`
Sets custom instructions for JSON output handling that override the default output format explanation.

**Behavior:** Replaces the default JSON output instructions that are injected into the system prompt when `applySystemPrompt()` is called. Use this to provide specific formatting requirements, validation rules, or output constraints beyond the basic schema. If empty, uses TPipe's default output instructions.

#### `requireJsonPromptInjection(stripExternalText: Boolean = false): Pipe`
Forces JSON prompt injection for models without native JSON support.

**Behavior:** Sets `supportsNativeJson = false` and optionally `stripNonJson = true`. When enabled, JSON schemas are injected as text instructions rather than using native API features.

---

### TodoList Integration

#### `setTodoListPageKey(key: String): Pipe`
Links this pipe to a TodoList stored in ContextBank.

**Behavior:** Sets the page key used to retrieve a TodoList from ContextBank. When `applySystemPrompt()` is called, the pipe automatically:
1. Retrieves the TodoList using this key
2. Adds instructions explaining the task format
3. Serializes the TodoList to JSON
4. Appends it to the system prompt

The AI receives the complete task list and can work on tasks accordingly. If the content object has `metadata["todoTaskNumber"]` set, that specific task is highlighted with additional focus instructions.

**Example:**
```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are a task executor.")
    .setTodoListPageKey("my-tasks")
    .applySystemPrompt()  // TodoList injected here
```

See [TodoList API](todolist.md) for complete TodoList documentation.

#### `setTodoListInstructions(instructions: String): Pipe`
Overrides the default instructions for TodoList injection.

**Behavior:** Replaces the default explanation of how to interpret the TodoList with custom instructions. Use this to guide the AI's behavior when working with tasks.

**Default instructions:**
```
You will be provided with a todo list that has a list of tasks you have been 
asked to complete. Each element on the list will contain a description of the 
task, the requirements to verify it has been completed, and whether it has been 
completed or not.
```

**Example:**
```kotlin
pipe.setTodoListInstructions("""
    Below is your task checklist. Work through each item in order.
    For each task, provide detailed findings and mark completion clearly.
""".trimIndent())
```

**Properties:**

**`todoPageKey`**
```kotlin
var todoPageKey: String = ""
```
The ContextBank page key for retrieving the TodoList. Set via `setTodoListPageKey()`.

**`todoListInstructions`**
```kotlin
var todoListInstructions: String = ""
```
Custom instructions for TodoList interpretation. Set via `setTodoListInstructions()`.

**`injectTodoList`**
```kotlin
var injectTodoList: Boolean = false
```
Internal flag indicating whether TodoList injection is enabled. Automatically set to `true` when `setTodoListPageKey()` is called.

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
Allows incomplete output when hitting token limit.

**Behavior:** When enabled, pipes won't fail if output is truncated due to token limits. Useful for generation tasks where partial output is acceptable.

#### `setRepetitionPenalty(penalty: Double): Pipe`
Sets penalty for repetitive text generation.

#### `setStopSequences(seqs: List<String>): Pipe`
Sets sequences that stop text generation.

---

### Context Management

#### `setContextWindowSize(window: Int): Pipe`
Sets maximum input tokens for context.

**Behavior:** Affects token budgeting and context truncation. When combined with `autoTruncateContext()`, context is automatically trimmed to fit within this limit.

#### `setContextWindowSettings(windowSettings: ContextWindowSettings): Pipe`
Sets context truncation method.

**Behavior:** Controls how context is truncated: `TruncateTop` removes oldest entries, `TruncateBottom` removes newest, `TruncateBoth` removes from both ends.

#### `setTokenBudget(budget: TokenBudgetSettings): Pipe`
Sets advanced token budgeting configuration with support for dynamic user prompt allocation.

**Behavior:** Enables sophisticated token management with separate budgets for user prompt, system prompt, reasoning, and output. Automatically truncates content to fit within specified limits. Overrides simple `contextWindowSize` when set.

**Dynamic User Prompt Allocation:** When `TokenBudgetSettings.userPromptSize` is set to `null`, TPipe automatically calculates the required space based on the actual token count of the user prompt. This enables optimal space utilization by allocating exactly what's needed for the user input and maximizing remaining space for context.

**Semantic Compression:** When `TokenBudgetSettings.compressUserPrompt` is `true`, TPipe will attempt semantic compression on the natural-language user prompt before truncation. This is designed for human language prompts only; structured payloads such as JSON, code, XML, or schema fragments are left to the standard budget and truncation path.
The default compressor lexicon is resource-backed, with stop-word and phrase tables loaded from
`src/main/resources/semantic-compression/` and merged with any caller-provided additions.
Common contractions are expanded before function-word stripping, and the audit helper can be used to
surface recurring prompt boilerplate that should be added to the lexicon next.

**Fluent Builders:** Call `enableSemanticCompression()` to turn this path on from the `Pipe` API, and call
`enableSemanticDecompression()` to prepend the decompression prelude that explains how the compressed prompt
should be expanded again before the rest of the system prompt is processed.

**Dynamic Allocation Process:**
1. **Automatic Sizing**: TPipe counts tokens in the actual user prompt and allocates that exact amount
2. **Overflow Handling**: If the calculated size exceeds available space, TPipe automatically reduces the allocation and truncates the user prompt to fit (when `allowUserPromptTruncation = true`)
3. **Space Optimization**: Remaining space after user prompt allocation is available for context data
4. **Cleanup**: The `userPromptSize` is reset to `null` after processing to prevent issues in subsequent calls

**Explicit vs Dynamic Allocation:**
- **Explicit** (`userPromptSize = 12000`): Reserves exactly 12,000 tokens for user input, predictable but may waste space
- **Dynamic** (`userPromptSize = null`): Allocates based on actual content size, optimal space usage but variable allocation

**Tip:** Set `preserveTextMatches = true` inside `TokenBudgetSettings` (or call `enableTextMatchingPreservation()`) to keep context elements and conversation history entries that match the user prompt before the rest of the truncation budget is applied.

#### `setTokenCountingBias(value: Double): Pipe`
Sets multiplicative adjustment for all token counts.

**Behavior:** Applies bias to token counting: `adjustedTokens = round(rawTokens * (1.0 + bias))`. Use positive values (0.1-0.3) to add safety margin when TPipe underestimates, negative values (-0.05 to -0.1) to maximize context when TPipe overestimates. Default is 0.0 (no adjustment).

**Examples:**
```kotlin
pipe.setTokenCountingBias(0.1)   // Add 10% safety margin
pipe.setTokenCountingBias(0.2)   // Add 20% safety margin  
pipe.setTokenCountingBias(-0.05) // Reduce by 5% to maximize usage
```

#### `pullGlobalContext(): Pipe`
Enables pulling context from global context bank.

**Behavior:** Context is loaded from `ContextBank` using the configured `pageKey`. Loaded before pipeline context if both are enabled.

#### `pullPipelineContext(): Pipe`
Enables pulling context from parent pipeline.

**Behavior:** Overrides `pullGlobalContext()` if both are enabled. Context is merged from the parent pipeline's context window.

#### `pullBankedContext(): Pipe`
Enables pulling banked context regardless of page key configuration.

**Behavior:** Forces the pipe to pull the default banked context from `ContextBank` even when page keys are configured. Useful when you need both the general banked context and specific page-keyed context simultaneously. Sets `pullFromBankedContext = true` internally.

#### `updatePipelineContextOnExit(): Pipe`
Updates pipeline context when pipe completes.

**Behavior:** Merges this pipe's context back into the parent pipeline after execution. Uses `emplaceLorebook` setting to determine merge strategy.

#### `autoInjectContext(instruction: String): Pipe`
Automatically injects context into user prompt.

**Behavior:** Context is injected as text into the user prompt during execution. The instruction parameter explains how to interpret the context.

#### `autoTruncateContext(fillMode: Boolean = false, fillAndSplitMode: Boolean = false): Pipe`
Enables automatic context truncation with optional fill or fill-and-split selection.

**Parameters:**
- `fillMode`: If true, enables select-and-fill lorebook selection during context truncation. When active, split budgets are applied after priority lorebook selection has filled with top-weighted entries.
- `fillAndSplitMode`: If true, enables fill mode and reserves a split budget for the rest of the top-level context. This keeps lorebook selection weighted first, then redistributes any unused lorebook budget to the rest of the context window.

**Behavior:** Context is automatically truncated during execution based on `contextWindowSize` and `contextWindowTruncation` settings. Essential for preventing token overflow. When `fillMode` is true, lorebook entries are prioritized and filled first before remaining budget is split between other context components. When `fillAndSplitMode` is true, the top-level context window reserves half of the budget for lorebook entries and half for the remaining context, but any unused lorebook space is reclaimed and handed to the rest of the context window while still preserving the existing context-elements vs conversation-history split.

#### `enableTextMatchingPreservation(): Pipe`
Ensures context elements and conversation history entries containing words from the latest prompt survive truncation before other content is considered.

**Behavior:** Sets `TokenBudgetSettings.preserveTextMatches = true`. When budgets are tight, text-matching entries get reserved tokens before the remaining content is truncated.

#### `disableTextMatchingPreservation(): Pipe`
Reverts to standard truncation ordering so every context element and conversation entry competes equally for budget.

**Behavior:** Sets `TokenBudgetSettings.preserveTextMatches = false`.

#### `setPageKey(key: String): Pipe`
Sets context bank page key for context isolation.

**Behavior:** If key contains commas, splits into multiple keys stored in `pageKeyList`. Multiple keys enable pulling context from different domains simultaneously.

#### `enableDynamicFill(): Pipe`
Enables dynamic budget redistribution for multi-page token budgeting. **This is now the default strategy.**

**Behavior:** Uses an iterative redistribution algorithm that:
1. **Initial Allocation**: Starts with priority fill estimates
2. **Usage Simulation**: Predicts actual token usage per page
3. **Dynamic Redistribution**: Sends unused budget to high-need pages
4. **Iterative Optimization**: Converges within a few passes

**Benefits:**
- **Eliminates token waste** when some pages cannot fill their allocations
- **Maximizes context utilization** across all page keys
- **Adaptive redistribution** based on actual content size

#### `setMultiPageBudgetStrategy(strategy: MultiPageBudgetStrategy): Pipe`
Sets the multi-page budget allocation strategy for advanced token budgeting.

**Available strategies:**
- `DYNAMIC_FILL`: Iterative redistribution for optimal token utilization (default)
- `EQUAL_SPLIT`: Equal budget distribution across all pages
- `WEIGHTED_SPLIT`: Budget distribution based on page weights
- `PRIORITY_FILL`: Sequential allocation until pages are full

#### `setPageWeights(weights: Map<String, Double>): Pipe`
Sets page weight overrides for WEIGHTED_SPLIT strategy.

**Usage:**
```kotlin
pipe.setPageWeights(mapOf(
    "critical" to 2.0,    // Gets 2x normal allocation
    "normal" to 1.0,      // Gets 1x normal allocation  
    "background" to 0.5   // Gets 0.5x normal allocation
))
```

**Usage Example:**
```kotlin
val pipe = BedrockPipe()
    .setPageKey("critical,normal,background")
    .enableDynamicFill()
    .autoTruncateContext()
```

**Comparison to other strategies:**
- `EQUAL_SPLIT`: Static equal budget distribution (simple, can waste tokens)
- `WEIGHTED_SPLIT`: Static weighted distribution (proportionate, no redistribution)
- `PRIORITY_FILL`: Static priority-based allocation (good, but no reuse)
- `DYNAMIC_FILL`: Dynamic redistribution (adaptive, maximal utilization)

---

### Token Counting

#### `enableComprehensiveTokenTracking(): Pipe`
Enables comprehensive token usage tracking for this pipe and its child pipes.

**Behavior:** When enabled, tracks detailed token consumption including input tokens, output tokens, and aggregated usage from all nested child pipes (validator, transformation, branch, and reasoning pipes). Provides comprehensive visibility into token usage across complex pipe hierarchies. Automatically initialized when tracing is enabled.

#### `disableComprehensiveTokenTracking(): Pipe`
Disables comprehensive token usage tracking and clears stored usage data.

**Behavior:** Turns off detailed token tracking and resets all stored usage data to free memory and disable the tracking overhead. Returns to basic token counting only.

#### `getTokenUsage(): TokenUsage`
Returns comprehensive usage data for this pipe and its children.

**Behavior:** Provides access to detailed token usage information when comprehensive tracking is enabled, or returns an empty TokenUsage object when tracking is disabled. Includes input/output tokens and child pipe usage.

**TokenUsage Methods:**
- **`getUsageBreakdown(): String`**: Returns formatted breakdown of token usage for debugging purposes, showing parent pipe usage, child pipe usage, and totals in a readable format.

**Usage Example:**
```kotlin
pipe.enableComprehensiveTokenTracking()
val result = pipe.execute("Your prompt")
val usage = pipe.getTokenUsage()
println(usage.getUsageBreakdown())
// Output:
// Parent Pipe: 150 input, 75 output
// Child Pipes:
//   validator: 25 input, 10 output
//   transformer: 30 input, 15 output
// Total: 205 input, 100 output
```

#### `getTotalInputTokens(): Int`
Returns total input tokens consumed by this pipe and nested pipes when tracking is enabled.

**Behavior:** Includes input tokens from this pipe plus the recursive totals from all child pipes when comprehensive tracking is active. Returns 0 if tracking is disabled.

#### `getTotalOutputTokens(): Int`
Returns total output tokens consumed by this pipe and nested pipes when tracking is enabled.

**Behavior:** Includes output tokens from this pipe plus the recursive totals from all child pipes when comprehensive tracking is active. Returns 0 if tracking is disabled.

#### `isComprehensiveTokenTrackingEnabled(): Boolean`
Indicates whether comprehensive token tracking is enabled on this pipe.

**Behavior:** Allows external code to check if detailed token usage data is being collected and is available through the token usage methods.

#### `setMultiplyWindowSizeBy(value: Int): Pipe`
Sets token counting multiplier.

**Behavior:** Affects how Dictionary token counting estimates tokens. Higher values provide more conservative estimates.

#### `setCountSubWordsInFirstWord(value: Boolean): Pipe`
Controls subword counting in first word.

#### `setFavorWholeWords(value: Boolean): Pipe`
Prioritizes whole words in token counting.

#### `setCountOnlyFirstWordFound(value: Boolean): Pipe`
Counts only first occurrence of words.

#### `setSplitForNonWordChar(value: Boolean): Pipe`
Enables splitting on non-word characters.

#### `setAlwaysSplitIfWholeWordExists(value: Boolean): Pipe`
Forces splitting when whole words exist.

#### `setCountSubWordsIfSplit(value: Boolean): Pipe`
Counts subwords when splitting occurs.

#### `setNonWordSplitCount(value: Int): Pipe`
Sets count for non-word character splits.

#### `truncateContextAsString(): Pipe`
Enables string-based context truncation.

**Behavior:** Context is converted to a single string before truncation rather than truncating individual entries. More aggressive but can also chop content into a potentially incomplete state.

#### `getTruncationSettings(): TruncationSettings`
Returns the current truncation settings bundled with any multi-page configuration that has been applied to the pipe.

**Behavior:**
- Includes all token counting flags (multipliers, split rules, etc.)
- Mirrors the current `loreBookFillMode` state through the returned `fillMode` flag
- Mirrors the current fill-and-split state through the returned `fillAndSplitMode` flag
- Surfaces any multi-page budget strategy and page weights pulled from `tokenBudgetSettings`

**Integration:** Useful for passing a single settings object into the new helper functions such as `selectAndFillLoreBookContextWithSettings()` or string-based truncation helpers.

---

### LoreBook

#### `enableImmutableLoreBook(): Pipe`
Prevents lorebook modifications during execution.

**Behavior:** Sets `emplaceLorebook = false`. Lorebook entries cannot be updated or replaced during context merging operations.

#### `enableAppendLoreBookScheme(): Pipe`
Enables append-only lorebook updates.

**Behavior:** Sets `appendLoreBook = true`. New information is added to existing lorebook entries rather than replacing them. Can lead to contradictory information but preserves historical data.

#### `enableLoreBookFillMode(): Pipe`
Enables the select-and-fill strategy for LoreBook selection used during context truncation.

**Behavior:** Sets `loreBookFillMode = true`. When enabled, `selectAndTruncateContext()` first runs `selectAndFillLoreBookContext()` using the full budget, then truncates context elements and conversation history with any remaining tokens, ensuring the lorebook crowding is capped by the requested token allocation.

---

### Model Reasoning

#### `setReasoning(): Pipe`
Enables model reasoning/thinking mode.

**Behavior:** Enables basic reasoning mode. Behavior depends on model support - some models have native reasoning, others use TPipe's reasoning pipe system.

#### `setReasoning(tokens: Int): Pipe`
Enables reasoning with token allocation.

**Behavior:** Sets `modelReasoningSettingsV2` with specific token budget for reasoning. Reasoning output is truncated if it exceeds this limit.

#### `setReasoning(custom: String): Pipe`
Enables reasoning with custom settings.

**Behavior:** Sets `modelReasoningSettingsV3` for vendor-specific reasoning configuration. Format depends on the AI provider.

---

### Streaming

#### `obtainStreamingCallbackManager(): StreamingCallbackManager`
Gets or creates the streaming callback manager for this pipe.

**Behavior:** Lazy-initializes the manager on first access. Returns the manager instance for direct callback manipulation. Use this for dynamic callback management (adding/removing callbacks at runtime).

**Example:**
```kotlin
val manager = pipe.obtainStreamingCallbackManager()
manager.addCallback { chunk -> print(chunk) }
manager.removeCallback(someCallback)
```

#### `streamingCallbacks(builder: StreamingCallbackBuilder.() -> Unit): Pipe` (BedrockPipe)
Configures multiple streaming callbacks using builder pattern.

**Behavior:** Registers multiple independent callbacks to receive streaming chunks. Each callback can perform different operations (UI updates, logging, metrics) without interfering with each other. Supports configurable execution mode (sequential or concurrent) and automatic error isolation. Automatically enables streaming mode.

**Example:**
```kotlin
pipe.streamingCallbacks {
    add { chunk -> print(chunk) }
    add { chunk -> logToFile(chunk) }
    add { chunk -> updateMetrics(chunk) }
    concurrent()  // or sequential()
    onError { e, chunk -> println("Error: ${e.message}") }
}
```

**Parameters:**
- **`builder`**: Lambda that configures the StreamingCallbackBuilder

**Returns:** This pipe instance for method chaining

**See Also:** [Streaming Callbacks Guide](../core-concepts/streaming-callbacks.md)

#### `enableStreaming(callback: (suspend (String) -> Unit)? = null, showReasoning: Boolean = false): Pipe` (BedrockPipe)
Enables streaming mode with optional callback.

**Behavior:** Switches to streaming API calls where tokens arrive incrementally. If callback is provided, it's invoked for each chunk. If `showReasoning` is true, propagates streaming to reasoning pipes recursively.

**Example:**
```kotlin
pipe.enableStreaming { chunk -> print(chunk) }
```

#### `setStreamingCallback(callback: suspend (String) -> Unit): Pipe` (BedrockPipe)
Sets a suspending callback for streaming chunks.

**Behavior:** Registers a single callback that receives each text chunk as it arrives. Automatically enables streaming mode. Use this for async operations within the callback (database writes, network calls, etc.).

**Example:**
```kotlin
pipe.setStreamingCallback { chunk ->
    delay(10)
    logToDatabase(chunk)
}
```

#### `setStreamingCallback(callback: (String) -> Unit): Pipe` (BedrockPipe)
Sets a non-suspending callback for streaming chunks.

**Behavior:** Convenience overload for simple synchronous callbacks. Automatically wraps the callback in a suspending lambda. Use this for simple operations like printing or basic text accumulation.

**Example:**
```kotlin
pipe.setStreamingCallback { chunk -> print(chunk) }
```

#### `disableStreaming(): Pipe` (BedrockPipe)
Disables streaming mode and clears all callbacks.

**Behavior:** Switches back to standard (non-streaming) API calls. Clears both legacy single callback and all multi-callback manager callbacks to prevent memory leaks.

> ℹ️ **Note:** Provider-specific methods (BedrockPipe) are available in provider implementations. Base Pipe class provides `obtainStreamingCallbackManager()` and `emitStreamingChunk()` for all providers.

---

### AWS Bedrock Guardrails (BedrockPipe)

AWS Bedrock Guardrails provide content moderation and safety controls. These methods are specific to BedrockPipe.

#### `setGuardrail(identifier: String, version: String = "DRAFT", enableTrace: Boolean = false): BedrockPipe`
Configures AWS Bedrock Guardrail for content filtering.

**Parameters:**
- `identifier`: Guardrail ID or ARN from AWS Bedrock Console
- `version`: Guardrail version number or "DRAFT" (default: "DRAFT")
- `enableTrace`: Enable guardrail tracing for debugging (default: false)

**Behavior:** Guardrails automatically evaluate both user inputs and model responses against configured policies including content filters, denied topics, sensitive information filters, and word filters. Requires `bedrock:ApplyGuardrail` IAM permission.

**Example:**
```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .setGuardrail("abc123def456", "1", enableTrace = true)
```

#### `enableFullGuardrailTrace(): BedrockPipe`
Enables comprehensive guardrail tracing including non-detected content.

**Behavior:** Provides enhanced debugging for content filters, denied topics, PII detection, and contextual grounding policies. Sets trace mode to "enabled_full".

**Example:**
```kotlin
val pipe = BedrockPipe()
    .setGuardrail("abc123def456", "DRAFT")
    .enableFullGuardrailTrace()
```

#### `clearGuardrail(): BedrockPipe`
Removes guardrail configuration and disables content filtering.

**Example:**
```kotlin
pipe.clearGuardrail()
```

#### `suspend fun applyGuardrailStandalone(content: String, source: String = "INPUT", fullOutput: Boolean = false): ApplyGuardrailResponse?`
Evaluates content against configured guardrail without invoking foundation models.

**Parameters:**
- `content`: Text content to evaluate
- `source`: "INPUT" for user input or "OUTPUT" for model output (default: "INPUT")
- `fullOutput`: Return full assessment including non-detected content (default: false)

**Returns:** `ApplyGuardrailResponse` containing action taken and detailed assessments, or null on failure.

**Throws:**
- `IllegalStateException` if guardrail is not configured
- `IllegalArgumentException` if client is not initialized

**Behavior:** Allows independent content validation at any stage of application flow. Useful for pre-validating user input, checking content at multiple pipeline stages, or implementing custom content moderation workflows. Requires prior guardrail configuration via `setGuardrail()`.

**Example:**
```kotlin
runBlocking {
    pipe.init()
    
    val assessment = pipe.applyGuardrailStandalone(
        content = userInput,
        source = "INPUT"
    )
    
    when (assessment?.action) {
        "GUARDRAIL_INTERVENED" -> println("Content blocked")
        "NONE" -> println("Content passed checks")
    }
}
```

**See Also:** [AWS Bedrock Guardrails Guide](../bedrock/guardrails.md) for comprehensive documentation.

---

### Function Hooks

#### `infix fun setValidatorFunction(func: suspend (MultimodalContent) -> Boolean): Pipe`
Sets function to validate AI output.

**Behavior:** Called after AI generation but before transformation. If returns false, triggers failure handling (branch pipe or onFailure function). Validation occurs before any post-processing. Can be called using infix notation: `pipe setValidatorFunction { ... }`.

#### `setStringValidatorFunction(func: (String) -> Boolean): Pipe`
Sets string-based validator function.

#### `setTransformationFunction(func: suspend (MultimodalContent) -> MultimodalContent): Pipe`
Sets function to transform AI output.

**Behavior:** Called after successful validation. Can modify the output content before it's returned. If transformation fails, triggers failure handling.

#### `setStringTransformationFunction(func: (String) -> String): Pipe`
Sets string-based transformation function.

#### `setPreInitFunction(func: suspend (MultimodalContent) -> Unit): Pipe`
Sets function called before context loading.

**Behavior:** First function called during execution. Allows preprocessing input before any context merging or prompt construction occurs.

#### `setPreValidationFunction(func: suspend (ContextWindow, MultimodalContent?) -> ContextWindow): Pipe`
Sets function for context adjustment before validation.

**Behavior:** Called after context loading but before prompt injection. Last chance to modify context before AI execution. Overrides `preValidationMiniBankFunction` if both are set.

#### `setPreValidationMiniBankFunction(func: suspend (MiniBank, MultimodalContent?) -> MiniBank): Pipe`
Sets function for MiniBank context adjustment.

**Behavior:** Alternative to `preValidationFunction` when using multiple page keys. Only called if `preValidationFunction` is not set.

#### `setPreInvokeFunction(func: suspend (MultimodalContent) -> Boolean): Pipe`
Sets function to determine if pipe should be skipped.

**Behavior:** Called just before AI invocation. If returns true, pipe execution is skipped entirely and content passes through unchanged. Useful for conditional logic.

#### `setPostGenerateFunction(func: suspend (MultimodalContent) -> Unit): Pipe`
Sets function to execute immediately after AI generates content.

**Behavior:** Called right after AI generation, before any validation or transformation. Useful for caching raw output, logging, or capturing content before validation steps modify it.

#### `setOnFailure(func: suspend (MultimodalContent, MultimodalContent) -> MultimodalContent): Pipe`
Sets function to handle validation failures.

**Behavior:** Called when validation fails and no branch pipe is configured. Can attempt to repair the output or return alternative content. If returns content with `terminatePipeline = true`, pipeline stops.

#### `setStringOnFailure(func: (String, String) -> Boolean): Pipe`
Sets string-based failure handler.

#### `setExceptionFunction(func: suspend (MultimodalContent, Throwable) -> Unit): Pipe`
Sets function to handle exceptions during AI execution.

**Parameters:**
- `func`: Function that receives the content state and exception when errors occur

**Behavior:** Called whenever an exception is thrown during `generateContent()` execution. Provides access to both the content object state and the exception for debugging purposes. Useful for logging, error recovery, or custom error handling logic.

**Usage Example:**
```kotlin
pipe.setExceptionFunction { content, exception ->
    println("Exception in pipe: ${exception.message}")
    println("Content state: ${content.text}")
    // Custom error handling logic
}
```

---

### Pipe Chaining

#### `setValidatorPipe(pipe: Pipe): Pipe`
Sets pipe to validate output using AI-based analysis.

**Parameters:**
- `pipe`: The validator pipe to use for validation

**Behavior:** Validator pipe is executed with the AI-generated output. The validator pipe's output is checked for termination status only - the actual content output is discarded. The original generated content flows to `validatorFunction` and all downstream operations. This allows AI-based validation analysis without modifying the content that continues through the pipeline.

> ⚠️ **Important:** The validator pipe's text output and modifications are not passed forward. Only its termination flag (`shouldTerminate()`) is respected.

#### `setTransformationPipe(pipe: Pipe): Pipe`
Sets pipe to transform output instead of transformation function.

**Behavior:** Transformation pipe is executed with the AI output after successful validation. Takes precedence over `transformationFunction`.

#### `infix fun setBranchPipe(pipe: Pipe): Pipe`
Sets pipe to handle validation failures.

**Behavior:** Executed when validation fails. Takes precedence over `onFailure` function. Branch pipe's output is passed through transformation pipe and transformation function (if set) before becoming the final result. Can be called using infix notation: `pipe setBranchPipe failurePipe`.

#### `setReasoningPipe(pipe: Pipe): Pipe`
Sets pipe for chain-of-thought reasoning.

**Behavior:** Executed before the main AI call to generate reasoning content. Reasoning output is injected into the main prompt. Enables chain-of-thought for models without native reasoning support.

---

### Protocols

#### `setPcPContext(context: PcpContext): Pipe`
Sets Pipe Context Protocol configuration.

**Behavior:** PCP tools are automatically injected into system prompt when `applySystemPrompt()` is called. Tools become available to the AI during execution.

#### `setPcPDescription(description: String): Pipe`
Sets custom PCP description that overrides the default tool explanation injected into the system prompt.

**Behavior:** Replaces TPipe's default PCP tool description with custom instructions when `applySystemPrompt()` is called. Use this to provide model-specific guidance on how to use PCP tools or to customize the tool invocation format. If empty, uses TPipe's default PCP instructions.

#### `processPcpResponse(llmResponse: String): PcpExecutionResult`
Processes PCP requests from LLM response.

**Behavior:** Parses AI output for PCP tool calls and executes them. Returns execution results that can be fed back to the AI or used by the application.

#### `setP2PAgentList(agentList: MutableList<AgentDescriptor>): Pipe`
Sets list of available P2P agents.

**Behavior:** Agent list is automatically injected into system prompt when `applySystemPrompt()` is called. Agents become available for the AI to call during execution.

#### `setP2PDescription(description: String): Pipe`
Sets custom P2P agent description that overrides the default agent explanation injected into the system prompt.

**Behavior:** Replaces TPipe's default P2P agent description with custom instructions when `applySystemPrompt()` is called. Use this to provide model-specific guidance on how to interact with P2P agents or to customize the agent invocation format. If empty, uses TPipe's default P2P instructions.

#### `setContainerPtr(ptr: P2PInterface): Pipe`
Sets container to execute instead of this pipe.

**Behavior:** When set, `execute()` calls delegate to the container's `executeLocal()` method instead of running the pipe's own logic. Enables complex orchestration patterns.

---

### Tracing

#### `enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipe`
Enables execution tracing with configuration and comprehensive token tracking.

**Behavior:** Emits trace events throughout pipe execution and automatically enables comprehensive token usage tracking to provide detailed token consumption data in traces. Trace detail level affects performance and memory usage. Events are stored globally and can be retrieved for debugging.

#### `disableTracing(): Pipe`
Disables execution tracing.

---

### Multi-Stream Tracing

TPipe supports broadcasting trace events to multiple trace IDs simultaneously. This is useful for complex orchestration (like Splitters) where a pipe's events should appear in both its individual trace and the orchestrator's trace.

#### `addTraceId(id: String)`
Adds a trace ID to the active set. Events will be broadcast to this ID in addition to the pipe's own ID.

#### `removeTraceId(id: String)`
Removes a trace ID from the active set. Events will no longer be broadcast to this ID.

---

### Getters

#### `getProviderEnum(): ProviderName`
Returns the configured AI provider.

#### `getModelName(): String`
Returns the configured model name.

#### `getP2PAgentList(): List<AgentDescriptor>?`
Returns list of available P2P agents.

#### `getTruncationSettings(): TruncationSettings`
Returns current token truncation settings.

**Behavior:** Builds settings from current token counting configuration. Used internally for consistent token counting across the pipe.

#### `getReasoningContent(): String`
Returns reasoning content from last execution.

#### `selectGlobalContextMode(): String`
Returns selected global context mode.

#### `countBinaryTokens(content: MultimodalContent, truncationSettings: TruncationSettings): Int`
Counts tokens in binary content.

#### `countTokens(input: Boolean, content: MultimodalContent): Int`
Counts tokens in content for input or output estimation.

#### `toPipeSettings(): PipeSettings`
Converts pipe configuration to PipeSettings object.

---

### Error Handling

#### `hasError(): Boolean`
Checks if pipe has captured an error during execution.

**Returns:** `true` if an error is stored, `false` otherwise.

**Example:**
```kotlin
val result = pipe.execute("input")
if (pipe.hasError()) {
    println("Pipe failed: ${pipe.getErrorMessage()}")
}
```

#### `getErrorMessage(): String`
Gets the error message from the last captured error.

**Returns:** Error message string, or empty string if no error.

#### `getErrorType(): TraceEventType?`
Gets the type of error that occurred.

**Returns:** `TraceEventType` (PIPE_FAILURE, API_CALL_FAILURE, VALIDATION_FAILURE, TRANSFORMATION_FAILURE), or `null` if no error.

#### `clearError()`
Clears the stored error information.

**Usage:** Call before reusing a pipe to reset error state.

#### `lastError: PipeError?`
Direct access to the complete error object containing exception, event type, phase, pipe name, pipe ID, and timestamp.

> ℹ️ **Note:** Errors are automatically captured when trace events with failure types are logged. The error persists until explicitly cleared or the pipe executes successfully.

---

### Pipe State Inspection

These methods allow you to read the current configuration state of a pipe without modifying it. They are useful for validation logic, DSL builders, and debugging.

#### `copyTokenBudgetSettings(): TokenBudgetSettings?`

Returns a detached copy of the current token budget settings, or `null` if token budgeting is not configured.

**Behavior:** The returned copy is safe to inspect or reuse without affecting the pipe's internal state. Map values (like `pageWeights`) are also copied.

```kotlin
val budget = pipe.copyTokenBudgetSettings()
if (budget != null)
{
    println("Context window: ${budget.contextWindowSize}")
    println("Max tokens: ${budget.maxTokens}")
}
```

#### `isAutoTruncateContextEnabled(): Boolean`

Returns `true` when legacy automatic truncation is enabled for this pipe.

```kotlin
if (pipe.isAutoTruncateContextEnabled())
{
    println("Legacy auto-truncation is active")
}
```

#### `hasContextOverflowProtectionConfigured(): Boolean`

Returns `true` when the pipe has any overflow protection path configured — either token budgeting or legacy auto truncation.

**Behavior:** This is the method the Manifold DSL uses to validate that worker pipes have overflow protection before startup.

```kotlin
if (!pipe.hasContextOverflowProtectionConfigured())
{
    throw IllegalStateException("Pipe needs overflow protection before use in a manifold")
}
```

#### `getConfiguredContextWindowSize(): Int`

Returns the current context window size in tokens.

```kotlin
val windowSize = pipe.getConfiguredContextWindowSize()
println("Context window: $windowSize tokens")
```

#### `getConfiguredMaxTokens(): Int`

Returns the current maximum output token count.

```kotlin
val maxTokens = pipe.getConfiguredMaxTokens()
println("Max output tokens: $maxTokens")
```

#### `getSystemPromptText(): String`

Returns the current system prompt text bound to this pipe.

```kotlin
val prompt = pipe.getSystemPromptText()
println("System prompt length: ${prompt.length} chars")
```

#### `truncateModuleContextSuspend(): Pipe`

Suspend-safe truncation entry point. Subclasses that need remote-aware lorebook selection (e.g., loading lorebook data from a remote MemoryServer) should override this instead of the synchronous `truncateModuleContext()`.

**Behavior:** The default implementation delegates to `truncateModuleContext()`. Provider modules can override this to perform async operations during truncation.

```kotlin
// Called internally during pipe execution when autoTruncateContext is enabled
// Override in custom provider modules:
override suspend fun truncateModuleContextSuspend(): Pipe
{
    // Perform async lorebook loading, then truncate
    loadRemoteLorebook()
    return truncateModuleContext()
}
```

---

### Execution

#### `execute(promptResult: String = ""): String`
Executes pipe with string input, returns string output.

**Behavior:** Wraps input in `MultimodalContent`, calls multimodal execution, returns text result. Legacy method for backward compatibility.

#### `execute(content: MultimodalContent): MultimodalContent`
Executes pipe with multimodal content, returns multimodal result.

**Behavior:** Main execution method. Follows complete execution pipeline: pre-init → context loading → pre-validation → pre-invoke check → reasoning → AI generation → validation → transformation → branch handling. Execution can be short-circuited at multiple points based on configuration and validation results.

**Error Handling:** On failure, returns empty `MultimodalContent` with `pipeError` field populated. Check `pipe.hasError()` or `result.hasError()` to detect failures.
