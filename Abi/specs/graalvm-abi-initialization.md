# GraalVM Native ABI - Initialization Specification

**Version:** 0.1.0-draft
**Created:** 2026-05-05
**Status:** Draft - Pending Thread Safety Research

---

## 1. Initialization Overview

TPipe uses a **stateful, lazy initialization** model. The library manages its own internal global state and defers heavy initialization until first use.

### 1.1 Init Flow

```
┌─────────────────────────────────────────┐
│         TPipe_init()                    │
│  → Validate library version             │
│  → Set up singleton refs                │
│  → Initialize thread pool (deferred)    │
│  → Return TPIPE_OK on success           │
└─────────────────────────────────────────┘
            ↓
┌─────────────────────────────────────────┐
│  TPipe_Config_*() (any order, lazy)      │
│  → Provider configuration                │
│  → Auth setup                           │
│  → Model selection                      │
│  → No subsystem activation yet         │
└─────────────────────────────────────────┘
            ↓
┌─────────────────────────────────────────┐
│  TPipe_Pipe_*() / TPipe_Pipeline_*()    │
│  → First call triggers subsystem init   │
│  → Thread pool starts                   │
│  → Memory systems activate              │
└─────────────────────────────────────────┘
```

---

## 2. TPipe_init()

```c
// Initialize library. Idempotent — calling multiple times is a no-op.
// Must be called before most other operations (except version/cap check).

TPipe_Result TPipe_init(void);
```

### 2.1 Behavior

- **Stateful**: Sets up internal global state (singletons, references)
- **Idempotent**: Second call is a no-op, returns `TPIPE_OK`
- **Lazy thread pool**: Thread pool is NOT started at init — deferred until first use (matches TPipe JVM behavior)
- **Retry on transient failure**: If init fails, caller may retry

### 2.2 What Gets Initialized at init()

| Component | Init Time | Notes |
|-----------|-----------|-------|
| Version validation | ✅ Immediate | |
| Singleton references | ✅ Immediate | ContextBank, P2P registry refs |
| Thread pool | ❌ Deferred | Starts on first pipe/pipeline execution |
| Memory systems | ❌ Deferred | ContextBank/MiniBank activate on first access |
| Provider configs | ❌ Deferred | Configured but not validated until first use |

### 2.3 Init Failures

- If `TPipe_init()` fails irrecoverably, returns error code
- Caller may retry once or twice before giving up
- On failure, library state is undefined — caller should not attempt further calls
- After shutdown and reinit cycle, retry is allowed

---

## 3. TPipe_shutdown()

```c
// Gracefully shut down library.
// Cancels in-flight operations, releases resources.

TPipe_Result TPipe_shutdown(void);
```

### 3.1 Behavior

- **Cancels in-flight async operations**: Any pending pipe/pipeline executions are cancelled
- **Releases resources**: Thread pool shutdown, memory cleanup
- **Exits gracefully**: If caller needs to be graceful, they must manage their own cleanup before calling shutdown
- **Forcible by default**: Shutdown is considered a forceful event — no gentle graceful completion for in-flight work
- **Can be called repeatedly**: Idempotent, returns `TPIPE_OK` on any call after first

### 3.2 Shutdown + Reinit Cycle

After `TPipe_shutdown()`:
- All handles become invalid
- Library state is reset
- `TPipe_init()` can be called again to restart
- This is a full restart cycle, not a resume

---

## 4. At-Exit Hooks

The library registers **at-exit handlers** to ensure clean shutdown even if the process terminates without calling `TPipe_shutdown()`.

```c
// Internally registered via atexit() or equivalent on each platform
// Ensures resources are freed and state is cleaned up on process exit
```

### 4.1 At-Exit Behavior

- On process exit (without explicit `TPipe_shutdown()`), at-exit hook fires
- Hook cancels all pending operations
- Hook releases thread pool and memory
- This prevents resource leaks on embedded systems where process termination may be uncontrolled

### 4.2 Embedding Consideration

On some embedded environments (bare metal, no OS), at-exit hooks may not fire. The library should be robust to unceremonious termination, but at-exit hooks cover the common case.

---

## 5. Memory Ownership Model

The ownership model determines who allocates and who frees memory for data crossing the ABI boundary.

### 5.1 Ownership Rules

| Scenario | Who Allocates | Who Frees | Notes |
|----------|--------------|-----------|-------|
| TPipe internal objects | TPipe (GC) | TPipe (GC) | Handles only, no raw pointers exposed |
| TPipe returns string/buffer to caller | TPipe | **Caller** | Caller must copy if needed |
| Caller passes string/buffer to TPipe | Caller | Caller | TPipe borrows, does not copy |
| TPipe allocates result structure | TPipe | **Caller** | via provided free function |
| PCP tool returns complex result | TPipe | **Caller** | via TPipe_Result_free() |

### 5.2 String Return Convention

```c
// TPipe returns const char* — caller MUST NOT free
// String lifetime: valid until next ABI call on same handle

const char* TPipe_Pipe_getModel(TPipe_PipeHandle handle);

// For cases where caller needs to retain beyond next ABI call,
// a copy must be made:

const char* model = TPipe_Pipe_getModel(handle);
char* copy = strdup(model);  // caller owns copy, must free()
```

### 5.3 Buffer-Based Returns (Large Data)

For large data (content buffers, JSON responses):

```c
// Pattern: caller provides buffer + size, TPipe fills
// If buffer too small, returns TPIPE_ERR_BUFFER_TOO_SMALL with needed size

int TPipe_Pipe_getContent(TPipe_PipeHandle handle,
                          char* buffer,
                          int buffer_size,
                          int* out_actual_size);

// Caller-owned free after copy
```

### 5.4 Caller-Provided Buffers

```c
// Caller provides memory, TPipe writes into it
// TPipe does NOT allocate — caller is always responsible for provided memory

TPipe_Result TPipe_Context_get(TPipe_ContextHandle handle,
                              const char* key,
                              char* buffer,
                              int buffer_size);
```

---

## Thread Safety (Confirmed from Codebase)

TPipe uses **kotlinx.coroutines.sync.Mutex** as the primary synchronization primitive (not ReentrantLock/Semaphore). Key patterns:

| Pattern | Where Used |
|---------|-----------|
| `Mutex + withLock {}` | ContextBank (5 mutexes), ContextLock, P2PRegistry, Splitter, PCP |
| `ConcurrentHashMap` | All thread-safe collections |
| `CoroutineScope(Dispatchers.Default + SupervisorJob())` | Pipe timeout timers, P2P registry refresh, DistributionGrid listing renewal |
| `Dispatchers.IO` | All blocking I/O (MemoryPersistence, StdioExecutor) |
| `@Volatile` | Singleton state flags (bankedContextWindow, cacheConfig, etc.) |
| `coroutineScope + async {}` | Parallel pipe execution in Pipe, Junction, Splitter |

### 6.1 Thread Safety Implications for ABI

- **Init/Shutdown are idempotent and thread-safe**: TPipe's internal mutex patterns ensure concurrent init/shutdown calls don't corrupt state
- **Handles are thread-safe**: TPipe's internal Mutex protection means handles can be used from multiple threads
- **No external synchronization needed**: Caller does NOT need to protect handle access with external locks
- **Coroutines all the way down**: The entire TPipe concurrency model is built on coroutines — the native ABI will call into this seamlessly

---

## 7. Configuration Before Init

All configuration functions can be called before `TPipe_init()` OR after. Everything is lazy — no configuration is validated until first use.

```c
// These can be called before init:
TPipe_Config_setProvider("bedrock");
TPipe_Config_setModel("gpt-5");
TPipe_Config_setAuthToken("...");

// Or after init — order doesn't matter:
TPipe_init();
TPipe_Config_setProvider("ollama");
```

### 7.1 Lazy Configuration

Configuration is stored but not validated until:
- A pipe/pipeline is actually executed, OR
- `TPipe_Config_validate()` is called explicitly

This matches TPipe's behavior where "APIs, SDKs, init systems are all invoked shortly prior to running LLM calls rather than at library bootstrapping."

---

## 8. Version/Capability Checks

These functions are callable **before** `TPipe_init()`:

```c
// No init required — can be called immediately after library load
const TPipe_Version* TPipe_getVersion(void);
int TPipe_getCapabilities(TPipe_Capability* caps, int capacity);
```

### 8.1 Recommended Startup Sequence

```c
// 1. Check version (no init required)
const TPipe_Version* v = TPipe_getVersion();
if (v->major != EXPECTED_MAJOR) { /* handle incompatibility */ }

// 2. Check capabilities (no init required)
TPipe_Capability caps[10];
int count = TPipe_getCapabilities(caps, 10);
// verify needed features are supported

// 3. Initialize
TPipe_Result res = TPipe_init();
if (res != TPIPE_OK) { /* handle error */ }

// 4. Configure
TPipe_Config_setProvider("bedrock");
TPipe_Config_setModel("claude-3-5-sonnet");

// 5. Execute
TPipe_PipeHandle pipe = TPipe_Pipe_create();
TPipe_Pipe_execute(pipe, input, &output);
```

---

## 9. Minimal Working Example

```kotlin
// Kotlin ABI wrapper (equivalent to JVM usage shown earlier)
val pipe = TPipe.Pipe.create().apply {
    setProvider("openai")
    setModel("gpt-5")
    setSystemPrompt("You are a helpful assistant")
    init()
}

val result = pipe.execute("Hello, world!")
println(result.text)

pipe.close()
TPipe.shutdown()  // optional, at-exit hook handles if omitted
```

### 9.1 C Equivalent

```c
TPipe_init();

TPipe_PipeHandle pipe = TPipe_Pipe_create();
TPipe_Config_setProvider(pipe, "openai");
TPipe_Config_setModel(pipe, "gpt-5");
TPipe_Config_setSystemPrompt(pipe, "You are a helpful assistant");

TPipe_Pipe_init(pipe);

TPipe_Content input = { .text = "Hello, world!" };
TPipe_Content output;
TPipe_Result res = TPipe_Pipe_execute(pipe, &input, &output);

if (res == TPIPE_OK) {
    printf("Result: %s\n", output.text);
    TPipe_Content_free(&output);  // caller frees
}

TPipe_Pipe_close(pipe);
TPipe_shutdown();  // optional
```

---

## 10. Error Codes Reference

| Code | Meaning |
|------|---------|
| `TPIPE_OK` | Success |
| `TPIPE_ERR_INVALID_HANDLE` | Handle is invalid or closed |
| `TPIPE_ERR_NOT_INITIALIZED` | Library not initialized |
| `TPIPE_ERR_INVALID_ARGUMENT` | Null/wrong-type argument |
| `TPIPE_ERR_RESOURCE_EXHAUSTED` | Memory/thread pool exhausted |
| `TPIPE_ERR_TIMEOUT` | Operation timed out |
| `TPIPE_ERR_NOT_FOUND` | Config/resource not found |
| `TPIPE_ERR_OPERATION_IN_PROGRESS` | Async operation still running |
| `TPIPE_ERR_OPERATION_CANCELLED` | Operation was cancelled |
| `TPIPE_ERR_BUFFER_TOO_SMALL` | Caller-provided buffer too small |
| `TPIPE_ERR_INTERNAL` | Internal library error |

---

## 11. Next Steps

- [ ] Finalize this spec based on user feedback
- [ ] Move to `graalvm-abi-core-types.md` — type system and data types