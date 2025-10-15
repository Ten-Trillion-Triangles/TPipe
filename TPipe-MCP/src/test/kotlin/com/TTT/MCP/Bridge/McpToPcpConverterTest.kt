package com.TTT.MCP.Bridge

import com.TTT.MCP.Models.McpRequest
import com.TTT.MCP.Models.McpTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpToPcpConverterTest 
{
    private val converter = McpToPcpConverter()

    @Test
    fun testConvertEmptyRequest() 
    {
        // Test converting empty MCP request
        val mcpRequest = McpRequest()
        val result = converter.convert(mcpRequest)
        // Verify resulting PCP context is empty
        assertTrue(result.tpipeOptions.isEmpty())
        assertTrue(result.stdioOptions.isEmpty())
    }

    @Test
    fun testConvertToolsToTPipeOptions() 
    {
        // Create test input schema for MCP tool
        val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("param1", buildJsonObject {
                    put("type", "string")
                })
            })
        }
        
        // Create MCP request with a single tool
        val tool = McpTool("test_function", "Test description", inputSchema)
        val mcpRequest = McpRequest(tools = listOf(tool))
        
        // Convert and verify TPipe option was created correctly
        val result = converter.convert(mcpRequest)
        assertEquals(1, result.tpipeOptions.size)
        assertEquals("test_function", result.tpipeOptions[0].functionName)
        assertEquals("Test description", result.tpipeOptions[0].description)
    }
}