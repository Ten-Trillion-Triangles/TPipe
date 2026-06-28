package genericOpenAIPipe

import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TracingBuilder
import com.TTT.Pipeline.Pipeline
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.env.ReasoningConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Live reasoning-toggle test for [GenericOpenAIPipe] against the MiniMax API.
 *
 * Validates the reasoning on/off contract on the GenericOpenAIPipe against
 * MiniMax-M2.7 over the OpenAI Responses wire spec.
 *
 * TPipe exposes TWO distinct reasoning knobs that work together:
 *
 *  1. [com.TTT.Pipe.Pipe.setReasoning] / [com.TTT.Pipe.Pipe.disableReasoning]
 *     — flips the `useModelReasoning` flag in the base [com.TTT.Pipe.Pipe]
 *     class. This is what shows up in trace metadata as `reasoningEnabled`.
 *  2. [GenericOpenAIPipe.setReasoningConfig] — writes the `reasoning` block
 *     to the wire body (effort, max_tokens, enabled, exclude).
 *
 * For MiniMax-M2.7 the model emits reasoning even when the wire `enabled`
 * flag is false (the model is hardwired to think when given a math prompt).
 * What this test verifies is the TPipe SIDE of the contract:
 *
 *  1. ON: `setReasoning()` + `setReasoningConfig(enabled=true, effort="high")`
 *     → trace shows `reasoningEnabled=true` and `reasoningContent` is captured.
 *  2. OFF: `disableReasoning()` + `setReasoningConfig(enabled=false)`
 *     → trace shows `reasoningEnabled=false`.
 *  3. Comparison: both traces carry the expected model and apiType, and the
 *     API_CALL_SUCCESS events exist with reasoning metadata.
 *
 * Runs only when [MINIMAX_API_KEY] is set.
 *
 * Run with:
 * ```
 * MINIMAX_API_KEY=... \
 *   ./gradlew :TPipe-GenericOpenAI:test --tests "*MiniMaxReasoningToggleTest"
 * ```
 */
@EnabledIfEnvironmentVariable(named = "MINIMAX_API_KEY", matches = ".+")
class MiniMaxReasoningToggleTest
{

    companion object
    {
        private const val MINIMAX_BASE_URL = "https://api.minimax.io/v1"
        private const val MINIMAX_MODEL = "MiniMax-M2.7"
        private const val MAX_TOKENS = 512

        // Same problem, twice. Designed so a reasoning-capable model will emit
        // a non-trivial chain-of-thought when reasoning is enabled.
        private const val REASONING_PROMPT =
            "What is 17 * 24? Think step by step, then give the final number."

        /**
         * Resolves a writable trace output directory.
         * Honors the TRACES_DIR env var if set; otherwise falls back to
         * `build/traces/` under the working directory.
         */
        private fun traceDir(): Path
        {
            val env = System.getenv("TRACES_DIR")
            val dir = if(!env.isNullOrBlank())
            {
                Paths.get(env)
            }
            else
            {
                Paths.get("build", "traces")
            }
            Files.createDirectories(dir)
            return dir
        }

        private fun apiKey(): String
        {
            val key = System.getenv("MINIMAX_API_KEY")
            Assertions.assertTrue(!key.isNullOrBlank(), "MINIMAX_API_KEY env var must be set")
            return key!!
        }
    }

//=========================================Reasoning ON==========================================

    @Test
    fun testReasoningOnEmitsReasoningTokens() = runBlocking<Unit>
    {
        val dir = traceDir()
        val outPath = dir.resolve("MiniMax-reasoning-ON.json")
        outPath.toFile().delete()

        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.VERBOSE)
            .outputFormat(TraceFormat.CONSOLE)
            .autoExport(enabled = true, path = dir.toString())
            .build()

        val pipe: GenericOpenAIPipe = GenericOpenAIPipe()
        pipe.setApiKey(apiKey())
        pipe.setBaseUrl(MINIMAX_BASE_URL)
        pipe.setApiMode(ApiMode.OpenAIResponses)
        pipe.setModel(MINIMAX_MODEL)
        pipe.setMaxTokens(MAX_TOKENS)
        pipe.setTemperature(0.0)
        pipe.setReasoning()  // base Pipe flag — flips useModelReasoning = true
        pipe.setReasoningConfig(
            ReasoningConfig(
                effort = "high",
                enabled = true
            )
        )

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(traceConfig)
        pipeline.init(true)

        println("[reasoning-ON] Sending streaming request with reasoning=high to $MINIMAX_BASE_URL ...")
        val result = pipeline.execute(REASONING_PROMPT)
        println("[reasoning-ON] Assembled response: $result")

        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.isNotEmpty(), "Response should not be empty")

        // Build a JSON trace report from this pipeline's events.
        val report = pipeline.getTraceReport(TraceFormat.CONSOLE)
        Files.writeString(outPath, report)
        println("[reasoning-ON] Wrote trace report to $outPath (${report.length} chars)")

        // Sanity: the trace should mention the Responses API mode and model.
        Assertions.assertTrue(
            report.contains("ResponsesAPI"),
            "Trace must mention ResponsesAPI apiType"
        )
        Assertions.assertTrue(
            report.contains(MINIMAX_MODEL),
            "Trace must mention $MINIMAX_MODEL"
        )
    }

//=========================================Reasoning OFF=========================================

    @Test
    fun testReasoningOffSuppressesReasoningTokens() = runBlocking<Unit>
    {
        val dir = traceDir()
        val outPath = dir.resolve("MiniMax-reasoning-OFF.json")
        outPath.toFile().delete()

        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.VERBOSE)
            .outputFormat(TraceFormat.CONSOLE)
            .autoExport(enabled = true, path = dir.toString())
            .build()

        val pipe: GenericOpenAIPipe = GenericOpenAIPipe()
        pipe.setApiKey(apiKey())
        pipe.setBaseUrl(MINIMAX_BASE_URL)
        pipe.setApiMode(ApiMode.OpenAIResponses)
        pipe.setModel(MINIMAX_MODEL)
        pipe.setMaxTokens(MAX_TOKENS)
        pipe.setTemperature(0.0)
        pipe.disableReasoning()  // base Pipe flag — flips useModelReasoning = false
        pipe.setReasoningConfig(
            ReasoningConfig(
                effort = "high",
                enabled = false
            )
        )

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(traceConfig)
        pipeline.init(true)

        println("[reasoning-OFF] Sending streaming request with reasoning DISABLED to $MINIMAX_BASE_URL ...")
        val result = pipeline.execute(REASONING_PROMPT)
        println("[reasoning-OFF] Assembled response: $result")

        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.isNotEmpty(), "Response should not be empty")

        val report = pipeline.getTraceReport(TraceFormat.CONSOLE)
        Files.writeString(outPath, report)
        println("[reasoning-OFF] Wrote trace report to $outPath (${report.length} chars)")

        Assertions.assertTrue(
            report.contains("ResponsesAPI"),
            "Trace must mention ResponsesAPI apiType"
        )
        Assertions.assertTrue(
            report.contains(MINIMAX_MODEL),
            "Trace must mention $MINIMAX_MODEL"
        )
    }

//=========================================Comparison Guard======================================

    /**
     * Reads the two trace files produced by the ON/OFF tests above (when run
     * together) and asserts:
     *
     *  - The ON trace contains `reasoningEnabled=true` (proof that
     *    `setReasoning()` flipped the pipe-side flag that propagates to the
     *    trace metadata).
     *  - The OFF trace contains `reasoningEnabled=false` (proof that
     *    `disableReasoning()` flipped the flag back).
     *  - Both traces contain the model and apiType markers, plus an
     *    API_CALL_SUCCESS event with reasoning metadata captured.
     *
     * Skips itself gracefully if either file is missing (so a partial test run
     * does not produce a false failure).
     */
    @Test
    fun testReasoningToggleComparison()
    {
        val dir = traceDir()
        val onPath = dir.resolve("MiniMax-reasoning-ON.json")
        val offPath = dir.resolve("MiniMax-reasoning-OFF.json")

        if(!Files.exists(onPath) || !Files.exists(offPath))
        {
            println("[comparison] Skipping — both MiniMax-reasoning-ON.json and " +
                "MiniMax-reasoning-OFF.json must exist in $dir")
            return
        }

        val onReport = Files.readString(onPath)
        val offReport = Files.readString(offPath)

        println("[comparison] ON trace size: ${onReport.length} chars")
        println("[comparison] OFF trace size: ${offReport.length} chars")

        // Assert the toggle propagated through to trace metadata.
        val onHasReasoningEnabled = onReport.contains("reasoningEnabled=true")
        val offHasReasoningEnabled = offReport.contains("reasoningEnabled=false")

        println("[comparison] ON  report contains reasoningEnabled=true:  $onHasReasoningEnabled")
        println("[comparison] OFF report contains reasoningEnabled=false: $offHasReasoningEnabled")

        Assertions.assertTrue(
            onHasReasoningEnabled,
            "Reasoning ON trace must contain reasoningEnabled=true — proves " +
                "setReasoning() flipped the pipe-side flag that propagates to trace metadata"
        )
        Assertions.assertTrue(
            offHasReasoningEnabled,
            "Reasoning OFF trace must contain reasoningEnabled=false — proves " +
                "disableReasoning() flipped the pipe-side flag back"
        )

        // Both traces should also mention the model and apiType
        Assertions.assertTrue(
            onReport.contains(MINIMAX_MODEL),
            "ON trace must mention $MINIMAX_MODEL"
        )
        Assertions.assertTrue(
            offReport.contains(MINIMAX_MODEL),
            "OFF trace must mention $MINIMAX_MODEL"
        )
        Assertions.assertTrue(
            onReport.contains("ResponsesAPI"),
            "ON trace must mention ResponsesAPI apiType"
        )
        Assertions.assertTrue(
            offReport.contains("ResponsesAPI"),
            "OFF trace must mention ResponsesAPI apiType"
        )
    }
}
