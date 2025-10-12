package com.TTT.MCP.Bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

/**
 * Tests for MCP schema compliance fixes including validation, round-trip preservation,
 * and proper handling of required fields, enums, and array items.
 */
class McpSchemaComplianceTest 
{
    private val parser = McpJsonParser()
    private val builder = McpJsonBuilder()

    @Test
    fun testToolInputSchemaValidation() 
    {
        // Test 1: Tool missing inputSchema should fail
        val missingSchemaJson = """
            {
                "tools": [
                    {
                        "name": "invalid_tool",
                        "description": "Tool without inputSchema"
                    }
                ]
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            parser.parseJson(missingSchemaJson)
        }

        // Test 2: Tool with inputSchema type != "object" should fail
        val wrongTypeJson = """
            {
                "tools": [
                    {
                        "name": "invalid_tool",
                        "description": "Tool with wrong schema type",
                        "inputSchema": {
                            "type": "string"
                        }
                    }
                ]
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            parser.parseJson(wrongTypeJson)
        }
    }

    @Test
    fun testResourceValidation() 
    {
        // Test 1: Resource missing uri should fail
        val missingUriJson = """
            {
                "resources": [
                    {
                        "name": "invalid_resource",
                        "description": "Resource without uri"
                    }
                ]
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            parser.parseJson(missingUriJson)
        }

        // Test 2: Resource missing name should fail
        val missingNameJson = """
            {
                "resources": [
                    {
                        "uri": "file://test.txt",
                        "description": "Resource without name"
                    }
                ]
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            parser.parseJson(missingNameJson)
        }
    }

    @Test
    fun testRoundTripPreservation() 
    {
        // Test round-trip with required fields, enums, and array items
        val originalJson = """
            {
                "tools": [
                    {
                        "name": "test_tool",
                        "description": "Test tool with complex schema",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "required_param": {
                                    "type": "string",
                                    "description": "A required parameter"
                                },
                                "enum_param": {
                                    "type": "string",
                                    "enum": ["option1", "option2", "option3"]
                                },
                                "array_param": {
                                    "type": "array",
                                    "items": {
                                        "type": "string"
                                    }
                                }
                            },
                            "required": ["required_param"]
                        }
                    }
                ]
            }
        """.trimIndent()

        // Parse and convert to PCP, then back to MCP
        val mcpRequest = parser.parseJson(originalJson)
        val converter = McpToPcpConverter()
        val pcpContext = converter.convert(mcpRequest)
        
        val reverseConverter = PcpToMcpConverter()
        val roundTripRequest = reverseConverter.convert(pcpContext)
        val roundTripJson = builder.buildMcpJson(roundTripRequest)

        // Parse the round-trip result to verify structure
        val roundTripParsed = Json.parseToJsonElement(roundTripJson).jsonObject
        val tools = roundTripParsed["tools"]?.jsonArray
        assertTrue(tools != null && tools.size == 1)

        val tool = tools[0].jsonObject
        assertEquals("test_tool", tool["name"]?.jsonPrimitive?.content)
        
        val inputSchema = tool["inputSchema"]?.jsonObject
        assertTrue(inputSchema != null)
        assertEquals("object", inputSchema["type"]?.jsonPrimitive?.content)
        
        // Verify required fields are preserved
        val required = inputSchema["required"]?.jsonArray
        assertTrue(required != null && required.size > 0)
    }

    @Test
    fun testValidToolWithAllFeatures() 
    {
        // Test valid tool with outputSchema, required fields, enums, and array items
        val validJson = """
            {
                "tools": [
                    {
                        "name": "complete_tool",
                        "description": "Tool with all MCP features",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "text": {
                                    "type": "string",
                                    "description": "Input text"
                                },
                                "mode": {
                                    "type": "string",
                                    "enum": ["fast", "accurate", "balanced"]
                                },
                                "tags": {
                                    "type": "array",
                                    "items": {
                                        "type": "string"
                                    }
                                }
                            },
                            "required": ["text"]
                        },
                        "outputSchema": {
                            "type": "object",
                            "properties": {
                                "result": {"type": "string"}
                            }
                        }
                    }
                ]
            }
        """.trimIndent()

        val result = parser.parseJson(validJson)
        assertEquals(1, result.tools.size)
        
        val tool = result.tools[0]
        assertEquals("complete_tool", tool.name)
        assertTrue(tool.outputSchema != null)
        assertEquals("object", tool.inputSchema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun testValidResourceWithAllFields() 
    {
        // Test valid resource with all camelCase fields
        val validJson = """
            {
                "resources": [
                    {
                        "uri": "file://config.json",
                        "name": "config",
                        "description": "Configuration file",
                        "mimeType": "application/json"
                    }
                ]
            }
        """.trimIndent()

        val result = parser.parseJson(validJson)
        assertEquals(1, result.resources.size)
        
        val resource = result.resources[0]
        assertEquals("file://config.json", resource.uri)
        assertEquals("config", resource.name)
        assertEquals("Configuration file", resource.description)
        assertEquals("application/json", resource.mimeType)
    }
}