# Pipe Class API

## Table of Contents
- [Overview](#overview)
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
  - [Function Hooks](#function-hooks-1)
  - [Pipe Chaining](#pipe-chaining)
  - [Protocols](#protocols)
  - [Tracing](#tracing)
  - [Getters](#getters)
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

---

### Prompt Management

#### `setSystemPrompt(prompt: String): Pipe`
Sets the system prompt for the AI model.

**Behavior:** Stores as `rawSystemPrompt` and rebuilds with injections when `applySystemPrompt()` is called. JSON schemas, PCP context, and P2P agents are automatically injected during system prompt application.

#### `setUserPrompt(prompt: String): Pipe`
Sets the user prompt prefix.

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

**Behavior:** Critical function that reconstructs the system prompt by injecting JSON schemas, PCP context, P2P agents, and custom instructions. Must be called after changing JSON schemas or protocol settings to take effect.

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

#### `setJsonInputInstructions(instructions: String): Pipe`
Sets custom instructions for JSON input handling that override the default input format explanation.

**Behavior:** Replaces the default JSON input instructions that are injected into the system prompt when `applySystemPrompt()` is called. Use this to provide model-specific or domain-specific guidance on how to interpret the JSON input schema. If empty, uses TPipe's default input instructions.

#### `setJsonOutput(json: String): Pipe`
Sets JSON output schema as string.

**Behavior:** Triggers automatic prompt injection if `supportsNativeJson` is false. Schema is injected into system prompt when `applySystemPrompt()` is called.

#### `setJsonOutput<T>(json: T): Pipe`
Sets JSON output schema from Kotlin object.

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
Sets advanced token budgeting configuration.

**Behavior:** Enables sophisticated token management with separate budgets for user prompt, system prompt, reasoning, and output. Automatically truncates content to fit within specified limits. Overrides simple `contextWindowSize` when set.

**Tip:** Set `preserveTextMatches = true` inside `TokenBudgetSettings` (or call `enableTextMatchingPreservation()`) to keep context elements and conversation history entries that match the user prompt before the rest of the truncation budget is applied.

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

#### `autoTruncateContext(fillMode: Boolean = false): Pipe`
Enables automatic context truncation with optional fill mode selection.

**Parameters:**
- `fillMode`: If true, enables select-and-fill lorebook selection during context truncation. When active, split budgets are applied after priority lorebook selection has filled with top-weighted entries.

**Behavior:** Context is automatically truncated during execution based on `contextWindowSize` and `contextWindowTruncation` settings. Essential for preventing token overflow. When `fillMode` is true, lorebook entries are prioritized and filled first before remaining budget is split between other context components.

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

### Function Hooks

#### `setValidatorFunction(func: suspend (MultimodalContent) -> Boolean): Pipe`
Sets function to validate AI output.

**Behavior:** Called after AI generation but before transformation. If returns false, triggers failure handling (branch pipe or onFailure function). Validation occurs before any post-processing.

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

#### `setOnFailure(func: suspend (MultimodalContent, MultimodalContent) -> MultimodalContent): Pipe`
Sets function to handle validation failures.

**Behavior:** Called when validation fails and no branch pipe is configured. Can attempt to repair the output or return alternative content. If returns content with `terminatePipeline = true`, pipeline stops.

#### `setStringOnFailure(func: (String, String) -> Boolean): Pipe`
Sets string-based failure handler.

---

### Pipe Chaining

#### `setValidatorPipe(pipe: Pipe): Pipe`
Sets pipe to validate output instead of validator function.

**Behavior:** Validator pipe is executed with the AI output. If validator pipe's output contains validation failure indicators, triggers failure handling. Takes precedence over `validatorFunction`.

#### `setTransformationPipe(pipe: Pipe): Pipe`
Sets pipe to transform output instead of transformation function.

**Behavior:** Transformation pipe is executed with the AI output after successful validation. Takes precedence over `transformationFunction`.

#### `setBranchPipe(pipe: Pipe): Pipe`
Sets pipe to handle validation failures.

**Behavior:** Executed when validation fails. Takes precedence over `onFailure` function. Branch pipe's output becomes the final result.

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
Enables execution tracing with configuration.

**Behavior:** Emits trace events throughout pipe execution. Trace detail level affects performance and memory usage. Events are stored globally and can be retrieved for debugging.

#### `disableTracing(): Pipe`
Disables execution tracing.

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

### Execution

#### `execute(promptResult: String = ""): String`
Executes pipe with string input, returns string output.

**Behavior:** Wraps input in `MultimodalContent`, calls multimodal execution, returns text result. Legacy method for backward compatibility.

#### `execute(content: MultimodalContent): MultimodalContent`
Executes pipe with multimodal content, returns multimodal result.

**Behavior:** Main execution method. Follows complete execution pipeline: pre-init → context loading → pre-validation → pre-invoke check → reasoning → AI generation → validation → transformation → branch handling. Execution can be short-circuited at multiple points based on configuration and validation results.
