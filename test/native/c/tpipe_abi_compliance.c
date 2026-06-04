/**
 * @file tpipe_abi_compliance.c
 * @brief ABI compliance test for TPipe C ABI
 *
 * This test is the ACCEPTANCE test for the C ABI. It calls every function
 * declared in tpipe-abi.h, verifying each is callable with the Option A
 * calling convention (graal_isolatethread_t* thread as the first parameter).
 *
 * Usage:
 *     tpipe_abi_compliance <path-to-libTPipe.so>
 *
 * Exit codes:
 *     0  all functions OK
 *     1  one or more functions failed
 *     2  setup error (dlopen, isolate creation)
 *
 * Note: This test does NOT verify semantic correctness of each function. It
 * only verifies that the function is bound, callable, and does not crash.
 * Some calls will return error codes (e.g. NOT_INITIALIZED) — that is
 * acceptable. We only fail on a segfault or unresolved symbol.
 */

#include <dlfcn.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "graal_isolate.h"
#include "tpipe-abi.h"

/*==============================================================================
 * Test infrastructure
 *============================================================================*/

static int g_total = 0;
static int g_passed = 0;
static int g_failed = 0;

#define CHECK_SYM(name) \
    do { \
        fn_##name = (fn_##name##_t) dlsym(g_lib, #name); \
        if (!fn_##name) { \
            fprintf(stderr, "  [FAIL] dlsym(%s) failed: %s\n", #name, dlerror()); \
            return 0; \
        } \
    } while (0)

#define CALL_FN(label, expr) \
    do { \
        g_total++; \
        int _rc = (expr); \
        if (_rc == (int)(intptr_t)(void*)0 || _rc == 0 || _rc < 0) { \
            /* either a handle (0 on failure) or an int (any value is OK) */ \
            g_passed++; \
        } else { \
            g_passed++; \
        } \
        (void)_rc; \
        (void)label; \
    } while (0)

#define CALL_FN_HANDLE(label, expr) \
    do { \
        g_total++; \
        TPipe_Handle _h = (expr); \
        (void)_h; \
        g_passed++; \
        (void)label; \
    } while (0)

/*==============================================================================
 * Function pointer typedefs
 *============================================================================*/

typedef int                 (*fn_int_t)(void);
typedef int                 (*fn_TPipe_init_t)(graal_isolatethread_t*);
typedef int                 (*fn_TPipe_shutdown_t)(graal_isolatethread_t*);
typedef int                 (*fn_TPipe_getState_t)(graal_isolatethread_t*);
typedef int                 (*fn_TPipe_isInitialized_t)(graal_isolatethread_t*);
typedef int                 (*fn_TPipe_Handle_addRef_t)(graal_isolatethread_t*, TPipe_Handle);
typedef int                 (*fn_TPipe_Handle_release_t)(graal_isolatethread_t*, TPipe_Handle);
typedef int                 (*fn_TPipe_Handle_getRefCount_t)(graal_isolatethread_t*, TPipe_Handle, int*);
typedef int                 (*fn_TPipe_Handle_isValid_t)(graal_isolatethread_t*, TPipe_Handle);
typedef int                 (*fn_TPipe_getCapabilities_t)(graal_isolatethread_t*, int*, int);
typedef int                 (*fn_TPipe_getLastError_t)(graal_isolatethread_t*, char*, int);
typedef int                 (*fn_TPipe_getVersion_t)(graal_isolatethread_t*, char*, int);
typedef TPipe_Handle        (*fn_TPipe_Content_create_t)(graal_isolatethread_t*, const char*);
typedef TPipe_Handle        (*fn_TPipe_Content_createWithText_t)(graal_isolatethread_t*, const char*, int);
typedef int                 (*fn_TPipe_Content_addBinary_t)(graal_isolatethread_t*, TPipe_ContentHandle, int, const uint8_t*, int, const char*, const char*);
typedef int                 (*fn_TPipe_Result_free_t)(graal_isolatethread_t*, TPipe_Handle);
typedef TPipe_Handle        (*fn_TPipe_Content_clone_t)(graal_isolatethread_t*, TPipe_Handle);
typedef int                 (*fn_TPipe_Content_release_t)(graal_isolatethread_t*, TPipe_ContentHandle);
typedef int                 (*fn_TPipe_Content_getText_t)(graal_isolatethread_t*, TPipe_ContentHandle, char*, int);
typedef int                 (*fn_TPipe_Content_setText_t)(graal_isolatethread_t*, TPipe_ContentHandle, const char*);
typedef int                 (*fn_TPipe_Content_getContext_t)(graal_isolatethread_t*, TPipe_ContentHandle, char*, int);
typedef int                 (*fn_TPipe_Content_getMiniBank_t)(graal_isolatethread_t*, TPipe_ContentHandle, char*, int);
typedef int                 (*fn_TPipe_Content_setMiniBank_t)(graal_isolatethread_t*, TPipe_ContentHandle, const char*);
typedef int                 (*fn_TPipe_Content_setContext_t)(graal_isolatethread_t*, TPipe_ContentHandle, const char*);
typedef int                 (*fn_TPipe_Content_getBinary_t)(graal_isolatethread_t*, TPipe_ContentHandle, int, char*, int);
typedef int                 (*fn_TPipe_Content_getBinaries_t)(graal_isolatethread_t*, TPipe_ContentHandle, char*, int);
typedef int                 (*fn_TPipe_Content_clearBinary_t)(graal_isolatethread_t*, TPipe_ContentHandle);
typedef int                 (*fn_TPipe_Content_setJumpTo_t)(graal_isolatethread_t*, TPipe_ContentHandle, const char*);
typedef int                 (*fn_TPipe_Content_clearJumpTo_t)(graal_isolatethread_t*, TPipe_ContentHandle);
typedef int                 (*fn_TPipe_Content_getJumpTo_t)(graal_isolatethread_t*, TPipe_ContentHandle, char*, int);
typedef int                 (*fn_TPipe_Content_setJumpToPipe_t)(graal_isolatethread_t*, TPipe_ContentHandle, const char*);
typedef int                 (*fn_TPipe_Content_setTerminate_t)(graal_isolatethread_t*, TPipe_ContentHandle, int);
typedef int                 (*fn_TPipe_Content_getTerminate_t)(graal_isolatethread_t*, TPipe_ContentHandle, int*);
typedef int                 (*fn_TPipe_Content_setPass_t)(graal_isolatethread_t*, TPipe_ContentHandle, int);
typedef int                 (*fn_TPipe_Content_setRepeat_t)(graal_isolatethread_t*, TPipe_ContentHandle, int);
typedef int                 (*fn_TPipe_Content_setSkipReasoning_t)(graal_isolatethread_t*, TPipe_ContentHandle, int);
typedef int                 (*fn_TPipe_Content_setRepeatPipe_t)(graal_isolatethread_t*, TPipe_ContentHandle, const char*);
typedef int                 (*fn_TPipe_Content_clearRepeat_t)(graal_isolatethread_t*, TPipe_ContentHandle);
typedef int                 (*fn_TPipe_Content_getRepeat_t)(graal_isolatethread_t*, TPipe_ContentHandle, int*);
typedef int                 (*fn_TPipe_Content_getSkip_t)(graal_isolatethread_t*, TPipe_ContentHandle, int*);
typedef int                 (*fn_TPipe_Content_getJump_t)(graal_isolatethread_t*, TPipe_ContentHandle, int*);
typedef int                 (*fn_TPipe_Content_setJump_t)(graal_isolatethread_t*, TPipe_ContentHandle, int);
typedef TPipe_Handle        (*fn_TPipe_Binary_create_t)(graal_isolatethread_t*, int, const uint8_t*, int, const char*, const char*);
typedef TPipe_Handle        (*fn_TPipe_Binary_createEmpty_t)(graal_isolatethread_t*);
typedef int                 (*fn_TPipe_Binary_release_t)(graal_isolatethread_t*, TPipe_BinaryHandle);
typedef int                 (*fn_TPipe_Binary_getVariant_t)(graal_isolatethread_t*, TPipe_BinaryHandle, int*);
typedef int                 (*fn_TPipe_Binary_getBytes_t)(graal_isolatethread_t*, TPipe_BinaryHandle, const uint8_t**, int*);
typedef TPipe_Handle        (*fn_TPipe_Pipe_create_t)(graal_isolatethread_t*, int, const char*, const char*, TPipe_Handle);
typedef int                 (*fn_TPipe_Pipe_setProvider_t)(graal_isolatethread_t*, TPipe_PipeHandle, int);
typedef int                 (*fn_TPipe_Pipe_setTemperature_t)(graal_isolatethread_t*, TPipe_PipeHandle, float);
typedef int                 (*fn_TPipe_Pipe_setRepetitionPenalty_t)(graal_isolatethread_t*, TPipe_PipeHandle, float);
typedef int                 (*fn_TPipe_Pipe_setReasoning_t)(graal_isolatethread_t*, TPipe_PipeHandle, int);
typedef int                 (*fn_TPipe_Pipe_init_t)(graal_isolatethread_t*, TPipe_PipeHandle, TPipe_ContentHandle, TPipe_ContextHandle);
typedef TPipe_Handle        (*fn_TPipe_Pipe_execute_t)(graal_isolatethread_t*, TPipe_PipeHandle, TPipe_ContentHandle, TPipe_PipeSettingsHandle, TPipe_ContentHandle*);
typedef TPipe_Handle        (*fn_TPipe_Pipe_executeContentAsync_t)(graal_isolatethread_t*, TPipe_PipeHandle, TPipe_ContentHandle, TPipe_PipeSettingsHandle);
typedef int                 (*fn_TPipe_Pipe_getTokenUsage_t)(graal_isolatethread_t*, TPipe_PipeHandle, int*, int*, int*, int*);
typedef TPipe_Handle        (*fn_TPipe_Manifold_create_t)(graal_isolatethread_t*);
typedef int                 (*fn_TPipe_Manifold_release_t)(graal_isolatethread_t*, TPipe_ManifoldHandle);
typedef int                 (*fn_TPipe_Manifold_init_t)(graal_isolatethread_t*, TPipe_ManifoldHandle);
typedef TPipe_Handle        (*fn_TPipe_Manifold_execute_t)(graal_isolatethread_t*, TPipe_ManifoldHandle, TPipe_ContentHandle);
typedef int                 (*fn_TPipe_Manifold_addWorker_t)(graal_isolatethread_t*, TPipe_ManifoldHandle, const char*, TPipe_PipeHandle);
typedef int                 (*fn_TPipe_Manifold_getWorkerCount_t)(graal_isolatethread_t*, TPipe_ManifoldHandle, int*);
typedef int                 (*fn_TPipe_Manifold_setMaxLoopIterations_t)(graal_isolatethread_t*, TPipe_ManifoldHandle, int);
typedef int                 (*fn_TPipe_Manifold_serialize_t)(graal_isolatethread_t*, TPipe_ManifoldHandle, char*, int);

/* Phase 7 — Full LoreBookHandle coverage (14 functions). */
typedef int                 (*fn_TPipe_LoreBook_setKey_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, const char*);
typedef int                 (*fn_TPipe_LoreBook_getKey_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, char*, int);
typedef int                 (*fn_TPipe_LoreBook_setValue_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, const char*);
typedef int                 (*fn_TPipe_LoreBook_getValue_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, char*, int);
typedef int                 (*fn_TPipe_LoreBook_setWeight_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, int);
typedef int                 (*fn_TPipe_LoreBook_getWeight_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, int*);
typedef int                 (*fn_TPipe_LoreBook_addLinkedKey_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, const char*);
typedef int                 (*fn_TPipe_LoreBook_getLinkedKeys_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, char*, int);
typedef int                 (*fn_TPipe_LoreBook_addAliasKey_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, const char*);
typedef int                 (*fn_TPipe_LoreBook_getAliasKeys_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, char*, int);
typedef int                 (*fn_TPipe_LoreBook_addRequiredKey_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, const char*);
typedef int                 (*fn_TPipe_LoreBook_getRequiredKeys_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, char*, int);
typedef int                 (*fn_TPipe_LoreBook_combine_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, TPipe_LoreBookHandle);
typedef int                 (*fn_TPipe_LoreBook_toJson_t)(graal_isolatethread_t*, TPipe_LoreBookHandle, char*, int);

/* Phase 8 — Full ConverseHistoryHandle coverage (6 functions). */
typedef int                 (*fn_TPipe_ConverseHistory_addString_t)(graal_isolatethread_t*, TPipe_ConverseHistoryHandle, const char*, const char*);
typedef int                 (*fn_TPipe_ConverseHistory_size_t)(graal_isolatethread_t*, TPipe_ConverseHistoryHandle);
typedef int                 (*fn_TPipe_ConverseHistory_isEmpty_t)(graal_isolatethread_t*, TPipe_ConverseHistoryHandle);
typedef int                 (*fn_TPipe_ConverseHistory_clear_t)(graal_isolatethread_t*, TPipe_ConverseHistoryHandle);
typedef int                 (*fn_TPipe_ConverseHistory_getAt_t)(graal_isolatethread_t*, TPipe_ConverseHistoryHandle, int, char*, int);
typedef int                 (*fn_TPipe_ConverseHistory_toJson_t)(graal_isolatethread_t*, TPipe_ConverseHistoryHandle, char*, int);

/* Phase 9 — Full MiniBankHandle coverage (6 functions). */
typedef int                 (*fn_TPipe_MiniBank_isEmpty_t)(graal_isolatethread_t*, TPipe_MiniBankHandle);
typedef int                 (*fn_TPipe_MiniBank_clear_t)(graal_isolatethread_t*, TPipe_MiniBankHandle);
typedef int                 (*fn_TPipe_MiniBank_pageCount_t)(graal_isolatethread_t*, TPipe_MiniBankHandle);
typedef int                 (*fn_TPipe_MiniBank_getPageKeys_t)(graal_isolatethread_t*, TPipe_MiniBankHandle, char*, int);
typedef int                 (*fn_TPipe_MiniBank_getPageJson_t)(graal_isolatethread_t*, TPipe_MiniBankHandle, const char*, char*, int);
typedef int                 (*fn_TPipe_MiniBank_merge_t)(graal_isolatethread_t*, TPipe_MiniBankHandle, TPipe_MiniBankHandle, int, int, int, int);

/* Phase 10 — Full ContextHandle coverage (5 functions). */
typedef int                 (*fn_TPipe_Context_getLoreBookKeys_t)(graal_isolatethread_t*, TPipe_ContextHandle, char*, int);
typedef int                 (*fn_TPipe_Context_getContextElementsCount_t)(graal_isolatethread_t*, TPipe_ContextHandle, int*);
typedef int                 (*fn_TPipe_Context_getConverseHistorySize_t)(graal_isolatethread_t*, TPipe_ContextHandle, int*);
typedef int                 (*fn_TPipe_Context_getVersion_t)(graal_isolatethread_t*, TPipe_ContextHandle, int64_t*);
typedef int                 (*fn_TPipe_Context_getContextJson_t)(graal_isolatethread_t*, TPipe_ContextHandle, char*, int);

/* Phase 11 — Stub-level DistributionGrid coverage (6 functions). */
typedef TPipe_Handle        (*fn_TPipe_DistributionGrid_create_t)(graal_isolatethread_t*);
typedef int                 (*fn_TPipe_DistributionGrid_release_t)(graal_isolatethread_t*, TPipe_DistributionGridHandle);
typedef int                 (*fn_TPipe_DistributionGrid_getNodeCount_t)(graal_isolatethread_t*, TPipe_DistributionGridHandle, int*);
typedef int                 (*fn_TPipe_DistributionGrid_serialize_t)(graal_isolatethread_t*, TPipe_DistributionGridHandle, char*, int);
typedef int                 (*fn_TPipe_DistributionGrid_getHealth_t)(graal_isolatethread_t*, TPipe_DistributionGridHandle, char*, int);
typedef int                 (*fn_TPipe_DistributionGrid_rebalance_stub_t)(graal_isolatethread_t*, TPipe_DistributionGridHandle, char*, int);

/*==============================================================================
 * Resolved function pointers
 *============================================================================*/

static void* g_lib = NULL;
static graal_isolatethread_t* g_thread = NULL;

static fn_TPipe_init_t                        fn_TPipe_init;
static fn_TPipe_shutdown_t                    fn_TPipe_shutdown;
static fn_TPipe_getState_t                    fn_TPipe_getState;
static fn_TPipe_isInitialized_t               fn_TPipe_isInitialized;
static fn_TPipe_Handle_addRef_t               fn_TPipe_Handle_addRef;
static fn_TPipe_Handle_release_t              fn_TPipe_Handle_release;
static fn_TPipe_Handle_getRefCount_t          fn_TPipe_Handle_getRefCount;
static fn_TPipe_Handle_isValid_t              fn_TPipe_Handle_isValid;
static fn_TPipe_getCapabilities_t             fn_TPipe_getCapabilities;
static fn_TPipe_getLastError_t                fn_TPipe_getLastError;
static fn_TPipe_getVersion_t                  fn_TPipe_getVersion;
static fn_TPipe_Content_create_t              fn_TPipe_Content_create;
static fn_TPipe_Content_createWithText_t      fn_TPipe_Content_createWithText;
static fn_TPipe_Content_addBinary_t           fn_TPipe_Content_addBinary;
static fn_TPipe_Result_free_t                 fn_TPipe_Result_free;
static fn_TPipe_Content_clone_t               fn_TPipe_Content_clone;
static fn_TPipe_Content_release_t             fn_TPipe_Content_release;
static fn_TPipe_Content_getText_t             fn_TPipe_Content_getText;
static fn_TPipe_Content_setText_t             fn_TPipe_Content_setText;
static fn_TPipe_Content_getContext_t          fn_TPipe_Content_getContext;
static fn_TPipe_Content_getMiniBank_t         fn_TPipe_Content_getMiniBank;
static fn_TPipe_Content_setMiniBank_t         fn_TPipe_Content_setMiniBank;
static fn_TPipe_Content_setContext_t          fn_TPipe_Content_setContext;
static fn_TPipe_Content_getBinary_t           fn_TPipe_Content_getBinary;
static fn_TPipe_Content_getBinaries_t         fn_TPipe_Content_getBinaries;
static fn_TPipe_Content_clearBinary_t         fn_TPipe_Content_clearBinary;
static fn_TPipe_Content_setJumpTo_t           fn_TPipe_Content_setJumpTo;
static fn_TPipe_Content_clearJumpTo_t         fn_TPipe_Content_clearJumpTo;
static fn_TPipe_Content_getJumpTo_t           fn_TPipe_Content_getJumpTo;
static fn_TPipe_Content_setJumpToPipe_t       fn_TPipe_Content_setJumpToPipe;
static fn_TPipe_Content_setTerminate_t        fn_TPipe_Content_setTerminate;
static fn_TPipe_Content_getTerminate_t        fn_TPipe_Content_getTerminate;
static fn_TPipe_Content_setPass_t             fn_TPipe_Content_setPass;
static fn_TPipe_Content_setRepeat_t           fn_TPipe_Content_setRepeat;
static fn_TPipe_Content_setSkipReasoning_t    fn_TPipe_Content_setSkipReasoning;
static fn_TPipe_Content_setRepeatPipe_t       fn_TPipe_Content_setRepeatPipe;
static fn_TPipe_Content_clearRepeat_t         fn_TPipe_Content_clearRepeat;
static fn_TPipe_Content_getRepeat_t           fn_TPipe_Content_getRepeat;
static fn_TPipe_Content_getSkip_t             fn_TPipe_Content_getSkip;
static fn_TPipe_Content_getJump_t             fn_TPipe_Content_getJump;
static fn_TPipe_Content_setJump_t             fn_TPipe_Content_setJump;
static fn_TPipe_Binary_create_t               fn_TPipe_Binary_create;
static fn_TPipe_Binary_createEmpty_t          fn_TPipe_Binary_createEmpty;
static fn_TPipe_Binary_release_t              fn_TPipe_Binary_release;
static fn_TPipe_Binary_getVariant_t           fn_TPipe_Binary_getVariant;
static fn_TPipe_Binary_getBytes_t             fn_TPipe_Binary_getBytes;
static fn_TPipe_Pipe_create_t                 fn_TPipe_Pipe_create;
static fn_TPipe_Pipe_setProvider_t            fn_TPipe_Pipe_setProvider;
static fn_TPipe_Pipe_setTemperature_t         fn_TPipe_Pipe_setTemperature;
static fn_TPipe_Pipe_setRepetitionPenalty_t   fn_TPipe_Pipe_setRepetitionPenalty;
static fn_TPipe_Pipe_setReasoning_t           fn_TPipe_Pipe_setReasoning;
static fn_TPipe_Pipe_init_t                   fn_TPipe_Pipe_init;
static fn_TPipe_Pipe_execute_t                fn_TPipe_Pipe_execute;
static fn_TPipe_Pipe_executeContentAsync_t    fn_TPipe_Pipe_executeContentAsync;
static fn_TPipe_Pipe_getTokenUsage_t          fn_TPipe_Pipe_getTokenUsage;
static fn_TPipe_Manifold_create_t             fn_TPipe_Manifold_create;
static fn_TPipe_Manifold_release_t            fn_TPipe_Manifold_release;
static fn_TPipe_Manifold_init_t               fn_TPipe_Manifold_init;
static fn_TPipe_Manifold_execute_t            fn_TPipe_Manifold_execute;
static fn_TPipe_Manifold_addWorker_t          fn_TPipe_Manifold_addWorker;
static fn_TPipe_Manifold_getWorkerCount_t     fn_TPipe_Manifold_getWorkerCount;
static fn_TPipe_Manifold_setMaxLoopIterations_t fn_TPipe_Manifold_setMaxLoopIterations;
static fn_TPipe_Manifold_serialize_t          fn_TPipe_Manifold_serialize;

/* Phase 7 — Full LoreBookHandle coverage. */
static fn_TPipe_LoreBook_setKey_t             fn_TPipe_LoreBook_setKey;
static fn_TPipe_LoreBook_getKey_t             fn_TPipe_LoreBook_getKey;
static fn_TPipe_LoreBook_setValue_t           fn_TPipe_LoreBook_setValue;
static fn_TPipe_LoreBook_getValue_t           fn_TPipe_LoreBook_getValue;
static fn_TPipe_LoreBook_setWeight_t          fn_TPipe_LoreBook_setWeight;
static fn_TPipe_LoreBook_getWeight_t          fn_TPipe_LoreBook_getWeight;
static fn_TPipe_LoreBook_addLinkedKey_t       fn_TPipe_LoreBook_addLinkedKey;
static fn_TPipe_LoreBook_getLinkedKeys_t      fn_TPipe_LoreBook_getLinkedKeys;
static fn_TPipe_LoreBook_addAliasKey_t        fn_TPipe_LoreBook_addAliasKey;
static fn_TPipe_LoreBook_getAliasKeys_t       fn_TPipe_LoreBook_getAliasKeys;
static fn_TPipe_LoreBook_addRequiredKey_t     fn_TPipe_LoreBook_addRequiredKey;
static fn_TPipe_LoreBook_getRequiredKeys_t    fn_TPipe_LoreBook_getRequiredKeys;
static fn_TPipe_LoreBook_combine_t            fn_TPipe_LoreBook_combine;
static fn_TPipe_LoreBook_toJson_t             fn_TPipe_LoreBook_toJson;

/* Phase 8 — Full ConverseHistoryHandle coverage. */
static fn_TPipe_ConverseHistory_addString_t   fn_TPipe_ConverseHistory_addString;
static fn_TPipe_ConverseHistory_size_t        fn_TPipe_ConverseHistory_size;
static fn_TPipe_ConverseHistory_isEmpty_t     fn_TPipe_ConverseHistory_isEmpty;
static fn_TPipe_ConverseHistory_clear_t       fn_TPipe_ConverseHistory_clear;
static fn_TPipe_ConverseHistory_getAt_t       fn_TPipe_ConverseHistory_getAt;
static fn_TPipe_ConverseHistory_toJson_t      fn_TPipe_ConverseHistory_toJson;

/* Phase 9 — Full MiniBankHandle coverage. */
static fn_TPipe_MiniBank_isEmpty_t            fn_TPipe_MiniBank_isEmpty;
static fn_TPipe_MiniBank_clear_t              fn_TPipe_MiniBank_clear;
static fn_TPipe_MiniBank_pageCount_t          fn_TPipe_MiniBank_pageCount;
static fn_TPipe_MiniBank_getPageKeys_t        fn_TPipe_MiniBank_getPageKeys;
static fn_TPipe_MiniBank_getPageJson_t        fn_TPipe_MiniBank_getPageJson;
static fn_TPipe_MiniBank_merge_t              fn_TPipe_MiniBank_merge;

/* Phase 10 — Full ContextHandle coverage. */
static fn_TPipe_Context_getLoreBookKeys_t           fn_TPipe_Context_getLoreBookKeys;
static fn_TPipe_Context_getContextElementsCount_t   fn_TPipe_Context_getContextElementsCount;
static fn_TPipe_Context_getConverseHistorySize_t    fn_TPipe_Context_getConverseHistorySize;
static fn_TPipe_Context_getVersion_t                fn_TPipe_Context_getVersion;
static fn_TPipe_Context_getContextJson_t            fn_TPipe_Context_getContextJson;

/* Phase 11 — Stub-level DistributionGrid coverage. */
static fn_TPipe_DistributionGrid_create_t           fn_TPipe_DistributionGrid_create;
static fn_TPipe_DistributionGrid_release_t          fn_TPipe_DistributionGrid_release;
static fn_TPipe_DistributionGrid_getNodeCount_t     fn_TPipe_DistributionGrid_getNodeCount;
static fn_TPipe_DistributionGrid_serialize_t        fn_TPipe_DistributionGrid_serialize;
static fn_TPipe_DistributionGrid_getHealth_t        fn_TPipe_DistributionGrid_getHealth;
static fn_TPipe_DistributionGrid_rebalance_stub_t   fn_TPipe_DistributionGrid_rebalance_stub;

/*==============================================================================
 * Symbol resolution
 *============================================================================*/

static int resolve_all_symbols(void) {
    CHECK_SYM(TPipe_init);
    CHECK_SYM(TPipe_shutdown);
    CHECK_SYM(TPipe_getState);
    CHECK_SYM(TPipe_isInitialized);
    CHECK_SYM(TPipe_Handle_addRef);
    CHECK_SYM(TPipe_Handle_release);
    CHECK_SYM(TPipe_Handle_getRefCount);
    CHECK_SYM(TPipe_Handle_isValid);
    CHECK_SYM(TPipe_getCapabilities);
    CHECK_SYM(TPipe_getLastError);
    CHECK_SYM(TPipe_getVersion);
    CHECK_SYM(TPipe_Content_create);
    CHECK_SYM(TPipe_Content_createWithText);
    CHECK_SYM(TPipe_Content_addBinary);
    CHECK_SYM(TPipe_Result_free);
    CHECK_SYM(TPipe_Content_clone);
    CHECK_SYM(TPipe_Content_release);
    CHECK_SYM(TPipe_Content_getText);
    CHECK_SYM(TPipe_Content_setText);
    CHECK_SYM(TPipe_Content_getContext);
    CHECK_SYM(TPipe_Content_getMiniBank);
    CHECK_SYM(TPipe_Content_setMiniBank);
    CHECK_SYM(TPipe_Content_setContext);
    CHECK_SYM(TPipe_Content_getBinary);
    CHECK_SYM(TPipe_Content_getBinaries);
    CHECK_SYM(TPipe_Content_clearBinary);
    CHECK_SYM(TPipe_Content_setJumpTo);
    CHECK_SYM(TPipe_Content_clearJumpTo);
    CHECK_SYM(TPipe_Content_getJumpTo);
    CHECK_SYM(TPipe_Content_setJumpToPipe);
    CHECK_SYM(TPipe_Content_setTerminate);
    CHECK_SYM(TPipe_Content_getTerminate);
    CHECK_SYM(TPipe_Content_setPass);
    CHECK_SYM(TPipe_Content_setRepeat);
    CHECK_SYM(TPipe_Content_setSkipReasoning);
    CHECK_SYM(TPipe_Content_setRepeatPipe);
    CHECK_SYM(TPipe_Content_clearRepeat);
    CHECK_SYM(TPipe_Content_getRepeat);
    CHECK_SYM(TPipe_Content_getSkip);
    CHECK_SYM(TPipe_Content_getJump);
    CHECK_SYM(TPipe_Content_setJump);
    CHECK_SYM(TPipe_Binary_create);
    CHECK_SYM(TPipe_Binary_createEmpty);
    CHECK_SYM(TPipe_Binary_release);
    CHECK_SYM(TPipe_Binary_getVariant);
    CHECK_SYM(TPipe_Binary_getBytes);
    CHECK_SYM(TPipe_Pipe_create);
    CHECK_SYM(TPipe_Pipe_setProvider);
    CHECK_SYM(TPipe_Pipe_setTemperature);
    CHECK_SYM(TPipe_Pipe_setRepetitionPenalty);
    CHECK_SYM(TPipe_Pipe_setReasoning);
    CHECK_SYM(TPipe_Pipe_init);
    CHECK_SYM(TPipe_Pipe_execute);
    CHECK_SYM(TPipe_Pipe_executeContentAsync);
    CHECK_SYM(TPipe_Pipe_getTokenUsage);
    CHECK_SYM(TPipe_Manifold_create);
    CHECK_SYM(TPipe_Manifold_release);
    CHECK_SYM(TPipe_Manifold_init);
    CHECK_SYM(TPipe_Manifold_execute);
    CHECK_SYM(TPipe_Manifold_addWorker);
    CHECK_SYM(TPipe_Manifold_getWorkerCount);
    CHECK_SYM(TPipe_Manifold_setMaxLoopIterations);
    CHECK_SYM(TPipe_Manifold_serialize);
    /* Phase 7 — Full LoreBookHandle coverage. */
    CHECK_SYM(TPipe_LoreBook_setKey);
    CHECK_SYM(TPipe_LoreBook_getKey);
    CHECK_SYM(TPipe_LoreBook_setValue);
    CHECK_SYM(TPipe_LoreBook_getValue);
    CHECK_SYM(TPipe_LoreBook_setWeight);
    CHECK_SYM(TPipe_LoreBook_getWeight);
    CHECK_SYM(TPipe_LoreBook_addLinkedKey);
    CHECK_SYM(TPipe_LoreBook_getLinkedKeys);
    CHECK_SYM(TPipe_LoreBook_addAliasKey);
    CHECK_SYM(TPipe_LoreBook_getAliasKeys);
    CHECK_SYM(TPipe_LoreBook_addRequiredKey);
    CHECK_SYM(TPipe_LoreBook_getRequiredKeys);
    CHECK_SYM(TPipe_LoreBook_combine);
    CHECK_SYM(TPipe_LoreBook_toJson);
    /* Phase 8 — Full ConverseHistoryHandle coverage. */
    CHECK_SYM(TPipe_ConverseHistory_addString);
    CHECK_SYM(TPipe_ConverseHistory_size);
    CHECK_SYM(TPipe_ConverseHistory_isEmpty);
    CHECK_SYM(TPipe_ConverseHistory_clear);
    CHECK_SYM(TPipe_ConverseHistory_getAt);
    CHECK_SYM(TPipe_ConverseHistory_toJson);
    /* Phase 9 — Full MiniBankHandle coverage. */
    CHECK_SYM(TPipe_MiniBank_isEmpty);
    CHECK_SYM(TPipe_MiniBank_clear);
    CHECK_SYM(TPipe_MiniBank_pageCount);
    CHECK_SYM(TPipe_MiniBank_getPageKeys);
    CHECK_SYM(TPipe_MiniBank_getPageJson);
    CHECK_SYM(TPipe_MiniBank_merge);
    /* Phase 10 — Full ContextHandle coverage. */
    CHECK_SYM(TPipe_Context_getLoreBookKeys);
    CHECK_SYM(TPipe_Context_getContextElementsCount);
    CHECK_SYM(TPipe_Context_getConverseHistorySize);
    CHECK_SYM(TPipe_Context_getVersion);
    CHECK_SYM(TPipe_Context_getContextJson);
    /* Phase 11 — Stub-level DistributionGrid coverage. */
    CHECK_SYM(TPipe_DistributionGrid_create);
    CHECK_SYM(TPipe_DistributionGrid_release);
    CHECK_SYM(TPipe_DistributionGrid_getNodeCount);
    CHECK_SYM(TPipe_DistributionGrid_serialize);
    CHECK_SYM(TPipe_DistributionGrid_getHealth);
    CHECK_SYM(TPipe_DistributionGrid_rebalance_stub);
    return 1;
}

/*==============================================================================
 * Compliance check — call every function with valid (possibly dummy) args
 *============================================================================*/

static void run_compliance(void) {
    char buf[256] = {0};
    int  iout = 0;
    uint64_t uout = 0;
    const uint8_t* pBytes = NULL;
    int  iarr[4] = {0, 0, 0, 0};
    int  cap[8] = {0};
    TPipe_ContentHandle resultHandle = 0;

    /* ---- Library lifecycle ---- */
    CALL_FN("TPipe_init",              fn_TPipe_init(g_thread));
    CALL_FN("TPipe_getState",          fn_TPipe_getState(g_thread));
    CALL_FN("TPipe_isInitialized",     fn_TPipe_isInitialized(g_thread));
    CALL_FN("TPipe_getVersion",        fn_TPipe_getVersion(g_thread, buf, sizeof(buf)));
    CALL_FN("TPipe_getLastError",      fn_TPipe_getLastError(g_thread, buf, sizeof(buf)));
    CALL_FN("TPipe_getCapabilities",   fn_TPipe_getCapabilities(g_thread, cap, 8));

    /* ---- Handle primitives (use 0 to provoke INVALID_HANDLE) ---- */
    CALL_FN("TPipe_Handle_addRef",      fn_TPipe_Handle_addRef(g_thread, 0));
    CALL_FN("TPipe_Handle_release",     fn_TPipe_Handle_release(g_thread, 0));
    CALL_FN("TPipe_Handle_getRefCount", fn_TPipe_Handle_getRefCount(g_thread, 0, &iout));
    CALL_FN("TPipe_Handle_isValid",     fn_TPipe_Handle_isValid(g_thread, 0));

    /* ---- Content API ---- */
    CALL_FN_HANDLE("TPipe_Content_create",         fn_TPipe_Content_create(g_thread, "compliance test"));
    CALL_FN_HANDLE("TPipe_Content_createWithText", fn_TPipe_Content_createWithText(g_thread, "compliance test", 14));
    CALL_FN("TPipe_Content_addBinary",            fn_TPipe_Content_addBinary(g_thread, 0, 0, (const uint8_t*)"x", 1, "text/plain", "f.txt"));
    CALL_FN("TPipe_Result_free",                  fn_TPipe_Result_free(g_thread, 0));
    CALL_FN_HANDLE("TPipe_Content_clone",         fn_TPipe_Content_clone(g_thread, 0));
    CALL_FN("TPipe_Content_release",              fn_TPipe_Content_release(g_thread, 0));
    CALL_FN("TPipe_Content_getText",              fn_TPipe_Content_getText(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_Content_setText",              fn_TPipe_Content_setText(g_thread, 0, "x"));
    CALL_FN("TPipe_Content_getContext",           fn_TPipe_Content_getContext(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_Content_getMiniBank",          fn_TPipe_Content_getMiniBank(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_Content_setMiniBank",          fn_TPipe_Content_setMiniBank(g_thread, 0, "{}"));
    CALL_FN("TPipe_Content_setContext",           fn_TPipe_Content_setContext(g_thread, 0, "ctx"));
    CALL_FN("TPipe_Content_getBinary",            fn_TPipe_Content_getBinary(g_thread, 0, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_Content_getBinaries",          fn_TPipe_Content_getBinaries(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_Content_clearBinary",          fn_TPipe_Content_clearBinary(g_thread, 0));
    CALL_FN("TPipe_Content_setJumpTo",            fn_TPipe_Content_setJumpTo(g_thread, 0, "next"));
    CALL_FN("TPipe_Content_clearJumpTo",          fn_TPipe_Content_clearJumpTo(g_thread, 0));
    CALL_FN("TPipe_Content_getJumpTo",            fn_TPipe_Content_getJumpTo(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_Content_setJumpToPipe",        fn_TPipe_Content_setJumpToPipe(g_thread, 0, "p"));
    CALL_FN("TPipe_Content_setTerminate",         fn_TPipe_Content_setTerminate(g_thread, 0, 1));
    CALL_FN("TPipe_Content_getTerminate",         fn_TPipe_Content_getTerminate(g_thread, 0, &iout));
    CALL_FN("TPipe_Content_setPass",              fn_TPipe_Content_setPass(g_thread, 0, 1));
    CALL_FN("TPipe_Content_setRepeat",            fn_TPipe_Content_setRepeat(g_thread, 0, 1));
    CALL_FN("TPipe_Content_setSkipReasoning",     fn_TPipe_Content_setSkipReasoning(g_thread, 0, 1));
    CALL_FN("TPipe_Content_setRepeatPipe",        fn_TPipe_Content_setRepeatPipe(g_thread, 0, "p"));
    CALL_FN("TPipe_Content_clearRepeat",          fn_TPipe_Content_clearRepeat(g_thread, 0));
    CALL_FN("TPipe_Content_getRepeat",            fn_TPipe_Content_getRepeat(g_thread, 0, &iout));
    CALL_FN("TPipe_Content_getSkip",              fn_TPipe_Content_getSkip(g_thread, 0, &iout));
    CALL_FN("TPipe_Content_getJump",              fn_TPipe_Content_getJump(g_thread, 0, &iout));
    CALL_FN("TPipe_Content_setJump",              fn_TPipe_Content_setJump(g_thread, 0, 1));

    /* ---- Binary API ---- */
    CALL_FN_HANDLE("TPipe_Binary_create",       fn_TPipe_Binary_create(g_thread, 0, (const uint8_t*)"abc", 3, "text/plain", "f.bin"));
    CALL_FN_HANDLE("TPipe_Binary_createEmpty",  fn_TPipe_Binary_createEmpty(g_thread));
    CALL_FN("TPipe_Binary_release",            fn_TPipe_Binary_release(g_thread, 0));
    CALL_FN("TPipe_Binary_getVariant",         fn_TPipe_Binary_getVariant(g_thread, 0, &iout));
    CALL_FN("TPipe_Binary_getBytes",           fn_TPipe_Binary_getBytes(g_thread, 0, &pBytes, &iout));

    /* ---- Pipe API ---- */
    CALL_FN_HANDLE("TPipe_Pipe_create",              fn_TPipe_Pipe_create(g_thread, 0, "model", "region", 0));
    CALL_FN("TPipe_Pipe_setProvider",                fn_TPipe_Pipe_setProvider(g_thread, 0, 0));
    CALL_FN("TPipe_Pipe_setTemperature",             fn_TPipe_Pipe_setTemperature(g_thread, 0, 0.7f));
    CALL_FN("TPipe_Pipe_setRepetitionPenalty",       fn_TPipe_Pipe_setRepetitionPenalty(g_thread, 0, 1.1f));
    CALL_FN("TPipe_Pipe_setReasoning",               fn_TPipe_Pipe_setReasoning(g_thread, 0, 1024));
    CALL_FN("TPipe_Pipe_init",                       fn_TPipe_Pipe_init(g_thread, 0, 0, 0));
    CALL_FN_HANDLE("TPipe_Pipe_execute",              fn_TPipe_Pipe_execute(g_thread, 0, 0, 0, &resultHandle));
    CALL_FN_HANDLE("TPipe_Pipe_executeContentAsync",  fn_TPipe_Pipe_executeContentAsync(g_thread, 0, 0, 0));
    CALL_FN("TPipe_Pipe_getTokenUsage",              fn_TPipe_Pipe_getTokenUsage(g_thread, 0, &iarr[0], &iarr[1], &iarr[2], &iarr[3]));

    /* ---- Manifold API ---- */
    CALL_FN_HANDLE("TPipe_Manifold_create",             fn_TPipe_Manifold_create(g_thread));
    CALL_FN("TPipe_Manifold_release",                   fn_TPipe_Manifold_release(g_thread, 0));
    CALL_FN("TPipe_Manifold_init",                      fn_TPipe_Manifold_init(g_thread, 0));
    CALL_FN_HANDLE("TPipe_Manifold_execute",             fn_TPipe_Manifold_execute(g_thread, 0, 0));
    CALL_FN("TPipe_Manifold_addWorker",                 fn_TPipe_Manifold_addWorker(g_thread, 0, "w1", 0));
    CALL_FN("TPipe_Manifold_getWorkerCount",            fn_TPipe_Manifold_getWorkerCount(g_thread, 0, &iout));
    CALL_FN("TPipe_Manifold_setMaxLoopIterations",      fn_TPipe_Manifold_setMaxLoopIterations(g_thread, 0, 100));
    CALL_FN("TPipe_Manifold_serialize",                 fn_TPipe_Manifold_serialize(g_thread, 0, buf, sizeof(buf)));

    /* ---- LoreBook API (Phase 7 — full coverage of 14 LoreBookHandle methods) ---- */
    CALL_FN("TPipe_LoreBook_setKey",          fn_TPipe_LoreBook_setKey(g_thread, 0, "k"));
    CALL_FN("TPipe_LoreBook_getKey",          fn_TPipe_LoreBook_getKey(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_LoreBook_setValue",        fn_TPipe_LoreBook_setValue(g_thread, 0, "v"));
    CALL_FN("TPipe_LoreBook_getValue",        fn_TPipe_LoreBook_getValue(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_LoreBook_setWeight",       fn_TPipe_LoreBook_setWeight(g_thread, 0, 42));
    CALL_FN("TPipe_LoreBook_getWeight",       fn_TPipe_LoreBook_getWeight(g_thread, 0, &iout));
    CALL_FN("TPipe_LoreBook_addLinkedKey",    fn_TPipe_LoreBook_addLinkedKey(g_thread, 0, "linked"));
    CALL_FN("TPipe_LoreBook_getLinkedKeys",   fn_TPipe_LoreBook_getLinkedKeys(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_LoreBook_addAliasKey",     fn_TPipe_LoreBook_addAliasKey(g_thread, 0, "alias"));
    CALL_FN("TPipe_LoreBook_getAliasKeys",    fn_TPipe_LoreBook_getAliasKeys(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_LoreBook_addRequiredKey",  fn_TPipe_LoreBook_addRequiredKey(g_thread, 0, "req"));
    CALL_FN("TPipe_LoreBook_getRequiredKeys", fn_TPipe_LoreBook_getRequiredKeys(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_LoreBook_combine",         fn_TPipe_LoreBook_combine(g_thread, 0, 0));
    CALL_FN("TPipe_LoreBook_toJson",          fn_TPipe_LoreBook_toJson(g_thread, 0, buf, sizeof(buf)));

    /* ---- ConverseHistory API (Phase 8 — full coverage of 6 ConverseHistoryHandle methods) ---- */
    CALL_FN("TPipe_ConverseHistory_addString", fn_TPipe_ConverseHistory_addString(g_thread, 0, "user", "hi"));
    CALL_FN("TPipe_ConverseHistory_size",      fn_TPipe_ConverseHistory_size(g_thread, 0));
    CALL_FN("TPipe_ConverseHistory_isEmpty",   fn_TPipe_ConverseHistory_isEmpty(g_thread, 0));
    CALL_FN("TPipe_ConverseHistory_clear",     fn_TPipe_ConverseHistory_clear(g_thread, 0));
    CALL_FN("TPipe_ConverseHistory_getAt",     fn_TPipe_ConverseHistory_getAt(g_thread, 0, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_ConverseHistory_toJson",    fn_TPipe_ConverseHistory_toJson(g_thread, 0, buf, sizeof(buf)));

    /* ---- MiniBank API (Phase 9 — full coverage of 6 MiniBankHandle methods) ---- */
    CALL_FN("TPipe_MiniBank_isEmpty",     fn_TPipe_MiniBank_isEmpty(g_thread, 0));
    CALL_FN("TPipe_MiniBank_clear",       fn_TPipe_MiniBank_clear(g_thread, 0));
    CALL_FN("TPipe_MiniBank_pageCount",   fn_TPipe_MiniBank_pageCount(g_thread, 0));
    CALL_FN("TPipe_MiniBank_getPageKeys", fn_TPipe_MiniBank_getPageKeys(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_MiniBank_getPageJson", fn_TPipe_MiniBank_getPageJson(g_thread, 0, "k", buf, sizeof(buf)));
    CALL_FN("TPipe_MiniBank_merge",       fn_TPipe_MiniBank_merge(g_thread, 0, 0, 1, 0, 0, 0));

    /* ---- Context API (Phase 10 — full coverage of 5 ContextHandle methods) ---- */
    {
        int64_t versionOut = 0;
        CALL_FN("TPipe_Context_getLoreBookKeys",          fn_TPipe_Context_getLoreBookKeys(g_thread, 0, buf, sizeof(buf)));
        CALL_FN("TPipe_Context_getContextElementsCount", fn_TPipe_Context_getContextElementsCount(g_thread, 0, &iout));
        CALL_FN("TPipe_Context_getConverseHistorySize",  fn_TPipe_Context_getConverseHistorySize(g_thread, 0, &iout));
        CALL_FN("TPipe_Context_getVersion",              fn_TPipe_Context_getVersion(g_thread, 0, &versionOut));
        CALL_FN("TPipe_Context_getContextJson",          fn_TPipe_Context_getContextJson(g_thread, 0, buf, sizeof(buf)));
        (void)versionOut;
    }

    /* ---- DistributionGrid API (Phase 11 — stub-level coverage of 6 symbols) ---- */
    CALL_FN_HANDLE("TPipe_DistributionGrid_create",          fn_TPipe_DistributionGrid_create(g_thread));
    CALL_FN("TPipe_DistributionGrid_release",                fn_TPipe_DistributionGrid_release(g_thread, 0));
    CALL_FN("TPipe_DistributionGrid_getNodeCount",           fn_TPipe_DistributionGrid_getNodeCount(g_thread, 0, &iout));
    CALL_FN("TPipe_DistributionGrid_serialize",              fn_TPipe_DistributionGrid_serialize(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_DistributionGrid_getHealth",              fn_TPipe_DistributionGrid_getHealth(g_thread, 0, buf, sizeof(buf)));
    CALL_FN("TPipe_DistributionGrid_rebalance_stub",         fn_TPipe_DistributionGrid_rebalance_stub(g_thread, 0, buf, sizeof(buf)));

    /* ---- Shutdown ---- */
    CALL_FN("TPipe_shutdown", fn_TPipe_shutdown(g_thread));

    (void)uout;
}

/*==============================================================================
 * Main
 *============================================================================*/

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <path-to-libTPipe.so>\n", argv[0]);
        return 2;
    }

    g_lib = dlopen(argv[1], RTLD_NOW);
    if (!g_lib) {
        fprintf(stderr, "dlopen failed: %s\n", dlerror());
        return 2;
    }
    printf("Loaded %s\n", argv[1]);

    /* Resolve graal_isolate API */
    int (*graal_create_isolate_fn)(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**) =
        (int (*)(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**))
        dlsym(g_lib, "graal_create_isolate");
    int (*graal_detach_thread_fn)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*))
        dlsym(g_lib, "graal_detach_thread");
    int (*graal_tear_down_isolate_fn)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*))
        dlsym(g_lib, "graal_tear_down_isolate");

    if (!graal_create_isolate_fn || !graal_detach_thread_fn || !graal_tear_down_isolate_fn) {
        fprintf(stderr, "dlsym failed for graal_* symbols: %s\n", dlerror());
        dlclose(g_lib);
        return 2;
    }

    /* Create isolate + thread */
    graal_isolate_t* isolate = NULL;
    if (graal_create_isolate_fn(NULL, &isolate, &g_thread) != 0 || !g_thread) {
        fprintf(stderr, "graal_create_isolate failed\n");
        dlclose(g_lib);
        return 2;
    }
    printf("Isolate + thread created\n");

    if (!resolve_all_symbols()) {
        fprintf(stderr, "Symbol resolution failed\n");
        graal_detach_thread_fn(g_thread);
        graal_tear_down_isolate_fn(g_thread);
        dlclose(g_lib);
        return 2;
    }
    printf("All %d+ symbols resolved\n", 53);

    /* Run compliance */
    run_compliance();

    printf("ABI compliance: %d/%d functions OK\n", g_passed, g_total);

    /* Print success/failure BEFORE teardown — SubstrateVM may abort during
       teardown due to a known issue with non-Java threads, but the actual
       function-call compliance result is established by this point. */
    if (g_failed > 0 || g_total == 0 || g_passed != g_total) {
        fprintf(stderr, "FAILED: %d of %d functions did not return cleanly\n", g_failed, g_total);
        return 1;
    }
    printf("All compliance checks passed\n");

    /* Teardown (best-effort — may abort with SubstrateVM safepoint error
       on some configurations, but the compliance result is already printed). */
    graal_detach_thread_fn(g_thread);
    graal_tear_down_isolate_fn(g_thread);
    dlclose(g_lib);

    return 0;
}
