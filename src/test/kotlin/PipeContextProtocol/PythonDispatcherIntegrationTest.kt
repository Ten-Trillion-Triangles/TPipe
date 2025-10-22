package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration coverage for Python execution via dispatcher with context merging and warnings.
 */
class PythonDispatcherIntegrationTest
{
    @Test
    fun `request whitelist denies disallowed import`()
    {
        runBlocking {
            val dispatcher = PcpExecutionDispatcher()

            val context = PcpContext().apply {
                pythonOptions.availablePackages = mutableListOf("json", "os")
                pythonOptions.permissions = mutableListOf(Permissions.Read)
            }

            val script = """
                import json
                import statistics
                print(json.dumps({'mean': statistics.mean([1, 2, 3])}))
            """.trimIndent()

            val request = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    availablePackages = mutableListOf("json", "os")
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(script)
            )

            println("Context available packages: ${context.pythonOptions.availablePackages}")
            println("Request available packages: ${request.pythonContextOptions.availablePackages}")
            val result = dispatcher.executeRequest(request, context)

            assertTrue(!result.success)
            assertEquals(Transport.Python, result.transport)
            assertTrue(
                result.error?.contains("Import 'statistics' not in allowed packages list") == true,
                "Expected blocked import message, got: ${result.error}"
            )
        }
    }

    @Test
    fun `context override allows additional import with warnings`()
    {
        runBlocking {
            val dispatcher = PcpExecutionDispatcher()

            val context = PcpContext().apply {
                pythonOptions.availablePackages = mutableListOf("json", "os")
                pythonOptions.permissions = mutableListOf(Permissions.Read)
            }

            val script = """
                import json
                import os
                print(json.dumps({'platform': os.name}))
            """.trimIndent()

            val request = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    availablePackages = mutableListOf("json", "os")
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(script)
            )

            val executor = PythonExecutor().apply { allowImports("os") }

            val pythonField = dispatcher.javaClass.getDeclaredField("pythonExecutor").apply { isAccessible = true }
            pythonField.set(dispatcher, executor)

            val result = dispatcher.executeRequest(request, context)
            assertTrue(result.success)
            assertEquals(Transport.Python, result.transport)
            assertTrue(result.output.contains("Warnings:\n"))
            assertTrue(result.output.contains("Import 'os' allowed via security override"))
        }
    }
}
