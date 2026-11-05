package env

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** OR-K: remaining P2 request fields (image_config, prediction, prompt_cache_*, metadata, models, stop_server_tools_when). */
class RequestExtraFieldsDtoTest
{
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun extraRequestFieldsRoundTrip()
    {
        val req = OpenRouterChatRequest(
            model = "m",
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            imageConfig = buildJsonObject { put("size", "1024x1024") },
            prediction = buildJsonObject { put("content", "the cat") },
            promptCacheKey = "key-1",
            promptCacheOptions = buildJsonObject { put("type", "ephemeral") },
            metadata = buildJsonObject { put("org", "ttt") },
            models = listOf("fallback/a", "fallback/b"),
            stopServerToolsWhen = buildJsonObject { put("step_count_is", 5) }
        )
        val s = json.encodeToString(OpenRouterChatRequest.serializer(), req)
        val out = json.decodeFromString(OpenRouterChatRequest.serializer(), s)
        assertEquals("1024x1024", (out.imageConfig?.get("size") as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("the cat", (out.prediction?.get("content") as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("key-1", out.promptCacheKey)
        assertEquals(listOf("fallback/a", "fallback/b"), out.models)
        assertEquals("ephemeral", (out.promptCacheOptions?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("5", (out.stopServerToolsWhen?.get("step_count_is") as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun extraFieldsOmittedWhenNull()
    {
        val req = OpenRouterChatRequest(model = "m", messages = listOf(ChatMessage(role = "user", content = "hi")))
        val obj = json.parseToJsonElement(json.encodeToString(OpenRouterChatRequest.serializer(), req)) as JsonObject
        assertEquals(false, (obj.keys.contains("image_config") || obj.keys.contains("prompt_cache_key") || obj.keys.contains("models")))
    }
}
