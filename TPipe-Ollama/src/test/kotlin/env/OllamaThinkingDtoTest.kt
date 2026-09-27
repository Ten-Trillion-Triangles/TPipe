package env

import com.TTT.Util.deserialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * DTO tests proving the Ollama `thinking` wire field deserializes into the
 * stream DTOs (kotlinx.serialization) so thinking-model trace tokens are
 * captured instead of dropped.
 */
class OllamaThinkingDtoTest
{
    @Test
    fun chatStreamChunkDeserializesThinkingField()
    {
        val line = """{"model":"test","created_at":"2026-09-27T00:00:00Z","message":{"role":"assistant","content":"","thinking":"Considering the options"},"done":false}"""

        val chunk = deserialize<ChatResponse>(line)

        assertNotNull(chunk)
        assertEquals("Considering the options", chunk.message?.thinking)
    }

    @Test
    fun chatStreamChunkWithoutThinkingDefaultsToNull()
    {
        val line = """{"model":"test","created_at":"2026-09-27T00:00:00Z","message":{"role":"assistant","content":"hi"},"done":true}"""

        val chunk = deserialize<ChatResponse>(line)

        assertNotNull(chunk)
        assertNull(chunk.message?.thinking)
    }

    @Test
    fun generateStreamChunkDeserializesThinkingField()
    {
        val line = """{"model":"test","created_at":"2026-09-27T00:00:00Z","response":"","thinking":"tracing","done":false}"""

        val chunk = deserialize<GeneratedResponse>(line)

        assertNotNull(chunk)
        assertEquals("tracing", chunk.thinking)
    }

    @Test
    fun generateStreamChunkWithoutThinkingDefaultsToNull()
    {
        val line = """{"model":"test","created_at":"2026-09-27T00:00:00Z","response":"hi","done":true}"""

        val chunk = deserialize<GeneratedResponse>(line)

        assertNotNull(chunk)
        assertNull(chunk.thinking)
    }
}
