package env

import com.TTT.Util.serialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** OLL-C: Ollama `/api/generate` accepts a top-level `suffix` (fill-in-the-middle). */
class OllamaSuffixDtoTest
{
    private val json = Json { isLenient = true }
    private fun obj(body: String) = json.parseToJsonElement(body) as JsonObject

    @Test
    fun generatedRequestSerializesSuffix()
    {
        val o = obj(serialize(GeneratedRequest(model = "m", prompt = "hi", suffix = "TWO")))
        assertEquals("TWO", o["suffix"]?.jsonPrimitive?.content)
    }

    @Test
    fun generatedRequestOmitsSuffixWhenNull()
    {
        val o = obj(serialize(GeneratedRequest(model = "m", prompt = "hi")))
        assertNull(o["suffix"])
    }
}
