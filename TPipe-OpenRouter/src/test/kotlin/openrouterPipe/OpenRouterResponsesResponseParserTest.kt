package openrouterPipe

import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import env.OpenRouterResponsesOutputItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Static tests for [OpenRouterResponsesResponseParser]: error-envelope mapping,
 * failed-status mapping and the [OpenRouterResponsesResponseParser.flatten]
 * projection of text / reasoning / tool calls / usage onto the P2P surface.
 */
class OpenRouterResponsesResponseParserTest
{
    private val parser = OpenRouterResponsesResponseParser()

    private val happyBody = """
        {
          "id": "resp_1", "object": "response", "created_at": 1,
          "status": "completed", "model": "openai/gpt-4.1",
          "output": [
            {"type":"reasoning","id":"r1","summary":[{"type":"summary_text","text":"thinking"}],
             "content":[{"type":"reasoning_text","text":"the whole chain of thought"}]},
            {"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}
          ],
          "usage":{"input_tokens":3,"output_tokens":4,"total_tokens":7,"output_tokens_details":{"reasoning_tokens":2}}
        }
    """.trimIndent()

    //=========================================Happy-path projection========================================

    @Test
    fun testParseHappyPathReturnsResponse()
    {
        val response = parser.parse(happyBody)
        assertEquals("resp_1", response.id)
        assertEquals("completed", response.status)
    }

    @Test
    fun testFlattenConcatenatesOutputTextParts()
    {
        val response = parser.parse(happyBody)
        val flat = parser.flatten(response)
        assertEquals("Hello", flat.text)
        assertEquals("stop", flat.finishReason)
    }

    @Test
    fun testFlattenConcatsMultipleOutputTextParts()
    {
        val body = """
            {"id":"r","object":"response","created_at":1,"status":"completed","model":"m",
             "output":[{"type":"message","id":"m1","role":"assistant",
                "content":[{"type":"output_text","text":"Hel"},{"type":"output_text","text":"lo"}]}]}
        """.trimIndent()
        val flat = parser.flatten(parser.parse(body))
        assertEquals("Hello", flat.text)
    }

    @Test
    fun testFlattenSurfacesReasoningFromReasoningItems()
    {
        val response = parser.parse(happyBody)
        val flat = parser.flatten(response)
        // reasoning_text + summary_text are both projected into the reasoning transcript.
        assertTrue(flat.reasoning.contains("the whole chain of thought"))
        assertTrue(flat.reasoning.contains("thinking"))
    }

    @Test
    fun testFlattenExposesFunctionCallsForPcp()
    {
        val body = """
            {"id":"r","object":"response","created_at":1,"status":"completed","model":"m",
             "output":[
               {"type":"function_call","id":"f1","call_id":"call_1","name":"get_weather",
                "arguments":"{\"loc\":\"NYC\"}","status":"completed"},
               {"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"done"}]}
             ]}
        """.trimIndent()
        val flat = parser.flatten(parser.parse(body))
        assertEquals(1, flat.functionCalls.size)
        val call = flat.functionCalls[0]
        assertEquals("get_weather", call.name)
        assertEquals("call_1", call.callId)
        assertEquals("done", flat.text)
    }

    @Test
    fun testFlattenSurfacesUsage()
    {
        val flat = parser.flatten(parser.parse(happyBody))
        assertEquals(7, flat.usage?.totalTokens)
        assertEquals(2, flat.usage?.outputTokensDetails?.reasoningTokens)
    }

    //=========================================Refusal + status mapping========================================

    @Test
    fun testRefusalContentClearsTextAndForcesRefusalFinishReason()
    {
        val body = """
            {"id":"r","object":"response","created_at":1,"status":"completed","model":"m",
             "output":[{"type":"message","id":"m1","role":"assistant",
                "content":[{"type":"output_text","text":""},{"type":"refusal","refusal":"cannot"}]}]}
        """.trimIndent()
        val flat = parser.flatten(parser.parse(body))
        assertEquals("", flat.text)
        assertEquals("refusal", flat.finishReason)
    }

    @Test
    fun testIncompleteStatusMapsToIncompleteFinishReason()
    {
        val body = """
            {"id":"r","object":"response","created_at":1,"status":"incomplete","model":"m",
             "output":[{"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"cut off"}]}]}
        """.trimIndent()
        val flat = parser.flatten(parser.parse(body))
        assertEquals("incomplete", flat.finishReason)
    }

    //=========================================Error handling===================================================

    @Test
    fun testErrorEnvelopeThrowsP2PException()
    {
        val body = """{"error":{"message":"bad key","code":401,"type":"auth_error"}}"""
        val ex = try
        {
            parser.parse(body)
            error("expected P2PException") as Nothing
        }
        catch(e: P2PException)
        {
            e
        }
        assertNotNull(ex)
        assertEquals(P2PError.auth, ex.errorType)
    }

    @Test
    fun testRateLimitErrorEnvelopeMapsToTransport()
    {
        val body = """{"error":{"message":"slow down","code":429,"metadata":{"limit_source":"rate_limit"}}}"""
        val ex = try { parser.parse(body); error("expected P2PException") as Nothing }
        catch(e: P2PException) { e }
        assertEquals(P2PError.transport, ex.errorType)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testFailedStatusWithEmbeddedErrorThrowsTransport()
    {
        val body = """
            {"id":"r","object":"response","created_at":1,"status":"failed","model":"m",
             "output":[],
             "error":{"code":"server_error","message":"upstream blew up"}}
        """.trimIndent()
        val ex = try { parser.parse(body); error("expected P2PException") as Nothing }
        catch(e: P2PException) { e }
        assertEquals(P2PError.transport, ex.errorType)
    }
}
