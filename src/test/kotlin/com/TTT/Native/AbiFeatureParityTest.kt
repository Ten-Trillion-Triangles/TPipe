package com.TTT.Native

import org.graalvm.nativeimage.IsolateThread
import org.graalvm.nativeimage.c.function.CEntryPoint
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 2: ABI Feature Parity Scoring.
 *
 * The AbiParityMatrixTest verifies the C ABI surface (Java @CEntryPoint <-> C
 * header declarations <-> exported binary symbols). This test goes further:
 * it scores the FEATURE PARITY between the native C ABI and the JVM-side
 * TPipe public API.
 *
 * Parity is measured per major subsystem:
 *   - Pipe.kt        (LLM interaction)
 *   - Pipeline.kt    (orchestration)
 *   - Manifold.kt    (manager/worker multi-agent)
 *   - Junction.kt    (voting / decision workflows)
 *   - Splitter.kt    (parallel pipeline fan-out)
 *   - Connector.kt   (path-based dispatch)
 *   - DistributionGrid.kt (P2P distributed)
 *   - ContextWindow.kt   (memory/context)
 *   - LoreBook.kt    (knowledge entries)
 *
 * For each subsystem, a "feature group" is defined that names the logical
 * operations the C ABI must expose. The test counts how many of these are
 * actually present in TPipeBootstrap.java and reports the score.
 *
 * The score is informational — it is NOT a hard pass/fail gate. The test
 * prints the scorecard for the audit log and asserts that no feature
 * group has regressed below the previous baseline.
 */
class AbiFeatureParityTest {

    /**
     * A feature group maps a logical operation family to the @CEntryPoint
     * symbol name(s) the C ABI should expose.
     */
    private data class FeatureGroup(
        val name: String,
        val requiredSymbols: List<String>
    )

    private val featureGroups: List<FeatureGroup> = listOf(
        // Core lifecycle (bootstrap)
        FeatureGroup("Core bootstrap",
            listOf("TPipe_init", "TPipe_shutdown", "TPipe_getState",
                "TPipe_isInitialized", "TPipe_getVersion",
                "TPipe_getCapabilities", "TPipe_getLastError", "TPipe_main",
                "TPipe_free", "TPipe_Result_free")),
        FeatureGroup("Handle lifecycle",
            listOf("TPipe_Handle_addRef", "TPipe_Handle_release",
                "TPipe_Handle_getRefCount", "TPipe_Handle_isValid")),

        // Pipe configuration
        FeatureGroup("Pipe lifecycle",
            listOf("TPipe_Pipe_create", "TPipe_Pipe_init", "TPipe_Pipe_execute",
                "TPipe_Pipe_executeContentAsync", "TPipe_Pipe_getTokenUsage")),
        FeatureGroup("Pipe direct setters",
            listOf("TPipe_Pipe_setProvider", "TPipe_Pipe_setTemperature",
                "TPipe_Pipe_setRepetitionPenalty", "TPipe_Pipe_setReasoning",
                // Cycle 4 — prompt + sampling surface
                "TPipe_Pipe_setSystemPrompt", "TPipe_Pipe_getSystemPrompt",
                "TPipe_Pipe_setUserPrompt", "TPipe_Pipe_setMiddlePrompt",
                "TPipe_Pipe_setFooterPrompt", "TPipe_Pipe_setTopP",
                "TPipe_Pipe_setTopK", "TPipe_Pipe_setMaxTokens",
                "TPipe_Pipe_setSeed", "TPipe_Pipe_setStopSequences",
                // Cycle 5 — JSON / multimodal / binary surface
                "TPipe_Pipe_setJsonInput", "TPipe_Pipe_setJsonOutput",
                "TPipe_Pipe_setJsonInputInstructions", "TPipe_Pipe_setJsonOutputInstructions",
                "TPipe_Pipe_requireJsonPromptInjection",
                "TPipe_Pipe_setMultimodalInput", "TPipe_Pipe_getCachedInput",
                "TPipe_Pipe_setMergedPcpJsonInstructions",
                "TPipe_Pipe_cacheInput", "TPipe_Pipe_forceCacheInput",
                // Cycle 6 — tracing / compression / token-budget surface
                "TPipe_Pipe_enableTracing", "TPipe_Pipe_disableTracing",
                "TPipe_Pipe_addTraceId", "TPipe_Pipe_removeTraceId",
                "TPipe_Pipe_clearTraceIds", "TPipe_Pipe_getActiveTraceId",
                "TPipe_Pipe_enableSemanticCompression",
                "TPipe_Pipe_enableSemanticDecompression",
                "TPipe_Pipe_enableMaxTokenOverflow",
                "TPipe_Pipe_isAutoTruncateContextEnabled",
                // Cycle 7 — Pipe hooks (DSL suspend-lambda stubs) + P2P/PCP/ContextBank
                "TPipe_Pipe_setRetryFunction",
                "TPipe_Pipe_setExceptionFunction",
                "TPipe_Pipe_setStringValidatorFunction",
                "TPipe_Pipe_setTransformationFunction",
                "TPipe_Pipe_setPreInitFunction",
                "TPipe_Pipe_setPreValidationFunction",
                "TPipe_Pipe_setPreInvokeFunction",
                "TPipe_Pipe_setPostGenerateFunction",
                "TPipe_Pipe_setPcPContext",
                "TPipe_Pipe_enableMemoryIntrospection")),
        FeatureGroup("PipeSettings builder",
            listOf("TPipe_PipeSettings_create", "TPipe_PipeSettings_release",
                "TPipe_PipeSettings_setModel", "TPipe_PipeSettings_setProvider",
                "TPipe_PipeSettings_setTemperature", "TPipe_PipeSettings_setMaxTokens",
                "TPipe_PipeSettings_setTimeout", "TPipe_PipeSettings_setString",
                "TPipe_PipeSettings_setInt", "TPipe_PipeSettings_setFloat",
                "TPipe_PipeSettings_setBool")),

        // Pipeline
        FeatureGroup("Pipeline lifecycle",
            listOf("TPipe_Pipeline_create", "TPipe_Pipeline_release",
                "TPipe_Pipeline_add", "TPipe_Pipeline_execute",
                "TPipe_Pipeline_setName", "TPipe_Pipeline_getName",
                "TPipe_Pipeline_getOutcome", "TPipe_Pipeline_getContextWindow",
                "TPipe_Pipeline_getMiniBank")),

        // Container: Manifold
        FeatureGroup("Manifold lifecycle",
            listOf("TPipe_Manifold_create", "TPipe_Manifold_init",
                "TPipe_Manifold_execute", "TPipe_Manifold_release",
                "TPipe_Manifold_serialize", "TPipe_Manifold_addWorker",
                "TPipe_Manifold_setMaxLoopIterations", "TPipe_Manifold_getWorkerCount",
                // Cycle 3: configuration surface
                "TPipe_Manifold_setContextWindowSize", "TPipe_Manifold_getContextWindowSize",
                "TPipe_Manifold_setTruncationMethod", "TPipe_Manifold_getTruncationMethod",
                "TPipe_Manifold_setSummaryMode", "TPipe_Manifold_getSummaryMode",
                "TPipe_Manifold_getMaxLoopIterations", "TPipe_Manifold_hasLoopLimit",
                "TPipe_Manifold_getWorkerPipelines",
                "TPipe_Manifold_setManagerTokenBudget", "TPipe_Manifold_getManagerTokenBudget",
                "TPipe_Manifold_getManagerPipeline")),

        // Container: Junction
        FeatureGroup("Junction lifecycle",
            listOf("TPipe_Junction_create", "TPipe_Junction_init",
                "TPipe_Junction_execute", "TPipe_Junction_release",
                "TPipe_Junction_serialize",
                // Cycle 3: configuration surface
                "TPipe_Junction_setStrategy", "TPipe_Junction_getStrategy",
                "TPipe_Junction_setRounds", "TPipe_Junction_getRounds",
                "TPipe_Junction_setVotingThreshold", "TPipe_Junction_getVotingThreshold",
                "TPipe_Junction_setMaxNestedDepth", "TPipe_Junction_getMaxNestedDepth",
                "TPipe_Junction_setWorkflowRecipe", "TPipe_Junction_getWorkflowRecipe",
                "TPipe_Junction_setMemoryPolicy", "TPipe_Junction_getMemoryPolicy",
                "TPipe_Junction_getMemoryPolicyEx",
                "TPipe_Junction_enableTracing", "TPipe_Junction_disableTracing",
                "TPipe_Junction_getTraceId", "TPipe_Junction_getFailureAnalysis")),

        // Container: Splitter
        FeatureGroup("Splitter lifecycle",
            listOf("TPipe_Splitter_create", "TPipe_Splitter_init",
                "TPipe_Splitter_execute", "TPipe_Splitter_release",
                "TPipe_Splitter_serialize",
                // Cycle 3: configuration surface
                "TPipe_Splitter_addPipeline", "TPipe_Splitter_removePipeline",
                "TPipe_Splitter_getAllChildPipelines", "TPipe_Splitter_getChildCount")),

        // Container: Connector
        FeatureGroup("Connector lifecycle",
            listOf("TPipe_Connector_create", "TPipe_Connector_init",
                "TPipe_Connector_execute", "TPipe_Connector_release",
                "TPipe_Connector_serialize",
                // Cycle 3: configuration surface
                "TPipe_Connector_add", "TPipe_Connector_get")),

        // Container: DistributionGrid
        FeatureGroup("DistributionGrid lifecycle",
            listOf("TPipe_DistributionGrid_create", "TPipe_DistributionGrid_release",
                "TPipe_DistributionGrid_serialize", "TPipe_DistributionGrid_getNodeCount",
                "TPipe_DistributionGrid_getNodeCount_v2",
                "TPipe_DistributionGrid_getHealth",
                "TPipe_DistributionGrid_getStatusJson",
                "TPipe_DistributionGrid_getLastRebalanceMs",
                "TPipe_DistributionGrid_rebalance_stub")),

        // Context
        FeatureGroup("Context window",
            listOf("TPipe_ContextWindow_create", "TPipe_Context_getVersion",
                "TPipe_Context_getContextElementsCount",
                "TPipe_Context_getContextJson",
                "TPipe_Context_getConverseHistorySize",
                "TPipe_Context_getLoreBookKeys")),

        // LoreBook
        FeatureGroup("LoreBook",
            listOf("TPipe_LoreBook_create", "TPipe_LoreBook_addEntry",
                "TPipe_LoreBook_addAliasKey", "TPipe_LoreBook_addLinkedKey",
                "TPipe_LoreBook_addRequiredKey", "TPipe_LoreBook_setKey",
                "TPipe_LoreBook_setValue", "TPipe_LoreBook_setWeight",
                "TPipe_LoreBook_getKey", "TPipe_LoreBook_getValue",
                "TPipe_LoreBook_getWeight", "TPipe_LoreBook_getAliasKeys",
                "TPipe_LoreBook_getLinkedKeys", "TPipe_LoreBook_getRequiredKeys",
                "TPipe_LoreBook_combine", "TPipe_LoreBook_toJson")),

        // ConverseHistory
        FeatureGroup("ConverseHistory",
            listOf("TPipe_ConverseHistory_create", "TPipe_ConverseHistory_add",
                "TPipe_ConverseHistory_addString", "TPipe_ConverseHistory_size",
                "TPipe_ConverseHistory_isEmpty", "TPipe_ConverseHistory_clear",
                "TPipe_ConverseHistory_getAt", "TPipe_ConverseHistory_toJson")),

        // MiniBank
        FeatureGroup("MiniBank",
            listOf("TPipe_MiniBank_create", "TPipe_MiniBank_set",
                "TPipe_MiniBank_get", "TPipe_MiniBank_clear",
                "TPipe_MiniBank_isEmpty", "TPipe_MiniBank_pageCount",
                "TPipe_MiniBank_getPageKeys", "TPipe_MiniBank_getPageJson",
                "TPipe_MiniBank_merge")),

        // Content
        FeatureGroup("Content",
            listOf("TPipe_Content_create", "TPipe_Content_createWithText",
                "TPipe_Content_clone", "TPipe_Content_release",
                "TPipe_Content_getText", "TPipe_Content_setText",
                "TPipe_Content_getContext", "TPipe_Content_setContext",
                "TPipe_Content_getMiniBank", "TPipe_Content_setMiniBank",
                "TPipe_Content_addBinary", "TPipe_Content_getBinary",
                "TPipe_Content_getBinaries", "TPipe_Content_clearBinary",
                "TPipe_Content_setJumpTo", "TPipe_Content_clearJumpTo",
                "TPipe_Content_getJumpTo", "TPipe_Content_setJumpToPipe",
                "TPipe_Content_setTerminate", "TPipe_Content_getTerminate",
                "TPipe_Content_setPass", "TPipe_Content_setRepeat",
                "TPipe_Content_clearRepeat", "TPipe_Content_getRepeat",
                "TPipe_Content_setSkipReasoning", "TPipe_Content_getSkip",
                "TPipe_Content_getJump", "TPipe_Content_setJump",
                "TPipe_Content_setRepeatPipe")),

        // Binary
        FeatureGroup("Binary",
            listOf("TPipe_Binary_create", "TPipe_Binary_createEmpty",
                "TPipe_Binary_release", "TPipe_Binary_getVariant",
                "TPipe_Binary_getBytes")),

        // Collections
        FeatureGroup("List",
            listOf("TPipe_List_create", "TPipe_List_append",
                "TPipe_List_get", "TPipe_List_size")),
        FeatureGroup("Map",
            listOf("TPipe_Map_create", "TPipe_Map_set",
                "TPipe_Map_get", "TPipe_Map_has", "TPipe_Map_size")),

        // Async
        FeatureGroup("Async",
            listOf("TPipe_AsyncHandle_create", "TPipe_AsyncHandle_cancel",
                "TPipe_AsyncHandle_getResult", "TPipe_AsyncHandle_isDone",
                "TPipe_AsyncHandle_poll", "TPipe_AsyncHandle_wait")),

        // P2P
        FeatureGroup("P2P",
            listOf("TPipe_P2PHandle_create", "TPipe_P2PHandle_connect",
                "TPipe_P2PHandle_registerAgent", "TPipe_P2PHandle_send")),

        // PCP
        FeatureGroup("PCP",
            listOf("TPipe_PCPHandle_create", "TPipe_PCPHandle_execute"))
    )

    private fun discoverEntryPointNames(): Set<String> {
        return TPipeBootstrap::class.java.declaredMethods
            .filter { it.isAnnotationPresent(CEntryPoint::class.java) }
            .mapNotNull { it.getAnnotation(CEntryPoint::class.java).name.takeIf { n -> n.isNotEmpty() } }
            .toSet()
    }

    @Test
    fun testPrintParityScorecard() {
        val available = discoverEntryPointNames()
        val totalRequired = featureGroups.sumOf { it.requiredSymbols.size }
        val totalPresent = featureGroups.sumOf { group ->
            group.requiredSymbols.count { it in available }
        }
        val totalMissing = totalRequired - totalPresent
        val percent = if (totalRequired > 0) {
            (totalPresent * 100.0 / totalRequired)
        } else 0.0

        val scorecard = buildString {
            appendLine("=== TPipe ABI Feature Parity Scorecard (Phase 2) ===")
            appendLine()
            appendLine("Overall: $totalPresent / $totalRequired symbols present " +
                "(${"%.1f".format(percent)}%)")
            appendLine("Missing: $totalMissing symbols across ${featureGroups.count { it.requiredSymbols.any { s -> s !in available } }} " +
                "feature groups")
            appendLine()
            appendLine("Per-group score:")
            for (group in featureGroups) {
                val present = group.requiredSymbols.count { it in available }
                val missing = group.requiredSymbols.filter { it !in available }
                val pct = if (group.requiredSymbols.isNotEmpty())
                    present * 100.0 / group.requiredSymbols.size else 0.0
                val status = when {
                    missing.isEmpty() -> "COMPLETE"
                    present == 0 -> "MISSING"
                    else -> "PARTIAL"
                }
                appendLine("  [$status] ${group.name}: $present/${group.requiredSymbols.size} " +
                    "(${"%.0f".format(pct)}%)")
                for (m in missing) {
                    appendLine("    - missing: $m")
                }
            }
        }
        println(scorecard)
        // The scorecard itself is informational; we just assert we computed
        // a non-empty result for the audit log.
        assertNotNull(scorecard)
    }

    @Test
    fun testNoFeatureGroupIsEmpty() {
        val empty = featureGroups.filter { it.requiredSymbols.isEmpty() }
        assertEquals(emptyList(), empty,
            "Every feature group must declare at least one required symbol; " +
            "empty groups: ${empty.map { it.name }}")
    }

    @Test
    fun testNoDuplicateSymbolsAcrossGroups() {
        val allSymbols = featureGroups.flatMap { it.requiredSymbols }
        val duplicates = allSymbols.groupBy { it }
            .filter { it.value.size > 1 }
            .keys
        assertEquals(emptySet(), duplicates,
            "A symbol may not appear in more than one feature group; duplicates: $duplicates")
    }

    @Test
    fun testAllRequiredSymbolsAreImplemented() {
        val available = discoverEntryPointNames()
        val missing = featureGroups.flatMap { it.requiredSymbols }
            .filter { it !in available }
            .distinct()
        // Allow a small set of "in-flight" symbols to be missing — this test
        // documents the current gap. Tighten this assertion as parity
        // improves. The scorecard test (above) prints the full breakdown.
        val allowedMissing = emptySet<String>()
        val actualMissing = missing.filter { it !in allowedMissing }.toSet()
        assertEquals(emptySet(), actualMissing,
            "Feature parity regression: these required symbols are missing " +
            "from TPipeBootstrap.java: $actualMissing. " +
            "Update the 'allowedMissing' set if these are documented as " +
            "out-of-scope for the current cycle.")
    }
}
