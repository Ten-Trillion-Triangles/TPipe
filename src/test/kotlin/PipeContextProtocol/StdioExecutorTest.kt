package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Test suite for StdioExecutor functionality.
 * Tests one-shot execution, interactive sessions, and buffer management.
 */
class StdioExecutorTest
{
    @Test
    fun testOneShotExecution()
    {
        runBlocking {
            val parser = PcpResponseParser()
            val dispatcher = PcpExecutionDispatcher()
            
            // Create PCP request for one-shot command
            val request = PcPRequest(
                stdioContextOptions = StdioContextOptions().apply {
                    command = "echo"
                    args = mutableListOf("Hello World")
                    executionMode = StdioExecutionMode.ONE_SHOT
                    permissions = mutableListOf(Permissions.Execute)
                }
            )
            
            // Execute through dispatcher
            val result = dispatcher.executeRequest(request)
            
            assertTrue(result.success, "One-shot execution should succeed")
            assertTrue(result.output.contains("Hello World"), "Output should contain expected text")
            assertEquals(Transport.Stdio, result.transport, "Should use Stdio transport")
        }
    }
    
    @Test
    fun testInteractiveSessionCreation()
    {
        runBlocking {
            val dispatcher = PcpExecutionDispatcher()
            
            // Create interactive session
            val request = PcPRequest(
                stdioContextOptions = StdioContextOptions().apply {
                    command = "cat" // Simple command that waits for input
                    executionMode = StdioExecutionMode.INTERACTIVE
                    bufferPersistence = true
                    permissions = mutableListOf(Permissions.Execute)
                }
            )
            
            val result = dispatcher.executeRequest(request)
            
            assertTrue(result.success, "Interactive session creation should succeed")
            assertTrue(result.output.contains("Session created:"), "Should indicate session creation")
        }
    }
    
    @Test
    fun testSecurityValidation()
    {
        runBlocking {
            val dispatcher = PcpExecutionDispatcher()
            
            // Test dangerous command rejection
            val dangerousRequest = PcPRequest(
                stdioContextOptions = StdioContextOptions().apply {
                    command = "rm"
                    args = mutableListOf("-rf", "/")
                    executionMode = StdioExecutionMode.ONE_SHOT
                }
            )
            
            val result = dispatcher.executeRequest(dangerousRequest)
            
            assertFalse(result.success, "Dangerous command should be rejected")
            assertTrue(result.error?.contains("not allowed") == true, "Should indicate command not allowed")
        }
    }
    
    @Test
    fun testCommandInjectionPrevention()
    {
        runBlocking {
            val dispatcher = PcpExecutionDispatcher()
            
            // Test command injection attempt
            val injectionRequest = PcPRequest(
                stdioContextOptions = StdioContextOptions().apply {
                    command = "echo"
                    args = mutableListOf("test; rm -rf /")
                    executionMode = StdioExecutionMode.ONE_SHOT
                }
            )
            
            val result = dispatcher.executeRequest(injectionRequest)
            
            assertFalse(result.success, "Command injection should be prevented")
            assertTrue(result.error?.contains("injection") == true, "Should detect injection attempt")
        }
    }
    
    @Test
    fun testBufferManagement()
    {
        val bufferManager = StdioBufferManager()
        
        // Create buffer
        val buffer = bufferManager.createBuffer("test-session")
        
        // Add entries
        bufferManager.appendToBuffer(buffer.bufferId, "input command", BufferDirection.INPUT)
        bufferManager.appendToBuffer(buffer.bufferId, "output result", BufferDirection.OUTPUT)
        bufferManager.appendToBuffer(buffer.bufferId, "error message", BufferDirection.ERROR)
        
        // Test retrieval
        val retrievedBuffer = bufferManager.getBuffer(buffer.bufferId)
        assertEquals(3, retrievedBuffer?.entries?.size, "Should have 3 entries")
        
        // Test search
        val matches = bufferManager.searchBuffer(buffer.bufferId, "command")
        assertEquals(1, matches.size, "Should find 1 match for 'command'")
        assertEquals("input command", matches.first().entry.content, "Should match correct entry")
        
        // Test stats
        val stats = bufferManager.getBufferStats(buffer.bufferId)
        assertEquals(1, stats?.get("inputEntries"), "Should have 1 input entry")
        assertEquals(1, stats?.get("outputEntries"), "Should have 1 output entry")
        assertEquals(1, stats?.get("errorEntries"), "Should have 1 error entry")
    }
}
