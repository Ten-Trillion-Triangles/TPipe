# GraalVM Native ABI Specification — Handle Type Bindings

**Spec File:** graalvm-abi-handle-types.md  
**Version:** 0.2.0-draft  
**Created:** 2026-05-07  
**Status:** Draft

**Scope:** All remaining handle types requiring C ABI bindings. Organized by functional area.

---

## 1. Handle Type Summary

| Handle Type | Maps To | Implementation |
|-------------|---------|----------------|
| `TPipe_ContentHandle` | MultimodalContent | ☐ TODO |
| `TPipe_BinaryHandle` | BinaryContent | ☐ TODO |
| `TPipe_ContextHandle` | ContextWindow | ☐ TODO |
| `TPipe_MiniBankHandle` | MiniBank | ☐ TODO |
| `TPipe_PipeSettingsHandle` | PipeSettings | ☐ TODO |
| `TPipe_ConverseHistoryHandle` | ConverseHistory | ☐ TODO |
| `TPipe_TokenBudgetHandle` | TokenBudgetSettings | ☐ TODO |
| `TPipe_LoreBookHandle` | LoreBook | ☐ TODO |
| `TPipe_TraceConfigHandle` | TraceConfig | ☐ TODO |
| `TPipe_ErrorHandle` | PipeError | ☐ TODO |
| `TPipe_PCPHandle` | PCP module root | ☐ TODO |
| `TPipe_StdioContextHandle` | StdioContextOptions | ☐ TODO |
| `TPipe_HttpContextHandle` | HttpContextOptions | ☐ TODO |
| `TPipe_P2PTransportHandle` | P2PTransport | ☐ TODO |
| `TPipe_P2PDescriptorHandle` | P2PDescriptor | ☐ TODO |
| `TPipe_P2PRequirementsHandle` | P2PRequirements | ☐ TODO |
| `TPipe_P2PInterfaceHandle` | P2PInterface | ☐ INTERNAL |
| `TPipe_PcpExecutionResultHandle` | PCP execution result | ☐ Output-only |

---

## 2. Content Handle (MultimodalContent)

### 2.1 Create/Destroy

```c
// Create empty content handle
TPipe_ContentHandle TPipe_Content_create(void);

// Create with text
TPipe_ContentHandle TPipe_Content_createWithText(const char* text);

// Release content handle
TPipe_Result TPipe_Content_release(TPipe_ContentHandle content);
```

### 2.2 Text Operations

```c
// Set text content
TPipe_Result TPipe_Content_setText(TPipe_ContentHandle content, const char* text);

// Get text content (returns pointer to internal storage, do not free)
const char* TPipe_Content_getText(TPipe_ContentHandle content);

// Check if content has text
int TPipe_Content_hasText(TPipe_ContentHandle content);
```

### 2.3 Binary Content Operations

```c
// Add binary content from buffer
TPipe_Result TPipe_Content_addBinary(TPipe_ContentHandle content,
                                     const char* mimeType,
                                     const uint8_t* data,
                                     int dataLen);

// Get binary content count
int32_t TPipe_Content_getBinaryCount(TPipe_ContentHandle content);

// Get binary content at index (caller must copy)
TPipe_Result TPipe_Content_getBinary(TPipe_ContentHandle content,
                                     int32_t index,
                                     char* mimeTypeBuffer, int mimeTypeSize,
                                     uint8_t* dataBuffer, int dataSize,
                                     int* outDataLen);

// Clear all binary content
TPipe_Result TPipe_Content_clearBinary(TPipe_ContentHandle content);
```

### 2.4 Metadata

```c
// Set/clear repeat pipe flag
TPipe_Result TPipe_Content_setRepeatPipe(TPipe_ContentHandle content, int repeat);
int TPipe_Content_getRepeatPipe(TPipe_ContentHandle content);

// Set/clear jump target
TPipe_Result TPipe_Content_setJumpTo(TPipe_ContentHandle content, const char* pipeName);
const char* TPipe_Content_getJumpTo(TPipe_ContentHandle content);

// Set terminate flag
TPipe_Result TPipe_Content_setTerminate(TPipe_ContentHandle content, int terminate);
int TPipe_Content_getTerminate(TPipe_ContentHandle content);
```

---

## 3. Binary Handle (BinaryContent)

### 3.1 Create/Destroy

```c
// Create from buffer
TPipe_BinaryHandle TPipe_Binary_create(const char* mimeType,
                                       const uint8_t* data,
                                       int dataLen);

// Create empty
TPipe_BinaryHandle TPipe_Binary_createEmpty(void);

// Release
TPipe_Result TPipe_Binary_release(TPipe_BinaryHandle binary);
```

### 3.2 Accessors

```c
// Get MIME type (returns pointer, do not free)
const char* TPipe_Binary_getMimeType(TPipe_BinaryHandle binary);

// Get data pointer (returns pointer to internal storage, do not free)
const uint8_t* TPipe_Binary_getData(TPipe_BinaryHandle binary);

// Get data length
int TPipe_Binary_getDataLen(TPipe_BinaryHandle binary);

// Get filename (if set, NULL otherwise)
const char* TPipe_Binary_getFilename(TPipe_BinaryHandle binary);

// Get base64-encoded data (caller must copy)
TPipe_Result TPipe_Binary_getBase64(TPipe_BinaryHandle binary, char* buffer, int bufferSize);
```

---

## 4. Context Handle (ContextWindow)

### 4.1 Create/Destroy

```c
TPipe_ContextHandle TPipe_Context_create(void);
TPipe_Result TPipe_Context_release(TPipe_ContextHandle context);
```

### 4.2 LoreBook Operations

```c
// Add lorebook entry
TPipe_Result TPipe_Context_addLoreBookEntry(TPipe_ContextHandle context,
                                             const char* key,
                                             TPipe_LoreBookHandle loreBook);

// Get lorebook entry (returns handle, caller must release)
TPipe_LoreBookHandle TPipe_Context_findLoreBookEntry(TPipe_ContextHandle context,
                                                     const char* key);

// Find matching lorebook keys for text
TPipe_ListHandle TPipe_Context_findMatchingLoreBookKeys(TPipe_ContextHandle context,
                                                        const char* text);
// Caller must release the returned list

// Get context matching lorebook entries up to maxTokens
TPipe_Result TPipe_Context_selectLoreBookContext(TPipe_ContextHandle context,
                                                  const char* text,
                                                  int maxTokens,
                                                  char* buffer, int bufferSize);
```

### 4.3 Context Elements

```c
// Add context element
TPipe_Result TPipe_Context_addContextElement(TPipe_ContextHandle context,
                                             const char* element);

// Get context elements count
int32_t TPipe_Context_getContextElementCount(TPipe_ContextHandle context);

// Get context element at index
const char* TPipe_Context_getContextElement(TPipe_ContextHandle context, int32_t index);

// Clear context elements
TPipe_Result TPipe_Context_clearContextElements(TPipe_ContextHandle context);
```

### 4.4 ConverseHistory

```c
// Get converse history handle (caller must release)
TPipe_ConverseHistoryHandle TPipe_Context_getConverseHistory(TPipe_ContextHandle context);

// Set converse history from handle
TPipe_Result TPipe_Context_setConverseHistory(TPipe_ContextHandle context,
                                               TPipe_ConverseHistoryHandle history);
```

### 4.5 Truncation

```c
// Truncate context elements by token budget
TPipe_Result TPipe_Context_truncateContextElements(TPipe_ContextHandle context,
                                                   TPipe_TruncationSettingsHandle settings,
                                                   int maxTokens);

// Truncate converse history
TPipe_Result TPipe_Context_truncateConverseHistory(TPipe_ContextHandle context,
                                                    TPipe_TruncationSettingsHandle settings,
                                                    int maxTokens);
```

### 4.6 Utility

```c
// Check if context is empty
int TPipe_Context_isEmpty(TPipe_ContextHandle context);

// Clear all data
TPipe_Result TPipe_Context_clear(TPipe_ContextHandle context);

// Clean lorebook
TPipe_Result TPipe_Context_cleanLorebook(TPipe_ContextHandle context);

// Get version
int64_t TPipe_Context_getVersion(TPipe_ContextHandle context);
```

---

## 5. LoreBook Handle (LoreBook)

### 5.1 Create/Destroy

```c
// Create with key and value
TPipe_LoreBookHandle TPipe_LoreBook_create(const char* key, const char* value);

// Create with all fields
TPipe_LoreBookHandle TPipe_LoreBook_createFull(const char* key,
                                                const char* value,
                                                float weight,
                                                const char** linkedKeys,
                                                int linkedKeysCount,
                                                const char** aliasKeys,
                                                int aliasKeysCount);

// Release
TPipe_Result TPipe_LoreBook_release(TPipe_LoreBookHandle loreBook);
```

### 5.2 Accessors

```c
const char* TPipe_LoreBook_getKey(TPipe_LoreBookHandle loreBook);
const char* TPipe_LoreBook_getValue(TPipe_LoreBookHandle loreBook);
float TPipe_LoreBook_getWeight(TPipe_LoreBookHandle loreBook);

// Get linked keys count
int32_t TPipe_LoreBook_getLinkedKeysCount(TPipe_LoreBookHandle loreBook);
// Get linked key at index
const char* TPipe_LoreBook_getLinkedKey(TPipe_LoreBookHandle loreBook, int32_t index);

// Get alias keys count
int32_t TPipe_LoreBook_getAliasKeysCount(TPipe_LoreBookHandle loreBook);
// Get alias key at index
const char* TPipe_LoreBook_getAliasKey(TPipe_LoreBookHandle loreBook, int32_t index);
```

### 5.3 Mutators

```c
TPipe_Result TPipe_LoreBook_setWeight(TPipe_LoreBookHandle loreBook, float weight);
TPipe_Result TPipe_LoreBook_setValue(TPipe_LoreBookHandle loreBook, const char* value);

// Add linked key
TPipe_Result TPipe_LoreBook_addLinkedKey(TPipe_LoreBookHandle loreBook, const char* key);

// Add alias key
TPipe_Result TPipe_LoreBook_addAliasKey(TPipe_LoreBookHandle loreBook, const char* key);

// Combine with another lorebook (returns new handle, caller must release)
TPipe_LoreBookHandle TPipe_LoreBook_combine(TPipe_LoreBookHandle loreBook,
                                             TPipe_LoreBookHandle other);
```

---

## 6. ConverseHistory Handle

### 6.1 Create/Destroy

```c
TPipe_ConverseHistoryHandle TPipe_ConverseHistory_create(void);
TPipe_Result TPipe_ConverseHistory_release(TPipe_ConverseHistoryHandle history);
```

### 6.2 Add Conversations

```c
// Add conversation entry with role and content
TPipe_Result TPipe_ConverseHistory_add(TPipe_ConverseHistoryHandle history,
                                       TPipe_ConverseRole role,
                                       const char* content);

// Add with timestamp (uses current time if timestampMs = 0)
TPipe_Result TPipe_ConverseHistory_addWithTimestamp(TPipe_ConverseHistoryHandle history,
                                                     TPipe_ConverseRole role,
                                                     const char* content,
                                                     int64_t timestampMs);
```

### 6.3 Query

```c
// Get number of entries
int32_t TPipe_ConverseHistory_size(TPipe_ConverseHistoryHandle history);

// Get role at index
TPipe_ConverseRole TPipe_ConverseHistory_getRole(TPipe_ConverseHistoryHandle history,
                                                  int32_t index);

// Get content at index
const char* TPipe_ConverseHistory_getContent(TPipe_ConverseHistoryHandle history,
                                              int32_t index);

// Get role-content pairs as list handle (caller must release)
TPipe_ListHandle TPipe_ConverseHistory_getRoleContentPairs(TPipe_ConverseHistoryHandle history);

// Get all content as strings
TPipe_ListHandle TPipe_ConverseHistory_getMessages(TPipe_ConverseHistoryHandle history);
```

### 6.4 Clear

```c
TPipe_Result TPipe_ConverseHistory_clear(TPipe_ConverseHistoryHandle history);
```

---

## 7. TraceConfig Handle

### 7.1 Create/Destroy

```c
// Create with defaults (enabled=false, normal detail level)
TPipe_TraceConfigHandle TPipe_TraceConfig_create(void);

// Create enabled with settings
TPipe_TraceConfigHandle TPipe_TraceConfig_createEnabled(int enabled,
                                                         int maxHistory,
                                                         TPipe_TraceFormat format,
                                                         TPipe_TraceDetailLevel detailLevel,
                                                         int autoExport,
                                                         const char* exportPath,
                                                         int includeContext,
                                                         int includeMetadata,
                                                         int mergeSplitterTraces);

TPipe_Result TPipe_TraceConfig_release(TPipe_TraceConfigHandle config);
```

### 7.2 Accessors

```c
int TPipe_TraceConfig_isEnabled(TPipe_TraceConfigHandle config);
int TPipe_TraceConfig_getMaxHistory(TPipe_TraceConfigHandle config);
TPipe_TraceFormat TPipe_TraceConfig_getOutputFormat(TPipe_TraceConfigHandle config);
TPipe_TraceDetailLevel TPipe_TraceConfig_getDetailLevel(TPipe_TraceConfigHandle config);
int TPipe_TraceConfig_getAutoExport(TPipe_TraceConfigHandle config);
const char* TPipe_TraceConfig_getExportPath(TPipe_TraceConfigHandle config);
int TPipe_TraceConfig_getIncludeContext(TPipe_TraceConfigHandle config);
int TPipe_TraceConfig_getIncludeMetadata(TPipe_TraceConfigHandle config);
int TPipe_TraceConfig_getMergeSplitterTraces(TPipe_TraceConfigHandle config);
```

### 7.3 Mutators

```c
TPipe_Result TPipe_TraceConfig_setEnabled(TPipe_TraceConfigHandle config, int enabled);
TPipe_Result TPipe_TraceConfig_setMaxHistory(TPipe_TraceConfigHandle config, int maxHistory);
TPipe_Result TPipe_TraceConfig_setOutputFormat(TPipe_TraceConfigHandle config, TPipe_TraceFormat format);
TPipe_Result TPipe_TraceConfig_setDetailLevel(TPipe_TraceConfigHandle config, TPipe_TraceDetailLevel level);
TPipe_Result TPipe_TraceConfig_setAutoExport(TPipe_TraceConfigHandle config, int autoExport);
TPipe_Result TPipe_TraceConfig_setExportPath(TPipe_TraceConfigHandle config, const char* path);
TPipe_Result TPipe_TraceConfig_setIncludeContext(TPipe_TraceConfigHandle config, int include);
TPipe_Result TPipe_TraceConfig_setIncludeMetadata(TPipe_TraceConfigHandle config, int include);
TPipe_Result TPipe_TraceConfig_setMergeSplitterTraces(TPipe_TraceConfigHandle config, int merge);
```

---

## 8. Error Handle (PipeError)

### 8.1 Create/Destroy

```c
TPipe_ErrorHandle TPipe_Error_create(const char* message,
                                      int errorCode,
                                      TPipe_ErrorSeverity severity);

TPipe_Result TPipe_Error_release(TPipe_ErrorHandle error);
```

### 8.2 Accessors

```c
const char* TPipe_Error_getMessage(TPipe_ErrorHandle error);
int TPipe_Error_getCode(TPipe_ErrorHandle error);
TPipe_ErrorSeverity TPipe_Error_getSeverity(TPipe_ErrorHandle error);
const char* TPipe_Error_getStackTrace(TPipe_ErrorHandle error);
```

---

## 9. PCP Handle Types

### 9.1 StdioContextHandle

```c
TPipe_StdioContextHandle TPipe_StdioContext_create(void);
TPipe_Result TPipe_StdioContext_release(TPipe_StdioContextHandle ctx);

// Fields
TPipe_Result TPipe_StdioContext_setCommand(TPipe_StdioContextHandle ctx, const char* cmd);
const char* TPipe_StdioContext_getCommand(TPipe_StdioContextHandle ctx);

TPipe_Result TPipe_StdioContext_addArg(TPipe_StdioContextHandle ctx, const char* arg);
TPipe_ListHandle TPipe_StdioContext_getArgs(TPipe_StdioContextHandle ctx); // caller releases

TPipe_Result TPipe_StdioContext_setDescription(TPipe_StdioContextHandle ctx, const char* desc);
TPipe_Result TPipe_StdioContext_setExecutionMode(TPipe_StdioContextHandle ctx, TPipe_StdioExecutionMode mode);

TPipe_Result TPipe_StdioContext_setTimeoutMs(TPipe_StdioContextHandle ctx, int64_t timeout);
int64_t TPipe_StdioContext_getTimeoutMs(TPipe_StdioContextHandle ctx);
```

### 9.2 HttpContextHandle

```c
TPipe_HttpContextHandle TPipe_HttpContext_create(void);
TPipe_Result TPipe_HttpContext_release(TPipe_HttpContextHandle ctx);

// Fields
TPipe_Result TPipe_HttpContext_setBaseUrl(TPipe_HttpContextHandle ctx, const char* url);
TPipe_Result TPipe_HttpContext_setEndpoint(TPipe_HttpContextHandle ctx, const char* endpoint);
TPipe_Result TPipe_HttpContext_setMethod(TPipe_HttpContextHandle ctx, const char* method);

TPipe_Result TPipe_HttpContext_setAuthType(TPipe_HttpContextHandle ctx, const char* authType);
TPipe_Result TPipe_HttpContext_setTimeoutMs(TPipe_HttpContextHandle ctx, int64_t timeout);
TPipe_Result TPipe_HttpContext_setFollowRedirects(TPipe_HttpContextHandle ctx, int follow);
```

---

## 10. P2P Handle Types

### 10.1 P2PTransportHandle

```c
TPipe_P2PTransportHandle TPipe_P2PTransport_create(TPipe_Transport transportMethod,
                                                     const char* address,
                                                     const char* authBody);
TPipe_Result TPipe_P2PTransport_release(TPipe_P2PTransportHandle transport);

TPipe_Transport TPipe_P2PTransport_getMethod(TPipe_P2PTransportHandle transport);
const char* TPipe_P2PTransport_getAddress(TPipe_P2PTransportHandle transport);
const char* TPipe_P2PTransport_getAuthBody(TPipe_P2PTransportHandle transport);
```

### 10.2 P2PDescriptorHandle

```c
TPipe_P2PDescriptorHandle TPipe_P2PDescriptor_create(const char* agentName,
                                                     const char* agentDescription,
                                                     TPipe_P2PTransportHandle transport,
                                                     TPipe_P2PRequirementsHandle requirements);
TPipe_Result TPipe_P2PDescriptor_release(TPipe_P2PDescriptorHandle desc);

// Accessors
const char* TPipe_P2PDescriptor_getAgentName(TPipe_P2PDescriptorHandle desc);
const char* TPipe_P2PDescriptor_getAgentDescription(TPipe_P2PDescriptorHandle desc);
TPipe_P2PTransportHandle TPipe_P2PDescriptor_getTransport(TPipe_P2PDescriptorHandle desc);
TPipe_P2PRequirementsHandle TPipe_P2PDescriptor_getRequirements(TPipe_P2PDescriptorHandle desc);

// Flags
int TPipe_P2PDescriptor_requiresAuth(TPipe_P2PDescriptorHandle desc);
int TPipe_P2PDescriptor_usesConverse(TPipe_P2PDescriptorHandle desc);
int TPipe_P2PDescriptor_allowsAgentDuplication(TPipe_P2PDescriptorHandle desc);
```

### 10.3 P2PRequirementsHandle

```c
TPipe_P2PRequirementsHandle TPipe_P2PRequirements_create(int64_t minMemory,
                                                            int64_t maxMemory,
                                                            int requiresNetwork,
                                                            int requiresFileSystem,
                                                            int requiresGPU);
TPipe_Result TPipe_P2PRequirements_release(TPipe_P2PRequirementsHandle req);

int64_t TPipe_P2PRequirements_getMinMemory(TPipe_P2PRequirementsHandle req);
int64_t TPipe_P2PRequirements_getMaxMemory(TPipe_P2PRequirementsHandle req);
```

---

## 11. Enums Required for Above Types

### 11.1 Trace Enums (for TraceConfig)

```c
typedef enum {
    TPIPE_TRACE_FORMAT_CONSOLE,
    TPIPE_TRACE_FORMAT_HTML,
    TPIPE_TRACE_FORMAT_JSON,
    TPIPE_TRACE_FORMAT_MARKDOWN,
    TPIPE_TRACE_FORMAT_CSV
} TPipe_TraceFormat;

typedef enum {
    TPIPE_TRACE_DETAIL_MINIMAL,
    TPIPE_TRACE_DETAIL_NORMAL,
    TPIPE_TRACE_DETAIL_DEBUG,
    TPIPE_TRACE_DETAIL_VERBOSE
} TPipe_TraceDetailLevel;

typedef enum {
    TPIPE_ERROR_SEVERITY_INFO,
    TPIPE_ERROR_SEVERITY_WARNING,
    TPIPE_ERROR_SEVERITY_ERROR,
    TPIPE_ERROR_SEVERITY_CRITICAL
} TPipe_ErrorSeverity;
```

---

## 12. Implementation Checklist (Summary)

| Handle Type | Create | Release | Key Methods |
|-------------|--------|---------|------------|
| `TPipe_ContentHandle` | ☐ | ☐ | text, binary, metadata |
| `TPipe_BinaryHandle` | ☐ | ☐ | mime, data, base64 |
| `TPipe_ContextHandle` | ☐ | ☐ | lorebook, elements, history, truncation |
| `TPipe_MiniBankHandle` | ☐ | ☐ | merge, isEmpty |
| `TPipe_LoreBookHandle` | ☐ | ☐ | key, value, weight, linkedKeys, aliasKeys, combine |
| `TPipe_ConverseHistoryHandle` | ☐ | ☐ | add, size, getRole, getContent, clear |
| `TPipe_TraceConfigHandle` | ☐ | ☐ | enabled, format, detailLevel, exportPath, etc. |
| `TPipe_ErrorHandle` | ☐ | ☐ | message, code, severity, stackTrace |
| `TPipe_StdioContextHandle` | ☐ | ☐ | command, args, description, timeout |
| `TPipe_HttpContextHandle` | ☐ | ☐ | baseUrl, endpoint, method, auth |
| `TPipe_P2PTransportHandle` | ☐ | ☐ | method, address, authBody |
| `TPipe_P2PDescriptorHandle` | ☐ | ☐ | agentName, transport, requirements, flags |
| `TPipe_P2PRequirementsHandle` | ☐ | ☐ | minMemory, maxMemory, network, fs, gpu |

---

*End of handle type specifications.*