package genericOpenAIPipe.api

import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.GenericOpenAIChatRequest
import genericOpenAIPipe.env.MessageContent
import genericOpenAIPipe.env.ReasoningConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies that Anthropic reasoning overloads become valid Messages API
 * thinking configuration instead of being silently discarded.
 */
class AnthropicReasoningMappingTest
{

    private val serializer = AnthropicRequestSerializer()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun disabledReasoningUsesAnthropicDisabledThinking()
    {
        val body = serialize(ReasoningConfig(enabled = false))

        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("output_config"))
    }

    @Test
    fun booleanReasoningUsesAdaptiveThinking()
    {
        val body = serialize(ReasoningConfig(enabled = true))

        assertEquals("adaptive", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("output_config"))
    }

    @Test
    fun customReasoningUsesAdaptiveEffort()
    {
        val body = serialize(ReasoningConfig(effort = "high", enabled = true))

        assertEquals("adaptive", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("high", body["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun tokenReasoningUsesManualBudget()
    {
        val body = serialize(ReasoningConfig(maxTokens = 2048, enabled = true))

        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("2048", body["thinking"]?.jsonObject?.get("budget_tokens")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("output_config"))
    }

    @Test
    fun tokenReasoningRejectsAnthropicInvalidBudget()
    {
        assertThrows(IllegalArgumentException::class.java)
        {
            serialize(ReasoningConfig(maxTokens = 1023, enabled = true))
        }
    }

    @Test
    fun customReasoningRejectsUnsupportedEffort()
    {
        assertThrows(IllegalArgumentException::class.java)
        {
            serialize(ReasoningConfig(effort = "none", enabled = true))
        }
    }

    private fun serialize(reasoning: ReasoningConfig) = json.parseToJsonElement(
        serializer.serialize(
            request = GenericOpenAIChatRequest(
                model = "claude-sonnet-4-6",
                messages = listOf(
                    ChatMessage(
                        role = "user",
                        content = MessageContent.TextContent("Hello")
                    )
                ),
                maxTokens = 4096,
                reasoning = reasoning
            ),
            apiMode = ApiMode.Anthropic
        )
    ).jsonObject
}
