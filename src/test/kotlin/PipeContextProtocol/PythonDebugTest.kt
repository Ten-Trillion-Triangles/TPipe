package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Debug test for Python execution.
 */
class PythonDebugTest
{
    @Test
    fun testPythonDetection()
    {
        val platformManager = PythonPlatformManager()
        val detectionResult = platformManager.detectPythonInstallations()
        
        println("Python detection success: ${detectionResult.success}")
        println("Found installations: ${detectionResult.installations.size}")
        detectionResult.installations.forEach { installation ->
            println("  - ${installation.executable} (${installation.version})")
        }
        println("Default installation: ${detectionResult.defaultInstallation?.executable}")
        println("Errors: ${detectionResult.errors}")
    }
    
    @Test
    fun testSimplePythonExecution()
    {
        runBlocking {
            val pythonScript = "print('Hello World')"
            
            val pcpRequest = PcPRequest(
                pythonContextOptions = PythonContext().apply {
                    permissions = mutableListOf(Permissions.Read)
                },
                argumentsOrFunctionParams = listOf(pythonScript)
            )
            
            val dispatcher = PcpExecutionDispatcher()
            val result = dispatcher.executeRequest(pcpRequest, PcpContext())
            
            println("Success: ${result.success}")
            println("Output: '${result.output}'")
            println("Error: '${result.error}'")
            println("Transport: ${result.transport}")
            println("Execution time: ${result.executionTimeMs}ms")
        }
    }
}
