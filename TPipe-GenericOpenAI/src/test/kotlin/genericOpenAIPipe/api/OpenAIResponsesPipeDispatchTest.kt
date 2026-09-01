package genericOpenAIPipe.api

import com.TTT.Util.serialize
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.TPipeContextOptions
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.MockStreamingConnectionFactory
import genericOpenAIPipe.access.GenericOpenAIAccessProfile
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
 * Pipe-dispatch + canned-response integration tests for the OpenAI Responses
 * API mode. Verifies:
 *  - the pipe hits `/v1/responses` (not `/v1/chat/completions`),
 *  - the pipe sends `Authorization: Bearer ...` for the Responses mode,
 *  - a canned non-streaming Responses body is parsed and returned,
 *  - a canned streaming Responses SSE sequence terminates on
 *    `response.completed` and accumulates text,
 *  - the terminal-text fallback paths (done-event, completed-response,
 *    response.failed, empty-completion) behave correctly.
 *
 * Two test seams are used:
 *
 *  - **Non-streaming** (one test): the production code path
 *    [GenericOpenAIPipe.sendRequest] uses the injected Ktor `HttpClient`
 *    via [GenericOpenAIPipe.injectHttpClientForTest]. The classic Ktor
 *    `MockEngine` is the right stub here because the production code
 *    actually does go through the Ktor client on this path.
 *
 *  - **Streaming** (eight tests): the production code path
 *    [GenericOpenAIPipe.executeStreamingDirect] uses raw
 *    `java.net.HttpURLConnection` because the Ktor CIO `bodyAsChannel`
 *    doesn't deliver bytes incrementally for chunked transfer-encoded
 *    SSE responses (see `GenericOpenAIPipe.kt` comments around
 *    `executeStreamingDirect`). The `HttpStreamingConnectionFactory`
 *    seam added by this fix lets tests drive that path without making
 *    a real network call. The bridge is [MockStreamingConnectionFactory].
 */
class OpenAIResponsesPipeDispatchTest
{
    fun echoForCodex(value: String): String = "echoed:$value"

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
    fun testAccessProfileIsNotSerialized()
    {
        val pipe = GenericOpenAIPipe()
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)
            .setAccessProfile(RotatingTestAccessProfile(forceStreaming = true))
        pipe.setModel("test-model")

        val serialized = serialize(pipe)

        Assertions.assertFalse(serialized.contains("test-profile"))
        Assertions.assertFalse(serialized.contains("accessProfile"))
    }

    @Test
    fun testAccessProfileRecoversOneKtorUnauthorizedResponse() = runBlocking<Unit>
    {
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val profile = RotatingTestAccessProfile(forceStreaming = false)
        val engine = MockEngine {
            if(callCount.incrementAndGet() == 1)
            {
                respond("", HttpStatusCode.Unauthorized)
            }
            else
            {
                respond(
                    cannedResponsesBodyForAccessRecovery,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        }
        val client = HttpClient(engine)
        val pipe = GenericOpenAIPipe()
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)
            .setAccessProfile(profile)
        pipe.setModel("test-model")
        pipe.setStreamingEnabled(false)
        pipe.injectHttpClientForTest(client)
        pipe.initForTest()
        try
        {
            Assertions.assertEquals("retry-pong", pipe.generateTextForTest("hi"))
            Assertions.assertEquals(2, callCount.get())
            Assertions.assertEquals(1, profile.recoveryCount)
        }
        finally
        {
            pipe.abortForTest()
            client.close()
        }
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
    fun testAccessProfileRecoversOneDirectStreamingUnauthorizedResponse() = runBlocking<Unit>
    {
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val profile = RotatingTestAccessProfile(forceStreaming = true)
        val sseBody = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"recovered"}

            event: response.completed
            data: {"type":"response.completed","response":{"status":"completed"}}

        """.trimIndent()
        val factory = MockStreamingConnectionFactory(
            responseBodySupplier = { sseBody },
            statusCodeSupplier = { if(callCount.incrementAndGet() == 1) 401 else 200 },
        )
        val pipe = GenericOpenAIPipe()
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)
            .setAccessProfile(profile)
        pipe.setModel("test-model")
        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()
        try
        {
            Assertions.assertEquals("recovered", pipe.generateTextForTest("hi"))
            Assertions.assertEquals(2, callCount.get())
            Assertions.assertEquals(1, profile.recoveryCount)
            Assertions.assertEquals("Bearer rotated", factory.capturedHeaders["Authorization"])
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Test
    fun testCodexShapedResponsesTextStillReachesExistingPcpDispatcher() = runBlocking<Unit>
    {
        FunctionRegistry.clear()
        FunctionRegistry.registerFunction("echo", ::echoForCodex)
        val pcpContext = PcpContext().apply {
            addTPipeOption(TPipeContextOptions().apply { functionName = "echo" })
        }
        val request = com.TTT.PipeContextProtocol.PcPRequest().apply {
            tPipeContextOptions = TPipeContextOptions().apply { functionName = "echo" }
            argumentsOrFunctionParams = listOf("codex")
        }
        val responseText = serialize(request, encodedefault = true)
        val sseBody = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":${kotlinx.serialization.json.JsonPrimitive(responseText)}}

            event: response.completed
            data: {"type":"response.completed","response":{"status":"completed"}}

        """.trimIndent()
        val factory = MockStreamingConnectionFactory(responseBodySupplier = { sseBody })
        val pipe = GenericOpenAIPipe()
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)
            .setAccessProfile(RotatingTestAccessProfile(forceStreaming = true))
        pipe.setModel("gpt-5-codex")
        pipe.setPcPContext(pcpContext)
        pipe.setSystemPrompt("Use the available tool when appropriate.")
        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()
        try
        {
            val llmResponse = pipe.generateTextForTest("run the tool")
            Assertions.assertTrue(factory.capturedRequestBody.contains("Pipe Context Protocol"))
            Assertions.assertTrue(factory.capturedRequestBody.contains("echo"))
            Assertions.assertFalse(factory.capturedRequestBody.contains("\"tools\""))

            val execution = pipe.processPcpResponse(llmResponse)
            Assertions.assertTrue(execution.success, "PCP execution failed: ${execution.errors}")
            Assertions.assertEquals("echoed:codex", execution.results.single().output)
        }
        finally
        {
            pipe.abortForTest()
            FunctionRegistry.clear()
        }
    }

    @Test
    fun testGetEndpointAnthropicUsesProtocolPath()
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://example.com/v1")
            .setApiMode(ApiMode.Anthropic)
        Assertions.assertEquals("/v1/messages", pipe.internalGetEndpointForTest())
    }

//=========================================Non-Streaming Round-Trip=========================================
// Exercises [GenericOpenAIPipe.sendRequest], which goes through the injected
// Ktor `HttpClient` seam. The streaming path (next section) does NOT —
// see the class KDoc for why.

    @Test
    fun testNonStreamingCannedConnectionRoundTrip() = runBlocking<Unit>
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

//=========================================Streaming Round-Trip (HttpStreamingConnectionFactory Seam)=========
// Every test in this section uses [streamingPipe] to build a pipe wired to the
// production-shaped streaming seam. Tests vary only the SSE body and the
// assertion contract being pinned. Add a new test by composing a new SSE body
// and a new assertion against the returned pipe — do not duplicate seam setup.

    /**
     * Builds an OpenAIResponses pipe wired to a [MockStreamingConnectionFactory]
     * that returns [sseBody] from the SSE stream. Returns the fully-initialised
     * pipe plus the factory (callers that need to assert on captured request
     * fields can use the second element). The caller is responsible for
     * [GenericOpenAIPipe.abortForTest] in a try/finally.
     */
    private suspend fun streamingPipe(sseBody: String): Pair<GenericOpenAIPipe, MockStreamingConnectionFactory>
    {
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
        return pipe to factory
    }

    private class RotatingTestAccessProfile(
        private val forceStreaming: Boolean,
    ) : GenericOpenAIAccessProfile
    {
        override val profileName: String = "test-profile"
        override val responsesWirePolicy: OpenAIResponsesWirePolicy = OpenAIResponsesWirePolicy(
            forceStreaming = forceStreaming,
            messageItemType = "message",
        )
        var recoveryCount: Int = 0
            private set

        override suspend fun headersForRequest(
            method: String,
            url: String,
            body: ByteArray,
        ): Map<String, String> = mapOf(
            "Authorization" to if(recoveryCount == 0) "Bearer initial" else "Bearer rotated"
        )

        override suspend fun recoverUnauthorized(): Boolean
        {
            recoveryCount++
            return true
        }
    }

    @Test
    fun testStreamingDeltaOnlyAccumulatesText() = runBlocking<Unit>
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

        val (pipe, _) = streamingPipe(sseBody)
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

        val (pipe, _) = streamingPipe(sseBody)
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

        val (pipe, _) = streamingPipe(sseBody)
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
        // SSE body carries both delta events AND a terminal done-event for the same
        // text. The done-event fallback contract (applyResponsesTerminalTextFallback)
        // must skip when the builder is non-empty, producing "Hello" (not "HelloHello").
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

        val (pipe, _) = streamingPipe(sseBody)
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
        // No delta events, no done events, no output in completed — the pipe must
        // fail with a typed P2PException, not return an empty string with success=true.
        val sseBody = buildString {
            append("event: response.created\n")
            append("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_empty\",\"object\":\"response\",\"status\":\"in_progress\",\"model\":\"MiniMax-M2.7\"}}\n\n")
            append("event: response.completed\n")
            append("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_empty\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"MiniMax-M2.7\",\"output\":[]}}\n\n")
        }

        val (pipe, _) = streamingPipe(sseBody)
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
        // An explicit response.failed event must surface the upstream error.message —
        // NOT the generic "produced no output text" that the empty-completion check
        // would otherwise emit.
        val sseBody = buildString {
            append("event: response.created\n")
            append("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_failed\",\"object\":\"response\",\"status\":\"in_progress\",\"model\":\"MiniMax-M2.7\"}}\n\n")
            append("event: response.failed\n")
            append("data: {\"type\":\"response.failed\",\"response\":{\"id\":\"resp_failed\",\"object\":\"response\",\"status\":\"failed\",\"model\":\"MiniMax-M2.7\",\"error\":{\"type\":\"server_error\",\"message\":\"upstream boom\"}}}\n\n")
        }

        val (pipe, _) = streamingPipe(sseBody)
        try
        {
            val exception = Assertions.assertThrows(P2PException::class.java) {
                runBlocking { pipe.generateTextForTest("hi") }
            }
            Assertions.assertEquals(P2PError.transport, exception.errorType)
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

    private companion object
    {
        val cannedResponsesBodyForAccessRecovery = """
            {
              "id":"resp_retry",
              "object":"response",
              "status":"completed",
              "model":"test-model",
              "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"retry-pong"}]}]
            }
        """.trimIndent()
    }
}
