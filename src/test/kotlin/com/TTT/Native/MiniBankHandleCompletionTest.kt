package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 9 completion test — enforces that every public method on
 * [MiniBankHandle] has a corresponding `TPipe_MiniBank_*` C symbol declared
 * in `tpipe-abi.h`.
 *
 * This guards the C ABI surface against future drift: if a developer adds a
 * new method to [MiniBankHandle], this test fails until the bridge,
 * bootstrap shim, and header declaration are also added.
 *
 * Notes:
 *  - `getOrCreatePage` is excluded from the reflection check because the C
 *    ABI exposes a JSON-snapshot variant (`TPipe_MiniBank_getPageJson`) rather
 *    than a direct handle return.
 *  - Property accessors for the wrapped `var miniBank` are internal-only.
 *  - Object / data-class members (`equals`, `hashCode`, `toString`) are
 *    excluded.
 *
 * @see [MiniBankHandle]
 * @see [NativeBridge]
 * @see [TPipeBootstrap]
 */
class MiniBankHandleCompletionTest {

    /** Methods that should NOT be required to appear in the C ABI header. */
    private val excludedMethods = setOf(
        // The C ABI exposes a JSON snapshot (TPipe_MiniBank_getPageJson) rather
        // than returning a full ContextWindow handle from C, so the underlying
        // method is excluded from the per-method symbol check.
        "getOrCreatePage",
        // Property accessors for the wrapped `val miniBank` — internal-only.
        "getMiniBank",
        // Object methods that Kotlin may auto-generate or inherit.
        "equals", "hashCode", "toString"
    )

    /** Cached header source — loaded once per test class instance. */
    private val headerSource: String by lazy {
        val stream = MiniBankHandleCompletionTest::class.java.getResourceAsStream("/tpipe-abi.h")
        assertNotNull(stream, "tpipe-abi.h must be on the classpath under /tpipe-abi.h")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * For every eligible public method on [MiniBankHandle], require that
     * `TPipe_MiniBank_<methodName>` appears somewhere in the header text.
     */
    @Test
    fun everyPublicMethodHasCSymbolDeclaration() {
        val methods = MiniBankHandle::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name }
            .distinct()
            .filterNot { it in excludedMethods }
            .sorted()

        assertTrue(
            methods.isNotEmpty(),
            "Reflection should discover at least one public method on MiniBankHandle"
        )

        val missing = mutableListOf<String>()
        for (methodName in methods) {
            val expectedSymbol = "TPipe_MiniBank_$methodName"
            if (!headerSource.contains(expectedSymbol)) {
                missing.add(expectedSymbol)
            }
        }

        if (missing.isNotEmpty()) {
            fail(
                "tpipe-abi.h is missing C symbol declarations for the following " +
                "MiniBankHandle methods: $missing. " +
                "Add the corresponding `int $expectedSymbolExample(...)` " +
                "declaration to src/main/resources/tpipe-abi.h."
            )
        }
    }

    /**
     * Static cross-check: these Phase 9 symbols must appear in the header
     * regardless of reflection filtering.
     */
    @Test
    fun expectedPhaseNineSymbolsArePresent() {
        val expected = listOf(
            "TPipe_MiniBank_isEmpty",
            "TPipe_MiniBank_clear",
            "TPipe_MiniBank_pageCount",
            "TPipe_MiniBank_getPageKeys",
            "TPipe_MiniBank_getPageJson",
            "TPipe_MiniBank_merge"
        )
        for (symbol in expected) {
            assertTrue(
                headerSource.contains(symbol),
                "tpipe-abi.h should declare $symbol but it was not found"
            )
        }
    }

    private val expectedSymbolExample: String = "TPipe_MiniBank_<methodName>"
}
