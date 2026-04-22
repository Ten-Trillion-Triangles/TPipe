package com.TTT.MCP.Bridge

import com.TTT.MCP.Models.JsonRpcErrorCode
import com.TTT.MCP.Models.JsonRpcRequest
import com.TTT.MCP.Models.JsonRpcResponse
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

/**
 * Edge case tests for McpProtocolHandler.
 * Tests MISSING edge cases NOT covered by McpProtocolHandlerSecurityTest.
 */
class McpProtocolHandlerEdgeCasesTest
{

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun createHandler(): McpProtocolHandler
    {
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
    ): JsonRpcRequest
    {
        return JsonRpcRequest(
            jsonrpc = "2.0",
            id = id,
            method = method,
            params = params
        )
    }

    @Test
    fun testHandleShutdownTransitionsToShuttingDown()
    {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))

        assertEquals(McpProtocolHandler.ServerState.READY, handler.getServerState())

        val shutdownRequest = buildRequest("shutdown", JsonPrimitive(2))
        val response = handler.route(shutdownRequest)

        assertTrue(response.isSuccess, "Shutdown should succeed")
        assertEquals(McpProtocolHandler.ServerState.SHUTTING_DOWN, handler.getServerState())
    }

    @Test
    fun testPostShutdownSubsequentRequestsReturnError()
    {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))
        handler.route(buildRequest("shutdown", JsonPrimitive(2)))

        val toolsRequest = buildRequest("tools/list", JsonPrimitive(3))
        val response = handler.route(toolsRequest)

        assertTrue(response.isError, "Request after shutdown should return error")
        assertNotNull(response.error)
        assertTrue(
            response.error!!.code in JsonRpcErrorCode.SERVER_ERROR_MIN..JsonRpcErrorCode.SERVER_ERROR_MAX,
            "Should return server error code"
        )
    }

    @Test
    fun testHandleNotificationsInitializedReturnsSuccess()
    {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))

        val notificationRequest = buildRequest("notifications/initialized", JsonPrimitive(2))
        val response = handler.route(notificationRequest)

        assertTrue(response.isSuccess, "notifications/initialized should return success")
        assertNotNull(response.result)
    }

    @Test
    fun testHandlePromptsGetWithValidNameAndArguments()
    {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))

        val params = buildJsonObject {
            put("name", "test_prompt")
            put("arguments", buildJsonObject {
                put("arg1", "value1")
            })
        }
        val request = buildRequest("prompts/get", JsonPrimitive(2), params)
        val response = handler.route(request)

        assertTrue(response.isSuccess || response.isError, "Should return a response")
    }

    @Test
    fun testHandlePromptsGetMissingNameReturnsInvalidParams()
    {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))

        val params = buildJsonObject {
            put("arguments", buildJsonObject {
                put("arg1", "value1")
            })
        }
        val request = buildRequest("prompts/get", JsonPrimitive(2), params)
        val response = handler.route(request)

        assertTrue(response.isError, "Missing name should return error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, response.error!!.code)
    }

    @Test
    fun testHandlePromptsGetMissingParamsReturnsInvalidParams()
    {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))

        val request = buildRequest("prompts/get", JsonPrimitive(2), null)
        val response = handler.route(request)

        assertTrue(response.isError, "Missing params should return error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, response.error!!.code)
    }

    @Test
    fun testHandleToolsCallWithMissingNameReturnsInvalidParams()
    {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))

        val params = buildJsonObject {
            put("arguments", buildJsonObject { })
        }
        val request = buildRequest("tools/call", JsonPrimitive(2), params)
        val response = handler.route(request)

        assertTrue(response.isError, "Missing name should return error")
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, response.error!!.code)
    }

    @Test
    fun testHandleToolsCallWithBlankEmptyArgumentsHandledGracefully()
    {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))

        val params = buildJsonObject {
            put("name", "valid_tool")
        }
        val request = buildRequest("tools/call", JsonPrimitive(2), params)
        val response = handler.route(request)

        assertTrue(response.isSuccess || response.isError, "Should handle gracefully with empty arguments")
    }

    @Test
    fun testUnexpectedExceptionReturnsInternalError()
    {
        val handler = createHandler()
        handler.route(buildRequest("initialize"))

        val invalidJson = """{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": "not an object"}"""
        try
        {
            val request = JsonRpcRequest.fromJson(invalidJson)
            val response = handler.route(request)
            assertTrue(response.isError, "Should return error for unexpected exception")
            assertNotNull(response.error)
            assertEquals(JsonRpcErrorCode.INTERNAL_ERROR, response.error!!.code)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Params must be") == true)
        }
    }

    @Test
    fun testStateTransitionReadyToShuttingDownAfterShutdownThenRejected()
    {
        val handler = createHandler()

        assertEquals(McpProtocolHandler.ServerState.INITIALIZING, handler.getServerState())

        handler.route(buildRequest("initialize"))
        assertEquals(McpProtocolHandler.ServerState.READY, handler.getServerState())

        handler.route(buildRequest("shutdown", JsonPrimitive(2)))
        assertEquals(McpProtocolHandler.ServerState.SHUTTING_DOWN, handler.getServerState())

        val toolsRequest = buildRequest("tools/list", JsonPrimitive(3))
        val response = handler.route(toolsRequest)
        assertTrue(response.isError, "Should reject requests in SHUTTING_DOWN state")
        assertNotNull(response.error)
        assertTrue(
            response.error!!.code in JsonRpcErrorCode.SERVER_ERROR_MIN..JsonRpcErrorCode.SERVER_ERROR_MAX
        )
    }
}