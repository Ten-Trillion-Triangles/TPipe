# GraalVM Native ABI Specification — Core Types

**Version:** 0.1.0-draft
**Created:** 2026-05-05
**Status:** Working Draft - In Progress
**Spec:** graalvm-abi-core-types.md

---

## 1. Design Philosophy

### 1.1 Type Taxonomy

Every type exposed across the ABI falls into exactly one of three categories:

| Category | Representation | Examples |
|---|---|---|
| **Scalar** | Native C types (`int32_t`, `double`, etc.) | `int32_t`, `double`, `bool` |
| **Enum** | C `typedef enum` with `TPipe_` prefix | `TPipe_ConverseRole`, `TPipe_ProviderName` |
| **Object** | Opaque handle + builder/destroy functions | `TPipe_ContentHandle`, `TPipe_ContextHandle` |

No structs are exposed directly across the ABI boundary. No arrays of structs appear as return types. Everything complex is represented as an opaque handle. This rule is absolute — it is the foundation of ABI stability as TPipe's internal Kotlin structures evolve.

### 1.2 Design Decisions

The following decisions govern the entire type system:

| Decision | Choice | Rationale |
|---|---|---|
| Object representation | Opaque handles + builder API | Preserves Kotlin semantics; ABI-stable as internals evolve |
| Handle lifecycle | Reference counting | Prevents leaks and use-after-free; standard pattern (COM, Wasm) |
| String memory | TPipe owns all returned strings | GC manages lifetime; callers read pointers safely while handle alive |
| Nullable scalars | Out-param + `TPipe_Result` return | Unambiguous; no magic sentinel values; consistent error handling |
| Collection representation | Opaque `TPipe_ListHandle` / `TPipe_MapHandle` | Consistent with handle pattern; builders for construction |
| Enum representation | C `typedef enum` with `TPipe_` prefix | Compiler-checkable in C; switch-compatible; matches POSIX convention |
| BinaryContent | Single handle + discriminator + variant getters | One handle type per sealed variant; clear type-safe variant access |

### 1.3 String Ownership Rules

All strings returned from TPipe ABI functions are **owned by TPipe** and valid until:

1. The handle they belong to is released (reference count reaches 0 and GC reclaims the object), OR
2. `TPipe_shutdown()` is called

Callers **must not** `free()` returned strings. Callers must **copy** the string if persistence beyond the handle's lifetime is required.

All strings passed **into** TPipe (as `const char*`) are **borrowed** by TPipe. TPipe copies what it needs internally. The caller retains ownership of the original string and may free it after the call returns.

For `out` parameter strings, the caller provides a buffer they own.

---

## 2. Handle System

### 2.1 Base Handle Type

All handles are `uint64_t` integers. The constant `TPIPE_INVALID_HANDLE` (0) represents a null or invalid handle.

```c
#define TPIPE_INVALID_HANDLE 0

typedef uint64_t TPipe_Handle;
```

### 2.2 Handle Types

```c
typedef uint64_t TPipe_ContentHandle;         // MultimodalContent
typedef uint64_t TPipe_BinaryHandle;          // BinaryContent (sealed class)
typedef uint64_t TPipe_ContextHandle;         // ContextWindow
typedef uint64_t TPipe_MiniBankHandle;       // MiniBank
typedef uint64_t TPipe_ListHandle;           // Generic list
typedef uint64_t TPipe_MapHandle;            // Generic map
typedef uint64_t TPipe_PipeSettingsHandle;   // PipeSettings
typedef uint64_t TPipe_ConverseHistoryHandle; // ConverseHistory
typedef uint64_t TPipe_TokenBudgetHandle;    // TokenBudgetSettings
typedef uint64_t TPipe_LoreBookHandle;       // LoreBook
typedef uint64_t TPipe_ErrorHandle;          // PipeError
typedef uint64_t TPipe_PCPHandle;            // PCP module root handle
typedef uint64_t TPipe_StdioContextHandle;   // StdioContextOptions
typedef uint64_t TPipe_HttpContextHandle;    // HttpContextOptions
typedef uint64_t TPipe_P2PTransportHandle;   // P2PTransport
typedef uint64_t TPipe_P2PDescriptorHandle;  // P2PDescriptor
typedef uint64_t TPipe_P2PRequirementsHandle; // P2PRequirements
typedef uint64_t TPipe_TraceConfigHandle;     // TraceConfig
typedef uint64_t TPipe_PcpExecutionResultHandle; // PCP execution result (output-only, not ref-counted)
typedef uint64_t TPipe_P2PInterfaceHandle;  // P2PInterface (native interface, not constructible)
```

### 2.3 Reference Counting

All handles are reference-counted. The library holds an internal count per handle. When the count reaches zero, the underlying object is eligible for GraalVM GC reclamation.

```c
// Increment the reference count. Called automatically by clone/copy operations.
void TPipe_Handle_addRef(TPipe_Handle handle);

// Decrement the reference count. When count reaches 0, the handle is invalidated
// and the underlying object is GC'd when TPipe's GC next runs.
void TPipe_Handle_release(TPipe_Handle handle);

// Get current reference count. Returns -1 if handle is invalid.
int TPipe_Handle_getRefCount(TPipe_Handle handle);

// Check if handle is valid (not TPIPE_INVALID_HANDLE and not yet freed)
int TPipe_Handle_isValid(TPipe_Handle handle);
```

**Lifecycle rules:**

- Every `create` or `clone` function increments the refcount (caller receives at count = 1)
- Caller must call `TPipe_Handle_release` when done (count decrements)
- Copying a handle (if supported) does NOT transfer ownership; both handles must be released
- `TPipe_shutdown()` releases all outstanding handles

### 2.4 Handle Validity

A handle is **valid** if:
1. It is not `TPIPE_INVALID_HANDLE` (0)
2. The underlying object has not been GC'd (refcount reached 0)

Calling any function with an invalid handle returns `TPIPE_ERR_INVALID_HANDLE`.

---

## 3. Enum Mappings

All TPipe enums map to C `typedef enum` with `TPipe_` prefix. Integer values match Kotlin ordinal order (0, 1, 2, ...).

### 3.1 Context and Conversation Enums

```c
typedef enum {
    TPIPE_ROLE_DEVELOPER,
    TPIPE_ROLE_SYSTEM,
    TPIPE_ROLE_USER,
    TPIPE_ROLE_AGENT,
    TPIPE_ROLE_ASSISTANT
} TPipe_ConverseRole;
// Maps to: com.TTT.Context.ConverseRole
// Kotlin: developer, system, user, agent, assistant

typedef enum {
    TPIPE_CONTEXT_TRUNCATE_TOP,
    TPIPE_CONTEXT_TRUNCATE_BOTTOM,
    TPIPE_CONTEXT_TRUNCATE_MIDDLE
} TPipe_ContextWindowSettings;
// Maps to: com.TTT.Enums.ContextWindowSettings
// Kotlin: TruncateTop, TruncateBottom, TruncateMiddle
```

### 3.2 Provider Enums

```c
typedef enum {
    TPIPE_PROVIDER_AWS,
    TPIPE_PROVIDER_NAI,
    TPIPE_PROVIDER_GEMINI,
    TPIPE_PROVIDER_GPT,
    TPIPE_PROVIDER_OLLAMA,
    TPIPE_PROVIDER_OPENROUTER
} TPipe_ProviderName;
// Maps to: com.TTT.Enums.ProviderName
// Kotlin: Aws, Nai, Gemini, Gpt, Ollama, OpenRouter

typedef enum {
    TPIPE_PROMPT_SINGLE,
    TPIPE_PROMPT_CHAT,
    TPIPE_PROMPT_INTERNAL_CONTEXT
} TPipe_PromptMode;
// Maps to: com.TTT.Enums.PromptMode
// Kotlin: singlePrompt, chat, internalContext

typedef enum {
    TPIPE_SUMMARY_APPEND,
    TPIPE_SUMMARY_REGENERATE
} TPipe_SummaryMode;
// Maps to: com.TTT.Enums.SummaryMode
// Kotlin: APPEND, REGENERATE
```

### 3.3 PCP / Transport Enums

```c
typedef enum {
    TPIPE_TRANSPORT_AUTO,
    TPIPE_TRANSPORT_STDIO,
    TPIPE_TRANSPORT_TPIPE,
    TPIPE_TRANSPORT_HTTP,
    TPIPE_TRANSPORT_PYTHON,
    TPIPE_TRANSPORT_KOTLIN,
    TPIPE_TRANSPORT_JAVASCRIPT,
    TPIPE_TRANSPORT_UNKNOWN
} TPipe_Transport;
// Maps to: com.TTT.PipeContextProtocol.Transport

typedef enum {
    TPIPE_PERMISSION_READ,
    TPIPE_PERMISSION_WRITE,
    TPIPE_PERMISSION_DELETE,
    TPIPE_PERMISSION_EXECUTE
} TPipe_Permissions;
// Maps to: com.TTT.PipeContextProtocol.Permissions

typedef enum {
    TPIPE_PARAM_STRING,
    TPIPE_PARAM_INT,
    TPIPE_PARAM_BOOL,
    TPIPE_PARAM_FLOAT,
    TPIPE_PARAM_ENUM,
    TPIPE_PARAM_LIST,
    TPIPE_PARAM_MAP,
    TPIPE_PARAM_OBJECT,
    TPIPE_PARAM_ANY
} TPipe_ParamType;
// Maps to: com.TTT.PipeContextProtocol.ParamType

typedef enum {
    TPIPE_STDIO_ONE_SHOT,
    TPIPE_STDIO_INTERACTIVE,
    TPIPE_STDIO_CONNECT,
    TPIPE_STDIO_BUFFER_REPLAY
} TPipe_StdioExecutionMode;
// Maps to: com.TTT.PipeContextProtocol.StdioExecutionMode
```

### 3.4 P2P Enums

```c
typedef enum {
    TPIPE_CONTEXT_PROTOCOL_PCP,
    TPIPE_CONTEXT_PROTOCOL_MCP,
    TPIPE_CONTEXT_PROTOCOL_PROVIDER,
    TPIPE_CONTEXT_PROTOCOL_NONE
} TPipe_ContextProtocol;
// Maps to: com.TTT.P2P.ContextProtocol

typedef enum {
    TPIPE_CONTENT_TEXT,
    TPIPE_CONTENT_IMAGE,
    TPIPE_CONTENT_VIDEO,
    TPIPE_CONTENT_AUDIO,
    TPIPE_CONTENT_APPLICATION,
    TPIPE_CONTENT_OTHER,
    TPIPE_CONTENT_NONE
} TPipe_SupportedContentTypes;
// Maps to: com.TTT.P2P.SupportedContentTypes

typedef enum {
    TPIPE_INPUT_PLAIN_TEXT,
    TPIPE_INPUT_JSON,
    TPIPE_INPUT_XML,
    TPIPE_INPUT_HTML,
    TPIPE_INPUT_CSV,
    TPIPE_INPUT_TSV,
    TPIPE_INPUT_YAML,
    TPIPE_INPUT_MARKDOWN,
    TPIPE_INPUT_BYTES,
    TPIPE_INPUT_OTHER,
    TPIPE_INPUT_NONE
} TPipe_InputSchema;
// Maps to: com.TTT.P2P.InputSchema
```

### 3.5 Trace / Debug Enums

```c
typedef enum {
    TPIPE_TRACE_EVENT_PIPE_START,
    TPIPE_TRACE_EVENT_PIPE_END,
    TPIPE_TRACE_EVENT_PIPE_FAILURE,
    TPIPE_TRACE_EVENT_API_CALL,
    TPIPE_TRACE_EVENT_API_RESPONSE,
    TPIPE_TRACE_EVENT_CONTEXT_INJECT,
    TPIPE_TRACE_EVENT_TOKEN_COUNT,
    TPIPE_TRACE_EVENT_TRUNCATION,
    TPIPE_TRACE_EVENT_LOREBOOK_INJECT,
    TPIPE_TRACE_EVENT_STREAMING_CALLBACK,
    TPIPE_TRACE_EVENT_ASYNC_START,
    TPIPE_TRACE_EVENT_ASYNC_COMPLETE,
    TPIPE_TRACE_EVENT_P2P_SEND,
    TPIPE_TRACE_EVENT_P2P_RECEIVE,
    TPIPE_TRACE_EVENT_PCP_INVOKE,
    TPIPE_TRACE_EVENT_PCP_RESULT
} TPipe_TraceEventType;
// Maps to: com.TTT.Debug.TraceEventType

typedef enum {
    TPIPE_TRACE_PHASE_INIT,
    TPIPE_TRACE_PHASE_PROMPT_BUILD,
    TPIPE_TRACE_PHASE_SYSTEM_PROMPT,
    TPIPE_TRACE_PHASE_CONTEXT_INJECT,
    TPIPE_TRACE_PHASE_LOREBOOK_INJECT,
    TPIPE_TRACE_PHASE_API_CALL,
    TPIPE_TRACE_PHASE_STREAMING,
    TPIPE_TRACE_PHASE_RESPONSE_PARSE,
    TPIPE_TRACE_PHASE_CONTEXT_UPDATE,
    TPIPE_TRACE_PHASE_COMPLETE,
    TPIPE_TRACE_PHASE_ERROR
} TPipe_TracePhase;
// Maps to: com.TTT.Debug.TracePhase
```

### 3.6 Storage / Memory Enums

```c
typedef enum {
    TPIPE_STORAGE_MEMORY_ONLY,
    TPIPE_STORAGE_MEMORY_AND_DISK,
    TPIPE_STORAGE_DISK_ONLY,
    TPIPE_STORAGE_DISK_WITH_CACHE,
    TPIPE_STORAGE_REMOTE
} TPipe_StorageMode;
// Maps to: com.TTT.Context.StorageMode

typedef enum {
    TPIPE_MEMORY_OP_SUCCESS,
    TPIPE_MEMORY_OP_NOT_FOUND,
    TPIPE_MEMORY_OP_READ_ERROR,
    TPIPE_MEMORY_OP_WRITE_ERROR,
    TPIPE_MEMORY_OP_LOCKED,
    TPIPE_MEMORY_OP_TIMEOUT,
    TPIPE_MEMORY_OP_QUOTA_EXCEEDED
} TPipe_MemoryErrorType;
// Maps to: com.TTT.Context.MemoryTypes.MemoryErrorType
```

---

## 4. MultimodalContent (ContentHandle)

`MultimodalContent` is the primary content object in TPipe. It aggregates text, binary data, context sub-objects, and control signals (terminate, repeat, pass, jump).

### 4.1 Lifecycle Functions

```c
// Create a new MultimodalContent with default (empty) values.
TPipe_ContentHandle TPipe_Content_create(void);

// Clone creates a deep copy of the content. Reference count = 1 on the new handle.
// Caller must release both the original and the clone independently.
TPipe_ContentHandle TPipe_Content_clone(TPipe_ContentHandle handle);

void TPipe_Content_addRef(TPipe_ContentHandle handle);
void TPipe_Content_release(TPipe_ContentHandle handle);
```

### 4.2 Text Field

```c
// Set the text field. TPipe copies the string internally.
// Returns the handle for chaining.
TPipe_ContentHandle TPipe_Content_setText(TPipe_ContentHandle handle, const char* text);

// Get a pointer to the text field. Pointer is owned by TPipe; valid until handle is released.
const char* TPipe_Content_getText(TPipe_ContentHandle handle);
```

### 4.3 Control Flags

```c
TPipe_ContentHandle TPipe_Content_setTerminate(TPipe_ContentHandle handle, int terminate);
// terminate: 0 = no terminate, 1 = terminate pipeline
//
// When terminate is set to 1, the pipeline terminates cooperatively:
// - Execution stops at the next execution checkpoint (not immediately)
// - The pipeline returns early with empty text (text = "")
// - HTTP connections, file handles, and agent dispatch state are cleaned up
//   via normal finally-block execution and structured concurrency scope unwinding
// - The terminate signal does NOT force-kill threads; it is a flag checked
//   at pipeline hop boundaries
//
// This is ABORTIVE in the sense that no further pipeline stages execute after
// the flag is observed, but COOPERATIVE in that cleanup (finally blocks, scope
// unwinding) executes normally. No resource leaks result from proper scope
// management.
//
// Note: TPipe_Pipeline_wasTerminatedByError() reports whether the pipeline
// was terminated by an error condition, vs normal completion.

TPipe_ContentHandle TPipe_Content_setRepeat(TPipe_ContentHandle handle, int repeat);
// repeat: 0 = do not repeat, 1 = repeat pipe call

TPipe_ContentHandle TPipe_Content_setPass(TPipe_ContentHandle handle, int pass);
// pass: 0 = normal, 1 = pass pipeline early (success exit)

TPipe_ContentHandle TPipe_Content_setSkipReasoning(TPipe_ContentHandle handle, int skip);
// skip: 0 = normal, 1 = skip reasoning pipe extraction

TPipe_ContentHandle TPipe_Content_setJumpToPipe(TPipe_ContentHandle handle, const char* pipeName);
// Set jump destination. Use TPipe_Content_clearJump to reset.

TPipe_ContentHandle TPipe_Content_clearJump(TPipe_ContentHandle handle);
// Clear the jump-to-pipe signal.

int TPipe_Content_getTerminate(TPipe_ContentHandle handle);
int TPipe_Content_getRepeat(TPipe_ContentHandle handle);
int TPipe_Content_getPass(TPipe_ContentHandle handle);
int TPipe_Content_getSkipReasoning(TPipe_ContentHandle handle);
const char* TPipe_Content_getJumpToPipe(TPipe_ContentHandle handle);
```

### 4.4 Error State

```c
// Check if content has an associated PipeError. out_error is set on TPIPE_OK.
int TPipe_Content_hasError(TPipe_ContentHandle handle, TPipe_ErrorHandle* out_error);

// Note: pipeError is transient (not serialized) in Kotlin. It is only present
// for content returned from a pipe execution that encountered an error.
// It cannot be constructed by callers via the ABI.
```

### 4.5 Binary Content

```c
// Add binary content. Returns a TPipe_BinaryHandle for further configuration.
// The binary is appended to the content's binaryContent list.
TPipe_BinaryHandle TPipe_Content_addBinary(TPipe_ContentHandle handle);

// Get all binary content as a list handle. The returned list is owned by TPipe
// and valid until handle is released. Do NOT release the list separately.
TPipe_ListHandle TPipe_Content_getBinaries(TPipe_ContentHandle handle);
```

### 4.6 Sub-Object Accessors

```c
// Get the ContextWindow sub-object. The context is owned by the content handle;
// it is NOT independently reference-counted. Do NOT release the returned handle.
TPipe_ContextHandle TPipe_Content_getContext(TPipe_ContentHandle handle);

// Get the MiniBank sub-object. Same ownership rules as context.
TPipe_MiniBankHandle TPipe_Content_getMiniBank(TPipe_ContentHandle handle);
```

### 4.7 Convenience Predicates

```c
// Returns 1 if both text and binaryContent are empty
int TPipe_Content_isEmpty(TPipe_ContentHandle handle);

// Returns 1 if binaryContent list is non-empty
int TPipe_Content_hasBinaryContent(TPipe_ContentHandle handle);
```

---

## 5. BinaryContent

`BinaryContent` is a sealed class with four variants. It is represented as a single `TPipe_BinaryHandle` type with a discriminator function and variant-specific getters.

### 5.1 Discriminator

```c
typedef enum {
    TPIPE_BINARY_TYPE_BYTES,
    TPIPE_BINARY_TYPE_BASE64,
    TPIPE_BINARY_TYPE_CLOUD_REF,
    TPIPE_BINARY_TYPE_TEXT_DOC
} TPipe_BinaryType;

// Get the variant discriminator. Always call this first before using variant getters.
TPipe_BinaryType TPipe_BinaryContent_getType(TPipe_BinaryHandle handle);
```

### 5.2 Bytes Variant

```c
// Create bytes variant. Data is copied into TPipe's GC heap.
TPipe_BinaryHandle TPipe_BinaryContent_createBytes(const uint8_t* data,
                                                    size_t dataLen,
                                                    const char* mimeType,
                                                    const char* filename);  // NULL allowed

// Get raw bytes. outLen is set to byte count. Pointer is owned by TPipe.
const uint8_t* TPipe_BinaryContent_getBytesData(TPipe_BinaryHandle handle, size_t* outLen);

// Returns NULL if no filename was set
const char* TPipe_BinaryContent_getFilename(TPipe_BinaryHandle handle, int* outIsNull);
```

### 5.3 Base64 Variant

```c
TPipe_BinaryHandle TPipe_BinaryContent_createBase64(const char* base64Data,
                                                      const char* mimeType,
                                                      const char* filename);  // NULL allowed

// Returns the raw base64 string. Pointer is owned by TPipe.
const char* TPipe_BinaryContent_getBase64Data(TPipe_BinaryHandle handle);
```

### 5.4 CloudReference Variant

```c
TPipe_BinaryHandle TPipe_BinaryContent_createCloudRef(const char* uri,
                                                       const char* mimeType,
                                                       const char* filename);  // NULL allowed

const char* TPipe_BinaryContent_getUri(TPipe_BinaryHandle handle);
```

### 5.5 TextDocument Variant

```c
TPipe_BinaryHandle TPipe_BinaryContent_createTextDoc(const char* content,
                                                      const char* mimeType,  // defaults to "text/plain"
                                                      const char* filename);  // NULL allowed

const char* TPipe_BinaryContent_getTextContent(TPipe_BinaryHandle handle);
```

### 5.6 Common Accessors

```c
// All variants support these:
const char* TPipe_BinaryContent_getMimeType(TPipe_BinaryHandle handle);
```

---

## 6. Context System

### 6.1 ContextWindow

```c
TPipe_ContextHandle TPipe_Context_create(void);
TPipe_ContextHandle TPipe_Context_clone(TPipe_ContextHandle handle);
void TPipe_Context_addRef(TPipe_ContextHandle handle);
void TPipe_Context_release(TPipe_ContextHandle handle);

// LoreBook management
const char* TPipe_Context_addLoreBook(TPipe_ContextHandle handle,
                                       const char* key,
                                       const char* value,
                                       const char** aliasKeys, int aliasCount,
                                       const char** linkedKeys, int linkedCount,
                                       const char** requiredKeys, int requiredCount);
// Returns the key on success, NULL on failure. Key must not be empty.

TPipe_ListHandle TPipe_Context_findLoreBookKeys(TPipe_ContextHandle handle, const char* text);
// Returns a list of matching lorebook keys (as const char*). Caller must release the list.

TPipe_LoreBookHandle TPipe_Context_getLoreBook(TPipe_ContextHandle handle, const char* key);
// Returns handle, or TPIPE_INVALID_HANDLE if not found. Caller must release.

TPipe_ListHandle TPipe_Context_getLoreBookKeys(TPipe_ContextHandle handle);
// Returns all lorebook keys. Caller must release.

// Context elements (raw string injection)
TPipe_ListHandle TPipe_Context_getContextElements(TPipe_ContextHandle handle);
TPipe_ContextHandle TPipe_Context_addContextElement(TPipe_ContextHandle handle, const char* element);

TPipe_ContextHandle TPipe_Context_clearContextElements(TPipe_ContextHandle handle);

// ConverseHistory
TPipe_ConverseHistoryHandle TPipe_Context_getHistory(TPipe_ContextHandle handle);
TPipe_ContextHandle TPipe_Context_addHistoryEntry(TPipe_ContextHandle handle,
                                                    TPipe_ConverseRole role,
                                                    TPipe_ContentHandle content);

// Version
int TPipe_Context_getVersion(TPipe_ContextHandle handle);
```

### 6.2 MiniBank

```c
TPipe_MiniBankHandle TPipe_MiniBank_create(void);
TPipe_MiniBankHandle TPipe_MiniBank_clone(TPipe_MiniBankHandle handle);
void TPipe_MiniBank_addRef(TPipe_MiniBankHandle handle);
void TPipe_MiniBank_release(TPipe_MiniBankHandle handle);

TPipe_ContextHandle TPipe_MiniBank_getContext(TPipe_MiniBankHandle handle, const char* pageKey);
// Returns TPIPE_INVALID_HANDLE if page not found.

TPipe_MiniBankHandle TPipe_MiniBank_setContext(TPipe_MiniBankHandle handle,
                                                const char* pageKey,
                                                TPipe_ContextHandle context);

TPipe_ListHandle TPipe_MiniBank_getPageKeys(TPipe_MiniBankHandle handle);
// Returns list of const char* page keys. Caller must release.

int TPipe_MiniBank_hasPage(TPipe_MiniBankHandle handle, const char* pageKey);
int TPipe_MiniBank_isEmpty(TPipe_MiniBankHandle handle);
TPipe_MiniBankHandle TPipe_MiniBank_clear(TPipe_MiniBankHandle handle);
```

### 6.3 ConverseHistory

```c
TPipe_ConverseHistoryHandle TPipe_History_create(void);
void TPipe_History_addRef(TPipe_ConverseHistoryHandle handle);
void TPipe_History_release(TPipe_ConverseHistoryHandle handle);

TPipe_ConverseHistoryHandle TPipe_History_add(TPipe_ConverseHistoryHandle handle,
                                               TPipe_ConverseRole role,
                                               TPipe_ContentHandle content);

TPipe_ListHandle TPipe_History_getEntries(TPipe_ConverseHistoryHandle handle);
// Returns a list of TPipe_ContentHandle (one per ConverseData entry).
// The content handles are borrowed (not addRef'd); do NOT release them.
int TPipe_History_isEmpty(TPipe_ConverseHistoryHandle handle);
int TPipe_History_getCount(TPipe_ConverseHistoryHandle handle);
```

---

## 7. LoreBook

```c
TPipe_LoreBookHandle TPipe_LoreBook_create(const char* key, const char* value);
void TPipe_LoreBook_addRef(TPipe_LoreBookHandle handle);
void TPipe_LoreBook_release(TPipe_LoreBookHandle handle);

const char* TPipe_LoreBook_getKey(TPipe_LoreBookHandle handle);
const char* TPipe_LoreBook_getValue(TPipe_LoreBookHandle handle);

TPipe_ListHandle TPipe_LoreBook_getAliasKeys(TPipe_LoreBookHandle handle);
TPipe_ListHandle TPipe_LoreBook_getLinkedKeys(TPipe_LoreBookHandle handle);
TPipe_ListHandle TPipe_LoreBook_getRequiredKeys(TPipe_LoreBookHandle handle);
// All return lists of const char*. Caller must release.

TPipe_LoreBookHandle TPipe_LoreBook_addAlias(TPipe_LoreBookHandle handle, const char* alias);
TPipe_LoreBookHandle TPipe_LoreBook_addLinked(TPipe_LoreBookHandle handle, const char* linked);
TPipe_LoreBookHandle TPipe_LoreBook_addRequired(TPipe_LoreBookHandle handle, const char* required);
```

---

## 8. TokenBudgetSettings and TruncationSettings

`TokenBudgetSettings` is a complex config object with 15+ fields. `TruncationSettings` is represented as a nested struct within `TokenBudgetSettings`.

### 8.1 TokenBudgetSettings

```c
TPipe_TokenBudgetHandle TPipe_TokenBudget_create(void);
void TPipe_TokenBudget_addRef(TPipe_TokenBudgetHandle handle);
void TPipe_TokenBudget_release(TPipe_TokenBudgetHandle handle);

// Builder-style setters (all return handle for chaining)
TPipe_TokenBudgetHandle TPipe_TokenBudget_setUserPromptSize(TPipe_TokenBudgetHandle, int tokens);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setMaxTokens(TPipe_TokenBudgetHandle, int tokens);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setReasoningBudget(TPipe_TokenBudgetHandle, int tokens);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setSubtractReasoning(TPipe_TokenBudgetHandle, int subtract);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setContextWindowSize(TPipe_TokenBudgetHandle, int tokens);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setAllowTruncation(TPipe_TokenBudgetHandle, int allow);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setPreserveJson(TPipe_TokenBudgetHandle, int preserve);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setCompressPrompt(TPipe_TokenBudgetHandle, int compress);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setTruncateAsString(TPipe_TokenBudgetHandle, int truncate);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setPreserveTextMatches(TPipe_TokenBudgetHandle, int preserve);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setTruncationMode(TPipe_TokenBudgetHandle,
                                                             TPipe_ContextWindowSettings mode);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setMultiPageStrategy(TPipe_TokenBudgetHandle,
                                                                 TPipe_MultiPageBudgetStrategy strategy);
// Note: TPipe_MultiPageBudgetStrategy is an enum; values are:
//   TPIPE_MULTI_PAGE_EQUAL_SPLIT = 0
//   TPIPE_MULTI_PAGE_WEIGHTED_SPLIT = 1
//   TPIPE_MULTI_PAGE_PRIORITY_FILL = 2
//   TPIPE_MULTI_PAGE_DYNAMIC_FILL = 3
//   TPIPE_MULTI_PAGE_DYNAMIC_SIZE_FILL = 4

TPipe_TokenBudgetHandle TPipe_TokenBudget_setPageWeights(TPipe_TokenBudgetHandle, TPipe_MapHandle pageWeights);
// pageWeights: Map<const char*, double>

TPipe_TokenBudgetHandle TPipe_TokenBudget_setReserveEmptyPage(TPipe_TokenBudgetHandle, int reserve);

// Getters via out-param pattern (nullable fields)
int TPipe_TokenBudget_getUserPromptSize(TPipe_TokenBudgetHandle, int* out_tokens);
int TPipe_TokenBudget_getMaxTokens(TPipe_TokenBudgetHandle, int* out_tokens);
int TPipe_TokenBudget_getReasoningBudget(TPipe_TokenBudgetHandle, int* out_tokens);
int TPipe_TokenBudget_getContextWindowSize(TPipe_TokenBudgetHandle, int* out_tokens);
int TPipe_TokenBudget_getAllowTruncation(TPipe_TokenBudgetHandle, int* out_allow);
int TPipe_TokenBudget_getCompressPrompt(TPipe_TokenBudgetHandle, int* out_compress);
TPipe_ContextWindowSettings TPipe_TokenBudget_getTruncationMode(TPipe_TokenBudgetHandle);
// Non-nullable getters (always have values)
int TPipe_TokenBudget_getSubtractReasoning(TPipe_TokenBudgetHandle);
int TPipe_TokenBudget_getPreserveJson(TPipe_TokenBudgetHandle);
int TPipe_TokenBudget_getMultiPageStrategy(TPipe_TokenBudgetHandle);
```

### 8.2 TruncationSettings

`TruncationSettings` is represented as a sub-configuration accessible via `TokenBudgetSettings`. It is not an independently constructible handle type.

```c
// TruncationSettings accessors on TokenBudgetHandle
TPipe_TokenBudgetHandle TPipe_TokenBudget_setMultiplyWindowBy(TPipe_TokenBudgetHandle, int multiplier);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setCountSubWords(TPipe_TokenBudgetHandle, int count);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setFavorWholeWords(TPipe_TokenBudgetHandle, int favor);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setCountFirstWordOnly(TPipe_TokenBudgetHandle, int countOnly);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setSplitForNonWord(TPipe_TokenBudgetHandle, int split);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setAlwaysSplitIfWhole(TPipe_TokenBudgetHandle, int always);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setCountSubWordsIfSplit(TPipe_TokenBudgetHandle, int count);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setNonWordSplitCount(TPipe_TokenBudgetHandle, int count);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setTokenBias(TPipe_TokenBudgetHandle, double bias);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setFillMode(TPipe_TokenBudgetHandle, int fillMode);
TPipe_TokenBudgetHandle TPipe_TokenBudget_setFillAndSplitMode(TPipe_TokenBudgetHandle, int mode);
```

---

## 9. PipeSettings

`PipeSettings` is the complete configuration snapshot for a pipe. It has 35+ fields including provider, model, prompts, token budget, context references, and truncation settings.

```c
TPipe_PipeSettingsHandle TPipe_PipeSettings_create(void);
void TPipe_PipeSettings_addRef(TPipe_PipeSettingsHandle handle);
void TPipe_PipeSettings_release(TPipe_PipeSettingsHandle handle);

// String fields
TPipe_PipeSettingsHandle TPipe_PipeSettings_setName(TPipe_PipeSettingsHandle, const char* name);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setModel(TPipe_PipeSettingsHandle, const char* model);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setSystemPrompt(TPipe_PipeSettingsHandle, const char* prompt);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setUserPrompt(TPipe_PipeSettingsHandle, const char* prompt);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setPageKey(TPipe_PipeSettingsHandle, const char* pageKey);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setPipeId(TPipe_PipeSettingsHandle, const char* pipeId);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setCurrentPipelineId(TPipe_PipeSettingsHandle, const char* id);

// Enum fields
TPipe_PipeSettingsHandle TPipe_PipeSettings_setProvider(TPipe_PipeSettingsHandle, TPipe_ProviderName provider);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setPromptMode(TPipe_PipeSettingsHandle, TPipe_PromptMode mode);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setContextTruncation(TPipe_PipeSettingsHandle,
                                                                   TPipe_ContextWindowSettings mode);

// Numeric fields
TPipe_PipeSettingsHandle TPipe_PipeSettings_setTemperature(TPipe_PipeSettingsHandle, double temp);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setTopP(TPipe_PipeSettingsHandle, double topP);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setTopK(TPipe_PipeSettingsHandle, int topK);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setMaxTokens(TPipe_PipeSettingsHandle, int tokens);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setContextWindowSize(TPipe_PipeSettingsHandle, int size);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setRepetitionPenalty(TPipe_PipeSettingsHandle, double penalty);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setMultiplyWindowBy(TPipe_PipeSettingsHandle, int multiplier);

// Boolean fields
TPipe_PipeSettingsHandle TPipe_PipeSettings_setSupportsNativeJson(TPipe_PipeSettingsHandle, int supports);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setReadFromGlobalContext(TPipe_PipeSettingsHandle, int read);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setReadFromPipelineContext(TPipe_PipeSettingsHandle, int read);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setUpdatePipelineContextOnExit(TPipe_PipeSettingsHandle, int update);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setAutoInjectContext(TPipe_PipeSettingsHandle, int autoInject);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setAutoTruncateContext(TPipe_PipeSettingsHandle, int truncate);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setEmplaceLorebook(TPipe_PipeSettingsHandle, int emplace);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setAppendLoreBook(TPipe_PipeSettingsHandle, int append);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setLoreBookFillMode(TPipe_PipeSettingsHandle, int fillMode);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setLoreBookFillAndSplitMode(TPipe_PipeSettingsHandle, int mode);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setUseModelReasoning(TPipe_PipeSettingsHandle, int use);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setTracingEnabled(TPipe_PipeSettingsHandle, int enabled);

// Truncation booleans
TPipe_PipeSettingsHandle TPipe_PipeSettings_setCountSubWords(TPipe_PipeSettingsHandle, int count);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setFavorWholeWords(TPipe_PipeSettingsHandle, int favor);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setCountFirstWordOnly(TPipe_PipeSettingsHandle, int countOnly);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setSplitForNonWord(TPipe_PipeSettingsHandle, int split);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setAlwaysSplitIfWhole(TPipe_PipeSettingsHandle, int always);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setCountSubWordsIfSplit(TPipe_PipeSettingsHandle, int count);

// Object handle fields
TPipe_PipeSettingsHandle TPipe_PipeSettings_setContextWindow(TPipe_PipeSettingsHandle,
                                                              TPipe_ContextHandle ctx);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setMiniContextBank(TPipe_PipeSettingsHandle,
                                                                 TPipe_MiniBankHandle bank);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setTokenBudget(TPipe_PipeSettingsHandle,
                                                              TPipe_TokenBudgetHandle budget);

// List fields
TPipe_PipeSettingsHandle TPipe_PipeSettings_setPageKeyList(TPipe_PipeSettingsHandle,
                                     TPipe_ListHandle keys);
TPipe_PipeSettingsHandle TPipe_PipeSettings_setStopSequences(TPipe_PipeSettingsHandle,
                                                                 TPipe_ListHandle sequences);

// Getters — nullable fields use out-param + result pattern
int TPipe_PipeSettings_getName(TPipe_PipeSettingsHandle, const char** out_name);
int TPipe_PipeSettings_getModel(TPipe_PipeSettingsHandle, const char** out_model);
// ... all nullable fields follow this pattern

// Non-nullable numeric getters (direct return)
double TPipe_PipeSettings_getTemperature(TPipe_PipeSettingsHandle);
double TPipe_PipeSettings_getTopP(TPipe_PipeSettingsHandle);
int TPipe_PipeSettings_getMaxTokens(TPipe_PipeSettingsHandle);
int TPipe_PipeSettings_getCount(TPipe_PipeSettingsHandle);

// Object handle getters (borrowed references, do not release)
TPipe_ContextHandle TPipe_PipeSettings_getContextWindow(TPipe_PipeSettingsHandle);
TPipe_TokenBudgetHandle TPipe_PipeSettings_getTokenBudget(TPipe_PipeSettingsHandle);
```

---

## 10. Collections (List and Map)

### 10.1 List

```c
TPipe_ListHandle TPipe_List_create(void);
void TPipe_List_addRef(TPipe_ListHandle handle);
void TPipe_List_release(TPipe_ListHandle handle);

// Mutation
TPipe_ListHandle TPipe_List_append(TPipe_ListHandle handle, TPipe_Handle item);
TPipe_ListHandle TPipe_List_removeAt(TPipe_ListHandle handle, int index);
TPipe_ListHandle TPipe_List_clear(TPipe_ListHandle handle);

// Access
int TPipe_List_getCount(TPipe_ListHandle handle);
TPipe_Handle TPipe_List_get(TPipe_ListHandle handle, int index);
// Returns TPIPE_INVALID_HANDLE if index out of range

// String convenience (handles own copies of strings)
TPipe_ListHandle TPipe_List_appendString(TPipe_ListHandle handle, const char* str);
const char* TPipe_List_getString(TPipe_ListHandle handle, int index);
// Returns owned string; caller must NOT release

// Content handle convenience (addRef'd on append, borrowed on get)
TPipe_ListHandle TPipe_List_appendContent(TPipe_ListHandle handle, TPipe_ContentHandle content);
TPipe_ContentHandle TPipe_List_getContent(TPipe_ListHandle handle, int index);
// Getter does NOT addRef; caller must NOT release returned handle
```

### 10.2 Map

```c
TPipe_MapHandle TPipe_Map_create(void);
void TPipe_Map_addRef(TPipe_MapHandle handle);
void TPipe_Map_release(TPipe_MapHandle handle);

// Mutation
TPipe_MapHandle TPipe_Map_set(TPipe_MapHandle handle, const char* key, TPipe_Handle value);
TPipe_MapHandle TPipe_Map_remove(TPipe_MapHandle handle, const char* key);
TPipe_MapHandle TPipe_Map_clear(TPipe_MapHandle handle);

// Access
int TPipe_Map_getCount(TPipe_MapHandle handle);
TPipe_Handle TPipe_Map_get(TPipe_MapHandle handle, const char* key);
int TPipe_Map_hasKey(TPipe_MapHandle handle, const char* key);
TPipe_ListHandle TPipe_Map_getKeys(TPipe_MapHandle handle);
// Returns list of const char*. Caller must release.

// String convenience
TPipe_MapHandle TPipe_Map_setString(TPipe_MapHandle handle, const char* key, const char* value);
const char* TPipe_Map_getString(TPipe_MapHandle handle, const char* key);
// Returns owned string; NULL if key not found

// Int convenience
TPipe_MapHandle TPipe_Map_setInt(TPipe_MapHandle handle, const char* key, int value);
int TPipe_Map_getInt(TPipe_MapHandle handle, const char* key, int* out_value);
// Returns TPIPE_OK and sets out_value on success; error code if key not found or type mismatch
```

---

## 11. PipeError

`PipeError` captures error information from a failed pipe execution.

```c
TPipe_ErrorHandle TPipe_Error_create(TPipe_TraceEventType eventType,
                                       TPipe_TracePhase phase,
                                       const char* pipeName,
                                       const char* pipeId);
void TPipe_Error_addRef(TPipe_ErrorHandle handle);
void TPipe_Error_release(TPipe_ErrorHandle handle);

TPipe_TraceEventType TPipe_Error_getEventType(TPipe_ErrorHandle handle);
TPipe_TracePhase TPipe_Error_getPhase(TPipe_ErrorHandle handle);
const char* TPipe_Error_getPipeName(TPipe_ErrorHandle handle);
const char* TPipe_Error_getPipeId(TPipe_ErrorHandle handle);
int64_t TPipe_Error_getTimestamp(TPipe_ErrorHandle handle);
// Timestamp: milliseconds since Unix epoch (1970-01-01)

const char* TPipe_Error_getMessage(TPipe_ErrorHandle handle);
// Returns the exception message, or "Unknown error" if no exception was set.
```

**Note:** The `exception` field on `PipeError` is `@Transient` in Kotlin and is not serializable. It is not accessible via the ABI. The error message is extracted and made available via `getMessage()`.

---

## 12. PCP / Tool Call Types

### 12.1 PcPRequest

Represents a tool call request generated by an LLM.

```c
TPipe_PCPHandle TPipe_PCP_createRequest(const char* functionName, TPipe_MapHandle params);
void TPipe_PCP_addRef(TPipe_PCPHandle handle);
void TPipe_PCP_release(TPipe_PCPHandle handle);

const char* TPipe_PCP_getFunctionName(TPipe_PCPHandle handle);
TPipe_MapHandle TPipe_PCP_getParams(TPipe_PCPHandle handle);
// Returns the params map. Do NOT release; it is owned by the request.
```

### 12.1.1 PcPRequest Schema Validation Guard

**Critical ABI boundary rule:** All `PcPRequest` instances crossing the managed/native boundary MUST be validated before they reach Kotlin deserialization logic.

#### Why validation is required at the boundary

LLM-generated JSON is inherently unreliable. The TPipe codebase explicitly includes `repairJsonString` and `repairAndDeserialize` utilities specifically because LLMs produce malformed PCP payloads. Without a validation guard at the ABI boundary, malformed `PcPRequest` input can cause:

1. **Partial deserialization** — JSON parser consumes only valid prefix, leaving inconsistent state
2. **Exception propagation** — unhandled parsing exceptions cross the ABI boundary (undefined behavior in native images)
3. **Type confusion attacks** — a `params` map containing wrong value types causes downstream cast failures

#### Required validation steps

When a caller passes a `PcPRequest` to any TPipe function (including `TPipe_PCP_createRequest`):

```
1. functionName:
   - Must be non-NULL, non-empty
   - Must match pattern: alphanumeric + underscore, max 128 chars
   - Must not exceed 128 characters
2. params (TPipe_MapHandle):
   - Must be a valid, non-NULL map handle
   - All keys must be non-NULL, non-empty strings
   - All values must be one of: STRING, INT, BOOL, FLOAT, ENUM, LIST, MAP, OBJECT, or NULL
   - Nested maps/lists must have valid structure (no cycles)
3. Schema enforcement:
   - If functionName is unknown/unregistered: return TPIPE_ERR_UNSUPPORTED
   - If required params are missing: return TPIPE_ERR_INVALID_ARGUMENT
   - If param types don't match expected types: return TPIPE_ERR_INVALID_ARGUMENT
```

#### Error handling

```
TPipe_PCP_createRequest(functionName, params):
  - If functionName NULL or empty → TPIPE_ERR_INVALID_ARGUMENT
  - If params is TPIPE_INVALID_HANDLE → TPIPE_ERR_INVALID_HANDLE
  - If params map has invalid structure → TPIPE_ERR_INVALID_ARGUMENT
  - If functionName unknown → TPIPE_ERR_UNSUPPORTED
  - If required param missing → TPIPE_ERR_INVALID_ARGUMENT
  - If param type mismatch → TPIPE_ERR_INVALID_ARGUMENT
  - On success → returns TPipe_PCPHandle (caller receives at refcount=1)
```

#### Wrapper guidance

Language wrappers (Python ctypes, Node FFI, etc.) MUST perform validation before calling `TPipe_PCP_createRequest`. The native image does not repair malformed input — repair happens at wrapper layer using `repairJsonString` if needed, then validation, then ABI call.

#### Internal repair path (not ABI surface)

TPipe's internal `repairJsonString` and `repairAndDeserialize` utilities handle JSON repair internally. These are **not** part of the public ABI — they are implementation details of the internal Kotlin parsing layer. The ABI surface only exposes the validated creation API.

---

### 12.2 StdioContextOptions

```c
TPipe_StdioContextHandle TPipe_StdioContext_create(void);
void TPipe_StdioContext_addRef(TPipe_StdioContextHandle handle);
void TPipe_StdioContext_release(TPipe_StdioContextHandle handle);

TPipe_StdioContextHandle TPipe_StdioContext_setCommand(TPipe_StdioContextHandle, const char* command);
TPipe_StdioContextHandle TPipe_StdioContext_setArgs(TPipe_StdioContextHandle, TPipe_ListHandle args);
// args: List<const char*>

TPipe_StdioContextHandle TPipe_StdioContext_setPermissions(TPipe_StdioContextHandle,
                                                            TPipe_ListHandle perms);
// perms: List<TPipe_Permissions>

TPipe_StdioContextHandle TPipe_StdioContext_setDescription(TPipe_StdioContextHandle, const char* desc);
TPipe_StdioContextHandle TPipe_StdioContext_setExecutionMode(TPipe_StdioContextHandle,
                                                                TPipe_StdioExecutionMode mode);
TPipe_StdioContextHandle TPipe_StdioContext_setTimeout(TPipe_StdioContextHandle, int64_t timeoutMs);
TPipe_StdioContextHandle TPipe_StdioContext_setWorkingDirectory(TPipe_StdioContextHandle, const char* dir);
TPipe_StdioContextHandle TPipe_StdioContext_setEnvVars(TPipe_StdioContextHandle, TPipe_MapHandle envVars);
// envVars: Map<const char*, const char*>

TPipe_StdioContextHandle TPipe_StdioContext_setKeepAlive(TPipe_StdioContextHandle, int keepAlive);
TPipe_StdioContextHandle TPipe_StdioContext_setBufferPersistence(TPipe_StdioContextHandle, int persist);
TPipe_StdioContextHandle TPipe_StdioContext_setMaxBufferSize(TPipe_StdioContextHandle, int64_t maxBytes);

// Getters
const char* TPipe_StdioContext_getCommand(TPipe_StdioContextHandle);
TPipe_StdioExecutionMode TPipe_StdioContext_getExecutionMode(TPipe_StdioContextHandle);
int64_t TPipe_StdioContext_getTimeout(TPipe_StdioContextHandle);
```

### 12.3 PcpExecutionResult

`PcpExecutionResult` is the result of PCP tool call execution. It is an **output-only** type — callers cannot construct it via the ABI. It is not reference-counted.

```c
// Fields (access via getters):
//   int success              — 1 if execution succeeded, 0 if errors occurred
//   TPipe_ListHandle results — list of TPipe_Handle (tool call results)
//   TPipe_ListHandle errors  — list of TPipe_ErrorHandle (execution errors)
//   int64_t executionTimeMs — time spent executing in milliseconds

TPipe_Result TPipe_PcpExecutionResult_getSuccess(TPipe_PcpExecutionResultHandle handle, int* out_success);
TPipe_Result TPipe_PcpExecutionResult_getResults(TPipe_PcpExecutionResultHandle handle,
                                                  TPipe_ListHandle* out_results);
TPipe_Result TPipe_PcpExecutionResult_getErrors(TPipe_PcpExecutionResultHandle handle,
                                                 TPipe_ListHandle* out_errors);
TPipe_Result TPipe_PcpExecutionResult_getExecutionTimeMs(TPipe_PcpExecutionResultHandle handle,
                                                         int64_t* out_timeMs);
```

---

## 13. P2P Types

### 13.1 P2PTransport

```c
TPipe_P2PTransportHandle TPipe_P2PTransport_create(TPipe_Transport transportMethod,
                                                      const char* address,
                                                      const char* authBody);
void TPipe_P2PTransport_addRef(TPipe_P2PTransportHandle handle);
void TPipe_P2PTransport_release(TPipe_P2PTransportHandle handle);

TPipe_Transport TPipe_P2PTransport_getMethod(TPipe_P2PTransportHandle handle);
const char* TPipe_P2PTransport_getAddress(TPipe_P2PTransportHandle handle);
// Returns NULL if authBody not set
const char* TPipe_P2PTransport_getAuthBody(TPipe_P2PTransportHandle handle, int* outIsNull);
```

### 13.2 P2PDescriptor

```c
TPipe_P2PDescriptorHandle TPipe_P2PDescriptor_create(const char* agentName,
                                                      TPipe_P2PTransportHandle transport,
                                                      TPipe_ContextProtocol contextProtocol,
                                                      TPipe_SupportedContentTypes contentType,
                                                      TPipe_InputSchema inputSchema);
void TPipe_P2PDescriptor_addRef(TPipe_P2PDescriptorHandle handle);
void TPipe_P2PDescriptor_release(TPipe_P2PDescriptorHandle handle);

const char* TPipe_P2PDescriptor_getAgentName(TPipe_P2PDescriptorHandle handle);
TPipe_P2PTransportHandle TPipe_P2PDescriptor_getTransport(TPipe_P2PDescriptorHandle handle);
TPipe_ContextProtocol TPipe_P2PDescriptor_getContextProtocol(TPipe_P2PDescriptorHandle handle);
TPipe_SupportedContentTypes TPipe_P2PDescriptor_getContentType(TPipe_P2PDescriptorHandle handle);
TPipe_InputSchema TPipe_P2PDescriptor_getInputSchema(TPipe_P2PDescriptorHandle handle);
```

---

## 14. TokenUsage (Output Only)

`TokenUsage` is returned from pipe execution to report token consumption. It is **not** constructible by callers; it is an output-only type.

```c
typedef struct {
    int inputTokens;
    int outputTokens;
    int totalInputTokens;   // includes this pipe + all child pipes
    int totalOutputTokens;  // includes this pipe + all child pipes
} TPipe_TokenUsage;

// Caller provides a pointer to struct; TPipe fills it on success.
// This struct is passed by value, not by handle.
TPipe_Result TPipe_TokenUsage_fromHandle(TPipe_Handle handle, TPipe_TokenUsage* out_usage);
```

**Note:** `TokenUsage.childPipeTokens` (a `MutableMap<String, TokenUsage>`) is represented as a flattened aggregation in the output struct. Individual child token breakdowns are not exposed at the ABI level — only aggregate totals.

---

## 15. Complete Type-to-Handle Catalog

| Kotlin Type | C Handle / Type | Constructible by Caller? | Notes |
|---|---|---|---|
| `MultimodalContent` | `TPipe_ContentHandle` | Yes | Section 4 |
| `BinaryContent.Bytes` | `TPipe_BinaryHandle` | Yes | Discriminated by `getType()` |
| `BinaryContent.Base64String` | `TPipe_BinaryHandle` | Yes | Same handle type |
| `BinaryContent.CloudReference` | `TPipe_BinaryHandle` | Yes | Same handle type |
| `BinaryContent.TextDocument` | `TPipe_BinaryHandle` | Yes | Same handle type |
| `ContextWindow` | `TPipe_ContextHandle` | Yes | Section 6 |
| `MiniBank` | `TPipe_MiniBankHandle` | Yes | Section 6 |
| `ConverseHistory` | `TPipe_ConverseHistoryHandle` | Yes | Section 6 |
| `ConverseData` | (implicit in History) | No | Accessed via History API |
| `TokenBudgetSettings` | `TPipe_TokenBudgetHandle` | Yes | Section 8 |
| `TruncationSettings` | (sub-config) | No | Accessed via TokenBudget API |
| `TokenUsage` | `TPipe_TokenUsage` (struct) | Output only | Section 14 |
| `PipeSettings` | `TPipe_PipeSettingsHandle` | Yes | Section 9 |
| `LoreBook` | `TPipe_LoreBookHandle` | Yes | Section 7 |
| `PipeError` | `TPipe_ErrorHandle` | Yes | Section 11 |
| `PcPRequest` | `TPipe_PCPHandle` | Yes | Section 12 |
| `StdioContextOptions` | `TPipe_StdioContextHandle` | Yes | Section 12 |
| `HttpContextOptions` | `TPipe_HttpContextHandle` | Yes | (not detailed in this spec) |
| `P2PTransport` | `TPipe_P2PTransportHandle` | Yes | Section 13 |
| `P2PDescriptor` | `TPipe_P2PDescriptorHandle` | Yes | Section 13 |
| `P2PRequirements` | `TPipe_P2PRequirementsHandle` | Yes | (not detailed in this spec) |
| `List<T>` | `TPipe_ListHandle` | Yes | Section 10 |
| `Map<K,V>` | `TPipe_MapHandle` | Yes | Section 10 |
| `String` | `const char*` | N/A | Owned by TPipe |
| `Int` (non-nullable) | `int32_t` | N/A | Direct return |
| `Int?` (nullable) | via `out` param | N/A | Out-param + TPipe_Result |
| `Double` (non-nullable) | `double` | N/A | Direct return |
| `Double?` (nullable) | via `out` param | N/A | Out-param + TPipe_Result |
| `Boolean` | `int` | N/A | 0=false, 1=true |
| `Int64` | `int64_t` | N/A | Timestamps, sizes |
| `enum class` | C `typedef enum` | N/A | Sections 3.1–3.6 |

---

## 16. Next Steps

- [x] graalvm-abi-overview.md — architecture and scope
- [x] graalvm-abi-initialization.md — init/shutdown contract
- [x] graalvm-abi-core-types.md — type system and data types (this document)
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