package com.TTT.MCP.Bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpJsonParserTest 
{
    private val parser = McpJsonParser()

    @Test
    fun testParseBasicMcpJson() 
    {
        // Create test MCP JSON with a single tool
        val mcpJson = """
            {
                "tools": [
                    {
                        "name": "test_tool",
                        "description": "Test tool",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "param1": {"type": "string"}
                            }
                        }
                    }
                ]
            }
        """.trimIndent()

        // Parse the JSON and verify the tool was extracted correctly
        val result = parser.parseJson(mcpJson)
        assertEquals(1, result.tools.size)
        assertEquals("test_tool", result.tools[0].name)
        assertEquals("Test tool", result.tools[0].description)
    }

    @Test
    fun testParseEmptyJson() 
    {
        // Test parsing empty JSON object
        val mcpJson = "{}"
        val result = parser.parseJson(mcpJson)
        // Verify all collections are empty
        assertTrue(result.tools.isEmpty())
        assertTrue(result.resources.isEmpty())
        //assertTrue(result.prompts.isEmpty())
    }
}