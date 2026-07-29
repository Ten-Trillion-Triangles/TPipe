package genericOpenAIPipe

import com.TTT.Debug.PipeTracer
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.env.ReasoningConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live integration test for [GenericOpenAIPipe] against Amazon Bedrock
 * Mantle. Exercises both Chat Completions and Responses API paths against
 * the `google.gemma-4-31b` model.
 *
 * Gating: runs ONLY when both env vars are set:
 *   - `BEDROCK_MANTLE_LIVE_TEST=true`
 *   - `AWS_ACCESS_KEY_ID` (and `AWS_SECRET_ACCESS_KEY`) are configured
 *
 * When the gate is not satisfied, the test class is skipped cleanly (no
 * failures), matching the JUnit 5 idiom.
 *
 * Run with:
 * ```
 * BEDROCK_MANTLE_LIVE_TEST=true \
 * AWS_ACCESS_KEY_ID=... \
 * AWS_SECRET_ACCESS_KEY=... \
 * BEDROCK_MANTLE_REGION=us-east-1 \
 * ./gradlew :TPipe-GenericOpenAI:test --tests "*BedrockMantleLiveTest"
 * ```
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_MANTLE_LIVE_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BedrockMantleLiveTest
{
    companion object
    {
        private const val DEFAULT_MODEL: String = "google.gemma-4-31b"
        private const val TEST_PROMPT: String = "Reply with the single word 'pong'."
        // A prompt that REQUIRES multi-step reasoning to answer correctly.
        // Mantle's Gemma 4 31B (89.2% AIME 2026 with thinking) will only emit
        // a `reasoning` output item when the prompt demands non-trivial
        // thought; trivial prompts return a single `message` output with no
        // reasoning. The trains problem is small enough to keep the live test
        // bounded in tokens and latency but complex enough to force reasoning.
        private const val REASONING_PROMPT: String =
            "If a train leaves Boston at 9am traveling at 60 mph, and another " +
            "train leaves New York at 10am traveling at 80 mph toward Boston, " +
            "and the distance between the cities is 220 miles, at what time do " +
            "the two trains meet? Show your reasoning step by step, then give " +
            "the final answer in hours and minutes past 9am."
        // 32 = a safe default for both /chat/completions (no minimum) and
        // /responses (Mantle requires max_output_tokens >= 16). 8 worked for
        // /chat but Mantle's /responses API rejects any value below 16.
        private const val MAX_TOKENS: Int = 32
        // Generous budget for the reasoning test: Gemma 4's thinking trace
        // alone routinely consumes 1000+ tokens before the visible answer
        // lands — earlier empirically-observed runs exhausted 1024 tokens
        // entirely on a single reasoning output item (status=incomplete)
        // and never produced the message. 8192 leaves plenty of headroom
        // for both the reasoning trace and the visible final answer.
        private const val REASONING_MAX_TOKENS: Int = 8192
    }

    @BeforeAll
    fun enableTracingForAllTests()
    {
        setupTraceDirectory(BedrockMantleLiveTest::class.java)
        PipeTracer.enable()
        PipeTracer.startTrace("bedrock-mantle-live")
    }

    @AfterAll
    fun disableTracingForAllTests()
    {
        // Export any in-memory traces to disk for post-run inspection.
        try {
            val out = java.io.File(System.getProperty("user.home") + "/.tpipe/debug/trace/bedrock-mantle-trace.txt")
            out.parentFile?.mkdirs()
            val allTraces = PipeTracer.getAllTraces()
            var totalEvents = 0
            out.writeText("Pipeline IDs: ${allTraces.keys}\n\n")
            for ((pipelineId, events) in allTraces) {
                totalEvents += events.size
                out.appendText("=== Pipeline: $pipelineId (${events.size} events) ===\n")
                events.forEachIndexed { i, event ->
                    val md = event.metadata.entries.joinToString(", ") { "${it.key}=${truncate(it.value.toString())}" }
                    out.appendText("  [$i] ${event.eventType} phase=${event.phase}\n")
                    out.appendText("      metadata: $md\n")
                    event.content?.let { c ->
                        out.appendText("      content: ${truncate(c.text)}\n")
                    }
                }
                out.appendText("\n")
            }
            out.appendText("Total events: $totalEvents\n")
            println("Trace exported to: ${out.absolutePath} (${out.length()} bytes)")
        } catch (e: Exception) {
            println("Trace export failed: ${e.message}")
            e.printStackTrace()
        }
        PipeTracer.getAllTraces().keys.forEach { PipeTracer.clearTrace(it) }
        PipeTracer.disable()
    }

    private fun truncate(s: String, max: Int = 500): String =
        if (s.length <= max) s else s.take(max) + "...[truncated ${s.length - max} chars]"

    @Test
    fun testMantleChatCompletions() = runBlocking<Unit>
    {
        assumeCredentialsConfigured()
        val region = resolveRegion()
        val pipe = GenericOpenAIPipe()
            .setBedrockMantle(region, DEFAULT_MODEL)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(0.0)
            .enableTracing(traceConfig())
            .also { it.addTraceId("bedrock-mantle-live") }
            .init()

        println("Sending Mantle Chat Completions request (region=$region, model=$DEFAULT_MODEL)...")
        val response = pipe.execute(TEST_PROMPT)
        println("Response: $response")

        assertNotNull(response, "Chat Completions response must not be null")
        assertTrue(
            response.contains("pong", ignoreCase = true),
            "Expected response to contain 'pong'. Got: $response"
        )
    }

    @Test
    fun testMantleResponses() = runBlocking<Unit>
    {
        assumeCredentialsConfigured()
        val region = resolveRegion()
        val pipe = GenericOpenAIPipe()
            .setBedrockMantleWithResponses(region, DEFAULT_MODEL)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(0.0)
            .enableTracing(traceConfig())
            .init()

        println("Sending Mantle Responses API request (region=$region, model=$DEFAULT_MODEL)...")
        val response = pipe.execute(TEST_PROMPT)
        println("Response: $response")

        assertNotNull(response, "Responses API response must not be null")
        assertTrue(
            response.contains("pong", ignoreCase = true),
            "Expected response to contain 'pong'. Got: $response"
        )
    }

    /**
     * Reasoning-mode end-to-end coverage on the Mantle `/responses` endpoint.
     *
     * Gemma 4 31B is purpose-built with a native thinking mode (AIME 2026
     * 89.2% with thinking on, vs 20.8% off — see the AWS Gemma 4 launch
     * blog and the [google/gemma-4-31B-it] HF model card). On the Mantle
     * Responses API surface, reasoning is exposed through a separate
     * `output` item of type `reasoning` — distinct from the final
     * `message` item — and is toggled per request via
     * `reasoning: {"effort": "high"}`.
     *
     * This test exercises the surface end-to-end through TPipe:
     *   - Sets [ReasoningConfig] with `effort = "high"` via [setReasoningConfig].
     *   - Calls `pipe.execute(MultimodalContent(...))` and asserts the
     *     visible answer text is non-empty.
     *   - Asserts `response.modelReasoning` is non-blank, confirming the
     *     wire-side reasoning content survives the public boundary
     *     (the pipe populates it from the parsed response just like
     *     BedrockPipe and OllamaPipe do).
     *   - Reads the persisted PipeTracer trace dump (written by
     *     `@AfterAll disableTracingForAllTests`) and asserts that an
     *     [TraceEventType.API_CALL_SUCCESS] event for our pipeline has
     *     `reasoningLength` metadata > 0 (Base `Pipe.trace()` injects
     *     `reasoningContent` / `reasoningLength` from `MultimodalContent.
     *     modelReasoning` whenever an API call surfaces reasoning).
     *     The trace check is a belt-and-suspenders guard against a
     *     future regression in the internal `trace(API_CALL_SUCCESS,
     *     content=result, ...)` call.
     *
     * No specialty "request builder" is needed for Gemma 4 on TPipe:
     * `setReasoningConfig` is the standard TPipe builder for thinking
     * parameters, and [OpenAIResponsesRequestSerializer] translates it
     * to the OpenAI Responses API wire shape (`reasoning` field).
     */
    @Test
    fun testMantleResponsesWithReasoning() = runBlocking<Unit>
    {
        assumeCredentialsConfigured()
        val region = resolveRegion()
        // Build a GenericOpenAIPipe up front (setBedrockMantleWithResponses
        // is a GenericOpenAIPipe-only method), then set remaining config +
        // reasoning using a typed reference. setMaxTokens / setTemperature
        // declared on Pipe return Pipe, which would orphan the chain into a
        // base-typed receiver — so we use a typed val + reassignment.
        val configuredPipe: GenericOpenAIPipe = GenericOpenAIPipe()
            .setBedrockMantleWithResponses(region, DEFAULT_MODEL)
            .also { it.setMaxTokens(REASONING_MAX_TOKENS) }
            .also { it.setTemperature(1.0) }
            .also { it.enableTracing(traceConfig()) }
            .also { it.addTraceId("bedrock-mantle-live") }
            .also { it.setReasoningConfig(ReasoningConfig(effort = "high")) }
            .also { it.init() }

        println("Sending Mantle Responses API request WITH REASONING (region=$region, model=$DEFAULT_MODEL, effort=high)...")
        val input = com.TTT.Pipe.MultimodalContent(text = REASONING_PROMPT)
        val response: com.TTT.Pipe.MultimodalContent = configuredPipe.execute(input)
        println("Response text length: ${response.text.length}, snippet: ${response.text.take(200)}")
        println("Response modelReasoning (length=${response.modelReasoning.length}): ${response.modelReasoning.take(500)}")

        assertNotNull(response, "Reasoning-API response must not be null")
        assertTrue(
            response.text.isNotBlank(),
            "Expected non-empty visible answer for the trains problem. Got: ${response.text}"
        )
        // Asserts the wire-side reasoning content (the hidden chain-of-thought
        // emitted as a separate `reasoning` output item on the Responses
        // API) round-trips through `pipe.execute(MultimodalContent(...))
        // .modelReasoning` — the same surface BedrockPipe and OllamaPipe
        // expose.
        assertTrue(
            response.modelReasoning.isNotBlank(),
            "Expected modelReasoning to be non-blank when reasoning.effort=high. " +
                "If this fails, check that generateContent()'s plain-text shortcut " +
                "goes through generateTextMultimodal() (which preserves reasoning " +
                "via response.reasoningContent) rather than wrapping a String-returning " +
                "generateText() in a fresh MultimodalContent(text=...)."
        )

        // Belt-and-suspenders: also confirm the trace event has
        // reasoningLength > 0.
        val traceOut = java.io.File(System.getProperty("user.home") + "/.tpipe/last-trace-events.txt")
        traceOut.parentFile?.mkdirs()
        val sb = StringBuilder()
        var foundReasoningMetadata = false
        for ((pipelineId, events) in PipeTracer.getAllTraces()) {
            if (pipelineId != "bedrock-mantle-live") continue
            for (event in events) {
                sb.append("--- ${event.eventType} ${event.phase}\n")
                for ((k, v) in event.metadata) {
                    sb.append("  $k = ${truncateForTrace(v.toString())}\n")
                }
                event.content?.let { c -> sb.append("  content.text = ${truncateForTrace(c.text)}\n") }
                sb.append("\n")
                if (event.metadata.containsKey("reasoningLength") && (event.metadata["reasoningLength"] as? Number)?.toLong() ?: 0L > 0L) {
                    foundReasoningMetadata = true
                }
                if (event.metadata.containsKey("reasoningContent") && (event.metadata["reasoningContent"] as? String)?.isNotBlank() == true) {
                    foundReasoningMetadata = true
                }
            }
        }
        traceOut.writeText(sb.toString())

        assertTrue(
            foundReasoningMetadata,
            "Expected an API_CALL_SUCCESS trace event with reasoningLength>0 or non-blank reasoningContent. " +
                "Full per-run trace dump: ${traceOut.absolutePath}"
        )
    }

    /**
     * Streaming-reasoning coverage on the Mantle `/v1/responses` endpoint.
     *
     * The streaming path is exercised end-to-end through TPipe: SSE
     * `response.reasoning.delta` events accumulate into
     * `streamingReasoningText`, which the parser surfaces as
     * `ResponseReasoningTextDelta` events. The streaming helper
     * (`generateTextMultimodal`) returns a [MultimodalContent] whose
     * `modelReasoning` carries that accumulated reasoning all the way
     * out to `pipe.execute(...)`.
     *
     * This is the streaming counterpart to [testMantleResponsesWithReasoning].
     * The non-streaming test reads the wire body once and the parser
     * populates `response.reasoningContent` directly; here the parser
     * has to accumulate reasoning across SSE events as they arrive.
     */
    @Test
    fun testMantleResponsesStreamingWithReasoning() = runBlocking<Unit>
    {
        assumeCredentialsConfigured()
        val region = resolveRegion()
        val configuredPipe: GenericOpenAIPipe = GenericOpenAIPipe()
            .setBedrockMantleWithResponses(region, DEFAULT_MODEL)
            .also { it.setMaxTokens(REASONING_MAX_TOKENS) }
            .also { it.setTemperature(1.0) }
            .also { it.setStreamingEnabled(true) }
            .also { it.enableTracing(traceConfig()) }
            .also { it.addTraceId("bedrock-mantle-live") }
            .also { it.setReasoningConfig(ReasoningConfig(effort = "high")) }
            .also { it.init() }

        println("Sending Mantle Responses API STREAMING request WITH REASONING (region=$region, model=$DEFAULT_MODEL, effort=high)...")
        val input = com.TTT.Pipe.MultimodalContent(text = REASONING_PROMPT)
        val response: com.TTT.Pipe.MultimodalContent = configuredPipe.execute(input)
        println("Streaming response text length: ${response.text.length}, snippet: ${response.text.take(200)}")
        println("Streaming response modelReasoning (length=${response.modelReasoning.length}): ${response.modelReasoning.take(500)}")

        assertNotNull(response, "Streaming Reasoning-API response must not be null")
        assertTrue(
            response.text.isNotBlank(),
            "Expected non-empty visible answer on the streaming path. Got: ${response.text}"
        )
        // Asserts reasoning content captured via the streaming
        // `response.reasoning.delta` SSE events round-trips through
        // `pipe.execute(MultimodalContent(...)).modelReasoning`.
        assertTrue(
            response.modelReasoning.isNotBlank(),
            "Expected streaming modelReasoning to be non-blank when reasoning.effort=high. " +
                "If this fails, check that executeStreamingDirect(...) returns MultimodalContent " +
                "and that generateTextMultimodal's streaming shortcut forwards it without " +
                "wrapping in MultimodalContent(text=...)."
        )
    }

    private fun truncateForTrace(s: String, max: Int = 240): String =
        if (s.length <= max) s else s.take(max) + "...[truncated ${s.length - max} chars]"

    //================================================StreamingTests================================================

    /**
     * Bearer streaming against the Mantle Chat Completions endpoint.
     * Validates that the SSE wire format is byte-equivalent to OpenAI's
     * Chat Completions streaming and that the parser reconstructs the
     * text correctly.
     */
    @Test
    fun testMantleChatCompletionsStreaming() = runBlocking<Unit>
    {
        assumeCredentialsConfigured()
        val region = resolveRegion()
        val pipe = GenericOpenAIPipe()
            .setBedrockMantle(region, DEFAULT_MODEL)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(0.0)
            .setStreamingEnabled(true)
            .enableTracing(traceConfig())
            .init()

        println("Sending Mantle Chat Completions STREAMING request (Bearer, region=$region)...")
        val response = pipe.execute(TEST_PROMPT)
        println("Streaming response: $response")

        assertNotNull(response, "Streaming response must not be null")
        assertTrue(
            response.contains("pong", ignoreCase = true),
            "Expected streaming response to contain 'pong'. Got: $response"
        )
    }

    /**
     * Bearer streaming against the Mantle Responses API endpoint.
     */
    @Test
    fun testMantleResponsesStreaming() = runBlocking<Unit>
    {
        assumeCredentialsConfigured()
        val region = resolveRegion()
        val pipe = GenericOpenAIPipe()
            .setBedrockMantleWithResponses(region, DEFAULT_MODEL)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(0.0)
            .setStreamingEnabled(true)
            .enableTracing(traceConfig())
            .init()

        println("Sending Mantle Responses STREAMING request (Bearer, region=$region)...")
        val response = pipe.execute(TEST_PROMPT)
        println("Streaming response: $response")

        assertNotNull(response, "Streaming response must not be null")
        assertTrue(
            response.contains("pong", ignoreCase = true),
            "Expected streaming response to contain 'pong'. Got: $response"
        )
    }

    /**
     * Chunked-encoding SigV4 streaming against Mantle Chat Completions.
     * Gated on a SEPARATE env var (`BEDROCK_MANTLE_STREAMING_SIGV4_TEST=true`)
     * so CI environments can run the bearer streaming tests without paying
     * for SigV4 streaming live hits.
     *
     * Skips cleanly when the gate is absent OR when credentials are missing.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "BEDROCK_MANTLE_STREAMING_SIGV4_TEST",
        matches = "true"
    )
    fun testMantleChatCompletionsStreamingSigV4() = runBlocking<Unit>
    {
        assumeCredentialsConfigured()
        val region = resolveRegion()
        val pipe = GenericOpenAIPipe()
            .setBedrockMantle(region, DEFAULT_MODEL)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(0.0)
            .setStreamingEnabled(true)
            .enableTracing(traceConfig())
            .init()

        println("Sending Mantle Chat Completions STREAMING request (SigV4 chunked, region=$region)...")
        val response = pipe.execute(TEST_PROMPT)
        println("Streaming SigV4 response: $response")

        assertNotNull(response, "Streaming SigV4 response must not be null")
        assertTrue(
            response.contains("pong", ignoreCase = true),
            "Expected streaming SigV4 response to contain 'pong'. Got: $response"
        )
    }

    /**
     * Chunked-encoding SigV4 streaming against Mantle Responses API.
     * Gated on `BEDROCK_MANTLE_STREAMING_SIGV4_TEST=true` (separate from
     * the bearer-streaming tests).
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "BEDROCK_MANTLE_STREAMING_SIGV4_TEST",
        matches = "true"
    )
    fun testMantleResponsesStreamingSigV4() = runBlocking<Unit>
    {
        assumeCredentialsConfigured()
        val region = resolveRegion()
        val pipe = GenericOpenAIPipe()
            .setBedrockMantleWithResponses(region, DEFAULT_MODEL)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(0.0)
            .setStreamingEnabled(true)
            .enableTracing(traceConfig())
            .init()

        println("Sending Mantle Responses STREAMING request (SigV4 chunked, region=$region)...")
        val response = pipe.execute(TEST_PROMPT)
        println("Streaming SigV4 response: $response")

        assertNotNull(response, "Streaming SigV4 response must not be null")
        assertTrue(
            response.contains("pong", ignoreCase = true),
            "Expected streaming SigV4 response to contain 'pong'. Got: $response"
        )
    }

    /**
     * Skip cleanly when AWS credentials are missing. JUnit 5's `assumeTrue`
     * aborts the test (instead of failing) when the condition is false,
     * preserving the `@EnabledIfEnvironmentVariable` skip semantics.
     */
    private fun assumeCredentialsConfigured()
    {
        val accessKeyId = System.getenv("AWS_ACCESS_KEY_ID") ?: ""
        val secretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY") ?: ""
        assumeTrue(
            accessKeyId.isNotBlank() && secretAccessKey.isNotBlank(),
            "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY env vars must be set to run live tests"
        )
    }

    private fun resolveRegion(): String =
        System.getenv("BEDROCK_MANTLE_REGION")?.takeIf { it.isNotBlank() }
            ?: "us-east-1"
}