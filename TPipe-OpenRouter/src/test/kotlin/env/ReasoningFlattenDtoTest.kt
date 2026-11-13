package env

import com.TTT.Util.deserialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** OR-C: non-stream responses carry `reasoning` / `reasoning_details` that must not be dropped. */
class ReasoningFlattenDtoTest
{
    @Test
    fun messageReasoningDeserializes()
    {
        val json = """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning":"step1 step2"}}]}"""
        val r = deserialize<OpenRouterChatResponse>(json)
        assertNotNull(r)
        assertEquals("step1 step2", r.choices[0].message.reasoning)
    }

    @Test
    fun messageReasoningDetailsDeserializes()
    {
        val json = """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_details":[{"type":"thinking","text":"deep"},{"type":"summary","summary":"short"}]}}]}"""
        val r = deserialize<OpenRouterChatResponse>(json)
        assertNotNull(r)
        val details = r.choices[0].message.reasoningDetails
        assertNotNull(details, "reasoning_details must deserialize")
        assertEquals("deep", details!![0].text)
        assertEquals("short", details[1].summary)
    }

    @Test
    fun plainMessageOmitsReasoning()
    {
        val json = """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi"}}]}"""
        val r = deserialize<OpenRouterChatResponse>(json)
        assertNotNull(r)
        assertEquals(null, r.choices[0].message.reasoning)
        assertEquals(null, r.choices[0].message.reasoningDetails)
    }
}
