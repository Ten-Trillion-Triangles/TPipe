# GraalVM Native ABI — Symbol Export Specification

**Spec File:** graalvm-abi-symbol-export.md
**Version:** 0.1.0-draft
**Created:** 2026-05-09
**Status:** Draft

---

## 1. Overview

This document specifies the complete symbol export surface of the TPipe native image binary, including which symbols are exported, the naming convention, stability guarantees, and how host languages (Python ctypes, Node FFI) discover and link against the library.

**Symbol source:** All TPipe C entry points use explicit `@CEntryPoint(name = "TPipe_...")` annotation. The explicit name is mandatory — default GraalVM auto-naming (`TpipeBootstrap_TPipe_*`) is not used. See `bootstrap-plan.md §5.4`.

---

## 2. Exported Symbol List

The following symbols are exported from the TPipe native image. All symbols use the `TPipe_` prefix. Symbols not listed here are internal and must not be called by host code.

### 2.1 Core Lifecycle

| Symbol | Signature | Notes |
|--------|-----------|-------|
| `TPipe_init` | `TPipe_Result(void)` | Initialize library. Idempotent. |
| `TPipe_shutdown` | `TPipe_Result(void)` | Graceful shutdown. Idempotent. |
| `TPipe_getState` | `TPipe_LibraryState(void)` | Query library state. Always thread-safe. |
| `TPipe_getVersion` | `const char*(void)` | Returns version string. Caller does not free. |
| `TPipe_getCapabilities` | `TPipe_Result(TPipe_CapabilityHandle** out, int* out_count)` | Caller frees via `TPipe_Handle_release()` on each entry. |
| `TPipe_Result_free` | `TPipe_Result(TPipe_Result)` | Free TPipe-allocated result payloads. |

### 2.2 Handle Lifecycle (Generic)

| Symbol | Signature | Notes |
|--------|-----------|-------|
| `TPipe_Handle_addRef` | `TPipe_Result(TPipe_Handle)` | Increment refcount. |
| `TPipe_Handle_release` | `TPipe_Result(TPipe_Handle)` | Decrement refcount, invalidate at zero. |
| `TPipe_Handle_getRefCount` | `int32_t(TPipe_Handle)` | Returns -1 if invalid. |
| `TPipe_Handle_isValid` | `int(TPipe_Handle)` | Returns 1 if valid, 0 if invalid. |

### 2.3 ContentHandle

| Symbol | Signature |
|--------|-----------|
| `TPipe_Content_create` | `TPipe_ContentHandle*(void)` |
| `TPipe_Content_createWithText` | `TPipe_ContentHandle*(const char* text)` |
| `TPipe_Content_clone` | `TPipe_ContentHandle*(TPipe_ContentHandle)` |
| `TPipe_Content_release` | `TPipe_Result(TPipe_ContentHandle)` |
| `TPipe_Content_getText` | `TPipe_Result(TPipe_ContentHandle, const char** out, int* out_len)` |
| `TPipe_Content_setText` | `TPipe_ContentHandle*(TPipe_ContentHandle, const char* text)` |
| `TPipe_Content_getBinary` | `TPipe_Result(TPipe_ContentHandle, TPipe_BinaryHandle* out)` |
| `TPipe_Content_getBinaries` | `TPipe_Result(TPipe_ContentHandle, TPipe_ListHandle* out)` |
| `TPipe_Content_addBinary` | `TPipe_ContentHandle*(TPipe_ContentHandle, TPipe_BinaryHandle binary)` |
| `TPipe_Content_clearBinary` | `TPipe_ContentHandle*(TPipe_ContentHandle)` |
| `TPipe_Content_getContext` | `TPipe_Result(TPipe_ContentHandle, TPipe_ContextHandle* out)` |
| `TPipe_Content_setContext` | `TPipe_ContentHandle*(TPipe_ContentHandle, TPipe_ContextHandle ctx)` |
| `TPipe_Content_getMiniBank` | `TPipe_Result(TPipe_ContentHandle, TPipe_MiniBankHandle* out)` |
| `TPipe_Content_setMiniBank` | `TPipe_ContentHandle*(TPipe_ContentHandle, TPipe_MiniBankHandle bank)` |
| `TPipe_Content_setTerminate` | `TPipe_ContentHandle*(TPipe_ContentHandle, int value)` |
| `TPipe_Content_setJumpTo` | `TPipe_ContentHandle*(TPipe_ContentHandle, const char* pipe_name)` |
| `TPipe_Content_setJumpToPipe` | `TPipe_ContentHandle*(TPipe_ContentHandle, const char* pipe_name)` |
| `TPipe_Content_clearJumpTo` | `TPipe_ContentHandle*(TPipe_ContentHandle)` |
| `TPipe_Content_setPass` | `TPipe_ContentHandle*(TPipe_ContentHandle, int value)` |
| `TPipe_Content_setRepeat` | `TPipe_ContentHandle*(TPipe_ContentHandle, int count)` |
| `TPipe_Content_setRepeatPipe` | `TPipe_ContentHandle*(TPipe_ContentHandle, const char* pipe_name, int count)` |
| `TPipe_Content_clearRepeat` | `TPipe_ContentHandle*(TPipe_ContentHandle)` |
| `TPipe_Content_setSkipReasoning` | `TPipe_ContentHandle*(TPipe_ContentHandle, int value)` |

### 2.4 BinaryHandle

| Symbol | Signature |
|--------|-----------|
| `TPipe_Binary_create` | `TPipe_BinaryHandle*(const void* data, int length)` |
| `TPipe_Binary_createEmpty` | `TPipe_BinaryHandle*(void)` |
| `TPipe_Binary_release` | `TPipe_Result(TPipe_BinaryHandle)` |
| `TPipe_BinaryContent_createBytes` | `TPipe_BinaryHandle*(const void* data, int length)` |
| `TPipe_BinaryContent_createCloudRef` | `TPipe_BinaryHandle*(const char* uri)` |
| `TPipe_BinaryContent_createTextDoc` | `TPipe_BinaryHandle*(const char* file_path)` |
| `TPipe_BinaryContent_getType` | `int(TPipe_BinaryHandle)` |

### 2.5 ContextHandle

| Symbol | Signature |
|--------|-----------|
| `TPipe_Context_create` | `TPipe_ContextHandle*(void)` |
| `TPipe_Context_clone` | `TPipe_ContextHandle*(TPipe_ContextHandle)` |
| `TPipe_Context_release` | `TPipe_Result(TPipe_ContextHandle)` |
| `TPipe_Context_get` | `TPipe_Result(TPipe_ContextHandle, const char** out, int* out_len)` |
| `TPipe_Context_set` | `TPipe_ContextHandle*(TPipe_ContextHandle, const char* data, int len)` |
| `TPipe_Context_addContextElement` | `TPipe_ContextHandle*(TPipe_ContextHandle, const char* key, const char* value)` |
| `TPipe_Context_addHistoryEntry` | `TPipe_ContextHandle*(TPipe_ContextHandle, const char* role, const char* content, int64_t timestamp)` |
| `TPipe_Context_addLoreBookEntry` | `TPipe_ContextHandle*(TPipe_ContextHandle, const char* pattern, const char* content, double weight)` |
| `TPipe_Context_cleanLorebook` | `TPipe_ContextHandle*(TPipe_ContextHandle)` |
| `TPipe_Context_clear` | `TPipe_ContextHandle*(TPipe_ContextHandle)` |
| `TPipe_Context_clearContextElements` | `TPipe_ContextHandle*(TPipe_ContextHandle)` |
| `TPipe_Context_findLoreBookEntry` | `TPipe_Result(TPipe_ContextHandle, const char* pattern, const char** out, int* out_len)` |
| `TPipe_Context_findMatchingLoreBookKeys` | `TPipe_Result(TPipe_ContextHandle, const char* pattern, TPipe_ListHandle* out)` |
| `TPipe_Context_getContextElements` | `TPipe_Result(TPipe_ContextHandle, TPipe_ListHandle* out)` |
| `TPipe_Context_getConverseHistory` | `TPipe_Result(TPipe_ContextHandle, TPipe_ConverseHistoryHandle* out)` |
| `TPipe_Context_getHistory` | `TPipe_Result(TPipe_ContextHandle, TPipe_ListHandle* out)` |
| `TPipe_Context_getLoreBook` | `TPipe_Result(TPipe_ContextHandle, TPipe_ListHandle* out)` |
| `TPipe_Context_getLoreBookKeys` | `TPipe_Result(TPipe_ContextHandle, TPipe_ListHandle* out)` |
| `TPipe_Context_selectLoreBookContext` | `TPipe_Result(TPipe_ContextHandle, const char* query, double minWeight, TPipe_ContextHandle* out)` |
| `TPipe_Context_setConverseHistory` | `TPipe_ContextHandle*(TPipe_ContextHandle, TPipe_ConverseHistoryHandle history)` |
| `TPipe_Context_truncateContextElements` | `TPipe_ContextHandle*(TPipe_ContextHandle, int max_entries)` |
| `TPipe_Context_truncateConverseHistory` | `TPipe_ContextHandle*(TPipe_ContextHandle, int max_entries)` |

### 2.6 ConverseHistoryHandle

| Symbol | Signature |
|--------|-----------|
| `TPipe_ConverseHistory_create` | `TPipe_ConverseHistoryHandle*(void)` |
| `TPipe_ConverseHistory_add` | `TPipe_ConverseHistoryHandle*(TPipe_ConverseHistoryHandle, const char* role, const char* content)` |
| `TPipe_ConverseHistory_addWithTimestamp` | `TPipe_ConverseHistoryHandle*(TPipe_ConverseHistoryHandle, const char* role, const char* content, int64_t timestamp)` |
| `TPipe_ConverseHistory_clear` | `TPipe_ConverseHistoryHandle*(TPipe_ConverseHistoryHandle)` |
| `TPipe_ConverseHistory_release` | `TPipe_Result(TPipe_ConverseHistoryHandle)` |
| `TPipe_ConverseHistory_getMessages` | `TPipe_Result(TPipe_ConverseHistoryHandle, TPipe_ListHandle* out)` |
| `TPipe_ConverseHistory_getRole` | `TPipe_Result(TPipe_ConverseHistoryHandle, int index, const char** out, int* out_len)` |
| `TPipe_ConverseHistory_getRoleContentPairs` | `TPipe_Result(TPipe_ConverseHistoryHandle, TPipe_ListHandle* out)` |
| `TPipe_ConverseHistory_get` | `TPipe_Result(TPipe_ConverseHistoryHandle, int index, const char** out, int* out_len)` |

### 2.7 ListHandle

| Symbol | Signature |
|--------|-----------|
| `TPipe_List_create` | `TPipe_ListHandle*(int element_size)` |
| `TPipe_List_release` | `TPipe_Result(TPipe_ListHandle)` |
| `TPipe_List_get` | `TPipe_Result(TPipe_ListHandle, int index, void* out)` |
| `TPipe_List_size` | `int(TPipe_ListHandle)` |
| `TPipe_List_add` | `TPipe_ListHandle*(TPipe_ListHandle, const void* item)` |

### 2.8 MiniBankHandle

| Symbol | Signature |
|--------|-----------|
| `TPipe_MiniBank_create` | `TPipe_MiniBankHandle*(void)` |
| `TPipe_MiniBank_release` | `TPipe_Result(TPipe_MiniBankHandle)` |
| `TPipe_MiniBank_get` | `TPipe_Result(TPipe_MiniBankHandle, const char* key, const char** out, int* out_len)` |
| `TPipe_MiniBank_set` | `TPipe_MiniBankHandle*(TPipe_MiniBankHandle, const char* key, const char* value, int val_len)` |
| `TPipe_MiniBank_keys` | `TPipe_Result(TPipe_MiniBankHandle, TPipe_ListHandle* out)` |

### 2.9 PipelineHandle

| Symbol | Signature |
|--------|-----------|
| `TPipe_Pipeline_create` | `TPipe_PipelineHandle*(const char* name)` |
| `TPipe_Pipeline_init` | `TPipe_PipelineHandle*(TPipe_PipelineHandle)` |
| `TPipe_Pipeline_release` | `TPipe_Result(TPipe_PipelineHandle)` |
| `TPipe_Pipeline_execute` | `TPipe_Result(TPipe_PipelineHandle, TPipe_ContentHandle* in, TPipe_ContentHandle** out)` |
| `TPipe_Pipeline_executeBegin` | `TPipe_AsyncHandle*(TPipe_PipelineHandle, TPipe_ContentHandle* in)` |
| `TPipe_Pipeline_executeEnd` | `TPipe_Result(TPipe_PipelineHandle, TPipe_AsyncHandle* async, TPipe_ContentHandle** out)` |
| `TPipe_Pipeline_executeLocal` | `TPipe_Result(TPipe_PipelineHandle, TPipe_ContentHandle* in, TPipe_ContentHandle** out)` |
| `TPipe_Pipeline_executeContent` | `TPipe_Result(TPipe_PipelineHandle, TPipe_ContentHandle* in, TPipe_ContentHandle** out)` |
| `TPipe_Pipeline_insert` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, TPipe_PipelineHandle after, TPipe_PipeHandle pipe)` |
| `TPipe_Pipeline_setName` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, const char* name)` |
| `TPipe_Pipeline_setContainerObject` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, void* obj)` |
| `TPipe_Pipeline_useGlobalContext` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, int enabled)` |
| `TPipe_Pipeline_setPipelineCompletionCallback` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, TPipe_PipelineCompletionCallback* cb, void* user_data)` |
| `TPipe_Pipeline_setPipeCompletionCallback` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, TPipe_PipeCompletionCallback* cb, void* user_data)` |
| `TPipe_Pipeline_setPreValidationFunction` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, TPipe_ValidationCallback* cb, void* user_data)` |
| `TPipe_Pipeline_wrapContentWithConverseHistory` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, int flag)` |
| `TPipe_Pipeline_pause` | `TPipe_PipelineHandle*(TPipe_PipelineHandle)` |
| `TPipe_Pipeline_resume` | `TPipe_PipelineHandle*(TPipe_PipelineHandle)` |
| `TPipe_Pipeline_onPause` | `TPipe_Result(TPipe_PipelineHandle, TPipe_PipelinePauseCallback* cb, void* user_data)` |
| `TPipe_Pipeline_onResume` | `TPipe_Result(TPipe_PipelineHandle, TPipe_PipelineResumeCallback* cb, void* user_data)` |
| `TPipe_Pipeline_pauseBeforePipes` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, int count)` |
| `TPipe_Pipeline_pauseAfterPipes` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, int count)` |
| `TPipe_Pipeline_pauseBeforeJumps` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, int count)` |
| `TPipe_Pipeline_pauseAfterRepeats` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, int count)` |
| `TPipe_Pipeline_pauseOnCompletion` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, TPipe_PipelineCompletionCallback* cb, void* user_data)` |
| `TPipe_Pipeline_pauseWhen` | `TPipe_PipelineHandle*(TPipe_PipelineHandle, const char* condition)` |

### 2.10 PipeHandle

| Symbol | Signature |
|--------|-----------|
| `TPipe_Pipe_create` | `TPipe_PipeHandle*(const char* provider)` |
| `TPipe_Pipe_release` | `TPipe_Result(TPipe_PipeHandle)` |
| `TPipe_Pipe_setModel` | `TPipe_PipeHandle*(TPipe_PipeHandle, const char* model_id)` |
| `TPipe_Pipe_setSystemPrompt` | `TPipe_PipeHandle*(TPipe_PipeHandle, const char* prompt)` |
| `TPipe_Pipe_setAuthToken` | `TPipe_PipeHandle*(TPipe_PipeHandle, const char* token)` |
| `TPipe_Pipe_setProvider` | `TPipe_PipeHandle*(TPipe_PipeHandle, const char* provider)` |
| `TPipe_Pipe_getConfig` | `TPipe_Result(TPipe_PipeHandle, TPipe_PipeConfigHandle* out)` |
| `TPipe_Pipe_execute` | `TPipe_Result(TPipe_PipeHandle, TPipe_ContentHandle* in, TPipe_ContentHandle** out)` |
| `TPipe_Pipe_executeStreaming` | `TPipe_Result(TPipe_PipeHandle, TPipe_ContentHandle* in, TPipe_StreamingCallback* cb, void* user_data)` |
| `TPipe_Pipe_setTokenBudget` | `TPipe_PipeHandle*(TPipe_PipeHandle, const TPipe_TokenBudgetSettings* settings)` |

### 2.11 AsyncHandle

| Symbol | Signature |
|--------|-----------|
| `TPipe_Async_wait` | `TPipe_Result(TPipe_AsyncHandle*, int64_t timeout_ms)` |
| `TPipe_Async_getResult` | `TPipe_Result(TPipe_AsyncHandle*, TPipe_ContentHandle** out)` |
| `TPipe_Async_cancel` | `TPipe_Result(TPipe_AsyncHandle*)` |

### 2.12 Connector

| Symbol | Signature |
|--------|-----------|
| `TPipe_Connector_create` | `TPipe_ConnectorHandle*(const char* name)` |
| `TPipe_Connector_destroy` | `TPipe_Result(TPipe_ConnectorHandle)` |
| `TPipe_Connector_execute` | `TPipe_Result(TPipe_ConnectorHandle, const char* key, TPipe_ContentHandle* in, TPipe_ContentHandle** out)` |
| `TPipe_Connector_executeBegin` | `TPipe_AsyncHandle*(TPipe_ConnectorHandle, const char* key, TPipe_ContentHandle* in)` |
| `TPipe_Connector_executeEnd` | `TPipe_Result(TPipe_ConnectorHandle, TPipe_AsyncHandle*, TPipe_ContentHandle** out)` |
| `TPipe_Connector_executeLocal` | `TPipe_Result(TPipe_ConnectorHandle, const char* key, TPipe_ContentHandle* in, TPipe_ContentHandle** out)` |
| `TPipe_Connector_get` | `TPipe_Result(TPipe_ConnectorHandle, const char* key, TPipe_PipelineHandle** out)` |
| `TPipe_Connector_setPath` | `TPipe_ConnectorHandle*(TPipe_ConnectorHandle, const char* key, TPipe_PipelineHandle pipeline)` |
| `TPipe_ConnectorConfig_add` | `TPipe_ConnectorConfigHandle*(TPipe_ConnectorConfigHandle*, const char* key, TPipe_PipelineHandle pipeline)` |
| `TPipe_ConnectorConfig_enableTracing` | `TPipe_ConnectorConfigHandle*(TPipe_ConnectorConfigHandle*, const TPipe_TraceConfig* config)` |
| `TPipe_ConnectorConfig_setDefaultPath` | `TPipe_ConnectorConfigHandle*(TPipe_ConnectorConfigHandle*, const char* key)` |

### 2.13 Splitter

| Symbol | Signature |
|--------|-----------|
| `TPipe_Splitter_init` | `TPipe_SplitterHandle*(TPipe_SplitterHandle)` |
| `TPipe_Splitter_destroy` | `TPipe_Result(TPipe_SplitterHandle)` |
| `TPipe_Splitter_executePipelines` | `TPipe_Result(TPipe_SplitterHandle, TPipe_ContentHandle* in, TPipe_ListHandle** out)` |
| `TPipe_Splitter_executePipelinesBegin` | `TPipe_AsyncHandle*(TPipe_SplitterHandle, TPipe_ContentHandle* in)` |
| `TPipe_Splitter_executePipelinesEnd` | `TPipe_Result(TPipe_SplitterHandle, TPipe_AsyncHandle*, TPipe_ListHandle** out)` |
| `TPipe_Splitter_executeLocal` | `TPipe_Result(TPipe_SplitterHandle, TPipe_ContentHandle* in, TPipe_ListHandle** out)` |
| `TPipe_Splitter_toMultimodalCollection` | `TPipe_Result(TPipe_SplitterHandle, TPipe_ListHandle* results, TPipe_ContentHandle** out)` |
| `TPipe_SplitterConfig_addContent` | `TPipe_SplitterConfigHandle*(TPipe_SplitterConfigHandle*, const char* key, TPipe_ContentHandle* content)` |
| `TPipe_SplitterConfig_addPipeline` | `TPipe_SplitterConfigHandle*(TPipe_SplitterConfigHandle*, const char* key, TPipe_PipelineHandle pipeline)` |
| `TPipe_SplitterConfig_removeKey` | `TPipe_SplitterConfigHandle*(TPipe_SplitterConfigHandle*, const char* key)` |
| `TPipe_SplitterConfig_removePipeline` | `TPipe_SplitterConfigHandle*(TPipe_SplitterConfigHandle*, const char* key)` |
| `TPipe_SplitterConfig_enableTracing` | `TPipe_SplitterConfigHandle*(TPipe_SplitterConfigHandle*, const TPipe_TraceConfig* config)` |

### 2.14 Manifold

| Symbol | Signature |
|--------|-----------|
| `TPipe_Manifold_create` | `TPipe_ManifoldHandle*(void)` |
| `TPipe_Manifold_release` | `TPipe_Result(TPipe_ManifoldHandle)` |
| `TPipe_Manifold_execute` | `TPipe_Result(TPipe_ManifoldHandle, TPipe_ContentHandle* in, TPipe_ContentHandle** out)` |
| `TPipe_Manifold_executeBegin` | `TPipe_AsyncHandle*(TPipe_ManifoldHandle, TPipe_ContentHandle* in)` |
| `TPipe_Manifold_executeEnd` | `TPipe_Result(TPipe_ManifoldHandle, TPipe_AsyncHandle*, TPipe_ContentHandle** out)` |
| `TPipe_Manifold_setManagerPipeline` | `TPipe_ManifoldHandle*(TPipe_ManifoldHandle, TPipe_PipelineHandle pipeline, const TPipe_P2PDescriptor* descriptor, const TPipe_P2PRequirements* requirements)` |
| `TPipe_Manifold_addWorkerPipeline` | `TPipe_ManifoldHandle*(TPipe_ManifoldHandle, TPipe_PipelineHandle pipeline, const TPipe_P2PDescriptor* descriptor, const TPipe_P2PRequirements* requirements, const char* agent_name, const char* agent_description)` |
| `TPipe_Manifold_setAgentPipeNames` | `TPipe_ManifoldHandle*(TPipe_ManifoldHandle, const char** names, int count)` |
| `TPipe_Manifold_autoTruncateContext` | `TPipe_ManifoldHandle*(TPipe_ManifoldHandle)` |
| `TPipe_ManifoldConfig_create` | `TPipe_ManifoldConfigHandle*(void)` |
| `TPipe_ManifoldConfig_release` | `TPipe_Result(TPipe_ManifoldConfigHandle)` |
| `TPipe_ManifoldConfig_setMaxLoopIterations` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, int limit)` |
| `TPipe_ManifoldConfig_setSummaryMode` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, TPipe_ManifoldSummaryMode mode)` |
| `TPipe_ManifoldConfig_enableTracing` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, const TPipe_TraceConfig* config)` |
| `TPipe_ManifoldConfig_setTruncationMethod` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, const TPipe_ContextWindowSettings* settings)` |
| `TPipe_ManifoldConfig_setContextWindowSize` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, int size)` |
| `TPipe_ManifoldConfig_setManagerTokenBudget` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, const TPipe_TokenBudgetSettings* budget)` |
| `TPipe_ManifoldConfig_setManagerPipeline` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, TPipe_PipelineHandle pipeline, const TPipe_P2PDescriptor* descriptor, const TPipe_P2PRequirements* requirements)` |
| `TPipe_ManifoldConfig_addWorkerPipeline` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, TPipe_PipelineHandle pipeline, const TPipe_P2PDescriptor* descriptor, const TPipe_P2PRequirements* requirements, const char* agent_name, const char* agent_description)` |
| `TPipe_ManifoldConfig_setP2pAgentNames` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, const char** names, int count)` |
| `TPipe_ManifoldConfig_autoTruncateContext` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*)` |
| `TPipe_ManifoldConfig_setManifoldInitFunction` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, TPipe_ManifoldInitCallback* func, void* user_data)` |
| `TPipe_ManifoldConfig_setContextTruncationFunction` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, TPipe_ContextTruncationCallback* func, void* user_data)` |
| `TPipe_ManifoldConfig_setValidatorFunction` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, TPipe_ManifoldValidatorCallback* func, void* user_data)` |
| `TPipe_ManifoldConfig_setFailureFunction` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, TPipe_ManifoldFailureCallback* func, void* user_data)` |
| `TPipe_ManifoldConfig_setTransformationFunction` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, TPipe_ManifoldTransformationCallback* func, void* user_data)` |
| `TPipe_ManifoldConfig_setSummaryPipeline` | `TPipe_ManifoldConfigHandle*(TPipe_ManifoldConfigHandle*, TPipe_PipelineHandle pipeline, const TPipe_P2PDescriptor* descriptor, const TPipe_P2PRequirements* requirements)` |

### 2.15 TokenBudget

| Symbol | Signature |
|--------|-----------|
| `TPipe_TokenBudget_create` | `TPipe_TokenBudgetHandle*(void)` |
| `TPipe_TokenBudget_release` | `TPipe_Result(TPipe_TokenBudgetHandle)` |
| `TPipe_TokenBudget_setUserPromptSize` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int tokens)` |
| `TPipe_TokenBudget_setMaxTokens` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int tokens)` |
| `TPipe_TokenBudget_setReasoningBudget` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int tokens)` |
| `TPipe_TokenBudget_setContextWindowSize` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int size)` |
| `TPipe_TokenBudget_setAllowTruncation` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int allow)` |
| `TPipe_TokenBudget_setCompressPrompt` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int compress)` |
| `TPipe_TokenBudget_setTruncateAsString` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int truncate)` |
| `TPipe_TokenBudget_setPreserveTextMatches` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int preserve)` |
| `TPipe_TokenBudget_setTruncationMode` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, TPipe_ContextTruncationMethod mode)` |
| `TPipe_TokenBudget_setMultiPageStrategy` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int strategy)` |
| `TPipe_TokenBudget_setPageWeights` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, const char* json)` |
| `TPipe_TokenBudget_setPreserveJson` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int preserve)` |
| `TPipe_TokenBudget_setReserveEmptyPage` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int reserve)` |
| `TPipe_TokenBudget_setMultiplyWindowBy` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int multiplier)` |
| `TPipe_TokenBudget_setSubtractReasoning` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int subtract)` |
| `TPipe_TokenBudget_setCountSubWords` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int count)` |
| `TPipe_TokenBudget_setCountFirstWordOnly` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int count_only)` |
| `TPipe_TokenBudget_setFavorWholeWords` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int favor)` |
| `TPipe_TokenBudget_setSplitForNonWord` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int split)` |
| `TPipe_TokenBudget_setNonWordSplitCount` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int count)` |
| `TPipe_TokenBudget_setAlwaysSplitIfWhole` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int always)` |
| `TPipe_TokenBudget_setCountSubWordsIfSplit` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int count)` |
| `TPipe_TokenBudget_setTokenBias` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, double bias)` |
| `TPipe_TokenBudget_setFillMode` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int fill)` |
| `TPipe_TokenBudget_setFillAndSplitMode` | `TPipe_TokenBudgetHandle*(TPipe_TokenBudgetHandle*, int fill_split)` |
| `TPipe_TokenBudget_getTruncationMode` | `TPipe_Result(TPipe_TokenBudgetHandle, int* out)` |

### 2.16 TraceConfig

| Symbol | Signature |
|--------|-----------|
| `TPipe_TraceConfig_create` | `TPipe_TraceConfigHandle*(void)` |
| `TPipe_TraceConfig_createEnabled` | `TPipe_TraceConfigHandle*(const char* output_path)` |
| `TPipe_TraceConfig_release` | `TPipe_Result(TPipe_TraceConfigHandle)` |
| `TPipe_TraceConfig_setEnabled` | `TPipe_TraceConfigHandle*(TPipe_TraceConfigHandle*, int enabled)` |
| `TPipe_TraceConfig_setDetailLevel` | `TPipe_TraceConfigHandle*(TPipe_TraceConfigHandle*, int level)` |
| `TPipe_TraceConfig_setOutputFormat` | `TPipe_TraceConfigHandle*(TPipe_TraceConfigHandle*, int format)` |
| `TPipe_TraceConfig_setExportPath` | `TPipe_TraceConfigHandle*(TPipe_TraceConfigHandle*, const char* path)` |
| `TPipe_TraceConfig_setMaxHistory` | `TPipe_TraceConfigHandle*(TPipe_TraceConfigHandle*, int max)` |
| `TPipe_TraceConfig_setAutoExport` | `TPipe_TraceConfigHandle*(TPipe_TraceConfigHandle*, int auto)` |
| `TPipe_TraceConfig_setMergeSplitterTraces` | `TPipe_TraceConfigHandle*(TPipe_TraceConfigHandle*, int merge)` |
| `TPipe_TraceConfig_getDetailLevel` | `TPipe_Result(TPipe_TraceConfigHandle, int* out)` |
| `TPipe_TraceConfig_getOutputFormat` | `TPipe_Result(TPipe_TraceConfigHandle, int* out)` |

### 2.17 TokenUsage

| Symbol | Signature |
|--------|-----------|
| `TPipe_TokenUsage_fromHandle` | `TPipe_TokenUsageHandle*(TPipe_Handle handle)` |
| `TPipe_TokenUsage_release` | `TPipe_Result(TPipe_TokenUsageHandle)` |
| `TPipe_TokenUsage_getInputTokens` | `TPipe_Result(TPipe_TokenUsageHandle, int* out)` |
| `TPipe_TokenUsage_getOutputTokens` | `TPipe_Result(TPipe_TokenUsageHandle, int* out)` |
| `TPipe_TokenUsage_getTotalTokens` | `TPipe_Result(TPipe_TokenUsageHandle, int* out)` |

### 2.18 StdioContext

| Symbol | Signature |
|--------|-----------|
| `TPipe_StdioContext_create` | `TPipe_StdioContextHandle*(void)` |
| `TPipe_StdioContext_release` | `TPipe_Result(TPipe_StdioContextHandle)` |
| `TPipe_StdioContext_setCommand` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, const char* cmd)` |
| `TPipe_StdioContext_setArgs` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, const char** args, int count)` |
| `TPipe_StdioContext_addArg` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, const char* arg)` |
| `TPipe_StdioContext_setEnvVars` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, const char** vars, int count)` |
| `TPipe_StdioContext_setWorkingDirectory` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, const char* dir)` |
| `TPipe_StdioContext_setTimeout` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, int64_t timeout_sec)` |
| `TPipe_StdioContext_setTimeoutMs` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, int64_t timeout_ms)` |
| `TPipe_StdioContext_setPermissions` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, const char** perms, int count)` |
| `TPipe_StdioContext_setKeepAlive` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, int keepalive)` |
| `TPipe_StdioContext_setBufferPersistence` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, int persist)` |
| `TPipe_StdioContext_setMaxBufferSize` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, int64_t max_size)` |
| `TPipe_StdioContext_setExecutionMode` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, int mode)` |
| `TPipe_StdioContext_setDescription` | `TPipe_StdioContextHandle*(TPipe_StdioContextHandle*, const char* desc)` |
| `TPipe_StdioContext_getArgs` | `TPipe_Result(TPipe_StdioContextHandle, const char*** out, int* out_count)` |
| `TPipe_StdioContext_getExecutionMode` | `TPipe_Result(TPipe_StdioContextHandle, int* out)` |

### 2.19 PCP (Pipe Context Protocol)

| Symbol | Signature | Notes |
|--------|-----------|-------|
| `TPipe_PCP_execute` | `TPipe_Result(const char* request_json, int json_len, const char** out_response, int* out_len)` | TPipe-allocated result — caller calls `TPipe_Result_free()` |
| `TPipe_PCP_executeAsync` | `TPipe_AsyncHandle*(const char* request_json, int json_len)` | |
| `TPipe_PCP_getRegisteredFunctions` | `TPipe_Result(TPipe_ListHandle* out)` | Returns list of function names. Caller frees list. |
| `TPipe_PCP_registerFunction` | `TPipe_Result(const char* name, TPipe_FunctionHandle* func)` | Post-init only |
| `TPipe_FunctionRegistry_register` | `TPipe_Result(const char* name, TPipe_FunctionHandle* func)` | Alias for PCP register |

### 2.20 DistributionGrid

| Symbol | Signature |
|--------|-----------|
| `TPipe_DistributionGrid_create` | `TPipe_DistributionGridHandle*(void)` |
| `TPipe_DistributionGrid_release` | `TPipe_Result(TPipe_DistributionGridHandle)` |
| `TPipe_DistributionGrid_init` | `TPipe_DistributionGridHandle*(TPipe_DistributionGridHandle)` |
| `TPipe_DistributionGrid_execute` | `TPipe_Result(TPipe_DistributionGridHandle, TPipe_ContentHandle* in, TPipe_ContentHandle** out)` |
| `TPipe_DistributionGrid_getNodeId` | `TPipe_Result(TPipe_DistributionGridHandle, const char** out, int* out_len)` |
| `TPipe_DistributionGrid_getPeerCount` | `TPipe_Result(TPipe_DistributionGridHandle, int* out)` |
| `TPipe_DistributionGridConfig_create` | `TPipe_DistributionGridConfigHandle*(void)` |
| `TPipe_DistributionGridConfig_release` | `TPipe_Result(TPipe_DistributionGridConfigHandle)` |
| `TPipe_DistributionGridConfig_setDiscoveryMode` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, int mode)` |
| `TPipe_DistributionGridConfig_setRoutingPolicy` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, int policy)` |
| `TPipe_DistributionGridConfig_setRpcTimeout` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, int64_t timeout_ms)` |
| `TPipe_DistributionGridConfig_setMaxHops` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, int max_hops)` |
| `TPipe_DistributionGridConfig_setMaxSessionDuration` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, int64_t duration_ms)` |
| `TPipe_DistributionGridConfig_setMemoryPolicy` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, const TPipe_DistributionGridMemoryPolicy* policy)` |
| `TPipe_DistributionGridConfig_setDurableStore` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, const char* path)` |
| `TPipe_DistributionGridConfig_setRouter` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, TPipe_DistributionGridRouterCallback* cb, void* user_data)` |
| `TPipe_DistributionGridConfig_addPeer` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, const char* peer_address)` |
| `TPipe_DistributionGridConfig_addPeerDescriptor` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, const TPipe_P2PDescriptor* descriptor)` |
| `TPipe_DistributionGridConfig_removePeer` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, const char* peer_address)` |
| `TPipe_DistributionGridConfig_replacePeer` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, const char* old_peer, const char* new_peer)` |
| `TPipe_DistributionGridConfig_setBeforeRouteHook` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, TPipe_DistributionGridHookCallback* cb, void* user_data)` |
| `TPipe_DistributionGridConfig_setAfterLocalWorkerHook` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, TPipe_DistributionGridHookCallback* cb, void* user_data)` |
| `TPipe_DistributionGridConfig_setBeforeLocalWorkerHook` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, TPipe_DistributionGridHookCallback* cb, void* user_data)` |
| `TPipe_DistributionGridConfig_setAfterPeerResponseHook` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, TPipe_DistributionGridHookCallback* cb, void* user_data)` |
| `TPipe_DistributionGridConfig_setBeforePeerDispatchHook` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, TPipe_DistributionGridHookCallback* cb, void* user_data)` |
| `TPipe_DistributionGridConfig_setOutcomeTransformationHook` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, TPipe_DistributionGridTransformCallback* cb, void* user_data)` |
| `TPipe_DistributionGridConfig_setFailureHook` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, TPipe_DistributionGridFailureCallback* cb, void* user_data)` |
| `TPipe_DistributionGridConfig_setOutboundMemoryHook` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, TPipe_DistributionGridMemoryCallback* cb, void* user_data)` |
| `TPipe_DistributionGridConfig_setTrustVerifier` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, TPipe_DistributionGridTrustCallback* cb, void* user_data)` |
| `TPipe_DistributionGridConfig_setRegistryMetadata` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, const char* metadata_json)` |
| `TPipe_DistributionGridConfig_addBootstrapCatalogSource` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, const char* catalog_url)` |
| `TPipe_DistributionGridConfig_addBootstrapRegistry` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, const char* registry_address)` |
| `TPipe_DistributionGridConfig_removeBootstrapCatalogSource` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, const char* catalog_url)` |
| `TPipe_DistributionGridConfig_removeBootstrapRegistry` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, const char* registry_address)` |
| `TPipe_DistributionGridConfig_enableTracing` | `TPipe_DistributionGridConfigHandle*(TPipe_DistributionGridConfigHandle*, const TPipe_TraceConfig* config)` |

### 2.21 Junction

| Symbol | Signature |
|--------|-----------|
| `TPipe_Junction_create` | `TPipe_JunctionHandle*(void)` |
| `TPipe_Junction_release` | `TPipe_Result(TPipe_JunctionHandle)` |
| `TPipe_Junction_init` | `TPipe_JunctionHandle*(TPipe_JunctionHandle)` |
| `TPipe_Junction_execute` | `TPipe_Result(TPipe_JunctionHandle, TPipe_ContentHandle* in, TPipe_ContentHandle** out)` |
| `TPipe_Junction_addParticipant` | `TPipe_JunctionHandle*(TPipe_JunctionHandle*, const char* role_name, TPipe_PipelineHandle pipeline, double weight, const TPipe_P2PDescriptor* descriptor, const TPipe_P2PRequirements* requirements)` |
| `TPipe_Junction_setModerator` | `TPipe_JunctionHandle*(TPipe_JunctionHandle*, TPipe_PipelineHandle moderator, const TPipe_P2PDescriptor* descriptor, const TPIPE_P2PRequirements* requirements)` |
| `TPipe_JunctionConfig_create` | `TPipe_JunctionConfigHandle*(void)` |
| `TPipe_JunctionConfig_release` | `TPipe_Result(TPipe_JunctionConfigHandle)` |
| `TPipe_JunctionConfig_setMaxRounds` | `TPipe_JunctionConfigHandle*(TPipe_JunctionConfigHandle*, int max_rounds)` |
| `TPipe_JunctionConfig_setMinVotes` | `TPipe_JunctionConfigHandle*(TPipe_JunctionConfigHandle*, int min_votes)` |
| `TPipe_JunctionConfig_setDecisionThreshold` | `TPipe_JunctionConfigHandle*(TPipe_JunctionConfigHandle*, double threshold)` |
| `TPipe_JunctionConfig_setTimeoutMs` | `TPipe_JunctionConfigHandle*(TPipe_JunctionConfigHandle*, int64_t timeout_ms)` |
| `TPipe_JunctionConfig_setMemoryPolicy` | `TPipe_JunctionConfigHandle*(TPipe_JunctionConfigHandle*, const TPipe_JunctionMemoryPolicy* policy)` |

### 2.22 Handle Type Conversions

| Symbol | Signature | Notes |
|--------|-----------|-------|
| `TPipe_Handle_fromString` | `TPipe_Handle(const char* id_string)` | Convert string ID to handle |
| `TPipe_Handle_toString` | `TPipe_Result(TPipe_Handle, const char** out, int* out_len)` | Convert handle to string ID. Caller frees via `TPipe_Result_free()` |

---

## 3. Symbol Stability Policy

### 3.1 Stability Levels

| Level | Guarantee | Examples |
|-------|-----------|----------|
| **STABLE** | Symbol will not change across minor/patch versions | `TPipe_init`, `TPipe_shutdown`, `TPipe_getVersion`, `TPipe_Handle_*` |
| **SEMI-STABLE** | Symbol signature preserved, may gain new overloads | Most `TPipe_*_set*` setters |
| **EXPERIMENTAL** | Subject to change without notice | `TPipe_DistributionGrid_*` hooks |
| **DEPRECATED** | Will be removed in next major version | None currently |

### 3.2 Versioning

TPipe follows semantic versioning (MAJOR.MINOR.PATCH). ABI stability applies within a MAJOR version. Breaking changes increment MAJOR and are preceded by a deprecation cycle.

---

## 4. Host Discovery Mechanism

### 4.1 Linux / ELF

```bash
# Verify exported symbols
nm -D libtpipe.so | grep " T TPipe_"

# Expected output (sample):
# 0000000000001234 T TPipe_init
# 0000000000001250 T TPipe_shutdown
# ...
```

### 4.2 macOS / Mach-O

```bash
# Verify exported symbols
nm -gU libtpipe.dylib | grep "TPipe_"

# Or via otool
otool -L libtpipe.dylib  # show load commands
```

### 4.3 Windows / PE

```powershell
# Verify exported symbols
dumpbin /EXPORTS libtpipe.dll | findstr "TPipe_"
```

### 4.4 Dynamic Discovery in Host Code

Python (ctypes):
```python
import ctypes
lib = ctypes.CDLL("libtpipe.so")
init = lib.TPipe_init
init.restype = ctypes.c_int
init.argtypes = []
```

Node.js (FFI):
```javascript
const ffi = require('ffi-napi');
const lib = ffi.Library('libtpipe', {
  'TPipe_init': ['int', []]
});
```

---

## 5. Symbol Versioning and Aliases

No aliases are maintained. Each symbol has exactly one canonical name. Host code must reference the canonical name directly.

---

## 6. Verification Command

The following command verifies the complete exported surface:

```bash
# Linux — extract all TPipe_ symbols
nm -D <tpipe-binary> | awk '$4 ~ /^T/ && $3 ~ /^TPipe_/ { print $3 }' | sort
```

If this list does not match the symbols documented in §2, the build is non-conformant.

---

## 7. Implementation Checklist

| Item | Status |
|------|--------|
| All `@CEntryPoint` symbols use explicit `name = "TPipe_..."` | Required |
| `nm -D` output matches symbol list in §2 | Required |
| No internal symbols (`TpipeBootstrap_*`, `Impl_*`) are exported | Required |
| Symbol stability labels match actual stability guarantees | TBD |
| Windows `dumpbin /EXPORTS` verification documented | TODO |
| macOS `nm -gU` verification documented | TODO |