# GraalVM Native ABI Specification

**Version:** 0.1.0-draft
**Created:** 2026-05-05
**Status:** Working Draft - In Progress

---

## 1. Overview

### 1.1 Purpose

This document defines the complete ABI (Application Binary Interface) for compiling TPipe to a native shared library (`.so`/`.dll`/`.dylib`) via GraalVM Native Image. The native library provides a C ABI that allows TPipe to be embedded, linked, and called from any language capable of C interop — serving as the foundation for language-specific wrappers (Kotlin, Python, C, Rust, etc.).

The goal is **100% feature parity** between the JVM-based TPipe and the native ABI version, ensuring that any feature available to JVM users is equally available through the native library.

### 1.2 Target Environments

| Environment | Requirement | Notes |
|-------------|-------------|-------|
| Edge Devices | ARM32/ARM64, low RAM | Full TPipe, no trimming |
| IoT Systems | Embedded Linux, bare metal | Full TPipe, no trimming |
| Industrial | x64/ARM64, real-time constraints | Full TPipe, no trimming |
| Mobile (future) | iOS/Android NDK | Via language wrappers |
| Desktop (future) | Windows/macOS/Linux | Via language wrappers |

**No JVM available in any target environment.** Everything must be pre-compiled via GraalVM Native Image with AOT compilation.

### 1.3 Key Design Principles

1. **Single Library Identity** — One `.so`/`.dll`/`.dylib` containing all TPipe functionality. Splitting is not pursued unless a compelling embedded scenario demands it.

2. **Library-Owned Global State** — The library manages its own internal singleton state. Callers do not provide context pointers; the library is self-contained.

3. **C ABI Compatibility** — All entry points are C-compatible function pointers (`extern "C"`), ensuring maximum language interoperability.

4. **Minimal Divergence from TPipe Conventions** — Where C allows expression of TPipe semantics (builder patterns, method chaining), the ABI preserves them via a thin Kotlin wrapper layer. Where C lacks such constructs, idiomatic C is used but the wrapper hides this from Kotlin callers.

5. **Synchronous by Default, Async by Design** — Calls block until completion (synchronous). However, async patterns are supported to match TPipe's actual behavior, via handle-based async APIs where needed.

6. **Feature Discovery via Capabilities** — The library exposes `TPipe_getCapabilities()` to report which features and API versions are supported, enabling wrappers to adapt.

7. **GraalVM-Managed Memory** — GraalVM Native Image includes garbage collection (GC). The library internally uses GC rather than manual memory management. External callers interact via typed handles and ownership-transfer semantics, not raw pointers.

### 1.4 GraalVM GC Support

GraalVM Native Image includes statically compiled garbage collectors:

| GC | Support | Notes |
|----|---------|-------|
| Serial | ✅ Default | Low footprint, small heaps, 80% RAM default |
| G1 | ⚠️ Enterprise only | Low latency, Linux AMD64 only, 25% RAM default |
| Epsilon | ✅ | No-op GC, for short-running apps |
| ZGC | ❌ Not supported | Confirmed limitation (oracle/graal#10321) |

The GC is **baked into the binary at build time** via `--gc=<type>`. Heap sizing can be tuned at both build time and runtime.

### 1.5 Library Ownership Model

```
┌─────────────────────────────────────────────────────────┐
│                    TPipe Native Library                  │
│  ┌─────────────────────────────────────────────────┐    │
│  │              Library-Owned State                 │    │
│  │  - Singleton instance                           │    │
│  │  - Internal GC (GraalVM managed)                 │    │
│  │  - Thread pool (library-managed)                │    │
│  │  - Memory allocator (library-internal)          │    │
│  │  - All TPipe subsystems (Pipe, Pipeline, etc.)  │    │
│  └─────────────────────────────────────────────────┘    │
│                                                         │
│  C ABI Interface (function pointers)                    │
└─────────────────────────────────────────────────────────┘
```

Callers interact only through the public C ABI. Internal state is encapsulated. The library owns and manages:
- Internal memory allocation (via GC)
- Thread pool and execution scheduling
- All TPipe subsystem lifecycle
- State singletons (ContextBank, P2P registry, etc.)

### 1.6 What's In Scope

- All **public-facing TPipe APIs** — everything a library consumer would use
- **100% feature parity** with JVM-based TPipe
- **C ABI** entry points for all public operations
- **Feature discovery** via capabilities reporting
- **Async operation support** matching TPipe's internal async model
- **Graceful initialization** — library initializes lazily or explicitly, not at boot
- **Complete resource lifecycle** — init through shutdown

### 1.7 What's Out of Scope

- JVM-specific internals not exposed to users
- Debug UI / tracing dashboard (embedded devices unlikely to need this)
- Just-in-time compilation (all AOT, no JIT)
- Multi-instance per process (single tenant on device = single instance; multi-tenant managed at OS level via containers/processes)

---

## 2. ABI Versioning

### 2.1 Version Detection

```c
typedef struct {
    int major;
    int minor;
    int patch;
    const char* version_string;  // e.g., "0.1.0-draft"
} TPipe_Version;

// Returns library version. Does not require init.
const TPipe_Version* TPipe_getVersion(void);
```

### 2.2 Capability Reporting

```c
typedef struct {
    const char* feature_name;      // e.g., "pipe", "pipeline", "pcp", "p2p"
    int supported;                   // 1 = supported, 0 = not compiled in
    int api_version;                // feature-specific API version
    const char* version_string;     // feature version
} TPipe_Capability;

// Returns array of capabilities. Caller provides array and count.
// Returns actual count (may exceed capacity; caller must resize and retry).
int TPipe_getCapabilities(TPipe_Capability* capabilities, int capacity);
```

**Example:** A minimal embedded device may compile without P2P, so `pcp` shows `supported=1` but `p2p` shows `supported=0`.

### 2.3 Version Compatibility Rules

- **Same major version** = ABI compatible (additive changes allowed)
- **Major version mismatch** = ABI incompatible (wrappers must rebuild)
- Wrappers **must** call `TPipe_getVersion()` before any other operation and validate compatibility

---

## 3. Function Naming Convention

All public ABI functions follow the pattern:

```
TPipe_{Subsystem}_{Operation}
```

| Prefix | Subsystem |
|--------|-----------|
| `TPipe_` | Core / top-level |
| `TPipe_Pipe_` | Pipe execution |
| `TPipe_Pipeline_` | Pipeline orchestration |
| `TPipe_Context_` | Context management |
| `TPipe_PCP_` | Pipe Context Protocol |
| `TPipe_P2P_` | P2P communication |
| `TPipe_Config_` | Configuration |
| `TPipe_Err_` | Error handling |

**Example functions:**
- `TPipe_init()` — library initialization
- `TPipe_Pipe_execute()` — execute a pipe call
- `TPipe_Pipeline_create()` — create a pipeline
- `TPipe_Context_get()` — retrieve context value

---

## 4. Initialization and Lifecycle

### 4.1 Initialization Sequence

TPipe does **not** initialize all state at library boot. Instead, it follows the same lazy initialization pattern as the JVM version — APIs, SDKs, and init systems are invoked shortly before running LLM calls, not at library bootstrap.

```
1. TPipe_init()          → Initialize library-level singletons
2. TPipe_Config_*()      → Configure provider, auth, model, etc.
3. TPipe_Pipe_*()        → Execute operations
4. TPipe_shutdown()      → Graceful cleanup (optional, for clean exit)
```

### 4.2 Lazy Initialization

- `TPipe_init()` sets up minimal global state (singleton refs, thread pool)
- Actual subsystems (Pipe, Pipeline, Context) initialize on first use
- This matches TPipe's JVM behavior where heavy initialization is deferred

### 4.3 Graceful Shutdown

```c
// Gracefully shuts down library, releases all resources.
// May block until async operations complete.
TPipe_Result TPipe_shutdown(void);
```

---

## 5. Handles and Opaque Types

All TPipe objects are exposed to external callers as **opaque handles** (integer IDs or pointers to internal structures). Callers cannot directly inspect or mutate TPipe objects — they interact exclusively through the ABI functions.

```c
// Opaque handle types — callers treat as black boxes
typedef uint64_t TPipe_PipeHandle;
typedef uint64_t TPipe_PipelineHandle;
typedef uint64_t TPipe_ContextHandle;
typedef uint64_t TPipe_PCPHandle;
typedef uint64_t TPipe_P2PHandle;

// Constants for invalid handles
#define TPIPE_INVALID_HANDLE 0
```

**Rationale:** This encapsulates internal structures, avoids ABI stability issues when TPipe internal objects change, and allows the library to manage memory via GC without external callers holding raw pointers.

---

## 6. Async Operations

TPipe's internal async model must be exposed through the ABI.

### 6.1 Async Pattern

1. Caller invokes an async operation → receives an operation handle
2. Caller checks status or provides callback
3. When complete, result is retrieved or callback is invoked

```c
// Start an async pipe execution
// Returns immediately with an operation handle
TPipe_AsyncHandle TPipe_Pipe_executeAsync(TPipe_PipeHandle pipe,
                                          const TPipe_Content* input,
                                          TPipe_AsyncCallback callback,
                                          void* user_data);

// Check if async operation is complete
int TPipe_Async_isDone(TPipe_AsyncHandle handle);

// Block until complete (with timeout)
TPipe_Result TPipe_Async_wait(TPipe_AsyncHandle handle, int timeout_ms);

// Retrieve result after completion
TPipe_Content* TPipe_Async_getResult(TPipe_AsyncHandle handle, TPipe_Result* out_result);

// Cancel an in-flight async operation
TPipe_Result TPipe_Async_cancel(TPipe_AsyncHandle handle);
```

### 6.2 Async Handle Lifecycle

- Caller receives handle from async call
- Caller must call `TPipe_Async_close(handle)` to release handle when done
- Handles are **not** GC'd externally — library manages async handle lifecycle

---

## 7. Error Handling

All ABI functions return `TPipe_Result` (an integer error code). `TPIPE_OK` (0) indicates success; non-zero indicates an error.

```c
typedef enum {
    TPIPE_OK = 0,
    TPIPE_ERR_INVALID_HANDLE = 1,
    TPIPE_ERR_NOT_INITIALIZED = 2,
    TPIPE_ERR_ALREADY_INITIALIZED = 3,
    TPIPE_ERR_INVALID_ARGUMENT = 4,
    TPIPE_ERR_RESOURCE_EXHAUSTED = 5,
    TPIPE_ERR_TIMEOUT = 6,
    TPIPE_ERR_NOT_FOUND = 7,
    TPIPE_ERR_OPERATION_IN_PROGRESS = 8,
    TPIPE_ERR_OPERATION_CANCELLED = 9,
    TPIPE_ERR_INTERNAL = 100,
    // ... extensible
} TPipe_Result;
```

Error details can be retrieved:

```c
const char* TPipe_Err_getLastError(void);
const char* TPipe_Err_getErrorString(TPipe_Result code);
```

---

## 8. Feature Coverage Map

| TPipe Feature | ABI Support | Notes |
|--------------|-------------|-------|
| Pipe | ✅ Full | All Pipe operations via `TPipe_Pipe_*` |
| Pipeline | ✅ Full | Pipeline orchestration via `TPipe_Pipeline_*` |
| Context (Window/Bank/MiniBank) | ✅ Full | Via `TPipe_Context_*` |
| PCP (tools) | ✅ Full | Via `TPipe_PCP_*` |
| P2P | ✅ Full | Via `TPipe_P2P_*` |
| Configuration | ✅ Full | Via `TPipe_Config_*` |
| Error Handling | ✅ Full | Via `TPipe_Err_*` |
| Async Operations | ✅ Full | Via `TPipe_Async_*` |
| Memory Management | ✅ Internal | Library-owned GC (Serial/G1/Epsilon) |
| Threading | ✅ Internal | Library-managed thread pool |

---

## 9. C Header Layout

The ABI is defined in a single header file for simplicity:

```c
// tpipe-abi.h - Public C ABI for TPipe Native Library

#ifndef TPIPE_ABI_H
#define TPIPE_ABI_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Version
// Capabilities
// Handles (opaque types)
// Core init/shutdown
// Pipe API
// Pipeline API
// Context API
// PCP API
// P2P API
// Config API
// Error API
// Async API

#ifdef __cplusplus
}
#endif

#endif // TPIPE_ABI_H
```

---

## 10. Next Steps

- [x] ✅ graalvm-abi-overview.md (this document)
- [x] ✅ graalvm-abi-initialization.md — detailed init/shutdown contract
- [x] ✅ graalvm-abi-core-types.md — type system and data types
- [ ] graalvm-abi-pipe-api.md — Pipe execution API
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