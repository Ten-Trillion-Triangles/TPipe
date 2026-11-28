package genericOpenAIPipe.api

import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.CacheControl
import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.GenericOpenAIChatRequest
import genericOpenAIPipe.env.MessageContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for explicit Anthropic & MiniMax prompt caching serialization and endpoint normalization.
 */
class AnthropicExplicitCachingTest
{
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `anthropic serializer places cache_control on last system block and penultimate message without top-level field`()
    {
        val request = GenericOpenAIChatRequest(
            model = "claude-sonnet-4-5",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("System block 1")),
                ChatMessage(role = "system", content = MessageContent.TextContent("System block 2")),
                ChatMessage(role = "user", content = MessageContent.TextContent("Turn 1 user")),
                ChatMessage(role = "assistant", content = MessageContent.TextContent("Turn 1 assistant")),
                ChatMessage(role = "user", content = MessageContent.TextContent("Turn 2 user"))
            ),
            maxTokens = 1024,
            cacheControl = CacheControl(type = "ephemeral", ttl = "5m")
        )

        val serialized = AnthropicRequestSerializer().serialize(request, ApiMode.Anthropic)
        val root = json.parseToJsonElement(serialized).jsonObject

        // Top-level cache_control / cacheControl must NOT be emitted
        assertNull(root["cache_control"])
        assertNull(root["cacheControl"])
        assertNull(root["systemBlocks"])

        // Last system block must be in "system" array with explicit type="text" and cache_control
        val systemBlocks = root["system"]?.jsonArray
        assertNotNull(systemBlocks)
        assertEquals(2, systemBlocks!!.size)
        assertEquals("text", systemBlocks[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertNull(systemBlocks[0].jsonObject["cache_control"])
        assertEquals("text", systemBlocks[1].jsonObject["type"]?.jsonPrimitive?.content)
        val lastSysCache = systemBlocks[1].jsonObject["cache_control"]?.jsonObject
        assertNotNull(lastSysCache)
        assertEquals("ephemeral", lastSysCache!!["type"]?.jsonPrimitive?.content)
        assertEquals("5m", lastSysCache["ttl"]?.jsonPrimitive?.content)

        // Penultimate message (index 1: Turn 1 assistant) last block must have rolling cache_control
        val messages = root["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertNull(messages[0].jsonObject["content"]!!.jsonArray.last().jsonObject["cache_control"])
        val penultCache = messages[1].jsonObject["content"]!!.jsonArray.last().jsonObject["cache_control"]?.jsonObject
        assertNotNull(penultCache)
        assertEquals("ephemeral", penultCache!!["type"]?.jsonPrimitive?.content)
        assertEquals("5m", penultCache["ttl"]?.jsonPrimitive?.content)
        assertNull(messages[2].jsonObject["content"]!!.jsonArray.last().jsonObject["cache_control"])
    }

    @Test
    fun `minimax anthropic mode omits ttl from cache_control markers`()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M3.1-Flash",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("System prompt")),
                ChatMessage(role = "user", content = MessageContent.TextContent("First")),
                ChatMessage(role = "assistant", content = MessageContent.TextContent("Reply")),
                ChatMessage(role = "user", content = MessageContent.TextContent("Second"))
            ),
            maxTokens = 2048,
            cacheControl = CacheControl(type = "ephemeral", ttl = null)
        )

        val serialized = AnthropicRequestSerializer().serialize(request, ApiMode.Anthropic)
        val root = json.parseToJsonElement(serialized).jsonObject

        assertNull(root["systemBlocks"])
        val systemBlocks = root["system"]!!.jsonArray
        assertEquals("text", systemBlocks.last().jsonObject["type"]!!.jsonPrimitive.content)
        val sysCache = systemBlocks.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", sysCache["type"]!!.jsonPrimitive.content)
        assertFalse(sysCache.containsKey("ttl"))

        val messages = root["messages"]!!.jsonArray
        val penultCache = messages[1].jsonObject["content"]!!.jsonArray.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", penultCache["type"]!!.jsonPrimitive.content)
        assertFalse(penultCache.containsKey("ttl"))
    }

    @Test
    fun `minimax anthropic baseUrl resolves cleanly to v1 messages without double-appending`()
    {
        val pipe = GenericOpenAIPipe().apply {
            setApiMode(ApiMode.Anthropic)
            setBaseUrl("https://api.minimax.io/anthropic")
            setApiKey("test-key")
        }

        assertEquals("/v1/messages", pipe.internalGetEndpointForTest())
        assertTrue(pipe.internalGetEndpointForTest().startsWith("/v1/"))
    }

    @Test
    fun `anthropic usage deserialization and sse parser capture cache creation and read tokens`()
    {
        val usageJson = """{"input_tokens":200,"output_tokens":42,"cache_creation_input_tokens":150,"cache_read_input_tokens":120}"""
        val usage = json.decodeFromString(UsageInfo.serializer(), usageJson)
        assertEquals(200, usage.inputTokens)
        assertEquals(42, usage.outputTokens)
        assertEquals(150, usage.cacheCreationInputTokens)
        assertEquals(120, usage.cacheReadInputTokens)

        val startLine = """data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":205,"output_tokens":1,"cache_creation_input_tokens":180,"cache_read_input_tokens":95}}}"""
        assertTrue(genericOpenAIPipe.env.AnthropicSseParser.parseAnthropicLine(startLine) is genericOpenAIPipe.env.AnthropicStreamEvent.Unknown)
        val startUsage = genericOpenAIPipe.env.AnthropicSseParser.parseAnthropicUsageInfo(startLine)
        assertNotNull(startUsage)
        assertEquals(205, startUsage!!.inputTokens)
        assertEquals(180, startUsage.cacheCreationInputTokens)
        assertEquals(95, startUsage.cacheReadInputTokens)
    }
}
