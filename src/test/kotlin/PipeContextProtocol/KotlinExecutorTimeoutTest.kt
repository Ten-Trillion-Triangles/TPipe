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
        val output = runTimeoutProbe()
        assertTrue(output.contains("timed out", ignoreCase = true),
            "expected timeout error, got: $output")
        assertTrue(timeoutProbeElapsed(output) < 1_500L,
            "executor-reported timeout elapsed time exceeded the bound: $output")
        Unit
    }

    @Test
    fun sleepingScriptReturnsTimeoutAt100ms() = runBlocking {
        val output = runTimeoutProbe("sleep")
        assertTrue(output.contains("timed out", ignoreCase = true),
            "Expected timeout error; got: $output")
        assertTrue(output.contains("100ms"), "Expected the 100ms timeout: $output")
        assertTrue(timeoutProbeElapsed(output) < 1_500L,
            "executor-reported timeout elapsed time exceeded the bound: $output")
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

    private fun javaExecutable(): String
    {
        return java.nio.file.Path.of(
            System.getProperty("java.home"),
            "bin",
            if(System.getProperty("os.name").contains("Windows")) "java.exe" else "java"
        ).toString()
    }

    private fun runTimeoutProbe(vararg arguments: String): String
    {
        val start = System.currentTimeMillis()
        val process = ProcessBuilder(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            KotlinTimeoutProbeMain::class.java.name,
            *arguments
        ).redirectErrorStream(true).start()
        val completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        if(!completed)
        {
            process.destroyForcibly()
        }
        val output = process.inputStream.bufferedReader().readText()
        val elapsed = System.currentTimeMillis() - start
        assertTrue(completed, "timeout probe did not exit: $output")
        assertTrue(elapsed < 5_000L, "executor took ${elapsed}ms — timeout not enforced")
        return output
    }

    private fun timeoutProbeElapsed(output: String): Long
    {
        return output.substringAfter("|elapsedMs=", missingDelimiterValue = "")
            .trim()
            .toLongOrNull()
            ?: error("Timeout probe did not report elapsed time: $output")
    }
}
