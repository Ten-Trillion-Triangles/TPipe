package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Basic Python functionality test.
 */
class PythonBasicTest
{
    @Test
    fun testPythonTransportDetection()
    {
        val parser = PcpResponseParser()
        
        // Test Python transport detection via pythonPath
        val pythonPathRequest = PcPRequest(
            pythonContextOptions = PythonContext().apply {
                pythonPath = "/usr/bin/python3"
            }
        )
        
        val transport1 = parser.determineTransport(pythonPathRequest)
        assertEquals(Transport.Python, transport1, "Should detect Python transport via pythonPath")
        
        // Test Python transport detection via script content
        val scriptRequest = PcPRequest(
            argumentsOrFunctionParams = listOf("print('hello world')")
        )
        
        val transport2 = parser.determineTransport(scriptRequest)
        assertEquals(Transport.Python, transport2, "Should detect Python transport via script content")
    }
    
    @Test
    fun testPythonValidation()
    {
        val parser = PcpResponseParser()
        
        // Test valid Python request
        val validRequest = PcPRequest(
            pythonContextOptions = PythonContext().apply {
                pythonPath = "/usr/bin/python3"
                permissions = mutableListOf(Permissions.Read)
            },
            argumentsOrFunctionParams = listOf("print('hello world')")
        )
        
        val validation = parser.validatePcpRequest(validRequest)
        assertTrue(validation.isValid, "Valid Python request should pass validation")
        
        // Test invalid Python request (missing script)
        val invalidRequest = PcPRequest(
            pythonContextOptions = PythonContext().apply {
                pythonPath = "/usr/bin/python3"
            }
        )
        
        val invalidValidation = parser.validatePcpRequest(invalidRequest)
        assertFalse(invalidValidation.isValid, "Python request without script should fail validation")
        assertTrue(invalidValidation.errors.any { it.contains("Python script is required") }, 
            "Should indicate missing script")
    }
    
    @Test
    fun testPythonSecurityManager()
    {
        val securityManager = PythonSecurityManager()
        
        // Test safe script
        val safeScript = """
            import pandas as pd
            import requests
            data = {'name': ['Alice', 'Bob'], 'age': [25, 30]}
            df = pd.DataFrame(data)
            print(df.head())
        """.trimIndent()
        
        val context = PythonContext().apply {
            permissions = mutableListOf(Permissions.Read)
        }
        
        val safeValidation = securityManager.validatePythonRequest(safeScript, context)
        assertTrue(safeValidation.isValid, "Safe Python script should pass validation")
        
        // Test dangerous script
        val dangerousScript = """
            import os
            os.system('rm -rf /')
        """.trimIndent()
        
        val dangerousValidation = securityManager.validatePythonRequest(dangerousScript, context)
        assertFalse(dangerousValidation.isValid, "Dangerous Python script should fail validation")
        assertTrue(dangerousValidation.errors.any { it.contains("os") }, 
            "Should indicate blocked import")
    }
    
    @Test
    fun testPythonSecurityOverrides()
    {
        val securityManager = PythonSecurityManager()
        
        // Test that overrides work
        val config = PythonSecurityConfig(
            level = PythonSecurityLevel.BALANCED,
            allowedImports = setOf("os"),
            allowedFunctions = setOf("os.makedirs")
        )
        securityManager.setSecurityConfig(config)
        
        val scriptWithOverride = """
            import os
            os.makedirs('/tmp/test', exist_ok=True)
        """.trimIndent()
        
        val context = PythonContext().apply {
            permissions = mutableListOf(Permissions.Write)
        }
        
        val validation = securityManager.validatePythonRequest(scriptWithOverride, context)
        assertTrue(validation.isValid, "Script with security overrides should pass validation")
    }
    
    @Test
    fun testPythonPlatformManager()
    {
        val platformManager = PythonPlatformManager()
        
        // Test Python detection
        val detectionResult = platformManager.detectPythonInstallations()
        
        // Should find at least one Python installation on most systems
        // (This test might fail in environments without Python, which is acceptable)
        if (detectionResult.success)
        {
            assertTrue(detectionResult.installations.isNotEmpty(), "Should find Python installations")
            assertTrue(detectionResult.defaultInstallation != null, "Should have default installation")
        }
        
        // Test command building
        val command = platformManager.buildPythonCommand(
            "python3", 
            "print('test')", 
            "/tmp"
        )
        
        assertEquals(3, command.size, "Should build 3-part command")
        assertEquals("python3", command[0], "Should use specified executable")
        assertEquals("-c", command[1], "Should use -c flag")
        assertEquals("print('test')", command[2], "Should include script content")
    }
}
