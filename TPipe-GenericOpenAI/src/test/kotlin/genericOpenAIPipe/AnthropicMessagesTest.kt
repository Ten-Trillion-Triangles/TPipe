package genericOpenAIPipe

import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import genericOpenAIPipe.api.AnthropicResponseParser
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.GenericOpenAIChatRequest
import genericOpenAIPipe.env.GenericOpenAIErrorResponse
import genericOpenAIPipe.env.MessageContent
import genericOpenAIPipe.api.AnthropicRequestSerializer
import genericOpenAIPipe.api.AnthropicMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnthropicMessagesTest
{

    private lateinit var json: Json

    @BeforeEach
    fun setup()
    {
        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    @Test
    fun testUsageTokenMappingDirectConstruction()
    {
        val usage = genericOpenAIPipe.api.UsageInfo(
            inputTokens = 100,
            outputTokens = 50
        )

        assertEquals(100, usage.inputTokens)
        assertEquals(50, usage.outputTokens)
    }

    @Test
    fun testMaxTokensValidationViaSerializer()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = MessageContent.TextContent("Hello")
                )
            )
        )

        val serializer = AnthropicRequestSerializer()

        assertFailsWith<IllegalStateException>
        {
            serializer.serialize(request, ApiMode.Anthropic)
        }
    }

    @Test
    fun testMaxTokensIncludedInRequest()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = MessageContent.TextContent("Hello")
                )
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val jsonResult = serializer.serialize(request, ApiMode.Anthropic)

        assertTrue(jsonResult.contains("4096"))
    }

    @Test
    fun testMaxCompletionTokensAccepted()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = MessageContent.TextContent("Hello")
                )
            ),
            maxCompletionTokens = 8192
        )

        val serializer = AnthropicRequestSerializer()
        val jsonResult = serializer.serialize(request, ApiMode.Anthropic)

        assertTrue(jsonResult.contains("8192"))
    }

    @Test
    fun testMaxTokensPrecedenceOverMaxCompletionTokens()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = MessageContent.TextContent("Hello")
                )
            ),
            maxTokens = 1024,
            maxCompletionTokens = 2048
        )

        val serializer = AnthropicRequestSerializer()
        val jsonResult = serializer.serialize(request, ApiMode.Anthropic)

        assertTrue(jsonResult.contains("1024"))
        assertTrue(!jsonResult.contains("2048"))
    }

    @Test
    fun testAnthropicErrorResponseFormat()
    {
        val errorJson = """
            {
                "error": {
                    "type": "invalid_request_error",
                    "message": "max_tokens is required"
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<GenericOpenAIErrorResponse>(errorJson)

        assertNotNull(errorResponse)
        assertEquals("invalid_request_error", errorResponse.error.type)
        assertEquals("max_tokens is required", errorResponse.error.message)
    }

    @Test
    fun testAnthropicErrorResponseWithCode()
    {
        val errorJson = """
            {
                "error": {
                    "type": "authentication_error",
                    "message": "Invalid API key",
                    "code": "401"
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<GenericOpenAIErrorResponse>(errorJson)

        assertNotNull(errorResponse)
        assertEquals("authentication_error", errorResponse.error.type)
        assertEquals("Invalid API key", errorResponse.error.message)
        assertEquals("401", errorResponse.error.code)
    }

    @Test
    fun testAnthropicErrorResponseWithAllFields()
    {
        val errorJson = """
            {
                "error": {
                    "type": "rate_limit_error",
                    "message": "Rate limit exceeded",
                    "code": "429",
                    "param": "max_tokens"
                }
            }
        """.trimIndent()

        val errorResponse = deserialize<GenericOpenAIErrorResponse>(errorJson)

        assertNotNull(errorResponse)
        assertEquals("rate_limit_error", errorResponse.error.type)
        assertEquals("Rate limit exceeded", errorResponse.error.message)
        assertEquals("429", errorResponse.error.code)
        assertEquals("max_tokens", errorResponse.error.param)
    }

    @Test
    fun testAnthropicMessagesRequestSerialization()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hello"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val jsonResult = serializer.serialize(request, ApiMode.Anthropic)

        assertTrue(jsonResult.contains("claude-3-5-sonnet-20241022"))
        assertTrue(jsonResult.contains("user"))
        assertTrue(jsonResult.contains("Hello"))
        assertTrue(jsonResult.contains("4096"))
    }

    @Test
    fun testAnthropicMessagesRequestUserRoleTransformation()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("What is 2+2?"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val jsonResult = serializer.serialize(request, ApiMode.Anthropic)

        val deserialized = deserialize<genericOpenAIPipe.api.AnthropicMessagesRequest>(jsonResult)
        assertNotNull(deserialized)
        assertEquals(1, deserialized.messages.size)
        assertTrue(deserialized.messages[0] is AnthropicMessage.UserMessage)
    }

    @Test
    fun testAnthropicMessagesRequestAssistantRoleTransformation()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi")),
                ChatMessage(role = "assistant", content = MessageContent.TextContent("Hello!"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val jsonResult = serializer.serialize(request, ApiMode.Anthropic)

        val deserialized = deserialize<genericOpenAIPipe.api.AnthropicMessagesRequest>(jsonResult)
        assertNotNull(deserialized)
        assertEquals(2, deserialized.messages.size)
        assertTrue(deserialized.messages[0] is AnthropicMessage.UserMessage)
        assertTrue(deserialized.messages[1] is AnthropicMessage.AssistantMessage)
    }

    @Test
    fun testSystemMessageExtractedFromMessages()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("You are helpful")),
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val jsonResult = serializer.serialize(request, ApiMode.Anthropic)

        val deserialized = deserialize<genericOpenAIPipe.api.AnthropicMessagesRequest>(jsonResult)
        assertNotNull(deserialized)
        assertEquals("You are helpful", deserialized.system)
        assertEquals(1, deserialized.messages.size)
    }

    @Test
    fun testSystemMessageWithEmptyContentBecomesNull()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("   ")),
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val jsonResult = serializer.serialize(request, ApiMode.Anthropic)

        val deserialized = deserialize<genericOpenAIPipe.api.AnthropicMessagesRequest>(jsonResult)
        assertNotNull(deserialized)
        assertTrue(deserialized.system == null)
    }

    @Test
    fun testEmptyContentArrayParsed()
    {
        val jsonResponse = """
            {
                "id": "msg_empty",
                "type": "message",
                "role": "assistant",
                "content": [],
                "model": "claude-3-5-sonnet-20241022",
                "stop_reason": "end_turn",
                "usage": {
                    "input_tokens": 5,
                    "output_tokens": 0
                }
            }
        """.trimIndent()

        val parser = AnthropicResponseParser(json)
        val result = parser.parse(jsonResponse, ApiMode.Anthropic)

        assertNotNull(result)
        assertEquals("", result.choices.firstOrNull()?.message?.content?.let {
            if(it is MessageContent.TextContent) it.text else ""
        } ?: "")
    }

    @Test
    fun testAnthropicRequestSerializerRequiresMaxTokens()
    {
        val requestWithoutMaxTokens = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hello"))
            )
        )

        val serializer = AnthropicRequestSerializer()

        assertFailsWith<IllegalStateException>
        {
            serializer.serialize(requestWithoutMaxTokens, ApiMode.Anthropic)
        }
    }

    @Test
    fun testAnthropicRequestSerializerWithMaxCompletionTokens()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hello"))
            ),
            maxCompletionTokens = 2048
        )

        val serializer = AnthropicRequestSerializer()
        val jsonResult = serializer.serialize(request, ApiMode.Anthropic)

        assertTrue(jsonResult.contains("2048"))
    }

    @Test
    fun testAnthropicRequestSerializerDoesNotUseOpenAISerializer()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hello"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val jsonResult = serializer.serialize(request, ApiMode.Anthropic)

        assertTrue(jsonResult.contains("max_tokens"))
        assertTrue(jsonResult.contains("4096"))
    }
}