package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Completion test — enforces that every public method on [ConnectorHandle]
 * has a corresponding `TPipe_Connector_*` C symbol declared in
 * `tpipe-abi.h`.
 *
 * This guards the C ABI surface against future drift: if a developer adds
 * a new method to [ConnectorHandle], this test fails until the bridge,
 * bootstrap shim, and header declaration are also added.
 *
 * Excluded from the check:
 *  - `getConnector` (Kotlin synthetic accessor for the wrapped
 *    `val connector` field) — exposes the underlying
 *    [com.TTT.Pipeline.Connector], not part of the C ABI surface
 *  - Object / data-class members (`equals`, `hashCode`, `toString`)
 *
 * @see [ConnectorHandle]
 * @see [NativeBridge]
 * @see [TPipeBootstrap]
 */
class ConnectorHandleCompletionTest
{

    /** Methods that should NOT be required to appear in the C ABI header. */
    private val excludedMethods = setOf(
        // Synthetic accessor for the wrapped `val connector` field —
        // internal-only, exposes the underlying Connector object.
        "getConnector",
        // Object methods that Kotlin may auto-generate or inherit.
        "equals", "hashCode", "toString"
    )


    /** Cached header source — loaded once per test class instance. */
    private val headerSource: String by lazy {
        val stream = ConnectorHandleCompletionTest::class.java.getResourceAsStream("/tpipe-abi.h")
        assertNotNull(stream, "tpipe-abi.h must be on the classpath under /tpipe-abi.h")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }


    /**
     * For every eligible public method on [ConnectorHandle], require that
     * `TPipe_Connector_<methodName>` appears somewhere in the header text.
     */
    @Test
    fun everyPublicMethodHasCSymbolDeclaration()
    {
        val methods = ConnectorHandle::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name }
            .distinct()
            .filterNot { it in excludedMethods }
            .sorted()

        assertTrue(
            methods.isNotEmpty(),
            "Reflection should discover at least one public method on ConnectorHandle"
        )

        val missing = mutableListOf<String>()
        for (methodName in methods)
        {
            val expectedSymbol = "TPipe_Connector_$methodName"
            if (!headerSource.contains(expectedSymbol))
            {
                missing.add(expectedSymbol)
            }
        }

        if (missing.isNotEmpty())
        {
            fail(
                "tpipe-abi.h is missing C symbol declarations for the following " +
                "ConnectorHandle methods: $missing. " +
                "Add the corresponding `int TPipe_Connector_<methodName>(...)` " +
                "declaration to src/main/resources/tpipe-abi.h."
            )
        }
    }


    /**
     * Static cross-check: the canonical `TPipe_Connector_*` symbols must
     * appear in the header regardless of reflection filtering.
     */
    @Test
    fun expectedConnectorSymbolsArePresent()
    {
        val expected = listOf(
            "TPipe_Connector_create",
            "TPipe_Connector_release",
            "TPipe_Connector_init",
            "TPipe_Connector_execute",
            "TPipe_Connector_serialize"
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
