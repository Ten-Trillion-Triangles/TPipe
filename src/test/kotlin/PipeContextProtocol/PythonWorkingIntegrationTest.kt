package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Working Python integration test demonstrating PCP execution.
 */
class PythonWorkingIntegrationTest
{
    @Test
    fun testPythonScriptViaPCP()
    {
        runBlocking {
            println("🐍 Testing Python script execution via PCP...")
            
            // Create a Python script that does data processing
            val pythonScript = """
                import json
                import math
                
                # Process some data
                numbers = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
                
                result = {
                    'count': len(numbers),
                    'sum': sum(numbers),
                    'average': sum(numbers) / len(numbers),
                    'squares': [x*x for x in numbers[:5]],
                    'message': 'Python via PCP works!'
                }
                
                print(json.dumps(result, indent=2))
            """.trimIndent()
            
            // Create PCP request
            val pcpRequest = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(pythonScript)
            )
            
            // Execute via PCP dispatcher
            val dispatcher = PcpExecutionDispatcher()
            val result = dispatcher.executeRequest(pcpRequest)
            
            // Print results
            println("✅ Execution successful: ${result.success}")
            println("📊 Output:")
            println(result.output)
            println("⏱️ Execution time: ${result.executionTimeMs}ms")
            
            // Verify results
            assertTrue(result.success, "Python script should execute successfully")
            assertEquals(Transport.Python, result.transport, "Should use Python transport")
            assertTrue(result.output.contains("Python via PCP works!"), "Should contain success message")
            assertTrue(result.output.contains("count"), "Should contain processed data")
            
            println("🎉 Python PCP integration test PASSED!")
        }
    }
    
    @Test
    fun testPythonSecurityValidation()
    {
        runBlocking {
            println("🛡️ Testing Python security validation...")
            
            // Create a script that should be blocked
            val dangerousScript = """
                import os
                print("About to do something dangerous...")
                os.system('echo "This should be blocked"')
            """.trimIndent()
            
            val pcpRequest = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(dangerousScript)
            )
            
            val dispatcher = PcpExecutionDispatcher()
            val result = dispatcher.executeRequest(pcpRequest)
            
            println("🚫 Blocked successfully: ${!result.success}")
            println("🛡️ Security error: ${result.error}")
            
            // Verify security worked
            assertTrue(!result.success, "Dangerous script should be blocked")
            assertTrue(result.error?.contains("os") == true, "Should mention blocked 'os' import")
            
            println("✅ Python security validation PASSED!")
        }
    }
    
    @Test
    fun testPythonWithOverrides()
    {
        runBlocking {
            println("🔓 Testing Python security overrides...")
            
            // Create executor with overrides
            val executor = PythonExecutor()
            executor.allowImports("os")
            executor.allowFunctions("os.getcwd")
            
            val script = """
                import os
                current_dir = os.getcwd()
                print(f"Working directory: {current_dir}")
            """.trimIndent()
            
            val pcpRequest = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(script)
            )
            
            val result = executor.execute(pcpRequest)
            
            println("✅ Override successful: ${result.success}")
            println("📂 Output: ${result.output}")
            
            assertTrue(result.success, "Script with overrides should work")
            assertTrue(result.output.contains("Working directory:"), "Should show directory")
            
            println("🎉 Python security overrides PASSED!")
        }
    }
}
