package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the contract for `KotlinExecutor` against the JSR-223 scripting runtime.
 * The Kotlin 2.3 readiness sweep is anchored here because the kotlinx-scripting
 * runtime depends on language-version features that change between compiler
 * minors; any regression in script discovery, evaluation, exception reporting,
 * timeout enforcement, or the security gate surfaces here first.
 */
class KotlinScriptingContractTest
{
    @Test
    fun `script with timeoutMs=200 and a non-terminating while loop returns timeout error within bound`() = runBlocking {
        val executor = KotlinExecutor()
        val request = PcPRequest(argumentsOrFunctionParams = listOf("while (true) { }"))
        val context = PcpContext().apply { kotlinOptions.timeoutMs = 200 }
        val start = System.currentTimeMillis()
        val result = executor.execute(request, context)
        val elapsed = System.currentTimeMillis() - start
        assertFalse(result.success)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("timed out", ignoreCase = true),
            "expected timeout error, got: ${result.error}")
        assertTrue(elapsed < 5_000L, "executor took ${elapsed}ms — timeout not enforced")
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
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("boom"),
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
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Reflection"),
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
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("ClassLoader"),
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
    fun `default kts engine is available on the classpath`() = runBlocking {
        // Sanity check: the kotlin-scripting-jsr223 dependency is on the test
        // classpath, so ScriptEngineManager.getEngineByExtension("kts") must succeed.
        val executor = KotlinExecutor()
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf("\"hello\"")
        )
        val result = executor.execute(request, PcpContext())
        assertTrue(result.success,
            "Default kts engine should be available; got error=${result.error}")
    }
}
