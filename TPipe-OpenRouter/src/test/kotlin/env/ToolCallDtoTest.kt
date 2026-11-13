package env

import com.TTT.Util.deserialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** OR-B: OpenRouter returns `tool_calls` on both non-stream messages and streaming deltas. */
class ToolCallDtoTest
{
    @Test
    fun responseMessageToolCallsDeserialize()
    {
        val json = """{"id":"c1","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{}"}}]}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
        val r = deserialize<OpenRouterChatResponse>(json)
        assertNotNull(r)
        val tc = r.choices[0].message.toolCalls
        assertNotNull(tc, "tool_calls must deserialize on the response message")
        assertEquals("call_1", tc!![0].id)
        assertEquals("function", tc[0].type)
        assertEquals("get_weather", tc[0].function?.name)
    }

    @Test
    fun streamDeltaToolCallsDeserialize()
    {
        val json = """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{}"}}]}}]}"""
        val chunk = deserialize<StreamingChunk>(json)
        assertNotNull(chunk)
        val tc = chunk.choices[0].delta.toolCalls
        assertNotNull(tc, "tool_calls must deserialize on the stream delta")
        assertEquals("get_weather", tc!![0].function?.name)
    }

    @Test
    fun plainResponseOmitsToolCalls()
    {
        val json = """{"id":"c1","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi"}}]}"""
        val r = deserialize<OpenRouterChatResponse>(json)
        assertNotNull(r)
        assertEquals(null, r.choices[0].message.toolCalls)
    }
}
