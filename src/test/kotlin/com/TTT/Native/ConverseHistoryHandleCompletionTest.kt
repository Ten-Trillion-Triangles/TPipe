package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 8 completion test — enforces that every public method on
 * [ConverseHistoryHandle] has a corresponding `TPipe_ConverseHistory_*` C
 * symbol declared in `tpipe-abi.h`.
 *
 * This guards the C ABI surface against future drift: if a developer adds a
 * new method to [ConverseHistoryHandle], this test fails until the bridge,
 * bootstrap shim, and header declaration are also added.
 *
 * Excluded from the check (per Phase 8 scope):
 *  - `getConverseHistory` (Kotlin synthetic accessor for the wrapped
 *    `val converseHistory` field) — exposes the underlying
 *    [com.TTT.Context.ConverseHistory], not part of the C ABI surface
 *  - Object / data-class members (`equals`, `hashCode`, `toString`)
 *  - Private helper extensions (e.g. `escapeJson`)
 *
 * @see [ConverseHistoryHandle]
 * @see [NativeBridge]
 * @see [TPipeBootstrap]
 */
class ConverseHistoryHandleCompletionTest {

    /** Methods that should NOT be required to appear in the C ABI header. */
    private val excludedMethods = setOf(
        // Synthetic accessor for the wrapped `val converseHistory` field —
        // internal-only, exposes the underlying ConverseHistory object.
        "getConverseHistory",
        // Object methods that Kotlin may auto-generate or inherit.
        "equals", "hashCode", "toString",
        // Private helper extension declared inside ConverseHistoryHandle for
        // JSON escaping. Kotlin compiles it to a private static-style method;
        // exclude defensively in case it shows up under any class loader.
        "escapeJson"
    )

    /** Cached header source — loaded once per test class instance. */
    private val headerSource: String by lazy {
        val stream = ConverseHistoryHandleCompletionTest::class.java.getResourceAsStream("/tpipe-abi.h")
        assertNotNull(stream, "tpipe-abi.h must be on the classpath under /tpipe-abi.h")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * For every eligible public method on [ConverseHistoryHandle], require
     * that `TPipe_ConverseHistory_<methodName>` appears somewhere in the
     * header text.
     */
    @Test
    fun everyPublicMethodHasCSymbolDeclaration() {
        val methods = ConverseHistoryHandle::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name }
            .distinct()
            .filterNot { it in excludedMethods }
            .sorted()

        assertTrue(
            methods.isNotEmpty(),
            "Reflection should discover at least one public method on ConverseHistoryHandle"
        )

        val missing = mutableListOf<String>()
        for (methodName in methods) {
            val expectedSymbol = "TPipe_ConverseHistory_$methodName"
            if (!headerSource.contains(expectedSymbol)) {
                missing.add(expectedSymbol)
            }
        }

        if (missing.isNotEmpty()) {
            fail(
                "tpipe-abi.h is missing C symbol declarations for the following " +
                "ConverseHistoryHandle methods: $missing. " +
                "Add the corresponding `int TPipe_ConverseHistory_<methodName>(...)` " +
                "declaration to src/main/resources/tpipe-abi.h."
            )
        }
    }

    /**
     * Static cross-check: these Phase 8 symbols must appear in the header
     * regardless of reflection filtering.
     */
    @Test
    fun expectedPhaseEightSymbolsArePresent() {
        val expected = listOf(
            "TPipe_ConverseHistory_addString",
            "TPipe_ConverseHistory_size",
            "TPipe_ConverseHistory_isEmpty",
            "TPipe_ConverseHistory_clear",
            "TPipe_ConverseHistory_getAt",
            "TPipe_ConverseHistory_toJson"
        )
        for (symbol in expected) {
            assertTrue(
                headerSource.contains(symbol),
                "tpipe-abi.h should declare $symbol but it was not found"
            )
        }
    }
}
