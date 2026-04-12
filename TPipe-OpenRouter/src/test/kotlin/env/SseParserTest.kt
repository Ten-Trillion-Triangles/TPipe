package env

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for SseParser.
 */
class SseParserTest
{
    //=========================================parseLine Tests========================================================

    @Test
    fun `parseLine returns Empty for blank line`()
    {
        assertIs<SseParser.SseLine.Empty>(SseParser.parseLine(""))
        // With trim(), whitespace-only lines are also Empty since trim() makes them ""
        assertIs<SseParser.SseLine.Empty>(SseParser.parseLine("   "))
        assertIs<SseParser.SseLine.Empty>(SseParser.parseLine("\t"))
        assertIs<SseParser.SseLine.Empty>(SseParser.parseLine("\n"))
    }

    @Test
    fun `parseLine returns Comment for colon-prefixed lines`()
    {
        assertIs<SseParser.SseLine.Comment>(SseParser.parseLine(": this is a comment"))
        assertIs<SseParser.SseLine.Comment>(SseParser.parseLine(":"))
        assertIs<SseParser.SseLine.Comment>(SseParser.parseLine(": single char comment"))
    }

    @Test
    fun `parseLine returns Done for terminal signal`()
    {
        assertIs<SseParser.SseLine.Done>(SseParser.parseLine("data: [DONE]"))
    }

    @Test
    fun `parseLine returns Data for data lines with JSON`()
    {
        val result = SseParser.parseLine("data: {\"id\":\"test\"}")
        assertIs<SseParser.SseLine.Data>(result)
        assertEquals("{\"id\":\"test\"}", result.content)
    }

    @Test
    fun `parseLine returns Invalid for unrecognized lines`()
    {
        assertIs<SseParser.SseLine.Invalid>(SseParser.parseLine("not a valid sse line"))
        assertIs<SseParser.SseLine.Invalid>(SseParser.parseLine("datatest"))
        assertIs<SseParser.SseLine.Invalid>(SseParser.parseLine("data : json"))
    }

    //=========================================parseChunk Tests=======================================================

    @Test
    fun `parseChunk parses valid StreamingChunk JSON`()
    {
        val json = """{"id":"chatcmpl-123","object":"chat.completion.chunk","created":1677652288,"model":"deepseek/deepseek-chat-v3-0324","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}"""

        val chunk = SseParser.parseChunk(json)

        assertTrue(chunk != null)
        assertEquals("chatcmpl-123", chunk!!.id)
        assertEquals("deepseek/deepseek-chat-v3-0324", chunk.model)
        assertEquals(1, chunk.choices.size)
        assertEquals("Hello", chunk.choices[0].delta.content)
    }

    @Test
    fun `parseChunk returns null for invalid JSON`()
    {
        assertNull(SseParser.parseChunk("not json"))
        assertNull(SseParser.parseChunk(""))
        assertNull(SseParser.parseChunk("{\"id\": invalid}"))
    }

    //=========================================extractContent Tests====================================================

    @Test
    fun `extractContent returns delta content from chunk`()
    {
        val json = """{"id":"chatcmpl-123","object":"chat.completion.chunk","created":1677652288,"model":"test","choices":[{"index":0,"delta":{"content":"World"},"finish_reason":null}]}"""

        val chunk = SseParser.parseChunk(json)!!
        assertEquals("World", SseParser.extractContent(chunk))
    }

    @Test
    fun `extractContent returns empty string when no delta content`()
    {
        val json = """{"id":"chatcmpl-123","object":"chat.completion.chunk","created":1677652288,"model":"test","choices":[{"index":0,"delta":{},"finish_reason":null}]}"""

        val chunk = SseParser.parseChunk(json)!!
        assertEquals("", SseParser.extractContent(chunk))
    }

    //=========================================extractContentFromLine Tests===========================================

    @Test
    fun `extractContentFromLine extracts content from data lines`()
    {
        val dataLine = "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":123,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"}}]}"
        assertEquals("Hi", SseParser.extractContentFromLine(dataLine))
    }

    @Test
    fun `extractContentFromLine returns null for non-data lines`()
    {
        assertNull(SseParser.extractContentFromLine(""))
        assertNull(SseParser.extractContentFromLine(": comment"))
        assertNull(SseParser.extractContentFromLine("data: [DONE]"))
        assertNull(SseParser.extractContentFromLine("invalid line"))
    }

    //=========================================iterateLines Tests=====================================================

    @Test
    fun `iterateLines accumulates content and calls callbacks`()
    {
        val line1 = """data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"content":"Hello"}}]}"""
        val line2 = """data: {"id":"2","object":"chat.completion.chunk","created":2,"model":"test","choices":[{"index":0,"delta":{"content":" World"}}]}"""

        val lines = listOf(
            ": comment line",
            "",
            line1,
            line2,
            "data: [DONE]"
        ).iterator()

        val chunks = mutableListOf<String>()
        var doneCalled = false

        val result = SseParser.iterateLines(
            lines = lines,
            onChunk = { chunks.add(it) },
            onDone = { doneCalled = true }
        )

        assertEquals("Hello World", result)
        assertEquals(listOf("Hello", " World"), chunks)
        assertTrue(doneCalled)
    }

    @Test
    fun `iterateLines handles empty iterator`()
    {
        val lines = emptyList<String>().iterator()
        val result = SseParser.iterateLines(lines)
        assertEquals("", result)
    }

    @Test
    fun `iterateLines handles malformed data lines gracefully`()
    {
        val lineA = """data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"content":"A"}}]}"""
        val malformedLine = "data: not valid json at all"
        val lineB = """data: {"id":"3","object":"chat.completion.chunk","created":3,"model":"test","choices":[{"index":0,"delta":{"content":"B"}}]}"""

        val lines = listOf(
            lineA,
            malformedLine,
            lineB,
            "data: [DONE]"
        ).iterator()

        val chunks = mutableListOf<String>()
        val result = SseParser.iterateLines(
            lines = lines,
            onChunk = { chunks.add(it) }
        )

        assertEquals(listOf("A", "B"), chunks)
        assertEquals("AB", result)
    }

    @Test
    fun `iterateLines processes multiple chunks in sequence`()
    {
        val lines = listOf(
            """data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"t","choices":[{"index":0,"delta":{"content":"First"}}]}""",
            """data: {"id":"2","object":"chat.completion.chunk","created":2,"model":"t","choices":[{"index":0,"delta":{"content":"Second"}}]}""",
            """data: {"id":"3","object":"chat.completion.chunk","created":3,"model":"t","choices":[{"index":0,"delta":{"content":"Third"}}]}""",
            "data: [DONE]"
        ).iterator()

        val result = SseParser.iterateLines(lines)
        assertEquals("FirstSecondThird", result)
    }

    @Test
    fun `iterateLines skips comment and empty lines`()
    {
        val contentLine = """data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"t","choices":[{"index":0,"delta":{"content":"Content"}}]}"""

        val lines = listOf(
            ": this is a comment",
            "",
            "   ",
            contentLine,
            "data: [DONE]"
        ).iterator()

        val result = SseParser.iterateLines(lines)
        assertEquals("Content", result)
    }
}
