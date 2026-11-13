package env

import com.TTT.Util.deserialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** OR-H: `stream_options.include_usage` requests a final usage chunk; the chunk now carries `usage`. */
class StreamOptionsDtoTest
{
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun streamOptionsSerializeOnRequest()
    {
        val req = OpenRouterChatRequest(
            model = "m",
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            stream = true,
            streamOptions = StreamOptions(includeUsage = true)
        )
        val obj = json.parseToJsonElement(json.encodeToString(OpenRouterChatRequest.serializer(), req)) as JsonObject
        val include = obj["stream_options"]?.jsonObject?.get("include_usage")
        assertEquals("true", (include as? JsonPrimitive)?.content)
    }

    @Test
    fun finalChunkDeserializesUsage()
    {
        val s = """{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3,"cost":0.001}}"""
        val chunk = deserialize<StreamingChunk>(s)
        assertNotNull(chunk)
        assertEquals(3, chunk.usage?.totalTokens)
        assertEquals(0.001, chunk.usage?.cost!!, 1e-9)
    }

    @Test
    fun chunkWithoutUsageIsNull()
    {
        val s = """{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"x"}}]}"""
        val chunk = deserialize<StreamingChunk>(s)
        assertNotNull(chunk)
        assertEquals(null, chunk.usage)
    }
}
