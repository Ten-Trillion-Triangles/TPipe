package env

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** OR-F: `structured_outputs` + `verbosity` are dead in the current spec; structured output
 *  is folded into `response_format` json_schema. */
class RequestDeadFieldsDtoTest
{
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun requestOmitsDeadFieldsAndKeepsResponseFormat()
    {
        val schema = buildJsonObject { put("type", "object") }
        val req = OpenRouterChatRequest(
            model = "m",
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            responseFormat = ResponseFormat(type = "json_schema", jsonSchema = schema)
        )
        val encoded = json.encodeToString(OpenRouterChatRequest.serializer(), req)
        val obj = json.parseToJsonElement(encoded) as JsonObject
        assertNull(obj["structured_outputs"], "structured_outputs must no longer serialize")
        assertNull(obj["verbosity"], "verbosity must no longer serialize")
        assertNotNull(obj["response_format"], "response_format carries structured output")
    }
}
