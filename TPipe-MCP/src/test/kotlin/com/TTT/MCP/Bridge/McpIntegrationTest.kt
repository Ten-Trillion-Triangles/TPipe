package com.TTT.MCP.Bridge

import com.TTT.MCP.Models.*
import com.TTT.PipeContextProtocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class McpIntegrationTest
{
    private val parser = McpJsonParser()
    private val mcpToPcp = McpToPcpConverter()
    private val pcpToMcp = PcpToMcpConverter()

    @Test
    fun testFullRoundTrip()
    {
        val originalJson = """
            {
                "tools": [
                    {
                        "name": "test_tool",
                        "description": "A test tool",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "param1": {"type": "string"}
                            },
                            "required": ["param1"]
                        },
                        "annotations": {
                            "priority": 0.8
                        }
                    }
                ],
                "prompts": [
                    {
                        "name": "test_prompt",
                        "description": "A test prompt",
                        "arguments": [
                            {"name": "arg1", "description": "Arg 1", "required": true}
                        ]
                    }
                ],
                "resources": [
                    {
                        "uri": "file://test.txt",
                        "name": "test_resource",
                        "description": "A test resource"
                    }
                ],
                "resourceTemplates": [
                    {
                        "uriTemplate": "file://{path}",
                        "name": "test_template",
                        "description": "A test template"
                    }
                ]
            }
        """.trimIndent()

        // 1. Parse MCP JSON
        val mcpRequest = parser.parseJson(originalJson)
        assertEquals(1, mcpRequest.tools.size)
        assertEquals(1, mcpRequest.prompts.size)
        assertEquals(1, mcpRequest.resources.size)
        assertEquals(1, mcpRequest.resourceTemplates.size)

        // 2. Convert MCP -> PCP
        val pcpContext = mcpToPcp.convert(mcpRequest)

        // Verify tools -> TPipeOptions
        val toolOption = pcpContext.tpipeOptions.find { it.functionName == "test_tool" }
        assertTrue(toolOption != null)
        assertTrue(toolOption.description.contains("Priority: 0.8"))

        // Verify prompts -> TPipeOptions
        val promptOption = pcpContext.tpipeOptions.find { it.functionName == "prompt_test_prompt" }
        assertTrue(promptOption != null)
        assertTrue(promptOption.params.containsKey("arg1"))

        // Verify resources -> StdioOptions
        val resourceOption = pcpContext.stdioOptions.find { it.args.contains("file://test.txt") }
        assertTrue(resourceOption != null)

        // 3. Convert PCP -> MCP
        val roundTripMcp = pcpToMcp.convert(pcpContext)
        assertEquals(1, roundTripMcp.tools.size)
        assertEquals("test_tool", roundTripMcp.tools[0].name)
        assertEquals(0.8, roundTripMcp.tools[0].annotations?.priority)
        assertEquals(1, roundTripMcp.prompts.size)
        assertEquals("test_prompt", roundTripMcp.prompts[0].name)
        assertEquals(1, roundTripMcp.resources.size)
        assertEquals(1, roundTripMcp.resourceTemplates.size)
        assertEquals("test_template", roundTripMcp.resourceTemplates[0].name)
    }
}
