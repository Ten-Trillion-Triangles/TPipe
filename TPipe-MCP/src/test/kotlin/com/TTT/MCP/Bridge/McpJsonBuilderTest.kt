package com.TTT.MCP.Bridge

import com.TTT.MCP.Models.McpRequest
import com.TTT.MCP.Models.McpTool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertTrue

class McpJsonBuilderTest 
{
    private val builder = McpJsonBuilder()

    @Test
    fun testBuildMcpJson() 
    {
        // Create test input schema for the tool
        val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("param1", buildJsonObject {
                    put("type", "string")
                })
            })
        }
        
        // Create test tool and MCP request
        val tool = McpTool("test_tool", "Test description", inputSchema)
        val mcpRequest = McpRequest(tools = listOf(tool))
        
        // Build JSON and verify it contains expected content
        val json = builder.buildMcpJson(mcpRequest)
        assertTrue(json.contains("test_tool"))
        assertTrue(json.contains("Test description"))
    }
}