# GraalVM Native ABI Specification — Pipeline API

**Version:** 0.1.0-draft
**Created:** 2026-05-05
**Status:** Working Draft - In Progress
**Spec:** graalvm-abi-pipeline-api.md
**Requires:** graalvm-abi-core-types.md (handle system, reference counting, MultimodalContent)

---

## 1. Overview

### 1.1 Purpose

This document defines the complete Pipeline API — the orchestration surface for chaining, branching, and coordinating multiple Pipes and sub-pipelines across the TPipe GraalVM Native ABI. The Pipeline API exposes seven container types, each implementing `P2PInterface` for distributed agent communication, offering different execution models from sequential orchestration to democratic discussion to distributed node grids.

Every function in this spec returns `TPipe_Result` (an integer error code) unless otherwise noted. `TPIPE_OK` (0) indicates success.

### 1.2 Container Type Taxonomy

TPipe provides seven Pipeline container types. **None extends another** — all implement `P2PInterface` independently:

```
TPipe_PipelineHandle          — Sequential pipe orchestration (one pipe after another)
TPipe_manifoldHandle         — Manager/worker multi-agent loop (manager decides worker dispatch)
TPipe_junctionHandle         — Democratic discussion, voting, workflow recipes (multiple participants)
TPipe_splitterHandle         — Parallel execution (key→Pipeline binding, all execute simultaneously)
TPipe_connectorHandle        — Conditional routing (key→Pipeline branch, path-based dispatch)
TPipe_multiConnectorHandle   — Orchestrates multiple Connectors (SEQUENTIAL/PARALLEL/FALLBACK)
TPipe_distributionGridHandle — Distributed node routing, registry discovery, remote handoff
```

All containers implement `P2PInterface` (see §4.2, §5.2, etc. per container).

### 1.3 Relationship to Core Types

The Pipeline API operates exclusively through the handle system defined in `graalvm-abi-core-types.md`:

| Handle Type | Constructed By | Used For |
|---|---|---|
| `TPipe_PipelineHandle` | `TPipe_Pipeline_create()` | Base sequential orchestration |
| `TPipe_manifoldHandle` | `TPipe_Manifold_create()` | Multi-agent manager/worker |
| `TPipe_junctionHandle` | `TPipe_Junction_create()` | Democratic discussion |
| `TPipe_splitterHandle` | `TPipe_Splitter_create()` | Parallel execution |
| `TPipe_connectorHandle` | `TPipe_Connector_create()` | Conditional routing |
| `TPipe_multiConnectorHandle` | `TPipe_MultiConnector_create()` | Connector orchestration |
| `TPipe_distributionGridHandle` | `TPipe_DistributionGrid_create()` | Distributed nodes |
| `TPipe_ContentHandle` | Caller via `TPipe_Content_*` | Input/output of execute calls |
| `TPipe_PipeHandle` | `TPipe_Pipe_create()` | Pipes managed by containers |
| `TPipe_AsyncHandle` | `TPipe_*_executeBegin()` | Async operation tracking |

All reference counting rules from core-types apply: `TPipe_Handle_addRef()` / `TPipe_Handle_release()` manage lifetime. `TPIPE_INVALID_HANDLE` (0) is the null value.

### 1.4 API Organization

Each container section follows the same structure:

```
TPipe_{Container}_create() / createEx()  — Handle creation
TPipe_{Container}_destroy()               — Handle destruction
TPipe_{Container}Config_*               — Config struct setters
TPipe_{Container}_init()                 — Container initialization
TPipe_{Container}_execute*                — Synchronous execution
TPipe_{Container}_executeBegin/End*      — Async execution pair
TPipe_{Container}_pause* / resume*        — Developer-in-the-loop control
TPipe_{Container}_get*                    — Query getters
TPipe_{Container}_set*                    — Configuration setters
TPipe_{Container}_enableTracing / disableTracing
```

### 1.5 Config Struct Pattern

All containers use a **config struct** pattern instead of chained builder methods (C has no builder pattern):

```c
// Create with defaults, then configure
TPipe_PipelineConfig cfg = TPipe_PipelineConfig_default();
TPipe_PipelineConfig_setName(&cfg, "my-pipeline");
TPipe_PipelineConfig_addPipe(&cfg, pipeHandle);
TPipe_PipelineConfig_enableTracing(&cfg, traceConfig);
TPipe_PipelineHandle p = TPipe_Pipeline_createEx(&cfg);

// Or use default creation
TPipe_PipelineHandle p2 = TPipe_Pipeline_create();
TPipe_Pipeline_setName(p2, "another-pipeline");
```

### 1.6 Execution Flow

The typical lifecycle of a pipeline container:

```
1. TPipe_init()                                   // Library-level init
2. TPipe_{Container}_create()                     // Create container handle
3. TPipe_{Container}_createEx(&config)            // Or with config struct
4. TPipe_{Container}_init()                        // Initialize (validates config, prepares pipelines)
5. TPipe_{Container}_execute() or executeBegin()   // Run (sync or async)
6. TPipe_{Container}_getTraceReport()              // Optional: get trace
7. TPipe_{Container}_destroy()                      // Release container handle
```

### 1.7 Content Ownership

**Library copies all input content.** When a caller passes `TPipe_Content*` to any execute function, the library makes an internal deep copy. The caller is free to release the input content immediately after a synchronous call returns without affecting the container's operation.

---

## 2. Handle Types

### 2.1 Container Handle Definitions

```c
// Opaque handle types — callers treat as black boxes.
// All handles are reference-counted via TPipe_Handle_addRef/release.
typedef uint64_t TPipe_PipelineHandle;           // Sequential orchestration
typedef uint64_t TPipe_manifoldHandle;          // Manager/worker multi-agent
typedef uint64_t TPipe_junctionHandle;           // Democratic discussion/voting
typedef uint64_t TPipe_splitterHandle;           // Parallel execution
typedef uint64_t TPipe_connectorHandle;         // Conditional routing
typedef uint64_t TPipe_multiConnectorHandle;    // Orchestrates multiple Connectors
typedef uint64_t TPipe_distributionGridHandle;   // Distributed node routing

// Convenience aliases for the async subsystem
typedef uint64_t TPipe_AsyncHandle;             // Async operation tracking
typedef uint64_t TPipe_RenewalId;               // Auto-renewal loop ID (string-based)

// Constant for invalid handles
#define TPIPE_INVALID_HANDLE 0
```

### 2.2 Handle Relationships

All seven containers are **peers**, not a hierarchy:

```
P2PInterface contract (shared by all 7):
  - setP2pDescription / getP2pDescription
  - setP2pTransport / getP2pTransport
  - setP2pRequirements / getP2pRequirements
  - getContainerObject / setContainerObject
  - getPipelinesFromInterface
  - executeP2PRequest
  - executeLocal

Each container additionally has its own specific API surface.
```

---

## 3. Lifecycle Pattern

This section defines the conventions used across all seven container types.

### 3.1 Config Struct

Each container has a config struct named `TPipe_{Container}Config`. The struct is allocated on the stack by the caller and passed by pointer to `createEx()`.

```c
// Default config (zero-initialized or via default() function)
TPipe_PipelineConfig TPipe_PipelineConfig_default(void);

// The config struct contains fields for all container settings.
// Exact fields per container are defined in each section.
typedef struct {
    // Container-specific fields...
} TPipe_PipelineConfig;
```

Config struct setters return `TPipe_{Container}Config*` for optional chaining within a single call:

```c
TPipe_PipelineConfig cfg = TPipe_PipelineConfig_default();
TPipe_PipelineConfig_setName(&cfg, "main");
// Chaining within a single expression:
TPipe_PipelineConfig_addPipe(&cfg, pipe1);
TPipe_PipelineConfig_addPipe(&cfg, pipe2);
```

### 3.2 Create and Destroy

```c
// Create with default settings (equivalent to createEx(&defaultConfig))
TPipe_PipelineHandle TPipe_Pipeline_create(void);

// Create from a populated config struct
TPipe_PipelineHandle TPipe_Pipeline_createEx(const TPipe_PipelineConfig* config);
// config: pointer to caller-allocated config struct. Library reads fields, does not retain pointer.
// Returns TPIPE_INVALID_HANDLE on failure (check TPipe_Err_getLastError()).

// Release a container handle (decrements refcount)
TPipe_Result TPipe_Pipeline_destroy(TPipe_PipelineHandle handle);
// Returns TPIPE_OK on success.
```

### 3.3 Initialization

```c
// Initialize the container — validates configuration, prepares all child pipelines.
// Must be called before execute(). Can be called multiple times (re-initializes).
TPipe_Result TPipe_Pipeline_init(TPipe_PipelineHandle handle);
// Returns TPIPE_OK on success.
```

### 3.4 Synchronous Execution

```c
// Execute with text input (convenience)
TPipe_Content* TPipe_Pipeline_execute(TPipe_PipelineHandle handle,
                                      const char* input_text,
                                      TPipe_Result* out_result);
// input_text: null-terminated UTF-8 text. Library copies internally.
// out_result: optional, receives TPipe_Result status. Can be NULL.
// Returns: owned TPipe_Content* result. Caller must release via TPipe_Content_release().
// Returns NULL on failure (check out_result or TPipe_Err_getLastError()).

// Execute with full content input
TPipe_Content* TPipe_Pipeline_executeContent(TPipe_PipelineHandle handle,
                                             const TPipe_Content* input,
                                             TPipe_Result* out_result);
// input: content to process. Library copies internally (content ownership: library).
// out_result: optional. Returns owned content on success, NULL on failure.
```

### 3.5 Asynchronous Execution (Hybrid Pair)

```c
// Begin async execution — returns immediately with an async handle
TPipe_AsyncHandle TPipe_Pipeline_executeBegin(TPipe_PipelineHandle handle,
                                              const TPipe_Content* input);
// input: content to process. Library copies internally.
// Returns: async handle for tracking progress. Caller must release via TPipe_Async_release().

// End async execution — blocks until complete, returns result
TPipe_Content* TPipe_Pipeline_executeEnd(TPipe_AsyncHandle async_handle,
                                         TPipe_Result* out_result);
// async_handle: handle returned from executeBegin.
// out_result: optional. Returns owned content on success, NULL on failure.
```

### 3.6 Pause and Resume

```c
// Request pause at next checkpoint (blocks until pause takes effect)
TPipe_Result TPipe_Pipeline_pause(TPipe_PipelineHandle handle);
// Returns TPIPE_OK when pause is active.

// Resume a paused pipeline (unblocks the pause channel)
TPipe_Result TPipe_Pipeline_resume(TPipe_PipelineHandle handle);
// Returns TPIPE_OK when resume is active.

// Check pause state
int TPipe_Pipeline_isPaused(TPipe_PipelineHandle handle);
// Returns 1 if paused, 0 otherwise.

// Check if pause is supported/enabled
int TPipe_Pipeline_canPause(TPipe_PipelineHandle handle);
// Returns 1 if pause is available, 0 otherwise.
```

### 3.7 Tracing

```c
// Enable tracing with optional configuration
TPipe_Result TPipe_Pipeline_enableTracing(TPipe_PipelineHandle handle,
                                         const TPipe_TraceConfig* config);
// config: optional. If NULL, uses default TraceConfig(enabled=true).
// Enables tracing on container and propagates to all child pipelines/pipes.

// Disable tracing
TPipe_Result TPipe_Pipeline_disableTracing(TPipe_PipelineHandle handle);
// Returns TPIPE_OK on success.

// Get formatted trace report
const char* TPipe_Pipeline_getTraceReport(TPipe_PipelineHandle handle,
                                          TPipe_TraceFormat format);
// format: JSON, HTML, MARKDOWN, or CONSOLE (from core-types).
// Returns: owned string. Caller must release via TPipe_String_release().

// Get failure analysis (if tracing enabled)
const TPipe_FailureAnalysis* TPipe_Pipeline_getFailureAnalysis(TPipe_PipelineHandle handle);
// Returns: NULL if tracing disabled or no failures. Otherwise pointer to analysis data.

// Get unique trace ID
const char* TPipe_Pipeline_getTraceId(TPipe_PipelineHandle handle);
// Returns: owned string trace ID.
```

### 3.8 Error and Token Reporting

```c
// Check if container has an error
int TPipe_Pipeline_hasError(TPipe_PipelineHandle handle);
// Returns 1 if error stored, 0 otherwise.

// Get error message
const char* TPipe_Pipeline_getErrorMessage(TPipe_PipelineHandle handle);
// Returns: empty string if no error, otherwise owned error string.

// Get name of failed sub-component (pipe name)
const char* TPipe_Pipeline_getFailedPipeName(TPipe_PipelineHandle handle);
// Returns: empty string if no failure.

// Clear stored errors
TPipe_Result TPipe_Pipeline_clearErrors(TPipe_PipelineHandle handle);
// Returns TPIPE_OK on success.

// Get full error context (formatted: "Pipe 'X' failed in Y phase: Z")
const char* TPipe_Pipeline_getFullErrorContext(TPipe_PipelineHandle handle);
// Returns: owned formatted string.

// Check if terminated by error
int TPipe_Pipeline_wasTerminatedByError(TPipe_PipelineHandle handle);
// Returns 1 if terminated by error, 0 otherwise.

// Get aggregated token usage across all child pipes
const TPipe_TokenUsage* TPipe_Pipeline_getTokenUsage(TPipe_PipelineHandle handle);
// Returns: pointer to token usage data. Not owned — do NOT release.

// Get total input tokens
int TPipe_Pipeline_getTotalInputTokens(TPipe_PipelineHandle handle);
// Returns: total input token count from tracking.

// Get total output tokens
int TPipe_Pipeline_getTotalOutputTokens(TPipe_PipelineHandle handle);
// Returns: total output token count from tracking.

// Get formatted token count string
const char* TPipe_Pipeline_getTokenCount(TPipe_PipelineHandle handle);
// Returns: owned string "Input tokens: X \n Output Tokens: Y".
```

---

## 4. Pipeline — Sequential Orchestration

`Pipeline` is the base sequential orchestration container. It executes Pipes in order, with support for pause/resume, tracing, and error aggregation.

**Handle:** `TPipe_PipelineHandle`
**Config struct:** `TPipe_PipelineConfig`

### 4.1 Config Struct

```c
// Config fields for Pipeline
typedef struct {
    const char* name;                     // Pipeline name (optional, can be NULL)
    TPipe_PipeHandle* pipes;             // Array of pipe handles
    int pipe_count;                       // Number of pipes in array
    TPipe_ContextHandle context_window;  // Optional context window handle
    TPipe_MiniBankHandle mini_bank;      // Optional mini bank handle
    TPipe_TraceConfig trace_config;       // Tracing configuration
    // ... additional config fields
} TPipe_PipelineConfig;
```

### 4.2 P2PInterface (inherited by Pipeline)

```c
TPipe_Result TPipe_Pipeline_setP2pDescription(TPipe_PipelineHandle handle,
                                             const TPipe_P2PDescriptor* descriptor);
const TPipe_P2PDescriptor* TPipe_Pipeline_getP2pDescription(TPipe_PipelineHandle handle);
TPipe_Result TPipe_Pipeline_setP2pTransport(TPipe_PipelineHandle handle,
                                             const TPipe_P2PTransport* transport);
const TPipe_P2PTransport* TPipe_Pipeline_getP2pTransport(TPipe_PipelineHandle handle);
TPipe_Result TPipe_Pipeline_setP2pRequirements(TPipe_PipelineHandle handle,
                                               const TPipe_P2PRequirements* requirements);
const TPipe_P2PRequirements* TPipe_Pipeline_getP2pRequirements(TPipe_PipelineHandle handle);
void* TPipe_Pipeline_getContainerObject(TPipe_PipelineHandle handle);
TPipe_Result TPipe_Pipeline_setContainerObject(TPipe_PipelineHandle handle, void* container);
const TPipe_PipeHandle* TPipe_Pipeline_getPipelinesFromInterface(TPipe_PipelineHandle handle,
                                                                  int* out_count);
TPipe_P2PResponse* TPipe_Pipeline_executeP2PRequest(TPipe_PipelineHandle handle,
                                                     const TPipe_P2PRequest* request);
TPipe_Content* TPipe_Pipeline_executeLocal(TPipe_PipelineHandle handle,
                                          const TPipe_Content* input,
                                          TPipe_Result* out_result);
```

### 4.3 Configuration Setters (Config Struct)

```c
// Set pipeline name (for logs/tracing)
TPipe_PipelineConfig* TPipe_PipelineConfig_setName(TPipe_PipelineConfig* cfg, const char* name);

// Add a pipe to the pipeline
TPipe_PipelineConfig* TPipe_PipelineConfig_addPipe(TPipe_PipelineConfig* cfg,
                                                    TPipe_PipeHandle pipe);

// Insert a pipe at a specific index
TPipe_PipelineConfig* TPipe_PipelineConfig_insertPipe(TPipe_PipelineConfig* cfg,
                                                      TPipe_PipeHandle pipe,
                                                      int index);

// Add multiple pipes
TPipe_PipelineConfig* TPipe_PipelineConfig_addAllPipes(TPipe_PipelineConfig* cfg,
                                                        const TPipe_PipeHandle* pipes,
                                                        int count);

// Set context window
TPipe_PipelineConfig* TPipe_PipelineConfig_setContextWindow(TPipe_PipelineConfig* cfg,
                                                           TPipe_ContextHandle window);

// Set mini bank
TPipe_PipelineConfig* TPipe_PipelineConfig_setMiniBank(TPipe_PipelineConfig* cfg,
                                                       TPipe_MiniBankHandle bank);

// Enable tracing
TPipe_PipelineConfig* TPipe_PipelineConfig_enableTracing(TPipe_PipelineConfig* cfg,
                                                        const TPipe_TraceConfig* config);
```

### 4.4 Additional Configuration (Post-Create)

```c
// Add a pipe after creation (builder pattern — returns handle for chaining)
TPipe_PipelineHandle TPipe_Pipeline_add(TPipe_PipelineHandle handle, TPipe_PipeHandle pipe);

// Insert a pipe at index after creation
TPipe_PipelineHandle TPipe_Pipeline_insert(TPipe_PipelineHandle handle,
                                           TPipe_PipeHandle pipe,
                                           int index);

// Add multiple pipes after creation
TPipe_PipelineHandle TPipe_Pipeline_addAll(TPipe_PipelineHandle handle,
                                             const TPipe_PipeHandle* pipes,
                                             int count);

// Set pre-validation callback
TPipe_PipelineHandle TPipe_Pipeline_setPreValidationFunction(
    TPipe_PipelineHandle handle,
    TPipe_PreValidationCallback func,
    void* user_data);

// Enable pipe timeout
TPipe_PipelineHandle TPipe_Pipeline_enablePipeTimeout(TPipe_PipelineHandle handle,
                                                       int apply_recursively,
                                                       long duration_ms,
                                                       int auto_retry,
                                                       int retry_limit,
                                                       TPipe_PipeTimeoutCustomLogic* custom_logic);

// Enable pause before each pipe execution
TPipe_PipelineHandle TPipe_Pipeline_pauseBeforePipes(TPipe_PipelineHandle handle);

// Enable pause after each pipe execution
TPipe_PipelineHandle TPipe_Pipeline_pauseAfterPipes(TPipe_PipelineHandle handle);

// Enable pause before jump operations
TPipe_PipelineHandle TPipe_Pipeline_pauseBeforeJumps(TPipe_PipelineHandle handle);

// Enable pause after repeat operations
TPipe_PipelineHandle TPipe_Pipeline_pauseAfterRepeats(TPipe_PipelineHandle handle);

// Enable pause on pipeline completion
TPipe_PipelineHandle TPipe_Pipeline_pauseOnCompletion(TPipe_PipelineHandle handle);

// Enable manual pause() calls
TPipe_PipelineHandle TPipe_Pipeline_enablePausing(TPipe_PipelineHandle handle);

// Enable all pause points (convenience)
TPipe_PipelineHandle TPipe_Pipeline_enablePausePoints(TPipe_PipelineHandle handle);

// Set conditional pause function
TPipe_PipelineHandle TPipe_Pipeline_pauseWhen(TPipe_PipelineHandle handle,
                                               TPipe_PauseCondition* condition,
                                               void* user_data);

// Set pause callback
TPipe_PipelineHandle TPipe_Pipeline_onPause(TPipe_PipelineHandle handle,
                                            TPipe_PipelinePauseCallback* callback,
                                            void* user_data);

// Set resume callback
TPipe_PipelineHandle TPipe_Pipeline_onResume(TPipe_PipelineHandle handle,
                                              TPipe_PipelineResumeCallback* callback,
                                              void* user_data);

// Set per-pipe completion callback
TPipe_PipelineHandle TPipe_Pipeline_setPipeCompletionCallback(
    TPipe_PipelineHandle handle,
    TPipe_PipeCompletionCallback* callback,
    void* user_data);

// Set pipeline completion callback
TPipe_PipelineHandle TPipe_Pipeline_setPipelineCompletionCallback(
    TPipe_PipelineHandle handle,
    TPipe_PipelineCompletionCallback* callback,
    void* user_data);

// Enable ConverseHistory wrapping for content
TPipe_PipelineHandle TPipe_Pipeline_wrapContentWithConverseHistory(
    TPipe_PipelineHandle handle,
    TPipe_ConverseHistoryHandle history,
    int wrap_text_response,
    int include_pipe_content,
    TPipe_ConverseRole pipeline_role,
    TPipe_ConverseRole pipe_role,
    TPipe_ConverseRole user_role);

// Enable global ContextBank usage
TPipe_PipelineHandle TPipe_Pipeline_useGlobalContext(TPipe_PipelineHandle handle,
                                                     const char* page_key);
```

### 4.5 Query Getters

```c
// Get all pipes in the pipeline
const TPipe_PipeHandle* TPipe_Pipeline_getPipes(TPipe_PipelineHandle handle, int* out_count);

// Check if context overflow protection is configured
int TPipe_Pipeline_hasContextOverflowProtectionConfigured(TPipe_PipelineHandle handle);

// Get pipes without overflow protection
const TPipe_PipeHandle* TPipe_Pipeline_getPipesWithoutContextOverflowProtection(
    TPipe_PipelineHandle handle, int* out_count);
```

### 4.6 Lifecycle

```c
// Create with defaults
TPipe_PipelineHandle TPipe_Pipeline_create(void);

// Create from config struct
TPipe_PipelineHandle TPipe_Pipeline_createEx(const TPipe_PipelineConfig* config);

// Initialize (validates config, prepares all pipes)
TPipe_Result TPipe_Pipeline_init(TPipe_PipelineHandle handle);

// Release handle
TPipe_Result TPipe_Pipeline_destroy(TPipe_PipelineHandle handle);
```

### 4.7 Execution

```c
// Execute with text prompt (convenience)
TPipe_Content* TPipe_Pipeline_execute(TPipe_PipelineHandle handle,
                                      const char* input_text,
                                      TPipe_Result* out_result);

// Execute with content
TPipe_Content* TPipe_Pipeline_executeContent(TPipe_PipelineHandle handle,
                                             const TPipe_Content* input,
                                             TPipe_Result* out_result);

// Async begin
TPipe_AsyncHandle TPipe_Pipeline_executeBegin(TPipe_PipelineHandle handle,
                                               const TPipe_Content* input);

// Async end
TPipe_Content* TPipe_Pipeline_executeEnd(TPipe_AsyncHandle async_handle,
                                          TPipe_Result* out_result);

// Pause / Resume
TPipe_Result TPipe_Pipeline_pause(TPipe_PipelineHandle handle);
TPipe_Result TPipe_Pipeline_resume(TPipe_PipelineHandle handle);
int TPipe_Pipeline_isPaused(TPipe_PipelineHandle handle);
int TPipe_Pipeline_canPause(TPipe_PipelineHandle handle);
```

---

## 5. Manifold — Manager/Worker Multi-Agent

`Manifold` is a multi-agent orchestration container with a manager pipeline that decides which worker pipelines to dispatch. The manager/worker loop continues until the task is complete or a termination condition is met.

**Handle:** `TPipe_manifoldHandle`
**Config struct:** `TPipe_ManifoldConfig`

### 5.1 Overview

Manifold orchestrates a manager Pipeline and multiple worker Pipelines. The manager decides which worker to call for each iteration. Supports:
- Configurable loop iteration limit
- Token budget control for shared manager history
- DITL (Developer-in-the-Loop) callbacks: init, validation, failure, transformation
- Optional summarization pipeline
- Per-worker overflow protection validation

### 5.2 P2PInterface

```c
TPipe_Result TPipe_Manifold_setP2pDescription(TPipe_manifoldHandle handle,
                                              const TPipe_P2PDescriptor* descriptor);
const TPipe_P2PDescriptor* TPipe_Manifold_getP2pDescription(TPipe_manifoldHandle handle);
TPipe_Result TPipe_Manifold_setP2pTransport(TPipe_manifoldHandle handle,
                                            const TPipe_P2PTransport* transport);
const TPipe_P2PTransport* TPipe_Manifold_getP2pTransport(TPipe_manifoldHandle handle);
TPipe_Result TPipe_Manifold_setP2pRequirements(TPipe_manifoldHandle handle,
                                                 const TPipe_P2PRequirements* requirements);
const TPipe_P2PRequirements* TPipe_Manifold_getP2pRequirements(TPipe_manifoldHandle handle);
void* TPipe_Manifold_getContainerObject(TPipe_manifoldHandle handle);
TPipe_Result TPipe_Manifold_setContainerObject(TPipe_manifoldHandle handle, void* container);
const TPipe_PipeHandle* TPipe_Manifold_getPipelinesFromInterface(TPipe_manifoldHandle handle,
                                                                   int* out_count);
TPipe_P2PResponse* TPipe_Manifold_executeP2PRequest(TPipe_manifoldHandle handle,
                                                     const TPipe_P2PRequest* request);
```

### 5.3 Enums

```c
// Manifold Summary Mode
typedef enum {
    TPIPE_MANIFOLD_SUMMARY_MODE_APPEND = 0,     // Appends to running summary
    TPIPE_MANIFOLD_SUMMARY_MODE_REGENERATE = 1  // Replaces summary each iteration
} TPipe_ManifoldSummaryMode;
```

### 5.4 Config Struct

```c
typedef struct {
    int max_loop_iterations;              // Max iterations (0 = unlimited, default 100)
    TPipe_ManifoldSummaryMode summary_mode; // Summary behavior
    TPipe_TraceConfig trace_config;        // Tracing config
    // ... additional fields
} TPipe_ManifoldConfig;
```

### 5.5 Configuration Setters (Config Struct)

```c
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setMaxLoopIterations(TPipe_ManifoldConfig* cfg,
                                                                   int limit);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setSummaryMode(TPipe_ManifoldConfig* cfg,
                                                               TPipe_ManifoldSummaryMode mode);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_enableTracing(TPipe_ManifoldConfig* cfg,
                                                             const TPipe_TraceConfig* config);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setTruncationMethod(TPipe_ManifoldConfig* cfg,
                                                                     const TPipe_ContextWindowSettings* method);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setContextWindowSize(TPipe_ManifoldConfig* cfg,
                                                                   int size);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setManagerTokenBudget(TPipe_ManifoldConfig* cfg,
                                                                     const TPipe_TokenBudgetSettings* budget);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setManagerPipeline(TPipe_ManifoldConfig* cfg,
                                                                  TPipe_PipelineHandle pipeline,
                                                                  const TPipe_P2PDescriptor* descriptor,
                                                                  const TPipe_P2PRequirements* requirements);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_addWorkerPipeline(TPipe_ManifoldConfig* cfg,
                                                                 TPipe_PipelineHandle pipeline,
                                                                 const TPipe_P2PDescriptor* descriptor,
                                                                 const TPipe_P2PRequirements* requirements,
                                                                 const char* agent_name,
                                                                 const char* agent_description);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setP2pAgentNames(TPipe_ManifoldConfig* cfg,
                                                                 const char** names,
                                                                 int count);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_autoTruncateContext(TPipe_ManifoldConfig* cfg);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setManifoldInitFunction(TPipe_ManifoldConfig* cfg,
                                                                       TPipe_ManifoldInitCallback* func,
                                                                       void* user_data);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setContextTruncationFunction(TPipe_ManifoldConfig* cfg,
                                                                            TPipe_ContextTruncationCallback* func,
                                                                            void* user_data);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setValidatorFunction(TPipe_ManifoldConfig* cfg,
                                                                   TPipe_ManifoldValidatorCallback* func,
                                                                   void* user_data);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setFailureFunction(TPipe_ManifoldConfig* cfg,
                                                                  TPipe_ManifoldFailureCallback* func,
                                                                  void* user_data);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setTransformationFunction(TPipe_ManifoldConfig* cfg,
                                                                        TPipe_ManifoldTransformationCallback* func,
                                                                        void* user_data);
TPipe_ManifoldConfig* TPipe_ManifoldConfig_setSummaryPipeline(TPipe_ManifoldConfig* cfg,
                                                                  TPipe_PipelineHandle pipeline,
                                                                  const TPipe_P2PDescriptor* descriptor,
                                                                  const TPipe_P2PRequirements* requirements);
```

### 5.6 Lifecycle

```c
TPipe_manifoldHandle TPipe_Manifold_create(void);
TPipe_manifoldHandle TPipe_Manifold_createEx(const TPipe_ManifoldConfig* config);
TPipe_Result TPipe_Manifold_init(TPipe_manifoldHandle handle);
TPipe_Result TPipe_Manifold_destroy(TPipe_manifoldHandle handle);
```

### 5.7 Execution

```c
TPipe_Content* TPipe_Manifold_execute(TPipe_manifoldHandle handle,
                                       const TPipe_Content* input,
                                       TPipe_Result* out_result);
TPipe_AsyncHandle TPipe_Manifold_executeBegin(TPipe_manifoldHandle handle,
                                               const TPipe_Content* input);
TPipe_Content* TPipe_Manifold_executeEnd(TPipe_AsyncHandle async_handle,
                                         TPipe_Result* out_result);
TPipe_Result TPipe_Manifold_pause(TPipe_manifoldHandle handle);
TPipe_Result TPipe_Manifold_resume(TPipe_manifoldHandle handle);
```

### 5.8 Query Getters

```c
int TPipe_Manifold_getMaxLoopIterations(TPipe_manifoldHandle handle);
int TPipe_Manifold_hasLoopLimit(TPipe_manifoldHandle handle);
const TPipe_ContextWindowSettings* TPipe_Manifold_getTruncationMethod(TPipe_manifoldHandle handle);
const TPipe_TokenBudgetSettings* TPipe_Manifold_getManagerTokenBudget(TPipe_manifoldHandle handle);
int TPipe_Manifold_isManagerBudgetControlEnabled(TPipe_manifoldHandle handle);
int TPipe_Manifold_workersHaveOverflowProtection(TPipe_manifoldHandle handle);
const char** TPipe_Manifold_getWorkersWithoutOverflowProtection(TPipe_manifoldHandle handle,
                                                                   int* out_count);
TPipe_PipelineHandle TPipe_Manifold_getManagerPipeline(TPipe_manifoldHandle handle);
const TPipe_PipelineHandle* TPipe_Manifold_getWorkerPipelines(TPipe_manifoldHandle handle,
                                                                int* out_count);
const char* TPipe_Manifold_getTraceId(TPipe_manifoldHandle handle);
const char* TPipe_Manifold_getTraceReport(TPipe_manifoldHandle handle,
                                          TPipe_TraceFormat format);
const TPipe_FailureAnalysis* TPipe_Manifold_getFailureAnalysis(TPipe_manifoldHandle handle);
```

---

## 6. Junction — Democratic Discussion

`Junction` is a collaborative decision-making container that runs discussion rounds among participants, uses voting to reach consensus, and supports configurable workflow recipes.

**Handle:** `TPipe_junctionHandle`
**Config struct:** `TPipe_JunctionConfig`

### 6.1 Overview

Junction orchestrates a moderator and multiple participant agents through discussion rounds. Participants propose, discuss, vote, and reach consensus. Supports:
- 7 workflow recipes (DISCUSSION_ONLY, VOTE_ACT_VERIFY_REPEAT, etc.)
- 3 discussion strategies (SIMULTANEOUS, CONVERSATIONAL, ROUND_ROBIN)
- Configurable voting threshold and rounds
- Optional planner/actor/verifier/adjuster/output roles
- Memory policy for outbound prompt compaction

### 6.2 Enums

```c
// Junction Workflow Recipe
typedef enum {
    TPIPE_JUNCTION_WORKFLOW_RECIPE_DISCUSSION_ONLY = 0,
    TPIPE_JUNCTION_WORKFLOW_RECIPE_VOTE_ACT_VERIFY_REPEAT = 1,
    TPIPE_JUNCTION_WORKFLOW_RECIPE_ACT_VOTE_VERIFY_REPEAT = 2,
    TPIPE_JUNCTION_WORKFLOW_RECIPE_VOTE_PLAN_ACT_VERIFY_REPEAT = 3,
    TPIPE_JUNCTION_WORKFLOW_RECIPE_PLAN_VOTE_ACT_VERIFY_REPEAT = 4,
    TPIPE_JUNCTION_WORKFLOW_RECIPE_VOTE_PLAN_OUTPUT_EXIT = 5,
    TPIPE_JUNCTION_WORKFLOW_RECIPE_PLAN_VOTE_ADJUST_OUTPUT_EXIT = 6
} TPipe_JunctionWorkflowRecipe;

// Junction Discussion Strategy
typedef enum {
    TPIPE_JUNCTION_STRATEGY_SIMULTANEOUS = 0,
    TPIPE_JUNCTION_STRATEGY_CONVERSATIONAL = 1,
    TPIPE_JUNCTION_STRATEGY_ROUND_ROBIN = 2
} TPipe_JunctionStrategy;
```

### 6.3 P2PInterface

```c
TPipe_Result TPipe_Junction_setP2pDescription(TPipe_junctionHandle handle,
                                              const TPipe_P2PDescriptor* descriptor);
const TPipe_P2PDescriptor* TPipe_Junction_getP2pDescription(TPipe_junctionHandle handle);
TPipe_Result TPipe_Junction_setP2pTransport(TPipe_junctionHandle handle,
                                               const TPipe_P2PTransport* transport);
const TPipe_P2PTransport* TPipe_Junction_getP2pTransport(TPipe_junctionHandle handle);
TPipe_Result TPipe_Junction_setP2pRequirements(TPipe_junctionHandle handle,
                                                 const TPipe_P2PRequirements* requirements);
const TPipe_P2PRequirements* TPipe_Junction_getP2pRequirements(TPipe_junctionHandle handle);
void* TPipe_Junction_getContainerObject(TPipe_junctionHandle handle);
TPipe_Result TPipe_Junction_setContainerObject(TPipe_junctionHandle handle, void* container);
const TPipe_PipelineHandle* TPipe_Junction_getPipelinesFromInterface(TPipe_junctionHandle handle,
                                                                      int* out_count);
TPipe_P2PResponse* TPipe_Junction_executeP2PRequest(TPipe_junctionHandle handle,
                                                     const TPipe_P2PRequest* request);
TPipe_Content* TPipe_Junction_executeLocal(TPipe_junctionHandle handle,
                                           const TPipe_Content* input,
                                           TPipe_Result* out_result);
```

### 6.4 Config Struct

```c
typedef struct {
    TPipe_JunctionWorkflowRecipe workflow_recipe;
    TPipe_JunctionStrategy strategy;
    int rounds;
    double voting_threshold;
    int moderator_intervention_enabled;
    int max_nested_depth;
    TPipe_TraceConfig trace_config;
    // ... additional fields
} TPipe_JunctionConfig;
```

### 6.5 Configuration Setters (Config Struct)

```c
TPipe_JunctionConfig* TPipe_JunctionConfig_setWorkflowRecipe(TPipe_JunctionConfig* cfg,
                                                               TPipe_JunctionWorkflowRecipe recipe);
TPipe_JunctionConfig* TPipe_JunctionConfig_setStrategy(TPipe_JunctionConfig* cfg,
                                                          TPipe_JunctionStrategy strategy);
TPipe_JunctionConfig* TPipe_JunctionConfig_setRounds(TPipe_JunctionConfig* cfg, int rounds);
TPipe_JunctionConfig* TPipe_JunctionConfig_setVotingThreshold(TPipe_JunctionConfig* cfg,
                                                               double threshold);
TPipe_JunctionConfig* TPipe_JunctionConfig_setModeratorIntervention(TPipe_JunctionConfig* cfg,
                                                                       int enabled);
TPipe_JunctionConfig* TPipe_JunctionConfig_setMaxNestedDepth(TPipe_JunctionConfig* cfg,
                                                                 int depth);
TPipe_JunctionConfig* TPipe_JunctionConfig_enableTracing(TPipe_JunctionConfig* cfg,
                                                           const TPipe_TraceConfig* config);
TPipe_JunctionConfig* TPipe_JunctionConfig_setMemoryPolicy(TPipe_JunctionConfig* cfg,
                                                               const TPipe_JunctionMemoryPolicy* policy);
TPipe_JunctionConfig* TPipe_JunctionConfig_setModerator(TPipe_JunctionConfig* cfg,
                                                           const char* role_name,
                                                           TPipe_P2PInterface* component,
                                                           const TPipe_P2PDescriptor* descriptor,
                                                           const TPipe_P2PRequirements* requirements,
                                                           const char* description);
TPipe_JunctionConfig* TPipe_JunctionConfig_addParticipant(TPipe_JunctionConfig* cfg,
                                                           const char* role_name,
                                                           TPipe_P2PInterface* component,
                                                           double weight,
                                                           const TPipe_P2PDescriptor* descriptor,
                                                           const TPipe_P2PRequirements* requirements,
                                                           const char* description);
TPipe_JunctionConfig* TPipe_JunctionConfig_addParticipants(TPipe_JunctionConfig* cfg,
                                                              const char** role_names,
                                                              TPipe_P2PInterface** components,
                                                              double* weights,
                                                              int count);
TPipe_JunctionConfig* TPipe_JunctionConfig_setPlanner(TPipe_JunctionConfig* cfg,
                                                         const char* role_name,
                                                         TPipe_P2PInterface* component,
                                                         const TPipe_P2PDescriptor* descriptor,
                                                         const TPipe_P2PRequirements* requirements,
                                                         const char* description);
TPipe_JunctionConfig* TPipe_JunctionConfig_setActor(TPipe_JunctionConfig* cfg,
                                                      const char* role_name,
                                                      TPipe_P2PInterface* component,
                                                      const TPipe_P2PDescriptor* descriptor,
                                                      const TPipe_P2PRequirements* requirements,
                                                      const char* description);
TPipe_JunctionConfig* TPipe_JunctionConfig_setVerifier(TPipe_JunctionConfig* cfg,
                                                          const char* role_name,
                                                          TPipe_P2PInterface* component,
                                                          const TPipe_P2PDescriptor* descriptor,
                                                          const TPipe_P2PRequirements* requirements,
                                                          const char* description);
TPipe_JunctionConfig* TPipe_JunctionConfig_setAdjuster(TPipe_JunctionConfig* cfg,
                                                         const char* role_name,
                                                         TPipe_P2PInterface* component,
                                                         const TPipe_P2PDescriptor* descriptor,
                                                         const TPipe_P2PRequirements* requirements,
                                                         const char* description);
TPipe_JunctionConfig* TPipe_JunctionConfig_setOutputHandler(TPipe_JunctionConfig* cfg,
                                                               const char* role_name,
                                                               TPipe_P2PInterface* component,
                                                               const TPipe_P2PDescriptor* descriptor,
                                                               const TPipe_P2PRequirements* requirements,
                                                               const char* description);
```

### 6.6 Lifecycle

```c
TPipe_junctionHandle TPipe_Junction_create(void);
TPipe_junctionHandle TPipe_Junction_createEx(const TPipe_JunctionConfig* config);
TPipe_Result TPipe_Junction_init(TPipe_junctionHandle handle);
TPipe_Result TPipe_Junction_destroy(TPipe_junctionHandle handle);
```

### 6.7 Execution

```c
// Main execution entry point
TPipe_Content* TPipe_Junction_execute(TPipe_junctionHandle handle,
                                       const TPipe_Content* input,
                                       TPipe_Result* out_result);

// Discussion-only execution (alias)
TPipe_Content* TPipe_Junction_conductDiscussion(TPipe_junctionHandle handle,
                                                 const TPipe_Content* input,
                                                 TPipe_Result* out_result);

// Workflow execution (requires non-DISCUSSION_ONLY recipe)
TPipe_Content* TPipe_Junction_conductWorkflow(TPipe_junctionHandle handle,
                                                const TPipe_Content* input,
                                                TPipe_Result* out_result);

// Async begin
TPipe_AsyncHandle TPipe_Junction_executeBegin(TPipe_junctionHandle handle,
                                               const TPipe_Content* input);
TPipe_Content* TPipe_Junction_executeEnd(TPipe_AsyncHandle async_handle,
                                         TPipe_Result* out_result);

// Pause / Resume
TPipe_Result TPipe_Junction_pause(TPipe_junctionHandle handle);
TPipe_Result TPipe_Junction_resume(TPipe_junctionHandle handle);
int TPipe_Junction_isPaused(TPipe_junctionHandle handle);
int TPipe_Junction_canPause(TPipe_junctionHandle handle);
```

### 6.8 Runtime and Query

```c
// Clear runtime state (preserves configuration)
TPipe_Result TPipe_Junction_clearRuntimeState(TPipe_junctionHandle handle);

// Clear trace history
TPipe_Result TPipe_Junction_clearTrace(TPipe_junctionHandle handle);

// Get memory policy copy
const TPipe_JunctionMemoryPolicy* TPipe_Junction_getMemoryPolicy(TPipe_junctionHandle handle);

// Get trace ID
const char* TPipe_Junction_getTraceId(TPipe_junctionHandle handle);

// Get trace report
const char* TPipe_Junction_getTraceReport(TPipe_junctionHandle handle,
                                         TPipe_TraceFormat format);

// Get failure analysis
const TPipe_FailureAnalysis* TPipe_Junction_getFailureAnalysis(TPipe_junctionHandle handle);
```

---

## 7. Splitter — Parallel Execution

`Splitter` executes multiple Pipelines in parallel, each bound to a key. Results are aggregated into a `MultimodalCollection`.

**Handle:** `TPipe_splitterHandle`
**Config struct:** `TPipe_SplitterConfig`

### 7.1 Overview

Splitter binds Pipelines to activation keys. When executed, all bound Pipelines run concurrently. Results are collected and returned as a `MultimodalCollection`.

### 7.2 P2PInterface

```c
TPipe_Content* TPipe_Splitter_executeLocal(TPipe_splitterHandle handle,
                                            const TPipe_Content* input,
                                            TPipe_Result* out_result);
```

### 7.3 Config Struct

```c
typedef struct {
    TPipe_TraceConfig trace_config;
    // ... additional fields
} TPipe_SplitterConfig;
```

### 7.4 Configuration Setters (Config Struct)

```c
TPipe_SplitterConfig* TPipe_SplitterConfig_enableTracing(TPipe_SplitterConfig* cfg,
                                                           const TPipe_TraceConfig* config);
TPipe_SplitterConfig* TPipe_SplitterConfig_addContent(TPipe_SplitterConfig* cfg,
                                                        const char* key,
                                                        const TPipe_Content* content);
TPipe_SplitterConfig* TPipe_SplitterConfig_addPipeline(TPipe_SplitterConfig* cfg,
                                                         const char* key,
                                                         TPipe_PipelineHandle pipeline);
TPipe_SplitterConfig* TPipe_SplitterConfig_removePipeline(TPipe_SplitterConfig* cfg,
                                                             TPipe_PipelineHandle pipeline);
TPipe_SplitterConfig* TPipe_SplitterConfig_removeKey(TPipe_SplitterConfig* cfg,
                                                        const char* key);
```

### 7.5 Lifecycle

```c
TPipe_splitterHandle TPipe_Splitter_create(void);
TPipe_splitterHandle TPipe_Splitter_createEx(const TPipe_SplitterConfig* config);
TPipe_Result TPipe_Splitter_init(TPipe_splitterHandle handle);
TPipe_Result TPipe_Splitter_destroy(TPipe_splitterHandle handle);
```

### 7.6 Execution

```c
// Execute all pipelines in parallel
TPipe_Content* TPipe_Splitter_executePipelines(TPipe_splitterHandle handle,
                                                TPipe_Result* out_result);

// Async begin
TPipe_AsyncHandle TPipe_Splitter_executePipelinesBegin(TPipe_splitterHandle handle);
TPipe_Content* TPipe_Splitter_executePipelinesEnd(TPipe_AsyncHandle async_handle,
                                                    TPipe_Result* out_result);
```

### 7.7 Query Getters

```c
const TPipe_PipelineHandle* TPipe_Splitter_getAllChildPipelines(TPipe_splitterHandle handle,
                                                                   int* out_count);
const char** TPipe_Splitter_getChildTraceIds(TPipe_splitterHandle handle,
                                              int* out_count);
const char* TPipe_Splitter_getTraceId(TPipe_splitterHandle handle);
const char* TPipe_Splitter_getTraceReport(TPipe_splitterHandle handle,
                                            TPipe_TraceFormat format);
const TPipe_FailureAnalysis* TPipe_Splitter_getFailureAnalysis(TPipe_splitterHandle handle);
```

### 7.8 Callbacks

```c
TPipe_splitterHandle TPipe_Splitter_setOnPipelineFinish(TPipe_splitterHandle handle,
                                                          TPipe_SplitterPipelineCallback* callback,
                                                          void* user_data);
TPipe_splitterHandle TPipe_Splitter_setOnSplitterFinish(TPipe_splitterHandle handle,
                                                          TPipe_SplitterFinishCallback* callback,
                                                          void* user_data);
```

### 7.9 Static Helper

```c
// Convert content to MultimodalCollection format
TPipe_Content* TPipe_Splitter_toMultimodalCollection(const TPipe_Content* input,
                                                       TPipe_Result* out_result);
```

---

## 8. Connector — Conditional Routing

`Connector` routes content to different Pipeline branches based on a path/key. The path can be set in content metadata or passed at execution time.

**Handle:** `TPipe_connectorHandle`
**Config struct:** `TPipe_ConnectorConfig`

### 8.1 Overview

Connector maps keys to Pipeline branches. At execution time, the path is resolved from content metadata or the `execute()` call parameter, and content flows through the matching branch.

### 8.2 P2PInterface

```c
TPipe_Result TPipe_Connector_setP2pDescription(TPipe_connectorHandle handle,
                                                 const TPipe_P2PDescriptor* descriptor);
const TPipe_P2PDescriptor* TPipe_Connector_getP2pDescription(TPipe_connectorHandle handle);
TPipe_Result TPipe_Connector_setP2pTransport(TPipe_connectorHandle handle,
                                              const TPipe_P2PTransport* transport);
const TPipe_P2PTransport* TPipe_Connector_getP2pTransport(TPipe_connectorHandle handle);
TPipe_Result TPipe_Connector_setP2pRequirements(TPipe_connectorHandle handle,
                                                 const TPipe_P2PRequirements* requirements);
const TPipe_P2PRequirements* TPipe_Connector_getP2pRequirements(TPipe_connectorHandle handle);
const TPipe_PipelineHandle* TPipe_Connector_getPipelinesFromInterface(TPipe_connectorHandle handle,
                                                                         int* out_count);
TPipe_P2PResponse* TPipe_Connector_executeP2PRequest(TPipe_connectorHandle handle,
                                                       const TPipe_P2PRequest* request);
TPipe_Content* TPipe_Connector_executeLocal(TPipe_connectorHandle handle,
                                             const TPipe_Content* input,
                                             TPipe_Result* out_result);
```

### 8.3 Config Struct

```c
typedef struct {
    TPipe_TraceConfig trace_config;
    // ... additional fields
} TPipe_ConnectorConfig;
```

### 8.4 Configuration Setters (Config Struct)

```c
TPipe_ConnectorConfig* TPipe_ConnectorConfig_enableTracing(TPipe_ConnectorConfig* cfg,
                                                               const TPipe_TraceConfig* config);
TPipe_ConnectorConfig* TPipe_ConnectorConfig_add(TPipe_ConnectorConfig* cfg,
                                                   const char* key,
                                                   TPipe_PipelineHandle pipeline);
TPipe_ConnectorConfig* TPipe_ConnectorConfig_setDefaultPath(TPipe_ConnectorConfig* cfg,
                                                               const char* path);
```

### 8.5 Lifecycle

```c
TPipe_connectorHandle TPipe_Connector_create(void);
TPipe_connectorHandle TPipe_Connector_createEx(const TPipe_ConnectorConfig* config);
TPipe_Result TPipe_Connector_destroy(TPipe_connectorHandle handle);
```

### 8.6 Execution

```c
// Execute with explicit path
TPipe_Content* TPipe_Connector_execute(TPipe_connectorHandle handle,
                                         const char* path,
                                         const TPipe_Content* input,
                                         TPipe_Result* out_result);

// Async begin
TPipe_AsyncHandle TPipe_Connector_executeBegin(TPipe_connectorHandle handle,
                                                const char* path,
                                                const TPipe_Content* input);
TPipe_Content* TPipe_Connector_executeEnd(TPipe_AsyncHandle async_handle,
                                           TPipe_Result* out_result);
```

### 8.7 Query Getters

```c
TPipe_PipelineHandle TPipe_Connector_get(TPipe_connectorHandle handle, const char* key);
const char* TPipe_Connector_getTrace(TPipe_connectorHandle handle, TPipe_TraceFormat format);
const char* TPipe_Connector_getTraceId(TPipe_connectorHandle handle);
```

### 8.8 Static Helpers

```c
// Set connector path in content metadata
TPipe_Result TPipe_Connector_setPath(TPipe_Content* content, const char* path);

// Get connector path from content metadata
const char* TPipe_Connector_getPath(const TPipe_Content* content);
```

---

## 9. MultiConnector — Connector Orchestration

`MultiConnector` orchestrates multiple Connectors with execution modes: SEQUENTIAL, PARALLEL, or FALLBACK.

**Handle:** `TPipe_multiConnectorHandle`
**Config struct:** `TPipe_MultiConnectorConfig`

### 9.1 Enum

```c
typedef enum {
    TPIPE_MULTI_CONNECTOR_EXECUTION_MODE_SEQUENTIAL = 0,
    TPIPE_MULTI_CONNECTOR_EXECUTION_MODE_PARALLEL = 1,
    TPIPE_MULTI_CONNECTOR_EXECUTION_MODE_FALLBACK = 2
} TPipe_MultiConnectorExecutionMode;
```

### 9.2 P2PInterface

```c
TPipe_Result TPipe_MultiConnector_setP2pDescription(TPipe_multiConnectorHandle handle,
                                                     const TPipe_P2PDescriptor* descriptor);
const TPipe_P2PDescriptor* TPipe_MultiConnector_getP2pDescription(TPipe_multiConnectorHandle handle);
TPipe_Result TPipe_MultiConnector_setP2pTransport(TPipe_multiConnectorHandle handle,
                                                   const TPipe_P2PTransport* transport);
const TPipe_P2PTransport* TPipe_MultiConnector_getP2pTransport(TPipe_multiConnectorHandle handle);
TPipe_Result TPipe_MultiConnector_setP2pRequirements(TPipe_multiConnectorHandle handle,
                                                      const TPipe_P2PRequirements* requirements);
const TPipe_P2PRequirements* TPipe_MultiConnector_getP2pRequirements(TPipe_multiConnectorHandle handle);
void* TPipe_MultiConnector_getContainerObject(TPipe_multiConnectorHandle handle);
TPipe_Result TPipe_MultiConnector_setContainerObject(TPipe_multiConnectorHandle handle,
                                                       void* container);
const TPipe_PipelineHandle* TPipe_MultiConnector_getPipelinesFromInterface(
    TPipe_multiConnectorHandle handle, int* out_count);
TPipe_P2PResponse* TPipe_MultiConnector_executeP2PRequest(TPipe_multiConnectorHandle handle,
                                                            const TPipe_P2PRequest* request);
```

### 9.3 Config Struct

```c
typedef struct {
    TPipe_MultiConnectorExecutionMode mode;
    TPipe_TraceConfig trace_config;
    // ... additional fields
} TPipe_MultiConnectorConfig;
```

### 9.4 Configuration Setters (Config Struct)

```c
TPipe_MultiConnectorConfig* TPipe_MultiConnectorConfig_setMode(TPipe_MultiConnectorConfig* cfg,
                                                                    TPipe_MultiConnectorExecutionMode mode);
TPipe_MultiConnectorConfig* TPipe_MultiConnectorConfig_enableTracing(TPipe_MultiConnectorConfig* cfg,
                                                                         const TPipe_TraceConfig* config);
TPipe_MultiConnectorConfig* TPipe_MultiConnectorConfig_add(TPipe_MultiConnectorConfig* cfg,
                                                               TPipe_connectorHandle connector);
```

### 9.5 Lifecycle

```c
TPipe_multiConnectorHandle TPipe_MultiConnector_create(void);
TPipe_multiConnectorHandle TPipe_MultiConnector_createEx(const TPipe_MultiConnectorConfig* config);
TPipe_Result TPipe_MultiConnector_destroy(TPipe_multiConnectorHandle handle);
```

### 9.6 Execution

```c
// Execute single content through connectors
TPipe_Content** TPipe_MultiConnector_execute(TPipe_multiConnectorHandle handle,
                                              const char** paths,
                                              int path_count,
                                              const TPipe_Content* input,
                                              int* out_result_count,
                                              TPipe_Result* out_result);

// Execute multiple content items through connectors
TPipe_Content** TPipe_MultiConnector_executeContentList(TPipe_multiConnectorHandle handle,
                                                           const char** paths,
                                                           int path_count,
                                                           const TPipe_Content** inputs,
                                                           int input_count,
                                                           int* out_result_count,
                                                           TPipe_Result* out_result);

// Execute parallel (round-robin distribution)
TPipe_Content** TPipe_MultiConnector_executeParallel(TPipe_multiConnectorHandle handle,
                                                       const TPipe_Content** inputs,
                                                       int input_count,
                                                       const char** paths,
                                                       int path_count,
                                                       int* out_result_count,
                                                       TPipe_Result* out_result);

// Async begin (for single content execute)
TPipe_AsyncHandle TPipe_MultiConnector_executeBegin(TPipe_multiConnectorHandle handle,
                                                      const char** paths,
                                                      int path_count,
                                                      const TPipe_Content* input);
TPipe_Content** TPipe_MultiConnector_executeEnd(TPipe_AsyncHandle async_handle,
                                                 int* out_result_count,
                                                 TPipe_Result* out_result);
```

---

## 10. DistributionGrid — Distributed Node Grid

`DistributionGrid` is a distributed node routing container with registry-based discovery, remote handoff, and durable checkpoint/resume.

**Handle:** `TPipe_distributionGridHandle`
**Config struct:** `TPipe_DistributionGridConfig`

### 10.1 Overview

DistributionGrid manages local bindings (router, worker, peers) and discovers external nodes via registries. It supports:
- Peer discovery via bootstrap registries and hosted catalogs
- Registry membership with lease renewal
- Public node/registry listings on hosted registries
- Hooks at every execution phase (before/after route, worker, peer dispatch, etc.)
- Auto-renewal loops for public listings
- Checkpoint/resume for long-running tasks

### 10.2 P2PInterface

```c
TPipe_Result TPipe_DistributionGrid_setP2pDescription(TPipe_distributionGridHandle handle,
                                                       const TPipe_P2PDescriptor* descriptor);
const TPipe_P2PDescriptor* TPipe_DistributionGrid_getP2pDescription(TPipe_distributionGridHandle handle);
TPipe_Result TPipe_DistributionGrid_setP2pTransport(TPipe_distributionGridHandle handle,
                                                     const TPipe_P2PTransport* transport);
const TPipe_P2PTransport* TPipe_DistributionGrid_getP2pTransport(TPipe_distributionGridHandle handle);
TPipe_Result TPipe_DistributionGrid_setP2pRequirements(TPipe_distributionGridHandle handle,
                                                         const TPipe_P2PRequirements* requirements);
const TPipe_P2PRequirements* TPipe_DistributionGrid_getP2pRequirements(TPipe_distributionGridHandle handle);
void* TPipe_DistributionGrid_getContainerObject(TPipe_distributionGridHandle handle);
TPipe_Result TPipe_DistributionGrid_setContainerObject(TPipe_distributionGridHandle handle,
                                                          void* container);
const TPipe_PipelineHandle* TPipe_DistributionGrid_getPipelinesFromInterface(
    TPipe_distributionGridHandle handle, int* out_count);
TPipe_P2PResponse* TPipe_DistributionGrid_executeP2PRequest(TPipe_distributionGridHandle handle,
                                                            const TPipe_P2PRequest* request);
TPipe_Content* TPipe_DistributionGrid_executeLocal(TPipe_distributionGridHandle handle,
                                                    const TPipe_Content* input,
                                                    TPipe_Result* out_result);
```

### 10.3 Enums

```c
typedef enum {
    TPIPE_DISTRIBUTION_GRID_DISCOVERY_MODE_MANUAL = 0,
    TPIPE_DISTRIBUTION_GRID_DISCOVERY_MODE_AUTO = 1
} TPipe_DistributionGridPeerDiscoveryMode;

typedef enum {
    TPIPE_DISTRIBUTION_GRID_ROUTING_POLICY_ROUND_ROBIN = 0,
    TPIPE_DISTRIBUTION_GRID_ROUTING_POLICY_WEIGHTED = 1,
    TPIPE_DISTRIBUTION_GRID_ROUTING_POLICY_LEAST_LOADED = 2
} TPipe_DistributionGridRoutingPolicy;

typedef enum {
    TPIPE_DISTRIBUTION_GRID_MEMORY_POLICY_NONE = 0,
    TPIPE_DISTRIBUTION_GRID_MEMORY_POLICY_COMPACT = 1,
    TPIPE_DISTRIBUTION_GRID_MEMORY_POLICY_SUMMARIZE = 2
} TPipe_DistributionGridMemoryPolicy;
```

### 10.4 Config Struct

```c
typedef struct {
    TPipe_DistributionGridPeerDiscoveryMode discovery_mode;
    TPipe_DistributionGridRoutingPolicy routing_policy;
    TPipe_DistributionGridMemoryPolicy memory_policy;
    int max_hops;
    long rpc_timeout_ms;
    int max_session_duration_seconds;
    TPipe_TraceConfig trace_config;
    // ... additional fields
} TPipe_DistributionGridConfig;
```

### 10.5 Configuration Setters (Config Struct)

```c
// Binding configuration
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setRouter(
    TPipe_DistributionGridConfig* cfg,
    TPipe_P2PInterface* component,
    const TPipe_P2PDescriptor* descriptor,
    const TPipe_P2PRequirements* requirements);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setWorker(
    TPipe_DistributionGridConfig* cfg,
    TPipe_P2PInterface* component,
    const TPipe_P2PDescriptor* descriptor,
    const TPipe_P2PRequirements* requirements);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_addPeer(
    TPipe_DistributionGridConfig* cfg,
    TPipe_P2PInterface* component,
    const TPipe_P2PDescriptor* descriptor,
    const TPipe_P2PRequirements* requirements);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_addPeerDescriptor(
    TPipe_DistributionGridConfig* cfg,
    const TPipe_P2PDescriptor* descriptor);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_removePeer(
    TPipe_DistributionGridConfig* cfg, const char* peer_key);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_replacePeer(
    TPipe_DistributionGridConfig* cfg, const char* peer_key,
    TPipe_P2PInterface* component,
    const TPipe_P2PDescriptor* descriptor,
    const TPipe_P2PRequirements* requirements);

// Behavior configuration
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setDiscoveryMode(
    TPipe_DistributionGridConfig* cfg, TPipe_DistributionGridPeerDiscoveryMode mode);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setRoutingPolicy(
    TPipe_DistributionGridConfig* cfg, TPipe_DistributionGridRoutingPolicy policy);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setMemoryPolicy(
    TPipe_DistributionGridConfig* cfg, TPipe_DistributionGridMemoryPolicy policy);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setDurableStore(
    TPipe_DistributionGridConfig* cfg,
    const TPipe_DistributionGridDurableStore* store);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setRegistryMetadata(
    TPipe_DistributionGridConfig* cfg,
    const TPipe_DistributionGridRegistryMetadata* metadata);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setTrustVerifier(
    TPipe_DistributionGridConfig* cfg,
    const TPipe_DistributionGridTrustVerifier* verifier);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setMaxHops(
    TPipe_DistributionGridConfig* cfg, int max_hops);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setRpcTimeout(
    TPipe_DistributionGridConfig* cfg, long timeout_ms);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setMaxSessionDuration(
    TPipe_DistributionGridConfig* cfg, int seconds);

// Bootstrap configuration
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_addBootstrapRegistry(
    TPipe_DistributionGridConfig* cfg,
    const TPipe_DistributionGridRegistryAdvertisement* advertisement);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_addBootstrapCatalogSource(
    TPipe_DistributionGridConfig* cfg,
    const TPipe_DistributionGridBootstrapCatalogSource* source);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_removeBootstrapCatalogSource(
    TPipe_DistributionGridConfig* cfg, const char* source_id);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_removeBootstrapRegistry(
    TPipe_DistributionGridConfig* cfg, const char* registry_id);

// Tracing
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_enableTracing(
    TPipe_DistributionGridConfig* cfg, const TPipe_TraceConfig* config);

// Hooks
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setBeforeRouteHook(
    TPipe_DistributionGridConfig* cfg,
    TPipe_DistributionGridEnvelopeHook* hook, void* user_data);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setBeforeLocalWorkerHook(
    TPipe_DistributionGridConfig* cfg,
    TPipe_DistributionGridEnvelopeHook* hook, void* user_data);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setAfterLocalWorkerHook(
    TPipe_DistributionGridConfig* cfg,
    TPipe_DistributionGridEnvelopeHook* hook, void* user_data);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setBeforePeerDispatchHook(
    TPipe_DistributionGridConfig* cfg,
    TPipe_DistributionGridEnvelopeHook* hook, void* user_data);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setAfterPeerResponseHook(
    TPipe_DistributionGridConfig* cfg,
    TPipe_DistributionGridEnvelopeHook* hook, void* user_data);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setOutboundMemoryHook(
    TPipe_DistributionGridConfig* cfg,
    TPipe_DistributionGridEnvelopeHook* hook, void* user_data);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setFailureHook(
    TPipe_DistributionGridConfig* cfg,
    TPipe_DistributionGridEnvelopeHook* hook, void* user_data);
TPipe_DistributionGridConfig* TPipe_DistributionGridConfig_setOutcomeTransformationHook(
    TPipe_DistributionGridConfig* cfg,
    TPipe_DistributionGridOutcomeHook* hook, void* user_data);
```

### 10.6 Lifecycle

```c
TPipe_distributionGridHandle TPipe_DistributionGrid_create(void);
TPipe_distributionGridHandle TPipe_DistributionGrid_createEx(const TPipe_DistributionGridConfig* config);
TPipe_Result TPipe_DistributionGrid_init(TPipe_distributionGridHandle handle);
TPipe_Result TPipe_DistributionGrid_destroy(TPipe_distributionGridHandle handle);
```

### 10.7 Execution

```c
TPipe_Content* TPipe_DistributionGrid_execute(TPipe_distributionGridHandle handle,
                                                const TPipe_Content* input,
                                                TPipe_Result* out_result);
TPipe_AsyncHandle TPipe_DistributionGrid_executeBegin(TPipe_distributionGridHandle handle,
                                                        const TPipe_Content* input);
TPipe_Content* TPipe_DistributionGrid_executeEnd(TPipe_AsyncHandle async_handle,
                                                   TPipe_Result* out_result);
TPipe_Content* TPipe_DistributionGrid_resumeTask(TPipe_distributionGridHandle handle,
                                                 const char* task_id,
                                                 TPipe_Result* out_result);

// Registry operations
TPipe_DistributionGridRegistryAdvertisement** TPipe_DistributionGrid_probeTrustedRegistries(
    TPipe_distributionGridHandle handle,
    int* out_count,
    TPipe_Result* out_result);
TPipe_DistributionGridRegistrationLease* TPipe_DistributionGrid_registerWithRegistry(
    TPipe_distributionGridHandle handle,
    const char* registry_id,
    int requested_lease_seconds,
    TPipe_Result* out_result);
TPipe_DistributionGridRegistrationLease* TPipe_DistributionGrid_renewRegistryLease(
    TPipe_distributionGridHandle handle,
    const char* registry_id,
    TPipe_Result* out_result);
TPipe_DistributionGridRegistrationLease** TPipe_DistributionGrid_tickRegistryMemberships(
    TPipe_distributionGridHandle handle,
    long now_epoch_millis,
    int* out_count,
    TPipe_Result* out_result);
TPipe_DistributionGridNodeAdvertisement** TPipe_DistributionGrid_queryRegistries(
    TPipe_distributionGridHandle handle,
    const TPipe_DistributionGridRegistryQuery* query,
    const char** registry_ids,
    int registry_id_count,
    int* out_count,
    TPipe_Result* out_result);
TPipe_DistributionGridRegistryAdvertisement** TPipe_DistributionGrid_pullTrustedBootstrapCatalogs(
    TPipe_distributionGridHandle handle,
    const char** source_ids,
    int source_id_count,
    int* out_count,
    TPipe_Result* out_result);

// Public listing operations
TPipe_P2PHostedRegistryMutationResult* TPipe_DistributionGrid_publishPublicNodeListing(
    TPipe_distributionGridHandle handle,
    const TPipe_P2PTransport* transport,
    const TPipe_DistributionGridPublicListingOptions* options,
    const char* auth_body,
    const char* transport_auth_body,
    TPipe_Result* out_result);
TPipe_P2PHostedRegistryMutationResult* TPipe_DistributionGrid_updatePublicNodeListing(
    TPipe_distributionGridHandle handle,
    const TPipe_P2PTransport* transport,
    const char* listing_id,
    const char* lease_id,
    const TPipe_DistributionGridPublicListingOptions* options,
    const char* auth_body,
    const char* transport_auth_body,
    TPipe_Result* out_result);
TPipe_P2PHostedRegistryMutationResult* TPipe_DistributionGrid_renewPublicNodeListing(
    TPipe_distributionGridHandle handle,
    const TPipe_P2PTransport* transport,
    const char* listing_id,
    const char* lease_id,
    int requested_lease_seconds,
    TPipe_Result* out_result);
TPipe_P2PHostedRegistryMutationResult* TPipe_DistributionGrid_removePublicNodeListing(
    TPipe_distributionGridHandle handle,
    const TPipe_P2PTransport* transport,
    const char* listing_id,
    const char* lease_id,
    TPipe_Result* out_result);
TPipe_P2PHostedRegistryMutationResult* TPipe_DistributionGrid_publishPublicRegistryListing(
    TPipe_distributionGridHandle handle,
    const TPipe_P2PTransport* transport,
    const TPipe_DistributionGridPublicListingOptions* options,
    const char* auth_body,
    const char* transport_auth_body,
    TPipe_Result* out_result);
TPipe_P2PHostedRegistryMutationResult* TPipe_DistributionGrid_updatePublicRegistryListing(
    TPipe_distributionGridHandle handle,
    const TPipe_P2PTransport* transport,
    const char* listing_id,
    const char* lease_id,
    const TPipe_DistributionGridPublicListingOptions* options,
    const char* auth_body,
    const char* transport_auth_body,
    TPipe_Result* out_result);
TPipe_P2PHostedRegistryMutationResult* TPipe_DistributionGrid_renewPublicRegistryListing(
    TPipe_distributionGridHandle handle,
    const TPipe_P2PTransport* transport,
    const char* listing_id,
    const char* lease_id,
    int requested_lease_seconds,
    TPipe_Result* out_result);
TPipe_P2PHostedRegistryMutationResult* TPipe_DistributionGrid_removePublicRegistryListing(
    TPipe_distributionGridHandle handle,
    const TPipe_P2PTransport* transport,
    const char* listing_id,
    const char* lease_id,
    TPipe_Result* out_result);

// Auto-renewal (string ID based)
const char* TPipe_DistributionGrid_startPublicNodeListingAutoRenew(
    TPipe_distributionGridHandle handle,
    const TPipe_P2PTransport* transport,
    const char* listing_id,
    const char* lease_id,
    int renewal_interval_seconds,
    TPipe_Result* out_result);
const char* TPipe_DistributionGrid_startPublicRegistryListingAutoRenew(
    TPipe_distributionGridHandle handle,
    const TPipe_P2PTransport* transport,
    const char* listing_id,
    const char* lease_id,
    int renewal_interval_seconds,
    TPipe_Result* out_result);
TPipe_Result TPipe_DistributionGrid_stopPublicListingAutoRenew(
    TPipe_distributionGridHandle handle, const char* renewal_id);
const char** TPipe_DistributionGrid_getPublicListingAutoRenewIds(
    TPipe_distributionGridHandle handle, int* out_count);
const TPipe_DistributionGridPublicListingAutoRenewStatus* TPipe_DistributionGrid_getPublicListingAutoRenewStatuses(
    TPipe_distributionGridHandle handle, int* out_count);
const TPipe_DistributionGridBootstrapCatalogSourceStatus* TPipe_DistributionGrid_getBootstrapCatalogSourceStatuses(
    TPipe_distributionGridHandle handle, int* out_count);
```

### 10.8 Runtime Control

```c
TPipe_Result TPipe_DistributionGrid_clearRuntimeState(TPipe_distributionGridHandle handle);
TPipe_Result TPipe_DistributionGrid_clearTrace(TPipe_distributionGridHandle handle);
TPipe_Result TPipe_DistributionGrid_pause(TPipe_distributionGridHandle handle);
TPipe_Result TPipe_DistributionGrid_pauseTask(TPipe_distributionGridHandle handle, const char* task_id);
TPipe_Result TPipe_DistributionGrid_resume(TPipe_distributionGridHandle handle);
TPipe_Result TPipe_DistributionGrid_resumeTask(TPipe_distributionGridHandle handle, const char* task_id);
int TPipe_DistributionGrid_isPaused(TPipe_distributionGridHandle handle);
int TPipe_DistributionGrid_canPause(TPipe_distributionGridHandle handle);
```

### 10.9 Query Getters

```c
// Key getters
const char* TPipe_DistributionGrid_getRouterBindingKey(TPipe_distributionGridHandle handle);
const char* TPipe_DistributionGrid_getWorkerBindingKey(TPipe_distributionGridHandle handle);
const char** TPipe_DistributionGrid_getLocalPeerKeys(TPipe_distributionGridHandle handle, int* out_count);
const char** TPipe_DistributionGrid_getExternalPeerKeys(TPipe_distributionGridHandle handle, int* out_count);
const char** TPipe_DistributionGrid_getBootstrapRegistryIds(TPipe_distributionGridHandle handle, int* out_count);
const char** TPipe_DistributionGrid_getBootstrapCatalogSourceIds(TPipe_distributionGridHandle handle, int* out_count);
const char** TPipe_DistributionGrid_getDiscoveredRegistryIds(TPipe_distributionGridHandle handle, int* out_count);
const char** TPipe_DistributionGrid_getDiscoveredNodeIds(TPipe_distributionGridHandle handle, int* out_count);
const char** TPipe_DistributionGrid_getActiveRegistryLeaseIds(TPipe_distributionGridHandle handle, int* out_count);

// Config getters
TPipe_DistributionGridPeerDiscoveryMode TPipe_DistributionGrid_getDiscoveryMode(TPipe_distributionGridHandle handle);
TPipe_DistributionGridRoutingPolicy TPipe_DistributionGrid_getRoutingPolicy(TPipe_distributionGridHandle handle);
TPipe_DistributionGridMemoryPolicy TPipe_DistributionGrid_getMemoryPolicy(TPipe_distributionGridHandle handle);
const TPipe_DistributionGridDurableStore* TPipe_DistributionGrid_getDurableStore(TPipe_distributionGridHandle handle);
int TPipe_DistributionGrid_getMaxHops(TPipe_distributionGridHandle handle);
long TPipe_DistributionGrid_getRpcTimeout(TPipe_distributionGridHandle handle);
int TPipe_DistributionGrid_getMaxSessionDuration(TPipe_distributionGridHandle handle);
const TPipe_DistributionGridTrustVerifier* TPipe_DistributionGrid_getTrustVerifier(TPipe_distributionGridHandle handle);
const TPipe_DistributionGridRegistryMetadata* TPipe_DistributionGrid_getRegistryMetadata(TPipe_distributionGridHandle handle);
int TPipe_DistributionGrid_isTracingEnabled(TPipe_distributionGridHandle handle);

// Trace getters
const char* TPipe_DistributionGrid_getTraceId(TPipe_distributionGridHandle handle);
const char* TPipe_DistributionGrid_getTraceReport(TPipe_distributionGridHandle handle, TPipe_TraceFormat format);
const TPipe_FailureAnalysis* TPipe_DistributionGrid_getFailureAnalysis(TPipe_distributionGridHandle handle);
```

---

## 11. Reference Table

| Function | Parameters | Return | Ownership |
|----------|-------------|--------|-----------|
| **Lifecycle — Pipeline** | | | |
| `TPipe_Pipeline_create` | `void` | `TPipe_PipelineHandle` | Owned |
| `TPipe_Pipeline_createEx` | `const TPipe_PipelineConfig*` | `TPipe_PipelineHandle` | Owned |
| `TPipe_Pipeline_init` | `TPipe_PipelineHandle` | `TPipe_Result` | — |
| `TPipe_Pipeline_destroy` | `TPipe_PipelineHandle` | `TPipe_Result` | — |
| **Config Struct — Pipeline** | | | |
| `TPipe_PipelineConfig_default` | `void` | `TPipe_PipelineConfig` | Owned |
| `TPipe_PipelineConfig_setName` | `TPipe_PipelineConfig*, const char*` | `TPipe_PipelineConfig*` | — |
| `TPipe_PipelineConfig_addPipe` | `TPipe_PipelineConfig*, TPipe_PipeHandle` | `TPipe_PipelineConfig*` | — |
| `TPipe_PipelineConfig_insertPipe` | `TPipe_PipelineConfig*, TPipe_PipeHandle, int` | `TPipe_PipelineConfig*` | — |
| `TPipe_PipelineConfig_addAllPipes` | `TPipe_PipelineConfig*, const TPipe_PipeHandle*, int` | `TPipe_PipelineConfig*` | — |
| `TPipe_PipelineConfig_setContextWindow` | `TPipe_PipelineConfig*, TPipe_ContextHandle` | `TPipe_PipelineConfig*` | — |
| `TPipe_PipelineConfig_setMiniBank` | `TPipe_PipelineConfig*, TPipe_MiniBankHandle` | `TPipe_PipelineConfig*` | — |
| `TPipe_PipelineConfig_enableTracing` | `TPipe_PipelineConfig*, const TPipe_TraceConfig*` | `TPipe_PipelineConfig*` | — |
| **Lifecycle — Manifold** | | | |
| `TPipe_Manifold_create` | `void` | `TPipe_manifoldHandle` | Owned |
| `TPipe_Manifold_createEx` | `const TPipe_ManifoldConfig*` | `TPipe_manifoldHandle` | Owned |
| `TPipe_Manifold_init` | `TPipe_manifoldHandle` | `TPipe_Result` | — |
| `TPipe_Manifold_destroy` | `TPipe_manifoldHandle` | `TPipe_Result` | — |
| **Lifecycle — Junction** | | | |
| `TPipe_Junction_create` | `void` | `TPipe_junctionHandle` | Owned |
| `TPipe_Junction_createEx` | `const TPipe_JunctionConfig*` | `TPipe_junctionHandle` | Owned |
| `TPipe_Junction_init` | `TPipe_junctionHandle` | `TPipe_Result` | — |
| `TPipe_Junction_destroy` | `TPipe_junctionHandle` | `TPipe_Result` | — |
| **Lifecycle — Splitter** | | | |
| `TPipe_Splitter_create` | `void` | `TPipe_splitterHandle` | Owned |
| `TPipe_Splitter_createEx` | `const TPipe_SplitterConfig*` | `TPipe_splitterHandle` | Owned |
| `TPipe_Splitter_init` | `TPipe_splitterHandle` | `TPipe_Result` | — |
| `TPipe_Splitter_destroy` | `TPipe_splitterHandle` | `TPipe_Result` | — |
| **Lifecycle — Connector** | | | |
| `TPipe_Connector_create` | `void` | `TPipe_connectorHandle` | Owned |
| `TPipe_Connector_createEx` | `const TPipe_ConnectorConfig*` | `TPipe_connectorHandle` | Owned |
| `TPipe_Connector_destroy` | `TPipe_connectorHandle` | `TPipe_Result` | — |
| **Lifecycle — MultiConnector** | | | |
| `TPipe_MultiConnector_create` | `void` | `TPipe_multiConnectorHandle` | Owned |
| `TPipe_MultiConnector_createEx` | `const TPipe_MultiConnectorConfig*` | `TPipe_multiConnectorHandle` | Owned |
| `TPipe_MultiConnector_destroy` | `TPipe_multiConnectorHandle` | `TPipe_Result` | — |
| **Lifecycle — DistributionGrid** | | | |
| `TPipe_DistributionGrid_create` | `void` | `TPipe_distributionGridHandle` | Owned |
| `TPipe_DistributionGrid_createEx` | `const TPipe_DistributionGridConfig*` | `TPipe_distributionGridHandle` | Owned |
| `TPipe_DistributionGrid_init` | `TPipe_distributionGridHandle` | `TPipe_Result` | — |
| `TPipe_DistributionGrid_destroy` | `TPipe_distributionGridHandle` | `TPipe_Result` | — |
| **Execution — Pipeline** | | | |
| `TPipe_Pipeline_execute` | `TPipe_PipelineHandle, const char*, TPipe_Result*` | `TPipe_Content*` | Owned |
| `TPipe_Pipeline_executeContent` | `TPipe_PipelineHandle, const TPipe_Content*, TPipe_Result*` | `TPipe_Content*` | Owned |
| `TPipe_Pipeline_executeBegin` | `TPipe_PipelineHandle, const TPipe_Content*` | `TPipe_AsyncHandle` | Owned |
| `TPipe_Pipeline_executeEnd` | `TPipe_AsyncHandle, TPipe_Result*` | `TPipe_Content*` | Owned |
| **Execution — Manifold** | | | |
| `TPipe_Manifold_execute` | `TPipe_manifoldHandle, const TPipe_Content*, TPipe_Result*` | `TPipe_Content*` | Owned |
| `TPipe_Manifold_executeBegin` | `TPipe_manifoldHandle, const TPipe_Content*` | `TPipe_AsyncHandle` | Owned |
| `TPipe_Manifold_executeEnd` | `TPipe_AsyncHandle, TPipe_Result*` | `TPipe_Content*` | Owned |
| **Execution — Junction** | | | |
| `TPipe_Junction_execute` | `TPipe_junctionHandle, const TPipe_Content*, TPipe_Result*` | `TPipe_Content*` | Owned |
| `TPipe_Junction_conductDiscussion` | `TPipe_junctionHandle, const TPipe_Content*, TPipe_Result*` | `TPipe_Content*` | Owned |
| `TPipe_Junction_conductWorkflow` | `TPipe_junctionHandle, const TPipe_Content*, TPipe_Result*` | `TPipe_Content*` | Owned |
| `TPipe_Junction_executeBegin` | `TPipe_junctionHandle, const TPipe_Content*` | `TPipe_AsyncHandle` | Owned |
| `TPipe_Junction_executeEnd` | `TPipe_AsyncHandle, TPipe_Result*` | `TPipe_Content*` | Owned |
| **Execution — Splitter** | | | |
| `TPipe_Splitter_executePipelines` | `TPipe_splitterHandle, TPipe_Result*` | `TPipe_Content*` | Owned |
| `TPipe_Splitter_executePipelinesBegin` | `TPipe_splitterHandle` | `TPipe_AsyncHandle` | Owned |
| `TPipe_Splitter_executePipelinesEnd` | `TPipe_AsyncHandle, TPipe_Result*` | `TPipe_Content*` | Owned |
| **Execution — Connector** | | | |
| `TPipe_Connector_execute` | `TPipe_connectorHandle, const char*, const TPipe_Content*, TPipe_Result*` | `TPipe_Content*` | Owned |
| `TPipe_Connector_executeBegin` | `TPipe_connectorHandle, const char*, const TPipe_Content*` | `TPipe_AsyncHandle` | Owned |
| `TPipe_Connector_executeEnd` | `TPipe_AsyncHandle, TPipe_Result*` | `TPipe_Content*` | Owned |
| **Execution — MultiConnector** | | | |
| `TPipe_MultiConnector_execute` | `TPipe_multiConnectorHandle, const char**, int, const TPipe_Content*, int*, TPipe_Result*` | `TPipe_Content**` | Owned |
| `TPipe_MultiConnector_executeContentList` | `TPipe_multiConnectorHandle, const char**, int, const TPipe_Content**, int, int*, TPipe_Result*` | `TPipe_Content**` | Owned |
| `TPipe_MultiConnector_executeParallel` | `TPipe_multiConnectorHandle, const TPipe_Content**, int, const char**, int, int*, TPipe_Result*` | `TPipe_Content**` | Owned |
| `TPipe_MultiConnector_executeBegin` | `TPipe_multiConnectorHandle, const char**, int, const TPipe_Content*` | `TPipe_AsyncHandle` | Owned |
| `TPipe_MultiConnector_executeEnd` | `TPipe_AsyncHandle, int*, TPipe_Result*` | `TPipe_Content**` | Owned |
| **Execution — DistributionGrid** | | | |
| `TPipe_DistributionGrid_execute` | `TPipe_distributionGridHandle, const TPipe_Content*, TPipe_Result*` | `TPipe_Content*` | Owned |
| `TPipe_DistributionGrid_executeBegin` | `TPipe_distributionGridHandle, const TPipe_Content*` | `TPipe_AsyncHandle` | Owned |
| `TPipe_DistributionGrid_executeEnd` | `TPipe_AsyncHandle, TPipe_Result*` | `TPipe_Content*` | Owned |
| `TPipe_DistributionGrid_resumeTask` | `TPipe_distributionGridHandle, const char*, TPipe_Result*` | `TPipe_Content*` | Owned |

*Reference table continues — see full spec for complete function listing.*

---

*This document will be updated as the spec progresses.*