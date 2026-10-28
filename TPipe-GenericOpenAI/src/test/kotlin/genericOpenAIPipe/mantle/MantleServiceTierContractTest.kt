package genericOpenAIPipe.mantle

import com.TTT.Util.deserialize
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.api.AnthropicRequestSerializer
import genericOpenAIPipe.api.OpenAIRequestSerializer
import genericOpenAIPipe.api.OpenAIResponsesRequestSerializer
import genericOpenAIPipe.env.ChatChoice
import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.GenericOpenAIChatRequest
import genericOpenAIPipe.env.GenericOpenAIChatResponse
import genericOpenAIPipe.env.MessageContent
import genericOpenAIPipe.env.OpenAIResponsesResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for Bedrock Mantle `service_tier` support.
 *
 * Pins the wire + echo behavior before implementation exists (TDD RED):
 *  - [MantleServiceTier] maps to exactly the three wire values Bedrock Mantle
 *    accepts (`default`, `priority`, `flex`) — OpenAI's `auto` has no variant.
 *  - Chat Completions and Responses request wires emit a top-level
 *    `service_tier` field when set and OMIT it when unset (byte-preserve).
 *  - The Anthropic Messages wire NEVER emits `service_tier`, even when the
 *    normalized request carries one.
 *  - Both response shapes parse the provider's `service_tier` echo field.
 *
 * Assertions parse the JSON (com.TTT.Util.serialize emits pretty-printed
 * output) so they are formatting-agnostic.
 */
class MantleServiceTierContractTest
{
    private val chatSerializer = OpenAIRequestSerializer()
    private val responsesSerializer = OpenAIResponsesRequestSerializer()
    private val anthropicSerializer = AnthropicRequestSerializer()

    private fun request(serviceTier: String? = null, maxTokens: Int? = null): GenericOpenAIChatRequest =
        GenericOpenAIChatRequest(
            model = "google.gemma-4-31b",
            messages = emptyList(),
            serviceTier = serviceTier,
            maxTokens = maxTokens
        )

    private fun jsonElement(json: String) = Json.parseToJsonElement(json).jsonObject

//=========================================Enum Wire Mapping=========================================

    @Test
    fun testStandardMapsToDefaultWireValue()
    {
        assertEquals("default", MantleServiceTier.Standard.toWireValue())
    }

    @Test
    fun testPriorityMapsToPriorityWireValue()
    {
        assertEquals("priority", MantleServiceTier.Priority.toWireValue())
    }

    @Test
    fun testFlexMapsToFlexWireValue()
    {
        assertEquals("flex", MantleServiceTier.Flex.toWireValue())
    }

//=========================================Chat Completions Wire=========================================

    @Test
    fun testChatWireEmitsServiceTierWhenSet()
    {
        val json = chatSerializer.serialize(request(serviceTier = "flex"), ApiMode.OpenAI)
        assertEquals("flex", jsonElement(json)["service_tier"]?.jsonPrimitive?.content)
    }

    @Test
    fun testChatWireOmitsServiceTierWhenUnset()
    {
        val json = chatSerializer.serialize(request(), ApiMode.OpenAI)
        assertFalse(jsonElement(json).containsKey("service_tier"), "service_tier leaked into unset wire: $json")
    }

//=========================================Responses Wire=========================================

    @Test
    fun testResponsesWireEmitsServiceTierWhenSet()
    {
        val json = responsesSerializer.serialize(request(serviceTier = "priority"), ApiMode.OpenAIResponses)
        assertEquals("priority", jsonElement(json)["service_tier"]?.jsonPrimitive?.content)
    }

    @Test
    fun testResponsesWireOmitsServiceTierWhenUnset()
    {
        val json = responsesSerializer.serialize(request(), ApiMode.OpenAIResponses)
        assertFalse(jsonElement(json).containsKey("service_tier"), "service_tier leaked into unset wire: $json")
    }

//=========================================Anthropic Wire Exclusion=========================================

    @Test
    fun testAnthropicWireNeverEmitsServiceTier()
    {
        val json = anthropicSerializer.serialize(request(serviceTier = "flex", maxTokens = 64), ApiMode.Anthropic)
        assertFalse(jsonElement(json).containsKey("service_tier"), "service_tier leaked onto the Anthropic wire: $json")
    }

//=========================================Response Echo Parsing=========================================

    @Test
    fun testChatResponseParsesServiceTierEcho()
    {
        val original = GenericOpenAIChatResponse(
            id = "c1",
            created = 0L,
            model = "google.gemma-4-31b",
            choices = listOf(ChatChoice(index = 0, message = ChatMessage(role = "assistant", content = MessageContent.TextContent("pong")))),
            serviceTier = "flex"
        )
        val json = com.TTT.Util.serialize(original)
        assertEquals("flex", jsonElement(json)["service_tier"]?.jsonPrimitive?.content)
        val parsed = deserialize<GenericOpenAIChatResponse>(json)
        assertEquals("flex", parsed?.serviceTier)
    }

    @Test
    fun testResponsesResponseParsesServiceTierEcho()
    {
        val json = """{"id":"r1","object":"response","status":"completed","model":"google.gemma-4-31b","service_tier":"priority"}"""
        val parsed = deserialize<OpenAIResponsesResponse>(json)
        assertEquals("priority", parsed?.serviceTier)
    }

    @Test
    fun testResponsesResponseParsesWithoutServiceTier()
    {
        val json = """{"id":"r1","object":"response","status":"completed","model":"google.gemma-4-31b"}"""
        val parsed = deserialize<OpenAIResponsesResponse>(json)
        assertNull(parsed?.serviceTier)
    }
}
