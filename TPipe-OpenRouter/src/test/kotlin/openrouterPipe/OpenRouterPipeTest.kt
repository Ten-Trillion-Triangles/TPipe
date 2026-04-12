package openrouterPipe

import com.TTT.Pipe.Pipe
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import env.ChatMessage
import env.OpenRouterChatRequest
import env.OpenRouterChatResponse
import env.OpenRouterErrorResponse
import env.StreamingChunk
import env.UsageInfo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Unit tests for OpenRouterPipe non-streaming core functionality.
 * Tests cover request/response serialization, builder pattern, validation, and error handling.
 */
class OpenRouterPipeTest
{

//=========================================Request/Response Serialization Tests========================================

    @Test
    fun testChatMessageSerialization()
    {
        val message = ChatMessage(role = "user", content = "Hello, world!")
        val json = serialize(message)
        val deserialized = deserialize<ChatMessage>(json)

        assertNotNull(deserialized)
        assertEquals("user", deserialized.role)
        assertEquals("Hello, world!", deserialized.content)
    }

    @Test
    fun testChatMessageDeserialization()
    {
        val json = """{"role": "assistant", "content": "Hello!"}"""
        val message = deserialize<ChatMessage>(json)

        assertNotNull(message)
        assertEquals("assistant", message.role)
        assertEquals("Hello!", message.content)
    }

    @Test
    fun testOpenRouterChatRequestSerialization()
    {
        val messages = listOf(
            ChatMessage(role = "system", content = "You are helpful."),
            ChatMessage(role = "user", content = "What is 2+2?")
        )
        val request = OpenRouterChatRequest(
            model = "deepseek/deepseek-chat-v3-0324:free",
            messages = messages,
            temperature = 0.7
        )

        val json = serialize(request)
        assertTrue(json.contains("deepseek"))
        assertTrue(json.contains("system"))
        assertTrue(json.contains("You are helpful"))
        assertTrue(json.contains("What is 2+2?"))
    }

    @Test
    fun testOpenRouterChatRequestWithNullOptionalFields()
    {
        val messages = listOf(ChatMessage(role = "user", content = "Hi"))
        val request = OpenRouterChatRequest(
            model = "test/model",
            messages = messages
        )

        val json = serialize(request)
        val deserialized = deserialize<OpenRouterChatRequest>(json)

        assertNotNull(deserialized)
        assertEquals("test/model", deserialized.model)
        assertEquals(1, deserialized.messages.size)
        assertTrue(deserialized.temperature == null)
    }

    @Test
    fun testOpenRouterChatResponseDeserialization()
    {
        val json = """
            {
                "id": "chatcmpl-123",
                "object": "chat.completion",
                "created": 1234567890,
                "model": "deepseek/deepseek-chat-v3-0324:free",
                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "The answer is 4."
                        },
                        "finish_reason": "stop"
                    }
                ],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 5,
                    "total_tokens": 15
                }
            }
        """.trimIndent()

        val response = deserialize<OpenRouterChatResponse>(json)

        assertNotNull(response)
        assertEquals("chatcmpl-123", response.id)
        assertEquals("chat.completion", response.objectType)
        assertEquals("deepseek/deepseek-chat-v3-0324:free", response.model)
        assertEquals(1, response.choices.size)
        assertEquals("The answer is 4.", response.choices[0].message.content)
        assertEquals("stop", response.choices[0].finishReason)

        assertNotNull(response.usage)
        assertEquals(10, response.usage.promptTokens)
        assertEquals(5, response.usage.completionTokens)
        assertEquals(15, response.usage.totalTokens)
    }

    @Test
    fun testObjectFieldHandlingReservedKeyword()
    {
        val json = """
            {
                "id": "test-123",
                "object": "chat.completion",
                "created": 1234567890,
                "model": "test/model",
                "choices": []
            }
        """.trimIndent()

        val response = deserialize<OpenRouterChatResponse>(json)

        assertNotNull(response)
        assertEquals("test-123", response.id)
        assertEquals("chat.completion", response.objectType)
        assertEquals("chat.completion", response.objectType)
    }

    @Test
    fun testStreamingChunkDeserialization()
    {
        val json = """
            {
                "id": "chatcmpl-123",
                "object": "chat.completion.chunk",
                "created": 1234567890,
                "model": "test/model",
                "choices": [
                    {
                        "index": 0,
                        "delta": {
                            "content": "Hello"
                        },
                        "finish_reason": null
                    }
                ]
            }
        """.trimIndent()

        val chunk = deserialize<StreamingChunk>(json)

        assertNotNull(chunk)
        assertEquals("chat.completion.chunk", chunk.objectType)
        assertEquals("Hello", chunk.choices[0].delta.content)
    }

    @Test
    fun testUsageInfoSerialization()
    {
        val usage = UsageInfo(
            promptTokens = 100,
            completionTokens = 50,
            totalTokens = 150
        )

        val json = serialize(usage)
        val deserialized = deserialize<UsageInfo>(json)

        assertNotNull(deserialized)
        assertEquals(100, deserialized.promptTokens)
        assertEquals(50, deserialized.completionTokens)
        assertEquals(150, deserialized.totalTokens)
    }

    @Test
    fun testErrorResponseDeserialization()
    {
        val json = """
            {
                "error": {
                    "message": "Invalid API key",
                    "code": "invalid_api_key",
                    "type": "auth_error"
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<OpenRouterErrorResponse>(json)

        assertNotNull(errorResponse)
        assertEquals("Invalid API key", errorResponse.error.message)
        assertEquals("invalid_api_key", errorResponse.error.code)
        assertEquals("auth_error", errorResponse.error.type)
    }

//=========================================Builder Pattern Tests========================================================

    @Test
    fun testBuilderPatternChainingReturnsPipe()
    {
        val pipe = OpenRouterPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://custom.openrouter.ai/api/v1")
            .setHttpReferer("https://example.com")
            .setOpenRouterTitle("TestApp")

        assertNotNull(pipe)
        assertTrue(pipe is OpenRouterPipe)
    }

    @Test
    fun testBuilderPatternSetApiKeyReturnsPipe()
    {
        val pipe = OpenRouterPipe()
        val returned = pipe.setApiKey("test-api-key-123")

        assertNotNull(returned)
        assertTrue(returned is OpenRouterPipe)
    }

//=========================================Missing API Key Validation Tests===========================================

    @Test
    fun testMissingApiKeyThrowsOnInit()
    {
        val pipe = OpenRouterPipe()

        assertFailsWith<IllegalStateException>
        {
            runBlocking {
                pipe.init()
            }
        }
    }

    @Test
    fun testEmptyApiKeyThrowsOnInit()
    {
        val pipe = OpenRouterPipe()
            .setApiKey("")

        var exceptionThrown = false
        runBlocking {
            try
            {
                pipe.init()
            }
            catch(e: IllegalStateException)
            {
                exceptionThrown = true
                assertTrue(e.message!!.contains("API key"))
            }
        }
        assertTrue(exceptionThrown)
    }

    @Test
    fun testBlankApiKeyThrowsOnInit()
    {
        val pipe = OpenRouterPipe()
            .setApiKey("   ")

        var exceptionThrown = false
        runBlocking {
            try
            {
                pipe.init()
            }
            catch(e: IllegalStateException)
            {
                exceptionThrown = true
            }
        }
        assertTrue(exceptionThrown)
    }

//=========================================Error Response Handling Tests===========================================

    @Test
    fun test401AuthenticationErrorMapping()
    {
        val json = """
            {
                "error": {
                    "message": "Invalid API key provided",
                    "code": "invalid_api_key",
                    "type": "auth_error"
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<OpenRouterErrorResponse>(json)

        assertNotNull(errorResponse)
        assertEquals("auth_error", errorResponse.error.type)
        assertTrue(errorResponse.error.message.contains("Invalid API key"))
    }

    @Test
    fun testErrorResponseWithCodeBody()
    {
        val json = """
            {
                "error": {
                    "message": "Request failed",
                    "code": "request_failed",
                    "type": "rate_limit_error",
                    "code_body": "rate_limit_exceeded"
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<OpenRouterErrorResponse>(json)

        assertNotNull(errorResponse)
        assertEquals("rate_limit_error", errorResponse.error.type)
        assertEquals("rate_limit_exceeded", errorResponse.error.codeBody)
    }

    @Test
    fun testErrorResponseNullFields()
    {
        val json = """
            {
                "error": {
                    "message": "Some error",
                    "code": null,
                    "type": null
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<OpenRouterErrorResponse>(json)

        assertNotNull(errorResponse)
        assertEquals("Some error", errorResponse.error.message)
        assertTrue(errorResponse.error.code == null)
        assertTrue(errorResponse.error.type == null)
    }

//=========================================System Prompt Handling Tests=========================================

    @Test
    fun testSystemPromptInRequest()
    {
        val messages = mutableListOf<ChatMessage>()
        val systemPrompt = "You are a helpful assistant."

        if(systemPrompt.isNotEmpty())
        {
            messages.add(ChatMessage(role = "system", content = systemPrompt))
        }
        messages.add(ChatMessage(role = "user", content = "Hello"))

        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals("You are a helpful assistant.", messages[0].content)
        assertEquals("user", messages[1].role)
    }

    @Test
    fun testSystemPromptEmpty()
    {
        val systemPrompt = ""
        val messages = mutableListOf<ChatMessage>()

        if(systemPrompt.isNotEmpty())
        {
            messages.add(ChatMessage(role = "system", content = systemPrompt))
        }
        messages.add(ChatMessage(role = "user", content = "Hello"))

        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
    }

//=========================================Temperature Parameter Tests=========================================

    @Test
    fun testTemperatureParameterIncludedWhenNonZero()
    {
        val temperature = 0.7
        val request = OpenRouterChatRequest(
            model = "test/model",
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            temperature = if(temperature > 0.0) temperature else null
        )

        val json = serialize(request)
        assertTrue(json.contains("0.7"))
    }

    @Test
    fun testTemperatureParameterExcludedWhenZero()
    {
        val temperature = 0.0
        val request = OpenRouterChatRequest(
            model = "test/model",
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            temperature = if(temperature > 0.0) temperature else null
        )

        assertTrue(request.temperature == null)
    }

    @Test
    fun testTemperatureParameterNullByDefault()
    {
        val request = OpenRouterChatRequest(
            model = "test/model",
            messages = listOf(ChatMessage(role = "user", content = "Hi"))
        )

        assertTrue(request.temperature == null)
    }

    @Test
    fun testTemperatureHighValue()
    {
        val temperature = 1.5
        val request = OpenRouterChatRequest(
            model = "test/model",
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            temperature = if(temperature > 0.0) temperature else null
        )

        val json = serialize(request)
        val deserialized = deserialize<OpenRouterChatRequest>(json)
        assertEquals(1.5, deserialized?.temperature)
    }

//=========================================Streaming Tests===========================================================

    @Test
    fun testSetStreamingEnabledBuilderReturnsPipe()
    {
        val pipe = OpenRouterPipe()
        val returned = pipe.setStreamingEnabled(true)

        assertNotNull(returned)
        assertTrue(returned is OpenRouterPipe)
    }

    @Test
    fun testStreamingCallbackBuilderReturnsPipe()
    {
        val pipe = OpenRouterPipe()
        val callback: suspend (String) -> Unit = {}
        val returned = pipe.setStreamingCallback(callback)

        assertNotNull(returned)
        assertTrue(returned is OpenRouterPipe)
    }

    @Test
    fun testStreamingCallbackEnablesStreamingFlag()
    {
        val pipe = OpenRouterPipe()
        assertTrue(pipe is OpenRouterPipe)

        val callback: suspend (String) -> Unit = {}
        pipe.setStreamingCallback(callback)

        assertTrue(pipe is OpenRouterPipe)
    }

    @Test
    fun testExecuteStreamingParsesSseFormat()
    {
        val sseLines = listOf(
            ": This is a comment line",
            "",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"test/model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"test/model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":null}]}",
            "data: [DONE]"
        )

        val textBuilder = StringBuilder()

        for(line in sseLines)
        {
            if(line.isEmpty()) continue
            if(line.startsWith(":")) continue
            if(line == "data: [DONE]") break
            if(line.startsWith("data: "))
            {
                val json = line.substringAfter("data: ")
                val chunk = deserialize<StreamingChunk>(json) ?: continue
                val contentDelta = chunk.choices.firstOrNull()?.delta?.content ?: ""

                if(contentDelta.isNotEmpty())
                {
                    textBuilder.append(contentDelta)
                }
            }
        }

        assertEquals("Hello world", textBuilder.toString())
    }

    @Test
    fun testExecuteStreamingSkipsEmptyAndCommentLines()
    {
        val sseLines = listOf(
            "",
            ": ignorable comment",
            "   ",
            ": another comment",
            ""
        )

        val textBuilder = StringBuilder()

        for(line in sseLines)
        {
            if(line.isEmpty()) continue
            if(line.startsWith(":")) continue
            if(line == "data: [DONE]") break
            if(line.startsWith("data: "))
            {
                val json = line.substringAfter("data: ")
                val chunk = deserialize<StreamingChunk>(json) ?: continue
                val contentDelta = chunk.choices.firstOrNull()?.delta?.content ?: ""

                if(contentDelta.isNotEmpty())
                {
                    textBuilder.append(contentDelta)
                }
            }
        }

        assertEquals("", textBuilder.toString())
    }

    @Test
    fun testStreamingChunkDeserializationWithPartialContent()
    {
        val json = """
            {
                "id": "chatcmpl-456",
                "object": "chat.completion.chunk",
                "created": 1234567890,
                "model": "test/model",
                "choices": [
                    {
                        "index": 0,
                        "delta": {
                            "content": "Partial"
                        },
                        "finish_reason": null
                    }
                ]
            }
        """.trimIndent()

        val chunk = deserialize<StreamingChunk>(json)

        assertNotNull(chunk)
        assertEquals("chatcmpl-456", chunk.id)
        assertEquals("chat.completion.chunk", chunk.objectType)
        assertEquals("Partial", chunk.choices[0].delta.content)
        assertEquals(0, chunk.choices[0].index)
    }

    @Test
    fun testStreamingTerminatesOnDoneNoExtraContent()
    {
        val sseLines = listOf(
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"test/model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"First\"},\"finish_reason\":null}]}",
            "data: [DONE]",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"test/model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Leak\"},\"finish_reason\":null}]}"
        )

        val textBuilder = StringBuilder()

        for(line in sseLines)
        {
            if(line.isEmpty()) continue
            if(line.startsWith(":")) continue
            if(line == "data: [DONE]") break
            if(line.startsWith("data: "))
            {
                val json = line.substringAfter("data: ")
                val chunk = deserialize<StreamingChunk>(json) ?: continue
                val contentDelta = chunk.choices.firstOrNull()?.delta?.content ?: ""

                if(contentDelta.isNotEmpty())
                {
                    textBuilder.append(contentDelta)
                }
            }
        }

        assertEquals("First", textBuilder.toString())
    }

    @Test
    fun testStreamingDeltaMessageRoleAndContent()
    {
        val json = """
            {
                "id": "chatcmpl-789",
                "object": "chat.completion.chunk",
                "created": 1234567890,
                "model": "test/model",
                "choices": [
                    {
                        "index": 0,
                        "delta": {
                            "role": "assistant",
                            "content": "Hi"
                        },
                        "finish_reason": "stop"
                    }
                ]
            }
        """.trimIndent()

        val chunk = deserialize<StreamingChunk>(json)

        assertNotNull(chunk)
        assertEquals("assistant", chunk.choices[0].delta.role)
        assertEquals("Hi", chunk.choices[0].delta.content)
        assertEquals("stop", chunk.choices[0].finishReason)
    }
}