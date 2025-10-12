package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Test suite for enhanced security level system.
 * Tests multi-level security, platform awareness, and developer overrides.
 */
class EnhancedSecurityTest
{
    @Test
    fun testSecurityLevels()
    {
        val securityManager = CommandSecurityManager()
        
        // Test SAFE level commands
        assertTrue(securityManager.validateCommand("echo", emptyList(), SecurityLevel.SAFE))
        assertTrue(securityManager.validateCommand("cat", emptyList(), SecurityLevel.SAFE))
        
        // Test RESTRICTED level commands
        assertTrue(securityManager.validateCommand("ps", emptyList(), SecurityLevel.RESTRICTED))
        assertFalse(securityManager.validateCommand("ps", emptyList(), SecurityLevel.SAFE))
        
        // Test DANGEROUS level commands  
        assertTrue(securityManager.validateCommand("chmod", emptyList(), SecurityLevel.DANGEROUS))
        assertFalse(securityManager.validateCommand("chmod", emptyList(), SecurityLevel.RESTRICTED))
        
        // Test FORBIDDEN level commands
        assertTrue(securityManager.validateCommand("rm", emptyList(), SecurityLevel.FORBIDDEN))
        assertFalse(securityManager.validateCommand("rm", emptyList(), SecurityLevel.DANGEROUS))
    }
    
    @Test
    fun testDeveloperCanAllowForbiddenCommands()
    {
        runBlocking {
            val dispatcher = PcpExecutionDispatcher()
            
            // Test that rm is blocked with normal permissions
            val blockedRequest = PcPRequest(
                stdioContextOptions = StdioContextOptions().apply {
                    command = "rm"
                    args = mutableListOf("test.txt")
                    permissions = mutableListOf(Permissions.Execute) // Only DANGEROUS level
                }
            )
            
            val blockedResult = dispatcher.executeRequest(blockedRequest)
            assertFalse(blockedResult.success, "rm should be blocked with Execute permission")
            
            // Test that rm is allowed with Delete permission (FORBIDDEN level)
            val allowedRequest = PcPRequest(
                stdioContextOptions = StdioContextOptions().apply {
                    command = "rm"
                    args = mutableListOf("test.txt")
                    permissions = mutableListOf(Permissions.Delete) // FORBIDDEN level allowed
                }
            )
            
            val allowedResult = dispatcher.executeRequest(allowedRequest)
            // Note: This will still fail because rm will actually try to delete a file
            // But the security validation should pass (different error message)
            assertTrue(allowedResult.error?.contains("exceeds maximum allowed level") != true, 
                "rm should pass security validation with Delete permission")
        }
    }
    
    @Test
    fun testCommandClassification()
    {
        val securityManager = CommandSecurityManager()
        
        // Test safe command classification
        val echoClassification = securityManager.getCommandClassification("echo")
        assertEquals(SecurityLevel.SAFE, echoClassification?.level)
        
        // Test restricted command classification
        val psClassification = securityManager.getCommandClassification("ps")
        assertEquals(SecurityLevel.RESTRICTED, psClassification?.level)
        
        // Test dangerous command classification
        val chmodClassification = securityManager.getCommandClassification("chmod")
        assertEquals(SecurityLevel.DANGEROUS, chmodClassification?.level)
        
        // Test forbidden command classification
        val rmClassification = securityManager.getCommandClassification("rm")
        assertEquals(SecurityLevel.FORBIDDEN, rmClassification?.level)
    }
    
    @Test
    fun testCustomOverrides()
    {
        val securityManager = CommandSecurityManager()
        
        // Override rm to be SAFE (not recommended but should work)
        securityManager.setCommandClassification("rm", 
            CommandClassification(SecurityLevel.SAFE, Platform.LINUX, "Custom override"))
        
        // Test that override works
        val classification = securityManager.getCommandClassification("rm")
        assertEquals(SecurityLevel.SAFE, classification?.level)
        
        // Test that validation respects override
        assertTrue(securityManager.validateCommand("rm", emptyList(), SecurityLevel.SAFE))
    }
    
    @Test
    fun testGetCommandsByLevel()
    {
        val securityManager = CommandSecurityManager()
        
        // Get only safe commands
        val safeCommands = securityManager.getCommandsBySecurityLevel(SecurityLevel.SAFE)
        assertTrue(safeCommands.containsKey("echo"))
        assertFalse(safeCommands.containsKey("rm"))
        
        // Get safe + restricted commands
        val restrictedCommands = securityManager.getCommandsBySecurityLevel(SecurityLevel.RESTRICTED)
        assertTrue(restrictedCommands.containsKey("echo")) // Safe included
        assertTrue(restrictedCommands.containsKey("ps"))   // Restricted included
        assertFalse(restrictedCommands.containsKey("rm"))  // Forbidden excluded
        
        // Get all except forbidden
        val dangerousCommands = securityManager.getCommandsBySecurityLevel(SecurityLevel.DANGEROUS)
        assertTrue(dangerousCommands.containsKey("echo"))  // Safe included
        assertTrue(dangerousCommands.containsKey("ps"))    // Restricted included
        assertTrue(dangerousCommands.containsKey("chmod")) // Dangerous included
        assertFalse(dangerousCommands.containsKey("rm"))   // Forbidden excluded
        
        // Get all commands including forbidden
        val allCommands = securityManager.getCommandsBySecurityLevel(SecurityLevel.FORBIDDEN)
        assertTrue(allCommands.containsKey("echo"))  // Safe included
        assertTrue(allCommands.containsKey("ps"))    // Restricted included
        assertTrue(allCommands.containsKey("chmod")) // Dangerous included
        assertTrue(allCommands.containsKey("rm"))    // Forbidden included
    }
}
