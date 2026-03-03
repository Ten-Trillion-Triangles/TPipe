package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class KotlinIntrospectionTest
{
    @Test
    fun testKotlinBasicExecution() = runBlocking {
        val executor = KotlinExecutor()
        val request = PcPRequest(
            kotlinContextOptions = KotlinContext(cinit = true),
            argumentsOrFunctionParams = listOf("val s = \"Hello from Kotlin!\"; s")
        )
        val context = PcpContext()

        val result = executor.execute(request, context)

        if (!result.success) {
            println("Kotlin execution failed: ${result.error}")
            println("Output: ${result.output}")
        }

        assertTrue(result.success, "Execution should be successful: ${result.error}")
        assertTrue(result.output.contains("Hello from Kotlin!"), "Output should contain result message: ${result.output}")
    }

    @Test
    fun testKotlinIntrospection() = runBlocking {
        val executor = KotlinExecutor()
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf("\"Global transport: \${com.TTT.PipeContextProtocol.PcpRegistry.globalContext.transport}\""),
            kotlinContextOptions = KotlinContext(cinit = true).apply { allowTpipeIntrospection = true }
        )
        val context = PcpContext()

        val result = executor.execute(request, context)

        assertTrue(result.success, "Execution should be successful: ${result.error}")
        assertTrue(result.output.contains("Global transport: Auto"), "Should be able to introspect PcpRegistry: ${result.output}")
    }

    @Test
    fun testKotlinSecurityRestricted() = runBlocking {
        val executor = KotlinExecutor()
        // Try to use java.io.File which should be blocked by default
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf("import java.io.File", "val f = File(\"test.txt\")")
        )
        val context = PcpContext()

        val result = executor.execute(request, context)

        assertFalse(result.success, "Execution should fail due to security")
        assertTrue(result.error?.contains("Import 'java.io.File' is not allowed") == true, "Error message should mention blocked import")
    }
}
