package env

import com.TTT.Util.serialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** OLL-H: Ollama now exposes `logprobs` / `top_logprobs` on generate + chat. */
class LogprobsDtoTest
{
    private val json = Json { isLenient = true }
    private fun obj(body: String) = json.parseToJsonElement(body) as JsonObject

    @Test
    fun chatRequestSerializesLogprobs()
    {
        val r = ChatRequest(model = "m", messages = listOf(OllamaMessage(role = "user", content = "hi")), logprobs = true, topLogprobs = 5)
        val o = obj(serialize(r))
        assertEquals("true", o["logprobs"]?.jsonPrimitive?.content)
        assertEquals("5", o["top_logprobs"]?.jsonPrimitive?.content)
    }

    @Test
    fun generatedRequestOmitsLogprobsWhenNull()
    {
        val o = obj(serialize(GeneratedRequest(model = "m", prompt = "hi")))
        assertNull(o["logprobs"])
        assertNull(o["top_logprobs"])
    }
}
