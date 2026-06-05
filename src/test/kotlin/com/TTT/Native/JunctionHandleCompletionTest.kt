package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Completion test — enforces that every public method on [JunctionHandle]
 * has a corresponding `TPipe_Junction_*` C symbol declared in
 * `tpipe-abi.h`.
 *
 * This guards the C ABI surface against future drift: if a developer adds
 * a new method to [JunctionHandle], this test fails until the bridge,
 * bootstrap shim, and header declaration are also added.
 *
 * Excluded from the check:
 *  - `getJunction` (Kotlin synthetic accessor for the wrapped
 *    `val junction` field) — exposes the underlying
 *    [com.TTT.Pipeline.Junction], not part of the C ABI surface
 *  - Object / data-class members (`equals`, `hashCode`, `toString`)
 *
 * @see [JunctionHandle]
 * @see [NativeBridge]
 * @see [TPipeBootstrap]
 */
class JunctionHandleCompletionTest
{

    /** Methods that should NOT be required to appear in the C ABI header. */
    private val excludedMethods = setOf(
        // Synthetic accessor for the wrapped `val junction` field —
        // internal-only, exposes the underlying Junction object.
        "getJunction",
        // Object methods that Kotlin may auto-generate or inherit.
        "equals", "hashCode", "toString"
    )


    /** Cached header source — loaded once per test class instance. */
    private val headerSource: String by lazy {
        val stream = JunctionHandleCompletionTest::class.java.getResourceAsStream("/tpipe-abi.h")
        assertNotNull(stream, "tpipe-abi.h must be on the classpath under /tpipe-abi.h")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }


    /**
     * For every eligible public method on [JunctionHandle], require that
     * `TPipe_Junction_<methodName>` appears somewhere in the header text.
     */
    @Test
    fun everyPublicMethodHasCSymbolDeclaration()
    {
        val methods = JunctionHandle::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name }
            .distinct()
            .filterNot { it in excludedMethods }
            .sorted()

        assertTrue(
            methods.isNotEmpty(),
            "Reflection should discover at least one public method on JunctionHandle"
        )

        val missing = mutableListOf<String>()
        for (methodName in methods)
        {
            val expectedSymbol = "TPipe_Junction_$methodName"
            if (!headerSource.contains(expectedSymbol))
            {
                missing.add(expectedSymbol)
            }
        }

        if (missing.isNotEmpty())
        {
            fail(
                "tpipe-abi.h is missing C symbol declarations for the following " +
                "JunctionHandle methods: $missing. " +
                "Add the corresponding `int TPipe_Junction_<methodName>(...)` " +
                "declaration to src/main/resources/tpipe-abi.h."
            )
        }
    }


    /**
     * Static cross-check: the canonical `TPipe_Junction_*` symbols must
     * appear in the header regardless of reflection filtering.
     */
    @Test
    fun expectedJunctionSymbolsArePresent()
    {
        val expected = listOf(
            "TPipe_Junction_create",
            "TPipe_Junction_release",
            "TPipe_Junction_init",
            "TPipe_Junction_execute",
            "TPipe_Junction_serialize"
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
