package genericOpenAIPipe

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Test double for [HttpStreamingConnectionFactory] used by
 * [genericOpenAIPipe.api.OpenAIResponsesPipeDispatchTest]. Captures the request
 * body and returns a canned SSE body from [responseBodySupplier].
 *
 * The [responseBodySupplier] is invoked exactly once when the connection is opened,
 * before [HttpStreamingConnection.inputStream] is read. This is intentional:
 * it lets tests construct the supplier AFTER [GenericOpenAIPipe.init] runs and
 * captures the URL/headers, but BEFORE [GenericOpenAIPipe.generateTextForTest]
 * reads the SSE body.
 *
 * Usage pattern:
 * ```kotlin
 * val factory = MockStreamingConnectionFactory(responseBodySupplier = { sseBody })
 * pipe.injectStreamingConnectionFactoryForTest(factory)
 * pipe.initForTest()
 * val text = pipe.generateTextForTest("hi")
 * Assertions.assertEquals("Hello", text)
 * Assertions.assertTrue(factory.capturedRequestBody.contains("\"input\""))
 * ```
 */
internal class MockStreamingConnectionFactory(
    private val responseBodySupplier: () -> String,
    private val statusCode: Int = 200
) : HttpStreamingConnectionFactory
{
    var capturedRequestBody: String = ""
        private set
    var capturedUrl: String = ""
        private set
    var capturedMethod: String = ""
        private set
    var capturedHeaders: Map<String, String> = emptyMap()
        private set
    var capturedConnectTimeoutMs: Int = 0
        private set
    var capturedReadTimeoutMs: Int = 0
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
        capturedConnectTimeoutMs = connectTimeoutMs
        capturedReadTimeoutMs = readTimeoutMs
        return MockStreamingConnection(responseBodySupplier, statusCode) { body ->
            capturedRequestBody = body
        }
    }
}

private class MockStreamingConnection(
    responseBodySupplier: () -> String,
    private val statusCode: Int,
    private val captureBody: (String) -> Unit
) : HttpStreamingConnection
{
    private val responseBody: String = responseBodySupplier()
    private val bodyOut = ByteArrayOutputStream()
    private val bodyIn: InputStream = ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8))

    override val responseCode: Int get() = statusCode

    override val outputStream: OutputStream = object : OutputStream()
    {
        override fun write(b: Int) { bodyOut.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { bodyOut.write(b, off, len) }
        override fun flush()
        {
            captureBody(bodyOut.toString(Charsets.UTF_8))
        }
        override fun close()
        {
            // Pipe writes the body via `outputStream.use { ... }` which closes after
            // the JSON write completes. Capture on close so the request body is
            // visible to assertions after generateTextForTest returns.
            captureBody(bodyOut.toString(Charsets.UTF_8))
        }
    }

    override val inputStream: InputStream get() = bodyIn

    override fun disconnect() {}

    override fun close() {}
}