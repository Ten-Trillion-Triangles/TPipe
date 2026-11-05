package env

import com.TTT.Util.serialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** OLL-B: Ollama chat `message` now carries `tool_name` + `tool_call_id` (tool-result turns). */
class OllamaToolResultDtoTest
{
    private val json = Json { isLenient = true }
    private fun obj(body: String) = json.parseToJsonElement(body) as JsonObject

    @Test
    fun toolMessageSerializesToolNameAndId()
    {
        val m = OllamaMessage(role = "tool", content = "result", toolName = "get_weather", toolCallId = "call_123")
        val o = obj(serialize(m))
        assertEquals("get_weather", o["tool_name"]?.jsonPrimitive?.content)
        assertEquals("call_123", o["tool_call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun toolFieldsOmittedWhenNull()
    {
        val o = obj(serialize(OllamaMessage(role = "user", content = "hi")))
        assertNull(o["tool_name"])
        assertNull(o["tool_call_id"])
    }
}
