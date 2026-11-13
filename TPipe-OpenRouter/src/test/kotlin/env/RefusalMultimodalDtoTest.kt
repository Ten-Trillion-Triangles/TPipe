package env

import com.TTT.Util.deserialize
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** OR-I: OpenRouter messages/deltas can carry `refusal`, `audio`, and `images`. */
class RefusalMultimodalDtoTest
{
    @Test
    fun messageRefusalAudioImagesDeserialize()
    {
        val json = """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"","refusal":"unsafe","audio":{"data":"BASE64","expires_at":123,"id":"a1","transcript":"hi"},"images":{"image":"B64IMG"}}}]}"""
        val r = deserialize<OpenRouterChatResponse>(json)
        assertNotNull(r)
        val msg = r.choices[0].message
        assertEquals("unsafe", msg.refusal)
        assertEquals("BASE64", msg.audio?.data)
        assertEquals(123L, msg.audio?.expiresAt)
        assertNotNull(msg.images)
        assertEquals("B64IMG", msg.images.get("image")?.let { (it as? JsonPrimitive)?.content })
    }

    @Test
    fun deltaRefusalAudioDeserialize()
    {
        val json = """{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":"assistant","refusal":"stop","audio":{"data":"B64"}}}]}"""
        val c = deserialize<StreamingChunk>(json)
        assertNotNull(c)
        assertEquals("stop", c.choices[0].delta.refusal)
        assertEquals("B64", c.choices[0].delta.audio?.data)
    }

    @Test
    fun plainMessageOmitsRefusalAudioImages()
    {
        val json = """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}"""
        val r = deserialize<OpenRouterChatResponse>(json)
        assertNotNull(r)
        assertEquals(null, r.choices[0].message.refusal)
        assertEquals(null, r.choices[0].message.audio)
        assertEquals(null, r.choices[0].message.images)
    }
}
