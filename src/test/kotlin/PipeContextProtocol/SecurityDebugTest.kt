package com.TTT.PipeContextProtocol

import org.junit.Test
import kotlin.test.assertNotNull

/**
 * Debug test to check security system.
 */
class SecurityDebugTest
{
    @Test
    fun debugCommandClassification()
    {
        val securityManager = CommandSecurityManager()
        
        println("Testing command classifications:")
        
        val testCommands = listOf("echo", "ps", "chmod", "rm")
        testCommands.forEach { cmd ->
            val classification = securityManager.getCommandClassification(cmd)
            println("$cmd: ${classification?.level} (${classification?.platform})")
        }
        
        println("\nTesting validation:")
        println("echo with SAFE: ${securityManager.validateCommand("echo", emptyList(), SecurityLevel.SAFE)}")
        println("ps with SAFE: ${securityManager.validateCommand("ps", emptyList(), SecurityLevel.SAFE)}")
        println("ps with RESTRICTED: ${securityManager.validateCommand("ps", emptyList(), SecurityLevel.RESTRICTED)}")
        
        // Just ensure no crashes
        assertNotNull(securityManager)
    }
}
