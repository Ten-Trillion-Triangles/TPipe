package genericOpenAIPipe.api

import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.MockStreamingConnectionFactory
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions

/**
 * Pipe-dispatch + MockStreamingConnectionFactory integration tests for the OpenAI
 * Responses API mode. Verifies:
 *  - the pipe hits `/v1/responses` (not `/v1/chat/completions`),
 *  - the pipe sends `Authorization: Bearer ...` for the Responses mode,
 *  - a canned non-streaming Responses body is parsed and returned,
 *  - a canned streaming Responses SSE sequence terminates on
 *    `response.completed` and accumulates text.
 *
 * Note: the pipe's streaming path uses `executeStreamingDirect` (a raw
 * `java.net.HttpURLConnection` flow) rather than the injected Ktor HttpClient,
 * because the Ktor CIO `bodyAsChannel` doesn't deliver bytes incrementally for
 * chunked transfer-encoded SSE responses (see `GenericOpenAIPipe.kt` comments
 * around `executeStreamingDirect`). Tests inject a `MockStreamingConnectionFactory`
 * via `injectStreamingConnectionFactoryForTest` to drive that path without
 * making a real network call.
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
// NOTE: This test exercises the non-streaming `sendRequest` path which uses the
// injected Ktor `HttpClient` (via `injectHttpClientForTest`), not the streaming
// factory seam. The test was broken in this sandbox before the Path A factory
// seam landed (mock.local does not resolve). The factory seam in Task 0 only
// covers `executeStreamingDirect`. A future plan should add a non-streaming
// HTTP-connection test seam or stand up a local mock server.

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

        val mockEngine = MockEngine { _ ->
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
        pipe.setStreamingEnabled(false)

        pipe.injectHttpClientForTest(HttpClient(mockEngine))
        pipe.initForTest()
        try
        {
            val text = pipe.generateTextForTest("hi")
            Assertions.assertEquals("mock-pong", text)
        }
        finally
        {
            pipe.abortForTest()
        }
    }

//=========================================Streaming MockConnection Round-Trip=========================================

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

        val factory = MockStreamingConnectionFactory(responseBodySupplier = { sseBody })

        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)

        pipe.setModel("MiniMax-M2.7")
        pipe.setMaxTokens(64)
        pipe.setStreamingEnabled(true)

        pipe.injectStreamingConnectionFactoryForTest(factory)
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

//=========================================Empty-Stream Recovery Tests=========================================

    @Test
    fun testStreamingDoneOnlyReturnsText() = runBlocking<Unit>
    {
        val sseBody = buildString {
            append("event: response.created\n")
            append("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_done_only\",\"object\":\"response\",\"status\":\"in_progress\",\"model\":\"MiniMax-M2.7\"}}\n\n")
            append("event: response.output_text.done\n")
            append("data: {\"type\":\"response.output_text.done\",\"item_id\":\"msg_d\",\"output_index\":0,\"content_index\":0,\"text\":\"Hello\"}\n\n")
            append("event: response.completed\n")
            append("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_done_only\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"MiniMax-M2.7\"}}\n\n")
        }

        val factory = MockStreamingConnectionFactory(responseBodySupplier = { sseBody })

        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)

        pipe.setModel("MiniMax-M2.7")
        pipe.setMaxTokens(64)
        pipe.setStreamingEnabled(true)

        pipe.injectStreamingConnectionFactoryForTest(factory)
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

    @Test
    fun testStreamingCompletedOnlyFallbackReturnsText() = runBlocking<Unit>
    {
        val sseBody = buildString {
            append("event: response.created\n")
            append("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_completed_only\",\"object\":\"response\",\"status\":\"in_progress\",\"model\":\"MiniMax-M2.7\"}}\n\n")
            append("event: response.completed\n")
            append("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_completed_only\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"MiniMax-M2.7\",\"output\":[{\"type\":\"message\",\"id\":\"msg_c\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"Recovered text\",\"annotations\":[]}]}]}}\n\n")
        }

        val factory = MockStreamingConnectionFactory(responseBodySupplier = { sseBody })

        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)

        pipe.setModel("MiniMax-M2.7")
        pipe.setMaxTokens(64)
        pipe.setStreamingEnabled(true)

        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()
        try
        {
            val text = pipe.generateTextForTest("hi")
            Assertions.assertEquals("Recovered text", text)
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Test
    fun testStreamingDeltaThenDoneDoesNotDuplicate() = runBlocking<Unit>
    {
        val sseBody = buildString {
            append("event: response.created\n")
            append("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_dup\",\"object\":\"response\",\"status\":\"in_progress\",\"model\":\"MiniMax-M2.7\"}}\n\n")
            append("event: response.output_text.delta\n")
            append("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_dup\",\"output_index\":0,\"content_index\":0,\"delta\":\"Hel\"}\n\n")
            append("event: response.output_text.delta\n")
            append("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_dup\",\"output_index\":0,\"content_index\":0,\"delta\":\"lo\"}\n\n")
            append("event: response.output_text.done\n")
            append("data: {\"type\":\"response.output_text.done\",\"item_id\":\"msg_dup\",\"output_index\":0,\"content_index\":0,\"text\":\"Hello\"}\n\n")
            append("event: response.completed\n")
            append("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_dup\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"MiniMax-M2.7\",\"output\":[{\"type\":\"message\",\"id\":\"msg_dup\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\",\"annotations\":[]}]}]}}\n\n")
        }

        val factory = MockStreamingConnectionFactory(responseBodySupplier = { sseBody })

        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)

        pipe.setModel("MiniMax-M2.7")
        pipe.setMaxTokens(64)
        pipe.setStreamingEnabled(true)

        pipe.injectStreamingConnectionFactoryForTest(factory)
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

    @Test
    fun testStreamingEmptyCompletionThrowsP2PException() = runBlocking<Unit>
    {
        val sseBody = buildString {
            append("event: response.created\n")
            append("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_empty\",\"object\":\"response\",\"status\":\"in_progress\",\"model\":\"MiniMax-M2.7\"}}\n\n")
            append("event: response.completed\n")
            append("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_empty\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"MiniMax-M2.7\",\"output\":[]}}\n\n")
        }

        val factory = MockStreamingConnectionFactory(responseBodySupplier = { sseBody })

        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)

        pipe.setModel("MiniMax-M2.7")
        pipe.setMaxTokens(64)
        pipe.setStreamingEnabled(true)

        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()
        try
        {
            val exception = Assertions.assertThrows(P2PException::class.java) {
                runBlocking { pipe.generateTextForTest("hi") }
            }
            Assertions.assertEquals(P2PError.transport, exception.errorType)
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Test
    fun testStreamingResponseFailedThrowsP2PException() = runBlocking<Unit>
    {
        val sseBody = buildString {
            append("event: response.created\n")
            append("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_failed\",\"object\":\"response\",\"status\":\"in_progress\",\"model\":\"MiniMax-M2.7\"}}\n\n")
            append("event: response.failed\n")
            append("data: {\"type\":\"response.failed\",\"response\":{\"id\":\"resp_failed\",\"object\":\"response\",\"status\":\"failed\",\"model\":\"MiniMax-M2.7\",\"error\":{\"type\":\"server_error\",\"message\":\"upstream boom\"}}}\n\n")
        }

        val factory = MockStreamingConnectionFactory(responseBodySupplier = { sseBody })

        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)

        pipe.setModel("MiniMax-M2.7")
        pipe.setMaxTokens(64)
        pipe.setStreamingEnabled(true)

        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()
        try
        {
            val exception = Assertions.assertThrows(P2PException::class.java) {
                runBlocking { pipe.generateTextForTest("hi") }
            }
            Assertions.assertEquals(P2PError.transport, exception.errorType)
            // The upstream error reason must be preserved — NOT the empty-completion
            // message that would fire if the loop just fell through to the empty check.
            Assertions.assertTrue(
                exception.message?.contains("upstream boom") == true,
                "Expected P2PException message to contain upstream error 'upstream boom', got: ${exception.message}"
            )
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Test
    fun testStreamingDeltaOnlyRegressionGuard() = runBlocking<Unit>
    {
        // Identical SSE body and assertion to
        // testStreamingMockEngineAccumulatesTextAndTerminatesOnCompleted.
        // Re-pinned as a regression guard so the fallback contract in
        // applyResponsesTerminalTextFallback cannot accidentally break the
        // primary delta-accumulation path.
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

        val factory = MockStreamingConnectionFactory(responseBodySupplier = { sseBody })

        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)

        pipe.setModel("MiniMax-M2.7")
        pipe.setMaxTokens(64)
        pipe.setStreamingEnabled(true)

        pipe.injectStreamingConnectionFactoryForTest(factory)
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
