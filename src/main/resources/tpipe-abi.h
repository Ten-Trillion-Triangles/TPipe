/**
 * @file tpipe-abi.h
 * @brief TPipe C ABI Header - GraalVM Native Image Shared Library Interface
 *
 * This header defines the C ABI surface for TPipe, enabling cross-language
 * interop via GraalVM Native Image shared library. All functions return
 * 0 on success, negative error code on failure.
 *
 * ============================================================================
 * OPTION A: graal_isolatethread_t* THREADED ENTRY POINTS
 * ============================================================================
 *
 * Every API function takes `graal_isolatethread_t* thread` as the FIRST
 * parameter. This matches the GraalVM @CEntryPoint calling convention where
 * the IsolateThread is automatically populated by the native image runtime
 * and passed across the JNI boundary.
 *
 * Usage pattern:
 *
 *     #include "tpipe-abi.h"
 *     #include "graal_isolate.h"
 *
 *     graal_isolate_t*     isolate = NULL;
 *     graal_isolatethread_t* thread = NULL;
 *     if (graal_create_isolate(NULL, &isolate, &thread) != 0) {
 *         fprintf(stderr, "Failed to create isolate\n");
 *         return 1;
 *     }
 *
 *     int rc = TPipe_init(thread);
 *     // ... use other TPipe_* functions ...
 *     TPipe_shutdown(thread);
 *
 *     graal_detach_thread(thread);
 *     graal_tear_down_isolate(thread);
 *
 * The C caller is responsible for creating the isolate, attaching threads,
 * and tearing down the isolate when done. The TPipe library does not
 * manage isolate lifecycle.
 *
 * ============================================================================
 *
 * @version 2.0 (Option A)
 * @date 2026-06-03
 */
#ifndef TPIPE_ABI_H
#define TPIPE_ABI_H

#include <stdbool.h>
#include <stdint.h>

/*
 * GraalVM Native Image types. Pulled in via the SDK header generated during
 * the nativeCompile build step. This brings in:
 *   - graal_isolate_t
 *   - graal_isolatethread_t
 *   - graal_create_isolate_params_t
 *   - graal_create_isolate / graal_attach_thread / graal_detach_thread
 *   - graal_tear_down_isolate / graal_detach_all_threads_and_tear_down_isolate
 */
#ifdef __has_include
  #if __has_include("graal_isolate.h")
    #include "graal_isolate.h"
  #else
    /* Forward declarations for consumers that don't have graal_isolate.h.
       These match the GraalVM SDK 24.x ABI. */
    struct __graal_isolate_t;
    typedef struct __graal_isolate_t graal_isolate_t;
    struct __graal_isolatethread_t;
    typedef struct __graal_isolatethread_t graal_isolatethread_t;
    typedef unsigned long __graal_uword;
    enum { __graal_create_isolate_params_version = 4 };
    struct __graal_create_isolate_params_t {
        int version;
        __graal_uword reserved_address_space_size;
    };
    typedef struct __graal_create_isolate_params_t graal_create_isolate_params_t;
    int graal_create_isolate(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**);
    int graal_attach_thread(graal_isolate_t*, graal_isolatethread_t**);
    int graal_detach_thread(graal_isolatethread_t*);
    int graal_tear_down_isolate(graal_isolatethread_t*);
    int graal_detach_all_threads_and_tear_down_isolate(graal_isolatethread_t*);
  #endif
#else
  /* No __has_include — assume consumer has graal_isolate.h. */
  #include "graal_isolate.h"
#endif

#ifdef __cplusplus
extern "C" {
#endif

/*==============================================================================
 * VERSION AND ABI COMPATIBILITY
 *============================================================================*/

/** ABI Version - bump on breaking changes */
#define TPIPE_COMPATIBLE_ABI_VERSION 2

/*==============================================================================
 * CONSTANTS AND LIMITS
 *============================================================================*/

/** Maximum string length (1MB) */
#define TPIPE_MAX_STRING_LEN 1048576

/** Maximum binary data size (100MB) - GAP-14 */
#define TPIPE_MAX_BINARY_SIZE 104857600

/** Maximum concurrent handles - GAP-13 */
#define TPIPE_MAX_HANDLE_COUNT 65536

/** Maximum reference count per handle - GAP-12 */
#define TPIPE_MAX_REFCOUNT 65535

/*==============================================================================
 * ERROR CODES (from Error Handling Contract)
 *============================================================================*/

/** Internal library error */
#define TPIPE_ERR_INTERNAL            -0x01
/** Library not initialized */
#define TPIPE_ERR_NOT_INITIALIZED     -0x02
/** Invalid handle provided */
#define TPIPE_ERR_INVALID_HANDLE      -0x03
/** Invalid argument value */
#define TPIPE_ERR_INVALID_ARGUMENT    -0x04
/** Out of memory */
#define TPIPE_ERR_OUT_OF_MEMORY       -0x0B
/** Empty content provided */
#define TPIPE_ERR_EMPTY_CONTENT       -0x15
/** Handle limit exceeded */
#define TPIPE_ERR_HANDLE_LIMIT        -0x16
/** Reference count overflow */
#define TPIPE_ERR_REFCOUNT_OVERFLOW   -0x17
/** Shutdown rejected */
#define TPIPE_ERR_SHUTDOWN_REJECTED   -0x1A
/** Already initialized */
#define TPIPE_ERR_ALREADY_INITIALIZED -0x1B
/** Operation cancelled - GAP-17 */
#define TPIPE_ERR_OPERATION_CANCELLED -0x1C
/** Binary payload too large */
#define TPIPE_ERR_BINARY_TOO_LARGE    -0x1D
/** String exceeds maximum length */
#define TPIPE_ERR_STRING_TOO_LONG     -0x1E

/*==============================================================================
 * OPERATION STATUS (from hostile review)
 *============================================================================*/

/** Operation is pending */
#define TPIPE_OPERATION_PENDING   0
/** Operation completed successfully */
#define TPIPE_OPERATION_COMPLETE  1
/** Operation failed */
#define TPIPE_OPERATION_FAILED    2

/*==============================================================================
 * LIBRARY STATE (TPipe_getState)
 *============================================================================*/

/** Library not initialized */
#define TPIPE_STATE_UNINITIALIZED  0
/** Library initializing */
#define TPIPE_STATE_INITIALIZING   1
/** Library ready for use */
#define TPIPE_STATE_READY           2
/** Library shutting down */
#define TPIPE_STATE_SHUTTING_DOWN  3
/** Library shut down */
#define TPIPE_STATE_SHUTDOWN        4

/*==============================================================================
 * HANDLE TYPES (uint64_t opaque handles)
 *============================================================================*/

/** Base handle type - all handles derive from this */
typedef uint64_t TPipe_Handle;

/** Handle for Content (multimodal) */
typedef uint64_t TPipe_ContentHandle;

/** Handle for Binary (4 variants: bytes/base64/cloudRef/textDoc) */
typedef uint64_t TPipe_BinaryHandle;

/** Handle for Pipe operations */
typedef uint64_t TPipe_PipeHandle;

/** Handle for Pipeline orchestration */
typedef uint64_t TPipe_PipelineHandle;

/** Handle for Context management */
typedef uint64_t TPipe_ContextHandle;

/** Handle for MiniBank (token budgeting) */
typedef uint64_t TPipe_MiniBankHandle;

/** Handle for LoreBook (knowledge entries) */
typedef uint64_t TPipe_LoreBookHandle;

/** Handle for ConverseHistory (conversation messages) */
typedef uint64_t TPipe_ConverseHistoryHandle;

/** Handle for PCP (Pipe Context Protocol) operations */
typedef uint64_t TPipe_PCPHandle;

/** Handle for P2P (peer-to-peer) operations */
typedef uint64_t TPipe_P2PHandle;

/** Handle for async operations */
typedef uint64_t TPipe_AsyncHandle;

/** Handle for List collections */
typedef uint64_t TPipe_ListHandle;

/** Handle for Map collections */
typedef uint64_t TPipe_MapHandle;

/** Handle for PipeSettings */
typedef uint64_t TPipe_PipeSettingsHandle;

/** Handle for generic operations */
typedef uint64_t TPipe_OperationHandle;

/*==============================================================================
 * ENUMERATIONS
 *============================================================================*/

/** Conversation role types */
typedef enum TPipe_ConverseRole {
    TPIPE_ROLE_USER = 0,
    TPIPE_ROLE_ASSISTANT = 1,
    TPIPE_ROLE_SYSTEM = 2,
    TPIPE_ROLE_TOOL = 3,
    TPIPE_ROLE_FUNCTION = 4,
    TPIPE_ROLE_VISUAL = 5
} TPipe_ConverseRole;

/** LLM Provider names */
typedef enum TPipe_ProviderName {
    TPIPE_PROVIDER_MINIMAX = 0,
    TPIPE_PROVIDER_OPENAI = 1,
    TPIPE_PROVIDER_ANTHROPIC = 2,
    TPIPE_PROVIDER_BEDROCK = 3,
    TPIPE_PROVIDER_OLLAMA = 4,
    TPIPE_PROVIDER_MISTRAL = 5,
    TPIPE_PROVIDER_GROQ = 6,
    TPIPE_PROVIDER_DEEPSEEK = 7,
    TPIPE_PROVIDER_TOGETHER = 8
} TPipe_ProviderName;

/** Prompt injection modes */
typedef enum TPipe_PromptMode {
    TPIPE_MODE_AUTO = 0,
    TPIPE_MODE_SYSTEM_ONLY = 1,
    TPIPE_MODE_NO_CONTEXT = 2,
    TPIPE_MODE_INJECT = 3
} TPipe_PromptMode;

/** Transport types */
typedef enum TPipe_Transport {
    TPIPE_TRANSPORT_STDIO = 0,
    TPIPE_TRANSPORT_HTTP = 1,
    TPIPE_TRANSPORT_WEBSOCKET = 2,
    TPIPE_TRANSPORT_GRPC = 3
} TPipe_Transport;

/** Permission flags */
typedef enum TPipe_Permissions {
    TPIPE_PERM_READ = (1 << 0),
    TPIPE_PERM_WRITE = (1 << 1),
    TPIPE_PERM_EXECUTE = (1 << 2)
} TPipe_Permissions;

/** Parameter types */
typedef enum TPipe_ParamType {
    TPIPE_TYPE_STRING = 0,
    TPIPE_TYPE_INT = 1,
    TPIPE_TYPE_FLOAT = 2,
    TPIPE_TYPE_BOOL = 3,
    TPIPE_TYPE_BINARY = 4,
    TPIPE_TYPE_LIST = 5,
    TPIPE_TYPE_MAP = 6
} TPipe_ParamType;

/** Trace event types */
typedef enum TPipe_TraceEventType {
    TPIPE_TRACE_ENTER = 0,
    TPIPE_TRACE_EXIT = 1,
    TPIPE_TRACE_ERROR = 2,
    TPIPE_TRACE_INFO = 3,
    TPIPE_TRACE_DEBUG = 4,
    TPIPE_TRACE_WARNING = 5
} TPipe_TraceEventType;

/** Storage modes */
typedef enum TPipe_StorageMode {
    TPIPE_STORAGE_MEMORY = 0,
    TPIPE_STORAGE_DISK = 1,
    TPIPE_STORAGE_DISTRIBUTED = 2
} TPipe_StorageMode;

/** Binary content variants */
typedef enum TPipe_BinaryVariant {
    TPIPE_BINARY_BYTES = 0,
    TPIPE_BINARY_BASE64 = 1,
    TPIPE_BINARY_CLOUD_REF = 2,
    TPIPE_BINARY_TEXT_DOC = 3
} TPipe_BinaryVariant;

/*==============================================================================
 * 8 PHANTOM FUNCTIONS (Library Lifecycle)
 *============================================================================*/

/**
 * @brief Initialize the TPipe library
 * @param thread Caller's IsolateThread (must be attached to an isolate)
 * @return 0 on success, negative error code on failure
 */
int TPipe_init(graal_isolatethread_t* thread);

/**
 * @brief Shutdown the TPipe library
 * @param thread Caller's IsolateThread
 * @return 0 on success, negative error code on failure
 */
int TPipe_shutdown(graal_isolatethread_t* thread);

/**
 * @brief Get the current library state
 * @param thread Caller's IsolateThread
 * @return Current state (TPIPE_STATE_*)
 */
int TPipe_getState(graal_isolatethread_t* thread);

/**
 * @brief Check if the library is initialized and ready
 * @param thread Caller's IsolateThread
 * @return 1 if ready, 0 otherwise
 */
int TPipe_isInitialized(graal_isolatethread_t* thread);

/**
 * @brief Increment reference count of a handle
 * @param thread Caller's IsolateThread
 * @param handle Handle to add reference to
 * @return 0 on success, negative error code on failure
 */
int TPipe_Handle_addRef(graal_isolatethread_t* thread, TPipe_Handle handle);

/**
 * @brief Release a handle (decrement reference count)
 * @param thread Caller's IsolateThread
 * @param handle Handle to release
 * @return 0 on success, negative error code on failure
 */
int TPipe_Handle_release(graal_isolatethread_t* thread, TPipe_Handle handle);

/**
 * @brief Get current reference count of a handle
 * @param thread Caller's IsolateThread
 * @param handle Handle to query
 * @param refCount Output: current reference count
 * @return 0 on success, negative error code on failure
 */
int TPipe_Handle_getRefCount(graal_isolatethread_t* thread, TPipe_Handle handle, int* refCount);

/**
 * @brief Check if a handle is valid
 * @param thread Caller's IsolateThread
 * @param handle Handle to validate
 * @return 0 if valid, negative error code if invalid
 */
int TPipe_Handle_isValid(graal_isolatethread_t* thread, TPipe_Handle handle);

/**
 * @brief Get library capabilities
 * @param thread Caller's IsolateThread
 * @param capabilities Output array for capability flags
 * @param capabilitiesSize Size of the capabilities array
 * @return 0 on success, negative error code on failure
 */
int TPipe_getCapabilities(graal_isolatethread_t* thread, int* capabilities, int capabilitiesSize);

/*==============================================================================
 * ERROR REPORTING FUNCTIONS
 *============================================================================*/

/**
 * @brief Get the last error message from the library
 * @param thread Caller's IsolateThread
 * @param buffer Buffer to store error message
 * @param bufferSize Size of the buffer
 * @return Length of error string on success, negative error code on failure
 */
int TPipe_getLastError(graal_isolatethread_t* thread, char* buffer, int bufferSize);

/*==============================================================================
 * VERSION FUNCTIONS
 *============================================================================*/

/**
 * @brief Get the library version
 * @param thread Caller's IsolateThread
 * @param buffer Output buffer for version string
 * @param bufferSize Size of the buffer
 * @return Length of the version string on success, negative error code on failure
 */
int TPipe_getVersion(graal_isolatethread_t* thread, char* buffer, int bufferSize);

/*==============================================================================
 * CONTENT HANDLE FUNCTIONS
 *============================================================================*/

/**
 * @brief Create a content handle from null-terminated text
 * @param thread Caller's IsolateThread
 * @param text Text content (UTF-8, null-terminated)
 * @return Content handle, or 0 on failure
 */
TPipe_Handle TPipe_Content_create(graal_isolatethread_t* thread, const char* text);

/**
 * @brief Create a content handle with explicit text length
 * @param thread Caller's IsolateThread
 * @param text Text content (UTF-8)
 * @param length Number of bytes in the text
 * @return Content handle, or 0 on failure
 */
TPipe_Handle TPipe_Content_createWithText(graal_isolatethread_t* thread, const char* text, int length);

/**
 * @brief Add binary content to a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle to add binary to
 * @param variant Binary variant (TPipe_BinaryVariant)
 * @param data Pointer to binary data
 * @param dataLen Length of binary data
 * @param mimeType MIME type string (may be NULL)
 * @param filename Filename string (may be NULL)
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_addBinary(graal_isolatethread_t* thread,
                            TPipe_ContentHandle contentHandle,
                            int variant,
                            const uint8_t* data,
                            int dataLen,
                            const char* mimeType,
                            const char* filename);

/**
 * @brief Free a result handle returned by TPipe_Pipe_execute
 * @param thread Caller's IsolateThread
 * @param operationHandle Operation handle to free
 * @return 0 on success, negative error code on failure
 */
int TPipe_Result_free(graal_isolatethread_t* thread, TPipe_Handle operationHandle);

/**
 * @brief Clone a content handle (creates a new handle with refcount=1)
 * @param thread Caller's IsolateThread
 * @param sourceHandle Source content handle
 * @return New content handle, or 0 on failure
 */
TPipe_Handle TPipe_Content_clone(graal_isolatethread_t* thread, TPipe_Handle sourceHandle);

/**
 * @brief Release a content handle (decrement refcount; frees if zero)
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle to release
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_release(graal_isolatethread_t* thread, TPipe_ContentHandle contentHandle);

/**
 * @brief Get the text content of a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param buffer Output buffer for text
 * @param bufferSize Size of the buffer
 * @return Length of text on success, negative error code on failure
 */
int TPipe_Content_getText(graal_isolatethread_t* thread,
                          TPipe_ContentHandle contentHandle,
                          char* buffer,
                          int bufferSize);

/**
 * @brief Set the text content of a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param text New text content (UTF-8)
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_setText(graal_isolatethread_t* thread,
                          TPipe_ContentHandle contentHandle,
                          const char* text);

/**
 * @brief Get the context (system prompt) of a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param buffer Output buffer for context
 * @param bufferSize Size of the buffer
 * @return Length of context on success, negative error code on failure
 */
int TPipe_Content_getContext(graal_isolatethread_t* thread,
                             TPipe_ContentHandle contentHandle,
                             char* buffer,
                             int bufferSize);

/**
 * @brief Get the MiniBank JSON of a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param buffer Output buffer for MiniBank JSON
 * @param bufferSize Size of the buffer
 * @return Length of JSON on success, negative error code on failure
 */
int TPipe_Content_getMiniBank(graal_isolatethread_t* thread,
                              TPipe_ContentHandle contentHandle,
                              char* buffer,
                              int bufferSize);

/**
 * @brief Set the MiniBank on a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param miniBank MiniBank JSON string
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_setMiniBank(graal_isolatethread_t* thread,
                              TPipe_ContentHandle contentHandle,
                              const char* miniBank);

/**
 * @brief Set the context (system prompt) on a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param context Context string
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_setContext(graal_isolatethread_t* thread,
                             TPipe_ContentHandle contentHandle,
                             const char* context);

/**
 * @brief Get a single binary attachment by index
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param index Binary index (0-based)
 * @param buffer Output buffer for binary metadata JSON
 * @param bufferSize Size of the buffer
 * @return Length of JSON on success, negative error code on failure
 */
int TPipe_Content_getBinary(graal_isolatethread_t* thread,
                            TPipe_ContentHandle contentHandle,
                            int index,
                            char* buffer,
                            int bufferSize);

/**
 * @brief Get all binary attachments as JSON array
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param buffer Output buffer for binaries JSON
 * @param bufferSize Size of the buffer
 * @return Length of JSON on success, negative error code on failure
 */
int TPipe_Content_getBinaries(graal_isolatethread_t* thread,
                              TPipe_ContentHandle contentHandle,
                              char* buffer,
                              int bufferSize);

/**
 * @brief Clear all binary attachments
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_clearBinary(graal_isolatethread_t* thread, TPipe_ContentHandle contentHandle);

/**
 * @brief Set the JumpTo target (pipe name) on a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param jumpTo Target pipe name
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_setJumpTo(graal_isolatethread_t* thread,
                            TPipe_ContentHandle contentHandle,
                            const char* jumpTo);

/**
 * @brief Clear the JumpTo target on a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_clearJumpTo(graal_isolatethread_t* thread, TPipe_ContentHandle contentHandle);

/**
 * @brief Get the JumpTo target from a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param buffer Output buffer for jump target
 * @param bufferSize Size of the buffer
 * @return Length on success, negative error code on failure
 */
int TPipe_Content_getJumpTo(graal_isolatethread_t* thread,
                            TPipe_ContentHandle contentHandle,
                            char* buffer,
                            int bufferSize);

/**
 * @brief Set the JumpTo target as a pipe reference
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param pipeName Target pipe name
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_setJumpToPipe(graal_isolatethread_t* thread,
                                TPipe_ContentHandle contentHandle,
                                const char* pipeName);

/**
 * @brief Set the terminate flag on a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param terminate 1 to terminate, 0 to clear
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_setTerminate(graal_isolatethread_t* thread,
                               TPipe_ContentHandle contentHandle,
                               int terminate);

/**
 * @brief Get the terminate flag from a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param value Output: terminate flag value
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_getTerminate(graal_isolatethread_t* thread,
                               TPipe_ContentHandle contentHandle,
                               int* value);

/**
 * @brief Set the pass flag on a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param pass 1 to pass, 0 to clear
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_setPass(graal_isolatethread_t* thread,
                          TPipe_ContentHandle contentHandle,
                          int pass);

/**
 * @brief Set the repeat flag on a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param repeat 1 to repeat, 0 to clear
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_setRepeat(graal_isolatethread_t* thread,
                            TPipe_ContentHandle contentHandle,
                            int repeat);

/**
 * @brief Set the skip-reasoning flag on a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param skip 1 to skip reasoning, 0 to allow
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_setSkipReasoning(graal_isolatethread_t* thread,
                                   TPipe_ContentHandle contentHandle,
                                   int skip);

/**
 * @brief Set the repeat-pipe name on a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param pipeName Target pipe name
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_setRepeatPipe(graal_isolatethread_t* thread,
                                TPipe_ContentHandle contentHandle,
                                const char* pipeName);

/**
 * @brief Clear the repeat flag on a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_clearRepeat(graal_isolatethread_t* thread, TPipe_ContentHandle contentHandle);

/**
 * @brief Get the repeat flag from a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param value Output: repeat flag value
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_getRepeat(graal_isolatethread_t* thread,
                            TPipe_ContentHandle contentHandle,
                            int* value);

/**
 * @brief Get the skip flag from a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param value Output: skip flag value
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_getSkip(graal_isolatethread_t* thread,
                          TPipe_ContentHandle contentHandle,
                          int* value);

/**
 * @brief Get the jump flag from a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param value Output: jump flag value
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_getJump(graal_isolatethread_t* thread,
                          TPipe_ContentHandle contentHandle,
                          int* value);

/**
 * @brief Set the jump flag on a content handle
 * @param thread Caller's IsolateThread
 * @param contentHandle Content handle
 * @param value jump flag value (0 or 1)
 * @return 0 on success, negative error code on failure
 */
int TPipe_Content_setJump(graal_isolatethread_t* thread,
                          TPipe_ContentHandle contentHandle,
                          int value);

/*==============================================================================
 * BINARY HANDLE FUNCTIONS
 *============================================================================*/

/**
 * @brief Create a binary handle
 * @param thread Caller's IsolateThread
 * @param variant Binary variant (TPipe_BinaryVariant)
 * @param data Pointer to binary data
 * @param dataLen Length of binary data
 * @param mimeType MIME type string (may be NULL)
 * @param filename Filename string (may be NULL)
 * @return Binary handle, or 0 on failure
 */
TPipe_Handle TPipe_Binary_create(graal_isolatethread_t* thread,
                                 int variant,
                                 const uint8_t* data,
                                 int dataLen,
                                 const char* mimeType,
                                 const char* filename);

/**
 * @brief Create an empty binary handle
 * @param thread Caller's IsolateThread
 * @return Binary handle, or 0 on failure
 */
TPipe_Handle TPipe_Binary_createEmpty(graal_isolatethread_t* thread);

/**
 * @brief Release a binary handle
 * @param thread Caller's IsolateThread
 * @param binaryHandle Binary handle to release
 * @return 0 on success, negative error code on failure
 */
int TPipe_Binary_release(graal_isolatethread_t* thread, TPipe_BinaryHandle binaryHandle);

/**
 * @brief Get the variant type of a binary handle
 * @param thread Caller's IsolateThread
 * @param binaryHandle Binary handle
 * @param variant Output: variant type (TPipe_BinaryVariant)
 * @return 0 on success, negative error code on failure
 */
int TPipe_Binary_getVariant(graal_isolatethread_t* thread,
                            TPipe_BinaryHandle binaryHandle,
                            int* variant);

/**
 * @brief Get the raw bytes pointer and length from a binary handle
 * @param thread Caller's IsolateThread
 * @param binaryHandle Binary handle
 * @param data Output: pointer to byte data
 * @param length Output: length of byte data
 * @return 0 on success, negative error code on failure
 */
int TPipe_Binary_getBytes(graal_isolatethread_t* thread,
                          TPipe_BinaryHandle binaryHandle,
                          const uint8_t** data,
                          int* length);

/*==============================================================================
 * PIPE API FUNCTIONS
 *============================================================================*/

/**
 * @brief Create a pipe handle from provider, model, region, and settings
 * @param thread Caller's IsolateThread
 * @param provider Provider enum (TPipe_ProviderName)
 * @param model Model identifier (null-terminated)
 * @param region Region string (may be NULL for default)
 * @param settings Settings handle (0 for defaults)
 * @return Pipe handle, or 0 on failure
 */
TPipe_Handle TPipe_Pipe_create(graal_isolatethread_t* thread,
                               int provider,
                               const char* model,
                               const char* region,
                               TPipe_Handle settings);

/**
 * @brief Set the provider on an existing pipe
 * @param thread Caller's IsolateThread
 * @param pipeHandle Pipe handle
 * @param provider Provider enum (TPipe_ProviderName)
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipe_setProvider(graal_isolatethread_t* thread,
                           TPipe_PipeHandle pipeHandle,
                           int provider);

/**
 * @brief Set the temperature on a pipe
 * @param thread Caller's IsolateThread
 * @param pipeHandle Pipe handle
 * @param temperature Temperature value (0.0 - 2.0)
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipe_setTemperature(graal_isolatethread_t* thread,
                               TPipe_PipeHandle pipeHandle,
                               float temperature);

/**
 * @brief Set the repetition penalty on a pipe
 * @param thread Caller's IsolateThread
 * @param pipeHandle Pipe handle
 * @param penalty Penalty value
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipe_setRepetitionPenalty(graal_isolatethread_t* thread,
                                    TPipe_PipeHandle pipeHandle,
                                    float penalty);

/**
 * @brief Set the reasoning token budget on a pipe
 * @param thread Caller's IsolateThread
 * @param pipeHandle Pipe handle
 * @param reasoningTokens Reasoning token budget
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipe_setReasoning(graal_isolatethread_t* thread,
                            TPipe_PipeHandle pipeHandle,
                            int reasoningTokens);

/**
 * @brief Initialize a pipe with content and context
 * @param thread Caller's IsolateThread
 * @param pipeHandle Pipe handle
 * @param contentHandle Content handle
 * @param contextHandle Context handle (0 for none)
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipe_init(graal_isolatethread_t* thread,
                    TPipe_PipeHandle pipeHandle,
                    TPipe_ContentHandle contentHandle,
                    TPipe_ContextHandle contextHandle);

/**
 * @brief Execute a pipe operation synchronously
 * @param thread Caller's IsolateThread
 * @param pipeHandle Pipe handle
 * @param contentHandle Input content handle
 * @param settings Settings handle (0 for defaults)
 * @param result Output: result content handle (out parameter)
 * @return Operation handle, or 0 on failure
 */
TPipe_Handle TPipe_Pipe_execute(graal_isolatethread_t* thread,
                                TPipe_PipeHandle pipeHandle,
                                TPipe_ContentHandle contentHandle,
                                TPipe_PipeSettingsHandle settings,
                                TPipe_ContentHandle* result);

/**
 * @brief Execute a pipe operation asynchronously
 * @param thread Caller's IsolateThread
 * @param pipeHandle Pipe handle
 * @param contentHandle Input content handle
 * @param settings Settings handle (0 for defaults)
 * @return Async operation handle, or 0 on failure
 */
TPipe_Handle TPipe_Pipe_executeContentAsync(graal_isolatethread_t* thread,
                                            TPipe_PipeHandle pipeHandle,
                                            TPipe_ContentHandle contentHandle,
                                            TPipe_PipeSettingsHandle settings);

/**
 * @brief Get token usage from a pipe operation
 * @param thread Caller's IsolateThread
 * @param pipeHandle Pipe handle
 * @param inputTokens Output: input tokens for last call
 * @param outputTokens Output: output tokens for last call
 * @param totalInputTokens Output: cumulative input tokens
 * @param totalOutputTokens Output: cumulative output tokens
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipe_getTokenUsage(graal_isolatethread_t* thread,
                             TPipe_PipeHandle pipeHandle,
                             int* inputTokens,
                             int* outputTokens,
                             int* totalInputTokens,
                             int* totalOutputTokens);

/*==============================================================================
 * PIPE SETTINGS HANDLE (10 functions)
 *============================================================================*/

/**
 * @brief Create a new pipe settings handle
 * @param thread Caller's IsolateThread
 * @return PipeSettings handle, or 0 on failure
 */
TPipe_Handle TPipe_PipeSettings_create(graal_isolatethread_t* thread);

/**
 * @brief Set the model on pipe settings
 * @param thread Caller's IsolateThread
 * @param settings PipeSettings handle
 * @param model Model identifier string (UTF-8)
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setModel(graal_isolatethread_t* thread, TPipe_PipeSettingsHandle settings, const char* model);

/**
 * @brief Set the temperature on pipe settings (0.0 - 2.0)
 * @param thread Caller's IsolateThread
 * @param settings PipeSettings handle
 * @param temperature Temperature value
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setTemperature(graal_isolatethread_t* thread, TPipe_PipeSettingsHandle settings, float temperature);

/**
 * @brief Set the max tokens on pipe settings
 * @param thread Caller's IsolateThread
 * @param settings PipeSettings handle
 * @param maxTokens Maximum tokens value
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setMaxTokens(graal_isolatethread_t* thread, TPipe_PipeSettingsHandle settings, int maxTokens);

/**
 * @brief Set the timeout on pipe settings (milliseconds)
 * @param thread Caller's IsolateThread
 * @param settings PipeSettings handle
 * @param timeoutMs Timeout in milliseconds
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setTimeout(graal_isolatethread_t* thread, TPipe_PipeSettingsHandle settings, int timeoutMs);

/**
 * @brief Set the provider on pipe settings
 * @param thread Caller's IsolateThread
 * @param settings PipeSettings handle
 * @param provider Provider name (TPipe_ProviderName)
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setProvider(graal_isolatethread_t* thread, TPipe_PipeSettingsHandle settings, int provider);

/**
 * @brief Set a string parameter on pipe settings
 * @param thread Caller's IsolateThread
 * @param settings PipeSettings handle
 * @param key Parameter key (UTF-8)
 * @param value Parameter value string (UTF-8)
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setString(graal_isolatethread_t* thread, TPipe_PipeSettingsHandle settings, const char* key, const char* value);

/**
 * @brief Set an int parameter on pipe settings
 * @param thread Caller's IsolateThread
 * @param settings PipeSettings handle
 * @param key Parameter key (UTF-8)
 * @param value Parameter value
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setInt(graal_isolatethread_t* thread, TPipe_PipeSettingsHandle settings, const char* key, int value);

/**
 * @brief Set a float parameter on pipe settings
 * @param thread Caller's IsolateThread
 * @param settings PipeSettings handle
 * @param key Parameter key (UTF-8)
 * @param value Parameter value
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setFloat(graal_isolatethread_t* thread, TPipe_PipeSettingsHandle settings, const char* key, float value);

/**
 * @brief Set a bool parameter on pipe settings
 * @param thread Caller's IsolateThread
 * @param settings PipeSettings handle
 * @param key Parameter key (UTF-8)
 * @param value 0=false, non-zero=true
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setBool(graal_isolatethread_t* thread, TPipe_PipeSettingsHandle settings, const char* key, int value);

/*==============================================================================
 * PIPELINE API FUNCTIONS (7 functions)
 *============================================================================*/

/**
 * @brief Create a pipeline from JSON configuration
 * @param thread Caller's IsolateThread
 * @param configJson JSON configuration string (UTF-8)
 * @return Pipeline handle, or 0 on failure
 */
TPipe_Handle TPipe_Pipeline_create(graal_isolatethread_t* thread, const char* configJson);

/**
 * @brief Add a pipe to a pipeline
 * @param thread Caller's IsolateThread
 * @param pipeline Pipeline handle
 * @param pipe Pipe handle to add
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipeline_add(graal_isolatethread_t* thread, TPipe_PipelineHandle pipeline, TPipe_PipeHandle pipe);

/**
 * @brief Execute a pipeline
 * @param thread Caller's IsolateThread
 * @param pipeline Pipeline handle
 * @param content Input content handle
 * @param result Output: result content handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipeline_execute(graal_isolatethread_t* thread, TPipe_PipelineHandle pipeline, TPipe_ContentHandle content, TPipe_ContentHandle* result);

/**
 * @brief Get the outcome of a pipeline as JSON
 * @param thread Caller's IsolateThread
 * @param pipeline Pipeline handle
 * @param outcomeJson Output buffer for outcome JSON
 * @param outcomeJsonSize Size of outcome buffer
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipeline_getOutcome(graal_isolatethread_t* thread, TPipe_PipelineHandle pipeline, char* outcomeJson, int outcomeJsonSize);

/**
 * @brief Get the name of a pipeline
 * @param thread Caller's IsolateThread
 * @param pipeline Pipeline handle
 * @param nameBuf Output buffer for name string
 * @param nameBufSize Size of name buffer
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipeline_getName(graal_isolatethread_t* thread, TPipe_PipelineHandle pipeline, char* nameBuf, int nameBufSize);

/**
 * @brief Set the name of a pipeline
 * @param thread Caller's IsolateThread
 * @param pipeline Pipeline handle
 * @param name New name string (UTF-8)
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipeline_setName(graal_isolatethread_t* thread, TPipe_PipelineHandle pipeline, const char* name);

/**
 * @brief Get the context window handle of a pipeline
 * @param thread Caller's IsolateThread
 * @param pipeline Pipeline handle
 * @return Context handle, or 0 if none
 */
TPipe_Handle TPipe_Pipeline_getContextWindow(graal_isolatethread_t* thread, TPipe_PipelineHandle pipeline);

/**
 * @brief Get the mini bank handle of a pipeline
 * @param thread Caller's IsolateThread
 * @param pipeline Pipeline handle
 * @return MiniBank handle, or 0 if none
 */
TPipe_Handle TPipe_Pipeline_getMiniBank(graal_isolatethread_t* thread, TPipe_PipelineHandle pipeline);

/*==============================================================================
 * LOREBOOK AND CONVERSE HISTORY ADD FUNCTIONS (2 functions)
 *============================================================================*/

/**
 * @brief Add an entry to a lore book
 * @param thread Caller's IsolateThread
 * @param loreBook LoreBook handle
 * @param key Entry key (UTF-8)
 * @param value Entry value (UTF-8)
 * @return 0 on success, negative error code on failure
 */
int TPipe_LoreBook_addEntry(graal_isolatethread_t* thread, TPipe_LoreBookHandle loreBook, const char* key, const char* value);

/**
 * @brief Add a message to conversation history
 * @param thread Caller's IsolateThread
 * @param history ConverseHistory handle
 * @param role Message role (TPipe_ConverseRole)
 * @param content Message content (UTF-8)
 * @return 0 on success, negative error code on failure
 */
int TPipe_ConverseHistory_add(graal_isolatethread_t* thread, TPipe_ConverseHistoryHandle history, int role, const char* content);

/*==============================================================================
 * PCP API FUNCTIONS (1 function)
 *============================================================================*/

/**
 * @brief Execute a PCP request
 * @param thread Caller's IsolateThread
 * @param pcp PCP handle
 * @param requestJson Request JSON string (UTF-8)
 * @param responseJson Output buffer for response JSON
 * @param responseJsonSize Size of response buffer
 * @return 0 on success, negative error code on failure
 */
int TPipe_PCPHandle_execute(graal_isolatethread_t* thread, TPipe_PCPHandle pcp, const char* requestJson, char* responseJson, int responseJsonSize);

/**
 * @brief Create a PCP handle
 * @param thread Caller's IsolateThread
 * @return PCP handle, or 0 on failure
 */
TPipe_Handle TPipe_PCPHandle_create(graal_isolatethread_t* thread);

/*==============================================================================
 * P2P API FUNCTIONS (4 functions)
 *============================================================================*/

/**
 * @brief Create a P2P handle
 * @param thread Caller's IsolateThread
 * @return P2P handle, or 0 on failure
 */
TPipe_Handle TPipe_P2PHandle_create(graal_isolatethread_t* thread);

/**
 * @brief Register an agent with P2P
 * @param thread Caller's IsolateThread
 * @param p2p P2P handle
 * @param agentId Agent identifier (UTF-8)
 * @param metadata Agent metadata JSON (UTF-8)
 * @return 0 on success, negative error code on failure
 */
int TPipe_P2PHandle_registerAgent(graal_isolatethread_t* thread, TPipe_P2PHandle p2p, const char* agentId, const char* metadata);

/**
 * @brief Connect to a peer agent
 * @param thread Caller's IsolateThread
 * @param p2p P2P handle
 * @param peerId Peer agent identifier (UTF-8)
 * @return 0 on success, negative error code on failure
 */
int TPipe_P2PHandle_connect(graal_isolatethread_t* thread, TPipe_P2PHandle p2p, const char* peerId);

/**
 * @brief Send a message to a peer
 * @param thread Caller's IsolateThread
 * @param p2p P2P handle
 * @param peerId Peer agent identifier (UTF-8)
 * @param message Message content handle
 * @param response Output: response content handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_P2PHandle_send(graal_isolatethread_t* thread, TPipe_P2PHandle p2p, const char* peerId, TPipe_ContentHandle message, TPipe_ContentHandle* response);

/*==============================================================================
 * LIST COLLECTION FUNCTIONS (4 functions)
 *============================================================================*/

/**
 * @brief Create a new list handle
 * @param thread Caller's IsolateThread
 * @return List handle, or 0 on failure
 */
TPipe_Handle TPipe_List_create(graal_isolatethread_t* thread);

/**
 * @brief Append an item to a list
 * @param thread Caller's IsolateThread
 * @param list List handle
 * @param item Item handle to append
 * @return 0 on success, negative error code on failure
 */
int TPipe_List_append(graal_isolatethread_t* thread, TPipe_ListHandle list, TPipe_Handle item);

/**
 * @brief Get an item from a list by index
 * @param thread Caller's IsolateThread
 * @param list List handle
 * @param index Item index (0-based)
 * @param item Output: item handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_List_get(graal_isolatethread_t* thread, TPipe_ListHandle list, int index, TPipe_Handle* item);

/**
 * @brief Get the size of a list
 * @param thread Caller's IsolateThread
 * @param list List handle
 * @param size Output: list size
 * @return 0 on success, negative error code on failure
 */
int TPipe_List_size(graal_isolatethread_t* thread, TPipe_ListHandle list, int* size);

/*==============================================================================
 * MAP COLLECTION FUNCTIONS (4 functions)
 *============================================================================*/

/**
 * @brief Create a new map handle
 * @param thread Caller's IsolateThread
 * @return Map handle, or 0 on failure
 */
TPipe_Handle TPipe_Map_create(graal_isolatethread_t* thread);

/**
 * @brief Set a key-value pair in a map
 * @param thread Caller's IsolateThread
 * @param map Map handle
 * @param key Key string (UTF-8)
 * @param value Value handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_Map_set(graal_isolatethread_t* thread, TPipe_MapHandle map, const char* key, TPipe_Handle value);

/**
 * @brief Get a value from a map by key
 * @param thread Caller's IsolateThread
 * @param map Map handle
 * @param key Key string (UTF-8)
 * @param value Output: value handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_Map_get(graal_isolatethread_t* thread, TPipe_MapHandle map, const char* key, TPipe_Handle* value);

/**
 * @brief Check if a key exists in a map
 * @param thread Caller's IsolateThread
 * @param map Map handle
 * @param key Key string (UTF-8)
 * @param has Output: 1 if key exists, 0 otherwise
 * @return 0 on success, negative error code on failure
 */
int TPipe_Map_has(graal_isolatethread_t* thread, TPipe_MapHandle map, const char* key, int* has);

/*==============================================================================
 * ASYNC HANDLE FUNCTIONS (4 functions)
 *============================================================================*/

/**
 * @brief Create an async operation handle (typically returned by executeAsync)
 * @param thread Caller's IsolateThread
 * @return Async handle, or 0 on failure
 */
TPipe_Handle TPipe_AsyncHandle_create(graal_isolatethread_t* thread);

/**
 * @brief Poll the status of an async operation
 * @param thread Caller's IsolateThread
 * @param handle Async handle
 * @param status Output: operation status (TPIPE_OPERATION_*)
 * @return 0 on success, negative error code on failure
 */
int TPipe_AsyncHandle_poll(graal_isolatethread_t* thread, TPipe_AsyncHandle handle, int* status);

/**
 * @brief Get the result of a completed async operation
 * @param thread Caller's IsolateThread
 * @param handle Async handle
 * @param result Output: result content handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_AsyncHandle_getResult(graal_isolatethread_t* thread, TPipe_AsyncHandle handle, TPipe_ContentHandle* result);

/**
 * @brief Cancel an ongoing async operation
 * @param thread Caller's IsolateThread
 * @param handle Async handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_AsyncHandle_cancel(graal_isolatethread_t* thread, TPipe_AsyncHandle handle);

#ifdef __cplusplus
}
#endif

#endif /* TPIPE_ABI_H */
