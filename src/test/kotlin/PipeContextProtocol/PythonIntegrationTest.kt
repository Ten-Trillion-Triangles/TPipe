package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Integration test for Python script execution via PCP.
 */
class PythonIntegrationTest
{
    @Test
    fun testPythonScriptExecution()
    {
        runBlocking {
            // Create Python script
            val pythonScript = """
                import json
                import math
                
                # Simple data processing
                data = {'numbers': [1, 2, 3, 4, 5]}
                total = sum(data['numbers'])
                average = total / len(data['numbers'])
                
                # Math operations
                result = {
                    'total': total,
                    'average': average,
                    'sqrt_total': math.sqrt(total)
                }
                
                print(json.dumps(result))
            """.trimIndent()
            
            // Create PCP request
            val pcpRequest = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(pythonScript)
            )
            
            // Execute via dispatcher
            val dispatcher = PcpExecutionDispatcher()
            val result = dispatcher.executeRequest(pcpRequest, PcpContext())
            
            // Verify execution
            assertTrue(result.success, "Python script should execute successfully")
            assertEquals(Transport.Python, result.transport, "Should use Python transport")
            
            // Verify output contains expected JSON
            assertTrue(result.output.contains("total"), "Output should contain 'total' field")
            assertTrue(result.output.contains("average"), "Output should contain 'average' field")
            assertTrue(result.output.contains("sqrt_total"), "Output should contain 'sqrt_total' field")
            
            // Verify no errors
            assertTrue(result.error == null || result.error!!.isEmpty(), "Should not have errors: ${result.error}")
            
            println("✅ Python script executed successfully!")
            println("📊 Output: ${result.output}")
            println("⏱️ Execution time: ${result.executionTimeMs}ms")
        }
    }
    
    @Test
    fun testPythonScriptWithFileOperations()
    {
        runBlocking {
            // Create Python script that writes and reads a file
            val pythonScript = """
                import json
                import os
                
                # Create test data
                test_data = {'message': 'Hello from Python!', 'numbers': [1, 2, 3]}
                
                # Write to temp file
                temp_file = '/tmp/tpipe_test.json'
                with open(temp_file, 'w') as f:
                    json.dump(test_data, f)
                
                # Read back from file
                with open(temp_file, 'r') as f:
                    loaded_data = json.load(f)
                
                # Clean up
                os.remove(temp_file)
                
                print(f"File operation successful: {loaded_data['message']}")
            """.trimIndent()
            
            // Create PCP request with Write permission for file operations
            val pcpRequest = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    permissions = mutableListOf(Permissions.Read, Permissions.Write)
                    availablePackages = mutableListOf("json", "os")
                },
                argumentsOrFunctionParams = listOf(pythonScript)
            )

            val context = PcpContext().apply {
                pythonOptions.availablePackages = mutableListOf("json", "os")
                pythonOptions.permissions = mutableListOf(Permissions.Read, Permissions.Write)
            }

            val pythonExecutor = PythonExecutor().apply {
                allowImports("os")
            }

            val dispatcher = PcpExecutionDispatcher().also { current ->
                val field = current.javaClass.getDeclaredField("pythonExecutor")
                field.isAccessible = true
                field.set(current, pythonExecutor)
            }

            val result = dispatcher.executeRequest(pcpRequest, context)
            
            // Verify execution
            assertTrue(result.success, "Python file operations should execute successfully")
            assertTrue(result.output.contains("File operation successful"), "Should confirm file operations")
            assertTrue(result.output.contains("Hello from Python!"), "Should contain expected message")
            assertTrue(result.output.contains("Warnings:\n"), "Override warning should be surfaced")
            
            println("✅ Python file operations executed successfully!")
            println("📁 Output: ${result.output}")
        }
    }
    
    @Test
    fun testPythonSecurityBlocking()
    {
        runBlocking {
            // Create dangerous Python script
            val dangerousScript = """
                import os
                os.system('echo "This should be blocked"')
            """.trimIndent()
            
            // Create PCP request
            val pcpRequest = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    permissions = mutableListOf(Permissions.Read, Permissions.Write)
                },
                argumentsOrFunctionParams = listOf(dangerousScript)
            )
            
            // Execute via dispatcher
            val dispatcher = PcpExecutionDispatcher()
            val result = dispatcher.executeRequest(pcpRequest, PcpContext())
            
            // Verify security blocking
            assertTrue(!result.success, "Dangerous Python script should be blocked")
            assertTrue(result.error?.contains("os") == true, "Error should mention blocked 'os' import")
            
            println("✅ Python security validation working!")
            println("🛡️ Blocked dangerous script: ${result.error}")
        }
    }
    
    @Test
    fun testPythonSecurityOverride()
    {
        runBlocking {
            // Create script that needs os module
            val systemScript = """
                import os
                current_dir = os.getcwd()
                print(f"Current directory: {current_dir}")
            """.trimIndent()
            
            // Create executor with security override
            val executor = PythonExecutor()
            executor.allowImports("os")
            executor.allowFunctions("os.getcwd")
            
            // Create PCP request
            val pcpRequest = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(systemScript)
            )
            
            // Execute directly with overridden executor
            val result = executor.execute(pcpRequest, PcpContext())
            
            // Verify override works
            assertTrue(result.success, "Python script with security override should execute")
            assertTrue(result.output.contains("Current directory:"), "Should show current directory")
            
            println("✅ Python security override working!")
            println("📂 Output: ${result.output}")
        }
    }
}
