package env

import com.TTT.Util.deserialize
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** OR-J: `/models` catalog entries expose context_length + supported_parameters + pricing. */
class ModelCatalogDtoTest
{
    @Test
    fun modelEntryDeserializes()
    {
        val s = """{"data":{"id":"anthropic/claude-3-5-sonnet","name":"Claude 3.5 Sonnet","context_length":200000,"supported_parameters":["temperature","max_tokens"],"pricing":{"prompt":"0.003","completion":"0.015"}}}"""
        val r = deserialize<OpenRouterModelResponse>(s)
        assertNotNull(r)
        assertEquals(200000, r.data.contextLength)
        assertEquals(listOf("temperature", "max_tokens"), r.data.supportedParameters)
        assertEquals("0.003", r.data.pricing?.get("prompt")?.let { (it as? JsonPrimitive)?.content })
    }

    @Test
    fun modelsListDeserializes()
    {
        val s = """{"data":[{"id":"a","context_length":8192,"supported_parameters":["top_p"]},{"id":"b","context_length":128000}],"total_count":2}"""
        val r = deserialize<OpenRouterModelsResponse>(s)
        assertNotNull(r)
        assertEquals(2, r.data.size)
        assertEquals(8192, r.data[0].contextLength)
        assertEquals(128000, r.data[1].contextLength)
        assertEquals(listOf("top_p"), r.data[0].supportedParameters)
    }
}
