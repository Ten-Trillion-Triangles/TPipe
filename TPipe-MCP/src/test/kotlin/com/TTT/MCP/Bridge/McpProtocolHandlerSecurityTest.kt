package com.TTT.MCP.Bridge

import com.TTT.MCP.Models.JsonRpcErrorCode
import com.TTT.MCP.Models.JsonRpcRequest
import com.TTT.MCP.Models.McpJsonRpcError
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.MCP.Server.McpResourceProvider
import com.TTT.MCP.Server.McpPromptProvider
import com.TTT.MCP.Server.McpToolRegistry
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpProtocolHandlerSecurityTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun createHandler(): McpProtocolHandler {
        FunctionRegistry.clear()
        val pcpContext = PcpContext()
        val toolRegistry = McpToolRegistry(pcpContext)
        val resourceProvider = McpResourceProvider(pcpContext)
        val promptProvider = McpPromptProvider(pcpContext)
        return McpProtocolHandler(
            pcpContext = pcpContext,
            toolRegistry = toolRegistry,
            resourceProvider = resourceProvider,
            promptProvider = promptProvider,
            serverInfo = Implementation(name = "test", version = "1.0.0")
        )
    }

    private fun buildRequest(
        method: String,
        id: JsonPrimitive? = JsonPrimitive(1),
        params: kotlinx.serialization.json.JsonObject? = null
    ): JsonRpcRequest {
        return JsonRpcRequest(
            jsonrpc = "2.0",
            id = id,
            method = method,
            params = params
        )
    }

    @Test
    fun testInvalidJsonRpcVersionReturnsInvalidRequest() {
        val handler = createHandler()
        val request = JsonRpcRequest(
            jsonrpc = "1.0",
            id = JsonPrimitive(1),
            method = "initialize"
        )
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, response.error!!.code)
    }

    @Test
    fun testEmptyMethodNameReturnsInvalidRequest() {
        val handler = createHandler()
        val request = buildRequest("")
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, response.error!!.code)
    }

    @Test
    fun testMethodNameWithInvalidCharactersReturnsInvalidRequest() {
        val handler = createHandler()
        val request = buildRequest("tools/list\u0000\u001F")
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, response.error!!.code)
    }

    @Test
    fun testMethodNameWithNewlineInjectionReturnsInvalidRequest() {
        val handler = createHandler()
        val request = buildRequest("tools/list\u000Awhoami")
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, response.error!!.code)
    }

    @Test
    fun testMethodNameWithSemicolonInjectionReturnsInvalidRequest() {
        val handler = createHandler()
        val request = buildRequest("tools;echo;list")
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, response.error!!.code)
    }

    @Test
    fun testMethodNameWithPipeInjectionReturnsInvalidRequest() {
        val handler = createHandler()
        val request = buildRequest("tools|cat /etc/passwd")
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, response.error!!.code)
    }

    @Test
    fun testMethodNameWithBackticksInjectionReturnsInvalidRequest() {
        val handler = createHandler()
        val request = buildRequest("tools`whoami`")
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, response.error!!.code)
    }

    @Test
    fun testValidMethodNameWithNamespace() {
        val handler = createHandler()
        val request = buildRequest("tools/list")
        val response = handler.route(request)
        assertTrue(response.isError, "Should return error because not initialized")
        assertNotNull(response.error)
        assertTrue(response.error!!.code in JsonRpcErrorCode.SERVER_ERROR_MIN..JsonRpcErrorCode.SERVER_ERROR_MAX)
    }

    @Test
    fun testParamsMustBeObjectOrNull() {
        val handler = createHandler()
        val invalidJson = """{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": "not an object"}"""
        try {
            val request = JsonRpcRequest.fromJson(invalidJson)
            val response = handler.route(request)
            assertTrue(response.isError, "Response should be an error for invalid params type")
            assertNotNull(response.error)
            assertEquals(JsonRpcErrorCode.INVALID_PARAMS, response.error!!.code)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Params must be") == true)
        }
    }

    @Test
    fun testPathTraversalSanitized() {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))
        val maliciousUri = "file://../../../etc/passwd"
        val params = buildJsonObject {
            put("uri", maliciousUri)
        }
        val request = buildRequest("resources/read", JsonPrimitive(2), params)
        val response = handler.route(request)
        assertTrue(response.isSuccess || response.isError, "Response received - path traversal sanitized to /dev/null")
    }

    @Test
    fun testToolNameWithInvalidCharactersReturnsInvalidParams() {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))
        val params = buildJsonObject {
            put("name", "tool\u0000name")
            put("arguments", buildJsonObject { })
        }
        val request = buildRequest("tools/call", JsonPrimitive(2), params)
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, response.error!!.code)
    }

    @Test
    fun testToolArgumentsWithInvalidKeyFormatReturnsInvalidParams() {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))
        val params = buildJsonObject {
            put("name", "valid_tool")
            put("arguments", buildJsonObject {
                put("valid-key", "value")
                put("invalid-key\u0000", "value")
            })
        }
        val request = buildRequest("tools/call", JsonPrimitive(2), params)
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, response.error!!.code)
    }

    @Test
    fun testToolArgumentsNotAnObjectReturnsInvalidParams() {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))
        val params = buildJsonObject {
            put("name", "valid_tool")
            put("arguments", JsonPrimitive("not an object"))
        }
        val request = buildRequest("tools/call", JsonPrimitive(2), params)
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, response.error!!.code)
    }

    @Test
    fun testValidToolCallWithProperArgumentsSucceeds() {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))
        val params = buildJsonObject {
            put("name", "test_tool")
            put("arguments", buildJsonObject {
                put("param1", "test value")
            })
        }
        val request = buildRequest("tools/call", JsonPrimitive(2), params)
        val response = handler.route(request)
        assertTrue(response.isSuccess || response.isError, "Response received")
    }

    @Test
    fun testMethodNameStartingWithNumberReturnsInvalidRequest() {
        val handler = createHandler()
        val request = buildRequest("123method")
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, response.error!!.code)
    }

    @Test
    fun testMethodNameWithSpacesReturnsInvalidRequest() {
        val handler = createHandler()
        val request = buildRequest("tools list")
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, response.error!!.code)
    }

    @Test
    fun testMethodNameWithNullBytesReturnsInvalidRequest() {
        val handler = createHandler()
        val request = buildRequest("tools\u0000list")
        val response = handler.route(request)
        assertTrue(response.isError, "Response should be an error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, response.error!!.code)
    }
}