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

---

## 3. Library State Machine

### 3.1 TPipe_LibraryState Enum

```c
typedef enum {
    TPIPE_STATE_LOADED,         // Library loaded, not initialized
    TPIPE_STATE_INITIALIZING,    // TPipe_init() in progress (thread-safe)
    TPIPE_STATE_READY,           // Fully initialized and usable
    TPIPE_STATE_SHUTTING_DOWN,   // TPipe_shutdown() in progress
    TPIPE_STATE_SHUTDOWN         // Fully shut down (can re-init via TPipe_init())
} TPipe_LibraryState;
```

### 3.2 State Transition Rules

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

### 3.3 Thread Safety Rules

- `TPipe_init()` is idempotent: multiple concurrent calls serialize via internal mutex
- `TPipe_shutdown()` is idempotent: same behavior
- `TPipe_getState()` is always thread-safe (no state mutation)
- Invalid state transitions return `TPIPE_ERR_STATE_TRANSITION`

---

## 4. TPipe_init() and TPipe_shutdown()

### 4.1 TPipe_init()

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

### 4.2 TPipe_shutdown()

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

### 4.3 TPipe_getState()

```c
// Query current library state. Always thread-safe.

TPipe_LibraryState TPipe_getState(void);
```

**Returns:** Current state from `TPipe_LibraryState` enum.

---

## 5. Handle Lifecycle System

### 5.1 Base Handle Type

```c
#define TPIPE_INVALID_HANDLE 0

typedef uint64_t TPipe_Handle;
```

All handles are `uint64_t` integers. `TPIPE_INVALID_HANDLE` (0) represents null/invalid.

### 5.2 Handle Lifecycle Functions

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

### 5.3 Lifecycle Rules

| Event | Refcount Action |
|-------|-----------------|
| Handle created via create function | Starts at 1, caller receives at 1 |
| Handle cloned (if supported) | +1, caller receives copy at new count |
| `TPipe_Handle_addRef()` called | +1 |
| `TPipe_Handle_release()` called | -1 |
| Count reaches 0 | Handle invalidated, object queued for GC |
| `TPipe_shutdown()` called | All outstanding handles released (count → 0) |

### 5.4 Handle Table Implementation

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

## 6. At-Exit Hooks

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

## 7. FunctionRegistry Lifecycle

### 7.1 Registration Contract

`FunctionRegistry.registerFunction(name, function)` has the following guarantees:

1. **Immediate visibility:** Registered functions are immediately visible to dispatch — no finalize step required
2. **Additive only:** Registration is additive; functions cannot be unregistered
3. **Init-time registration:** Functions registered during `TPipe_init()` bootstrap are guaranteed available before any pipe executes
4. **Post-init registration:** Wrappers (Python, Node) may call `registerFunction` after `TPipe_init()` completes — this is valid and supported

### 7.2 Handle Longevity

Functions registered during library init are safe to call even after `TPipe_shutdown()` only if the wrapper retains the Kotlin function reference. Once the Kotlin object is garbage collected, the function handle becomes invalid. Wrapper authors should not assume functions survive `TPipe_shutdown()` unless they explicitly maintain the reference.

### 7.3 Reflection Calls Before TPipe_init() — Forbidden

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

## 8. Implementation Checklist

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