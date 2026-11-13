package env

import com.TTT.Util.serialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DTO tests proving the Ollama `think` request wire field (bool OR level string,
 * `bool | string` in the Ollama API) round-trips through the request DTOs so thinking can
 * actually be requested. `think` is omitted when null (explicitNulls=false).
 *
 * `serialize` pretty-prints, so we parse the JSON and assert on the element, not on spacing.
 */
class OllamaThinkWireTest
{
    private val msg = OllamaMessage(role = "user", content = "hi")
    private val json = Json { isLenient = true }

    private fun thinkOf(reqBody: String) = json.parseToJsonElement(reqBody).let {
        if(it is kotlinx.serialization.json.JsonObject) it["think"] else null
    }

    @Test
    fun chatRequestSerializesThinkTrue()
    {
        val req = ChatRequest(model = "m", messages = listOf(msg), think = JsonPrimitive(true))
        assertEquals(JsonPrimitive(true), thinkOf(serialize(req)))
    }

    @Test
    fun chatRequestSerializesThinkLevel()
    {
        val req = ChatRequest(model = "m", messages = listOf(msg), think = JsonPrimitive("high"))
        assertEquals(JsonPrimitive("high"), thinkOf(serialize(req)))
    }

    @Test
    fun chatRequestOmitsThinkWhenNull()
    {
        val req = ChatRequest(model = "m", messages = listOf(msg))
        assertNull(thinkOf(serialize(req)), "think should be absent when unset")
    }

    @Test
    fun generatedRequestSerializesThinkTrue()
    {
        val req = GeneratedRequest(model = "m", prompt = "hi", think = JsonPrimitive(true))
        val el = thinkOf(serialize(req))
        assertEquals(JsonPrimitive(true), el, "expected think:true")
        assertEquals(true, el?.jsonPrimitive?.boolean)
    }

    @Test
    fun generatedRequestOmitsThinkWhenNull()
    {
        val req = GeneratedRequest(model = "m", prompt = "hi")
        assertNull(thinkOf(serialize(req)), "think should be absent when unset")
    }
}
