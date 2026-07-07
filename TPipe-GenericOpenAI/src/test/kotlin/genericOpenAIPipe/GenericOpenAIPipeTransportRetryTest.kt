package genericOpenAIPipe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import genericOpenAIPipe.api.ApiMode
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transport-level retry behaviour for [GenericOpenAIPipe.generateText].
 *
 * Background: when Ktor's CIO engine hits a mid-stream EOF, a [SocketTimeoutException],
 * or an [HttpRequestTimeoutException] during the body read, the exception propagates
 * unhandled out of `client.post(...).bodyAsText()`. There is no internal retry layer
 * and the exception escapes all the way up through `PumpStationLoop.runDispatchPhase`.
 *
 * The retry helper is intentionally narrow:
 *   - it retries only on [IOException] (covers raw EOF, socket timeout, and
 *     Ktor's [HttpRequestTimeoutException] which extends IOException),
 *   - it retries exactly once with a 100ms backoff,
 *   - it does not retry on HTTP error responses, parse failures, or programmer errors.
 *
 * Verified scenarios:
 *   1. IOException on first request, success on retry -> pipe returns the canned body.
 *   2. IOException on both requests -> the second IOException propagates (no infinite loop).
 *   3. Successful first request -> exactly one network call (no retry, no extra latency).
 *   4. Non-IOException (e.g. IllegalStateException from MockEngine) -> propagates without retry.
 */
class GenericOpenAIPipeTransportRetryTest
{

//=========================================Helpers=========================================

    private val cannedChatCompletionsBody = """
        {
          "id": "chatcmpl-retry-1",
          "object": "chat.completion",
          "created": 1700000000,
          "model": "MiniMax-M2.7",
          "choices": [
            {
              "index": 0,
              "message": { "role": "assistant", "content": "retry-pong" },
              "finish_reason": "stop"
            }
          ],
          "usage": { "prompt_tokens": 2, "completion_tokens": 3, "total_tokens": 5 }
        }
    """.trimIndent()

    private val cannedResponsesBody = """
        {
          "id": "resp_retry_1",
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
              "content": [ { "type": "output_text", "text": "retry-mm", "annotations": [] } ]
            }
          ],
          "usage": { "input_tokens": 1, "output_tokens": 2, "total_tokens": 3 }
        }
    """.trimIndent()

    private val jsonContentHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun buildResponsesApiPipe(httpClient: HttpClient): GenericOpenAIPipe
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAIResponses)

        pipe.setModel("MiniMax-M2.7")
        pipe.setMaxTokens(64)
        pipe.setStreamingEnabled(false)

        pipe.injectHttpClientForTest(httpClient)
        return pipe
    }

    private fun buildChatCompletionsPipe(httpClient: HttpClient): GenericOpenAIPipe
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAI)

        pipe.setModel("MiniMax-M2.7")
        pipe.setMaxTokens(64)
        pipe.setStreamingEnabled(false)

        pipe.injectHttpClientForTest(httpClient)
        return pipe
    }

//=========================================Retry Recovers From Transient IOException=========================================

    @Test
    fun testGenerateTextRetriesOnceOnTransientIOException() = runBlocking<Unit>
    {
        val callCount = AtomicInteger(0)

        val mockEngine = MockEngine { _ ->
            val attempt = callCount.incrementAndGet()
            if(attempt == 1)
            {
                // Simulate a mid-stream EOF / transient transport blip on the first attempt.
                throw SocketTimeoutException("simulated read timeout")
            }
            respond(
                content = cannedChatCompletionsBody,
                status = HttpStatusCode.OK,
                headers = jsonContentHeaders
            )
        }

        val pipe = buildChatCompletionsPipe(HttpClient(mockEngine))
        pipe.initForTest()

        try
        {
            val text = pipe.generateTextForTest("hi")
            Assertions.assertEquals("retry-pong", text, "Pipe should return the canned body after a retry")
            Assertions.assertEquals(2, callCount.get(), "Pipe should issue exactly one retry (2 calls total)")
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Test
    fun testSendRequestRetriesOnceOnHttpRequestTimeout() = runBlocking<Unit>
    {
        // Exercises the sendRequest path used by generateMultimodalContent.
        val callCount = AtomicInteger(0)

        val mockEngine = MockEngine { _ ->
            val attempt = callCount.incrementAndGet()
            if(attempt == 1)
            {
                throw HttpRequestTimeoutException(
                    url = "https://mock.local/v1/responses",
                    timeoutMillis = 100L,
                    cause = null
                )
            }
            respond(
                content = cannedResponsesBody,
                status = HttpStatusCode.OK,
                headers = jsonContentHeaders
            )
        }

        val pipe = buildResponsesApiPipe(HttpClient(mockEngine))
        pipe.initForTest()
        try
        {
            val result = pipe.generateTextForTest("hi")
            Assertions.assertEquals("retry-mm", result)
            Assertions.assertEquals(2, callCount.get(), "sendRequest should retry exactly once on HttpRequestTimeoutException")
        }
        finally
        {
            pipe.abortForTest()
        }
    }

//=========================================Failure Modes=========================================

    @Test
    fun testGenerateTextDoesNotLoopWhenBothAttemptsFail() = runBlocking<Unit>
    {
        // If both attempts fail with IOException, the second one must propagate.
        // A buggy implementation that retries forever would hang this test or run until
        // the next guard fires.
        //
        // The pipe's outer catch wraps transport IOException into P2PException(transport, ...).
        // That wrapping is intentional production behaviour — it preserves the original cause
        // for diagnostics. This test asserts the wrapping is correct AND the retry fired
        // exactly once.
        val callCount = AtomicInteger(0)

        val mockEngine = MockEngine { _ ->
            callCount.incrementAndGet()
            throw SocketTimeoutException("permanent transport failure")
        }

        val pipe = buildChatCompletionsPipe(HttpClient(mockEngine))
        pipe.initForTest()
        try
        {
            val thrown = runCatching { pipe.generateTextForTest("hi") }.exceptionOrNull()
            Assertions.assertNotNull(thrown, "Second IOException should propagate (wrapped) to the caller")
            Assertions.assertTrue(
                thrown is P2PException,
                "Outer catch should wrap transport failure as P2PException, was ${thrown!!::class.simpleName}"
            )
            val p2pEx = thrown as P2PException
            Assertions.assertEquals(
                P2PError.transport,
                p2pEx.errorType,
                "P2PException should carry the transport error code"
            )
            Assertions.assertTrue(
                p2pEx.cause is SocketTimeoutException,
                "P2PException should preserve the original SocketTimeoutException as cause, was ${p2pEx.cause?.let { it::class.simpleName }}"
            )
            Assertions.assertEquals(2, callCount.get(), "Pipe should attempt exactly twice (no infinite retry)")
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Test
    fun testGenerateTextDoesNotRetryOnNonIoException() = runBlocking<Unit>
    {
        // Programmer-error / parse-side exceptions should not trigger a retry.
        // The pipe's outer catch only wraps IOException-family into P2PException; an
        // IllegalStateException is rethrown unchanged.
        val callCount = AtomicInteger(0)

        val mockEngine = MockEngine { _ ->
            callCount.incrementAndGet()
            throw IllegalStateException("not a transport failure")
        }

        val pipe = buildChatCompletionsPipe(HttpClient(mockEngine))
        pipe.initForTest()
        try
        {
            val thrown = runCatching { pipe.generateTextForTest("hi") }.exceptionOrNull()
            Assertions.assertNotNull(thrown, "Non-IOException should propagate")
            Assertions.assertTrue(
                thrown is IllegalStateException,
                "IllegalStateException should propagate unwrapped, was ${thrown!!::class.simpleName}"
            )
            Assertions.assertEquals(1, callCount.get(), "Non-IOException must not trigger a retry")
        }
        finally
        {
            pipe.abortForTest()
        }
    }

//=========================================Happy Path Unaffected=========================================

    @Test
    fun testGenerateTextIssuesExactlyOneRequestOnHappyPath() = runBlocking<Unit>
    {
        val callCount = AtomicInteger(0)

        val mockEngine = MockEngine { _ ->
            callCount.incrementAndGet()
            respond(
                content = cannedChatCompletionsBody,
                status = HttpStatusCode.OK,
                headers = jsonContentHeaders
            )
        }

        val pipe = buildChatCompletionsPipe(HttpClient(mockEngine))
        pipe.initForTest()
        try
        {
            val text = pipe.generateTextForTest("hi")
            Assertions.assertEquals("retry-pong", text)
            Assertions.assertEquals(1, callCount.get(), "Happy path must NOT issue a second request")
        }
        finally
        {
            pipe.abortForTest()
        }
    }
}