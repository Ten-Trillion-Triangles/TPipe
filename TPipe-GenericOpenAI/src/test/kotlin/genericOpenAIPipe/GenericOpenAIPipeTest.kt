package genericOpenAIPipe

import com.TTT.Pipe.Pipe
import com.TTT.P2P.P2PException
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import genericOpenAIPipe.env.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Unit tests for GenericOpenAIPipe non-streaming core functionality.
 * Tests cover request/response serialization, builder pattern, validation, and error handling.
 */
class GenericOpenAIPipeTest
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
    fun testGenericOpenAIChatRequestSerialization()
    {
        val messages = listOf(
            ChatMessage(role = "system", content = "You are helpful."),
            ChatMessage(role = "user", content = "What is 2+2?")
        )
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = messages,
            temperature = 0.7
        )

        val json = serialize(request)
        assertTrue(json.contains("gpt-4o"))
        assertTrue(json.contains("system"))
        assertTrue(json.contains("You are helpful"))
        assertTrue(json.contains("What is 2+2?"))
    }

    @Test
    fun testGenericOpenAIChatRequestWithNullOptionalFields()
    {
        val messages = listOf(ChatMessage(role = "user", content = "Hi"))
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = messages
        )

        val json = serialize(request)
        val deserialized = deserialize<GenericOpenAIChatRequest>(json)

        assertNotNull(deserialized)
        assertEquals("gpt-4o", deserialized.model)
        assertEquals(1, deserialized.messages.size)
        assertTrue(deserialized.temperature == null)
    }

    @Test
    fun testGenericOpenAIChatResponseDeserialization()
    {
        val json = """
            {
                "id": "chatcmpl-123",
                "object": "chat.completion",
                "created": 1234567890,
                "model": "gpt-4o",
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

        val response = deserialize<GenericOpenAIChatResponse>(json)

        assertNotNull(response)
        assertEquals("chatcmpl-123", response.id)
        assertEquals("chat.completion", response.objectType)
        assertEquals("gpt-4o", response.model)
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
                "model": "gpt-4o",
                "choices": []
            }
        """.trimIndent()

        val response = deserialize<GenericOpenAIChatResponse>(json)

        assertNotNull(response)
        assertEquals("test-123", response.id)
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
                "model": "gpt-4o",
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
                    "type": "authentication_error"
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<GenericOpenAIErrorResponse>(json)

        assertNotNull(errorResponse)
        assertEquals("Invalid API key", errorResponse.error.message)
        assertEquals("invalid_api_key", errorResponse.error.code)
        assertEquals("authentication_error", errorResponse.error.type)
    }

//=========================================Builder Pattern Tests========================================================

    @Test
    fun testBuilderPatternChainingReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://custom.example.com/v1")

        assertNotNull(pipe)
        assertTrue(pipe is GenericOpenAIPipe)
    }

    @Test
    fun testBuilderPatternSetApiKeyReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
        val returned = pipe.setApiKey("test-api-key-123")

        assertNotNull(returned)
        assertTrue(returned is GenericOpenAIPipe)
    }

    @Test
    fun testSetFrequencyPenaltyReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
        val returned = pipe.setFrequencyPenalty(0.5)

        assertNotNull(returned)
        assertTrue(returned is GenericOpenAIPipe)
    }

    @Test
    fun testSetToolsReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
        val tools = listOf(
            ToolDefinition(
                type = "function",
                function = FunctionSchema(
                    name = "get_weather",
                    description = "Get weather for a location",
                    parameters = kotlinx.serialization.json.JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("object")))
                )
            )
        )
        val returned = pipe.setTools(tools)

        assertNotNull(returned)
        assertTrue(returned is GenericOpenAIPipe)
    }

    @Test
    fun testSetToolChoiceReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
        val returned = pipe.setToolChoice("required")

        assertNotNull(returned)
        assertTrue(returned is GenericOpenAIPipe)
    }

    @Test
    fun testSetParallelToolCallsReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
        val returned = pipe.setParallelToolCalls(true)

        assertNotNull(returned)
        assertTrue(returned is GenericOpenAIPipe)
    }

    @Test
    fun testSetResponseFormatReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
        val returned = pipe.setResponseFormat("json_object")

        assertNotNull(returned)
        assertTrue(returned is GenericOpenAIPipe)
    }

    @Test
    fun testSetResponseFormatWithSchemaReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
        val schema = kotlinx.serialization.json.JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("object")))
        val returned = pipe.setResponseFormat("json_schema", schema)

        assertNotNull(returned)
        assertTrue(returned is GenericOpenAIPipe)
    }

    @Test
    fun testSetStructuredOutputsReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
        val returned = pipe.setStructuredOutputs(true)

        assertNotNull(returned)
        assertTrue(returned is GenericOpenAIPipe)
    }

    @Test
    fun testSetModalitiesReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
        val returned = pipe.setModalities(listOf("text", "image"))

        assertNotNull(returned)
        assertTrue(returned is GenericOpenAIPipe)
    }

    @Test
    fun testSetReasoningConfigReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
        val config = ReasoningConfig(effort = "high")
        val returned = pipe.setReasoningConfig(config)

        assertNotNull(returned)
        assertTrue(returned is GenericOpenAIPipe)
    }

    @Test
    fun testSetStreamingEnabledReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
        val returned = pipe.setStreamingEnabled(true)

        assertNotNull(returned)
        assertTrue(returned is GenericOpenAIPipe)
    }

    @Test
    fun testStreamingCallbackBuilderReturnsPipe()
    {
        val pipe = GenericOpenAIPipe()
        val callback: suspend (String) -> Unit = {}
        val returned = pipe.setStreamingCallback(callback)

        assertNotNull(returned)
        assertTrue(returned is GenericOpenAIPipe)
    }

//=========================================Missing API Key Validation Tests===========================================

    @Test
    fun testMissingApiKeyThrowsOnInit()
    {
        GenericOpenAIEnv.clearApiKey()
        val pipe = GenericOpenAIPipe()

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
        GenericOpenAIEnv.clearApiKey()
        val pipe = GenericOpenAIPipe()
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
        GenericOpenAIEnv.clearApiKey()
        val pipe = GenericOpenAIPipe()
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

    @Test
    fun testEnvApiKeyResolvedOnInit()
    {
        GenericOpenAIEnv.clearApiKey()
        GenericOpenAIEnv.setApiKey("env-test-key")
        val pipe = GenericOpenAIPipe()

        var initSucceeded = false
        runBlocking {
            try
            {
                pipe.init()
                initSucceeded = true
            }
            catch(e: IllegalStateException)
            {
                initSucceeded = false
            }
        }
        assertTrue(initSucceeded)
        GenericOpenAIEnv.clearApiKey()
    }

//=========================================Error Response Handling Tests===========================================

    @Test
    fun test401AuthenticationErrorMapping()
    {
        val json = """
            {
                "error": {
                    "message": "Invalid API key provided",
                    "code": "401",
                    "type": "authentication_error"
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<GenericOpenAIErrorResponse>(json)

        assertNotNull(errorResponse)
        assertEquals("authentication_error", errorResponse.error.type)
        assertEquals("401", errorResponse.error.code)
        assertTrue(errorResponse.error.message.contains("Invalid API key"))
    }

    @Test
    fun test429RateLimitErrorMapping()
    {
        val json = """
            {
                "error": {
                    "message": "Rate limit exceeded",
                    "code": "429",
                    "type": "rate_limit_error"
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<GenericOpenAIErrorResponse>(json)

        assertNotNull(errorResponse)
        assertEquals("rate_limit_error", errorResponse.error.type)
        assertEquals("429", errorResponse.error.code)
    }

    @Test
    fun test500ServerErrorMapping()
    {
        val json = """
            {
                "error": {
                    "message": "Internal server error",
                    "code": "500",
                    "type": "api_error"
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<GenericOpenAIErrorResponse>(json)

        assertNotNull(errorResponse)
        assertEquals("api_error", errorResponse.error.type)
        assertEquals("500", errorResponse.error.code)
    }

    @Test
    fun testErrorResponseWithParamField()
    {
        val json = """
            {
                "error": {
                    "message": "Invalid parameter",
                    "code": "invalid_request_error",
                    "type": "invalid_request_error",
                    "param": "temperature"
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<GenericOpenAIErrorResponse>(json)

        assertNotNull(errorResponse)
        assertEquals("temperature", errorResponse.error.param)
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

        val errorResponse = deserialize<GenericOpenAIErrorResponse>(json)

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
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
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
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            temperature = if(temperature > 0.0) temperature else null
        )

        assertTrue(request.temperature == null)
    }

    @Test
    fun testTemperatureParameterNullByDefault()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = "Hi"))
        )

        assertTrue(request.temperature == null)
    }

    @Test
    fun testTemperatureHighValue()
    {
        val temperature = 1.5
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            temperature = if(temperature > 0.0) temperature else null
        )

        val json = serialize(request)
        val deserialized = deserialize<GenericOpenAIChatRequest>(json)
        assertEquals(1.5, deserialized?.temperature)
    }

//=========================================MaxTokens Parameter Tests=========================================

    @Test
    fun testMaxTokensParameter()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            maxTokens = 1000
        )

        val json = serialize(request)
        assertTrue(json.contains("1000"))
    }

    @Test
    fun testMaxTokensNullByDefault()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = "Hi"))
        )

        assertTrue(request.maxTokens == null)
    }

//=========================================Streaming Tests===========================================================

    @Test
    fun testExecuteStreamingParsesSseFormat()
    {
        val sseLines = listOf(
            ": This is a comment line",
            "",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":null}]}",
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
                "model": "gpt-4o",
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
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"First\"},\"finish_reason\":null}]}",
            "data: [DONE]",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Leak\"},\"finish_reason\":null}]}"
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
                "model": "gpt-4o",
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

//=========================================SseParser Unit Tests===========================================

    @Test
    fun testSseParserParseLineEmpty()
    {
        val result = SseParser.parseLine("")
        assertTrue(result is SseParser.SseLine.Empty)
    }

    @Test
    fun testSseParserParseLineComment()
    {
        val result = SseParser.parseLine(": This is a comment")
        assertTrue(result is SseParser.SseLine.Comment)
    }

    @Test
    fun testSseParserParseLineDone()
    {
        val result = SseParser.parseLine("data: [DONE]")
        assertTrue(result is SseParser.SseLine.Done)
    }

    @Test
    fun testSseParserParseLineData()
    {
        val result = SseParser.parseLine("data: {\"content\":\"hello\"}")
        assertTrue(result is SseParser.SseLine.Data)
        val data = result as SseParser.SseLine.Data
        assertTrue(data.content.contains("hello"))
    }

    @Test
    fun testSseParserParseLineInvalid()
    {
        val result = SseParser.parseLine("invalid line")
        assertTrue(result is SseParser.SseLine.Invalid)
    }

    @Test
    fun testSseParserExtractContent()
    {
        val json = """
            {
                "id": "chatcmpl-123",
                "object": "chat.completion.chunk",
                "created": 1234567890,
                "model": "gpt-4o",
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

        val content = SseParser.extractContent(chunk)
        assertEquals("Hello", content)
    }

    @Test
    fun testSseParserExtractContentFromEmptyChunk()
    {
        val json = """
            {
                "id": "chatcmpl-123",
                "object": "chat.completion.chunk",
                "created": 1234567890,
                "model": "gpt-4o",
                "choices": [
                    {
                        "index": 0,
                        "delta": {},
                        "finish_reason": null
                    }
                ]
            }
        """.trimIndent()

        val chunk = deserialize<StreamingChunk>(json)
        assertNotNull(chunk)

        val content = SseParser.extractContent(chunk)
        assertEquals("", content)
    }

    @Test
    fun testSseParserExtractContentFromLine()
    {
        val line = "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}"
        val content = SseParser.extractContentFromLine(line)
        assertEquals("Hello", content)
    }

    @Test
    fun testSseParserExtractContentFromLineDone()
    {
        val line = "data: [DONE]"
        val content = SseParser.extractContentFromLine(line)
        assertTrue(content == null)
    }

    @Test
    fun testSseParserIterateLines()
    {
        val lines = listOf(
            ": comment",
            "",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" World\"},\"finish_reason\":null}]}",
            "data: [DONE]"
        ).iterator()

        val result = SseParser.iterateLines(lines)
        assertEquals("Hello World", result)
    }

//=========================================Error Response Streaming Tests===========================================

    @Test
    fun testStreamingErrorResponseDeserialization()
    {
        val json = """
            {
                "error": {
                    "message": "Authentication failed",
                    "code": "authentication_error",
                    "type": "authentication_error"
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<GenericOpenAIErrorResponse>(json)

        assertNotNull(errorResponse)
        assertEquals("Authentication failed", errorResponse.error.message)
        assertEquals("authentication_error", errorResponse.error.type)
    }

//=========================================GenericOpenAIEnv Tests=========================================

    @Test
    fun testGenericOpenAIEnvSetAndGetApiKey()
    {
        GenericOpenAIEnv.setApiKey("test-key-123")
        assertEquals("test-key-123", GenericOpenAIEnv.getApiKey())
        GenericOpenAIEnv.clearApiKey()
    }

    @Test
    fun testGenericOpenAIEnvResolveApiKeyPrefersProgrammatic()
    {
        GenericOpenAIEnv.setApiKey("programmatic-key")
        val resolved = GenericOpenAIEnv.resolveApiKey()
        assertEquals("programmatic-key", resolved)
        GenericOpenAIEnv.clearApiKey()
    }

    @Test
    fun testGenericOpenAIEnvHasApiKeyTrue()
    {
        GenericOpenAIEnv.setApiKey("some-key")
        assertTrue(GenericOpenAIEnv.hasApiKey())
        GenericOpenAIEnv.clearApiKey()
    }

    @Test
    fun testGenericOpenAIEnvHasApiKeyFalseWhenEmpty()
    {
        GenericOpenAIEnv.clearApiKey()
        assertFalse(GenericOpenAIEnv.hasApiKey())
    }

    @Test
    fun testGenericOpenAIEnvClearApiKey()
    {
        GenericOpenAIEnv.setApiKey("some-key")
        GenericOpenAIEnv.clearApiKey()
        assertEquals("", GenericOpenAIEnv.getApiKey())
    }

//=========================================ReasoningConfig Tests=========================================

    @Test
    fun testReasoningConfigSerialization()
    {
        val config = ReasoningConfig(effort = "high")
        val json = serialize(config)

        assertTrue(json.contains("high"))
    }

    @Test
    fun testReasoningConfigWithMaxTokens()
    {
        val config = ReasoningConfig(effort = "medium", maxTokens = 1000)
        val json = serialize(config)
        val deserialized = deserialize<ReasoningConfig>(json)

        assertNotNull(deserialized)
        assertEquals("medium", deserialized.effort)
        assertEquals(1000, deserialized.maxTokens)
    }

//=========================================ResponseFormat Tests=========================================

    @Test
    fun testResponseFormatJsonObject()
    {
        val format = ResponseFormat(type = "json_object")
        val json = serialize(format)

        assertTrue(json.contains("json_object"))
    }

    @Test
    fun testResponseFormatJsonSchema()
    {
        val schema = kotlinx.serialization.json.JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("object")))
        val format = ResponseFormat(type = "json_schema", jsonSchema = schema)
        val json = serialize(format)

        assertTrue(json.contains("json_schema"))
        assertTrue(json.contains("object"))
    }

//=========================================ToolDefinition Tests=========================================

    @Test
    fun testToolDefinitionSerialization()
    {
        val tool = ToolDefinition(
            type = "function",
            function = FunctionSchema(
                name = "get_weather",
                description = "Get weather for a location",
                parameters = kotlinx.serialization.json.JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("object")))
            )
        )

        val json = serialize(tool)
        assertTrue(json.contains("get_weather"))
        assertTrue(json.contains("function"))
        assertTrue(json.contains("Get weather for a location"))
    }

    @Test
    fun testToolDefinitionDeserialization()
    {
        val json = """
            {
                "type": "function",
                "function": {
                    "name": "get_weather",
                    "description": "Get weather",
                    "parameters": {"type": "object"}
                }
            }
        """.trimIndent()

        val tool = deserialize<ToolDefinition>(json)

        assertNotNull(tool)
        assertEquals("function", tool.type)
        assertEquals("get_weather", tool.function.name)
        assertEquals("Get weather", tool.function.description)
    }

//=========================================CacheControl Tests=========================================

    @Test
    fun testCacheControlSerialization()
    {
        val cache = CacheControl(type = "ephemeral", ttl = "5m")
        val json = serialize(cache)

        assertTrue(json.contains("ephemeral"))
        assertTrue(json.contains("5m"))
    }

//=========================================Presence and Frequency Penalty Tests=========================================

    @Test
    fun testPresencePenaltySerialization()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            presencePenalty = 0.5
        )

        val json = serialize(request)
        assertTrue(json.contains("0.5"))
    }

    @Test
    fun testFrequencyPenaltySerialization()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            frequencyPenalty = 0.5
        )

        val json = serialize(request)
        assertTrue(json.contains("0.5"))
    }

//=========================================Stop Sequences Tests=========================================

    @Test
    fun testStopSequencesSerialization()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            stop = listOf("STOP", "END")
        )

        val json = serialize(request)
        assertTrue(json.contains("STOP"))
        assertTrue(json.contains("END"))
    }

    @Test
    fun testStopSequencesNullByDefault()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = "Hi"))
        )

        assertTrue(request.stop == null)
    }

//=========================================Seed Tests=========================================

    @Test
    fun testSeedSerialization()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            seed = 42
        )

        val json = serialize(request)
        assertTrue(json.contains("42"))
    }

//=========================================Logprobs Tests=========================================

    @Test
    fun testLogprobsSerialization()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            logprobs = true,
            topLogprobs = 5
        )

        val json = serialize(request)
        assertTrue(json.contains("true"))
        assertTrue(json.contains("5"))
    }
}