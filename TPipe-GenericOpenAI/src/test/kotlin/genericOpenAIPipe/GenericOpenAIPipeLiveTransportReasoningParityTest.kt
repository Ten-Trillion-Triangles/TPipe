package genericOpenAIPipe

import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.ReasoningStream
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Parity spec for reasoning-capture on the LIVE streaming transport
 * ([GenericOpenAIPipe.executeStreamingDirect]) across all three wire protocols.
 *
 * [GenericOpenAILiveReasoningStreamingTest] already pins the *channel* contract
 * (reasoning deltas ride the streaming callback wrapped in OPEN/CLOSE tags, gated
 * on `streamModelReasoning`). This test pins the *capture* contract: the
 * reasoning content is accumulated into the pipe's reasoning builder and survives
 * the public boundary on `MultimodalContent.modelReasoning`, **uniformly for all
 * three protocols**.
 *
 * The asymmetry under test: `executeStreamingDirect` builds its terminal
 * `MultimodalContent` with `modelReasoning = streamingReasoningText`, where the
 * `when(apiMode)` only forwarded `streamingReasoning` for `OpenAIResponses` and
 * `Anthropic`, yielding `""` for `ApiMode.OpenAI`. AND the OpenAI
 * chat-completions arm emitted `delta.reasoning_content` deltas to the channel
 * but never appended them to the reasoning builder (unlike the Responses /
 * Anthropic arms, whose capture is unconditional). The net effect: OpenAI
 * chat-completions `modelReasoning` was always empty.
 *
 * Operator decision (2026-10-09): OpenAI chat-completions must populate
 * `modelReasoning` like the other two protocols. This test asserts the
 * post-fix uniform matrix. Before the fix, rows 3 & 4 (OpenAI) are RED on
 * `result.modelReasoning`; rows 1/2 (Responses) and 5/6 (Anthropic) are GREEN.
 * After the two-line source fix, all six are GREEN.
 *
 * Drives the public [GenericOpenAIPipe.generateContent] (the plain-text
 * shortcut that returns `MultimodalContent` and preserves `modelReasoning`)
 * through the [MockStreamingConnectionFactory] direct-transport seam. No live
 * LLM, no Ktor MockEngine.
 */
class GenericOpenAIPipeLiveTransportReasoningParityTest
{
    //==== canned SSE bodies: one reasoning delta then one text delta, per protocol.
    //      Shapes mirror [GenericOpenAILiveReasoningStreamingTest].

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

    //==== shared drive: build a pipe for one protocol, capture channel + result ====

    /**
     * Drives the live streaming path for [apiMode] through the
     * [MockStreamingConnectionFactory] seam and returns both the captured
     * channel chunks and the terminal [MultimodalContent] so the test can
     * assert on `modelReasoning`.
     *
     * [setStreamingCallback] flips `streamingEnabled=true`, which is what
     * [GenericOpenAIPipe.wireStreamingEnabled] (no access profile here)
     * resolves to, so the direct transport is ridden.
     */
    private suspend fun driveDirectPath(
        apiMode: ApiMode,
        sseBody: String,
        streamModelReasoning: Boolean,
        chunks: MutableList<String>,
    ): MultimodalContent
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
            return pipe.generateContent(MultimodalContent("hi"))
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    private fun expectReasoningChannel() =
        listOf(ReasoningStream.OPEN_TAG, "think-a", ReasoningStream.CLOSE_TAG, "Hello")

    private fun expectTextOnlyChannel() = listOf("Hello")

    //==== OpenAI Responses ====

    @Test
    fun responsesCaptureReasoningWhenEnabled() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val result = driveDirectPath(ApiMode.OpenAIResponses, responsesSse, true, chunks)

        Assertions.assertEquals(expectReasoningChannel(), chunks,
            "flag-on Responses: reasoning segment must ride the chunk callback delimited")
        Assertions.assertTrue(result.modelReasoning.contains("think-a"),
            "flag-on Responses: modelReasoning must capture the reasoning builder, was '${result.modelReasoning}'")
    }

    @Test
    fun responsesCaptureReasoningWhenDisabled() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val result = driveDirectPath(ApiMode.OpenAIResponses, responsesSse, false, chunks)

        Assertions.assertEquals(expectTextOnlyChannel(), chunks,
            "flag-off Responses: zero reasoning bytes and zero markers on the channel")
        Assertions.assertTrue(result.modelReasoning.contains("think-a"),
            "flag-off Responses: capture into the reasoning builder stays unconditional, was '${result.modelReasoning}'")
    }

    //==== OpenAI chat completions (the asymmetric rows — RED before the fix) ====

    @Test
    fun openAiChatCaptureReasoningWhenEnabled() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val result = driveDirectPath(ApiMode.OpenAI, openaiSse, true, chunks)

        Assertions.assertEquals(expectReasoningChannel(), chunks,
            "flag-on OpenAI: delta.reasoning_content must ride the chunk callback delimited")
        Assertions.assertTrue(result.modelReasoning.contains("think-a"),
            "flag-on OpenAI: modelReasoning must capture the reasoning builder, was '${result.modelReasoning}'")
    }

    @Test
    fun openAiChatCaptureReasoningWhenDisabled() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val result = driveDirectPath(ApiMode.OpenAI, openaiSse, false, chunks)

        Assertions.assertEquals(expectTextOnlyChannel(), chunks,
            "flag-off OpenAI: zero reasoning bytes and zero markers on the channel")
        Assertions.assertTrue(result.modelReasoning.contains("think-a"),
            "flag-off OpenAI: capture into the reasoning builder stays unconditional, was '${result.modelReasoning}'")
    }

    //==== Anthropic ====

    @Test
    fun anthropicCaptureReasoningWhenEnabled() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val result = driveDirectPath(ApiMode.Anthropic, anthropicSse, true, chunks)

        Assertions.assertEquals(expectReasoningChannel(), chunks,
            "flag-on Anthropic: thinking_delta must ride the chunk callback delimited")
        Assertions.assertTrue(result.modelReasoning.contains("think-a"),
            "flag-on Anthropic: modelReasoning must capture the reasoning builder, was '${result.modelReasoning}'")
    }

    @Test
    fun anthropicCaptureReasoningWhenDisabled() = runBlocking<Unit>
    {
        val chunks = mutableListOf<String>()
        val result = driveDirectPath(ApiMode.Anthropic, anthropicSse, false, chunks)

        Assertions.assertEquals(expectTextOnlyChannel(), chunks,
            "flag-off Anthropic: zero reasoning bytes and zero markers on the channel")
        Assertions.assertTrue(result.modelReasoning.contains("think-a"),
            "flag-off Anthropic: capture into the reasoning builder stays unconditional, was '${result.modelReasoning}'")
    }
}
