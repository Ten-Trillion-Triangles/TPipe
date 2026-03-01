package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.io.File

class JavaScriptExecutionTest
{
    private fun isNodeAvailable(): Boolean
    {
        return try
        {
            val process = ProcessBuilder("node", "--version").start()
            process.waitFor() == 0
        }
        catch (e: Exception)
        {
            false
        }
    }

    @Test
    fun testJavaScriptBasicExecution() = runBlocking {
        if (!isNodeAvailable()) {
            println("Skipping JavaScript test: Node.js not found")
            return@runBlocking
        }

        val executor = JavaScriptExecutor()
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf("console.log('Hello from JS');")
        )
        val context = PcpContext()

        val result = executor.execute(request, context)

        assertTrue(result.success, "Execution should be successful: ${result.error}")
        assertTrue(result.output.contains("Hello from JS"), "Output should contain hello message")
    }

    @Test
    fun testJavaScriptSecurityRestricted() = runBlocking {
        val executor = JavaScriptExecutor()
        // Try to use 'fs' which should be blocked by default
        val request = PcPRequest(
            argumentsOrFunctionParams = listOf("const fs = require('fs');", "fs.readFileSync('test.txt');")
        )
        val context = PcpContext()

        val result = executor.execute(request, context)

        assertFalse(result.success, "Execution should fail due to security")
        assertTrue(result.error?.contains("Module 'fs' is not allowed") == true, "Error message should mention blocked module")
    }
}
