package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Completion test — enforces that every public method on [PipeHandle] has a
 * corresponding `TPipe_Pipe_*` C symbol declared in `tpipe-abi.h`.
 *
 * Guards the C ABI surface against future drift: if a developer adds a new
 * method to [PipeHandle], this test fails until the bridge, bootstrap shim,
 * and header declaration are also added.
 *
 * Excluded from the check:
 *  - `getPipe` / `getSettings` (Kotlin synthetic accessors for the wrapped
 *    `val pipe` / `val settings` fields) — internal-only.
 *  - Object / data-class members (`equals`, `hashCode`, `toString`).
 *  - Nested `Result` type members (`getMessage`, `getHandleId`, etc.) — those
 *    are surfaced via the `TPipe_Operation_*` symbols, not the Pipe C ABI.
 *
 * @see [PipeHandle]
 * @see [NativeBridge]
 * @see [TPipeBootstrap]
 */
class PipeHandleCompletionTest
{

    /** Methods that should NOT be required to appear in the C ABI header. */
    private val excludedMethods = setOf(
        // Synthetic accessors for the wrapped `val pipe` / `val settings` fields.
        "getPipe", "getSettings",
        // Object methods that Kotlin may auto-generate or inherit.
        "equals", "hashCode", "toString",
        // Pre-existing PipeHandle methods (predate C ABI expansion effort):
        // their functionality is reachable via the pipe's provider/model/
        // region string passed to TPipe_Pipe_create and via the
        // TPipe_Pipe_getTokenUsage companion symbol, not via dedicated
        // TPipe_Pipe_getModel/getProvider/getRegion C symbols. The async
        // execute variant similarly folds into TPipe_Pipe_executeContentAsync.
        "getModel", "getRegion", "getProvider", "executeAsync"
    )

    /** Cached header source — loaded once per test class instance. */
    private val headerSource: String by lazy {
        val stream = PipeHandleCompletionTest::class.java.getResourceAsStream("/tpipe-abi.h")
        assertNotNull(stream, "tpipe-abi.h must be on the classpath under /tpipe-abi.h")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * For every eligible public method on [PipeHandle], require that
     * `TPipe_Pipe_<methodName>` appears somewhere in the header text.
     */
    @Test
    fun everyPublicMethodHasCSymbolDeclaration()
    {
        val methods = PipeHandle::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name }
            .distinct()
            .filterNot { it in excludedMethods }
            .sorted()

        assertTrue(
            methods.isNotEmpty(),
            "Reflection should discover at least one public method on PipeHandle"
        )

        val missing = mutableListOf<String>()
        for (methodName in methods)
        {
            val expectedSymbol = "TPipe_Pipe_$methodName"
            if (!headerSource.contains(expectedSymbol))
            {
                missing.add(expectedSymbol)
            }
        }

        if (missing.isNotEmpty())
        {
            fail(
                "tpipe-abi.h is missing C symbol declarations for the following " +
                "PipeHandle methods: $missing. " +
                "Add the corresponding `int TPipe_Pipe_<methodName>(...)` " +
                "declaration to src/main/resources/tpipe-abi.h."
            )
        }
    }

    /**
     * Static cross-check: the canonical `TPipe_Pipe_*` symbols must
     * appear in the header regardless of reflection filtering.
     */
    @Test
    fun expectedPipeSymbolsArePresent()
    {
        val expected = listOf(
            // Lifecycle
            "TPipe_Pipe_create", "TPipe_Pipe_init", "TPipe_Pipe_execute",
            // Original setters
            "TPipe_Pipe_setProvider", "TPipe_Pipe_setTemperature",
            "TPipe_Pipe_setRepetitionPenalty", "TPipe_Pipe_setReasoning",
            "TPipe_Pipe_getTokenUsage",
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
            "TPipe_Pipe_cacheInput", "TPipe_Pipe_forceCacheInput"
        )
        for (symbol in expected)
        {
            assertTrue(
                headerSource.contains(symbol),
                "tpipe-abi.h should declare $symbol but it was not found"
            )
        }
    }
}
