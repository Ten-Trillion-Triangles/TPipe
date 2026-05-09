# GraalVM Native ABI Specification — Capability & Version Discovery

**Spec File:** graalvm-abi-capability-version.md  
**Version:** 0.2.0-draft  
**Created:** 2026-05-07  
**Status:** Draft

---

## 1. Overview

These functions allow callers to query library capabilities, version information, and initialization state before performing other operations.

**Key principle:** These functions are callable **before** `TPipe_init()` — they don't require library initialization to function.

---

## 2. TPipe_Capabilities Struct

```c
typedef struct {
    const char* version;           // "1.0.0" - ABI version
    const char* tpipe_version;     // "0.9.x" - TPipe library version
    uint32_t api_version;           // 1 - API version number
    uint64_t features;             // Bitfield of supported features (see below)
    uint32_t max_concurrent_pipes;  // Max concurrent pipes (0 = unlimited)
    uint32_t max_handles;          // Max concurrent handles (0 = unlimited)
    uint32_t gc_type;              // 0=Serial, 1=G1, 2=Epsilon
    const char* build_timestamp;   // ISO 8601 build time
    uint32_t reserved[8];          // Future expansion
} TPipe_Capabilities;
```

### 2.1 Feature Bitfield

```c
// Feature flags (bit positions in capabilities.features)
#define TPIPE_FEATURE_PIPELINES       (1ULL << 0)   // Pipeline API supported
#define TPIPE_FEATURE_DISTGRID       (1ULL << 1)   // DistributionGrid supported
#define TPIPE_FEATURE_MANIFOLD       (1ULL << 2)   // Manifold/workers supported
#define TPIPE_FEATURE_PCP             (1ULL << 3)   // PCP/FunctionRegistry supported
#define TPIPE_FEATURE_MEMORY          (1ULL << 4)   // Memory/context API supported
#define TPIPE_FEATURE_P2P             (1ULL << 5)   // P2P registry supported
#define TPIPE_FEATURE_LIST_HANDLES    (1ULL << 6)   // ListHandle implemented
#define TPIPE_FEATURE_MAP_HANDLES     (1ULL << 7)   // MapHandle implemented
#define TPIPE_FEATURE_STREAMING       (1ULL << 8)   // Streaming responses supported
#define TPIPE_FEATURE_JSON_CONTENT    (1ULL << 9)   // JSON content parsing
#define TPIPE_FEATURE_BINARY_CONTENT  (1ULL << 10)  // BinaryContent supported
```

### 2.2 GC Type Values

```c
#define TPIPE_GC_SERIAL   0  // Serial GC (default, low footprint)
#define TPIPE_GC_G1       1  // G1 GC (enterprise only, Linux AMD64)
#define TPIPE_GC_EPSILON  2  // Epsilon GC (no-op, short-running apps)
```

---

## 3. TPipe_getCapabilities()

```c
// Query library capabilities. Can be called before TPipe_init().

TPipe_Result TPipe_getCapabilities(TPipe_Capabilities* out_caps);
```

**Parameters:**
- `out_caps` — Pointer to caller-owned `TPipe_Capabilities` struct. Must not be NULL.

**Returns:**
- `TPIPE_OK` — Success, `out_caps` filled
- `TPIPE_ERR_INVALID_ARGUMENT` — `out_caps` is NULL

**Behavior:**
- Does not require `TPipe_init()` to be called first
- Returns static data — no mutation of library state
- All string pointers in returned struct point to static storage (do not free)

**Example:**
```c
TPipe_Capabilities caps;
TPipe_Result result = TPipe_getCapabilities(&caps);
if (result == TPIPE_OK) {
    printf("ABI version: %s\n", caps.version);
    printf("TPipe version: %s\n", caps.tpipe_version);
    if (caps.features & TPIPE_FEATURE_DISTGRID) {
        printf("DistributionGrid supported\n");
    }
}
```

---

## 4. TPipe_getVersion()

```c
// Get version string for the native library.
// Writes up to buffer_size bytes to buffer (including null terminator).

TPipe_Result TPipe_getVersion(char* buffer, int buffer_size);
```

**Parameters:**
- `buffer` — Caller-owned buffer to receive version string
- `buffer_size` — Size of buffer in bytes

**Returns:**
- `TPIPE_OK` — Success, buffer contains null-terminated string
- `TPIPE_ERR_INVALID_ARGUMENT` — buffer is NULL or buffer_size <= 0
- `TPIPE_ERR_BUFFER_TOO_SMALL` — buffer too small, result truncated

**Behavior:**
- Does not require `TPipe_init()` to be called first
- Always null-terminates (even if truncated)
- Recommended buffer size: 64 bytes

**Output format:** `"TPipe-Native-{abi_version}-{build_hash}"`

**Example:**
```c
char buffer[64];
if (TPipe_getVersion(buffer, sizeof(buffer)) == TPIPE_OK) {
    printf("Library version: %s\n", buffer);
}
```

---

## 5. TPipe_isInitialized() — DEPRECATED

**Note:** This function is deprecated in favor of `TPipe_getState()`. For backwards compatibility, it continues to exist but new code should use `TPipe_getState()`.

```c
// DEPRECATED: Use TPipe_getState() instead.
//
// Returns 1 if library is in TPIPE_STATE_READY, 0 otherwise.
// During TPIPE_STATE_INITIALIZING (transient), returns 0 because the library
// is not yet fully ready. During TPIPE_STATE_SHUTTING_DOWN, returns 0.

int TPipe_isInitialized(void);
```

**Replacement:**
```c
// Preferred alternative:
TPipe_LibraryState state = TPipe_getState();
int isReady = (state == TPIPE_STATE_READY) ? 1 : 0;
```

---

## 6. Implementation Checklist

| Function | Status | Notes |
|----------|--------|-------|
| `TPipe_getCapabilities()` | ☐ TODO | Native image layer; no Kotlin impl |
| `TPipe_getVersion()` | ☐ TODO | Native image layer; no Kotlin impl |
| `TPipe_isInitialized()` | ☐ TODO (deprecated) | Native image layer; no Kotlin impl |

**Note:** These 3 functions are C entry points generated by the GraalVM native image build process — they do not have Kotlin implementations in `src/main/kotlin`. See `graalvm-abi-bootstrap-plan.md` for the native image entry point generation process.

---

## 7. Design Rationale

### Why capabilities via bitfield?

Allows future expansion without breaking the struct. New features add new bits. Callers can check for optional features without version matching.

### Why static strings?

Capabilities are baked into the native image at compile time. No runtime string allocation needed.

### Why TPipe_getState() instead of bool?

The 5-state model allows callers to handle transient states (`INITIALIZING`, `SHUTTING_DOWN`) gracefully rather than treating everything as binary.

---

*Next spec: graalvm-abi-collection-handles.md (Group 3)*