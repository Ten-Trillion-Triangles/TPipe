package env

import com.TTT.Util.serialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** OLL-D: Ollama added `draft_num_predict` (speculative-decoding draft tokens) to Options. */
class OllamaDraftNumPredictDtoTest
{
    private val json = Json { isLenient = true }
    private fun optionsObj(body: String) =
        (json.parseToJsonElement(body) as JsonObject)["options"] as? JsonObject

    @Test
    fun optionsSerializeDraftNumPredict()
    {
        val o = optionsObj(serialize(GeneratedRequest(model = "m", prompt = "hi", options = OllamaOptions(draftNumPredict = 4))))
        assertEquals("4", o?.get("draft_num_predict")?.jsonPrimitive?.content)
    }

    @Test
    fun optionsOmitDraftNumPredictWhenNull()
    {
        val o = optionsObj(serialize(GeneratedRequest(model = "m", prompt = "hi", options = OllamaOptions())))
        assertNull(o?.get("draft_num_predict"))
    }
}
