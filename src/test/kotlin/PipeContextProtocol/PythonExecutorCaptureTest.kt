package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PythonExecutorCaptureTest
{
    @Test
    fun largeStdoutIsCapturedWithoutDeadlock() = runBlocking {
        val executor = PythonExecutor().apply {
            setSecurityLevel(PythonSecurityLevel.DISABLED)
        }
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf(
                "import sys",
                "sys.stdout.write('x' * (1024 * 1024))"
            )
        )
        val context = PcpContext()

        val result = executor.execute(request, context)

        assertEquals(true, result.success)
        assertNotNull(result.outputBuffer)
        // totalBytes reports the FULL stream contents even when overflowed
        assertEquals(1024L * 1024L, result.outputBuffer!!.totalBytes)
        // Back-compat: output string still populated. The executor's
        // maxInMemoryBytes cap is 256KB, so 1MB of stdout overflows to
        // the temp file referenced by outputBuffer.overflowPath and the
        // in-memory output string holds only the head. Callers that need
        // the full content should read outputBuffer.overflowPath.
        assertTrue(result.output.isNotEmpty())
        assertEquals(256 * 1024, result.output.length)
        assertTrue(result.outputBuffer!!.truncated)
        assertNotNull(result.outputBuffer!!.overflowPath)
        // Explicit Unit return so the function's inferred return type
        // is Unit and JUnit picks it up.
        Unit
    }

    @Test
    fun stdoutAndStderrAreSeparated() = runBlocking {
        val executor = PythonExecutor().apply {
            setSecurityLevel(PythonSecurityLevel.DISABLED)
        }
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf(
                "import sys",
                "print('to stdout')",
                "print('to stderr', file=sys.stderr)"
            )
        )
        val context = PcpContext()

        val result = executor.execute(request, context)

        assertEquals(true, result.success)
        val buf = result.outputBuffer!!
        assertEquals("to stdout\n", buf.stdout)
        assertEquals("to stderr\n", buf.stderr)
    }

    @Test
    fun timeoutKillsSubprocessAndReturnsTimeoutError() = runBlocking {
        val executor = PythonExecutor().apply {
            setSecurityLevel(PythonSecurityLevel.DISABLED)
        }
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf("import time; time.sleep(60)")
        )
        val context = PcpContext().apply {
            pythonOptions.timeoutMs = 500
        }

        val result = executor.execute(request, context)

        assertEquals(false, result.success)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("timed out", ignoreCase = true),
            "expected timeout error, got: ${result.error}")
        assertTrue(result.executionTimeMs < 5_000L,
            "execution took ${result.executionTimeMs}ms — timeout not enforced")
    }
}