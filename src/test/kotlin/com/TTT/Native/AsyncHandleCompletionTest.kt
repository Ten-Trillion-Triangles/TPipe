package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Completion test — enforces that the public methods of [OperationHandle]
 * (the Kotlin class that backs the C ABI's `TPipe_AsyncHandle`) have
 * corresponding `TPipe_AsyncHandle_*` C symbols declared in `tpipe-abi.h`.
 *
 * The C ABI names this handle `TPipe_AsyncHandle`; the Kotlin
 * implementation lives in [OperationHandle]. The bridge, bootstrap shim,
 * and header prototype must all be added together when a new method
 * appears in [OperationHandle] that is intended to be reachable from C.
 *
 * Excluded from the check:
 *  - `getError` — no `TPipe_AsyncHandle_getError` symbol exists; the C ABI
 *    surfaces errors via `TPipe_getLastError` and `TPipe_Result_free` only.
 *  - `isSuccessful` — the C ABI's `TPipe_AsyncHandle_poll` returns the full
 *    `TPIPE_OPERATION_*` status; consumers branch on the status value
 *    rather than calling a boolean predicate.
 *  - `isFailed` — same reasoning as `isSuccessful`.
 *  - Object / data-class members (`equals`, `hashCode`, `toString`).
 *
 * @see [OperationHandle]
 * @see [NativeBridge]
 * @see [TPipeBootstrap]
 */
class AsyncHandleCompletionTest
{

    /** Methods that should NOT be required to appear in the C ABI header. */
    private val excludedMethods = setOf(
        "getError",
        "isSuccessful",
        "isFailed",
        "getErrorMessage", "setErrorMessage",
        "getResultHandle", "setResultHandle",
        "getStatus", "setStatus",
        "equals", "hashCode", "toString"
    )


    /** Cached header source — loaded once per test class instance. */
    private val headerSource: String by lazy {
        val stream = AsyncHandleCompletionTest::class.java.getResourceAsStream("/tpipe-abi.h")
        assertNotNull(stream, "tpipe-abi.h must be on the classpath under /tpipe-abi.h")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }


    /**
     * For every eligible public method on [OperationHandle], require that
     * `TPipe_AsyncHandle_<methodName>` appears somewhere in the header
     * text.
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

        assertTrue(
            methods.isNotEmpty(),
            "Reflection should discover at least one public method on OperationHandle"
        )

        val missing = mutableListOf<String>()
        for (methodName in methods)
        {
            val expectedSymbol = "TPipe_AsyncHandle_$methodName"
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
                "Add the corresponding `int TPipe_AsyncHandle_<methodName>(...)` " +
                "declaration to src/main/resources/tpipe-abi.h."
            )
        }
    }


    /**
     * Static cross-check: the canonical `TPipe_AsyncHandle_*` symbols must
     * appear in the header regardless of reflection filtering. The `poll`
     * and `getResult` symbols are pre-existing in the header; `isDone` and
     * `wait` are shim-extras that gained header prototypes in this phase.
     */
    @Test
    fun expectedAsyncHandleSymbolsArePresent()
    {
        val expected = listOf(
            "TPipe_AsyncHandle_create",
            "TPipe_AsyncHandle_poll",
            "TPipe_AsyncHandle_getResult",
            "TPipe_AsyncHandle_cancel",
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
