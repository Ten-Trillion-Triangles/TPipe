# GraalVM Native ABI Specification — Core Infrastructure

**Spec File:** graalvm-abi-core-infrastructure.md  
**Version:** 0.2.0-draft  
**Created:** 2026-05-07  
**Status:** Draft - Based on confirmed design decisions

---

## 1. Design Decisions (Confirmed)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Reference Counting | Shared refcounting (Option A) | Familiar COM/Wasm pattern; explicit addRef/release |
| Handle Table | ConcurrentHashMap | Standard JVM, GraalVM-optimized, thread-safe |
| Init State | Detailed flags via TPipe_LibraryState | Binary was insufficient; 5-state enum needed |
| Error Handling | TPipe_Result + out-param | Consistent across all functions |

---

## 2. TPipe_Result Error Codes

```c
typedef enum {
    TPIPE_OK                      = 0,   // Success
    TPIPE_ERR_INVALID_HANDLE      = 1,   // Handle is null/invalid/freed
    TPIPE_ERR_INVALID_ARGUMENT    = 2,   // NULL out-param, invalid enum value, etc.
    TPIPE_ERR_BUFFER_TOO_SMALL    = 3,   // Caller buffer insufficient
    TPIPE_ERR_NOT_INITIALIZED      = 4,   // API called before TPipe_init()
    TPIPE_ERR_ALREADY_INITIALIZED  = 5,   // TPipe_init() called twice (should be no-op)
    TPIPE_ERR_STATE_TRANSITION     = 6,   // Invalid state change (e.g., shutdown during init)
    TPIPE_ERR_OUT_OF_MEMORY        = 7,   // GC could not satisfy allocation
    TPIPE_ERR_UNSUPPORTED          = 8,   // Feature not available in this build
    TPIPE_ERR_TIMEOUT              = 9,   // Operation timed out
    TPIPE_ERR_SERIALIZATION        = 10,  // JSON encode/decode failed
    TPIPE_ERR_INTERNAL             = 99   // Unexpected internal error
} TPipe_Result;
```

### 2.1 String Return Convention

All functions returning `const char*` follow this rule:

```c
// TPipe returns const char* — caller MUST NOT free
// String lifetime: valid until next ABI call on same handle, or TPipe_shutdown()

const char* TPipe_Pipe_getModel(TPipe_PipeHandle handle);
// Caller must copy if persistence beyond next ABI call is needed:
char* copy = strdup(model);  // caller owns, must free()
```

### 2.2 Result Memory Management

For functions that allocate and return complex result structures (such as PCP tool execution results), TPipe follows a **caller-owned free** model:

```c
// Free TPipe-allocated result structures.
// Must be called by the caller for any TPipe_Result payload where TPipe
// owned and allocated the memory internally.
//
// After this call, the TPipe_Result handle is invalid — do not use it again.
//
// Usage pattern:
//   TPipe_Result result = TPipe_PCP_execute(request, &out_size);
//   if (result != TPIPE_OK) { /* handle error */ }
//   TPipe_Result_free(result);  // caller frees allocated result data

TPipe_Result TPipe_Result_free(TPipe_Result result);
```

**When to call `TPipe_Result_free()`:**

| Scenario | Who calls free? | Notes |
|----------|----------------|-------|
| PCP tool returns JSON/payload result | **Caller** | TPipe allocated result struct |
| String returned via `const char*` out-param | Caller owns buffer | No free needed — caller owns the buffer |
| Handle returned from create/clone | **Caller** | Use `TPipe_Handle_release()` not `TPipe_Result_free()` |
| TPipe_Result from sync call (success path) | **Caller** | Only when result contains allocated payload |

> **Do not confuse `TPipe_Result_free()` with `TPipe_Handle_release()`** — they operate on different handle types. Use `TPipe_Handle_release()` for all handle types (`TPipe_ContentHandle`, `TPipe_PipeHandle`, `TPipe_ListHandle`, etc.). Use `TPipe_Result_free()` only for the `TPipe_Result` integer code itself when it carries an allocated payload.

---

## 3. Struct Definitions

### 3.1 TPipe_ContextWindowSettings

Controls how the context window handles truncation. Corresponds to `ContextWindowSettings` enum and `TruncationSettings` data class in the Kotlin codebase.

```c
// Truncation method — corresponds to com.TTT.Enums.ContextWindowSettings
typedef enum {
    TPIPE_CONTEXT_TRUNCATE_TOP,      // Chop from beginning
    TPIPE_CONTEXT_TRUNCATE_BOTTOM,   // Chop from end
    TPIPE_CONTEXT_TRUNCATE_MIDDLE    // Chop from both ends evenly
} TPipe_ContextTruncationMethod;

// Full settings struct — corresponds to Pipe.kt:62 TruncationSettings
typedef struct {
    // Token budgeting
    int32_t  multiplyWindowSizeBy;      // default: 0
    int32_t  maxTokenOutput;            // max tokens for LLM output (reserved)
    int32_t  reasoningBudgetTokens;     // max reasoning/thinking tokens

    // Token counting controls
    int32_t  contextWindowSize;         // total context window size
    int32_t  userPromptMaxTokens;       // max tokens for user prompt
    int32_t  nonWordSplitCount;         // default: 4
    double   tokenCountingBias;         // default: 0.0

    // Word-boundary behavior
    int32_t  countSubWordsInFirstWord;  // default: true (non-zero)
    int32_t  favorWholeWords;           // default: true (non-zero)
    int32_t  countOnlyFirstWordFound;   // default: false (zero)
    int32_t  splitForNonWordChar;       // default: true (non-zero)
    int32_t  alwaysSplitIfWholeWordExists; // default: false (zero)
    int32_t  countSubWordsIfSplit;      // default: false (zero)

    // Fill modes
    int32_t  fillMode;                  // default: false (zero)
    int32_t  fillAndSplitMode;          // default: false (zero)

    // Truncation control
    int32_t  truncationMethod;          // TPipe_ContextTruncationMethod enum
    int32_t  allowUserPromptTruncation; // default: true (non-zero)
    int32_t  compressUserPrompt;        // default: false (zero)
    int32_t  truncateContextWindowAsString; // default: false (zero)
    int32_t  preserveTextMatches;       // default: false (zero)

    // Multi-page budget strategy
    int32_t  multiPageBudgetStrategy;  // 0=EQUAL_SPLIT, 1=WEIGHTED_SPLIT, 2=PRIORITY_FILL, 3=DYNAMIC_FILL

    // Page weights (JSON-serialized map, nullable)
    const char* pageWeightsJson;        // nullable, e.g. "{\"page1\": 0.5, \"page2\": 0.5}"
} TPipe_ContextWindowSettings;
```

**Cross-reference:** `com.TTT.Enums.ContextWindowSettings` (3-value enum) + `com.TTT.Pipe.TruncationSettings` (Pipe.kt:62, 13 fields).

**Allocation:** Caller-allocated. Pass pointer to `TPipe_ManifoldConfig_setTruncationMethod()`.

---

### 3.2 TPipe_TokenBudgetSettings

Advanced token budgeting configuration. Corresponds to `TokenBudgetSettings` data class in Pipe.kt:77.

```c
typedef struct {
    int32_t  userPromptSize;           // Max tokens for user prompt. Throws error or truncates if exceeded.
    int32_t  maxTokens;                // Max LLM output tokens (includes reasoning + response).
    int32_t  reasoningBudget;          // Max reasoning/thinking tokens. Subtracts from maxTokens.
    int32_t  contextWindowSize;         // Total token budget (input + output). Override for Pipe's ContextWindow.
    int32_t  allowUserPromptTruncation; // 0 = throw error on overflow, non-zero = truncate
    int32_t  compressUserPrompt;        // 0 = error on overflow, non-zero = semantic compression
    int32_t  truncateContextWindowAsString; // 0 = error, non-zero = truncate
    int32_t  preserveTextMatches;       // non-zero = preserve matched text in converse history
    int32_t  truncationMethod;          // TPipe_ContextTruncationMethod enum
    int32_t  multiPageBudgetStrategy;   // 0=EQUAL_SPLIT, 1=WEIGHTED_SPLIT, 2=PRIORITY_FILL, 3=DYNAMIC_FILL
    const char* pageWeightsJson;        // nullable JSON map of page weights
} TPipe_TokenBudgetSettings;
```

**Cross-reference:** `com.TTT.Pipe.TokenBudgetSettings` (Pipe.kt:77, 10 fields).

**Allocation:** Caller-allocated. Pass pointer to `TPipe_ManifoldConfig_setManagerTokenBudget()`.

---

### 3.3 TPipe_JunctionMemoryPolicy

Memory management policy for Junction's democratic voting flow. Controls how intermediate results are retained or discarded between rounds.

```c
typedef struct {
    int32_t retainRoundResults;    // non-zero = keep results from each voting round
    int32_t retainModeratorState;  // non-zero = keep moderator decision history
    int32_t maxRoundsStored;       // Maximum rounds to retain. 0 = unlimited.
    int32_t discardOnShutdown;     // non-zero = discard all on TPipe_shutdown (default behavior)
} TPipe_JunctionMemoryPolicy;
```

**Cross-reference:** `JunctionDsl.kt` memoryPolicy DSL block. Junction is design-notes / not-implemented, so this struct is defined for completeness but may not yet have a builder path.

**Allocation:** Caller-allocated. Pass pointer to `TPipe_JunctionConfig_setMemoryPolicy()`.

---

### 3.4 TPipe_JunctionConfig

Configuration struct for Junction democratic voting. Corresponds to `Junction.kt` internal config.

```c
typedef struct {
    int32_t maxRounds;             // Maximum voting rounds before forced conclusion
    int32_t minVotes;             // Minimum votes required for decision
    double  decisionThreshold;     // 0.0-1.0, vote weight threshold for consensus
    int32_t timeoutMs;            // Timeout for entire Junction execution (milliseconds)
    // ... additional fields TBD — see pipeline-api.md §6
} TPipe_JunctionConfig;
```

**Status:** Partial definition. `pipeline-api.md` shows `// ... additional fields` placeholder. Fields above are the confirmed ones; additional fields require codebase audit of Junction.kt internals.

**Setter methods:**
```c
TPipe_JunctionConfig TPipe_JunctionConfig_setMaxRounds(TPipe_JunctionConfig* config, int32_t maxRounds);
TPipe_JunctionConfig TPipe_JunctionConfig_setMinVotes(TPipe_JunctionConfig* config, int32_t minVotes);
TPipe_JunctionConfig TPipe_JunctionConfig_setDecisionThreshold(TPipe_JunctionConfig* config, double threshold);
TPipe_JunctionConfig TPipe_JunctionConfig_setTimeoutMs(TPipe_JunctionConfig* config, int32_t timeoutMs);
TPipe_JunctionConfig TPipe_JunctionConfig_setMemoryPolicy(TPipe_JunctionConfig* config, const TPipe_JunctionMemoryPolicy* policy);
```

---

### 3.5 TPipe_P2PResponse / TPipe_P2PRequest

P2P message types for Manifold P2P dispatch. Corresponds to `P2PRequest` and `AgentRequest` data classes.

```c
typedef struct {
    const char* requestId;        // Unique identifier for this request
    const char* sourceAgentId;    // Agent that originated the request
    const char* targetAgentId;    // Target agent (nullable for broadcast)
    const char* payloadJson;      // JSON-encoded payload (caller-encoded)
    int32_t     timeoutMs;        // Request timeout in milliseconds
    int32_t     priority;         // Higher = more urgent (used for queue ordering)
} TPipe_P2PRequest;

typedef struct {
    const char* requestId;        // Matches the originating request
    int32_t     success;          // non-zero = success, zero = failure
    const char* resultJson;       // JSON-encoded result (TPipe-encoded, caller frees)
    const char* errorMessage;     // Error description if success == 0
} TPipe_P2PResponse;
```

**Cross-reference:** `com.TTT.P2P.P2PRequest` (P2PRequest.kt:83), `com.TTT.P2P.AgentRequest` (P2PRequest.kt:101).

**Lifecycle:** `TPipe_P2PResponse` is TPipe-allocated on return. Caller must call `TPipe_Result_free()` to release.

---

### 3.6 TPipe_PipeConfig / TPipe_PipeConfigHandle

Base configuration for a Pipe. Corresponds to `PipeSettings.kt` (47-field data class) and `TokenBudgetSettings` in the Kotlin codebase. Used by `TPipe_Pipe_getConfig()` to export the pipe's full configuration snapshot.

**Handle alias:**
```c
typedef uint64_t TPipe_PipeConfigHandle;  // type alias — underlying storage is a handle ID
```

**C struct definition:**
```c
typedef struct {
    // Identity
    const char* pipeName;           // nullable, set via TPipe_Pipeline_setName
    const char* pipeId;            // nullable, internal UUID
    const char* currentPipelineId; // nullable, pipeline this pipe belongs to

    // Provider & Model
    const char* provider;           // nullable, e.g. "aws_bedrock", "ollama"
    const char* model;             // nullable, e.g. "gpt-4", "claude-3-7-sonnet"
    int         supportsNativeJson; // non-zero = provider uses native JSON mode

    // Prompt configuration
    const char* systemPrompt;      // nullable, system prompt text
    const char* userPrompt;       // nullable, pre-set user prompt
    int         promptMode;        // 0=SINGLE, 1=CHAT, 2=INTERNAL_CONTEXT

    // JSON I/O
    const char* jsonOutput;       // nullable, e.g. "{\"headline\": \"\"}"
    const char* jsonInput;        // nullable, raw JSON input
    const char* jsonOutputInstructions; // nullable, instructions for JSON mode

    // Sampling parameters
    double      temperature;       // default: 0.0
    double      topP;             // default: 1.0
    int         topK;             // default: 0 (disabled)
    double      repetitionPenalty; // default: 1.0
    const char* stopSequences;    // nullable, JSON array of stop strings

    // Reasoning
    int         useModelReasoning; // non-zero = reasoning enabled
    int         modelReasoningSettingsV2; // V2 reasoning verbosity level
    const char* modelReasoningSettingsV3; // nullable, V3 mode string

    // Token budget (nested — corresponds to TokenBudgetSettings)
    int         hasTokenBudget;    // non-zero = token budget is configured
    int         userPromptSize;
    int         maxTokens;
    int         reasoningBudget;
    int         subtractReasoningFromInput;
    int         contextWindowSize;
    int         allowUserPromptTruncation;
    int         preserveJsonInUserPrompt;
    int         compressUserPrompt;
    int         truncateContextWindowAsString;
    int         preserveTextMatches;
    int         truncationMethod;  // TPipe_ContextTruncationMethod enum
    int         multiPageBudgetStrategy;
    const char* pageWeightsJson;   // nullable, JSON map

    // Context window
    int         contextWindowTruncation; // TPipe_ContextTruncationMethod enum
    int         truncateContextAsString; // non-zero = truncate as string
    int         autoTruncateContext;    // non-zero = auto-truncate enabled
    int         emplaceLorebook;         // non-zero = lorebook auto-injected
    int         appendLoreBook;          // non-zero = append mode
    int         loreBookFillMode;        // non-zero = fill mode
    int         loreBookFillAndSplitMode;// non-zero = fill+split mode

    // Context access
    int         readFromGlobalContext;    // non-zero = reads global context
    int         readFromPipelineContext; // non-zero = reads pipeline context
    int         updatePipelineContextOnExit; // non-zero = updates on exit
    int         autoInjectContext;       // non-zero = auto-inject context
    int         multiplyWindowSizeBy;    // multiplier for context window

    // MiniBank
    const char* pageKey;          // nullable, MiniBank page key
    const char* pageKeyListJson;   // nullable, JSON array of page keys

    // Token counting controls (TruncationSettings subset)
    int         countSubWordsInFirstWord;
    int         favorWholeWords;
    int         countOnlyFirstWordFound;
    int         splitForNonWordChar;
    int         alwaysSplitIfWholeWordExists;
    int         countSubWordsIfSplit;
    int         nonWordSplitCount;

    // Tracing
    int         tracingEnabled;   // non-zero = tracing enabled

    // PCP
    const char* pcpContextJson;   // nullable, serialized PcpContext
} TPipe_PipeConfig;
```

**Cross-reference:** `com.TTT.Structs.PipeSettings` (47 fields, PipeSettings.kt), `com.TTT.Pipe.TokenBudgetSettings` (Pipe.kt:138).

**Allocation:** TPipe-allocated on `TPipe_Pipe_getConfig()` call. Caller receives a handle — use `TPipe_Handle_release()` to free, not `TPipe_Result_free()`.

**Status:** Complete — all 47 fields from PipeSettings.kt are enumerated. Fields not in PipeSettings.kt (provider/model string format, stopSequences JSON) are documented as nullable char* with format notes.

---

## 4. Library State Machine

### 4.1 TPipe_LibraryState Enum

```c
typedef enum {
    TPIPE_STATE_LOADED,         // Library loaded, not initialized
    TPIPE_STATE_INITIALIZING,    // TPipe_init() in progress (thread-safe)
    TPIPE_STATE_READY,           // Fully initialized and usable
    TPIPE_STATE_SHUTTING_DOWN,   // TPipe_shutdown() in progress
    TPIPE_STATE_SHUTDOWN         // Fully shut down (can re-init via TPipe_init())
} TPipe_LibraryState;
```

### 4.2 State Transition Rules

```
TPIPE_STATE_LOADED
    ↓ TPipe_init() called
TPIPE_STATE_INITIALIZING
    ↓ init completes (success)
TPIPE_STATE_READY
    ↓ TPipe_shutdown() called
TPIPE_STATE_SHUTTING_DOWN
    ↓ cleanup completes
TPIPE_STATE_SHUTDOWN
    ↓ TPipe_init() called again
TPIPE_STATE_INITIALIZING (loop)
```

### 4.3 Thread Safety Rules

- `TPipe_init()` is idempotent: multiple concurrent calls serialize via internal mutex
- `TPipe_shutdown()` is idempotent: same behavior
- `TPipe_getState()` is always thread-safe (no state mutation)
- Invalid state transitions return `TPIPE_ERR_STATE_TRANSITION`

---

## 5. TPipe_init() and TPipe_shutdown()

### 5.1 TPipe_init()

```c
// Initialize library. Idempotent — concurrent calls serialize, second call is no-op.
// Must be called before most other operations (except version/cap check).

TPipe_Result TPipe_init(void);
```

**Behavior:**
- Returns `TPIPE_OK` on success (including second call = no-op)
- Returns `TPIPE_ERR_OUT_OF_MEMORY` on failure (rare, unrecoverable)
- Thread-safe: concurrent calls are serialized via internal mutex
- On failure, library state is `TPIPE_STATE_SHUTDOWN` — caller should not attempt further calls

### 5.2 TPipe_shutdown()

```c
// Gracefully shut down library. Idempotent — second call returns TPIPE_OK.

TPipe_Result TPipe_shutdown(void);
```

**Behavior:**
- Cancels in-flight operations
- Releases all outstanding handles (equivalent to calling `TPipe_Handle_release` on each)
- Thread pool shutdown, memory cleanup
- After shutdown, all handles become invalid
- Can be called repeatedly (idempotent)

### 5.3 TPipe_getState()

```c
// Query current library state. Always thread-safe.

TPipe_LibraryState TPipe_getState(void);
```

**Returns:** Current state from `TPipe_LibraryState` enum.

---

## 6. Handle Lifecycle System

### 6.1 Base Handle Type

```c
#define TPIPE_INVALID_HANDLE 0

typedef uint64_t TPipe_Handle;
```

All handles are `uint64_t` integers. `TPIPE_INVALID_HANDLE` (0) represents null/invalid.

### 6.2 Handle Lifecycle Functions

```c
// Increment reference count. Called automatically by clone/copy operations.
// Returns TPIPE_OK on success.

TPipe_Result TPipe_Handle_addRef(TPipe_Handle handle);


// Decrement reference count. When count reaches 0, the handle is invalidated
// and the underlying object is GC'd when TPipe's GC next runs.
// Returns TPIPE_OK on success, TPIPE_ERR_INVALID_HANDLE if already invalid.

TPipe_Result TPipe_Handle_release(TPipe_Handle handle);


// Get current reference count. Returns -1 if handle is invalid.
// Primarily for diagnostics and debugging.

int32_t TPipe_Handle_getRefCount(TPipe_Handle handle);


// Check if handle is valid (not TPIPE_INVALID_HANDLE and not yet freed).
// Returns 1 if valid, 0 if invalid.

int TPipe_Handle_isValid(TPipe_Handle handle);
```

### 6.3 Lifecycle Rules

| Event | Refcount Action |
|-------|-----------------|
| Handle created via create function | Starts at 1, caller receives at 1 |
| Handle cloned (if supported) | +1, caller receives copy at new count |
| `TPipe_Handle_addRef()` called | +1 |
| `TPipe_Handle_release()` called | -1 |
| Count reaches 0 | Handle invalidated, object queued for GC |
| `TPipe_shutdown()` called | All outstanding handles released (count → 0) |

### 6.4 Handle Table Implementation

Internal structure (not exposed in ABI):

```kotlin
object HandleTable {
    private val handles = ConcurrentHashMap<u64, RefCountedObject>()

    data class RefCountedObject(
        val data: Any,           // The underlying Kotlin object
        val refCount: AtomicInteger,
        val type: HandleType     // For type validation
    )

    fun allocate(data: Any): u64
    fun get(id: u64): RefCountedObject?
    fun addRef(id: u64): Int
    fun release(id: u64): Boolean
}
```

**Implementation notes:**
- Uses `ConcurrentHashMap<u64, RefCountedObject>` for thread-safe access
- AtomicInteger for lock-free refcount updates
- Handle IDs are monotonically increasing u64 values
- GraalVM's built-in GC handles object reclamation when refcount reaches 0

---

## 7. At-Exit Hooks

```c
// Internally registered via atexit() or equivalent on each platform.
// Ensures resources are freed and state is cleaned up on process exit.
```

**Behavior:**
- On process exit (without explicit `TPipe_shutdown()`), at-exit hook fires
- Hook cancels all pending operations
- Hook releases thread pool and memory
- Prevents resource leaks on embedded systems with uncontrolled termination

---

## 8. FunctionRegistry Lifecycle

### 8.1 Registration Contract

`FunctionRegistry.registerFunction(name, function)` has the following guarantees:

1. **Immediate visibility:** Registered functions are immediately visible to dispatch — no finalize step required
2. **Additive only:** Registration is additive; functions cannot be unregistered
3. **Init-time registration:** Functions registered during `TPipe_init()` bootstrap are guaranteed available before any pipe executes
4. **Post-init registration:** Wrappers (Python, Node) may call `registerFunction` after `TPipe_init()` completes — this is valid and supported

### 8.2 Handle Longevity

Functions registered during library init are safe to call even after `TPipe_shutdown()` only if the wrapper retains the Kotlin function reference. Once the Kotlin object is garbage collected, the function handle becomes invalid. Wrapper authors should not assume functions survive `TPipe_shutdown()` unless they explicitly maintain the reference.

### 8.3 Reflection Calls Before TPipe_init() — Forbidden

> **No reflection calls are valid before the library reaches `TPIPE_STATE_READY`.**

The following are NOT safe to call before `TPipe_init()`:
- `Class.forName(...)` for any TPipe class
- `ServiceLoader.load(...)` for TPipe service interfaces
- `getDeclaredField()` on any TPipe internal
- `Constructor.newInstance()` for TPipe objects
- Any `KFunction`/`KCallable` access on TPipe types

**Rationale:** During `TPIPE_STATE_INITIALIZING`, the bootstrap is initializing `TpipeRuntime` — class loading, ServiceLoader enumeration, and template reconstruction are all in progress. Reflection calls during this window race against incomplete initialization and may produce `NoClassDefFoundError` or `NoSuchMethodError` in native images.

Wrapper authors must call `TPipe_init()` and await `TPIPE_OK` before any reflection-based operations.

---

## 9. Implementation Checklist

| Function | Status | Notes |
|----------|--------|-------|
| `TPipe_init()` | ☐ TODO | Native image layer; no Kotlin impl |
| `TPipe_shutdown()` | ☐ TODO | Native image layer; no Kotlin impl |
| `TPipe_getState()` | ☐ TODO | Native image layer; no Kotlin impl |
| `TPipe_Handle_addRef()` | ☐ TODO | Native image layer; no Kotlin impl |
| `TPipe_Handle_release()` | ☐ TODO | Native image layer; no Kotlin impl |
| `TPipe_Handle_getRefCount()` | ☐ TODO | Native image layer; no Kotlin impl |
| `TPipe_Handle_isValid()` | ☐ TODO | Native image layer; no Kotlin impl |
| Handle table implementation | ☐ TODO | Kotlin impl exists (HandleTable.kt); ABI wrapper needed |
| **FunctionRegistry lifecycle (§7)** | ☐ TODO | Add to spec — reflects hyperplan finding |
| **Reflection init ordering (§7.3)** | ☐ TODO | Document before-first-call contract |

---

*Next spec: graalvm-abi-capability-version.md (Group 2)*