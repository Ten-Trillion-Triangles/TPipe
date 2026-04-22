package com.TTT.MCP.Models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class McpJsonRpcEdgeCasesTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun testJsonRpcRequestMissingJsonrpcFieldThrows() {
        val jsonStr = """
            {"id": 1, "method": "test.method"}
        """.trimIndent()

        val exception = assertFailsWith<IllegalArgumentException> {
            JsonRpcRequest.fromJson(jsonStr)
        }
        assertTrue(exception.message?.contains("jsonrpc") == true)
    }

    @Test
    fun testJsonRpcRequestMissingMethodFieldThrows() {
        val jsonStr = """
            {"jsonrpc": "2.0", "id": 1}
        """.trimIndent()

        val exception = assertFailsWith<IllegalArgumentException> {
            JsonRpcRequest.fromJson(jsonStr)
        }
        assertTrue(exception.message?.contains("method") == true)
    }

    @Test
    fun testJsonRpcRequestStringIdZeroIsNotNotification() {
        val jsonStr = """
            {"jsonrpc": "2.0", "id": "0", "method": "test"}
        """.trimIndent()

        val request = JsonRpcRequest.fromJson(jsonStr)

        assertFalse(request.isNotification)
    }

    @Test
    fun testJsonRpcRequestStringIdNullIsNotification() {
        val jsonStr = """
            {"jsonrpc": "2.0", "id": null, "method": "test"}
        """.trimIndent()

        val request = JsonRpcRequest.fromJson(jsonStr)

        assertTrue(request.isNotification)
    }

    @Test
    fun testJsonRpcBatchSingleNotificationIsNotificationBatch() {
        val jsonStr = """
            [{"jsonrpc": "2.0", "method": "notify"}]
        """.trimIndent()

        val batch = JsonRpcBatchRequest.fromJson(jsonStr)

        assertTrue(batch.isNotificationBatch)
        assertEquals(1, batch.requests.size)
    }

    @Test
    fun testJsonRpcServerErrorMinus32100Throws() {
        assertFailsWith<IllegalArgumentException> {
            JsonRpcError.serverError(-32100, "Invalid server error code")
        }
    }

    @Test
    fun testJsonRpcBatchResponseSerializationFormat() {
        val responses = listOf(
            JsonRpcResponse.success(id = JsonPrimitive(1)),
            JsonRpcResponse.success(id = JsonPrimitive(2))
        )

        val batchResponse = JsonRpcBatchResponse.from(responses)
        val encoded = json.encodeToString(batchResponse)

        assertTrue(encoded.isNotEmpty())
        assertTrue(encoded.contains("responses") || encoded.contains("["))
    }
}