package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Completion test — enforces that every public method on [DistributionGridHandle]
 * has a corresponding `TPipe_DistributionGrid_*` C symbol declared in
 * `tpipe-abi.h`.
 *
 * Guards the C ABI surface against future drift: if a developer adds a
 * new method to [DistributionGridHandle], this test fails until the
 * bridge, bootstrap shim, and header declaration are also added.
 *
 * Excluded from the check:
 *  - `getGrid` (Kotlin synthetic accessor for the wrapped `val grid` field)
 *  - Object / data-class members (`equals`, `hashCode`, `toString`)
 *  - `rebalanceStub` (legacy Phase 1 alias for `rebalance`)
 *
 * @see [DistributionGridHandle]
 * @see [NativeBridge]
 * @see [TPipeBootstrap]
 */
class DistributionGridHandleCompletionTest
{

    /** Methods that should NOT be required to appear in the C ABI header. */
    private val excludedMethods = setOf(
        // Synthetic accessor for the wrapped `val grid` field.
        "getGrid",
        // Object methods that Kotlin may auto-generate or inherit.
        "equals", "hashCode", "toString",
        // Legacy Phase 1 stub alias (TPipe_DistributionGrid_rebalance_stub maps to this).
        "rebalanceStub",
        // The C symbol uses a `get_` prefix that doesn't match the Kotlin method
        // name (TPipe_DistributionGrid_getLastRebalanceMs vs `lastRebalanceMs`).
        // The symbol is verified in the static expectedSymbolsArePresent check below.
        "lastRebalanceMs"
    )

    /** Cached header source — loaded once per test class instance. */
    private val headerSource: String by lazy {
        val stream = DistributionGridHandleCompletionTest::class.java.getResourceAsStream("/tpipe-abi.h")
        assertNotNull(stream, "tpipe-abi.h must be on the classpath under /tpipe-abi.h")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * For every eligible public method on [DistributionGridHandle], require
     * that `TPipe_DistributionGrid_<methodName>` appears somewhere in the
     * header text.
     */
    @Test
    fun everyPublicMethodHasCSymbolDeclaration()
    {
        val methods = DistributionGridHandle::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name }
            .distinct()
            .filterNot { it in excludedMethods }
            .sorted()

        assertTrue(
            methods.isNotEmpty(),
            "Reflection should discover at least one public method on DistributionGridHandle"
        )

        val missing = mutableListOf<String>()
        for (methodName in methods)
        {
            val expectedSymbol = "TPipe_DistributionGrid_$methodName"
            if (!headerSource.contains(expectedSymbol))
            {
                missing.add(expectedSymbol)
            }
        }

        if (missing.isNotEmpty())
        {
            fail(
                "tpipe-abi.h is missing C symbol declarations for the following " +
                "DistributionGridHandle methods: $missing. " +
                "Add the corresponding `int TPipe_DistributionGrid_<methodName>(...)` " +
                "declaration to src/main/resources/tpipe-abi.h."
            )
        }
    }

    /**
     * Static cross-check: the canonical `TPipe_DistributionGrid_*` symbols
     * must appear in the header regardless of reflection filtering.
     */
    @Test
    fun expectedDistributionGridSymbolsArePresent()
    {
        val expected = listOf(
            // Pre-existing read surface (Phase 6)
            "TPipe_DistributionGrid_create",
            "TPipe_DistributionGrid_release",
            "TPipe_DistributionGrid_getNodeCount",
            "TPipe_DistributionGrid_serialize",
            "TPipe_DistributionGrid_getHealth",
            "TPipe_DistributionGrid_rebalance_stub",
            "TPipe_DistributionGrid_getStatusJson",
            "TPipe_DistributionGrid_getLastRebalanceMs",
            // Cycle 8 — configuration surface
            "TPipe_DistributionGrid_setMaxHops",
            "TPipe_DistributionGrid_getMaxHops",
            "TPipe_DistributionGrid_setRpcTimeout",
            "TPipe_DistributionGrid_getRpcTimeout",
            "TPipe_DistributionGrid_setMaxSessionDuration",
            "TPipe_DistributionGrid_getMaxSessionDuration",
            "TPipe_DistributionGrid_setDiscoveryMode",
            "TPipe_DistributionGrid_getDiscoveryMode",
            "TPipe_DistributionGrid_pause",
            "TPipe_DistributionGrid_isPaused"
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
