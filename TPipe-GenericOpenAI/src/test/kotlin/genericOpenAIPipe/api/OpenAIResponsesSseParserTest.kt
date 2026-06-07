package genericOpenAIPipe.api

import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import genericOpenAIPipe.env.OpenAIResponsesResponse
import genericOpenAIPipe.env.OpenAIResponsesStreamEvent
import genericOpenAIPipe.env.OpenAIResponsesUsage
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OpenAIResponsesSseParser].
 *
 * The OpenAI Responses API streams events as `event: <type>\ndata: <json>` SSE frames.
 * The parser must:
 *  - handle all standard event types defined by OpenAI,
 *  - tolerate both the `event:`+`data:` form and the bare `data:` form,
 *  - terminate the stream on `response.completed` / `response.failed` / `error`,
 *  - raise [P2PException] for `error` events.
 */
class OpenAIResponsesSseParserTest
{

//=========================================Response Lifecycle Events=========================================

    @Test
    fun testParsesResponseCreatedEvent()
    {
        val raw = """
            event: response.created
            data: {"type":"response.created","response":{"id":"resp_1","object":"response","status":"in_progress","model":"MiniMax-M2.7"}}
        """.trimIndent()

        val event = OpenAIResponsesSseParser.parseLine(raw)
        Assertions.assertNotNull(event)
        Assertions.assertTrue(event is OpenAIResponsesStreamEvent.ResponseCreated)
        Assertions.assertEquals("resp_1", (event as OpenAIResponsesStreamEvent.ResponseCreated).response.id)
    }

    @Test
    fun testParsesResponseInProgressEvent()
    {
        val raw = """
            event: response.in_progress
            data: {"type":"response.in_progress","response":{"id":"resp_1","object":"response","status":"in_progress"}}
        """.trimIndent()

        val event = OpenAIResponsesSseParser.parseLine(raw)
        Assertions.assertNotNull(event)
        Assertions.assertTrue(event is OpenAIResponsesStreamEvent.ResponseInProgress)
    }

    @Test
    fun testParsesResponseCompletedEvent()
    {
        val raw = """
            event: response.completed
            data: {"type":"response.completed","response":{"id":"resp_1","object":"response","status":"completed"}}
        """.trimIndent()

        val event = OpenAIResponsesSseParser.parseLine(raw)
        Assertions.assertNotNull(event)
        val completed = event as OpenAIResponsesStreamEvent.ResponseCompleted
        Assertions.assertTrue(completed.isTerminal)
    }

    @Test
    fun testParsesResponseFailedEvent()
    {
        val raw = """
            event: response.failed
            data: {"type":"response.failed","response":{"id":"resp_1","object":"response","status":"failed","error":{"type":"server_error","message":"boom"}}}
        """.trimIndent()

        val event = OpenAIResponsesSseParser.parseLine(raw)
        Assertions.assertNotNull(event)
        val failed = event as OpenAIResponsesStreamEvent.ResponseFailed
        Assertions.assertTrue(failed.isTerminal)
    }

//=========================================Output Text Delta Events=========================================

    @Test
    fun testParsesOutputTextDeltaEvent()
    {
        val raw = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hel"}
        """.trimIndent()

        val event = OpenAIResponsesSseParser.parseLine(raw)
        Assertions.assertNotNull(event)
        val delta = event as OpenAIResponsesStreamEvent.ResponseOutputTextDelta
        Assertions.assertEquals("Hel", delta.delta)
        Assertions.assertEquals("Hel", OpenAIResponsesSseParser.extractContentDelta(delta))
    }

    @Test
    fun testParsesOutputTextDoneEvent()
    {
        val raw = """
            event: response.output_text.done
            data: {"type":"response.output_text.done","item_id":"msg_1","output_index":0,"content_index":0,"text":"Hello"}
        """.trimIndent()

        val event = OpenAIResponsesSseParser.parseLine(raw)
        Assertions.assertNotNull(event)
        val done = event as OpenAIResponsesStreamEvent.ResponseOutputTextDone
        Assertions.assertEquals("Hello", done.text)
    }

//=========================================Function Call Events=========================================

    @Test
    fun testParsesFunctionCallArgumentsDelta()
    {
        val raw = """
            event: response.function_call_arguments.delta
            data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\"loc\":"}
        """.trimIndent()

        val event = OpenAIResponsesSseParser.parseLine(raw)
        Assertions.assertNotNull(event)
        val delta = event as OpenAIResponsesStreamEvent.ResponseFunctionCallArgumentsDelta
        Assertions.assertEquals("{\"loc\":", delta.delta)
    }

    @Test
    fun testParsesFunctionCallArgumentsDone()
    {
        val raw = """
            event: response.function_call_arguments.done
            data: {"type":"response.function_call_arguments.done","item_id":"fc_1","output_index":0,"arguments":"{\"location\":\"NYC\"}"}
        """.trimIndent()

        val event = OpenAIResponsesSseParser.parseLine(raw)
        Assertions.assertNotNull(event)
        val done = event as OpenAIResponsesStreamEvent.ResponseFunctionCallArgumentsDone
        Assertions.assertEquals("{\"location\":\"NYC\"}", done.arguments)
    }

//=========================================Error Event=========================================

    @Test
    fun testParsesErrorEventMapsToP2PException()
    {
        val raw = """
            event: error
            data: {"type":"error","code":"invalid_api_key","message":"bad key","param":null}
        """.trimIndent()

        val exception = Assertions.assertThrows(P2PException::class.java) { OpenAIResponsesSseParser.parseLine(raw) }
        Assertions.assertEquals(P2PError.auth, exception.errorType)
    }

    @Test
    fun testParsesErrorEventRateLimitMapsToTransport()
    {
        val raw = """
            event: error
            data: {"type":"error","code":"rate_limit_exceeded","message":"slow down","param":null}
        """.trimIndent()

        val exception = Assertions.assertThrows(P2PException::class.java) { OpenAIResponsesSseParser.parseLine(raw) }
        Assertions.assertEquals(P2PError.transport, exception.errorType)
    }

    @Test
    fun testParsesErrorEventInvalidRequestMapsToPrompt()
    {
        val raw = """
            event: error
            data: {"type":"error","code":"invalid_request","message":"bad request","param":"model"}
        """.trimIndent()

        val exception = Assertions.assertThrows(P2PException::class.java) { OpenAIResponsesSseParser.parseLine(raw) }
        Assertions.assertEquals(P2PError.prompt, exception.errorType)
    }

//=========================================Format Tolerance Tests=========================================

    @Test
    fun testParsesBareDataLineWithoutEventPrefix()
    {
        val raw = """data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hi"}"""
        val event = OpenAIResponsesSseParser.parseLine(raw)
        Assertions.assertNotNull(event)
        val delta = event as OpenAIResponsesStreamEvent.ResponseOutputTextDelta
        Assertions.assertEquals("Hi", delta.delta)
    }

    @Test
    fun testDoneSentinelIsToleratedAsNoOp()
    {
        val event = OpenAIResponsesSseParser.parseLine("data: [DONE]")
        Assertions.assertTrue(event is OpenAIResponsesStreamEvent.Unknown)
    }

    @Test
    fun testEmptyLineReturnsUnknown()
    {
        val event = OpenAIResponsesSseParser.parseLine("")
        Assertions.assertTrue(event is OpenAIResponsesStreamEvent.Unknown)
    }

    @Test
    fun testCommentLineReturnsUnknown()
    {
        val event = OpenAIResponsesSseParser.parseLine(": this is a comment")
        Assertions.assertTrue(event is OpenAIResponsesStreamEvent.Unknown)
    }

    @Test
    fun testUnknownEventTypeReturnsUnknown()
    {
        val raw = """
            event: response.made_up_event
            data: {"type":"response.made_up_event"}
        """.trimIndent()
        val event = OpenAIResponsesSseParser.parseLine(raw)
        Assertions.assertTrue(event is OpenAIResponsesStreamEvent.Unknown)
    }

    @Test
    fun testExtractContentDeltaOnNonTextEventReturnsNull()
    {
        val created = OpenAIResponsesStreamEvent.ResponseCreated(
            response = OpenAIResponsesResponse(
                id = "resp_1",
                objectType = "response",
                createdAt = 0L,
                model = "MiniMax-M2.7",
                status = "in_progress",
                output = emptyList(),
                usage = OpenAIResponsesUsage(0, 0, 0, null)
            )
        )
        Assertions.assertNull(OpenAIResponsesSseParser.extractContentDelta(created))
    }

    @Test
    fun testParsesReasoningTextDeltaEvent()
    {
        val raw = """event: response.reasoning_text.delta
data: {"type":"response.reasoning_text.delta","item_id":"rs_1","output_index":0,"content_index":0,"delta":"thinking..."}"""

        val event = OpenAIResponsesSseParser.parseLine(raw)
        Assertions.assertTrue(event is OpenAIResponsesStreamEvent.ResponseReasoningTextDelta)
        val delta = event as OpenAIResponsesStreamEvent.ResponseReasoningTextDelta
        Assertions.assertEquals("rs_1", delta.itemId)
        Assertions.assertEquals(0, delta.outputIndex)
        Assertions.assertEquals(0, delta.contentIndex)
        Assertions.assertEquals("thinking...", delta.delta)
    }

    @Test
    fun testParsesReasoningTextDoneEvent()
    {
        val raw = """event: response.reasoning_text.done
data: {"type":"response.reasoning_text.done","item_id":"rs_1","output_index":0,"content_index":0,"text":"the whole reasoning transcript"}"""

        val event = OpenAIResponsesSseParser.parseLine(raw)
        Assertions.assertTrue(event is OpenAIResponsesStreamEvent.ResponseReasoningTextDone)
        val done = event as OpenAIResponsesStreamEvent.ResponseReasoningTextDone
        Assertions.assertEquals("rs_1", done.itemId)
        Assertions.assertEquals(0, done.outputIndex)
        Assertions.assertEquals(0, done.contentIndex)
        Assertions.assertEquals("the whole reasoning transcript", done.text)
    }

    @Test
    fun testExtractTextDeltaReturnsBothOutputAndReasoningDeltas()
    {
        val outRaw = """event: response.output_text.delta
data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":1,"content_index":0,"delta":"answer"}"""
        val reasoningRaw = """event: response.reasoning_text.delta
data: {"type":"response.reasoning_text.delta","item_id":"rs_1","output_index":0,"content_index":0,"delta":"think"}"""

        val outEvent = OpenAIResponsesSseParser.parseLine(outRaw)
        val reasoningEvent = OpenAIResponsesSseParser.parseLine(reasoningRaw)

        Assertions.assertEquals("answer", OpenAIResponsesSseParser.extractTextDelta(outEvent))
        Assertions.assertEquals("think", OpenAIResponsesSseParser.extractTextDelta(reasoningEvent))
    }

}
