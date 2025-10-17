package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for complete stdio execution pipeline.
 * Tests end-to-end flow from LLM response parsing to command execution.
 */
class StdioIntegrationTest
{
    @Test
    fun testCompleteStdioPipeline()
    {
        runBlocking {
            // Simulate LLM response with stdio PCP request
            val llmResponse = """
            {
                "stdioContextOptions": {
                    "command": "echo",
                    "args": ["Hello from stdio!"],
                    "executionMode": "ONE_SHOT",
                    "permissions": ["Execute"]
                }
            }
            """.trimIndent()
            
            // Test complete pipeline
            val parser = PcpResponseParser()
            val dispatcher = PcpExecutionDispatcher()
            
            // 1. Parse LLM response
            val parseResult = parser.extractPcpRequests(llmResponse)
            assertTrue(parseResult.success, "Should parse stdio request successfully")
            assertEquals(1, parseResult.requests.size, "Should find one request")
            
            val request = parseResult.requests.first()
            assertEquals(Transport.Stdio, parser.determineTransport(request), "Should detect Stdio transport")
            
            // 2. Execute through dispatcher
            val executionResult = dispatcher.executeRequests(parseResult.requests, PcpContext())
            assertTrue(executionResult.success, "Should execute stdio command successfully")
            assertEquals(1, executionResult.results.size, "Should have one result")
            
            val result = executionResult.results.first()
            assertTrue(result.success, "Stdio command should execute successfully")
            assertTrue(result.output.contains("Hello from stdio!"), "Should return command output")
            assertEquals(Transport.Stdio, result.transport, "Should use Stdio transport")
        }
    }
    
    @Test
    fun testStdioSecurityIntegration()
    {
        runBlocking {
            // Test that security validation works in full pipeline
            val maliciousResponse = """
            {
                "stdioContextOptions": {
                    "command": "rm",
                    "args": ["-rf", "/important/data"],
                    "executionMode": "ONE_SHOT",
                    "permissions": ["Execute"]
                }
            }
            """.trimIndent()
            
            val parser = PcpResponseParser()
            val dispatcher = PcpExecutionDispatcher()
            
            val parseResult = parser.extractPcpRequests(maliciousResponse)
            assertTrue(parseResult.success, "Should parse malicious request")
            
            val executionResult = dispatcher.executeRequests(parseResult.requests, PcpContext())
            assertTrue(!executionResult.success, "Dispatcher should report failure when security blocks request")
            
            val result = executionResult.results.first()
            assertTrue(!result.success, "Malicious command should be blocked")
            assertTrue(result.error?.contains("is not allowed") == true,
                "Should indicate security level exceeded")
        }
    }
}
