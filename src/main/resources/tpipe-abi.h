/**
 * @file tpipe-abi.h
 * @brief TPipe C ABI Header - GraalVM Native Image Shared Library Interface
 *
 * This header defines the C ABI surface for TPipe, enabling cross-language
 * interop via GraalVM Native Image shared library. All functions return
 * 0 on success, negative error code on failure.
 *
 * @version 1.0
 * @date 2026-05-16
 */
#ifndef TPIPE_ABI_H
#define TPIPE_ABI_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*==============================================================================
 * VERSION AND ABI COMPATIBILITY
 *============================================================================*/

/** ABI Version - bump on breaking changes */
#define TPIPE_COMPATIBLE_ABI_VERSION 1

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
 * LIBRARY STATE (TPipe_GetState)
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

/** Handle for MultimodalContent */
typedef uint64_t TPipe_ContentHandle;

/** Handle for BinaryContent (4 variants: bytes/base64/cloudRef/textDoc) */
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
 * 8 PHANTOM FUNCTIONS (Bootstrap - from plan Task 3)
 *============================================================================*/

/**
 * @brief Initialize the TPipe library
 * @return 0 on success, negative error code on failure
 */
int TPipe_init(void);

/**
 * @brief Shutdown the TPipe library
 * @return 0 on success, negative error code on failure
 */
int TPipe_shutdown(void);

/**
 * @brief Get the current library state
 * @return Current state (TPIPE_STATE_*)
 */
int TPipe_getState(void);

/**
 * @brief Increment reference count of a handle
 * @param handle Handle to add reference to
 * @return 0 on success, negative error code on failure
 */
int TPipe_Handle_addRef(TPipe_Handle handle);

/**
 * @brief Release a handle (decrement reference count)
 * @param handle Handle to release
 * @return 0 on success, negative error code on failure
 */
int TPipe_Handle_release(TPipe_Handle handle);

/**
 * @brief Get current reference count of a handle
 * @param handle Handle to query
 * @param refCount Output: current reference count
 * @return 0 on success, negative error code on failure
 */
int TPipe_Handle_getRefCount(TPipe_Handle handle, int* refCount);

/**
 * @brief Check if a handle is valid
 * @param handle Handle to validate
 * @return 0 if valid, negative error code if invalid
 */
int TPipe_Handle_isValid(TPipe_Handle handle);

/**
 * @brief Get library capabilities
 * @param capabilities Output array for capability flags
 * @param capabilitiesSize Size of the capabilities array
 * @return 0 on success, negative error code on failure
 */
int TPipe_getCapabilities(int* capabilities, int capabilitiesSize);

/*==============================================================================
 * ERROR REPORTING FUNCTIONS
 *============================================================================*/

/**
 * @brief Get the last error message from the library
 * @param buffer Buffer to store error message
 * @param bufferSize Size of the buffer
 * @return 0 on success, negative error code on failure
 */
int TPipe_getLastError(char* buffer, int bufferSize);

/**
 * @brief Get the last error message for a specific handle
 * @param handle Handle to get error for
 * @param buffer Buffer to store error message
 * @param bufferSize Size of the buffer
 * @return 0 on success, negative error code on failure
 */
int TPipe_Handle_getLastError(TPipe_Handle handle, char* buffer, int bufferSize);

/*==============================================================================
 * VERSION AND STATS FUNCTIONS
 *============================================================================*/

/**
 * @brief Get the library version
 * @param major Output: major version number
 * @param minor Output: minor version number
 * @param patch Output: patch version number
 * @return 0 on success, negative error code on failure
 */
int TPipe_getVersion(int* major, int* minor, int* patch);

/**
 * @brief Get memory statistics
 * @param statsStruct Pointer to stats structure to fill
 * @return 0 on success, negative error code on failure
 */
int TPipe_getMemoryStats(void* statsStruct);

/*==============================================================================
 * CONTENT HANDLE FUNCTIONS
 *============================================================================*/

/**
 * @brief Create a content handle from text
 * @param text Text content (UTF-8)
 * @return Content handle, or 0 on failure
 */
TPipe_Handle TPipe_ContentHandle_create(const char* text);

/**
 * @brief Set the terminate flag on content
 * @param handle Content handle
 * @param value terminate flag value (0 or 1)
 * @return 0 on success, negative error code on failure
 */
int TPipe_ContentHandle_setTerminate(TPipe_ContentHandle handle, int value);

/**
 * @brief Get the terminate flag from content
 * @param handle Content handle
 * @param value Output: terminate flag value
 * @return 0 on success, negative error code on failure
 */
int TPipe_ContentHandle_getTerminate(TPipe_ContentHandle handle, int* value);

/**
 * @brief Set the repeat flag on content
 * @param handle Content handle
 * @param value repeat flag value (0 or 1)
 * @return 0 on success, negative error code on failure
 */
int TPipe_ContentHandle_setRepeat(TPipe_ContentHandle handle, int value);

/**
 * @brief Get the repeat flag from content
 * @param handle Content handle
 * @param value Output: repeat flag value
 * @return 0 on success, negative error code on failure
 */
int TPipe_ContentHandle_getRepeat(TPipe_ContentHandle handle, int* value);

/**
 * @brief Set the pass flag on content
 * @param handle Content handle
 * @param value pass flag value (0 or 1)
 * @return 0 on success, negative error code on failure
 */
int TPipe_ContentHandle_setPass(TPipe_ContentHandle handle, int value);

/**
 * @brief Get the pass flag from content
 * @param handle Content handle
 * @param value Output: pass flag value
 * @return 0 on success, negative error code on failure
 */
int TPipe_ContentHandle_getPass(TPipe_ContentHandle handle, int* value);

/**
 * @brief Set the skip flag on content
 * @param handle Content handle
 * @param value skip flag value (0 or 1)
 * @return 0 on success, negative error code on failure
 */
int TPipe_ContentHandle_setSkip(TPipe_ContentHandle handle, int value);

/**
 * @brief Get the skip flag from content
 * @param handle Content handle
 * @param value Output: skip flag value
 * @return 0 on success, negative error code on failure
 */
int TPipe_ContentHandle_getSkip(TPipe_ContentHandle handle, int* value);

/**
 * @brief Set the jump flag on content
 * @param handle Content handle
 * @param value jump flag value (0 or 1)
 * @return 0 on success, negative error code on failure
 */
int TPipe_ContentHandle_setJump(TPipe_ContentHandle handle, int value);

/**
 * @brief Get the jump flag from content
 * @param handle Content handle
 * @param value Output: jump flag value
 * @return 0 on success, negative error code on failure
 */
int TPipe_ContentHandle_getJump(TPipe_ContentHandle handle, int* value);

/**
 * @brief Add binary content to a content handle
 * @param handle Content handle to add binary to
 * @param binary Binary handle to add
 * @return 0 on success, negative error code on failure
 */
int TPipe_ContentHandle_addBinary(TPipe_ContentHandle handle, TPipe_BinaryHandle binary);

/*==============================================================================
 * BINARY HANDLE FUNCTIONS
 *============================================================================*/

/**
 * @brief Create a binary handle from raw bytes
 * @param data Pointer to byte data
 * @param length Length of byte data
 * @return Binary handle, or 0 on failure
 */
TPipe_Handle TPipe_BinaryHandle_createBytes(const uint8_t* data, int length);

/**
 * @brief Create a binary handle from base64 encoded data
 * @param base64Data Base64 encoded string
 * @return Binary handle, or 0 on failure
 */
TPipe_Handle TPipe_BinaryHandle_createBase64(const char* base64Data);

/**
 * @brief Create a binary handle from a cloud reference
 * @param ref Cloud reference string (URI, S3 path, etc.)
 * @return Binary handle, or 0 on failure
 */
TPipe_Handle TPipe_BinaryHandle_createCloudRef(const char* ref);

/**
 * @brief Create a binary handle from a text document reference
 * @param docRef Document reference string
 * @return Binary handle, or 0 on failure
 */
TPipe_Handle TPipe_BinaryHandle_createTextDoc(const char* docRef);

/**
 * @brief Get the variant type of a binary handle
 * @param handle Binary handle
 * @param variant Output: variant type (TPipe_BinaryVariant)
 * @return 0 on success, negative error code on failure
 */
int TPipe_BinaryHandle_getVariant(TPipe_BinaryHandle handle, int* variant);

/**
 * @brief Get the raw bytes from a binary handle
 * @param handle Binary handle
 * @param data Output: pointer to byte data
 * @param length Output: length of byte data
 * @return 0 on success, negative error code on failure
 */
int TPipe_BinaryHandle_getBytes(TPipe_BinaryHandle handle, const uint8_t** data, int* length);

/*==============================================================================
 * PIPE API FUNCTIONS
 *============================================================================*/

/**
 * @brief Execute a synchronous pipe operation
 * @param pipe Pipe handle
 * @param content Input content handle
 * @param settings Pipe settings handle (can be 0 for defaults)
 * @param result Output: result content handle
 * @return Operation handle, or 0 on failure
 */
TPipe_Handle TPipe_Pipe_execute(TPipe_PipeHandle pipe,
                                  TPipe_ContentHandle content,
                                  TPipe_PipeSettingsHandle settings,
                                  TPipe_ContentHandle* result);

/**
 * @brief Execute an asynchronous pipe operation
 * @param pipe Pipe handle
 * @param content Input content handle
 * @param settings Pipe settings handle (can be 0 for defaults)
 * @return Async handle, or 0 on failure
 */
TPipe_Handle TPipe_Pipe_executeAsync(TPipe_PipeHandle pipe,
                                      TPipe_ContentHandle content,
                                      TPipe_PipeSettingsHandle settings);

/*==============================================================================
 * ASYNC HANDLE FUNCTIONS
 *============================================================================*/

/**
 * @brief Poll the status of an async operation
 * @param handle Async handle
 * @param status Output: operation status (TPIPE_OPERATION_*)
 * @return 0 on success, negative error code on failure
 */
int TPipe_AsyncHandle_poll(TPipe_AsyncHandle handle, int* status);

/**
 * @brief Get the result of a completed async operation
 * @param handle Async handle
 * @param result Output: result content handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_AsyncHandle_getResult(TPipe_AsyncHandle handle, TPipe_ContentHandle* result);

/**
 * @brief Cancel an ongoing async operation
 * @param handle Async handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_AsyncHandle_cancel(TPipe_AsyncHandle handle);

/*==============================================================================
 * PIPE SETTINGS FUNCTIONS
 *============================================================================*/

/**
 * @brief Create a new pipe settings handle
 * @return Pipe settings handle, or 0 on failure
 */
TPipe_Handle TPipe_PipeSettings_create(void);

/**
 * @brief Set the model on pipe settings
 * @param settings Pipe settings handle
 * @param model Model identifier string
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setModel(TPipe_PipeSettingsHandle settings, const char* model);

/**
 * @brief Set the temperature on pipe settings
 * @param settings Pipe settings handle
 * @param temperature Temperature value (0.0 - 2.0)
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setTemperature(TPipe_PipeSettingsHandle settings, float temperature);

/**
 * @brief Set the max tokens on pipe settings
 * @param settings Pipe settings handle
 * @param maxTokens Maximum tokens value
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setMaxTokens(TPipe_PipeSettingsHandle settings, int maxTokens);

/**
 * @brief Set the timeout on pipe settings (in milliseconds)
 * @param settings Pipe settings handle
 * @param timeoutMs Timeout in milliseconds
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setTimeout(TPipe_PipeSettingsHandle settings, int timeoutMs);

/**
 * @brief Set the provider on pipe settings
 * @param settings Pipe settings handle
 * @param provider Provider name (TPipe_ProviderName)
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setProvider(TPipe_PipeSettingsHandle settings, int provider);

/**
 * @brief Set a string parameter on pipe settings
 * @param settings Pipe settings handle
 * @param key Parameter key
 * @param value Parameter value string
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setString(TPipe_PipeSettingsHandle settings, const char* key, const char* value);

/**
 * @brief Set an int parameter on pipe settings
 * @param settings Pipe settings handle
 * @param key Parameter key
 * @param value Parameter value
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setInt(TPipe_PipeSettingsHandle settings, const char* key, int value);

/**
 * @brief Set a float parameter on pipe settings
 * @param settings Pipe settings handle
 * @param key Parameter key
 * @param value Parameter value
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setFloat(TPipe_PipeSettingsHandle settings, const char* key, float value);

/**
 * @brief Set a bool parameter on pipe settings
 * @param settings Pipe settings handle
 * @param key Parameter key
 * @param value Parameter value
 * @return 0 on success, negative error code on failure
 */
int TPipe_PipeSettings_setBool(TPipe_PipeSettingsHandle settings, const char* key, int value);

/*==============================================================================
 * PIPELINE API FUNCTIONS
 *============================================================================*/

/**
 * @brief Create a pipeline from JSON configuration
 * @param configJson JSON configuration string
 * @return Pipeline handle, or 0 on failure
 */
TPipe_Handle TPipe_Pipeline_create(const char* configJson);

/**
 * @brief Execute a pipeline
 * @param pipeline Pipeline handle
 * @param content Input content handle
 * @param result Output: result content handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipeline_execute(TPipe_PipelineHandle pipeline,
                           TPipe_ContentHandle content,
                           TPipe_ContentHandle* result);

/**
 * @brief Get the outcome of a pipeline as JSON
 * @param pipeline Pipeline handle
 * @param outcomeJson Buffer for outcome JSON
 * @param outcomeJsonSize Size of outcome buffer
 * @return 0 on success, negative error code on failure
 */
int TPipe_Pipeline_getOutcome(TPipe_PipelineHandle pipeline,
                               char* outcomeJson,
                               int outcomeJsonSize);

/*==============================================================================
 * CONTEXT API FUNCTIONS
 *============================================================================*/

/**
 * @brief Create a new context handle
 * @return Context handle, or 0 on failure
 */
TPipe_Handle TPipe_Context_create(void);

/**
 * @brief Create a mini bank with token budget
 * @param tokenBudget Maximum tokens for context
 * @return MiniBank handle, or 0 on failure
 */
TPipe_Handle TPipe_MiniBank_create(int tokenBudget);

/**
 * @brief Create a lore book with name
 * @param name Lore book name
 * @return LoreBook handle, or 0 on failure
 */
TPipe_Handle TPipe_LoreBook_create(const char* name);

/**
 * @brief Add an entry to a lore book
 * @param loreBook LoreBook handle
 * @param key Entry key
 * @param value Entry value
 * @return 0 on success, negative error code on failure
 */
int TPipe_LoreBook_addEntry(TPipe_LoreBookHandle loreBook, const char* key, const char* value);

/**
 * @brief Create a conversation history
 * @return ConverseHistory handle, or 0 on failure
 */
TPipe_Handle TPipe_ConverseHistory_create(void);

/**
 * @brief Add a message to conversation history
 * @param history ConverseHistory handle
 * @param role Message role (TPipe_ConverseRole)
 * @param content Message content
 * @return 0 on success, negative error code on failure
 */
int TPipe_ConverseHistory_add(TPipe_ConverseHistoryHandle history, TPipe_ConverseRole role, const char* content);

/*==============================================================================
 * PCP API FUNCTIONS (Pipe Context Protocol)
 *============================================================================*/

/**
 * @brief Create a PCP handle
 * @return PCP handle, or 0 on failure
 */
TPipe_Handle TPipe_PCPHandle_create(void);

/**
 * @brief Execute a PCP request
 * @param pcp PCP handle
 * @param requestJson Request JSON string
 * @param responseJson Buffer for response JSON
 * @param responseJsonSize Size of response buffer
 * @return 0 on success, negative error code on failure
 */
int TPipe_PCPHandle_execute(TPipe_PCPHandle pcp,
                            const char* requestJson,
                            char* responseJson,
                            int responseJsonSize);

/*==============================================================================
 * P2P API FUNCTIONS (Peer-to-Peer)
 *============================================================================*/

/**
 * @brief Create a P2P handle
 * @return P2P handle, or 0 on failure
 */
TPipe_Handle TPipe_P2PHandle_create(void);

/**
 * @brief Register an agent with P2P
 * @param p2p P2P handle
 * @param agentId Agent identifier
 * @param metadata Agent metadata JSON
 * @return 0 on success, negative error code on failure
 */
int TPipe_P2PHandle_registerAgent(TPipe_P2PHandle p2p,
                                   const char* agentId,
                                   const char* metadata);

/**
 * @brief Connect to a peer agent
 * @param p2p P2P handle
 * @param peerId Peer agent identifier
 * @return 0 on success, negative error code on failure
 */
int TPipe_P2PHandle_connect(TPipe_P2PHandle p2p, const char* peerId);

/**
 * @brief Send a message to a peer
 * @param p2p P2P handle
 * @param peerId Peer agent identifier
 * @param message Message content
 * @param response Output: response content handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_P2PHandle_send(TPipe_P2PHandle p2p,
                          const char* peerId,
                          TPipe_ContentHandle message,
                          TPipe_ContentHandle* response);

/*==============================================================================
 * LIST COLLECTION FUNCTIONS
 *============================================================================*/

/**
 * @brief Create a new list handle
 * @return List handle, or 0 on failure
 */
TPipe_Handle TPipe_List_create(void);

/**
 * @brief Append an item to a list
 * @param list List handle
 * @param item Item handle to append
 * @return 0 on success, negative error code on failure
 */
int TPipe_List_append(TPipe_ListHandle list, TPipe_Handle item);

/**
 * @brief Get an item from a list by index
 * @param list List handle
 * @param index Item index (0-based)
 * @param item Output: item handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_List_get(TPipe_ListHandle list, int index, TPipe_Handle* item);

/**
 * @brief Get the size of a list
 * @param list List handle
 * @param size Output: list size
 * @return 0 on success, negative error code on failure
 */
int TPipe_List_size(TPipe_ListHandle list, int* size);

/*==============================================================================
 * MAP COLLECTION FUNCTIONS
 *============================================================================*/

/**
 * @brief Create a new map handle
 * @return Map handle, or 0 on failure
 */
TPipe_Handle TPipe_Map_create(void);

/**
 * @brief Set a key-value pair in a map
 * @param map Map handle
 * @param key Key string
 * @param value Value handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_Map_set(TPipe_MapHandle map, const char* key, TPipe_Handle value);

/**
 * @brief Get a value from a map by key
 * @param map Map handle
 * @param key Key string
 * @param value Output: value handle
 * @return 0 on success, negative error code on failure
 */
int TPipe_Map_get(TPipe_MapHandle map, const char* key, TPipe_Handle* value);

/**
 * @brief Check if a key exists in a map
 * @param map Map handle
 * @param key Key string
 * @param has Output: 1 if key exists, 0 otherwise
 * @return 0 on success, negative error code on failure
 */
int TPipe_Map_has(TPipe_MapHandle map, const char* key, int* has);

#ifdef __cplusplus
}
#endif

#endif /* TPIPE_ABI_H */
