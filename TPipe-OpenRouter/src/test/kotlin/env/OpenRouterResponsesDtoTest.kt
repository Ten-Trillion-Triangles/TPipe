package env

import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Static, fixture-driven wire-shape tests for the OpenRouter `/responses` DTO set
 * ([OpenRouterResponsesRequest], [OpenRouterResponsesResponse],
 * [OpenRouterResponsesStreamEvent], [OpenRouterResponsesInputPart]).
 *
 * No live HTTP is performed: every case decodes/encodes a canned JSON payload.
 */
class OpenRouterResponsesDtoTest
{
    //=========================================Request wire shape====================================================

    @Test
    fun testRequestSerializesResponsesWireShape()
    {
        val request = OpenRouterResponsesRequest(
            model = "openai/gpt-4.1",
            input = listOf(
                OpenRouterResponsesMessageItem(
                    role = "user",
                    content = listOf(OpenRouterResponsesInputPart.InputTextPart("Hello"))
                )
            ),
            instructions = "You are helpful.",
            maxOutputTokens = 100,
            temperature = 0.5,
            topP = 0.9,
            stream = true,
            toolChoice = "auto",
            tools = listOf(
                OpenRouterResponsesTool(
                    type = "function",
                    name = "get_weather",
                    description = "Get the weather",
                    parameters = null
                )
            )
        )

        val json = serialize(request, encodedefault = false)
        val obj = Json.parseToJsonElement(json).jsonObject

        // Responses (not chat-completions) wire markers.
        assertTrue(json.contains("\"input\""), "responses request must use 'input'")
        assertTrue(json.contains("\"max_output_tokens\""), "responses request must use max_output_tokens")
        assertTrue(json.contains("\"tool_choice\""))
        assertTrue(json.contains("\"instructions\""))
        assertTrue(!json.contains("\"messages\""), "responses request must NOT use 'messages'")

        // Flat function tool (OpenRouter Responses shape), not nested under "function".
        val tool = obj["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", tool["name"]!!.jsonPrimitive.content)

        // Input message content is an input_text part.
        val inputPart = obj["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("input_text", inputPart["type"]!!.jsonPrimitive.content)
        assertEquals("Hello", inputPart["text"]!!.jsonPrimitive.content)

        // Numeric/boolean fields survive.
        assertEquals(100, obj["max_output_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.5, obj["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals(true, obj["stream"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun testRequestDeserializesFromJson()
    {
        val json = """
            {"model":"m","input":[{"role":"user","content":[{"type":"input_text","text":"hi"}]}],
             "instructions":"sys","max_output_tokens":42,"temperature":0.1,"stream":false}
        """.trimIndent()
        val request = deserialize<OpenRouterResponsesRequest>(json)
        assertNotNull(request)
        assertEquals("m", request.model)
        assertEquals("sys", request.instructions)
        assertEquals(42, request.maxOutputTokens)
        assertEquals(1, request.input.size)
        assertTrue(request.input[0].content[0] is OpenRouterResponsesInputPart.InputTextPart)
        assertEquals("hi", (request.input[0].content[0] as OpenRouterResponsesInputPart.InputTextPart).text)
        assertNull(request.tools)
    }

    //=========================================Input part wire shape=================================================

    @Test
    fun testInputImagePartSerializesWithDetail()
    {
        val part = OpenRouterResponsesInputPart.InputImagePart(imageUrl = "https://x/img.png", detail = "high")
        val obj = Json.parseToJsonElement(serialize(part, encodedefault = false)).jsonObject
        assertEquals("input_image", obj["type"]!!.jsonPrimitive.content)
        assertEquals("https://x/img.png", obj["imageUrl"]!!.jsonPrimitive.content)
        assertEquals("high", obj["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun testInputTextPartDeserializesFromJson()
    {
        val json = """{"type":"input_text","text":"abc"}"""
        val part = deserialize<OpenRouterResponsesInputPart>(json)
        val text = part as OpenRouterResponsesInputPart.InputTextPart
        assertEquals("abc", text.text)
    }

    //=========================================Response wire shape===================================================

    @Test
    fun testResponseDeserializesMessageReasoningAndFunctionCall()
    {
        val json = """
            {
              "id": "resp_1", "object": "response", "created_at": 1700000000,
              "status": "completed", "model": "openai/gpt-4.1",
              "output": [
                {"type":"message","id":"m1","role":"assistant","status":"completed",
                 "content":[{"type":"output_text","text":"Hi"},{"type":"refusal","refusal":""}]},
                {"type":"reasoning","id":"r1","summary":[{"type":"summary_text","text":"thinking..."}],
                 "content":[{"type":"reasoning_text","text":"deep thought"}]},
                {"type":"function_call","id":"f1","call_id":"call_1","name":"get_weather",
                 "arguments":"{\"loc\":\"NYC\"}","status":"completed"}
              ],
              "usage":{"input_tokens":10,"output_tokens":25,"total_tokens":35,
                "input_tokens_details":{"cached_tokens":2},
                "output_tokens_details":{"reasoning_tokens":5}}
            }
        """.trimIndent()

        val response = deserialize<OpenRouterResponsesResponse>(json)
        assertNotNull(response)
        assertEquals("resp_1", response.id)
        assertEquals("openai/gpt-4.1", response.model)
        assertEquals(3, response.output.size)

        val message = response.output[0] as OpenRouterResponsesOutputItem.Message
        assertEquals(2, message.content.size)
        val textPart = message.content[0] as OpenRouterResponsesContentPart.OutputText
        assertEquals("Hi", textPart.text)
        assertTrue(message.content[1] is OpenRouterResponsesContentPart.Refusal)

        val reasoning = response.output[1] as OpenRouterResponsesOutputItem.Reasoning
        val summary = reasoning.summary[0] as OpenRouterResponsesContentPart.SummaryText
        assertEquals("thinking...", summary.text)

        val call = response.output[2] as OpenRouterResponsesOutputItem.FunctionCall
        assertEquals("get_weather", call.name)
        assertEquals("call_1", call.callId)
        assertEquals("""{"loc":"NYC"}""", call.arguments)

        assertEquals(35, response.usage!!.totalTokens)
        assertEquals(5, response.usage!!.outputTokensDetails!!.reasoningTokens)
        assertEquals(2, response.usage!!.inputTokensDetails!!.cachedTokens)
    }

    @Test
    fun testResponseToleratesUnknownOutputItemType()
    {
        // A web_search_call item the pipe does not model must decode to Unknown
        // (raw retained) instead of failing the whole response.
        val json = """
            {"id":"r","object":"response","created_at":1,"status":"completed","model":"m",
             "output":[
               {"type":"web_search_call","id":"ws1","results":[]},
               {"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"ok"}]}
             ]}
        """.trimIndent()

        val response = deserialize<OpenRouterResponsesResponse>(json)
        assertNotNull(response)
        assertEquals(2, response.output.size)
        assertIs<OpenRouterResponsesOutputItem.Unknown>(response.output[0])
        assertIs<OpenRouterResponsesOutputItem.Message>(response.output[1])
    }

    @Test
    fun testResponseDefaultsWhenOutputAbsent()
    {
        val json = """{"id":"r","object":"response","created_at":1,"status":"completed","model":"m"}"""
        val response = deserialize<OpenRouterResponsesResponse>(json)
        assertNotNull(response)
        assertEquals(0, response.output.size)
        assertNull(response.usage)
        assertNull(response.error)
    }
}
