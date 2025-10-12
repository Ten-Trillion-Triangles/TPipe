package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for the complete PCP execution pipeline.
 * Tests the flow from LLM response parsing through to native function execution.
 */
class PcpExecutionPipelineTest
{
    fun testFunction(a: Int, b: Int): Int = a + b
    
    @Test
    fun testCompleteExecutionPipeline()
    {
        runBlocking {
            // Setup
            FunctionRegistry.clear()
            FunctionRegistry.registerFunction("add", ::testFunction)
            
            // Simulate LLM response with PCP request
            val llmResponse = """
            {
                "tPipeContextOptions": {
                    "functionName": "add"
                },
                "argumentsOrFunctionParams": ["5", "3"]
            }
            """.trimIndent()
            
            // Test complete pipeline
            val parser = PcpResponseParser()
            val dispatcher = PcpExecutionDispatcher()
            
            // 1. Parse LLM response
            val parseResult = parser.extractPcpRequests(llmResponse)
            assertTrue(parseResult.success, "Should parse successfully")
            assertEquals(1, parseResult.requests.size, "Should find one request")
            
            // 2. Execute through dispatcher
            val executionResult = dispatcher.executeRequests(parseResult.requests)
            assertTrue(executionResult.success, "Should execute successfully")
            assertEquals(1, executionResult.results.size, "Should have one result")
            
            val result = executionResult.results.first()
            assertTrue(result.success, "Function should execute successfully")
            assertEquals("8", result.output, "Should return sum as string")
            assertEquals(Transport.Tpipe, result.transport, "Should use Tpipe transport")
            
            // Cleanup
            FunctionRegistry.clear()
        }
    }
}
