package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Completion test — enforces that every public method on [MapHandle] has a
 * corresponding `TPipe_Map_*` C symbol declared in `tpipe-abi.h`.
 *
 * This guards the C ABI surface against future drift: if a developer adds a
 * new method to [MapHandle], this test fails until the bridge, bootstrap
 * shim, and header declaration are also added.
 *
 * Excluded from the check:
 *  - `setString` — the C ABI exposes a flat `TPipe_Map_set` that takes a
 *    handle ID; the Kotlin convenience `setString` is not part of the C ABI
 *    surface.
 *  - `isEmpty` — not exposed in the C ABI header.
 *  - `build` — internal builder-pattern method that commits to
 *    [HandleRegistry]; the C ABI treats the map as a single handle returned
 *    by `TPipe_Map_create`.
 *  - Object / data-class members (`equals`, `hashCode`, `toString`).
 *
 * @see [MapHandle]
 * @see [NativeBridge]
 * @see [TPipeBootstrap]
 */
class MapHandleCompletionTest
{

    /** Methods that should NOT be required to appear in the C ABI header. */
    private val excludedMethods = setOf(
        "setString",
        "isEmpty",
        "build",
        "equals", "hashCode", "toString"
    )


    /** Cached header source — loaded once per test class instance. */
    private val headerSource: String by lazy {
        val stream = MapHandleCompletionTest::class.java.getResourceAsStream("/tpipe-abi.h")
        assertNotNull(stream, "tpipe-abi.h must be on the classpath under /tpipe-abi.h")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }


    /**
     * For every eligible public method on [MapHandle], require that
     * `TPipe_Map_<methodName>` appears somewhere in the header text.
     */
    @Test
    fun everyPublicMethodHasCSymbolDeclaration()
    {
        val methods = MapHandle::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name }
            .distinct()
            .filterNot { it in excludedMethods }
            .sorted()

        assertTrue(
            methods.isNotEmpty(),
            "Reflection should discover at least one public method on MapHandle"
        )

        val missing = mutableListOf<String>()
        for (methodName in methods)
        {
            val expectedSymbol = "TPipe_Map_$methodName"
            if (!headerSource.contains(expectedSymbol))
            {
                missing.add(expectedSymbol)
            }
        }

        if (missing.isNotEmpty())
        {
            fail(
                "tpipe-abi.h is missing C symbol declarations for the following " +
                "MapHandle methods: $missing. " +
                "Add the corresponding `int TPipe_Map_<methodName>(...)` " +
                "declaration to src/main/resources/tpipe-abi.h."
            )
        }
    }


    /**
     * Static cross-check: the canonical `TPipe_Map_*` symbols must appear in
     * the header regardless of reflection filtering.
     */
    @Test
    fun expectedMapSymbolsArePresent()
    {
        val expected = listOf(
            "TPipe_Map_create",
            "TPipe_Map_set",
            "TPipe_Map_get",
            "TPipe_Map_has",
            "TPipe_Map_size"
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
