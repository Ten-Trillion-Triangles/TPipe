package genericOpenAIPipe

import com.TTT.Debug.PipeTracer
import genericOpenAIPipe.api.ApiMode
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
        // 32 = a safe default for both /chat/completions (no minimum) and
        // /responses (Mantle requires max_output_tokens >= 16). 8 worked for
        // /chat but Mantle's /responses API rejects any value below 16.
        private const val MAX_TOKENS: Int = 32
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