package genericOpenAIPipe

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Pipe.Pipe
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions

/**
 * Pins the contract that input tokens billed by Anthropic streaming endpoints
 * actually appear in the API_CALL_SUCCESS trace event metadata.
 *
 * Background — two coupled bugs surfaced during a tracing audit:
 *
 *  1. `executeStreamingDirect` (HttpURLConnection path) and the Ktor
 *     `executeStreaming(... executeStreamingAnthropic ...)` path both
 *     ignore the `message_start` SSE event that Anthropic fires at the
 *     start of every stream. `message_start.usage.input_tokens` is the
 *     official source of billed input tokens, but the production SSE loop
 *     only reads `content_block_delta` (for text) and `message_delta`
 *     (for stop_reason). The accumulator `totalInputTokens` stays 0 for
 *     Anthropic streams even when the provider billed thousands of input
 *     tokens.
 *
 *  2. The metadata-assembly guard at the bottom of both streaming paths
 *     (`GenericOpenAIPipe.kt:1502` for the HttpURLConnection path and
 *     `:1691` for the Ktor path) hard-codes
 *     `streamingInputTok = 0` for anything that is not
 *     `ApiMode.OpenAIResponses`. Even if bug 1 is fixed and the value
 *     reaches the metadata map, the gate throws it away for
 *     `ApiMode.Anthropic`. The visualizer's token card then reads
 *     `inputTokens = 0` for the whole turn.
 *
 * These tests fail RED today (against the unpatched production code) and
 * go GREEN once both bugs are fixed. They cover both the wire-level
 * capture AND the trace-event surface (`PipeTracer.getTrace(...)`
 * API_CALL_SUCCESS metadata).
 */
class StreamingInputTokenTracingTest
{

    private val tracePipelineId = "streaming-input-token-tracing-test"

    @BeforeEach
    fun enableTracing()
    {
        PipeTracer.enable()
        PipeTracer.startTrace(tracePipelineId)
    }

    @AfterEach
    fun disableTracing()
    {
        PipeTracer.getAllTraces().keys.forEach { PipeTracer.clearTrace(it) }
        PipeTracer.disable()
    }

    //=============================================Bug 1: wire capture============================================

    @Test
    fun anthropicStreaming_capturesInputTokensFromMessageStart() = runBlocking<Unit>
    {
        // Wire payload faithfully mirrors an Anthropic /v1/messages stream that
        // reports 137 input tokens in the opening message_start event and
        // 42 output tokens in the closing message_delta. The contract under
        // test: both numbers must reach the API_CALL_SUCCESS metadata emitted
        // by `executeStreamingDirect` after the SSE loop completes.
        val sseBody = buildAnthropicStreamWithInputTokens(inputTokens = 137, outputTokens = 42)

        val (pipe, _) = streamingPipeAnthropic(sseBody)
        try
        {
            val text = pipe.generateTextForTest("hi")
            Assertions.assertEquals("Hello", text, "Streamed text should be 'Hello'")

            val successEvent = singleApiCallSuccessEvent()

            // The provider reported 137 input tokens in message_start.usage.
            // The trace MUST carry them. Today this fails with inputTokens == 0
            // because (a) the SSE loop in `executeStreamingDirect` never
            // reads message_start.usage, and (b) the metadata gate at
            // `GenericOpenAIPipe.kt:1502` hard-zeros inputTokens for
            // ApiMode.Anthropic. Both are pinned by this assertion.
            Assertions.assertEquals(
                137, readIntMetadata(successEvent, "inputTokens"),
                "API_CALL_SUCCESS.metadata.inputTokens must reflect the 137 tokens " +
                    "Anthropic billed in message_start.usage.input_tokens. " +
                    "Capture path: executeStreamingDirect Anthropic branch must read " +
                    "message_start.usage.input_tokens into totalInputTokens, and " +
                    "metadata-assembly must NOT zero inputTokens for ApiMode.Anthropic. " +
                    "Actual event: " + successEvent
            )
            Assertions.assertEquals(
                42, readIntMetadata(successEvent, "outputTokens"),
                "API_CALL_SUCCESS.metadata.outputTokens must reflect the 42 tokens " +
                    "Anthropic billed in message_delta.usage.output_tokens."
            )
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Test
    fun anthropicStreaming_messageStartInputTokensAreNotOverwrittenByMessageDeltaZero() = runBlocking<Unit>
    {
        // Some Anthropic SDK versions report `input_tokens` only on the
        // opening message_start event and either omit or zero-out the field
        // on message_delta. The trace must capture the FIRST accurate value
        // (137 from message_start) and not be erased by a later
        // input_tokens=0 in message_delta. This pins that the message_start
        // path is the canonical input-token source — it cannot simply be
        // skipped because message_delta also has the field.
        val sseBody = buildString {
            append("event: message_start\n")
            append("""data: {"type":"message_start","message":{"id":"msg_y","type":"message","role":"assistant","usage":{"input_tokens":137,"output_tokens":1}},"usage":{"input_tokens":137,"output_tokens":1}}""")
            append("\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""")
            append("\n\n")
            append("event: message_delta\n")
            append("""data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":0,"output_tokens":5}}""")
            append("\n\n")
            append("event: message_stop\n")
            append("""data: {"type":"message_stop"}""")
            append("\n\n")
        }

        val (pipe, _) = streamingPipeAnthropic(sseBody)
        try
        {
            pipe.generateTextForTest("hi")
            val successEvent = singleApiCallSuccessEvent()
            Assertions.assertEquals(
                137, readIntMetadata(successEvent, "inputTokens"),
                "First accurate input-token value (137, from message_start.usage) " +
                    "must be carried into the trace. Today this fails because " +
                    "`executeStreamingDirect`'s Anthropic branch discards " +
                    "message_start events entirely and the metadata gate " +
                    "zeroes inputTokens for ApiMode.Anthropic."
            )
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    //=============================================Bug 2: metadata gate===========================================

    @Test
    fun anthropicStreaming_totalTokensEqualsInputPlusOutput() = runBlocking<Unit>
    {
        // Targets the `totalTokens` synthesis at the bottom of the streaming
        // path. In executeStreamingDirect at `GenericOpenAIPipe.kt:1526`:
        //
        //     "totalTokens" to (streamingInputTok + streamingOutputTok)
        //
        // Today, streamingInputTok is gated to 0 for ApiMode.Anthropic, so
        // totalTokens collapses to outputTokens alone. After the fix, the
        // trace should report totalTokens == inputTokens + outputTokens.
        val sseBody = buildAnthropicStreamWithInputTokens(inputTokens = 200, outputTokens = 33)

        val (pipe, _) = streamingPipeAnthropic(sseBody)
        try
        {
            pipe.generateTextForTest("hi")
            val successEvent = singleApiCallSuccessEvent()
            val input = readIntMetadata(successEvent, "inputTokens") ?: -1
            val output = readIntMetadata(successEvent, "outputTokens") ?: -1
            val total = readIntMetadata(successEvent, "totalTokens") ?: -1
            Assertions.assertEquals(
                200, input,
                "inputTokens must be 200 (Anthropic billed 200)."
            )
            Assertions.assertEquals(
                33, output,
                "outputTokens must be 33 (Anthropic billed 33 in message_delta)."
            )
            Assertions.assertEquals(
                233, total,
                "totalTokens must equal inputTokens + outputTokens. " +
                    "Today total == output only because input is gated to 0."
            )
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    //=============================================Sanity: Responses path already works=================================

    @Test
    fun openAiResponsesStreaming_inputTokensAlreadyFlowThrough() = runBlocking<Unit>
    {
        // Sanity pin: the OpenAIResponses-mode streaming path is already
        // correct (it captures usage.inputTokens from `response.completed`).
        // This test MUST keep passing through any future refactor — if it
        // starts failing, the Responses path regressed.
        val sseBody = """
event: response.created
data: {"type":"response.created","response":{"id":"resp_p","status":"in_progress","model":"MiniMax-M2.7"}}

event: response.output_text.delta
data: {"type":"response.output_text.delta","item_id":"msg_p","output_index":0,"content_index":0,"delta":"Hi"}

event: response.completed
data: {"type":"response.completed","response":{"id":"resp_p","status":"completed","model":"MiniMax-M2.7","usage":{"input_tokens":99,"output_tokens":7,"total_tokens":106}}}

""".trimIndent()

        val (pipe, _) = streamingPipeOpenAIResponses(sseBody)
        try
        {
            pipe.generateTextForTest("hi")
            val successEvent = singleApiCallSuccessEvent()
            Assertions.assertEquals(99, readIntMetadata(successEvent, "inputTokens"))
            Assertions.assertEquals(7, readIntMetadata(successEvent, "outputTokens"))
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    //=============================================Helpers==========================================================

    /**
     * Reproduces the `streamingPipe` helper from OpenAIResponsesPipeDispatchTest
     * but wired for ApiMode.Anthropic. Drives the `executeStreamingDirect`
     * code path via the existing `MockStreamingConnectionFactory` seam. The
     * SseParser parity for Anthropic is already pinned by the existing
     * AnthropicStreamingDispatchTest, so this test stays focused on
     * token-capture semantics rather than parser correctness.
     */
    private suspend fun streamingPipeAnthropic(sseBody: String): Pair<GenericOpenAIPipe, MockStreamingConnectionFactory>
    {
        val factory = MockStreamingConnectionFactory(responseBodySupplier = { sseBody })

        val pipe = GenericOpenAIPipe()
            .setApiKey("mock-key")
            .setBaseUrl("https://mock.local/v1")
            .setApiMode(ApiMode.Anthropic)

        pipe.setModel("claude-3-5-sonnet-20241022")
        pipe.setMaxTokens(64)
        pipe.setStreamingEnabled(true)
        pipe.injectStreamingConnectionFactoryForTest(factory)

        instrumentStreamingPipe(pipe, tracePipelineId)
        pipe.initForTest()
        return pipe to factory
    }

    /**
     * Same shape as [streamingPipeAnthropic] but for OpenAIResponses — used
     * by the sanity test that already passes and pins the unaffected path.
     */
    private suspend fun streamingPipeOpenAIResponses(sseBody: String): Pair<GenericOpenAIPipe, MockStreamingConnectionFactory>
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

        instrumentStreamingPipe(pipe, tracePipelineId)
        pipe.initForTest()
        return pipe to factory
    }

    /**
     * Build a minimal Anthropic SSE body that carries [inputTokens] on the
     * opening message_start and [outputTokens] on the closing message_delta.
     * The streamed text is "Hello" so the assertion target is obvious.
     */
    private fun buildAnthropicStreamWithInputTokens(inputTokens: Int, outputTokens: Int): String = buildString {
        append("event: message_start\n")
        append("""data: {"type":"message_start","message":{"id":"msg_a","type":"message","role":"assistant","usage":{"input_tokens":$inputTokens,"output_tokens":1}},"usage":{"input_tokens":$inputTokens,"output_tokens":1}}""")
        append("\n\n")
        append("event: content_block_start\n")
        append("""data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
        append("\n\n")
        append("event: content_block_delta\n")
        append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""")
        append("\n\n")
        append("event: content_block_stop\n")
        append("""data: {"type":"content_block_stop","index":0}""")
        append("\n\n")
        append("event: message_delta\n")
        append("""data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens}}""")
        append("\n\n")
        append("event: message_stop\n")
        append("""data: {"type":"message_stop"}""")
        append("\n\n")
    }

    /**
     * Wire tracing on the pipe with the test pipeline id. Mirrors
     * `TracingTestSupport.instrumentPipeForTracing` without the HTML output
     * to disk — these are contract-pinning tests, not live LLM tests, so
     * the trace artifact on disk is noise.
     */
    private fun instrumentStreamingPipe(pipe: Pipe, pipelineId: String)
    {
        val traceConfig = TraceConfig(
            enabled = true,
            includeMetadata = true
        )
        pipe.enableTracing(traceConfig)
        pipe.addTraceId(pipelineId)
    }

    /**
     * Find the single API_CALL_SUCCESS event in the recorded trace and
     * return it. Fails if there isn't exactly one.
     */
    private fun singleApiCallSuccessEvent(): TraceEvent
    {
        val events = PipeTracer.getTrace(tracePipelineId)
        val successEvents = events.filter { it.eventType == TraceEventType.API_CALL_SUCCESS }
        Assertions.assertEquals(
            1, successEvents.size,
            "Expected exactly one API_CALL_SUCCESS event for pipelineId=$tracePipelineId, " +
                "got ${successEvents.size}. All events: " +
                events.joinToString { "${it.eventType}@${it.timestamp}" }
        )
        return successEvents.first()
    }

    /**
     * Read an Int metadata field from a trace event, returning it as a
     * nullable Int. Mirrors TraceVisualizer's `readTokenField` logic — the
     * trace metadata Map can store numbers as Int, Long, or as a String,
     * depending on which code path wrote it.
     */
    private fun readIntMetadata(event: TraceEvent, key: String): Int?
    {
        val raw = event.metadata[key] ?: return null
        return when (raw)
        {
            is Int -> raw
            is Long -> raw.toInt()
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> raw.toString().toIntOrNull()
        }
    }
}
