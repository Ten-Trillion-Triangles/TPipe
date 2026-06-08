package genericOpenAIPipe.api

import genericOpenAIPipe.GenericOpenAIPipe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.client.request.HttpRequestData
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions
import kotlinx.coroutines.runBlocking

/**
 * Pipe-dispatch + Ktor [MockEngine] integration tests for the OpenAI Responses
 * API mode. Verifies:
 *  - the pipe hits `/v1/responses` (not `/v1/chat/completions`),
 *  - the pipe sends `Authorization: Bearer ...` for the Responses mode,
 *  - a canned non-streaming Responses body is parsed and returned,
 *  - a canned streaming Responses SSE sequence terminates on
 *    `response.completed` and accumulates text.
 */
class OpenAIResponsesPipeDispatchTest
{

//=========================================Endpoint + Auth Headers=========================================

    @Test
    fun testGetEndpointReturnsResponsesPath()
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://example.com/v1")
            .setApiMode(ApiMode.OpenAIResponses)

        val endpoint = pipe.internalGetEndpointForTest()
        Assertions.assertEquals("/responses", endpoint)
    }

    @Test
    fun testGetAuthHeadersReturnsBearer()
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key-xyz")
            .setBaseUrl("https://example.com/v1")
            .setApiMode(ApiMode.OpenAIResponses)

        val headers = pipe.internalGetAuthHeadersForTest()
        Assertions.assertEquals("Bearer test-key-xyz", headers["Authorization"])
        Assertions.assertTrue(!headers.containsKey("x-api-key"))
    }

    @Test
    fun testGetEndpointOpenAICompletionsUnchanged()
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://example.com/v1")
            .setApiMode(ApiMode.OpenAI)
        Assertions.assertEquals("/chat/completions", pipe.internalGetEndpointForTest())
    }

    @Test
    fun testGetEndpointAnthropicUnchanged()
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://example.com/v1")
            .setApiMode(ApiMode.Anthropic)
        Assertions.assertEquals("/anthropic/v1/messages", pipe.internalGetEndpointForTest())
    }

//=========================================Non-Streaming MockEngine Round-Trip=========================================

    @Test
    fun testNonStreamingMockEngineRoundTrip() = runBlocking<Unit>
    {
        val cannedBody = """
            {
              "id": "resp_mock_1",
              "object": "response",
              "created_at": 1700000000,
              "status": "completed",
              "model": "MiniMax-M2.7",
              "output": [
                {
                  "type": "message",
                  "id": "msg_1",
                  "role": "assistant",
                  "status": "completed",
                  "content": [
                    { "type": "output_text", "text": "mock-pong", "annotations": [] }
                  ]
                }
              ],
              "usage": { "input_tokens": 3, "output_tokens": 4, "total_tokens": 7 }
            }
        """.trimIndent()

        var capturedRequest: HttpRequestData? = null
        val mockEngine = MockEngine { request ->
            capturedRequest = request
            respond(
                content = cannedBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)

        pipe.setModel("MiniMax-M2.7")
        pipe.setMaxTokens(64)
        pipe.setStreamingEnabled(true)

        pipe.injectHttpClientForTest(HttpClient(mockEngine))
        pipe.initForTest()
        try
        {
            val text = pipe.generateTextForTest("hi")
            Assertions.assertEquals("mock-pong", text)

            // The request must have been POSTed to /v1/responses
            val req = capturedRequest
            Assertions.assertTrue(req != null, "MockEngine did not capture a request")
            Assertions.assertEquals("https://mock.local/v1/responses", "https://mock.local/v1" + req!!.url.fullPath)
            Assertions.assertEquals("Bearer mock-key", req.headers["Authorization"])
            // And the body must be the Responses shape, not chat-completions
            val body = when (val reqBody = req.body)
            {
                is OutgoingContent.ByteArrayContent -> reqBody.bytes().decodeToString()
                else -> throw IllegalStateException("Unexpected body type: ${reqBody::class}")
            }
            Assertions.assertTrue(body.contains("\"input\""))
            Assertions.assertTrue(!body.contains("\"messages\""), "Responses body must not contain chat-completions 'messages' field")
        }
        finally
        {
            pipe.abortForTest()
        }
    }

//=========================================Streaming MockEngine Round-Trip=========================================

    @Test
    fun testStreamingMockEngineAccumulatesTextAndTerminatesOnCompleted() = runBlocking<Unit>
    {
        val sseBody = buildString {
            append("event: response.created\n")
            append("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_s\",\"object\":\"response\",\"status\":\"in_progress\",\"model\":\"MiniMax-M2.7\"}}\n\n")
            append("event: response.output_text.delta\n")
            append("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_s\",\"output_index\":0,\"content_index\":0,\"delta\":\"Hel\"}\n\n")
            append("event: response.output_text.delta\n")
            append("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_s\",\"output_index\":0,\"content_index\":0,\"delta\":\"lo\"}\n\n")
            append("event: response.completed\n")
            append("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_s\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"MiniMax-M2.7\"}}\n\n")
        }

        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(sseBody),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }

        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)

        pipe.setModel("MiniMax-M2.7")
        pipe.setMaxTokens(64)
        pipe.setStreamingEnabled(true)

        pipe.injectHttpClientForTest(HttpClient(mockEngine))
        pipe.initForTest()
        try
        {
            val text = pipe.generateTextForTest("hi")
            Assertions.assertEquals("Hello", text)
        }
        finally
        {
            pipe.abortForTest()
        }
    }
}
