package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PythonSecurityBypassTest
{
    @Test
    fun `bypass import restriction using string concatenation`()
    {
        runBlocking {
            val executor = PythonExecutor()
            executor.setSecurityLevel(PythonSecurityLevel.STRICT)

            // 'os' is blocked in STRICT level
            val script = """
                import importlib
                module_name = 'o' + 's'
                os = importlib.import_module(module_name)
                print(os.name)
            """.trimIndent()

            val request = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(script)
            )

            val result = executor.execute(request, PcpContext())

            // Now that we have AST validation, this should FAIL
            assertFalse(result.success, "Bypass using importlib should be blocked. Result: $result")
            assertTrue(result.error?.contains("Import via import_module is not allowed: 'os'") == true,
                "Expected error about blocked import, got: ${result.error}")
        }
    }

    @Test
    fun `bypass function restriction using getattr`()
    {
        runBlocking {
            val executor = PythonExecutor()
            executor.setSecurityLevel(PythonSecurityLevel.STRICT)

            // 'eval' is blocked in STRICT level
            val script = """
                e = getattr(__builtins__, 'ev' + 'al')
                e('print("Bypassed!")')
            """.trimIndent()

            val request = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(script)
            )

            val result = executor.execute(request, PcpContext())

            // Now that we have AST validation, this should FAIL
            assertFalse(result.success, "Bypass using getattr should be blocked. Result: $result")
            assertTrue(result.error?.contains("Access to blocked function via getattr: 'eval'") == true,
                "Expected error about blocked function, got: ${result.error}")
        }
    }

    @Test
    fun `direct blocked import still fails`()
    {
        runBlocking {
            val executor = PythonExecutor()
            executor.setSecurityLevel(PythonSecurityLevel.STRICT)

            val script = "import os"

            val request = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(script)
            )

            val result = executor.execute(request, PcpContext())

            assertFalse(result.success, "Direct blocked import should fail")
            assertTrue(result.error?.contains("Import 'os' is not allowed") == true)
        }
    }
}
