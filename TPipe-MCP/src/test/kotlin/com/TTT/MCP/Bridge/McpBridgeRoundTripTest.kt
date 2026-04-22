package com.TTT.MCP.Bridge

import com.TTT.MCP.Models.*
import com.TTT.PipeContextProtocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class McpBridgeRoundTripTest
{
    private val parser = McpJsonParser()
    private val mcpToPcp = McpToPcpConverter()
    private val pcpToMcp = PcpToMcpConverter()

    @Test
    fun testRoundTripPreservesAllComponents()
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

        val mcpRequest = parser.parseJson(originalJson)
        val pcpContext = mcpToPcp.convert(mcpRequest)
        val roundTripMcp = pcpToMcp.convert(pcpContext)

        assertEquals(1, roundTripMcp.tools.size)
        assertEquals("test_tool", roundTripMcp.tools[0].name)
        assertEquals(1, roundTripMcp.prompts.size)
        assertEquals("test_prompt", roundTripMcp.prompts[0].name)
        assertEquals(1, roundTripMcp.resources.size)
        assertEquals("test_resource", roundTripMcp.resources[0].name)
        assertEquals(1, roundTripMcp.resourceTemplates.size)
        assertEquals("test_template", roundTripMcp.resourceTemplates[0].name)
    }

    @Test
    fun testRoundTripPreservesAnnotations()
    {
        val originalJson = """
            {
                "tools": [
                    {
                        "name": "priority_tool",
                        "description": "High priority tool",
                        "inputSchema": {
                            "type": "object",
                            "properties": {}
                        },
                        "annotations": {
                            "priority": 0.9,
                            "audience": ["user", "assistant"]
                        }
                    }
                ],
                "prompts": [
                    {
                        "name": "annotated_prompt",
                        "description": "Prompt with annotations",
                        "arguments": [],
                        "annotations": {
                            "priority": 0.5,
                            "audience": ["developer"]
                        }
                    }
                ]
            }
        """.trimIndent()

        val mcpRequest = parser.parseJson(originalJson)
        val pcpContext = mcpToPcp.convert(mcpRequest)
        val roundTripMcp = pcpToMcp.convert(pcpContext)

        assertEquals(1, roundTripMcp.tools.size)
        assertEquals(0.9, roundTripMcp.tools[0].annotations?.priority)
        assertEquals(listOf("user", "assistant"), roundTripMcp.tools[0].annotations?.audience)

        assertEquals(1, roundTripMcp.prompts.size)
        assertEquals(0.5, roundTripMcp.prompts[0].annotations?.priority)
        assertEquals(listOf("developer"), roundTripMcp.prompts[0].annotations?.audience)
    }

    @Test
    fun testRoundTripPreservesParameterTypes()
    {
        val originalJson = """
            {
                "tools": [
                    {
                        "name": "all_types_tool",
                        "description": "Tool with all parameter types",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "stringParam": {"type": "string", "description": "A string"},
                                "integerParam": {"type": "integer", "description": "An integer"},
                                "booleanParam": {"type": "boolean", "description": "A boolean"},
                                "arrayParam": {"type": "array", "description": "An array"},
                                "objectParam": {"type": "object", "description": "An object"},
                                "numberParam": {"type": "number", "description": "A number"}
                            },
                            "required": ["stringParam", "integerParam", "booleanParam", "arrayParam", "objectParam", "numberParam"]
                        }
                    }
                ]
            }
        """.trimIndent()

        val mcpRequest = parser.parseJson(originalJson)
        val pcpContext = mcpToPcp.convert(mcpRequest)
        val roundTripMcp = pcpToMcp.convert(pcpContext)

        val tool = roundTripMcp.tools[0]
        val schema = tool.inputSchema

        val stringType = schema["properties"]?.jsonObject?.get("stringParam")?.jsonObject?.get("type")?.jsonPrimitive?.content
        assertEquals("string", stringType)

        val integerType = schema["properties"]?.jsonObject?.get("integerParam")?.jsonObject?.get("type")?.jsonPrimitive?.content
        assertEquals("integer", integerType)

        val booleanType = schema["properties"]?.jsonObject?.get("booleanParam")?.jsonObject?.get("type")?.jsonPrimitive?.content
        assertEquals("boolean", booleanType)

        val arrayType = schema["properties"]?.jsonObject?.get("arrayParam")?.jsonObject?.get("type")?.jsonPrimitive?.content
        assertEquals("array", arrayType)

        val objectType = schema["properties"]?.jsonObject?.get("objectParam")?.jsonObject?.get("type")?.jsonPrimitive?.content
        assertEquals("object", objectType)

        val numberType = schema["properties"]?.jsonObject?.get("numberParam")?.jsonObject?.get("type")?.jsonPrimitive?.content
        assertEquals("number", numberType)
    }

    @Test
    fun testRoundTripPreservesRequiredFlags()
    {
        val originalJson = """
            {
                "tools": [
                    {
                        "name": "required_test_tool",
                        "description": "Test required flags",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "requiredParam": {"type": "string"},
                                "optionalParam": {"type": "string"}
                            },
                            "required": ["requiredParam"]
                        }
                    }
                ],
                "prompts": [
                    {
                        "name": "required_args_prompt",
                        "description": "Prompt with required args",
                        "arguments": [
                            {"name": "requiredArg", "required": true},
                            {"name": "optionalArg", "required": false}
                        ]
                    }
                ]
            }
        """.trimIndent()

        val mcpRequest = parser.parseJson(originalJson)
        val pcpContext = mcpToPcp.convert(mcpRequest)
        val roundTripMcp = pcpToMcp.convert(pcpContext)

        val toolSchema = roundTripMcp.tools[0].inputSchema
        val requiredList = toolSchema["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
        assertTrue(requiredList.contains("requiredParam"))
        assertFalse(requiredList.contains("optionalParam"))

        val prompt = roundTripMcp.prompts[0]
        val requiredArg = prompt.arguments?.find { it.name == "requiredArg" }
        val optionalArg = prompt.arguments?.find { it.name == "optionalArg" }
        assertTrue(requiredArg?.required == true)
        assertFalse(optionalArg?.required == true)
    }

    @Test
    fun testEdgeCaseEmptyLists()
    {
        val originalJson = """
            {
                "tools": [],
                "resources": [],
                "resourceTemplates": [],
                "prompts": []
            }
        """.trimIndent()

        val mcpRequest = parser.parseJson(originalJson)
        val pcpContext = mcpToPcp.convert(mcpRequest)
        val roundTripMcp = pcpToMcp.convert(pcpContext)

        assertEquals(0, roundTripMcp.tools.size)
        assertEquals(0, roundTripMcp.resources.size)
        assertEquals(0, roundTripMcp.resourceTemplates.size)
        assertEquals(0, roundTripMcp.prompts.size)
    }

    @Test
    fun testEdgeCaseMissingOptionalFields()
    {
        val originalJson = """
            {
                "tools": [
                    {
                        "name": "minimal_tool",
                        "inputSchema": {
                            "type": "object",
                            "properties": {}
                        }
                    }
                ],
                "resources": [
                    {
                        "uri": "file://test.txt",
                        "name": "minimal_resource"
                    }
                ],
                "prompts": [
                    {
                        "name": "minimal_prompt"
                    }
                ]
            }
        """.trimIndent()

        val mcpRequest = parser.parseJson(originalJson)
        val pcpContext = mcpToPcp.convert(mcpRequest)
        val roundTripMcp = pcpToMcp.convert(pcpContext)

        assertEquals(1, roundTripMcp.tools.size)
        assertEquals("minimal_tool", roundTripMcp.tools[0].name)
        assertNull(roundTripMcp.tools[0].description)
        assertNull(roundTripMcp.tools[0].annotations)

        assertEquals(1, roundTripMcp.resources.size)
        assertEquals("file://test.txt", roundTripMcp.resources[0].uri)
        assertNull(roundTripMcp.resources[0].description)

        assertEquals(1, roundTripMcp.prompts.size)
        assertEquals("minimal_prompt", roundTripMcp.prompts[0].name)
        assertNull(roundTripMcp.prompts[0].description)
        assertTrue(roundTripMcp.prompts[0].arguments.isNullOrEmpty())
    }

    @Test
    fun testRoundTripPreservesResourceTemplates()
    {
        val originalJson = """
            {
                "resourceTemplates": [
                    {
                        "uriTemplate": "https://api.example.com/{version}/users/{id}",
                        "name": "user_template",
                        "description": "User API endpoint",
                        "mimeType": "application/json"
                    }
                ]
            }
        """.trimIndent()

        val mcpRequest = parser.parseJson(originalJson)
        val pcpContext = mcpToPcp.convert(mcpRequest)
        val roundTripMcp = pcpToMcp.convert(pcpContext)

        assertEquals(1, roundTripMcp.resourceTemplates.size)
        assertEquals("https://api.example.com/{version}/users/{id}", roundTripMcp.resourceTemplates[0].uriTemplate)
        assertEquals("user_template", roundTripMcp.resourceTemplates[0].name)
        assertEquals("User API endpoint", roundTripMcp.resourceTemplates[0].description)
        assertEquals("application/json", roundTripMcp.resourceTemplates[0].mimeType)
    }
}