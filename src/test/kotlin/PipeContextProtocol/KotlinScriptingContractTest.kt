package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the public contract for [KotlinExecutor] independently of its internal
 * scripting backend.
 */
class KotlinScriptingContractTest
{
    @Test
    fun `script with timeoutMs=200 and a non-terminating while loop returns timeout error within bound`() = runBlocking {
        val start = System.currentTimeMillis()
        val process = ProcessBuilder(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            KotlinTimeoutProbeMain::class.java.name
        ).redirectErrorStream(true).start()
        val completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        if(!completed)
        {
            process.destroyForcibly()
        }
        val output = process.inputStream.bufferedReader().readText()
        val elapsed = System.currentTimeMillis() - start
        assertTrue(completed, "timeout probe did not exit: $output")
        assertTrue(output.contains("timed out", ignoreCase = true),
            "expected timeout error, got: $output")
        assertTrue(timeoutProbeElapsed(output) < 1_500L,
            "executor-reported timeout elapsed time exceeded the bound: $output")
        assertTrue(elapsed < 5_000L, "executor took ${elapsed}ms — timeout probe did not exit")
    }

    @Test
    fun `script with default timeout returns result for a simple expression`() = runBlocking {
        val executor = KotlinExecutor()
        val request = PcPRequest(argumentsOrFunctionParams = listOf("val x = 6; val y = 7; x * y"))
        val result = executor.execute(request, PcpContext())
        assertTrue(result.success, "Execution should be successful: ${result.error}")
        assertTrue(result.output.contains("42"), "Output should contain 42: ${result.output}")
    }

    @Test
    fun `script that throws an exception returns error rather than crash`() = runBlocking {
        val executor = KotlinExecutor()
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf("throw IllegalStateException(\"boom\")")
        )
        val result = executor.execute(request, PcpContext())
        assertFalse(result.success)
        val error = assertNotNull(result.error)
        assertTrue(error.contains("boom"),
            "Expected error message to propagate; got: ${result.error}")
    }

    @Test
    fun `script that uses double-colon class syntax without allowReflection is rejected at validation time`() = runBlocking {
        val executor = KotlinExecutor()
        // The `::class` literal triggers the reflection blocklist in the security gate.
        val request = PcPRequest(argumentsOrFunctionParams = listOf("val k = String::class"))
        val context = PcpContext()  // default: allowReflection = false
        val result = executor.execute(request, context)
        assertFalse(result.success)
        val error = assertNotNull(result.error)
        assertTrue(error.contains("Reflection"),
            "Expected Reflection error; got: ${result.error}")
    }

    @Test
    fun `script that uses ClassLoader without allowClassLoaderAccess is rejected at validation time`() = runBlocking {
        val executor = KotlinExecutor()
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf("val cl = ClassLoader.getSystemClassLoader()")
        )
        val context = PcpContext()  // default: allowClassLoaderAccess = false
        val result = executor.execute(request, context)
        assertFalse(result.success)
        val error = assertNotNull(result.error)
        assertTrue(error.contains("ClassLoader"),
            "Expected ClassLoader error; got: ${result.error}")
    }

    @Test
    fun `allowTpipeIntrospection exposes PcpRegistry global context to the script`() = runBlocking {
        val executor = KotlinExecutor()
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf(
                "\"transport=\${com.TTT.PipeContextProtocol.PcpRegistry.globalContext.transport}\""
            )
        )
        val context = PcpContext().apply { kotlinOptions.allowTpipeIntrospection = true }
        val result = executor.execute(request, context)
        assertTrue(result.success,
            "Expected success; got error=${result.error}, output=${result.output}")
        assertTrue(result.output.contains("transport="),
            "Output should contain transport= from PcpRegistry: ${result.output}")
    }

    @Test
    fun `internal Kotlin scripting backend is available`() = runBlocking {
        val executor = KotlinExecutor()
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf("\"hello\"")
        )
        val result = executor.execute(request, PcpContext())
        assertTrue(result.success,
            "Internal Kotlin scripting backend should be available; got error=${result.error}")
    }

    private fun javaExecutable(): String
    {
        return java.nio.file.Path.of(
            System.getProperty("java.home"),
            "bin",
            if(System.getProperty("os.name").contains("Windows")) "java.exe" else "java"
        ).toString()
    }

    private fun timeoutProbeElapsed(output: String): Long
    {
        return output.substringAfter("|elapsedMs=", missingDelimiterValue = "")
            .trim()
            .toLongOrNull()
            ?: error("Timeout probe did not report elapsed time: $output")
    }
}
