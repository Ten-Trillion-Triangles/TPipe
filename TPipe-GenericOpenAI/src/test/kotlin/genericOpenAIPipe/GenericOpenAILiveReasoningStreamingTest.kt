package genericOpenAIPipe

import com.TTT.Util.ReasoningStream
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * RED/GREEN spec for reasoning-stream delivery on the LIVE streaming path
 * ([GenericOpenAIPipe.executeStreamingDirect] — the raw HttpURLConnection
 * line loop, the transport Codex's `forceStreaming=true` profile rides).
 *
 * The operator scoped task-4 to this live path only: the Ktor `bodyAsChannel`
 * subloops ([GenericOpenAIPipe.executeStreaming] and its `executeStreaming*`
 * helpers, reached via [GenericOpenAIPipe.sendRequest]) are legacy dead code
 * (zero production callers) and stay untouched.
 *
 * Contract under test (per the reasoning-stream normalization plan): when a
 * provider streams reasoning, the reasoning deltas ride the SAME streaming
 * callback channel as text, wrapped in a `ReasoningStream.OPEN_TAG` /
 * `ReasoningStream.CLOSE_TAG` segment, gated on the base `streamModelReasoning`
 * flag (default true). When the flag is off, zero reasoning bytes and zero
 * markers reach the callback.
 *
 * Each mode's canned SSE body carries one reasoning delta then one text delta.
 * The expected callback sequence (flag on) is:
 *   [OPEN_TAG, "think-a", CLOSE_TAG, "Hello"]
 * and (flag off):
 *   ["Hello"]
 *
 * RED today: the live path's three apiMode branches (Responses
 * `ResponseReasoningTextDelta`, OpenAI `delta.reasoning_content`, Anthropic
 * `ThinkingDelta`) only accumulate into `reasoningBuilder` and never emit, so
 * the three "on" tests fail. GREEN after wiring them to
 * `emitReasoningStreamingChunk` / `emitReasoningStreamingEnd`.
 */
class GenericOpenAILiveReasoningStreamingTest
{
    //==== canned SSE bodies: one reasoning delta then one text delta, per mode ====

    private val responsesSse = """
        event: response.reasoning_text.delta
        data: {"type":"response.reasoning_text.delta","item_id":"rs_1","output_index":0,"content_index":0,"delta":"think-a"}

        event: response.output_text.delta
        data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hello"}

        event: response.completed
        data: {"type":"response.completed","response":{"status":"completed"}}
    """.trimIndent()

    private val openaiSse = """
        data: {"id":"c1","object":"chat.completion.chunk","created":0,"model":"m","choices":[{"index":0,"delta":{"reasoning_content":"think-a"},"finish_reason":null}]}
        data: {"id":"c1","object":"chat.completion.chunk","created":0,"model":"m","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}
        data: {"id":"c1","object":"chat.completion.chunk","created":0,"model":"m","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
    """.trimIndent()

    private val anthropicSse = """
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"think-a"}}
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
    """.trimIndent()

    //==== shared drive: build a pipe for one mode, capture callback chunks ====

    /**
     * Drives the live streaming path for [apiMode] through the
     * [MockStreamingConnectionFactory] direct-transport seam and returns the
     * visible text. The pipe's single streaming callback appends every emitted
     * chunk to [chunks] so the test can assert the exact deliverable sequence.
     */
    private suspend fun driveDirectPath(
        apiMode: ApiMode,
        sseBody: String,
        streamModelReasoning: Boolean,
        chunks: MutableList<String>,
    ): String
    {
        val factory = MockStreamingConnectionFactory(responseBodySupplier = { sseBody })
        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(apiMode)
        pipe.setModel("test-model")
        pipe.setMaxTokens(64)
        pipe.setStreamModelReasoning(streamModelReasoning)
        val streamingCallback: suspend (String) -> Unit = { chunk -> chunks.add(chunk) }
        pipe.setStreamingCallback(streamingCallback)
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

    //==== OpenAI Responses (Codex-critical) ====

    @Test
    fun responsesStreamsDelimitedReasoningWhenEnabled() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val text = driveDirectPath(ApiMode.OpenAIResponses, responsesSse, true, chunks)

        Assertions.assertEquals("Hello", text)
        Assertions.assertEquals(
            listOf(ReasoningStream.OPEN_TAG, "think-a", ReasoningStream.CLOSE_TAG, "Hello"),
            chunks,
            "flag-on Responses: reasoning segment must ride the chunk callback delimited"
        )
    }

    @Test
    fun responsesSuppressesReasoningWhenDisabled() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val text = driveDirectPath(ApiMode.OpenAIResponses, responsesSse, false, chunks)

        Assertions.assertEquals("Hello", text)
        Assertions.assertEquals(listOf("Hello"), chunks,
            "flag-off Responses: zero reasoning bytes and zero markers")
    }

    //==== OpenAI chat completions ====

    @Test
    fun openAiChatStreamsDelimitedReasoningWhenEnabled() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val text = driveDirectPath(ApiMode.OpenAI, openaiSse, true, chunks)

        Assertions.assertEquals("Hello", text)
        Assertions.assertEquals(
            listOf(ReasoningStream.OPEN_TAG, "think-a", ReasoningStream.CLOSE_TAG, "Hello"),
            chunks,
            "flag-on OpenAI: delta.reasoning_content must ride the chunk callback delimited"
        )
    }

    @Test
    fun openAiChatSuppressesReasoningWhenDisabled() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val text = driveDirectPath(ApiMode.OpenAI, openaiSse, false, chunks)

        Assertions.assertEquals("Hello", text)
        Assertions.assertEquals(listOf("Hello"), chunks,
            "flag-off OpenAI: zero reasoning bytes and zero markers")
    }

    //==== Anthropic ====

    @Test
    fun anthropicStreamsDelimitedReasoningWhenEnabled() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val text = driveDirectPath(ApiMode.Anthropic, anthropicSse, true, chunks)

        Assertions.assertEquals("Hello", text)
        Assertions.assertEquals(
            listOf(ReasoningStream.OPEN_TAG, "think-a", ReasoningStream.CLOSE_TAG, "Hello"),
            chunks,
            "flag-on Anthropic: thinking_delta must ride the chunk callback delimited"
        )
    }

    @Test
    fun anthropicSuppressesReasoningWhenDisabled() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val text = driveDirectPath(ApiMode.Anthropic, anthropicSse, false, chunks)

        Assertions.assertEquals("Hello", text)
        Assertions.assertEquals(listOf("Hello"), chunks,
            "flag-off Anthropic: zero reasoning bytes and zero markers")
    }
}
