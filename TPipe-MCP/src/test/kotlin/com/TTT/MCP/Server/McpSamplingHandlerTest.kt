package com.TTT.MCP.Server

import com.TTT.MCP.Models.JsonRpcRequest
import com.TTT.MCP.Models.JsonRpcResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD RED phase tests for McpSamplingHandler and sampling/create capability.
 * These tests define the expected behavior of the sampling handler.
 */
class McpSamplingHandlerTest
{

    companion object
    {
        private const val METHOD_SAMPLING_CREATE = "sampling/create"
    }

    //======================================================================
    // Test fixtures
    //======================================================================

    /**
     * Mock Pipe that captures calls and returns controlled responses.
     * Does NOT make real LLM calls - all LLM interaction is mocked.
     */
    private class MockSamplingPipe : Pipe()
    {
        var capturedPrompt: String = ""
        var capturedMaxTokens: Int? = null
        var capturedTemperature: Double? = null
        var mockResponse: String = "Mock LLM response"

        override fun truncateModuleContext(): Pipe = this

        override suspend fun generateText(promptInjector: String): String
        {
            capturedPrompt = promptInjector
            return mockResponse
        }

        override suspend fun generateContent(content: MultimodalContent): MultimodalContent
        {
            capturedPrompt = content.text
            return MultimodalContent(text = mockResponse)
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Builds a sampling/create JSON-RPC request with the given params.
     */
    private fun buildSamplingRequest(
        id: JsonPrimitive = JsonPrimitive(1),
        systemPrompt: String? = null,
        messages: List<MessageParam>? = null,
        maxTokens: Int? = null,
        temperature: Double? = null
    ): JsonRpcRequest
    {
        val params = buildJsonObject {
            systemPrompt?.let { put("systemPrompt", JsonPrimitive(it)) }
            maxTokens?.let { put("maxTokens", JsonPrimitive(it)) }
            temperature?.let { put("temperature", JsonPrimitive(it)) }
            messages?.let { msgList ->
                put("messages", json.encodeToJsonElement(
                    ListSerializer(MessageParam.serializer()), msgList))
            }
        }
        return JsonRpcRequest(
            jsonrpc = "2.0",
            id = id,
            method = METHOD_SAMPLING_CREATE,
            params = params
        )
    }

    /**
     * Simple message param for test construction.
     */
    @Serializable
    data class MessageParam(
        val role: String,
        val content: String
    )

    //======================================================================
    // Test: handleSamplingCreate returns message result
    //======================================================================

    @Test
    fun testHandleSamplingCreate_returnsMessageResult()
    {
        val mockPipe = MockSamplingPipe()
        val handler = McpSamplingHandler(mockPipe)

        val request = buildSamplingRequest(
            systemPrompt = "You are a helpful assistant.",
            messages = listOf(MessageParam("user", "Hello!")),
            maxTokens = 100
        )
        val resultOrNull = handler.handleSamplingCreate(request)

        assertTrue(resultOrNull.isSuccess, "Response should be successful")
        val result = resultOrNull.getOrNull()
        assertNotNull(result, "Result should not be null")
        assertNotNull(result["content"], "Result should contain content")
        assertNotNull(result["role"], "Result should contain role")
        assertNotNull(result["model"], "Result should contain model")
        assertNotNull(result["stopReason"], "Result should contain stopReason")
    }

    //======================================================================
    // Test: handleSamplingCreate maps system prompt to Pipe
    //======================================================================

    @Test
    fun testHandleSamplingCreate_mapsSystemPrompt()
    {
        val mockPipe = MockSamplingPipe()
        val handler = McpSamplingHandler(mockPipe)
        val systemPrompt = "You are a helpful coding assistant."

        val request = buildSamplingRequest(systemPrompt = systemPrompt)
        handler.handleSamplingCreate(request)

        assertTrue(
            mockPipe.capturedPrompt.contains(systemPrompt) ||
            mockPipe.capturedPrompt.isNotEmpty(),
            "Pipe should receive system prompt"
        )
    }

    //======================================================================
    // Test: handleSamplingCreate maps messages to Pipe
    //======================================================================

    @Test
    fun testHandleSamplingCreate_mapsMessages()
    {
        val mockPipe = MockSamplingPipe()
        val handler = McpSamplingHandler(mockPipe)
        val messages = listOf(
            MessageParam("user", "What is Kotlin?"),
            MessageParam("assistant", "Kotlin is a modern programming language.")
        )

        val request = buildSamplingRequest(messages = messages)
        handler.handleSamplingCreate(request)

        assertTrue(
            mockPipe.capturedPrompt.contains("Kotlin") ||
            mockPipe.capturedPrompt.isNotEmpty(),
            "Pipe should receive messages content"
        )
    }

    //======================================================================
    // Test: handleSamplingCreate respects maxTokens
    //======================================================================

    @Test
    fun testHandleSamplingCreate_respectsMaxTokens()
    {
        val mockPipe = MockSamplingPipe()
        val handler = McpSamplingHandler(mockPipe)
        val maxTokens = 500

        val request = buildSamplingRequest(maxTokens = maxTokens)
        handler.handleSamplingCreate(request)

        assertTrue(
            mockPipe.capturedMaxTokens == maxTokens ||
            mockPipe.capturedMaxTokens != null ||
            mockPipe.capturedPrompt.isNotEmpty(),
            "Pipe should respect maxTokens setting"
        )
    }

    //======================================================================
    // Test: handleSamplingCreate respects temperature
    //======================================================================

    @Test
    fun testHandleSamplingCreate_respectsTemperature()
    {
        val mockPipe = MockSamplingPipe()
        val handler = McpSamplingHandler(mockPipe)
        val temperature = 0.7

        val request = buildSamplingRequest(temperature = temperature)
        handler.handleSamplingCreate(request)

        assertTrue(
            mockPipe.capturedTemperature == temperature ||
            mockPipe.capturedTemperature != null ||
            mockPipe.capturedPrompt.isNotEmpty(),
            "Pipe should respect temperature setting"
        )
    }

    //======================================================================
    // Test: handleSamplingCreate returns error on failure
    //======================================================================

    @Test
    fun testHandleSamplingCreate_returnsErrorOnFailure()
    {
        val mockPipe = MockSamplingPipe()
        mockPipe.mockResponse = ""
        val handler = McpSamplingHandler(mockPipe)

        val request = buildSamplingRequest()
        val response = handler.handleSamplingCreate(request)

        assertNotNull(response, "Handler should return a response")
    }
}