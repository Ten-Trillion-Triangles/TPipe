package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Completion test — enforces that every public method on [OperationHandle]
 * has a corresponding `TPipe_Operation_*` C symbol declared in
 * `tpipe-abi.h`.
 *
 * This guards the C ABI surface against future drift: if a developer adds a
 * new method to [OperationHandle] under the `TPipe_Operation_*` prefix, this
 * test fails until the bridge, bootstrap shim, and header declaration are
 * also added.
 *
 * The C ABI does not currently export any `TPipe_Operation_*` functions.
 * The Kotlin [OperationHandle] is exposed through the `TPipe_AsyncHandle_*`
 * family of symbols. As a result, every public method on [OperationHandle]
 * is excluded from the strict prefix-based check below; the test is
 * currently a vacuous guard. The static cross-check enforces that the
 * canonical name for the operation handle in the C ABI is
 * `TPipe_OperationHandle` (typedef) and that the runtime methods are
 * exposed under the `TPipe_AsyncHandle_*` family.
 *
 * @see [OperationHandle]
 * @see [NativeBridge]
 * @see [TPipeBootstrap]
 */
class OperationHandleCompletionTest
{

    /**
     * Methods that should NOT be required to appear in the C ABI header
     * under the `TPipe_Operation_*` prefix. The C ABI exposes these via
     * `TPipe_AsyncHandle_*` symbols; see [AsyncHandleCompletionTest] for
     * the canonical cross-check.
     */
    private val excludedMethods = setOf(
        "poll",
        "getResult",
        "getError",
        "cancel",
        "isDone",
        "isSuccessful",
        "isFailed",
        "getErrorMessage", "setErrorMessage",
        "getResultHandle", "setResultHandle",
        "getStatus", "setStatus",
        "equals", "hashCode", "toString"
    )


    /** Cached header source — loaded once per test class instance. */
    private val headerSource: String by lazy {
        val stream = OperationHandleCompletionTest::class.java.getResourceAsStream("/tpipe-abi.h")
        assertNotNull(stream, "tpipe-abi.h must be on the classpath under /tpipe-abi.h")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }


    /**
     * For every eligible public method on [OperationHandle], require that
     * `TPipe_Operation_<methodName>` appears somewhere in the header text.
     *
     * With the current exclusion set, this test passes vacuously. It is
     * retained as a guard: if the C ABI ever gains `TPipe_Operation_*`
     * functions, the exclusion set must be revisited.
     */
    @Test
    fun everyPublicMethodHasCSymbolDeclaration()
    {
        val methods = OperationHandle::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name }
            .distinct()
            .filterNot { it in excludedMethods }
            .sorted()

        if (methods.isEmpty())
        {
            return
        }

        val missing = mutableListOf<String>()
        for (methodName in methods)
        {
            val expectedSymbol = "TPipe_Operation_$methodName"
            if (!headerSource.contains(expectedSymbol))
            {
                missing.add(expectedSymbol)
            }
        }

        if (missing.isNotEmpty())
        {
            fail(
                "tpipe-abi.h is missing C symbol declarations for the following " +
                "OperationHandle methods: $missing. " +
                "Add the corresponding `int TPipe_Operation_<methodName>(...)` " +
                "declaration to src/main/resources/tpipe-abi.h."
            )
        }
    }


    /**
     * Static cross-check: the C ABI typedef for the operation handle is
     * `TPipe_OperationHandle`, and the runtime methods are exposed under
     * the `TPipe_AsyncHandle_*` family. The audit added
     * `TPipe_AsyncHandle_poll` and `TPipe_AsyncHandle_getResult`; the
     * shim-extras `TPipe_AsyncHandle_isDone` and `TPipe_AsyncHandle_wait`
     * gained header prototypes in this phase.
     */
    @Test
    fun expectedOperationHandleSymbolsArePresent()
    {
        val expected = listOf(
            "TPipe_OperationHandle",
            "TPipe_AsyncHandle_poll",
            "TPipe_AsyncHandle_getResult",
            "TPipe_AsyncHandle_isDone",
            "TPipe_AsyncHandle_wait"
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
