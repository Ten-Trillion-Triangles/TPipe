package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinExecutorTimeoutTest
{
    @Test
    fun whileTrueLoopTerminatesAtTimeoutMs() = runBlocking {
        val executor = KotlinExecutor()
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf("while (true) { }")
        )
        val context = PcpContext().apply {
            kotlinOptions.timeoutMs = 500
        }

        val start = System.currentTimeMillis()
        val result = executor.execute(request, context)
        val elapsed = System.currentTimeMillis() - start

        assertEquals(false, result.success)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("timed out", ignoreCase = true),
            "expected timeout error, got: ${result.error}")
        // Termination must be bounded by timeout + small overhead.
        // The engine thread is daemon, so an infinite loop in the script
        // doesn't block JVM exit — it just spins until the test process
        // terminates.
        assertTrue(elapsed < 5_000L, "executor took ${elapsed}ms — timeout not enforced")
        Unit
    }

    @Test
    fun normalScriptReturnsResult() = runBlocking {
        // println isn't available in the default scripting host — it
        // lives in kotlin.io which the host doesn't expose. We return
        // the result as the script's last expression instead, which is
        // what kotlin-scripting captures as the result.
        val executor = KotlinExecutor()
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf(
                "val x = 2 + 2",
                "x"
            )
        )
        val context = PcpContext()

        val result = executor.execute(request, context)

        assertEquals(true, result.success)
        assertNotNull(result.outputBuffer)
        // The script returns x as its last expression — kotlin-scripting
        // exposes this as the result value, surfaced in the merged output
        // string. stdout is empty because the script didn't call any
        // print function (the default scripting host doesn't expose
        // println — kotlin.io is not exported to the host classpath).
        assertEquals("Result: 4", result.output)
        Unit
    }
}