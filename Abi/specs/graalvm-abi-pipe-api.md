# GraalVM Native ABI Specification — Pipe API

**Version:** 0.1.0-draft
**Created:** 2026-05-05
**Status:** Working Draft - In Progress
**Spec:** graalvm-abi-pipe-api.md
**Requires:** graalvm-abi-core-infrastructure.md (handle system, reference counting, TPipe_Result, LibraryState) and graalvm-abi-core-types.md (MultimodalContent, TokenBudgetSettings, enums)

---

## 1. Overview

### 1.1 Purpose

This document defines the complete Pipe API — the primary surface for LLM interaction across the TPipe GraalVM Native ABI. The Pipe API exposes every operation a caller needs to configure a pipe, execute LLM calls (synchronously or asynchronously), attach streaming callbacks, wire validation/transformation hooks, chain pipes together, and report token usage.

Every function in this spec returns `TPipe_Result` (an integer error code) unless otherwise noted. `TPIPE_OK` (0) indicates success.

### 1.2 Relationship to Core Types

The Pipe API operates exclusively through the handle system defined in `graalvm-abi-core-types.md`:

| Handle Type | Constructed By | Used For |
|---|---|---|
| `TPipe_PipeHandle` | `TPipe_Pipe_create()` | The pipe itself — primary API entry point |
| `TPipe_ContentHandle` | Caller via `TPipe_Content_*` | Input/output of execute calls |
| `TPipe_TokenBudgetHandle` | `TPipe_TokenBudget_create()` | Token budget configuration |
| `TPipe_ContextHandle` | `TPipe_Context_create()` | Per-run context window |
| `TPipe_MiniBankHandle` | `TPipe_MiniBank_create()` | Multi-page context |
| `TPipe_PCPHandle` | `TPipe_PCP_createRequest()` | PCP tool call configuration |
| `TPipe_StdioContextHandle` | `TPipe_StdioContext_create()` | PCP STDIO transport config |
| `TPipe_P2PDescriptorHandle` | `TPipe_P2PDescriptor_create()` | P2P agent description |
| `TPipe_ErrorHandle` | `TPipe_Error_create()` or via `out` param | Error reporting |
| `TPipe_AsyncHandle` | `TPipe_Pipe_executeContentAsync()` | Async operation tracking |

All reference counting rules from core-types apply: `TPipe_Handle_addRef()` / `TPipe_Handle_release()` manage lifetime. `TPIPE_INVALID_HANDLE` (0) is the null value.

### 1.3 API Organization

```
TPipe_Pipe_*           — Pipe lifecycle, init, configuration
TPipe_Pipe_execute*    — Synchronous execution
TPipe_Pipe_*Async*     — Async execution
TPipe_Async_*          — Async handle lifecycle
TPipe_Pipe_set*        — Configuration setters (builder pattern, return handle for chaining)
TPipe_Pipe_get*        — Configuration getters
TPipe_Pipe_setCallback* — Callback function registration
```

### 1.4 Execution Flow

The typical lifecycle of a pipe:

```
1. TPipe_init()                          // Library-level init
2. TPipe_Pipe_create()                   // Create pipe handle
3. TPipe_Pipe_setProvider()              // AWS, Ollama, OpenRouter, etc.
4. TPipe_Pipe_setModel()                 // Model name
5. TPipe_Pipe_setSystemPrompt()           // Optional
6. TPipe_Pipe_setTokenBudget()           // Optional
7. TPipe_Pipe_init()                     // Initialize the pipe
8. TPipe_Pipe_execute() or executeContentAsync() // Run
9. TPipe_Pipe_getTokenUsage()            // Optional: report usage
10. TPipe_Pipe_release()                  // Release pipe handle
```

---

## 2. Pipe Lifecycle

### 2.1 Handle Creation and Destruction

```c
// Create a new Pipe instance. The pipe has no provider, no model, and is not initialized.
// All fields are at default values.
TPipe_PipeHandle TPipe_Pipe_create(void);

// Increment/decrement reference count.
// A new pipe starts at refcount = 1. Caller must release when done.
void TPipe_Pipe_addRef(TPipe_PipeHandle handle);
void TPipe_Pipe_release(TPipe_PipeHandle handle);
```

### 2.2 Initialization

```c
// Initialize the pipe. This performs provider-specific setup:
//   - Loads API credentials from environment/config
//   - Validates the model name is supported
//   - Allocates internal state (thread pool, coroutine scope, etc.)
//
// Idempotent: calling init() on an already-initialized pipe is a no-op and returns TPIPE_OK.
// Re-initializing after shutdown (via TPipe_shutdown()) is supported.
TPipe_Result TPipe_Pipe_init(TPipe_PipeHandle handle);

// Check if a pipe has been initialized.
int TPipe_Pipe_isInitialized(TPipe_PipeHandle handle);
```

### 2.3 Abort

```c
// Abort any in-flight execution on this pipe.
// Cancels the active coroutine job. If no execution is running, this is a no-op.
// After abort(), the pipe is ready for another execute call.
TPipe_Result TPipe_Pipe_abort(TPipe_PipeHandle handle);
```

---

## 3. Provider and Model Configuration

### 3.1 Provider

```c
// Set the AI provider. Required before init().
// provider: one of TPIPE_PROVIDER_AWS, TPIPE_PROVIDER_OLLAMA, TPIPE_PROVIDER_OPENROUTER,
//           TPIPE_PROVIDER_GPT, TPIPE_PROVIDER_GEMINI, TPIPE_PROVIDER_NAI
// Returns the handle for chaining.
TPipe_PipeHandle TPipe_Pipe_setProvider(TPipe_PipeHandle handle, TPipe_ProviderName provider);

// Get the current provider. Returns TPIPE_PROVIDER_GPT if not set.
TPipe_ProviderName TPipe_Pipe_getProvider(TPipe_PipeHandle handle);
```

### 3.2 Model

```c
// Set the model name. Required before init().
TPipe_PipeHandle TPipe_Pipe_setModel(TPipe_PipeHandle handle, const char* modelName);
// modelName: e.g. "anthropic.claude-3-sonnet-20240229-v1:0", "gpt-4o", "llama3"
// The model name format is provider-specific. TPipe does not validate the format;
// validation happens at init() when the provider's API is contacted.

const char* TPipe_Pipe_getModel(TPipe_PipeHandle handle);
// Returns the configured model name, or NULL if not set.
// Pointer is valid until next ABI call on this handle; do not free.

const char* TPipe_Pipe_getSystemPrompt(TPipe_PipeHandle handle);
// Returns system prompt; valid until next ABI call on this handle; do not free.

const char* TPipe_Pipe_getUserPrompt(TPipe_PipeHandle handle);
// Returns user prompt; valid until next ABI call on this handle; do not free.

### 4.1 Prompt Fields

```c
// Set the system prompt. Instructs the LLM on its role and behavior.
// TPipe copies the string internally.
TPipe_PipeHandle TPipe_Pipe_setSystemPrompt(TPipe_PipeHandle handle, const char* prompt);
const char* TPipe_Pipe_getSystemPrompt(TPipe_PipeHandle handle);

// Set the user prompt template. Injected at execution time via execute() or executeContent().
// Can include placeholders that are replaced at runtime.
TPipe_PipeHandle TPipe_Pipe_setUserPrompt(TPipe_PipeHandle handle, const char* prompt);
const char* TPipe_Pipe_getUserPrompt(TPipe_PipeHandle handle);

// Middle and footer prompts are injected after the user prompt but before the model response.
// Useful for continuation prompts or response framing.
TPipe_PipeHandle TPipe_Pipe_setMiddlePrompt(TPipe_PipeHandle handle, const char* prompt);
TPipe_PipeHandle TPipe_Pipe_setFooterPrompt(TPipe_PipeHandle handle, const char* prompt);
```

### 4.2 Prompt Mode

```c
// Set how TPipe manages prompt context.
// mode: TPIPE_PROMPT_SINGLE (no history), TPIPE_PROMPT_CHAT (model-managed history),
//       TPIPE_PROMPT_INTERNAL_CONTEXT (TPipe-managed history)
TPipe_PipeHandle TPipe_Pipe_setPromptMode(TPipe_PipeHandle handle, TPipe_PromptMode mode);
TPipe_PromptMode TPipe_Pipe_getPromptMode(TPipe_PipeHandle handle);
```

### 4.3 Lorebook Control

```c
// Control whether lorebook entries are injected into the context window.
// emplace: if 1, matching lorebook entries are added to context.
// append: if 1, lorebook entries are appended rather than replacing existing content.
TPipe_PipeHandle TPipe_Pipe_setEmplaceLorebook(TPipe_PipeHandle handle, int emplace);
TPipe_PipeHandle TPipe_Pipe_setAppendLoreBook(TPipe_PipeHandle handle, int append);
TPipe_PipeHandle TPipe_Pipe_setLoreBookFillMode(TPipe_PipeHandle handle, int fillMode);
TPipe_PipeHandle TPipe_Pipe_setLoreBookFillAndSplitMode(TPipe_PipeHandle handle, int mode);
```

---

## 5. Numeric Parameter Configuration

### 5.1 Temperature, TopP, TopK

```c
TPipe_PipeHandle TPipe_Pipe_setTemperature(TPipe_PipeHandle handle, double temp);
// temp: typically 0.0 to 2.0. Default is provider-dependent (often 0.7).

TPipe_PipeHandle TPipe_Pipe_setTopP(TPipe_PipeHandle handle, double topP);
// topP: nucleus sampling threshold. Typically 0.0 to 1.0. Default varies by provider.

TPipe_PipeHandle TPipe_Pipe_setTopK(TPipe_PipeHandle handle, int topK);
// topK: number of top tokens to consider. Default varies by provider.

TPipe_PipeHandle TPipe_Pipe_setMaxTokens(TPipe_PipeHandle handle, int maxTokens);
// maxTokens: maximum tokens the model can generate. Does not count input tokens.
```

### 5.2 Repetition and Presence

```c
TPipe_PipeHandle TPipe_Pipe_setRepetitionPenalty(TPipe_PipeHandle handle, double penalty);
// repetitionPenalty: penalize repeated tokens. Typically 1.0 to 2.0. Default is 1.0 (no penalty).

TPipe_PipeHandle TPipe_Pipe_setPresencePenalty(TPipe_PipeHandle handle, double penalty);
// presencePenalty: penalize token presence. Default is 0.0.

TPipe_PipeHandle TPipe_Pipe_setSeed(TPipe_PipeHandle handle, int seed);
// seed: deterministic sampling. -1 means no seed (random). If seed is supported by the
// provider, identical seeds produce identical outputs.

TPipe_PipeHandle TPipe_Pipe_setN(TPipe_PipeHandle handle, int completions);
// completions: number of completions to generate. Default is 1.
// Note: not all providers support n > 1. TPipe may simulate it if the provider does not.
```

### 5.3 Stop Sequences and User

```c
TPipe_PipeHandle TPipe_Pipe_setStopSequences(TPipe_PipeHandle handle, TPipe_ListHandle sequences);
// sequences: List<const char*>. TPipe copies the strings. Pass empty list to clear.
// When the model generates any of these sequences, generation stops.

TPipe_PipeHandle TPipe_Pipe_setUser(TPipe_PipeHandle handle, const char* userId);
// userId: identifier for the user making the request. Some providers use this for
// per-user rate limiting or content filtering.
```

### 5.4 Logit Bias and Word Control

```c
TPipe_PipeHandle TPipe_Pipe_setLogitBias(TPipe_PipeHandle handle, TPipe_MapHandle biasMap);
// biasMap: Map<Int, Double> mapping token IDs (Int key) to bias values (Double).
//          Bias values typically range from -100.0 to 100.0.
//          Positive values increase likelihood; negative values decrease likelihood.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_banWords(TPipe_PipeHandle handle, TPipe_ListHandle wordList);
// wordList: List<const char*> — words/phrases to ban from generation.
//           TPipe converts words to approximate token IDs internally.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_encourageWords(TPipe_PipeHandle handle,
                                           TPipe_ListHandle wordList,
                                           double bias);
// wordList: List<const char*> — words/phrases to encourage.
// bias: positive bias value (typically 1.0 to 100.0).
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_clearLogitBias(TPipe_PipeHandle handle);
// Clears all logit bias settings.
// Returns the handle for chaining.
```

### 5.5 Repetition Control

```c
TPipe_PipeHandle TPipe_Pipe_setRepetitionControl(TPipe_PipeHandle handle, double penalty);
// penalty: combined frequency + presence penalty (0.0 to 2.0).
//          Sets both repetitionPenalty and presencePenalty to the same value.
// Returns the handle for chaining.
```

### 5.6 Deterministic Generation

```c
TPipe_PipeHandle TPipe_Pipe_enableDeterministicGeneration(TPipe_PipeHandle handle, int* seed);
// seed: nullable Int. If NULL is passed, uses current timestamp as seed.
//       If provided, must be a valid integer seed value.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_disableDeterministicGeneration(TPipe_PipeHandle handle);
// Clears the seed, disabling deterministic generation.
// Returns the handle for chaining.
```

### 5.7 Reasoning Configuration

```c
TPipe_PipeHandle TPipe_Pipe_setReasoning(TPipe_PipeHandle handle);
// Enable built-in reasoning/thinking mode for models that support it natively.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_setReasoningWithTokens(TPipe_PipeHandle handle, int tokens);
// tokens: maximum reasoning token budget. If 0, uses provider default.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_setReasoningWithCustom(TPipe_PipeHandle handle,
                                                    const char* customSettings);
// customSettings: vendor-specific reasoning configuration string.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_disableReasoning(TPipe_PipeHandle handle);
// Disable reasoning mode.
// Returns the handle for chaining.
```

### 5.8 Token Counting Bias

```c
TPipe_PipeHandle TPipe_Pipe_setTokenCountingBias(TPipe_PipeHandle handle, double bias);
// bias: token counting adjustment value (can be negative).
// Returns the handle for chaining.
```

---

## 6. Execution

### 6.1 Synchronous String Execution

```c
// Execute with a simple string prompt.
// Internally wraps the string in MultimodalContent, calls executeContent(), returns result.text.
// This is a convenience wrapper for the common case.
const char* TPipe_Pipe_execute(TPipe_PipeHandle handle, const char* prompt);
// Returns a pointer to the response text. Pointer is owned by TPipe; valid until
// the pipe handle is released or shutdown() is called. Do NOT free the pointer.
//
// On error: returns NULL and sets the pipe's error state.
// Use TPipe_Pipe_getErrorMessage() to retrieve the error string after a NULL return.
```

### 6.2 Synchronous Content Execution

```c
// Execute with a full MultimodalContent object.
// This is the primary execution entry point for advanced use cases (binary content,
// context injection, tool call results, etc.).
TPipe_Result TPipe_Pipe_executeContent(TPipe_PipeHandle handle,
                                       TPipe_ContentHandle input,
                                       TPipe_ContentHandle* out_result);
// input: the MultimodalContent to send. Caller retains ownership of their reference.
// out_result: on success, set to the result MultimodalContent handle. Caller must release.
//
// After executeContent returns TPIPE_OK and the caller releases out_result,
// the caller should check out_result for error state via TPipe_Content_hasError().
//
// TPipe_Result values:
//   TPIPE_OK                — execution succeeded
//   TPIPE_ERR_NOT_INITIALIZED — pipe not initialized
//   TPIPE_ERR_INVALID_ARGUMENT — input is TPIPE_INVALID_HANDLE
//   TPIPE_ERR_TIMEOUT       — execution timed out
//   TPIPE_ERR_OPERATION_CANCELLED — execution was aborted
//   TPIPE_ERR_INTERNAL      — provider API error (see getErrorMessage for details)
```

### 6.3 Async Execution

```c
// Start an async content execution.
// Returns immediately with an async handle. The callback is invoked when complete.
TPipe_AsyncHandle TPipe_Pipe_executeContentAsync(TPipe_PipeHandle handle,
                                                   TPipe_ContentHandle input,
                                                   TPipe_AsyncCallback callback,
                                                   void* user_data);

// Callback signature
typedef void (*TPipe_AsyncCallback)(TPipe_AsyncHandle asyncHandle,
                                     TPipe_Result status,
                                     TPipe_ContentHandle result,
                                     void* user_data);

// callback: invoked exactly once on completion (success or failure).
//           If execution fails, result is TPIPE_INVALID_HANDLE.
// user_data: passed through from the async call. Unchanged by TPipe.

// Check if an async operation is complete. Non-blocking.
int TPipe_Async_isDone(TPipe_AsyncHandle handle);
// Returns 1 if complete, 0 if still running.

// Wait for an async operation to complete.
TPipe_Result TPipe_Async_wait(TPipe_AsyncHandle handle, int timeoutMs);
// timeoutMs: 0 = no wait, -1 = wait forever. Returns TPIPE_OK on completion,
// TPIPE_ERR_TIMEOUT if timed out, TPIPE_ERR_OPERATION_CANCELLED if cancelled.

// Retrieve the result after completion.
// out_result: set to the result content handle. Caller must release.
// out_error: set to the error handle if execution failed. NULL allowed if caller
//           doesn't need error details. Caller must release on error.
TPipe_Result TPipe_Async_getResult(TPipe_AsyncHandle handle,
                                    TPipe_ContentHandle* out_result,
                                    TPipe_ErrorHandle* out_error);
// After a successful wait (TPIPE_OK from Async_wait), this always succeeds.
// If Async_wait returned TPIPE_ERR_TIMEOUT, this is not yet valid.

// Cancel an in-flight async operation.
// After cancel, Async_getResult will return TPIPE_ERR_OPERATION_CANCELLED.
TPipe_Result TPipe_Async_cancel(TPipe_AsyncHandle handle);

// Release the async handle. Must be called once when done with the handle.
// Cancels the operation if still running.
void TPipe_Async_release(TPipe_AsyncHandle handle);
```

### 6.4 Streaming Execution

```c
// Streaming callback signature. Called once per token chunk as the response arrives.
typedef void (*TPipe_StreamingCallback)(const char* chunk, void* user_data);
// chunk: a text fragment of the streaming response. Pointer valid only during callback.
// user_data: the user_data registered with the callback.

// Set a streaming callback. The callback is invoked on every token chunk during execute().
// Streaming applies to both sync and async execution.
//
// To disable streaming, set callback to NULL.
TPipe_PipeHandle TPipe_Pipe_setStreamingCallback(TPipe_PipeHandle handle,
                                                    TPipe_StreamingCallback callback,
                                                    void* user_data);

// Get the current streaming callback.
TPipe_StreamingCallback TPipe_Pipe_getStreamingCallback(TPipe_PipeHandle handle,
                                                          void** out_user_data);
// out_user_data: set to the registered user_data. NULL allowed if caller doesn't need it.

### 6.5 Timeout and Retry Configuration

```c
TPipe_PipeHandle TPipe_Pipe_enablePipeTimeout(TPipe_PipeHandle handle,
                                              long durationMs,
                                              int enable,
                                              int applyRecursively,
                                              int autoRetry,
                                              int retryLimit);
// durationMs: timeout duration in milliseconds (default 300000 = 5 minutes).
// enable: 1 to enable timeout system, 0 to disable.
// applyRecursively: 1 to propagate timeout settings to all child pipes.
// autoRetry: 1 to enable automatic retry with snapshot restoration on timeout.
// retryLimit: maximum number of retry attempts.
// Returns the handle for chaining.
//
// Note: Advanced custom retry logic (PipeTimeoutStrategy.CustomLogic) requires the
// PCP/function invoker API. The callback-based variant uses TPipe's internal retry
// function binding system not directly exposed via this ABI.
```

---

## 7. Token Budget and Context

### 7.1 Token Budget Attachment

```c
// Attach a TokenBudgetSettings to the pipe.
// budget: a TPipe_TokenBudgetHandle created via TPipe_TokenBudget_create().
//        TPipe clones the budget internally; caller retains ownership of their handle.
TPipe_PipeHandle TPipe_Pipe_setTokenBudget(TPipe_PipeHandle handle,
                                            TPipe_TokenBudgetHandle budget);

// Get the attached TokenBudgetSettings. Returns borrowed handle; do NOT release.
TPipe_TokenBudgetHandle TPipe_Pipe_getTokenBudget(TPipe_PipeHandle handle);

TPipe_PipeHandle TPipe_Pipe_setContextWindowSize(TPipe_PipeHandle handle, int windowSize);
int TPipe_Pipe_getContextWindowSize(TPipe_PipeHandle handle);

TPipe_PipeHandle TPipe_Pipe_setContextWindowSettings(TPipe_PipeHandle handle,
                                                      TPipe_ContextWindowSettings settings);
TPipe_ContextWindowSettings TPipe_Pipe_getContextWindowSettings(TPipe_PipeHandle handle);
```

### 7.2 Context Window Attachment

```c
// Attach a ContextWindow to the pipe for context-aware execution.
// ctx: TPipe_ContextHandle. TPipe uses this context for lorebook injection,
//      context elements, and converse history.
TPipe_PipeHandle TPipe_Pipe_setContextWindow(TPipe_PipeHandle handle,
                                              TPipe_ContextHandle ctx);
// Returns TPIPE_INVALID_HANDLE if ctx is TPIPE_INVALID_HANDLE.

TPipe_ContextHandle TPipe_Pipe_getContextWindow(TPipe_PipeHandle handle);
// Returns the attached context, or TPIPE_INVALID_HANDLE if none attached.
```

### 7.3 MiniBank and Page Keys

```c
TPipe_PipeHandle TPipe_Pipe_setMiniContextBank(TPipe_PipeHandle handle,
                                               TPipe_MiniBankHandle bank);
TPipe_MiniBankHandle TPipe_Pipe_getMiniContextBank(TPipe_PipeHandle handle);
// Returns borrowed handle reference; do not release.

TPipe_PipeHandle TPipe_Pipe_setPageKey(TPipe_PipeHandle handle, const char* key);
const char* TPipe_Pipe_getPageKey(TPipe_PipeHandle handle);
// Returns page key; valid until next ABI call on this handle; do not free.
```

### 7.4 Context Behavior Flags

```c
TPipe_PipeHandle TPipe_Pipe_setAutoInjectContext(TPipe_PipeHandle handle, int autoInject);
// autoInject: 1 = automatically inject context into prompts

TPipe_PipeHandle TPipe_Pipe_setAutoTruncateContext(TPipe_PipeHandle handle, int truncate);
// truncate: 1 = automatically truncate context to fit token budget

TPipe_PipeHandle TPipe_Pipe_setReadFromGlobalContext(TPipe_PipeHandle handle, int read);
// read: 1 = read from the global ContextBank

TPipe_PipeHandle TPipe_Pipe_setReadFromPipelineContext(TPipe_PipeHandle handle, int read);
// read: 1 = read from pipeline-level context

TPipe_PipeHandle TPipe_Pipe_setUpdatePipelineContextOnExit(TPipe_PipeHandle handle, int update);
// update: 1 = write execution results back to pipeline context

### 7.5 Context Injection

```c
TPipe_PipeHandle TPipe_Pipe_autoInjectContext(TPipe_PipeHandle handle, const char* instruction);
// instruction: instructions on how the LLM should interpret the injected context.
//              Injected into the system prompt when auto-inject is enabled.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_appendContextInstructions(TPipe_PipeHandle handle,
                                                      const char* instruction);
// Appends additional context instructions to an already-configured injection.
// Returns the handle for chaining.
```

### 7.6 Auto Truncation

```c
TPipe_PipeHandle TPipe_Pipe_autoTruncateContext(TPipe_PipeHandle handle,
                                                  int fillMode,
                                                  int fillAndSplitMode);
// fillMode: 1 to enable select-and-fill lorebook selection during truncation.
// fillAndSplitMode: 1 to enable fill mode and reserve split budget for non-lorebook context.
// Returns the handle for chaining.
```

### 7.7 Lorebook Configuration

```c
TPipe_PipeHandle TPipe_Pipe_enableImmutableLoreBook(TPipe_PipeHandle handle);
// Makes lorebook keys immutable — values cannot be updated by validation/transformation.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_enableAppendLoreBookScheme(TPipe_PipeHandle handle);
// Switches lorebook emplacement to append mode. New context can be added to existing keys,
// but old context cannot be removed. May result in contradictory information.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_enableLoreBookFillMode(TPipe_PipeHandle handle);
// Enables select-and-fill lorebook selection during context truncation.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_enableLoreBookFillAndSplitMode(TPipe_PipeHandle handle);
// Enables select-and-fill lorebook selection and reserves split budget for non-lorebook context.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_emplaceConverseHistory(TPipe_PipeHandle handle);
// Enables full converse history rewriting upon merging context.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_emplaceConverseHistoryOnlyIfNull(TPipe_PipeHandle handle);
// Enables converse history emplacement only if the target window/bank has null history.
// Returns the handle for chaining.

TPipe_Result TPipe_Pipe_getLorebookScheme(TPipe_PipeHandle handle,
                                           int* outAppendMode,
                                           int* outEmplaceMode);
// outAppendMode: set to 1 if append mode enabled, 0 otherwise.
// outEmplaceMode: set to 1 if emplace mode enabled, 0 otherwise.
// Returns TPIPE_OK on success.

TPipe_PipeHandle TPipe_Pipe_setPageKey(TPipe_PipeHandle handle, const char* key);
// key: page key for context bank operations. Multiple keys can be separated by ", ".
// Returns the handle for chaining.
```

### 7.8 Truncation Settings

```c
TPipe_PipeHandle TPipe_Pipe_setMultiplyWindowSizeBy(TPipe_PipeHandle handle, int multiplier);
// multiplier: window size multiplier for dictionary truncation.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_setCountSubWordsInFirstWord(TPipe_PipeHandle handle, int count);
// count: 1 to count sub-words in first word, 0 otherwise.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_setFavorWholeWords(TPipe_PipeHandle handle, int favor);
// favor: 1 to favor whole words during truncation, 0 otherwise.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_setSplitForNonWordChar(TPipe_PipeHandle handle, int split);
// split: 1 to split on non-word characters, 0 otherwise.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_setAlwaysSplitIfWholeWordExists(TPipe_PipeHandle handle, int always);
// always: 1 to always split when whole words exist, 0 otherwise.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_setCountSubWordsIfSplit(TPipe_PipeHandle handle, int count);
// count: 1 to count sub-words when splitting, 0 otherwise.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_setNonWordSplitCount(TPipe_PipeHandle handle, int count);
// count: number of non-word splits to allow.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_truncateContextAsString(TPipe_PipeHandle handle);
// Enables truncating context as a single string rather than individual entries.
// Returns the handle for chaining.

TPipe_Result TPipe_Pipe_getTruncationSettings(TPipe_PipeHandle handle,
                                               TPipe_TruncationSettingsHandle* out_settings);
// out_settings: filled with current truncation settings. Caller must release.
// Returns TPIPE_OK on success.
```

### 7.9 Token Budget Getters

```c
TPipe_Result TPipe_Pipe_copyTokenBudgetSettings(TPipe_PipeHandle handle,
                                                TPipe_TokenBudgetSettingsHandle* out_settings);
// out_settings: filled with a detached copy of the token budget settings. Caller must release.
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Pipe_isAutoTruncateContextEnabled(TPipe_PipeHandle handle, int* out_result);
// out_result: set to 1 if auto-truncate is enabled, 0 otherwise.
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Pipe_hasContextOverflowProtectionConfigured(TPipe_PipeHandle handle,
                                                                int* out_result);
// out_result: set to 1 if token budgeting or auto-truncation is configured, 0 otherwise.
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Pipe_getConfiguredContextWindowSize(TPipe_PipeHandle handle, int* out_size);
// out_size: set to the configured context window size in tokens.
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Pipe_getConfiguredMaxTokens(TPipe_PipeHandle handle, int* out_tokens);
// out_tokens: set to the configured maximum output token count.
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Pipe_getSystemPromptText(TPipe_PipeHandle handle,
                                             const char** out_text);
// out_text: set to the current system prompt text. TPipe owns the string.
// Returns TPIPE_OK on success.
```

### 7.10 Comprehensive Token Tracking

```c
TPipe_PipeHandle TPipe_Pipe_enableComprehensiveTokenTracking(TPipe_PipeHandle handle);
// Enables detailed token usage tracking for this pipe and all child pipes.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_disableComprehensiveTokenTracking(TPipe_PipeHandle handle);
// Disables comprehensive token tracking and clears stored usage data.
// Returns the handle for chaining.
```

### 7.11 Context Mode Getters

```c
TPipe_Result TPipe_Pipe_selectGlobalContextMode(TPipe_PipeHandle handle,
                                                  const char** out_mode);
// out_mode: set to the JSON example for the configured context mode
//           (ContextWindow if no page keys, MiniBank if page keys configured).
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Pipe_getReasoningContent(TPipe_PipeHandle handle,
                                             const char** out_content);
// out_content: set to reasoning content extracted from the last response, or empty string.
// Returns TPIPE_OK on success.
```

### 7.12 Token Counting

```c
TPipe_Result TPipe_Pipe_countTokens(TPipe_PipeHandle handle,
                                     int isInput,
                                     TPipe_ContentHandle content,
                                     int* out_count);
// isInput: 1 to count as input tokens, 0 to count as output tokens.
// content: the MultimodalContent to count tokens for.
// out_count: set to the token count.
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Pipe_countBinaryTokens(TPipe_PipeHandle handle,
                                          TPipe_ContentHandle content,
                                          TPipe_TruncationSettingsHandle settings,
                                          int* out_count);
// content: the MultimodalContent containing binary data to process.
// settings: truncation settings to use for token counting.
// out_count: set to the total token count for all binary content.
// Returns TPIPE_OK on success.

All callbacks are function pointers registered on the pipe. The native library implementation must provide the actual function implementations — TPipe invokes them at runtime during execution.

### 8.1 Callback Function Pointer Types

```c
// Validator: called after LLM response before it's returned.
// Return 1 (accept) to return the content normally.
// Return 0 (reject) to trigger the branch pipe (if set) or return an error.
typedef int (*TPipe_ValidatorCallback)(TPipe_ContentHandle content, void* user_data);

// Exception handler: called when an exception occurs during execution.
// Receives both the original input and the error that occurred.
typedef void (*TPipe_ExceptionCallback)(TPipe_ContentHandle content,
                                         TPipe_ErrorHandle error,
                                         void* user_data);

// Transformation: called to transform the LLM response before it's returned.
// Must return a TPipe_ContentHandle. Caller must release the returned handle.
typedef TPipe_ContentHandle (*TPipe_TransformationCallback)(TPipe_ContentHandle content,
                                                            void* user_data);

// Pre-init: called before LLM execution begins.
// Can modify the input content in place.
typedef void (*TPipe_PreInitCallback)(TPipe_ContentHandle content, void* user_data);

// Pre-validation: called after pre-init, before the LLM call.
// Can modify the context window. Return value is the context to use.
typedef TPipe_ContextHandle (*TPipe_PreValidationCallback)(TPipe_ContextHandle ctx,
                                                             TPipe_ContentHandle content,
                                                             void* user_data);

// Pre-invoke: called immediately before the API call.
// Return 1 to proceed with execution, 0 to abort (treated as validation failure).
typedef int (*TPipe_PreInvokeCallback)(TPipe_ContentHandle content, void* user_data);

// Post-generate: called after the LLM response is received and parsed.
// Can inspect or log the result.
typedef void (*TPipe_PostGenerateCallback)(TPipe_ContentHandle content, void* user_data);
```

### 8.2 Callback Registration

```c
TPipe_PipeHandle TPipe_Pipe_setValidator(TPipe_PipeHandle handle,
                                          TPipe_ValidatorCallback callback,
                                          void* user_data);

TPipe_PipeHandle TPipe_Pipe_setExceptionHandler(TPipe_PipeHandle handle,
                                                 TPipe_ExceptionCallback callback,
                                                 void* user_data);

TPipe_PipeHandle TPipe_Pipe_setTransformation(TPipe_PipeHandle handle,
                                               TPipe_TransformationCallback callback,
                                               void* user_data);

TPipe_PipeHandle TPipe_Pipe_setPreInit(TPipe_PipeHandle handle,
                                        TPipe_PreInitCallback callback,
                                        void* user_data);

TPipe_PipeHandle TPipe_Pipe_setPreValidation(TPipe_PipeHandle handle,
                                             TPipe_PreValidationCallback callback,
                                             void* user_data);

TPipe_PipeHandle TPipe_Pipe_setPreInvoke(TPipe_PipeHandle handle,
                                          TPipe_PreInvokeCallback callback,
                                          void* user_data);

TPipe_PipeHandle TPipe_Pipe_setPostGenerate(TPipe_PipeHandle handle,
                                              TPipe_PostGenerateCallback callback,
                                              void* user_data);

// String validator: called with raw JSON string for validation.
// Return 1 (accept) to continue normally, 0 (reject) to trigger validation failure.
typedef int (*TPipe_StringValidatorCallback)(const char* json, void* user_data);

// String transformation: called to transform a JSON string before parsing.
// Must return a transformed string. TPipe owns the returned string.
typedef const char* (*TPipe_StringTransformationCallback)(const char* json, void* user_data);

// Pre-validation MiniBank callback: called after MiniBank and content are both
// fully pulled and settled, just before the LLM call.
// Receives the MiniBank and content; returns the MiniBank to use.
typedef TPipe_MiniBankHandle (*TPipe_PreValidationMiniBankCallback)(TPipe_MiniBankHandle miniBank,
                                                                    TPipe_ContentHandle content,
                                                                    void* user_data);

TPipe_PipeHandle TPipe_Pipe_setStringValidator(TPipe_PipeHandle handle,
                                                TPipe_StringValidatorCallback callback,
                                                void* user_data);
// Registers a string-based validator for backward compatibility.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_setStringTransformation(TPipe_PipeHandle handle,
                                                     TPipe_StringTransformationCallback callback,
                                                     void* user_data);
// Registers a string-based transformation for backward compatibility.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_setPreValidationMiniBank(TPipe_PipeHandle handle,
                                                     TPipe_PreValidationMiniBankCallback callback,
                                                     void* user_data);
// Registers a MiniBank-based pre-validation callback.
// Returns the handle for chaining.
```

**Note:** Language wrappers (Python, Rust, etc.) must use FFI/cgo/cxxbind to connect their language-level callback functions to these C function pointer types.

---

## 9. Branching and Pipe Chaining

### 9.1 Branch Pipe

```c
// Set a branch pipe. Executed when validation fails (validator returns 0).
// The branch pipe receives the rejected content and returns corrected content.
// The original pipe then continues with the branch pipe's output.
//
// Note: Calling setBranchPipe establishes a bidirectional parent-child relationship.
// Internally, this function also calls branchPipe.setParentPipe(this) to set the reverse link.
TPipe_PipeHandle TPipe_Pipe_setBranchPipe(TPipe_PipeHandle handle,
                                            TPipe_PipeHandle branchPipe);
```

### 9.2 Validator Pipe

```c
// Set a validator pipe. The validator pipe receives the input content,
// executes itself (calling its own LLM), and returns validated content.
// saveSnapshotAsPageKey: if 1, saves a snapshot of the input as a page key
//                       in the validator pipe's context before running.
TPipe_PipeHandle TPipe_Pipe_setValidatorPipe(TPipe_PipeHandle handle,
                                             TPipe_PipeHandle validatorPipe,
                                             int saveSnapshotAsPageKey);
```

### 9.3 Transformation Pipe

```c
// Set a transformation pipe. The transformation pipe receives the LLM output,
// runs its own LLM to transform it (e.g., reformat, filter, summarize), and
// returns the transformed content.
TPipe_PipeHandle TPipe_Pipe_setTransformationPipe(TPipe_PipeHandle handle,
                                                   TPipe_PipeHandle transformPipe);
```

### 9.4 Reasoning

```c
// Set a reasoning pipe. Used for models that don't support native reasoning.
// The reasoning pipe receives the content, generates reasoning/thinking output,
// and that output is injected into the system prompt for the main pipe.
TPipe_PipeHandle TPipe_Pipe_setReasoningPipe(TPipe_PipeHandle handle,
                                               TPipe_PipeHandle reasoningPipe);

// Note: Reasoning configuration variants (no-arg, tokens, custom string) are
// defined in §5.7 (Numeric Parameter Configuration) as they are model参数.
// See TPipe_Pipe_setReasoning(), setReasoningWithTokens(), setReasoningWithCustom().
```

---

## 10. PCP and P2P Configuration

### 10.1 PCP Context

```c
// Set the PCP (Pipe Context Protocol) context. Enables tool/function calling.
// pcpContext: a TPipe_PCPHandle. Created via TPipe_PCP_createRequest() or
//             via the PCP stdio context functions from core-types.
TPipe_PipeHandle TPipe_Pipe_setPcPContext(TPipe_PipeHandle handle,
                                            TPipe_PCPHandle pcpContext);

TPipe_PCPHandle TPipe_Pipe_getPcPContext(TPipe_PipeHandle handle);

// Set the PCP description string. Instructs the LLM on available tools.
TPipe_PipeHandle TPipe_Pipe_setPcPDescription(TPipe_PipeHandle handle,
                                                 const char* description);
const char* TPipe_Pipe_getPcPDescription(TPipe_PipeHandle handle);
// Returns PCP description; valid until next ABI call on this handle; do not free.

// Set additional instructions merged into the PCP JSON prompt.
TPipe_PipeHandle TPipe_Pipe_enableMemoryIntrospection(TPipe_PipeHandle handle,
                                                        TPipe_TraceConfigHandle config);
// config: memory introspection security configuration. If TPIPE_INVALID_HANDLE,
//         uses default configuration (all introspection tools enabled with
//         MemoryIntrospectionConfig defaults).
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_setContainerPtr(TPipe_PipeHandle handle,
                                             TPipe_P2PInterfaceHandle container);
// container: opaque P2P interface handle for the container.
//            When set, the pipe redirects execution to the container instead.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_setMergedPcpJsonInstructions(TPipe_PipeHandle handle,
                                                             const char* instructions);

TPipe_Result TPipe_Pipe_processPcpResponse(TPipe_PipeHandle handle,
                                             const char* llmResponse,
                                             TPipe_PcpExecutionResultHandle* out_result);
// llmResponse: raw LLM response containing potential PCP requests.
// out_result: filled with execution results and any errors. Caller must release.
// Note: this is an async suspend function — caller must call TPipe_Async_* functions
// to wait for completion if the PCP execution is asynchronous.
// Returns TPIPE_OK on success.
```

### 10.2 P2P Agent List

```c
// Set the list of P2P agent descriptors for distributed execution.
// agents: List<TPipe_P2PDescriptorHandle>. TPipe clones each descriptor internally.
TPipe_PipeHandle TPipe_Pipe_setP2PAgentList(TPipe_PipeHandle handle,
                                             TPipe_ListHandle agents);

TPipe_ListHandle TPipe_Pipe_getP2PAgentList(TPipe_PipeHandle handle);
// Returns borrowed list; do NOT release.

// Set the P2P description for this pipe.
TPipe_PipeHandle TPipe_Pipe_setP2PDescription(TPipe_PipeHandle handle,
                                                 const char* description);
const char* TPipe_Pipe_getP2PDescription(TPipe_PipeHandle handle);
// Returns P2P description; valid until next ABI call on this handle; do not free.
```

---

## 11. Error Reporting and Token Usage

### 11.1 Error State

```c
// Get the error message from the last execution that failed on this pipe.
// Returns NULL if no error has occurred (pipe has no error state).
// Pointer is valid until next ABI call on this handle; do not free.
const char* TPipe_Pipe_getErrorMessage(TPipe_PipeHandle handle);

// Get the error type (TraceEventType) from the last failed execution.
TPipe_TraceEventType TPipe_Pipe_getErrorType(TPipe_PipeHandle handle);
// Returns TPIPE_TRACE_EVENT_PIPE_FAILURE for most failures. Returns a zero/void
// value if no error has occurred.

TPipe_Result TPipe_Pipe_getError(TPipe_PipeHandle handle, TPipe_ErrorHandle* out_error);
// Fills out_error with the full PipeError if an error occurred.
// out_error must not be NULL. Caller must release the returned handle.
// Returns TPIPE_OK if error was present; TPIPE_ERR_NOT_FOUND if no error.
```

### 11.2 Token Usage

```c
// Get token usage for the last execution on this pipe.
// Returns a TPipe_TokenUsage struct by value (fields filled with current counts).
// Must call after execute() or executeContent() returned TPIPE_OK.
TPipe_TokenUsage TPipe_Pipe_getTokenUsage(TPipe_PipeHandle handle);
// TPipe_TokenUsage fields:
//   int inputTokens      — tokens in the input prompt
//   int outputTokens      — tokens in the model output
//   int totalInputTokens — input tokens including all nested pipes
//   int totalOutputTokens — output tokens including all nested pipes

// Convenience accessors for totals
int TPipe_Pipe_getTotalInputTokens(TPipe_PipeHandle handle);
int TPipe_Pipe_getTotalOutputTokens(TPipe_PipeHandle handle);
```

---

## 12. JSON I/O and Miscellaneous Configuration

### 12.1 JSON Input/Output

```c
// Specify the expected JSON input schema class name.
// The class name is used to validate and parse the input JSON.
TPipe_PipeHandle TPipe_Pipe_setJsonInput(TPipe_PipeHandle handle,
                                          const char* jsonSchemaClassName);
TPipe_PipeHandle TPipe_Pipe_setJsonInputInstructions(TPipe_PipeHandle handle,
                                                       const char* instructions);
// instructions: additional instructions for parsing the JSON input.

// Specify the expected JSON output schema class name.
TPipe_PipeHandle TPipe_Pipe_setJsonOutput(TPipe_PipeHandle handle,
                                           const char* jsonSchemaClassName);
TPipe_PipeHandle TPipe_Pipe_setJsonOutputInstructions(TPipe_PipeHandle handle,
                                                        const char* instructions);

// Set whether the provider natively supports JSON mode.
TPipe_PipeHandle TPipe_Pipe_setSupportsNativeJson(TPipe_PipeHandle handle, int supports);
// supports: 1 = provider has native JSON support; 0 = TPipe handles JSON framing
```

### 12.2 Tracing

```c
TPipe_PipeHandle TPipe_Pipe_enableTracing(TPipe_PipeHandle handle,
                                            TPipe_TraceConfigHandle config);
// config: tracing configuration. If TPIPE_INVALID_HANDLE, uses defaults (enabled=true).
//          Enables comprehensive token tracking automatically when tracing is enabled.
// Returns the handle for chaining.

TPipe_PipeHandle TPipe_Pipe_disableTracing(TPipe_PipeHandle handle);
// Disables tracing for this pipe.
// Returns the handle for chaining.
```

### 12.3 Pipe Introspection

```c
TPipe_Result TPipe_Pipe_getParentPipe(TPipe_PipeHandle handle,
                                      TPipe_PipeHandle* out_parent);
// out_parent: set to the parent pipe handle, or TPIPE_INVALID_HANDLE if no parent.
//             Returns borrowed handle; do NOT release.
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Pipe_isReasoningPipe(TPipe_PipeHandle handle, int* out_result);
// out_result: set to 1 if this pipe is a reasoning pipe of its parent, 0 otherwise.
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Pipe_getContextWindow(TPipe_PipeHandle handle,
                                         TPipe_ContextHandle* out_context);
// out_context: set to the pipe's context window. Borrowed reference; do NOT release.
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Pipe_getMiniBank(TPipe_PipeHandle handle,
                                      TPipe_MiniBankHandle* out_bank);
// out_bank: set to the pipe's MiniBank. Borrowed reference; do NOT release.
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Pipe_hasError(TPipe_PipeHandle handle, int* out_result);
// out_result: set to 1 if pipe has an error stored, 0 otherwise.
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Pipe_isComprehensiveTokenTrackingEnabled(TPipe_PipeHandle handle,
                                                            int* out_result);
// out_result: set to 1 if comprehensive token tracking is enabled, 0 otherwise.
// Returns TPIPE_OK on success.
```

### 12.4 Pipe Identity

```c
TPipe_PipeHandle TPipe_Pipe_setPipeName(TPipe_PipeHandle handle, const char* name);
// name: human-readable name for this pipe. Used in logs and trace output.

const char* TPipe_Pipe_getPipeName(TPipe_PipeHandle handle);
// Returns pipe name; valid until next ABI call on this handle; do not free.

TPipe_PipeHandle TPipe_Pipe_setPipeId(TPipe_PipeHandle handle, const char* id);
// id: programmatic identifier. Used for pipeline routing and P2P communication.

const char* TPipe_Pipe_getPipeId(TPipe_PipeHandle handle);
// Returns pipe ID; valid until next ABI call on this handle; do not free.
```

### 12.5 Retry Configuration (via Timeout Strategy)

```c
// The pipe timeout strategy and retry settings are configured via set functions
// that align with the timeout system. The relevant configuration happens via
// TPipe_Config_* (defined in configuration spec) and timeout settings on the pipe.
// The timeout strategy (NoRetry, GracefulRetry, etc.) determines retry behavior.

// Additional pipe-specific settings:
// Continuity wrapping: if set, the pipe wraps content with ConverseHistory
TPipe_PipeHandle TPipe_Pipe_setWrapContentWithConverseHistory(TPipe_PipeHandle handle, int wrap);
```

---

## 13. Complete Function Reference

| Function | Parameters | Returns | Notes |
|---|---|---|---|
| `TPipe_Pipe_create` | void | `TPipe_PipeHandle` | New pipe, refcount=1 |
| `TPipe_Pipe_addRef` | `TPipe_PipeHandle` | void | Increment refcount |
| `TPipe_Pipe_release` | `TPipe_PipeHandle` | void | Decrement refcount |
| `TPipe_Pipe_init` | `TPipe_PipeHandle` | `TPipe_Result` | Initialize pipe |
| `TPipe_Pipe_isInitialized` | `TPipe_PipeHandle` | `int` | 1 if initialized |
| `TPipe_Pipe_abort` | `TPipe_PipeHandle` | `TPipe_Result` | Abort in-flight execution |
| `TPipe_Pipe_setProvider` | `TPipe_PipeHandle`, `TPipe_ProviderName` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getProvider` | `TPipe_PipeHandle` | `TPipe_ProviderName` | |
| `TPipe_Pipe_setModel` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getModel` | `TPipe_PipeHandle` | `const char*` | Owned by TPipe |
| `TPipe_Pipe_setSystemPrompt` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getSystemPrompt` | `TPipe_PipeHandle` | `const char*` | Owned by TPipe |
| `TPipe_Pipe_setUserPrompt` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getUserPrompt` | `TPipe_PipeHandle` | `const char*` | Owned by TPipe |
| `TPipe_Pipe_setMiddlePrompt` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setFooterPrompt` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setPromptMode` | `TPipe_PipeHandle`, `TPipe_PromptMode` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getPromptMode` | `TPipe_PipeHandle` | `TPipe_PromptMode` | |
| `TPipe_Pipe_setEmplaceLorebook` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setAppendLoreBook` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setLoreBookFillMode` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setLoreBookFillAndSplitMode` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setTemperature` | `TPipe_PipeHandle`, `double` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setTopP` | `TPipe_PipeHandle`, `double` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setTopK` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setMaxTokens` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setRepetitionPenalty` | `TPipe_PipeHandle`, `double` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setPresencePenalty` | `TPipe_PipeHandle`, `double` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setSeed` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setN` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setStopSequences` | `TPipe_PipeHandle`, `TPipe_ListHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setUser` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setLogitBias` | `TPipe_PipeHandle`, `TPipe_MapHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_banWords` | `TPipe_PipeHandle`, `TPipe_ListHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_encourageWords` | `TPipe_PipeHandle`, `TPipe_ListHandle`, `double` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_clearLogitBias` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setRepetitionControl` | `TPipe_PipeHandle`, `double` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_enableDeterministicGeneration` | `TPipe_PipeHandle`, `int*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_disableDeterministicGeneration` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setReasoning` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setReasoningWithTokens` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setReasoningWithCustom` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_disableReasoning` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setTokenCountingBias` | `TPipe_PipeHandle`, `double` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_enablePipeTimeout` | `TPipe_PipeHandle`, `long`, `int`, `int`, `int`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_execute` | `TPipe_PipeHandle`, `const char*` | `const char*` | Owned by TPipe; NULL on error |
| `TPipe_Pipe_executeContent` | `TPipe_PipeHandle`, `TPipe_ContentHandle`, `TPipe_ContentHandle*` | `TPipe_Result` | Caller releases out_result |
| `TPipe_Pipe_executeContentAsync` | `TPipe_PipeHandle`, `TPipe_ContentHandle`, `TPipe_AsyncCallback`, `void*` | `TPipe_AsyncHandle` | Caller must release |
| `TPipe_Async_isDone` | `TPipe_AsyncHandle` | `int` | 1=complete, 0=running |
| `TPipe_Async_wait` | `TPipe_AsyncHandle`, `int` | `TPipe_Result` | TPIPE_OK/TIMEOUT/CANCELLED |
| `TPipe_Async_getResult` | `TPipe_AsyncHandle`, `TPipe_ContentHandle*`, `TPipe_ErrorHandle*` | `TPipe_Result` | Caller releases result if not NULL |
| `TPipe_Async_cancel` | `TPipe_AsyncHandle` | `TPipe_Result` | |
| `TPipe_Async_release` | `TPipe_AsyncHandle` | void | |
| `TPipe_Pipe_setStreamingCallback` | `TPipe_PipeHandle`, `TPipe_StreamingCallback`, `void*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getStreamingCallback` | `TPipe_PipeHandle`, `void**` | `TPipe_StreamingCallback` | |
| `TPipe_Pipe_setTokenBudget` | `TPipe_PipeHandle`, `TPipe_TokenBudgetHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getTokenBudget` | `TPipe_PipeHandle` | `TPipe_TokenBudgetHandle` | Borrowed |
| `TPipe_Pipe_setContextWindowSize` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getContextWindowSize` | `TPipe_PipeHandle` | `int` | |
| `TPipe_Pipe_setContextWindowSettings` | `TPipe_PipeHandle`, `TPipe_ContextWindowSettings` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getContextWindowSettings` | `TPipe_PipeHandle` | `TPipe_ContextWindowSettings` | |
| `TPipe_Pipe_setContextWindow` | `TPipe_PipeHandle`, `TPipe_ContextHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getContextWindow` | `TPipe_PipeHandle` | `TPipe_ContextHandle` | Borrowed |
| `TPipe_Pipe_setMiniContextBank` | `TPipe_PipeHandle`, `TPipe_MiniBankHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getMiniContextBank` | `TPipe_PipeHandle` | `TPipe_MiniBankHandle` | Borrowed |
| `TPipe_Pipe_setPageKey` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getPageKey` | `TPipe_PipeHandle` | `const char*` | Owned by TPipe |
| `TPipe_Pipe_setAutoInjectContext` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setAutoTruncateContext` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setReadFromGlobalContext` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setReadFromPipelineContext` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setUpdatePipelineContextOnExit` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_autoInjectContext` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_appendContextInstructions` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_autoTruncateContext` | `TPipe_PipeHandle`, `int`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_enableImmutableLoreBook` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_enableAppendLoreBookScheme` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_enableLoreBookFillMode` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_enableLoreBookFillAndSplitMode` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_emplaceConverseHistory` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_emplaceConverseHistoryOnlyIfNull` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getLorebookScheme` | `TPipe_PipeHandle`, `int*`, `int*` | `TPipe_Result` | |
| `TPipe_Pipe_setMultiplyWindowSizeBy` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setCountSubWordsInFirstWord` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setFavorWholeWords` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setSplitForNonWordChar` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setAlwaysSplitIfWholeWordExists` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setCountSubWordsIfSplit` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setNonWordSplitCount` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_truncateContextAsString` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getTruncationSettings` | `TPipe_PipeHandle`, `TPipe_TruncationSettingsHandle*` | `TPipe_Result` | Caller releases |
| `TPipe_Pipe_copyTokenBudgetSettings` | `TPipe_PipeHandle`, `TPipe_TokenBudgetSettingsHandle*` | `TPipe_Result` | Caller releases |
| `TPipe_Pipe_isAutoTruncateContextEnabled` | `TPipe_PipeHandle`, `int*` | `TPipe_Result` | |
| `TPipe_Pipe_hasContextOverflowProtectionConfigured` | `TPipe_PipeHandle`, `int*` | `TPipe_Result` | |
| `TPipe_Pipe_getConfiguredContextWindowSize` | `TPipe_PipeHandle`, `int*` | `TPipe_Result` | |
| `TPipe_Pipe_getConfiguredMaxTokens` | `TPipe_PipeHandle`, `int*` | `TPipe_Result` | |
| `TPipe_Pipe_getSystemPromptText` | `TPipe_PipeHandle`, `const char**` | `TPipe_Result` | |
| `TPipe_Pipe_enableComprehensiveTokenTracking` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_disableComprehensiveTokenTracking` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_selectGlobalContextMode` | `TPipe_PipeHandle`, `const char**` | `TPipe_Result` | |
| `TPipe_Pipe_getReasoningContent` | `TPipe_PipeHandle`, `const char**` | `TPipe_Result` | |
| `TPipe_Pipe_countTokens` | `TPipe_PipeHandle`, `int`, `TPipe_ContentHandle`, `int*` | `TPipe_Result` | |
| `TPipe_Pipe_countBinaryTokens` | `TPipe_PipeHandle`, `TPipe_ContentHandle`, `TPipe_TruncationSettingsHandle`, `int*` | `TPipe_Result` | |
| `TPipe_Pipe_setValidator` | `TPipe_PipeHandle`, `TPipe_ValidatorCallback`, `void*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setExceptionHandler` | `TPipe_PipeHandle`, `TPipe_ExceptionCallback`, `void*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setTransformation` | `TPipe_PipeHandle`, `TPipe_TransformationCallback`, `void*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setPreInit` | `TPipe_PipeHandle`, `TPipe_PreInitCallback`, `void*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setPreValidation` | `TPipe_PipeHandle`, `TPipe_PreValidationCallback`, `void*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setPreInvoke` | `TPipe_PipeHandle`, `TPipe_PreInvokeCallback`, `void*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setPostGenerate` | `TPipe_PipeHandle`, `TPipe_PostGenerateCallback`, `void*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setStringValidator` | `TPipe_PipeHandle`, `TPipe_StringValidatorCallback`, `void*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setStringTransformation` | `TPipe_PipeHandle`, `TPipe_StringTransformationCallback`, `void*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setPreValidationMiniBank` | `TPipe_PipeHandle`, `TPipe_PreValidationMiniBankCallback`, `void*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setBranchPipe` | `TPipe_PipeHandle`, `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setValidatorPipe` | `TPipe_PipeHandle`, `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setTransformationPipe` | `TPipe_PipeHandle`, `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setReasoningPipe` | `TPipe_PipeHandle`, `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setPcPContext` | `TPipe_PipeHandle`, `TPipe_PCPHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getPcPContext` | `TPipe_PipeHandle` | `TPipe_PCPHandle` | Borrowed |
| `TPipe_Pipe_setPcPDescription` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getPcPDescription` | `TPipe_PipeHandle` | `const char*` | Owned by TPipe |
| `TPipe_Pipe_setMergedPcpJsonInstructions` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_processPcpResponse` | `TPipe_PipeHandle`, `const char*`, `TPipe_PcpExecutionResultHandle*` | `TPipe_Result` | Caller releases |
| `TPipe_Pipe_setContainerPtr` | `TPipe_PipeHandle`, `TPipe_P2PInterfaceHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_enableMemoryIntrospection` | `TPipe_PipeHandle`, `TPipe_TraceConfigHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setP2PAgentList` | `TPipe_PipeHandle`, `TPipe_ListHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getP2PAgentList` | `TPipe_PipeHandle` | `TPipe_ListHandle` | Borrowed |
| `TPipe_Pipe_setP2PDescription` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getP2PDescription` | `TPipe_PipeHandle` | `const char*` | Owned by TPipe |
| `TPipe_Pipe_getErrorMessage` | `TPipe_PipeHandle` | `const char*` | NULL if no error |
| `TPipe_Pipe_getErrorType` | `TPipe_PipeHandle` | `TPipe_TraceEventType` | |
| `TPipe_Pipe_getError` | `TPipe_PipeHandle`, `TPipe_ErrorHandle*` | `TPipe_Result` | Fills out_error |
| `TPipe_Pipe_getTokenUsage` | `TPipe_PipeHandle` | `TPipe_TokenUsage` | Struct by value |
| `TPipe_Pipe_getTotalInputTokens` | `TPipe_PipeHandle` | `int` | |
| `TPipe_Pipe_getTotalOutputTokens` | `TPipe_PipeHandle` | `int` | |
| `TPipe_Pipe_setJsonInput` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setJsonInputInstructions` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setJsonOutput` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setJsonOutputInstructions` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_setSupportsNativeJson` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_enableTracing` | `TPipe_PipeHandle`, `TPipe_TraceConfigHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_disableTracing` | `TPipe_PipeHandle` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getParentPipe` | `TPipe_PipeHandle`, `TPipe_PipeHandle*` | `TPipe_Result` | |
| `TPipe_Pipe_isReasoningPipe` | `TPipe_PipeHandle`, `int*` | `TPipe_Result` | |
| `TPipe_Pipe_getContextWindow` | `TPipe_PipeHandle`, `TPipe_ContextHandle*` | `TPipe_Result` | |
| `TPipe_Pipe_getMiniBank` | `TPipe_PipeHandle`, `TPipe_MiniBankHandle*` | `TPipe_Result` | |
| `TPipe_Pipe_hasError` | `TPipe_PipeHandle`, `int*` | `TPipe_Result` | |
| `TPipe_Pipe_isComprehensiveTokenTrackingEnabled` | `TPipe_PipeHandle`, `int*` | `TPipe_Result` | |
| `TPipe_Pipe_setPipeName` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getPipeName` | `TPipe_PipeHandle` | `const char*` | Owned by TPipe |
| `TPipe_Pipe_setPipeId` | `TPipe_PipeHandle`, `const char*` | `TPipe_PipeHandle` | Chainable |
| `TPipe_Pipe_getPipeId` | `TPipe_PipeHandle` | `const char*` | Owned by TPipe |
| `TPipe_Pipe_setWrapContentWithConverseHistory` | `TPipe_PipeHandle`, `int` | `TPipe_PipeHandle` | Chainable |

---

## 14. Next Steps

- [x] ✅ graalvm-abi-overview.md — architecture and scope
- [x] ✅ graalvm-abi-initialization.md — init/shutdown contract
- [x] ✅ graalvm-abi-core-types.md — type system and data types
- [x] ✅ graalvm-abi-pipe-api.md — Pipe execution API (this document)
- [ ] graalvm-abi-pipeline-api.md — Pipeline orchestration API
- [ ] graalvm-abi-context-api.md — Context management API
- [ ] graalvm-abi-pcp-api.md — Tool/protocol execution API
- [ ] graalvm-abi-p2p-api.md — P2P communication API
- [ ] graalvm-abi-configuration.md — Configuration API
- [ ] graalvm-abi-error-handling.md — Error handling conventions
- [ ] graalvm-abi-lifecycle.md — Resource lifecycle
- [ ] graalvm-abi-reflection-handling.md — Reflection/JVM concerns
- [ ] graalvm-abi-memory-model.md — Memory management
- [ ] graalvm-abi-thread-model.md — Concurrency model
- [ ] graalvm-abi-serialization.md — Cross-language serialization

---

*This document will be updated as the spec progresses.*