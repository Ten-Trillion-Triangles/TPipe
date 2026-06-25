package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaScriptExecutorCaptureTest
{
    @Test
    fun largeStdoutCapturedWithoutDeadlock() = runBlocking {
        val executor = JavaScriptExecutor()
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf(
                "process.stdout.write('x'.repeat(1024 * 1024))"
            )
        )
        val context = PcpContext()

        val result = executor.execute(request, context)

        assertEquals(true, result.success)
        assertNotNull(result.outputBuffer)
        assertEquals(1024L * 1024L, result.outputBuffer!!.totalBytes)
        Unit
    }

    @Test
    fun stdoutAndStderrSeparated() = runBlocking {
        val executor = JavaScriptExecutor()
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf(
                "console.log('to stdout')",
                "console.error('to stderr')"
            )
        )
        val context = PcpContext()

        val result = executor.execute(request, context)

        val buf = result.outputBuffer!!
        assertEquals("to stdout\n", buf.stdout)
        assertEquals("to stderr\n", buf.stderr)
        Unit
    }
}