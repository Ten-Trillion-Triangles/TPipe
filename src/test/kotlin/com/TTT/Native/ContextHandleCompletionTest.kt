package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 10 completion test — enforces that every public method on
 * [ContextHandle] has a corresponding `TPipe_Context_*` C symbol declared in
 * `tpipe-abi.h`.
 *
 * This guards the C ABI surface against future drift: if a developer adds a
 * new method to [ContextHandle], this test fails until the bridge, bootstrap
 * shim, and header declaration are also added.
 *
 * Excluded from the check (per Phase 10 scope):
 *  - `getContextWindow` (Kotlin synthetic accessor for the wrapped
 *    `val contextWindow` field) — exposes the underlying
 *    [com.TTT.Context.ContextWindow], not part of the C ABI surface
 *  - Object / data-class members (`equals`, `hashCode`, `toString`)
 *
 * Note: the pre-existing `TPipe_ContextWindow_create` symbol is unchanged
 * and is not enforced by this test (its name uses the `ContextWindow` prefix,
 * not `Context`, because it predates Phase 10 naming conventions).
 *
 * @see [ContextHandle]
 * @see [NativeBridge]
 * @see [TPipeBootstrap]
 */
class ContextHandleCompletionTest {

    /** Methods that should NOT be required to appear in the C ABI header. */
    private val excludedMethods = setOf(
        // Synthetic accessor for the wrapped `val contextWindow` field —
        // internal-only, exposes the underlying ContextWindow object.
        "getContextWindow",
        // Object methods that Kotlin may auto-generate or inherit.
        "equals", "hashCode", "toString"
    )

    /** Cached header source — loaded once per test class instance. */
    private val headerSource: String by lazy {
        val stream = ContextHandleCompletionTest::class.java.getResourceAsStream("/tpipe-abi.h")
        assertNotNull(stream, "tpipe-abi.h must be on the classpath under /tpipe-abi.h")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * For every eligible public method on [ContextHandle], require that
     * `TPipe_Context_<methodName>` appears somewhere in the header text.
     */
    @Test
    fun everyPublicMethodHasCSymbolDeclaration() {
        val methods = ContextHandle::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name }
            .distinct()
            .filterNot { it in excludedMethods }
            .sorted()

        assertTrue(
            methods.isNotEmpty(),
            "Reflection should discover at least one public method on ContextHandle"
        )

        val missing = mutableListOf<String>()
        for (methodName in methods) {
            val expectedSymbol = "TPipe_Context_$methodName"
            if (!headerSource.contains(expectedSymbol)) {
                missing.add(expectedSymbol)
            }
        }

        if (missing.isNotEmpty()) {
            fail(
                "tpipe-abi.h is missing C symbol declarations for the following " +
                "ContextHandle methods: $missing. " +
                "Add the corresponding `int TPipe_Context_<methodName>(...)` " +
                "declaration to src/main/resources/tpipe-abi.h."
            )
        }
    }

    /**
     * Static cross-check: these Phase 10 symbols must appear in the header
     * regardless of reflection filtering.
     */
    @Test
    fun expectedPhaseTenSymbolsArePresent() {
        val expected = listOf(
            "TPipe_Context_getLoreBookKeys",
            "TPipe_Context_getContextElementsCount",
            "TPipe_Context_getConverseHistorySize",
            "TPipe_Context_getVersion",
            "TPipe_Context_getContextJson"
        )
        for (symbol in expected) {
            assertTrue(
                headerSource.contains(symbol),
                "tpipe-abi.h should declare $symbol but it was not found"
            )
        }
    }
}
