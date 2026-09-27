package openrouterPipe

import com.TTT.Util.ReasoningStream
import com.TTT.Util.deserialize
import env.StreamingChunk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * TDD coverage for OpenRouter reasoning-stream delivery.
 *
 * Two seams:
 * 1. DTO seam — a chunk JSON carrying `delta.reasoning` and
 *    `delta.reasoning_details` deserializes into the new [env.DeltaMessage]
 *    fields (mirrors the SseParserTest.kt fixture style).
 * 2. Stream seam — reasoning chunks followed by content chunks drive
 *    [OpenRouterPipe.handleStreamDataChunk] against a real streaming
 *    callback, verifying the base reasoning hooks deliver
 *    OPEN_TAG + reasoning text + CLOSE_TAG + content, and that
 *    `setStreamModelReasoning(false)` suppresses every reasoning byte.
 */
class OpenRouterReasoningStreamTest
{

//=========================================Fixtures===================================================================

    private val reasoningChunkJson =
        """{"id":"chatcmpl-r1","object":"chat.completion.chunk","created":1700000000,"model":"test/reasoner","choices":[{"index":0,"delta":{"reasoning":"Let me think step by step"},"finish_reason":null}]}"""

    private val reasoningDetailsChunkJson =
        """{"id":"chatcmpl-r2","object":"chat.completion.chunk","created":1700000000,"model":"test/reasoner","choices":[{"index":0,"delta":{"reasoning_details":[{"type":"thinking","text":"step one","summary":"s1","id":"detail-1","format":"text","index":0,"signature":"sig-1"},{"type":"summary","summary":"wrapped up","id":"detail-2","index":1}]},"finish_reason":null}]}"""

    private val reasoningAndDetailsChunkJson =
        """{"id":"chatcmpl-r3","object":"chat.completion.chunk","created":1700000000,"model":"test/reasoner","choices":[{"index":0,"delta":{"reasoning":"plain reasoning","reasoning_details":[{"type":"thinking","text":"ignored when plain present"}]},"finish_reason":null}]}"""

    private val contentChunkJson =
        """{"id":"chatcmpl-c1","object":"chat.completion.chunk","created":1700000000,"model":"test/reasoner","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}"""

    private val combinedChunkJson =
        """{"id":"chatcmpl-x1","object":"chat.completion.chunk","created":1700000000,"model":"test/reasoner","choices":[{"index":0,"delta":{"reasoning":"quick think","content":"Hi"},"finish_reason":null}]}"""

    /**
     * Captures everything that reaches a streaming callback plus the
     * visible-text accumulator the pipe keeps for its return value.
     */
    private class StreamCapture
    {
        val events = mutableListOf<String>()
        val visibleText = StringBuilder()
        val rawStream: String get() = events.joinToString("")
    }

    /**
     * Drives a real [OpenRouterPipe] streaming callback through the
     * given chunk JSONs, deserializing each exactly as executeStreaming does.
     *
     * @param chunkJsons Canned SSE data-payload JSONs in stream order
     * @param configure Optional per-test configuration (e.g. setStreamModelReasoning)
     * @return The capture of callback events and visible text
     */
    private suspend fun driveStream(chunkJsons: List<String>, configure: (OpenRouterPipe) -> Unit = {}): StreamCapture
    {
        val capture = StreamCapture()
        val pipe = OpenRouterPipe().setApiKey("test-key")
        pipe.setStreamingCallback { chunk -> capture.events.add(chunk) }
        configure(pipe)

        chunkJsons.forEach { json ->
            val chunk = deserialize<StreamingChunk>(json)
            assertNotNull(chunk, "chunk deserialization failed: $json")
            pipe.handleStreamDataChunk(chunk, capture.visibleText)
        }
        return capture
    }

//=========================================DTO Seam Tests============================================================

    @Test
    fun testDeltaMessageDeserializesReasoningAndReasoningDetails()
    {
        val chunk = deserialize<StreamingChunk>(reasoningAndDetailsChunkJson)
        assertNotNull(chunk)

        val delta = chunk!!.choices[0].delta
        assertEquals("plain reasoning", delta.reasoning)
        assertNotNull(delta.reasoningDetails)

        val details = delta.reasoningDetails!!
        assertEquals(1, details.size)
        assertEquals("thinking", details[0].type)
        assertEquals("ignored when plain present", details[0].text)
        assertEquals(null, details[0].summary)
        assertEquals(null, details[0].id)
        assertEquals(null, details[0].format)
        assertEquals(null, details[0].index)
        assertEquals(null, details[0].signature)
    }

    @Test
    fun testDeltaMessageDeserializesFullStructuredDetails()
    {
        val chunk = deserialize<StreamingChunk>(reasoningDetailsChunkJson)
        assertNotNull(chunk)

        val delta = chunk!!.choices[0].delta
        assertNull(delta.reasoning)
        assertNotNull(delta.reasoningDetails)

        val details = delta.reasoningDetails!!
        assertEquals(2, details.size)
        assertEquals("thinking", details[0].type)
        assertEquals("step one", details[0].text)
        assertEquals("s1", details[0].summary)
        assertEquals("detail-1", details[0].id)
        assertEquals("text", details[0].format)
        assertEquals(0, details[0].index)
        assertEquals("sig-1", details[0].signature)
        assertEquals("summary", details[1].type)
        assertNull(details[1].text)
        assertEquals("wrapped up", details[1].summary)
        assertEquals("detail-2", details[1].id)
        assertEquals(1, details[1].index)
        assertNull(details[1].signature)
    }

    @Test
    fun testDeltaMessageReasoningFieldsDefaultToNull()
    {
        val chunk = deserialize<StreamingChunk>(contentChunkJson)
        assertNotNull(chunk)

        val delta = chunk!!.choices[0].delta
        assertEquals("Hello", delta.content)
        assertNull(delta.reasoning)
        assertNull(delta.reasoningDetails)
    }

//=========================================Stream Seam Tests==========================================================

    @Test
    fun testReasoningThenContentEmitsSegmentMarkersAndText() = runBlocking<Unit>
    {
        val capture = driveStream(listOf(reasoningChunkJson, contentChunkJson))

        val expected = ReasoningStream.OPEN_TAG +
            "Let me think step by step" +
            ReasoningStream.CLOSE_TAG +
            "Hello"
        assertEquals(expected, capture.rawStream)
        assertEquals("Hello", capture.visibleText.toString())
    }

    @Test
    fun testSecondReasoningChunkDoesNotReopenSegment() = runBlocking<Unit>
    {
        val capture = driveStream(listOf(reasoningChunkJson, reasoningChunkJson, contentChunkJson))

        assertEquals(1, capture.events.count { it == ReasoningStream.OPEN_TAG })
        assertEquals(1, capture.events.count { it == ReasoningStream.CLOSE_TAG })
        assertEquals(2, capture.events.count { it == "Let me think step by step" })
        assertEquals("Hello", capture.visibleText.toString())
    }

    @Test
    fun testStructuredDetailsFallBackToTextAndSummary() = runBlocking<Unit>
    {
        val capture = driveStream(listOf(reasoningDetailsChunkJson, contentChunkJson))

        // Entry 1 contributes its `text`; entry 2 has no text so it
        // contributes its `summary`.
        val expected = ReasoningStream.OPEN_TAG +
            "step onewrapped up" +
            ReasoningStream.CLOSE_TAG +
            "Hello"
        assertEquals(expected, capture.rawStream)
        assertEquals("Hello", capture.visibleText.toString())
    }

    @Test
    fun testPlainReasoningWinsOverStructuredDetails() = runBlocking<Unit>
    {
        val capture = driveStream(listOf(reasoningAndDetailsChunkJson, contentChunkJson))

        val expected = ReasoningStream.OPEN_TAG +
            "plain reasoning" +
            ReasoningStream.CLOSE_TAG +
            "Hello"
        assertEquals(expected, capture.rawStream)
    }

    @Test
    fun testCombinedReasoningAndContentChunkClosesSegmentBeforeContent() = runBlocking<Unit>
    {
        val capture = driveStream(listOf(combinedChunkJson))

        val expected = ReasoningStream.OPEN_TAG + "quick think" + ReasoningStream.CLOSE_TAG + "Hi"
        assertEquals(expected, capture.rawStream)
        assertEquals("Hi", capture.visibleText.toString())
    }

    @Test
    fun testStreamModelReasoningDisabledEmitsNoReasoningBytes() = runBlocking<Unit>
    {
        val capture = driveStream(
            listOf(reasoningChunkJson, reasoningDetailsChunkJson, contentChunkJson))
        { pipe -> pipe.setStreamModelReasoning(false) }

        assertEquals("Hello", capture.rawStream)
        assertFalse(capture.rawStream.contains(ReasoningStream.OPEN_TAG))
        assertFalse(capture.rawStream.contains(ReasoningStream.CLOSE_TAG))
        assertFalse(capture.rawStream.contains("Let me think"))
        assertFalse(capture.rawStream.contains("step one"))
        assertEquals("Hello", capture.visibleText.toString())
    }
}
