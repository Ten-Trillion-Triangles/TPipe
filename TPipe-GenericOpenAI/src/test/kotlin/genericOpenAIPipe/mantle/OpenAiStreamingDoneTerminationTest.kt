package genericOpenAIPipe.mantle

import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.HttpStreamingConnection
import genericOpenAIPipe.HttpStreamingConnectionFactory
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Regression tests for the GenericOpenAIPipe OpenAI Chat Completions
 * streaming termination bug observed against AWS Mantle on 2026-08-02.
 *
 * The OpenAI Chat Completions spec mandates a `data: [DONE]` terminal
 * sentinel. A conforming server that keeps the TCP connection alive
 * after sending `[DONE]` — which is what Mantle does because of its
 * chunked-SigV4 streaming transport — exposes the bug: the OpenAI
 * branch of `executeStreamingDirect` feeds `[DONE]` to
 * `Json.parseToJsonElement`, the throw is swallowed by a silent
 * `catch(_: Exception)`, and the parser loop waits for socket EOF
 * that never comes. The 120-second `HttpURLConnection.readTimeoutMs`
 * is the only deadline.
 *
 * The companion terminal signal is `finish_reason: "stop"` on the
 * final choice object. A server that emits finish_reason but never
 * sends `[DONE]` also hangs.
 *
 * These tests reproduce the production condition (socket does not
 * EOF after the terminal signal) using a custom [InputStream] that
 * delivers the SSE body and then blocks on read() indefinitely,
 * exactly like Mantle's chunked-SigV4 keepalive. The existing
 * [MantleSseFixtureReplayTest] does not catch the bug because its
 * [MockStreamingConnectionFactory] uses a `ByteArrayInputStream`
 * that naturally EOFs after `[DONE]`, terminating the parser loop
 * without exercising the missing `[DONE]` guard.
 */
class OpenAiStreamingDoneTerminationTest
{
    /**
     * RED test: `data: [DONE]` after the final content delta must
     * terminate the streaming loop within milliseconds, not after
     * the 120-second socket read timeout. With the bug present the
     * test hangs past the JUnit 20-second timeout and the assertion
     * fails with `elapsed >= 20_000`.
     *
     * Fixture layout (OpenAI Chat Completions SSE wire format):
     *   1. content delta "Hel"
     *   2. content delta "lo"
     *   3. terminal chunk carrying finish_reason="stop" with empty delta
     *   4. data: [DONE]
     *
     * The mock InputStream writes the SSE body bytes then blocks on
     * read() indefinitely, mirroring Mantle's chunked-SigV4 transport
     * behavior: data is delivered, terminal sentinel may arrive, but
     * the socket never EOFs.
     */
    @Test
    fun openAiChatCompletionsStreamingTerminatesOnDoneSentinel() = runBlocking<Unit>
    {
        val sseBody = listOf(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"}}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
            "data: [DONE]"
        ).joinToString("")

        val factory = BlockingSocketConnectionFactory(sseBody.toByteArray(Charsets.UTF_8))

        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://bedrock-mantle.us-east-1.api.aws/openai/v1")
            .setApiMode(ApiMode.OpenAI)
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(true)
        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()

        try
        {
            val start = System.currentTimeMillis()
            // withTimeoutOrNull propagates cancellation into the
            // Dispatchers.IO worker that executeStreamingDirect's
            // BufferedReader.readLine() is parked on. JUnit's
            // @Timeout annotation alone does NOT preempt coroutines
            // suspended on blocking I/O — it only interrupts the
            // JUnit thread, which is the runBlocking caller, not the
            // IO worker.
            val result = withTimeoutOrNull(10_000) {
                pipe.generateTextForTest("hi")
            }
            val elapsed = System.currentTimeMillis() - start

            Assertions.assertNotNull(
                result,
                "Pipe did not terminate within 10s. The [DONE] sentinel was swallowed by the JSON parser, " +
                    "and the parser is blocked on the InputStream.read() call waiting for socket EOF that " +
                    "never arrives (Mantle's chunked-SigV4 keepalive). elapsed=${elapsed}ms"
            )
            Assertions.assertEquals(
                "Hello", result!!,
                "Streamed text should be 'Hello'. Got: '$result'"
            )
            Assertions.assertTrue(
                elapsed < 2_000,
                "Pipe must terminate on [DONE] within 2s, was ${elapsed}ms. " +
                    "If elapsed >= 20_000 the [DONE] sentinel was swallowed by the JSON parser."
            )
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    /**
     * RED test: a `finish_reason` on any choice must terminate the
     * loop even if the server never sends `[DONE]`. This is the
     * defensive layer that catches servers that omit the sentinel.
     *
     * Fixture layout: two content deltas then a terminal chunk with
     * finish_reason="stop" and no further data. The mock connection
     * stays open after the final line — same Mantle-like shape.
     */
    @Test
    fun openAiChatCompletionsStreamingTerminatesOnFinishReasonWithoutDone() = runBlocking<Unit>
    {
        val sseBody = listOf(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"}}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
        ).joinToString("")

        val factory = BlockingSocketConnectionFactory(sseBody.toByteArray(Charsets.UTF_8))

        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://bedrock-mantle.us-east-1.api.aws/openai/v1")
            .setApiMode(ApiMode.OpenAI)
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(true)
        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()

        try
        {
            val start = System.currentTimeMillis()
            val result = withTimeoutOrNull(10_000) {
                pipe.generateTextForTest("hi")
            }
            val elapsed = System.currentTimeMillis() - start

            Assertions.assertNotNull(
                result,
                "Pipe did not terminate within 10s. The finish_reason was ignored, " +
                    "and the parser is blocked on the InputStream.read() call waiting for socket EOF " +
                    "that never arrives. elapsed=${elapsed}ms"
            )
            Assertions.assertEquals(
                "Hello", result!!,
                "Streamed text should be 'Hello'. Got: '$result'"
            )
            Assertions.assertTrue(
                elapsed < 2_000,
                "Pipe must terminate on finish_reason within 2s, was ${elapsed}ms. " +
                    "If elapsed >= 20_000 the finish_reason was ignored."
            )
        }
        finally
        {
            pipe.abortForTest()
        }
    }
}

/**
 * Mock connection factory that returns a [BlockingSocketConnection]
 * whose input stream delivers a canned body and then blocks on
 * read() indefinitely. Reproduces Mantle's chunked-SigV4 transport
 * behavior: data is delivered, terminal sentinel may arrive, but
 * the socket never EOFs. The 120-second
 * `HttpURLConnection.readTimeoutMs` would be the only deadline if
 * the parser does not recognize the terminal sentinel — but the
 * mock does not honor a socket-level read timeout (there is no
 * socket), so reads block forever. JUnit's `@Timeout` is the test
 * harness's deadline.
 */
private class BlockingSocketConnectionFactory(
    private val responseBody: ByteArray,
    private val statusCode: Int = 200
) : HttpStreamingConnectionFactory
{
    var capturedUrl: String = ""
        private set
    var capturedMethod: String = ""
        private set
    var capturedHeaders: Map<String, String> = emptyMap()
        private set

    override fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpStreamingConnection
    {
        capturedUrl = url
        capturedMethod = method
        capturedHeaders = headers
        // Use a 5-second read timeout instead of the production
        // 120_000ms so a RED test (parser hangs because [DONE] is
        // not handled) fails in ~5s instead of ~120s. The bug
        // shape is identical: socket reads block past the SSE
        // terminal sentinel because the parser only knows how to
        // stop on socket EOF.
        return BlockingSocketConnection(responseBody, statusCode, 5_000L)
    }
}

/**
 * Custom [InputStream] that returns the bytes from [body] on read,
 * then blocks on read() indefinitely once exhausted. Mirrors Mantle's
 * chunked-SigV4 streaming transport: bytes flow, but the socket
 * never EOFs. close() wakes any blocked reader so the test JVM can
 * exit cleanly after JUnit's timeout fires.
 */
private class BlockingSocketConnection(
    body: ByteArray,
    private val statusCode: Int,
    readTimeoutMs: Long
) : HttpStreamingConnection
{
    private val input: InputStream = BlockingInputStream(body, readTimeoutMs)

    override val responseCode: Int get() = statusCode
    override val outputStream: OutputStream = ByteArrayOutputStream()
    override val inputStream: InputStream get() = input

    override fun disconnect()
    {
        try { input.close() } catch(_: Exception) {}
    }

    override fun close()
    {
        try { input.close() } catch(_: Exception) {}
    }
}

private class BlockingInputStream(
    private val body: ByteArray,
    private val readTimeoutMs: Long
) : InputStream()
{
    private var position = 0
    @Volatile private var closed = false
    private val lock = Any()
    private val createdAtMs = System.currentTimeMillis()

    override fun read(): Int
    {
        while(!closed)
        {
            if(position < body.size)
            {
                val b = body[position].toInt() and 0xff
                position++
                return b
            }
            // Buffer drained but not closed — block until close() wakes us,
            // or until readTimeoutMs elapses (mimics HttpURLConnection's
            // SO_TIMEOUT, which is what production executeStreamingDirect
            // relies on as the only deadline when the parser fails to
            // recognize the SSE terminal sentinel).
            val waitStart = System.currentTimeMillis()
            synchronized(lock) {
                try { (lock as java.lang.Object).wait(readTimeoutMs) }
                catch(e: InterruptedException) { Thread.currentThread().interrupt() }
            }
            if(Thread.interrupted())
            {
                return -1
            }
            val elapsed = System.currentTimeMillis() - waitStart
            if(elapsed >= readTimeoutMs - 50 && !closed)
            {
                throw java.net.SocketTimeoutException(
                    "Read timed out after ${readTimeoutMs}ms. " +
                        "The parser is blocked waiting for data that never arrives " +
                        "(Mantle chunked-SigV4 keepalive). elapsed=${System.currentTimeMillis() - createdAtMs}ms"
                )
            }
        }
        return -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int
    {
        while(!closed)
        {
            if(position < body.size)
            {
                val available = body.size - position
                val copy = minOf(len, available)
                System.arraycopy(body, position, b, off, copy)
                position += copy
                return copy
            }
            val waitStart = System.currentTimeMillis()
            synchronized(lock) {
                try { (lock as java.lang.Object).wait(readTimeoutMs) }
                catch(e: InterruptedException) { Thread.currentThread().interrupt() }
            }
            if(Thread.interrupted())
            {
                return -1
            }
            val elapsed = System.currentTimeMillis() - waitStart
            if(elapsed >= readTimeoutMs - 50 && !closed)
            {
                throw java.net.SocketTimeoutException(
                    "Read timed out after ${readTimeoutMs}ms. " +
                        "The parser is blocked waiting for data that never arrives " +
                        "(Mantle chunked-SigV4 keepalive). elapsed=${System.currentTimeMillis() - createdAtMs}ms"
                )
            }
        }
        return -1
    }

    override fun close()
    {
        closed = true
        synchronized(lock) { (lock as java.lang.Object).notifyAll() }
    }
}