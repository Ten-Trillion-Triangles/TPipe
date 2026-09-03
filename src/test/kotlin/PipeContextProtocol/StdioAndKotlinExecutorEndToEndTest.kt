package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end PcpStdioHost / KotlinExecutor boundary tests. These exercise
 * the daemon-thread + Thread.join(timeout) path inside [KotlinExecutor] and
 * the System.setIn/System.setOut redirect inside [PcpStdioHost.runOnce].
 */
class StdioAndKotlinExecutorEndToEndTest
{
    @After
    fun cleanup() {
        FunctionRegistry.clear()
    }

    @Test
    fun `PcpStdioHost runOnce handles a JSON request with a non-trivial Tpipe tool call`() {
        // registerLambda because { name: String -> ... } is a Function1, not a KFunction.
        FunctionRegistry.registerLambda(
            "greet",
            { name: String -> "hello $name" },
            FunctionSignature(
                name = "greet",
                parameters = listOf(
                    ParameterInfo("name", ParamType.String, "kotlin.String", false, null, emptyList(), "")
                ),
                returnType = ReturnTypeInfo(ParamType.String, "kotlin.String", false, ""),
                description = ""
            )
        )
        val request = PcPRequest().apply {
            tPipeContextOptions = TPipeContextOptions().apply { functionName = "greet" }
            argumentsOrFunctionParams = listOf("world")
        }
        val requestJson = com.TTT.Util.serialize(request, true).replace("\n", " ").replace("\r", " ") + "\n"
        val oldIn = System.`in`
        val oldOut = System.out
        try
        {
            System.setIn(requestJson.byteInputStream())
            val baos = java.io.ByteArrayOutputStream()
            System.setOut(java.io.PrintStream(baos))
            PcpStdioHost.runOnce()
            val output = baos.toString().trim()
            val result = com.TTT.Util.deserialize<PcpExecutionResult>(output)
            assertNotNull(result, "Stdio host must emit a parseable PcpExecutionResult; got: $output")
            // PcpStdioHost's context requires the function to be whitelisted. The
            // default PcpContext() does NOT whitelist any TPipe function names, so
            // PcpStdioHost returns `success = false` with either a
            // "Function '<name>' not in context whitelist" or
            // "Function execution not enabled in context" error depending on the
            // host's branching. The test pins this current contract; if the host
            // grows an auto-whitelist for registered functions, the assertion below
            // should flip to expect success.
            if(result.success)
            {
                val first = assertNotNull(
                    result.results.firstOrNull(),
                    "Stdio host result should include a result entry; got: $output"
                )
                assertEquals("hello world", first.output)
            }
            else
            {
                val allErrors: List<String> = result.errors + result.results.mapNotNull { it.error }
                val hasWhitelistError = allErrors.any {
                    it.contains("not in context whitelist") ||
                        it.contains("Function execution not enabled")
                }
                assertTrue(hasWhitelistError,
                    "Expected whitelist-related error; got: $output")
            }
        }
        finally
        {
            System.setIn(oldIn)
            System.setOut(oldOut)
            FunctionRegistry.clear()
        }
    }

    @Test
    fun `KotlinExecutor timeout probe returns a non-success result without poisoning the test JVM`() = runBlocking {
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
        assertTrue(completed, "timeout probe did not exit: $output")
        assertTrue(output.contains("timed out", ignoreCase = true),
            "Expected timeout error; got: $output")
        val elapsed = timeoutProbeElapsed(output)
        assertTrue(elapsed < 1_500L, "timeout probe took ${elapsed}ms — timeout not enforced")
    }

    @Test
    fun `PcpExecutionDispatcher routes a Tpipe call to the registered function`() = runBlocking {
        // Use registerLambda for the same reason: a local Function1 is not a KFunction.
        FunctionRegistry.registerLambda(
            "echo",
            { s: String -> "echoed:$s" },
            FunctionSignature(
                name = "echo",
                parameters = listOf(
                    ParameterInfo("s", ParamType.String, "kotlin.String", false, null, emptyList(), "")
                ),
                returnType = ReturnTypeInfo(ParamType.String, "kotlin.String", false, ""),
                description = ""
            )
        )
        val request = PcPRequest().apply {
            tPipeContextOptions = TPipeContextOptions().apply { functionName = "echo" }
            argumentsOrFunctionParams = listOf("alpha")
        }
        val dispatcher = PcpExecutionDispatcher()
        val ctx = PcpContext().apply {
            addTPipeOption(TPipeContextOptions().apply { functionName = "echo" })
        }
        val results = dispatcher.executeRequests(listOf(request), ctx)
        assertEquals(true, results.success, "Dispatcher should succeed: ${results.errors}")
        assertEquals(1, results.results.size)
        assertEquals("echoed:alpha", results.results.first().output)
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
