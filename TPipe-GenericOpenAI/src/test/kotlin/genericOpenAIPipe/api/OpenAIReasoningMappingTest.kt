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
import org.junit.jupiter.api.Test

/**
 * Verifies that the OpenAI Chat Completions serializer uses the official
 * reasoning fields rather than leaking the provider-neutral configuration.
 */
class OpenAIReasoningMappingTest
{

    private val serializer = OpenAIRequestSerializer()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun disabledReasoningUsesOfficialChatCompletionsField()
    {
        val body = serialize(ReasoningConfig(enabled = false))

        assertEquals("none", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("reasoning"))
    }

    @Test
    fun booleanReasoningUsesDefaultMediumEffort()
    {
        val body = serialize(ReasoningConfig(enabled = true))

        assertEquals("medium", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("reasoning"))
    }

    @Test
    fun customReasoningUsesOfficialEffortField()
    {
        val body = serialize(ReasoningConfig(effort = "high", enabled = true))

        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("reasoning"))
    }

    @Test
    fun tokenReasoningUsesCompletionBudgetWithoutNestedReasoning()
    {
        val body = serialize(ReasoningConfig(maxTokens = 2048, enabled = true))

        assertEquals("medium", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("2048", body["max_completion_tokens"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("reasoning"))
    }

    @Test
    fun tokenReasoningReplacesLegacyMaxTokensWhenPipeAlreadyHasOutputLimit()
    {
        val body = json.parseToJsonElement(
            serializer.serialize(
                request = GenericOpenAIChatRequest(
                    model = "gpt-5.1",
                    messages = listOf(
                        ChatMessage(
                            role = "user",
                            content = MessageContent.TextContent("Hello")
                        )
                    ),
                    maxTokens = 4096,
                    reasoning = ReasoningConfig(maxTokens = 2048, enabled = true)
                ),
                apiMode = ApiMode.OpenAI
            )
        ).jsonObject

        assertEquals("2048", body["max_completion_tokens"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("max_tokens"))
    }

    private fun serialize(reasoning: ReasoningConfig) = json.parseToJsonElement(
        serializer.serialize(
            request = GenericOpenAIChatRequest(
                model = "gpt-5.1",
                messages = listOf(
                    ChatMessage(
                        role = "user",
                        content = MessageContent.TextContent("Hello")
                    )
                ),
                reasoning = reasoning
            ),
            apiMode = ApiMode.OpenAI
        )
    ).jsonObject
}
