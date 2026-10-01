package genericOpenAIPipe

import com.TTT.Util.ReasoningStream
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Election-surface tests for [GenericOpenAIPipe.enableStreaming] reasoning
 * params. Bedrock and Ollama already expose
 * `enableStreaming(callback, showReasoning, streamReasoning)`; this test pins
 * GenericOpenAI to the same provider-module surface so a single caller-side
 * call elects internal-reasoning streaming uniformly across every provider
 * module — no caller-side `setStreamModelReasoning` duplicate.
 *
 * Contract: `streamReasoning` is the base `streamModelReasoning` election
 * point. When true (default, Bedrock parity) reasoning deltas emitted by the
 * executor ride the chunk callback channel wrapped in
 * [ReasoningStream.OPEN_TAG] / [ReasoningStream.CLOSE_TAG]; when false,
 * zero reasoning bytes and zero markers reach the callback.
 */
class GenericOpenAIReasoningElectionTest
{
    //==== canned SSE: one reasoning_content delta, then one text delta, then stop ====

    private val openaiSse = """
        data: {"id":"c1","object":"chat.completion.chunk","created":0,"model":"m","choices":[{"index":0,"delta":{"reasoning_content":"think-a"},"finish_reason":null}]}
        data: {"id":"c1","object":"chat.completion.chunk","created":0,"model":"m","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}
        data: {"id":"c1","object":"chat.completion.chunk","created":0,"model":"m","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
    """.trimIndent()

    /**
     * Drives the live streaming path for one election configuration and
     * returns the visible text. Every chunk reaching the streaming callback
     * is appended to [chunks] so the test asserts the exact deliverable
     * sequence. [configure] is applied after the base callback registration
     * so it owns the election under test.
     */
    private suspend fun drivePipe(
        configure: (GenericOpenAIPipe) -> Unit,
        chunks: MutableList<String>
    ): String
    {
        val factory = MockStreamingConnectionFactory(responseBodySupplier = { openaiSse })
        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.OpenAI)
        pipe.setModel("test-model")
        pipe.setMaxTokens(64)
        val streamingCallback: suspend (String) -> Unit = { chunk -> chunks.add(chunk) }
        pipe.setStreamingCallback(streamingCallback)
        configure(pipe)
        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()
        try
        {
            return pipe.generateTextForTest("hi")
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Test
    fun defaultElectionStreamsDelimitedReasoningSegment() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val text = drivePipe({ pipe -> pipe.enableStreaming() }, chunks)

        assertEquals("Hello", text)
        assertEquals(
            listOf(ReasoningStream.OPEN_TAG, "think-a", ReasoningStream.CLOSE_TAG, "Hello"),
            chunks,
            "streamReasoning defaults to true: reasoning bytes must ride the callback delimited"
        )
    }

    @Test
    fun explicitStreamReasoningFalseSuppressesAllReasoningBytes() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val text = drivePipe(
            { pipe -> pipe.enableStreaming(null, showReasoning = false, streamReasoning = false) },
            chunks
        )

        assertEquals("Hello", text)
        assertEquals(
            listOf("Hello"),
            chunks,
            "streamReasoning=false must deliver zero reasoning bytes and zero markers"
        )
    }

    @Test
    fun enableStreamingReturnsTheSamePipeForChaining()
    {
        val pipe = GenericOpenAIPipe()
        val returned = pipe.enableStreaming(null, showReasoning = false, streamReasoning = true)
        assertIs<GenericOpenAIPipe>(returned)
        assertEquals(pipe, returned)
    }
}
