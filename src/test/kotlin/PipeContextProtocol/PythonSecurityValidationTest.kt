package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Focused tests for Python import and package validation logic.
 */
class PythonSecurityValidationTest
{
    @Test
    fun `request-level package whitelist enforced`()
    {
        runBlocking {
            val executor = PythonExecutor()

            val script = """
                import json
                import statistics
                print(json.dumps({'mean': statistics.mean([1, 2, 3])}))
            """.trimIndent()

            val request = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    availablePackages = mutableListOf("json")
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(script)
            )

            val result = executor.execute(request, PcpContext())

            assertTrue(!result.success, "Script should fail due to disallowed import")
            assertTrue(
                result.error?.contains("Import 'statistics' not in allowed packages list") == true,
                "Expected package whitelist error, got: ${result.error}"
            )
        }
    }

    @Test
    fun `context-level package whitelist enforced`()
    {
        runBlocking {
            val executor = PythonExecutor()

            val script = """
                import json
                print(json.dumps({'ok': True}))
            """.trimIndent()

            val context = PcpContext().apply {
                pythonOptions.availablePackages = mutableListOf("json")
                pythonOptions.permissions = mutableListOf(Permissions.Read)
            }

            val request = PcPRequest(
                pythonContextOptions = PythonContext(),
                argumentsOrFunctionParams = listOf(script)
            )

            val result = executor.execute(request, context)

            assertTrue(result.success, "Script should execute when imports match whitelist")
        }
    }

    @Test
    fun `security overrides surface warnings`()
    {
        runBlocking {
            val executor = PythonExecutor()
            executor.allowImports("os")

            val script = """
                import os
                print(os.name)
            """.trimIndent()

            val request = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(script)
            )

            val result = executor.execute(request, PcpContext())

            assertTrue(result.success, "Script should execute successfully when override is applied")
            assertTrue(
                result.output.contains("Warnings:\n"),
                "Warnings should be prefixed in the output"
            )
            assertTrue(
                result.output.contains("Import 'os' allowed via security override"),
                "Expected override warning to be surfaced"
            )
        }
    }
}
