# GraalVM Native ABI — Host Requirements Specification

**Spec File:** graalvm-abi-host-requirements.md
**Version:** 0.1.0-draft
**Created:** 2026-05-09
**Status:** Draft

---

## 1. Overview

This document specifies everything a **C host runtime** (Python ctypes, Node.js FFI, C/C++ programs) must know to integrate with a TPipe GraalVM native image. It covers isolate lifecycle, symbol linkage, error handling, and the boundary between host responsibilities and TPipe responsibilities.

**Key principle:** TPipe's `@CEntryPoint` functions require a valid GraalVM isolate. The host must create and manage this isolate — TPipe does not create it for you.

---

## 2. Boundary of Responsibility

```
┌─────────────────────────────────────────────────────────────┐
│                      C HOST RUNTIME                         │
│  (Python ctypes / Node FFI / C++ program)                   │
│                                                              │
│  Responsibilities:                                           │
│  - Load the native image shared library                     │
│  - Create and manage GraalVM isolate                        │
│  - Call TPipe entry points with valid isolate threads        │
│  - Tear down isolate on shutdown                            │
└─────────────────────────────────────────────────────────────┘
                          │
                          │ C function calls (TPipe_init, etc.)
                          ▼
┌─────────────────────────────────────────────────────────────┐
│              TPipe Native Image (libtpipe.so)                │
│                                                              │
│  Responsibilities:                                          │
│  - TPipe_init(thread, ...)  — initialize TPipe runtime      │
│  - TPipe_shutdown(thread)  — clean up TPipe runtime          │
│  - All other TPipe_* functions                               │
└─────────────────────────────────────────────────────────────┘
                          │
                          │ GraalVM C API (libgraalisolate)
                          ▼
┌─────────────────────────────────────────────────────────────┐
│              GraalVM Runtime (libgraalisolate)              │
│                                                              │
│  Provides:                                                   │
│  - graal_create_isolate()                                   │
│  - graal_tear_down_isolate()                                │
│  - graal_isolatethread_t* management                         │
└─────────────────────────────────────────────────────────────┘
```

**The host does NOT call TPipe functions without first creating an isolate.**

---

## 3. GraalVM Isolate Lifecycle

### 3.1 What Is an Isolate

A GraalVM **Isolate** is an isolated native heap with its own GC, separate from the host process's heap. Each isolate has one or more **IsolateThreads** — the execution context for `@CEntryPoint` calls.

All TPipe `@CEntryPoint` functions receive `graal_isolatethread_t*` as their implicit first argument. The host must provide a valid, alive isolate thread on every call.

### 3.2 Isolate States

```
                   ┌──────────────────────┐
                   │                      │
                   ▼                      │
         graal_create_isolate()           │
                   │                      │
                   ▼                      │
        ┌─────────────────────────┐       │
        │  ISOLATE CREATED        │       │
        │  (alive, usable)        │       │
        └──────────┬──────────────┘       │
                   │                      │
                   │ graal_tear_down_isolate()
                   │                      │
                   ▼                      │
         ┌─────────────────────────┐       │
         │  ISOLATE TEAR DOWN      │ ─────┘
         │  (clean shutdown)       │
         └─────────────────────────┘
```

### 3.3 graal_create_isolate()

```c
#include <graal_isolate.h>

// Create a new GraalVM isolate.
// Must be called before any TPipe_* function.
//
// Parameters:
//   archive_info  — NULL for default; can point to a bundle path
//   isolate        — output: receives the created isolate handle
//   thread         — output: receives the primary isolate thread
//
// Returns: 0 on success, non-zero on failure
//
// After successful call, thread is valid and can be passed to
// any TPipe_* entry point.

int graal_create_isolate(void* archive_info,
                         graal_isolate_t** isolate,
                         graal_isolatethread_t** thread);
```

### 3.4 graal_tear_down_isolate()

```c
// Tear down the isolate and release all associated resources.
// Must be called during host shutdown (before dlclose of libtpipe).
//
// All TPipe_* handles are invalidated after this call.
// The isolate thread is no longer usable.

int graal_tear_down_isolate(graal_isolatethread_t* thread);
```

### 3.5 Typical Host Startup Sequence

```c
#include <stdio.h>
#include <dlfcn.h>
#include <graal_isolate.h>

// Function pointer types for GraalVM C API
typedef int (*create_isolate_fn)(void*, graal_isolate_t**, graal_isolatethread_t**);
typedef int (*tear_down_fn)(graal_isolatethread_t*);

// TPipe function pointer type
typedef int (*tpipe_init_fn)(graal_isolatethread_t*);

int main(int argc, char** argv) {
    // 1. Load the TPipe native image library
    void* tpipe_lib = dlopen("./libtpipe.so", RTLD_NOW);
    if (!tpipe_lib) {
        fprintf(stderr, "Failed to load libtpipe.so: %s\n", dlerror());
        return 1;
    }

    // 2. Load GraalVM isolate API (may be statically linked or separate .so)
    //    Note: The GraalVM isolate symbols may be in libtpipe.so itself
    //    (when libgraal is statically linked into the native image) OR
    //    in a separate libgraalisolate.so — check your native image build.
    create_isolate_fn create_isolate = dlsym(RTLD_DEFAULT, "graal_create_isolate");
    tear_down_fn tear_down_isolate   = dlsym(RTLD_DEFAULT, "graal_tear_down_isolate");
    tpipe_init_fn tpipe_init         = dlsym(tpipe_lib, "TPipe_init");

    if (!create_isolate || !tear_down_isolate || !tpipe_init) {
        fprintf(stderr, "Missing required symbols\n");
        return 1;
    }

    // 3. Create isolate (MUST be done before any TPipe call)
    graal_isolate_t* isolate = NULL;
    graal_isolatethread_t* thread = NULL;

    int result = create_isolate(NULL, &isolate, &thread);
    if (result != 0) {
        fprintf(stderr, "graal_create_isolate failed with code %d\n", result);
        return 1;
    }

    // 4. Initialize TPipe (now safe — we have a valid thread)
    result = tpipe_init(thread);
    if (result != TPIPE_OK) {
        fprintf(stderr, "TPipe_init failed: %d\n", result);
        tear_down_isolate(thread);
        return 1;
    }

    // 5. Use TPipe ... (execute pipes, pipelines, etc.)

    // 6. On shutdown: tear down isolate
    tear_down_isolate(thread);
    dlclose(tpipe_lib);
    return 0;
}
```

---

## 4. Symbol Linkage

### 4.1 Symbol Sources

There are **two categories** of symbols available when loading a TPipe native image:

| Symbol Category | Provided By | Purpose |
|----------------|-------------|---------|
| GraalVM isolate management | `graal_create_isolate`, `graal_tear_down_isolate` | GraalVM runtime (may be statically linked into libtpipe.so or separate) |
| TPipe entry points | `TPipe_init`, `TPipe_shutdown`, `TPipe_Pipe_*`, etc. | TPipe library API |

### 4.2 Static vs Dynamic Linking of libgraal

The GraalVM isolate management symbols may be:
- **Statically linked** into `libtpipe.so` — `graal_create_isolate` is directly in the TPipe library
- **Dynamically linked** from a separate `libgraalisolate.so` — must be loaded alongside `libtpipe.so`

Check your native image build output. If symbols are not found with `dlsym(RTLD_DEFAULT, ...)`, they are inside `libtpipe.so` — use `dlsym(tpipe_lib, "graal_create_isolate")`.

### 4.3 Symbol Discovery Pattern

```c
// Robust symbol resolution across build configurations

void* tpipe_lib = dlopen("./libtpipe.so", RTLD_NOW);

// Try libtpipe.so first (static linking case)
create_isolate_fn create_isolate = dlsym(tpipe_lib, "graal_create_isolate");
tear_down_fn tear_down           = dlsym(tpipe_lib, "graal_tear_down_isolate");

if (!create_isolate) {
    // Fall back to global scope (dynamic linking case)
    create_isolate = dlsym(RTLD_DEFAULT, "graal_create_isolate");
    tear_down      = dlsym(RTLD_DEFAULT, "graal_tear_down_isolate");
}

if (!create_isolate) {
    // Signal error — GraalVM symbols not found
}
```

### 4.4 Verifying Symbol Availability

After building the native image, verify symbols are exported:

```bash
# List all exported symbols
nm -D build/native/nativeImage/libtpipe.so | grep -E "^(TPipe|graal)"

# Expected TPipe symbols:
#   TPipe_init
#   TPipe_shutdown
#   TPipe_getState
#   TPipe_Handle_addRef
#   TPipe_Handle_release
#   TPipe_Pipe_create
#   ... (all public TPipe_* entry points)

# Expected GraalVM symbols (if not statically linked):
#   graal_create_isolate
#   graal_tear_down_isolate
```

---

## 5. Single-Isolate vs Multi-Isolate Patterns

### 5.1 Single Isolate (Most Common)

For most embedding scenarios (Python, Node.js single-process), use **one isolate** with one primary thread:

```c
// One isolate, one thread, shared across all TPipe calls
graal_isolatethread_t* primary_thread = NULL;
graal_create_isolate(NULL, &isolate, &primary_thread);

// All TPipe calls use primary_thread
TPipe_PipeHandle pipe = TPipe_Pipe_create(primary_thread);
// ...
TPipe_shutdown(primary_thread);
tear_down_isolate(primary_thread);
```

**This is the recommended default.** No performance benefit to multiple isolates in a single-process embedding.

### 5.2 Multi-Isolate (Advanced)

For scenarios requiring strict isolation between independent TPipe instances (e.g., multi-tenant servers where tenants must not share any runtime state):

```c
// Each tenant gets its own isolate + thread
graal_isolate_t* tenant_a_isolate;
graal_isolatethread_t* tenant_a_thread;
graal_create_isolate(NULL, &tenant_a_isolate, &tenant_a_thread);

graal_isolate_t* tenant_b_isolate;
graal_isolatethread_t* tenant_b_thread;
graal_create_isolate(NULL, &tenant_b_isolate, &tenant_b_thread);

// Tenant A and B are fully isolated — separate GC heaps
TPipe_PipeHandle pipe_a = TPipe_Pipe_create(tenant_a_thread);
TPipe_PipeHandle pipe_b = TPipe_Pipe_create(tenant_b_thread);

// On shutdown: tear down each isolate independently
tear_down_isolate(tenant_a_thread);
tear_down_isolate(tenant_b_thread);
```

**Cost:** Higher memory footprint per tenant. Only use when strict runtime isolation is required.

### 5.3 Thread Attachment (Multi-Threaded Hosts)

Each additional thread that calls TPipe functions must be **attached** to the isolate:

```c
// Attach worker thread to the isolate
graal_isolatethread_t* worker_thread;
int attach_result = graal_attach_thread(isolate, NULL, &worker_thread);
if (attach_result != 0) { /* handle error */ }

// Now worker_thread can call TPipe functions
TPipe_ContentHandle content = TPipe_Pipeline_execute(
    worker_thread, pipeline, input, &result
);

// On thread exit: detach
graal_detach_thread(worker_thread);
```

Note: `graal_attach_thread` and `graal_detach_thread` are GraalVM C API functions. Verify availability in your GraalVM version.

---

## 6. Error Handling

### 6.1 Isolate Creation Failures

```c
graal_isolate_t* isolate = NULL;
graal_isolatethread_t* thread = NULL;

int result = graal_create_isolate(NULL, &isolate, &thread);
if (result != 0) {
    // Common error codes:
    // 1 = Out of memory
    // 2 = Invalid archive path
    // 3 = Native image not initialized correctly

    fprintf(stderr, "Isolate creation failed: code %d\n", result);
    // Do NOT call TPipe_* functions — isolate is invalid
    return;
}
```

### 6.2 Thread Validity

If a TPipe call returns `TPIPE_ERR_INVALID_HANDLE` or crashes, verify:
1. The isolate thread is still valid (not torn down)
2. The isolate has not been shut down
3. The thread was properly attached

### 6.3 Isolate Teardown Failures

```c
int result = graal_tear_down_isolate(thread);
if (result != 0) {
    // Isolate may be in an inconsistent state
    // Log the error but do not attempt further TPipe calls
    fprintf(stderr, "Isolate teardown warning: code %d\n", result);
}
```

---

## 7. Shutdown Order

Always tear down in this order:

```
1. Call TPipe_shutdown(thread)     // Stop TPipe runtime gracefully
2. Release all TPipe handles       // Call TPipe_Handle_release on every handle
3. Call graal_tear_down_isolate() // Tear down the isolate
4. dlclose(libtpipe.so)           // Unload the library (optional)
```

**Skipping step 1** (calling `TPipe_shutdown`) may leave in-flight operations in an undefined state. The at-exit hook handles the no-explicit-shutdown case, but explicit shutdown is cleaner.

---

## 8. Python ctypes Example

```python
import ctypes
import os

class TPipeHost:
    def __init__(self, libtpipe_path="./libtpipe.so"):
        self.lib = ctypes.CDLL(libtpipe_path)

        # Resolve GraalVM isolate symbols (may be inside libtpipe or separate)
        self._resolve_isolate_symbols()

        # Resolve TPipe entry points
        self.TPipe_init = self.lib.TPipe_init
        self.TPipe_init.argtypes = [ctypes.c_void_p]
        self.TPipe_init.restype = ctypes.c_int

        self.TPipe_shutdown = self.lib.TPipe_shutdown
        self.TPipe_shutdown.argtypes = [ctypes.c_void_p]
        self.TPipe_shutdown.restype = ctypes.c_int

    def _resolve_isolate_symbols(self):
        # Try libtpipe.so first (static linking)
        self.graal_create_isolate = self.lib.graal_create_isolate
        self.graal_tear_down_isolate = self.lib.graal_tear_down_isolate

        if not hasattr(self.lib, 'graal_create_isolate'):
            # Fall back: look in libc (dynamic linking case)
            self.graal_create_isolate = ctypes.CDLL(None).graal_create_isolate
            self.graal_tear_down_isolate = ctypes.CDLL(None).graal_tear_down_isolate

        # Configure argtypes for isolate functions
        self.graal_create_isolate.argtypes = [
            ctypes.c_void_p,                          # archive_info
            ctypes.POINTER(ctypes.c_void_p),         # isolate out
            ctypes.POINTER(ctypes.c_void_p)          # thread out
        ]
        self.graal_create_isolate.restype = ctypes.c_int

        self.graal_tear_down_isolate.argtypes = [ctypes.c_void_p]
        self.graal_tear_down_isolate.restype = ctypes.c_int

    def create_isolate(self):
        isolate = ctypes.c_void_p()
        thread = ctypes.c_void_p()
        result = self.graal_create_isolate(
            None, ctypes.byref(isolate), ctypes.byref(thread)
        )
        if result != 0:
            raise RuntimeError(f"graal_create_isolate failed: {result}")
        return isolate.value, thread.value

    def tear_down_isolate(self, thread):
        self.graal_tear_down_isolate(thread)

    def init(self, thread):
        result = self.TPipe_init(thread)
        if result != 0:
            raise RuntimeError(f"TPipe_init failed: {result}")

    def shutdown(self, thread):
        self.TPipe_shutdown(thread)

# Usage
if __name__ == "__main__":
    host = TPipeHost()
    isolate, thread = host.create_isolate()
    host.init(thread)
    print("TPipe initialized successfully")
    host.shutdown(thread)
    host.tear_down_isolate(thread)
```

---

## 9. Node.js FFI Example

```javascript
const ffi = require('ffi-napi');
const ref = require('ref-napi');

// Types
const voidPtr = ref.refType('void');
const int32 = 'int32_t';

// Load library
const libtpipe = ffi.Library('./libtpipe', {
    'TPipe_init': [int32, [voidPtr]],
    'TPipe_shutdown': [int32, [voidPtr]],
});

// Resolve GraalVM isolate symbols
let graal;
try {
    // Static linking case
    graal = ffi.Library('./libtpipe', {
        'graal_create_isolate': ['int', ['void*', voidPtr, voidPtr]],
        'graal_tear_down_isolate': ['int', [voidPtr]],
    });
} catch (e) {
    // Dynamic linking case — try libgraalisolate.so
    graal = ffi.Library('libgraalisolate', {
        'graal_create_isolate': ['int', ['void*', voidPtr, voidPtr]],
        'graal_tear_down_isolate': ['int', [voidPtr]],
    });
}

// Create isolate
const isolate = ref.alloc(voidPtr);
const thread = ref.alloc(voidPtr);
const result = graal.graal_create_isolate(null, isolate, thread);

if (result !== 0) {
    throw new Error(`graal_create_isolate failed: ${result}`);
}

// Initialize TPipe
const tpipeResult = libtpipe.TPipe_init(thread.deref());
if (tpipeResult !== 0) {
    throw new Error(`TPipe_init failed: ${tpipeResult}`);
}

console.log('TPipe initialized successfully');

// On shutdown
libtpipe.TPipe_shutdown(thread.deref());
graal.graal_tear_down_isolate(thread.deref());
```

---

## 10. Common Mistakes

### Mistake 1: Calling TPipe Without an Isolate

```c
// WRONG — thread is uninitialized garbage
TPipe_init(0xdeadbeef);  // Will crash or return TPIPE_ERR_INVALID_HANDLE

// CORRECT
graal_isolatethread_t* thread;
graal_create_isolate(NULL, &isolate, &thread);
TPipe_init(thread);
```

### Mistake 2: Tearing Down Isolate While Handles Are Active

```c
// WRONG — handles become invalid, potential crash
TPipe_PipeHandle pipe = TPipe_Pipe_create(thread);
tear_down_isolate(thread);  // All handles now invalid
TPipe_Pipe_execute(pipe, ...);  // Undefined behavior

// CORRECT
TPipe_PipeHandle pipe = TPipe_Pipe_create(thread);
// ... use pipe ...
TPipe_Handle_release(pipe);  // Release all handles first
TPipe_shutdown(thread);       // Then shutdown
tear_down_isolate(thread);   // Then tear down
```

### Mistake 3: Closing Library Before Tearing Down Isolate

```c
// WRONG — if isolate symbols are in libtpipe.so
dlclose(tpipe_lib);
tear_down_isolate(thread);  // Symbol no longer available if dynamically linked

// CORRECT
tear_down_isolate(thread);  // Tear down while symbols still available
dlclose(tpipe_lib);          // Then close
```

### Mistake 4: Assuming graal_create_isolate Is in a Separate .so

```c
// WRONG — static linking case, symbol not in libc
void* graal_lib = dlopen("libgraalisolate.so", RTLD_NOW);
create_isolate = dlsym(graal_lib, "graal_create_isolate");  // May be NULL

// CORRECT — check libtpipe.so first
create_isolate = dlsym(tpipe_lib, "graal_create_isolate");
if (!create_isolate) {
    create_isolate = dlsym(RTLD_DEFAULT, "graal_create_isolate");
}
```

---

## 12. Build Artifacts

### 12.1 Artifact Naming Convention

TPipe produces a **shared library** (not an executable, not a static archive). The library exposes C entry points via `@CEntryPoint` and is loaded by hosts via `dlopen` / `LoadLibrary`.

| Platform | Artifact Name | Format |
|----------|--------------|--------|
| Linux x86_64 | `libtpipe.so` | ELF shared object |
| Linux ARM64 (aarch64) | `libtpipe.so` | ELF shared object |
| macOS x86_64 | `libtpipe.dylib` | Mach-O dylib |
| macOS ARM64 (Apple Silicon) | `libtpipe.dylib` | Mach-O dylib |
| Windows x64 | `tpipe.dll` | PE/COFF DLL |

> **Community edition exclusion:** Exotic hardware targets (RISC-V, MIPS, SPARC, PowerPC beyond ppc64le, GPU embedded targets, FPGA, DSP) are enterprise-priced and excluded from the community edition ABI.

### 12.2 Build Output Location

Default location after `nativeImage` task:
```
TPipe/build/native/nativeImage/
├── libtpipe.so         # Linux
├── libtpipe.dylib      # macOS
└── tpipe.dll           # Windows
```

The native image is a **standalone shared library** — no additional JAR or classpath needed at runtime. The host language bindings (Python ctypes, Node FFI, C dlopen) load this file directly.

### 12.3 Host Discovery Pattern

The host is responsible for locating the library. Common patterns by platform:

```python
# Python — common search paths
import os, ctypes

search_paths = [
    "./libtpipe.so",           # relative to cwd
    "/usr/local/lib/libtpipe.so",
    os.path.expanduser("~/.tpipe/lib/libtpipe.so"),
    os.environ.get("TPIPE_LIB", "./libtpipe.so"),  # environment override
]

for path in search_paths:
    if os.path.exists(path):
        lib = ctypes.CDLL(path)
        break
```

```javascript
// Node.js — using ffi-napi
const ffi = require('ffi-napi');
const searchPaths = [
  './libtpipe.so',
  '/usr/local/lib/libtpipe.so',
  process.env.TPIPE_LIB || './libtpipe.so'
];
```

### 12.4 Build Configuration

In `TPipe/build.gradle.kts`, the native image produces a shared library via:

```kotlin
nativeImage {
    mainClass = "TPipe.Native.TPipeBootstrap"
    buildArgs.addAll(listOf(
        "-H:-DeleteNativePoints",   // keep C entry points
        "-H:+ReportExceptionStackTraces"
    ))
}

// Output: build/native/nativeImage/{libtpipe.so,libtpipe.dylib,tpipe.dll}
```

### 12.5 Artifact Verification

```bash
# Linux — verify it is a shared library and has TPipe symbols
file build/native/nativeImage/libtpipe.so
nm -D build/native/nativeImage/libtpipe.so | grep " T TPipe_" | head -5

# macOS
file build/native/nativeImage/libtpipe.dylib
nm -gU build/native/nativeImage/libtpipe.dylib | grep "TPipe_" | head -5

# Windows
dumpbin /EXPORTS build/native/nativeImage/tpipe.dll | findstr "TPipe_"
```

---

## 13. Implementation Checklist

| Item | Status | Notes |
|------|--------|-------|
| `graal_create_isolate()` | ☐ TODO | Host responsibility |
| `graal_tear_down_isolate()` | ☐ TODO | Host responsibility |
| `graal_attach_thread()` (multi-thread) | ☐ TODO | Only if multi-threaded host |
| `graal_detach_thread()` | ☐ TODO | Only if multi-threaded host |
| Symbol resolution logic | ☐ TODO | Must handle both static and dynamic linking |
| Python ctypes example | ✅ Done | Section 8 |
| Node.js FFI example | ✅ Done | Section 9 |
| Shutdown order documented | ✅ Done | Section 7 |
| Build artifacts documented | ✅ Done | Section 12 |

---

*Next spec: graalvm-abi-reflection-config.md (Priority 0 audits)*