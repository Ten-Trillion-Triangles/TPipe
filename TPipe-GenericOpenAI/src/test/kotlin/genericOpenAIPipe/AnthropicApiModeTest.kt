package genericOpenAIPipe

import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import genericOpenAIPipe.api.AnthropicMessage
import genericOpenAIPipe.api.AnthropicMessagesRequest
import genericOpenAIPipe.api.AnthropicRequestSerializer
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.api.OpenAIRequestSerializer
import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.GenericOpenAIChatRequest
import genericOpenAIPipe.env.MessageContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for ApiMode sealed class and serialization components.
 * Tests cover ApiMode creation, OpenAIRequestSerializer, AnthropicRequestSerializer,
 * max_tokens validation, and system message extraction edge cases.
 */
class AnthropicApiModeTest
{

//=========================================ApiMode Tests=========================================

    @Test
    fun testApiModeOpenAICreation()
    {
        val apiMode = ApiMode.OpenAI
        assertNotNull(apiMode)
        assertTrue(apiMode is ApiMode.OpenAI)
    }

    @Test
    fun testApiModeAnthropicCreation()
    {
        val apiMode = ApiMode.Anthropic
        assertNotNull(apiMode)
        assertTrue(apiMode is ApiMode.Anthropic)
    }

    @Test
    fun testApiModeDefaultIsOpenAI()
    {
        assertEquals(ApiMode.OpenAI, ApiMode.DEFAULT)
    }

    @Test
    fun testApiModeOpenAIDefaultInstance()
    {
        val default = ApiMode.OpenAI.default
        assertNotNull(default)
        assertTrue(default is ApiMode.OpenAI)
    }

    @Test
    fun testApiModeSealedClassExhaustiveness()
    {
        val modes = listOf(ApiMode.OpenAI, ApiMode.Anthropic)
        assertEquals(2, modes.size)
    }

//=========================================OpenAIRequestSerializer Tests=========================================

    @Test
    fun testOpenAIRequestSerializerSerializeOpenAIMode()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hello"))
            )
        )

        val serializer = OpenAIRequestSerializer()
        val json = serializer.serialize(request, ApiMode.OpenAI)

        assertNotNull(json)
        assertTrue(json.contains("gpt-4o"))
        assertTrue(json.contains("role"))
        assertTrue(json.contains("user"))
        assertTrue(json.contains("Hello"))
        // Should NOT contain Anthropic-specific fields
        assertTrue(!json.contains("\"system\""))
    }

    @Test
    fun testOpenAIRequestSerializerWithSystemMessage()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("You are helpful")),
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            )
        )

        val serializer = OpenAIRequestSerializer()
        val json = serializer.serialize(request, ApiMode.OpenAI)

        assertTrue(json.contains("You are helpful"))
        assertTrue(json.contains("Hi"))
        // system role should be in messages for OpenAI
        assertTrue(json.contains("system"))
    }

    @Test
    fun testOpenAIRequestSerializerWithAllParameters()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hello"))
            ),
            temperature = 0.7,
            maxTokens = 1000,
            stream = false
        )

        val serializer = OpenAIRequestSerializer()
        val json = serializer.serialize(request, ApiMode.OpenAI)

        assertTrue(json.contains("gpt-4o"))
        assertTrue(json.contains("0.7"))
        assertTrue(json.contains("1000"))
    }

//=========================================AnthropicRequestSerializer Tests=========================================

    @Test
    fun testAnthropicRequestSerializerSerializeAnthropicMode()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hello"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val json = serializer.serialize(request, ApiMode.Anthropic)

        assertNotNull(json)
        assertTrue(json.contains("claude-3-5-sonnet-20241022"))
        // Anthropic format uses "user" as type, not role
        assertTrue(json.contains("user"))
        assertTrue(json.contains("Hello"))
    }

    @Test
    fun testAnthropicRequestSerializerTransformsUserMessage()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("What is 2+2?"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val json = serializer.serialize(request, ApiMode.Anthropic)

        // Verify JSON structure
        val deserialized = deserialize<AnthropicMessagesRequest>(json)
        assertNotNull(deserialized)
        assertEquals("claude-3-5-sonnet-20241022", deserialized.model)
        assertEquals(1, deserialized.messages.size)
        assertTrue(deserialized.messages[0] is AnthropicMessage.UserMessage)
    }

    @Test
    fun testAnthropicRequestSerializerTransformsAssistantMessage()
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
        val json = serializer.serialize(request, ApiMode.Anthropic)

        val deserialized = deserialize<AnthropicMessagesRequest>(json)
        assertNotNull(deserialized)
        assertEquals(2, deserialized.messages.size)
        assertTrue(deserialized.messages[0] is AnthropicMessage.UserMessage)
        assertTrue(deserialized.messages[1] is AnthropicMessage.AssistantMessage)
    }

    @Test
    fun testAnthropicRequestSerializerRequiresApiMode()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()

        // Should throw when using OpenAI mode with AnthropicRequestSerializer
        assertFailsWith<IllegalArgumentException>
        {
            serializer.serialize(request, ApiMode.OpenAI)
        }
    }

//=========================================max_tokens Validation Tests=========================================

    @Test
    fun testAnthropicRequestSerializerThrowsWhenMaxTokensMissing()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            )
        )

        val serializer = AnthropicRequestSerializer()

        assertFailsWith<IllegalStateException>
        {
            serializer.serialize(request, ApiMode.Anthropic)
        }
    }

    @Test
    fun testAnthropicRequestSerializerThrowsWithSpecificMessage()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            )
        )

        val serializer = AnthropicRequestSerializer()

        val exception = assertFailsWith<IllegalStateException>
        {
            serializer.serialize(request, ApiMode.Anthropic)
        }
        assertTrue(exception.message!!.contains("maxTokens"))
        assertTrue(exception.message!!.contains("REQUIRED"))
    }

    @Test
    fun testAnthropicRequestSerializerAcceptsMaxCompletionTokens()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            ),
            maxCompletionTokens = 8192
        )

        val serializer = AnthropicRequestSerializer()
        val json = serializer.serialize(request, ApiMode.Anthropic)

        assertNotNull(json)
        val deserialized = deserialize<AnthropicMessagesRequest>(json)
        assertNotNull(deserialized)
        assertEquals(8192, deserialized.maxTokens)
    }

    @Test
    fun testAnthropicRequestSerializerPrefersMaxTokensOverMaxCompletionTokens()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            ),
            maxTokens = 1024,
            maxCompletionTokens = 2048
        )

        val serializer = AnthropicRequestSerializer()
        val json = serializer.serialize(request, ApiMode.Anthropic)

        val deserialized = deserialize<AnthropicMessagesRequest>(json)
        assertNotNull(deserialized)
        // maxTokens should be preferred
        assertEquals(1024, deserialized.maxTokens)
    }

//=========================================System Message Extraction Tests=========================================

    @Test
    fun testSystemMessageExtractionNoSystemMessage()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hello"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val json = serializer.serialize(request, ApiMode.Anthropic)

        val deserialized = deserialize<AnthropicMessagesRequest>(json)
        assertNotNull(deserialized)
        // system should be null when no system message
        assertNull(deserialized.system)
        // Should still have the user message
        assertEquals(1, deserialized.messages.size)
    }

    @Test
    fun testSystemMessageExtractionSingleSystemMessage()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("You are a helpful assistant")),
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val json = serializer.serialize(request, ApiMode.Anthropic)

        val deserialized = deserialize<AnthropicMessagesRequest>(json)
        assertNotNull(deserialized)
        // system should be extracted
        assertEquals("You are a helpful assistant", deserialized.system)
        // user message should still be present
        assertEquals(1, deserialized.messages.size)
        // system should be removed from messages
        assertTrue(deserialized.messages[0] is AnthropicMessage.UserMessage)
    }

    @Test
    fun testSystemMessageExtractionMultipleSystemMessages()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("First system prompt")),
                ChatMessage(role = "user", content = MessageContent.TextContent("Hello")),
                ChatMessage(role = "system", content = MessageContent.TextContent("Second system prompt"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val json = serializer.serialize(request, ApiMode.Anthropic)

        val deserialized = deserialize<AnthropicMessagesRequest>(json)
        assertNotNull(deserialized)
        // First system message should be used
        assertEquals("First system prompt", deserialized.system)
        // Only user message should remain (system messages filtered out)
        assertEquals(1, deserialized.messages.size)
    }

    @Test
    fun testSystemMessageExtractionEmptySystemContent()
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
        val json = serializer.serialize(request, ApiMode.Anthropic)

        val deserialized = deserialize<AnthropicMessagesRequest>(json)
        assertNotNull(deserialized)
        // Empty system content should be treated as null
        assertNull(deserialized.system)
        // User message should still be present
        assertEquals(1, deserialized.messages.size)
    }

    @Test
    fun testSystemMessageExtractionOnlySystemMessages()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("System only"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val json = serializer.serialize(request, ApiMode.Anthropic)

        val deserialized = deserialize<AnthropicMessagesRequest>(json)
        assertNotNull(deserialized)
        assertEquals("System only", deserialized.system)
        // No messages remain after filtering system
        assertEquals(0, deserialized.messages.size)
    }

//=========================================End-to-End Serialization Tests=========================================

    @Test
    fun testFullSerializationRoundTripOpenAI()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-4o",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("You are helpful")),
                ChatMessage(role = "user", content = MessageContent.TextContent("What is 2+2?"))
            ),
            temperature = 0.7,
            maxTokens = 1000
        )

        val serializer = OpenAIRequestSerializer()
        val json = serializer.serialize(request, ApiMode.OpenAI)

        val deserialized = deserialize<GenericOpenAIChatRequest>(json)
        assertNotNull(deserialized)
        assertEquals("gpt-4o", deserialized.model)
        assertEquals(2, deserialized.messages.size)
        assertEquals(0.7, deserialized.temperature)
    }

    @Test
    fun testFullSerializationRoundTripAnthropic()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("You are a mathematician")),
                ChatMessage(role = "user", content = MessageContent.TextContent("What is 3+3?"))
            ),
            maxTokens = 4096
        )

        val serializer = AnthropicRequestSerializer()
        val json = serializer.serialize(request, ApiMode.Anthropic)

        val deserialized = deserialize<AnthropicMessagesRequest>(json)
        assertNotNull(deserialized)
        assertEquals("claude-3-5-sonnet-20241022", deserialized.model)
        assertEquals("You are a mathematician", deserialized.system)
        assertEquals(1, deserialized.messages.size)
        assertTrue(deserialized.messages[0] is AnthropicMessage.UserMessage)
    }
}