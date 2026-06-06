
/**
 * @file tpipe_abi_symbols_coverage.c
 * @brief ABI symbol coverage test (GENERATED - do not edit by hand).
 *
 * Regenerate with:
 *     test/native/c/generate_abi_symbols_coverage.py \
 *         src/main/resources/tpipe-abi.h \
 *         test/native/c/tpipe_abi_symbols_coverage.c
 *
 * Exit codes:
 *     0  every declared TPipe_* function is exported by the .so (PASS)
 *     1  one or more declared TPipe_* functions are missing from the .so
 *     2  setup error (bad argv, dlopen failure)
 *
 * This test does NOT verify that the symbols do anything sensible.
 * It only verifies the symbol exists in the .so. The Kotlin
 * AbiParityMatrixTest is the source of truth for "called from
 * JVM = exported in .so" and "declared in header = has @CEntryPoint".
 */

#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_NAME_LEN 256
#define MAX_MISSING 1024

static void* g_lib = NULL;
static int g_total = 0;
static int g_present = 0;
static char g_missing[MAX_MISSING][MAX_NAME_LEN];
static int g_missing_count = 0;

static void record_missing(const char* name) {
    if (g_missing_count >= MAX_MISSING) return;
    snprintf(g_missing[g_missing_count], MAX_NAME_LEN, "%s", name);
    g_missing_count++;
}

static int check(const char* name) {
    g_total++;
    void* sym = dlsym(g_lib, name);
    if (sym == NULL) {
        record_missing(name);
        return 0;
    }
    g_present++;
    return 1;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <path-to-libTPipe.so>\n", argv[0]);
        return 2;
    }
    const char* lib_path = argv[1];
    g_lib = dlopen(lib_path, RTLD_NOW);
    if (!g_lib) {
        fprintf(stderr, "dlopen('%s') failed: %s\n", lib_path, dlerror());
        return 2;
    }
    printf("Loaded %s\n", lib_path);

    static const char* SYMBOL_LIST[] = {
        "TPipe_AsyncHandle_cancel",
        "TPipe_AsyncHandle_create",
        "TPipe_AsyncHandle_getResult",
        "TPipe_AsyncHandle_isDone",
        "TPipe_AsyncHandle_poll",
        "TPipe_AsyncHandle_wait",
        "TPipe_Binary_create",
        "TPipe_Binary_createEmpty",
        "TPipe_Binary_getBytes",
        "TPipe_Binary_getVariant",
        "TPipe_Binary_release",
        "TPipe_Connector_create",
        "TPipe_Connector_execute",
        "TPipe_Connector_init",
        "TPipe_Connector_release",
        "TPipe_Connector_serialize",
        "TPipe_Content_addBinary",
        "TPipe_Content_clearBinary",
        "TPipe_Content_clearJumpTo",
        "TPipe_Content_clearRepeat",
        "TPipe_Content_clone",
        "TPipe_Content_create",
        "TPipe_Content_createWithText",
        "TPipe_Content_getBinaries",
        "TPipe_Content_getBinary",
        "TPipe_Content_getContext",
        "TPipe_Content_getJump",
        "TPipe_Content_getJumpTo",
        "TPipe_Content_getMiniBank",
        "TPipe_Content_getRepeat",
        "TPipe_Content_getSkip",
        "TPipe_Content_getTerminate",
        "TPipe_Content_getText",
        "TPipe_Content_release",
        "TPipe_Content_setContext",
        "TPipe_Content_setJump",
        "TPipe_Content_setJumpTo",
        "TPipe_Content_setJumpToPipe",
        "TPipe_Content_setMiniBank",
        "TPipe_Content_setPass",
        "TPipe_Content_setRepeat",
        "TPipe_Content_setRepeatPipe",
        "TPipe_Content_setSkipReasoning",
        "TPipe_Content_setTerminate",
        "TPipe_Content_setText",
        "TPipe_ContextWindow_create",
        "TPipe_Context_getContextElementsCount",
        "TPipe_Context_getContextJson",
        "TPipe_Context_getConverseHistorySize",
        "TPipe_Context_getLoreBookKeys",
        "TPipe_Context_getVersion",
        "TPipe_ConverseHistory_add",
        "TPipe_ConverseHistory_addString",
        "TPipe_ConverseHistory_clear",
        "TPipe_ConverseHistory_getAt",
        "TPipe_ConverseHistory_isEmpty",
        "TPipe_ConverseHistory_size",
        "TPipe_ConverseHistory_toJson",
        "TPipe_DistributionGrid_create",
        "TPipe_DistributionGrid_getHealth",
        "TPipe_DistributionGrid_getLastRebalanceMs",
        "TPipe_DistributionGrid_getNodeCount",
        "TPipe_DistributionGrid_getNodeCount_v2",
        "TPipe_DistributionGrid_getStatusJson",
        "TPipe_DistributionGrid_rebalance_stub",
        "TPipe_DistributionGrid_release",
        "TPipe_DistributionGrid_serialize",
        "TPipe_Handle_addRef",
        "TPipe_Handle_getRefCount",
        "TPipe_Handle_isValid",
        "TPipe_Handle_release",
        "TPipe_Junction_create",
        "TPipe_Junction_execute",
        "TPipe_Junction_init",
        "TPipe_Junction_release",
        "TPipe_Junction_serialize",
        "TPipe_List_append",
        "TPipe_List_create",
        "TPipe_List_get",
        "TPipe_List_size",
        "TPipe_LoreBook_addAliasKey",
        "TPipe_LoreBook_addEntry",
        "TPipe_LoreBook_addLinkedKey",
        "TPipe_LoreBook_addRequiredKey",
        "TPipe_LoreBook_combine",
        "TPipe_LoreBook_create",
        "TPipe_LoreBook_getAliasKeys",
        "TPipe_LoreBook_getKey",
        "TPipe_LoreBook_getLinkedKeys",
        "TPipe_LoreBook_getRequiredKeys",
        "TPipe_LoreBook_getValue",
        "TPipe_LoreBook_getWeight",
        "TPipe_LoreBook_setKey",
        "TPipe_LoreBook_setValue",
        "TPipe_LoreBook_setWeight",
        "TPipe_LoreBook_toJson",
        "TPipe_Manifold_addWorker",
        "TPipe_Manifold_create",
        "TPipe_Manifold_execute",
        "TPipe_Manifold_getWorkerCount",
        "TPipe_Manifold_init",
        "TPipe_Manifold_release",
        "TPipe_Manifold_serialize",
        "TPipe_Manifold_setMaxLoopIterations",
        "TPipe_Map_create",
        "TPipe_Map_get",
        "TPipe_Map_has",
        "TPipe_Map_set",
        "TPipe_Map_size",
        "TPipe_MiniBank_clear",
        "TPipe_MiniBank_create",
        "TPipe_MiniBank_getPageJson",
        "TPipe_MiniBank_getPageKeys",
        "TPipe_MiniBank_isEmpty",
        "TPipe_MiniBank_merge",
        "TPipe_MiniBank_pageCount",
        "TPipe_MiniBank_set",
        "TPipe_P2PHandle_connect",
        "TPipe_P2PHandle_create",
        "TPipe_P2PHandle_registerAgent",
        "TPipe_P2PHandle_send",
        "TPipe_PCPHandle_create",
        "TPipe_PCPHandle_execute",
        "TPipe_PipeSettings_create",
        "TPipe_PipeSettings_release",
        "TPipe_PipeSettings_setBool",
        "TPipe_PipeSettings_setFloat",
        "TPipe_PipeSettings_setInt",
        "TPipe_PipeSettings_setMaxTokens",
        "TPipe_PipeSettings_setModel",
        "TPipe_PipeSettings_setProvider",
        "TPipe_PipeSettings_setString",
        "TPipe_PipeSettings_setTemperature",
        "TPipe_PipeSettings_setTimeout",
        "TPipe_Pipe_create",
        "TPipe_Pipe_execute",
        "TPipe_Pipe_executeContentAsync",
        "TPipe_Pipe_getTokenUsage",
        "TPipe_Pipe_init",
        "TPipe_Pipe_setProvider",
        "TPipe_Pipe_setReasoning",
        "TPipe_Pipe_setRepetitionPenalty",
        "TPipe_Pipe_setTemperature",
        "TPipe_Pipeline_add",
        "TPipe_Pipeline_create",
        "TPipe_Pipeline_execute",
        "TPipe_Pipeline_getContextWindow",
        "TPipe_Pipeline_getMiniBank",
        "TPipe_Pipeline_getName",
        "TPipe_Pipeline_getOutcome",
        "TPipe_Pipeline_release",
        "TPipe_Pipeline_setName",
        "TPipe_Result_free",
        "TPipe_Splitter_create",
        "TPipe_Splitter_execute",
        "TPipe_Splitter_init",
        "TPipe_Splitter_release",
        "TPipe_Splitter_serialize",
        "TPipe_getCapabilities",
        "TPipe_getLastError",
        "TPipe_getState",
        "TPipe_getVersion",
        "TPipe_init",
        "TPipe_isInitialized",
        "TPipe_main",
        "TPipe_shutdown",
    };
    for (size_t i = 0; i < sizeof(SYMBOL_LIST)/sizeof(SYMBOL_LIST[0]); i++) {
        check(SYMBOL_LIST[i]);
    }

    printf("Checking %d declared TPipe_* symbols...\n",
           (int)(sizeof(SYMBOL_LIST)/sizeof(SYMBOL_LIST[0])));
    printf("\n=== TPipe ABI Symbol Coverage ===\n");
    printf("Declared symbols checked: %d\n", g_total);
    printf("Symbols present in .so:  %d\n", g_present);
    printf("Symbols missing:          %d\n", g_missing_count);

    if (g_missing_count == 0) {
        printf("RESULT: PASS\n");
        dlclose(g_lib);
        return 0;
    }
    printf("RESULT: FAIL - %d symbols are declared in tpipe-abi.h but not exported\n", g_missing_count);
    printf("\nMissing symbols:\n");
    for (int i = 0; i < g_missing_count; i++) {
        printf("  - %s\n", g_missing[i]);
    }
    dlclose(g_lib);
    return 1;
}
