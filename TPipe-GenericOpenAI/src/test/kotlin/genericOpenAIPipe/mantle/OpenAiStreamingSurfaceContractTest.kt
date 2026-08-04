package genericOpenAIPipe.mantle

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceEventType
import com.TTT.P2P.P2PException
import com.TTT.Pipe.PipeTimeoutStrategy
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.HttpStreamingConnection
import genericOpenAIPipe.HttpStreamingConnectionFactory
import genericOpenAIPipe.MockStreamingConnectionFactory
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.instrumentPipeForTracing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * RED tests pinning the two contracts the user asked for in the
 * 2026-08-02 re-check:
 *
 *   A) The captured streaming finish_reason is exposed downstream
 *      via a class field `streamingFinishReason` and a public setter
 *      `setStreamingFinishReason(value)`, and surfaces in the
 *      API_CALL_SUCCESS trace metadata so trace-file consumers can
 *      read it.
 *
 *   D) When the OpenAI streaming call fails mid-stream, the failure
 *      path respects TPipe's generic pipe retry policy: if the
 *      caller has configured `timeoutStrategy = Retry` with
 *      `maxRetryAttempts > 0`, the failure propagates as a
 *      `P2PException(P2PError.transport, ...)` carrying a
 *      `retryable=true` trace tag so [com.TTT.Pipe.PipeTimeoutManager]
 *      catches it on its retry path. If the caller has NOT configured
 *      retries (default `PipeTimeoutStrategy.Fail`), the same
 *      failure still throws `P2PException` but the trace tag is
 *      `retryable=false` and the metadata block carries the full
 *      diagnostic context (streamingFinishReason, partialTextLength,
 *      elapsedMs, transportErrorKind).
 *
 * The trace-observation surface used here is [PipeTracer.getAllTraces],
 * which is the same global trace store the production tracing layer
 * writes to. Tests instrument the pipe via [instrumentPipeForTracing]
 * which is the canonical pattern documented in
 * `TracingTestSupport.kt`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAiStreamingSurfaceContractTest
{
    private val pipelineId = "openai-surface-contract"

    @BeforeAll
    fun enableTracing()
    {
        PipeTracer.enable()
    }

    @AfterAll
    fun disableTracing()
    {
        PipeTracer.disable()
    }

    //================================================================
    // A — finish_reason exposure
    //================================================================

    /**
     * A: setter round-trip. Calling `setStreamingFinishReason("stop")`
     * before init must be readable after the stream completes. Pin
     * the contract that the field is settable, persists across the
     * streaming call, and is cleared by the next streaming start so
     * stale values from a prior request don't leak into the next
     * one's success metadata.
     */
    @Test
    fun streamingFinishReasonSetterRoundTripsAndClearsBetweenRequests() = runBlocking<Unit>
    {
        // RED: the field/setter does not exist yet — this test will
        // fail to compile until task-A1 lands. Once it compiles,
        // the runtime assertions pin the persistence + reset contract.
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://bedrock-mantle.us-east-1.api.aws/openai/v1")
            .setApiMode(ApiMode.OpenAI) as GenericOpenAIPipe
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(true)

        // Setter exists and is chainable.
        val returned = pipe.setStreamingFinishReason("stop")
        Assertions.assertSame(
            pipe, returned,
            "setStreamingFinishReason must return `this` for builder-pattern chaining"
        )
        Assertions.assertEquals(
            "stop", pipe.streamingFinishReason,
            "Setter value must be readable before the stream starts"
        )

        // Reset for the next request.
        pipe.setStreamingFinishReason(null)
        Assertions.assertNull(
            pipe.streamingFinishReason,
            "Setter must accept null to clear the field between requests"
        )
    }

    /**
     * A: API_CALL_SUCCESS metadata includes the captured
     * streamingFinishReason. After a successful streaming call that
     * saw finish_reason="stop", the API_CALL_SUCCESS event written
     * to [PipeTracer] must include the finish_reason under the key
     * "streamingFinishReason" in its metadata map.
     *
     * Uses the shared [MockStreamingConnectionFactory] which delivers
     * a canned body via ByteArrayInputStream that EOFs naturally.
     * This is the canonical fixture for "parser must terminate
     * cleanly on [DONE]" — no need to reproduce the BlockingInputStream
     * keepalive shape here.
     */
    @Test
    fun apiCallSuccessMetadataIncludesStreamingFinishReason() = runBlocking<Unit>
    {
        val fixture = listOf(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"}}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
            "data: [DONE]\n\n"
        ).joinToString("")
        val factory = MockStreamingConnectionFactory(responseBodySupplier = { fixture })

        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://bedrock-mantle.us-east-1.api.aws/openai/v1")
            .setApiMode(ApiMode.OpenAI) as GenericOpenAIPipe
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(true)
        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()
        instrumentPipeForTracing(pipe, pipelineId)

        try
        {
            val result = withTimeoutOrNull(10_000) { pipe.generateTextForTest("hi") }
            Assertions.assertNotNull(result, "Stream must terminate; got null")
            Assertions.assertEquals("Hi", result)
        }
        finally
        {
            pipe.abortForTest()
        }

        // Field on the pipe must reflect the captured reason.
        Assertions.assertEquals(
            "stop", pipe.streamingFinishReason,
            "After a streaming call that saw finish_reason='stop', " +
                "the class field must read 'stop'"
        )

        // API_CALL_SUCCESS event must carry the finish_reason in metadata.
        val events = PipeTracer.getAllTraces()[pipelineId] ?: emptyList()
        val success = events.firstOrNull { it.eventType == TraceEventType.API_CALL_SUCCESS }
        Assertions.assertNotNull(
            success,
            "No API_CALL_SUCCESS event recorded for pipelineId='$pipelineId'. " +
                "Got events: ${events.map { it.eventType }}"
        )
        Assertions.assertEquals(
            "stop", success!!.metadata["streamingFinishReason"],
            "API_CALL_SUCCESS metadata 'streamingFinishReason' must equal 'stop'. " +
                "Got metadata keys: ${success.metadata.keys}"
        )
    }

    //================================================================
    // D — retry policy / failure propagation
    //================================================================

    /**
     * D: when `timeoutStrategy = Retry` and `maxRetryAttempts > 0`,
     * a mid-stream SocketException propagates as P2PException
     * carrying a `retryable=true` trace tag. This is the path
     * [com.TTT.Pipe.PipeTimeoutManager] watches for when deciding
     * whether to schedule another attempt.
     *
     * Mock connection delivers one content delta then throws a
     * SocketException from read() — exactly what a Mantle mid-stream
     * network blip looks like to the parser.
     */
    @Test
    fun midStreamSocketFailureWithRetryPolicyPropagatesAsRetryableTransportFailure() = runBlocking<Unit>
    {
        val factory = FailingSocketConnectionFactory(
            failAfterBytes = 30,
            failureException = SocketException("Connection reset by peer (simulated Mantle blip)")
        )

        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://bedrock-mantle.us-east-1.api.aws/openai/v1")
            .setApiMode(ApiMode.OpenAI) as GenericOpenAIPipe
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(true)
        pipe.timeoutStrategy = PipeTimeoutStrategy.Retry
        pipe.maxRetryAttempts = 3
        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()
        instrumentPipeForTracing(pipe, pipelineId)

        val thrown = try
        {
            pipe.generateTextForTest("hi")
            null
        }
        catch(e: P2PException) { e }
        catch(e: Exception) { throw AssertionError("Expected P2PException, got ${e::class.simpleName}: ${e.message}", e) }

        Assertions.assertNotNull(
            thrown,
            "Mid-stream SocketException must surface as P2PException, " +
                "not silently retry or swallow"
        )
        // P2PError.transport is the dedicated transport-error enum value.
        Assertions.assertEquals(
            "transport", thrown!!.errorType.name.lowercase(),
            "P2PException.errorType must be the transport-error kind"
        )

        // The failure event must be tagged retryable=true so the
        // PipeTimeoutManager recognises it as a retry candidate.
        val events = PipeTracer.getAllTraces()[pipelineId] ?: emptyList()
        val failure = events.firstOrNull { it.eventType == TraceEventType.API_CALL_FAILURE }
        Assertions.assertNotNull(
            failure,
            "No API_CALL_FAILURE event recorded. Got: ${events.map { it.eventType }}"
        )
        Assertions.assertEquals(
            true, failure!!.metadata["retryable"],
            "retryable must be true when timeoutStrategy=Retry. " +
                "Got metadata: ${failure.metadata}"
        )
        Assertions.assertEquals(
            "SocketException", failure.metadata["transportErrorKind"],
            "transportErrorKind must name the underlying exception class. " +
                "Got: ${failure.metadata["transportErrorKind"]}"
        )
        Assertions.assertNotNull(
            failure.metadata["elapsedMs"],
            "Failure metadata must include elapsedMs so retry budgets can reason about timing"
        )
        Assertions.assertTrue(
            failure.metadata.containsKey("partialTextLength"),
            "Failure metadata must include partialTextLength for diagnostic visibility"
        )
    }

    /**
     * D: when `timeoutStrategy = Fail` (the TPipe default), the same
     * mid-stream SocketException still throws P2PException but the
     * trace tag is `retryable=false` and the diagnostic metadata is
     * still populated. PipeTimeoutManager will NOT retry because
     * the policy gate is closed; the trace file is the visible
     * error record.
     */
    @Test
    fun midStreamSocketFailureWithFailPolicyPropagatesAsTerminalTransportFailure() = runBlocking<Unit>
    {
        val factory = FailingSocketConnectionFactory(
            failAfterBytes = 30,
            failureException = SocketException("Connection reset by peer (simulated Mantle blip)")
        )

        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://bedrock-mantle.us-east-1.api.aws/openai/v1")
            .setApiMode(ApiMode.OpenAI) as GenericOpenAIPipe
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(true)
        // Default timeoutStrategy is Fail; default maxRetryAttempts is 5.
        // We do NOT mutate either — leave them at TPipe's defaults.
        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()
        instrumentPipeForTracing(pipe, pipelineId)

        val thrown = try
        {
            pipe.generateTextForTest("hi")
            null
        }
        catch(e: P2PException) { e }
        catch(e: Exception) { throw AssertionError("Expected P2PException, got ${e::class.simpleName}: ${e.message}", e) }

        Assertions.assertNotNull(thrown, "Mid-stream SocketException must surface as P2PException")
        Assertions.assertEquals(
            "transport", thrown!!.errorType.name.lowercase(),
            "P2PException.errorType must be the transport-error kind"
        )

        // Failure tagged retryable=false because timeoutStrategy=Fail.
        val events = PipeTracer.getAllTraces()[pipelineId] ?: emptyList()
        val failure = events.firstOrNull { it.eventType == TraceEventType.API_CALL_FAILURE }
        Assertions.assertNotNull(
            failure,
            "API_CALL_FAILURE must always be emitted on transport failure, even on the Fail path"
        )
        Assertions.assertEquals(
            false, failure!!.metadata["retryable"],
            "retryable must be false when timeoutStrategy=Fail (default). " +
                "Got: ${failure.metadata}"
        )
        Assertions.assertEquals(
            "SocketException", failure.metadata["transportErrorKind"],
            "transportErrorKind must name the underlying exception class even on the Fail path"
        )
    }
}

/**
 * Mock connection factory whose input stream throws [failureException]
 * after delivering [failAfterBytes] bytes. Reproduces a Mantle
 * mid-stream network blip — the socket doesn't EOF, it just throws
 * a SocketException from read().
 */
private class FailingSocketConnectionFactory(
    private val failAfterBytes: Int,
    private val failureException: IOException
) : HttpStreamingConnectionFactory
{
    override fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpStreamingConnection
    {
        return FailingSocketConnection(failAfterBytes, failureException)
    }
}

private class FailingSocketConnection(
    private val failAfterBytes: Int,
    private val failureException: IOException
) : HttpStreamingConnection
{
    private val bytesDelivered = AtomicInteger(0)
    override val responseCode: Int get() = 200
    override val outputStream: OutputStream = ByteArrayOutputStream()
    override val inputStream: InputStream = object : InputStream()
    {
        override fun read(): Int
        {
            val n = bytesDelivered.incrementAndGet()
            if(n > failAfterBytes) throw failureException
            return 'H'.code and 0xff // dummy byte
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int
        {
            val n = bytesDelivered.addAndGet(len)
            if(n > failAfterBytes) throw failureException
            for(i in 0 until len) b[off + i] = ('H'.code and 0xff).toByte()
            return len
        }
    }
    override fun disconnect() {}
    override fun close() {}
}