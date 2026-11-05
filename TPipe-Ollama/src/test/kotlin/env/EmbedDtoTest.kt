package env

import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** OLL-G: Ollama current embeddings endpoint `/api/embed` DTOs round-trip. */
class EmbedDtoTest
{
    private val json = Json { isLenient = true }
    private fun obj(body: String) = json.parseToJsonElement(body) as JsonObject

    @Test
    fun embedRequestSerializes()
    {
        val o = obj(serialize(EmbedRequest(model = "m", input = JsonPrimitive("hi"), dimensions = 512)))
        assertEquals("m", o["model"]?.jsonPrimitive?.content)
        assertEquals("hi", o["input"]?.jsonPrimitive?.content)
        assertEquals("512", o["dimensions"]?.jsonPrimitive?.content)
    }

    @Test
    fun embedResponseDeserializes()
    {
        val line = """{"model":"m","embeddings":[0.1,0.2,0.3],"total_duration":100,"load_duration":5,"prompt_eval_count":3}"""
        val r = deserialize<EmbedResponse>(line)
        assertNotNull(r)
        assertEquals(3, r.promptEvalCount)
        assertNotNull(r.embeddings, "embeddings should deserialize")
    }

    @Test
    fun embedRequestOmitsOptionalWhenNull()
    {
        val o = obj(serialize(EmbedRequest(model = "m")))
        assertNull(o["input"])
        assertNull(o["dimensions"])
    }
}
