package com.TTT.MCP.Bridge

import com.TTT.MCP.Models.*
import com.TTT.MCP.Server.McpBridgeServerHost
import com.TTT.PipeContextProtocol.FunctionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class McpBridgeIntegrationTest
{
    private val testMcpJson = """
        {
            "tools": [
                {
                    "name": "test_tool",
                    "description": "A test tool",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "param1": {"type": "string"},
                            "param2": {"type": "integer"}
                        },
                        "required": ["param1"]
                    }
                },
                {
                    "name": "echo_tool",
                    "description": "Echoes input back",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "message": {"type": "string"}
                        },
                        "required": ["message"]
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

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun createServer(): McpBridgeServerHost {
        FunctionRegistry.clear()
        return McpBridgeServerHost(testMcpJson)
    }

    private fun buildRequest(method: String, id: JsonElement = JsonPrimitive(1), params: JsonElement? = null): JsonRpcRequest {
        return JsonRpcRequest(
            jsonrpc = "2.0",
            id = id,
            method = method,
            params = params as? kotlinx.serialization.json.JsonObject
        )
    }

    @Test
    fun testInitializeThenToolsList()
    {
        val server = createServer()

        val initRequest = buildRequest("initialize", JsonPrimitive(1))
        val initResponse = server.handleRequest(initRequest)

        assertFalse(initResponse.isError, "Initialize should succeed")
        assertNotNull(initResponse.result)
        assertEquals("2024-11-05", initResponse.result!!.jsonObject["protocolVersion"]?.jsonPrimitive?.content)

        val toolsRequest = buildRequest("tools/list", JsonPrimitive(2))
        val toolsResponse = server.handleRequest(toolsRequest)

        assertFalse(toolsResponse.isError, "Tools list should succeed")
        assertNotNull(toolsResponse.result)
        val toolsValue = toolsResponse.result!!.jsonObject["tools"]
        assertNotNull(toolsValue, "Tools value should be present")
    }

    @Test
    fun testToolsCallRoundTrip()
    {
        val server = createServer()

        server.handleRequest(buildRequest("initialize", JsonPrimitive(1)))

        val callParams = buildJsonObject {
            put("name", "echo_tool")
            put("arguments", buildJsonObject {
                put("message", "Hello World")
            })
        }
        val callRequest = buildRequest("tools/call", JsonPrimitive(2), callParams)
        val callResponse = server.handleRequest(callRequest)

        assertFalse(callResponse.isError, "Tools call should succeed")
        assertNotNull(callResponse.result)
    }

    @Test
    fun testResourcesListAndRead()
    {
        val server = createServer()

        server.handleRequest(buildRequest("initialize", JsonPrimitive(1)))

        val listRequest = buildRequest("resources/list", JsonPrimitive(2))
        val listResponse = server.handleRequest(listRequest)

        assertFalse(listResponse.isError, "Resources list should succeed")
        assertNotNull(listResponse.result)
        val resourcesValue = listResponse.result!!.jsonObject["resources"]
        assertNotNull(resourcesValue, "Resources value should be present")

        val readParams = buildJsonObject {
            put("uri", "file://test.txt")
        }
        val readRequest = buildRequest("resources/read", JsonPrimitive(3), readParams)
        val readResponse = server.handleRequest(readRequest)

        assertFalse(readResponse.isError, "Resources read should succeed")
        assertNotNull(readResponse.result)
    }

    @Test
    fun testPromptsList()
    {
        val server = createServer()

        server.handleRequest(buildRequest("initialize", JsonPrimitive(1)))

        val listRequest = buildRequest("prompts/list", JsonPrimitive(2))
        val listResponse = server.handleRequest(listRequest)

        assertFalse(listResponse.isError, "Prompts list should succeed")
        assertNotNull(listResponse.result)
        val promptsArray = listResponse.result!!.jsonObject["prompts"]?.jsonArray
        assertNotNull(promptsArray)
        assertTrue(promptsArray.size >= 1)
    }

    @Test
    fun testShutdown()
    {
        val server = createServer()

        server.handleRequest(buildRequest("initialize", JsonPrimitive(1)))

        val shutdownRequest = buildRequest("shutdown", JsonPrimitive(2))
        val shutdownResponse = server.handleRequest(shutdownRequest)

        assertFalse(shutdownResponse.isError, "Shutdown should succeed")
        assertNotNull(shutdownResponse.result)
    }

    @Test
    fun testInvalidJsonError()
    {
        val server = createServer()

        val invalidJson = """{"jsonrpc": "2.0", "method": "initialize", "params":}"""

        try
        {
            server.handleRequest(JsonRpcRequest.fromJson(invalidJson))
            assertTrue(false, "Should have thrown exception for invalid JSON")
        }
        catch(e: Exception)
        {
            assertTrue(e is IllegalArgumentException || e.javaClass.name.contains("Json"))
        }
    }

    @Test
    fun testUnknownMethodError()
    {
        val server = createServer()

        server.handleRequest(buildRequest("initialize", JsonPrimitive(1)))

        val unknownRequest = buildRequest("unknown/method", JsonPrimitive(2))
        val response = server.handleRequest(unknownRequest)

        assertTrue(response.isError, "Should return error for unknown method")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND, response.error!!.code)
    }

    @Test
    fun testPreInitializationReject()
    {
        val server = createServer()

        val toolsRequest = buildRequest("tools/list", JsonPrimitive(1))
        val response = server.handleRequest(toolsRequest)

        assertTrue(response.isError, "Should reject tool calls before initialization")
        assertNotNull(response.error)
        assertTrue(response.error!!.code in JsonRpcErrorCode.SERVER_ERROR_MIN..JsonRpcErrorCode.SERVER_ERROR_MAX)
    }
}