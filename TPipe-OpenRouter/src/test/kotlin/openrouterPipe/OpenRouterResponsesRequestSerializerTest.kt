package openrouterPipe

import com.TTT.Util.serialize
import env.ChatMessage
import env.OpenRouterChatRequest
import env.OpenRouterResponsesInputPart
import env.OpenRouterResponsesMessageItem
import env.ResponseFormat
import env.ReasoningConfig
import env.ToolDefinition
import env.FunctionSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Static tests for [OpenRouterResponsesRequestSerializer]: the pure
 * `OpenRouterChatRequest` -> OpenRouter Responses wire-spec conversion.
 *
 * The system-message hoisting, `max_tokens` -> `max_output_tokens`,
 * `response_format` -> `text.format` and flat-function-tool mappings are pinned
 * here against fixtures; no HTTP is performed.
 */
class OpenRouterResponsesRequestSerializerTest
{
    private val serializer = OpenRouterResponsesRequestSerializer()

    private fun baseRequest(messages: List<ChatMessage> = emptyList()): OpenRouterChatRequest =
        OpenRouterChatRequest(model = "openai/gpt-4.1", messages = messages)

    //=========================================System hoisting======================================================

    @Test
    fun testSystemMessagesHoistedIntoInstructions()
    {
        val request = baseRequest(
            listOf(
                ChatMessage(role = "system", content = "Be concise."),
                ChatMessage(role = "system", content = "Use markdown."),
                ChatMessage(role = "user", content = "Hi")
            )
        )
        val converted = serializer.convert(request, stream = false)

        assertEquals("Be concise.\n\nUse markdown.", converted.instructions)
        assertEquals(1, converted.input.size)
        assertEquals("user", converted.input[0].role)
    }

    @Test
    fun testNonSystemMessagesBecomeInputItems()
    {
        val request = baseRequest(
            listOf(
                ChatMessage(role = "user", content = "Question?"),
                ChatMessage(role = "assistant", content = "Answer.")
            )
        )
        val converted = serializer.convert(request, stream = false)

        assertNull(converted.instructions)
        assertEquals(2, converted.input.size)
        assertEquals("user", converted.input[0].role)
        assertEquals("assistant", converted.input[1].role)
        val userText = converted.input[0].content[0] as OpenRouterResponsesInputPart.InputTextPart
        assertEquals("Question?", userText.text)
    }

    @Test
    fun testBlankSystemMessagesAreDroppedFromInstructions()
    {
        val request = baseRequest(
            listOf(
                ChatMessage(role = "system", content = ""),
                ChatMessage(role = "user", content = "Hi")
            )
        )
        val converted = serializer.convert(request, stream = false)
        assertNull(converted.instructions)
        assertEquals(1, converted.input.size)
    }

    //=========================================Field mappings=======================================================

    @Test
    fun testMaxTokensMapsToMaxOutputTokens()
    {
        val request = OpenRouterChatRequest(
            model = "m",
            messages = listOf(ChatMessage("user", "hi")),
            maxTokens = 128
        )
        assertEquals(128, serializer.convert(request, stream = false).maxOutputTokens)
    }

    @Test
    fun testNullMaxTokensYieldsNullMaxOutputTokens()
    {
        val converted = serializer.convert(baseRequest(listOf(ChatMessage("user", "hi"))), stream = false)
        assertNull(converted.maxOutputTokens)
    }

    @Test
    fun testSamplingParametersPassThrough()
    {
        val request = OpenRouterChatRequest(
            model = "m",
            messages = listOf(ChatMessage("user", "hi")),
            temperature = 0.7,
            topP = 0.9,
            topK = 50
        )
        val converted = serializer.convert(request, stream = false)
        assertEquals(0.7, converted.temperature)
        assertEquals(0.9, converted.topP)
        assertEquals(50, converted.topK)
    }

    @Test
    fun testResponseFormatMapsToTextConfig()
    {
        val schema: JsonObject = buildJsonObject { }
        val request = OpenRouterChatRequest(
            model = "m",
            messages = listOf(ChatMessage("user", "hi")),
            responseFormat = ResponseFormat(type = "json_schema", jsonSchema = schema)
        )
        val converted = serializer.convert(request, stream = false)
        assertEquals("json_schema", converted.text?.format?.type)
        assertTrue(converted.text?.format?.schema != null)
    }

    @Test
    fun testNoResponseFormatYieldsNullText()
    {
        val converted = serializer.convert(baseRequest(listOf(ChatMessage("user", "hi"))), stream = false)
        assertNull(converted.text)
    }

    @Test
    fun testToolsMapToFlatFunctionTools()
    {
        val schema: JsonObject = buildJsonObject { }
        val request = OpenRouterChatRequest(
            model = "m",
            messages = listOf(ChatMessage("user", "hi")),
            tools = listOf(
                ToolDefinition(type = "function", function = FunctionSchema("get_weather", "desc", schema))
            ),
            toolChoice = "auto",
            parallelToolCalls = true
        )
        val converted = serializer.convert(request, stream = false)

        assertEquals(1, converted.tools?.size)
        val tool = converted.tools!![0]
        assertEquals("function", tool.type)
        assertEquals("get_weather", tool.name)
        assertEquals("desc", tool.description)
        assertEquals("auto", converted.toolChoice)
        assertEquals(true, converted.parallelToolCalls)
    }

    @Test
    fun testReasoningConfigMapsEffort()
    {
        val request = OpenRouterChatRequest(
            model = "m",
            messages = listOf(ChatMessage("user", "hi")),
            reasoning = ReasoningConfig(effort = "high", summary = "detailed")
        )
        val converted = serializer.convert(request, stream = false)
        assertEquals("high", converted.reasoning?.effort)
        assertEquals("detailed", converted.reasoning?.summary)
    }

    @Test
    fun testFlatReasoningEffortFallsBackWhenConfigAbsent()
    {
        val request = OpenRouterChatRequest(
            model = "m",
            messages = listOf(ChatMessage("user", "hi")),
            reasoningEffort = "low"
        )
        val converted = serializer.convert(request, stream = false)
        assertEquals("low", converted.reasoning?.effort)
    }

    @Test
    fun testNoReasoningYieldsNullReasoning()
    {
        val converted = serializer.convert(baseRequest(listOf(ChatMessage("user", "hi"))), stream = false)
        assertNull(converted.reasoning)
    }

    @Test
    fun testStreamFlagIsPassedThrough()
    {
        val request = baseRequest(listOf(ChatMessage("user", "hi")))
        assertEquals(false, serializer.convert(request, stream = false).stream)
        assertEquals(true, serializer.convert(request, stream = true).stream)
    }

    @Test
    fun testSerializeEmitsResponsesNotChatCompletionsJson()
    {
        val request = baseRequest(
            listOf(
                ChatMessage(role = "system", content = "sys"),
                ChatMessage(role = "user", content = "hi")
            )
        )
        val json = serializer.serialize(request, stream = false)
        val obj = Json.parseToJsonElement(json).jsonObject

        assertTrue(json.contains("\"input\""))
        assertTrue(json.contains("\"instructions\""))
        assertTrue(!json.contains("\"messages\""))
        assertEquals("openai/gpt-4.1", obj["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun testInputMessageItemShape()
    {
        val item = OpenRouterResponsesMessageItem(
            type = "message",
            role = "user",
            content = listOf(OpenRouterResponsesInputPart.InputTextPart("x"))
        )
        val obj = Json.parseToJsonElement(serialize(item, encodedefault = false)).jsonObject
        assertEquals("user", obj["role"]!!.jsonPrimitive.content)
        assertEquals("message", obj["type"]!!.jsonPrimitive.content)
    }
}
