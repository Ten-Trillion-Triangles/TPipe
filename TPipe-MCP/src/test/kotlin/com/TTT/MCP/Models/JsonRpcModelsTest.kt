package com.TTT.MCP.Models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class JsonRpcModelsTest
{

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun testJsonRpcRequestParsing()
    {
        val jsonStr = """
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "test.method",
                "params": {"key": "value"}
            }
        """.trimIndent()

        val request = JsonRpcRequest.fromJson(jsonStr)

        assertEquals("2.0", request.jsonrpc)
        assertEquals("test.method", request.method)
        assertFalse(request.isNotification)
    }

    @Test
    fun testJsonRpcRequestNotification()
    {
        val jsonStr = """
            {
                "jsonrpc": "2.0",
                "method": "notify",
                "params": {}
            }
        """.trimIndent()

        val request = JsonRpcRequest.fromJson(jsonStr)

        assertEquals("notify", request.method)
        assertTrue(request.isNotification)
    }

    @Test
    fun testJsonRpcResponseSuccess()
    {
        val response = JsonRpcResponse.success(
            id = JsonPrimitive(1),
            result = JsonObject(mapOf("result" to JsonPrimitive("success")))
        )

        assertTrue(response.isSuccess)
        assertFalse(response.isError)
        assertNull(response.error)
        assertEquals("2.0", response.jsonrpc)
    }

    @Test
    fun testJsonRpcResponseError()
    {
        val response = JsonRpcResponse.error(
            id = JsonPrimitive(1),
            error = McpJsonRpcError(
                code = -32600,
                message = "Invalid Request"
            )
        )

        assertTrue(response.isError)
        assertFalse(response.isSuccess)
        assertEquals(-32600, response.error?.code)
    }

    @Test
    fun testJsonRpcErrorFactoryMethods()
    {
        assertEquals(-32700, JsonRpcError.parseError("Parse error").code)
        assertEquals(-32600, JsonRpcError.invalidRequest("Invalid request").code)
        assertEquals(-32601, JsonRpcError.methodNotFound("Method not found").code)
        assertEquals(-32602, JsonRpcError.invalidParams("Invalid params").code)
        assertEquals(-32603, JsonRpcError.internalError("Internal error").code)
    }

    @Test
    fun testJsonRpcServerError()
    {
        val error = JsonRpcError.serverError(-32000, "Server error")
        assertEquals(-32000, error.code)
        assertEquals("Server error", error.message)
    }

    @Test
    fun testJsonRpcServerErrorRange()
    {
        val errorMin = JsonRpcError.serverError(-32099, "Min server error")
        val errorMax = JsonRpcError.serverError(-32000, "Max server error")

        assertEquals(-32099, errorMin.code)
        assertEquals(-32000, errorMax.code)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testJsonRpcServerErrorOutOfRange()
    {
        JsonRpcError.serverError(-32100, "Invalid")
    }

    @Test
    fun testJsonRpcBatchRequestParsing() {
        val jsonStr = """
            [
                {"jsonrpc": "2.0", "id": 1, "method": "method1"},
                {"jsonrpc": "2.0", "id": 2, "method": "method2"},
                {"jsonrpc": "2.0", "method": "notify"}
            ]
        """.trimIndent()

        val batch = JsonRpcBatchRequest.fromJson(jsonStr)

        assertEquals(3, batch.requests.size)
        assertFalse(batch.isNotificationBatch)
    }

    @Test
    fun testJsonRpcBatchNotificationOnly()
    {
        val jsonStr = """
            [
                {"jsonrpc": "2.0", "method": "notify1"},
                {"jsonrpc": "2.0", "method": "notify2"}
            ]
        """.trimIndent()

        val batch = JsonRpcBatchRequest.fromJson(jsonStr)

        assertTrue(batch.isNotificationBatch)
    }

    @Test
    fun testJsonRpcBatchResponse()
    {
        val responses = listOf(
            JsonRpcResponse.success(id = JsonPrimitive(1)),
            JsonRpcResponse.error(
                id = JsonPrimitive(2),
                error = McpJsonRpcError(code = -32601, message = "Method not found")
            )
        )

        val batchResponse = JsonRpcBatchResponse.from(responses)

        assertEquals(2, batchResponse.responses.size)
        assertTrue(batchResponse.responses[0].isSuccess)
        assertTrue(batchResponse.responses[1].isError)
    }

    @Test
    fun testJsonRpcRequestWithStringId()
    {
        val jsonStr = """
            {"jsonrpc": "2.0", "id": "abc123", "method": "test"}
        """.trimIndent()

        val request = JsonRpcRequest.fromJson(jsonStr)

        assertFalse(request.isNotification)
    }

    @Test
    fun testJsonRpcRequestWithNullId()
    {
        val jsonStr = """
            {"jsonrpc": "2.0", "id": null, "method": "test"}
        """.trimIndent()

        val request = JsonRpcRequest.fromJson(jsonStr)

        assertTrue(request.isNotification)
    }

    @Test
    fun testJsonRpcErrorWithData()
    {
        val errorData = JsonObject(mapOf("details" to JsonPrimitive("extra info")))
        val error = JsonRpcError.invalidParams("Invalid params", errorData)

        assertEquals(-32602, error.code)
        assertEquals("Invalid params", error.message)
        assertEquals(errorData, error.data)
    }

    @Test
    fun testJsonRpcResponseWithJsonRpcError()
    {
        val response = JsonRpcResponse.error(
            id = JsonPrimitive(1),
            jsonRpcError = JsonRpcError.methodNotFound("Method not found")
        )

        assertTrue(response.isError)
        assertEquals(-32601, response.error?.code)
    }

    @Test
    fun testJsonRpcBatchResponseSerialization()
    {
        val responses = listOf(
            JsonRpcResponse.success(id = JsonPrimitive(1)),
            JsonRpcResponse.success(id = JsonPrimitive(2))
        )

        val batchResponse = JsonRpcBatchResponse.from(responses)
        val encoded = json.encodeToString(batchResponse)
        println("Encoded: $encoded")

        assertTrue(encoded.contains("responses"))
        assertTrue(encoded.contains("1") || encoded.contains("\"1\""))
    }
}