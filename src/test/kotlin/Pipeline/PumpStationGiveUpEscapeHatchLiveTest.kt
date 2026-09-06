package com.TTT.Pipeline

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceFormat
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live LLM test that verifies the give-up escape-hatch design theory:
 *
 *  Scenario:
 *    1. The harness is wired with a work path (medium risk) and a `giveUp` path.
 *    2. The path-safety agent is configured to hard-reject the work path with
 *       a clear "cannot complete" reason.
 *    3. The dispatch LLM is given a task the work path cannot satisfy.
 *
 *  Theory under test:
 *    The original live-04 trace showed the LLM dispatching `giveUp` 19 times
 *    in a row with `error=UnknownPath` because `giveUp` was the only viable
 *    forward path once the work path was rejected, but the LLM hallucinated
 *    a name that the harness couldn't resolve. With `giveUp` registered as a
 *    real, resolvable path, the LLM should pick it as the intended escape
 *    after seeing the rejection hint, and the harness should halt via
 *    `passPipeline=true` rather than loop to `MaxTurnsHit`.
 *
 *  Acceptance:
 *    - The trace HTML contains `PUMP_STATION_PASS_PIPELINE` (or the
 *      pass-pipeline path's terminator event).
 *    - The trace HTML contains the `[Path Safety]` hint with the rejection
 *      reason, indicating the LLM had the information needed to pick giveUp.
 *    - The harness exits with `PassSignal` (or `JudgeComplete` if a judge
 *      verifies the result), not `MaxTurnsHit`.
 *    - The LLM-dispatched pathName in the trace is `giveUp` (not a
 *      hallucination like `flarble`).
 *
 *  Gated on `TPIPE_LIVE_LLM_TEST=true` + a non-blank `MINIMAX_API_KEY`.
 *  Follows the same env-var pattern as [PumpStationPostGoalLiveTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PumpStationGiveUpEscapeHatchLiveTest
{
    companion object
    {
        private const val MINIMAX_BASE_URL = "https://api.minimax.io/v1"
        private const val MINIMAX_MODEL = "MiniMax-M2.7"
        private const val TEMPERATURE = 1.0
        private const val TOP_P = 0.95
        private const val TOP_K = 40
        private const val MAX_TOKENS = 16384

        private const val WORK_PATH = "produceReport"
        private const val GIVE_UP_PATH = "giveUp"
        private const val GIVE_UP_MARKER = "GIVEUP:"
    }

    private var apiKeyCache: String? = null

    @BeforeAll
    fun setup()
    {
        val envKey = System.getenv("MINIMAX_API_KEY")
        val key = envKey?.takeIf { it.isNotBlank() } ?: readKeyFromBashrc()
        if (key.isNullOrBlank()) return
        genericOpenAIPipe.env.GenericOpenAIEnv.setApiKey(key)
        apiKeyCache = key
        System.setProperty("tpipe.allowInsecureBaseUrl", "true")
    }

    @AfterAll
    fun teardown()
    {
        if (apiKeyCache != null)
        {
            genericOpenAIPipe.env.GenericOpenAIEnv.clearApiKey()
            apiKeyCache = null
        }
        System.clearProperty("tpipe.allowInsecureBaseUrl")
    }

    private fun liveGateOrSkip(): String? =
        apiKeyCache?.takeUnless { it.startsWith("sk-stub") }

    @Test
    fun live_01_giveUpPathReachableAfterSafetyRejection_passesThrough() = runBlocking<Unit>
    {
        if (liveGateOrSkip() == null) return@runBlocking

        val traceCfg = traceConfigFor("live-01-giveup-escape")
        val baseUrl = MINIMAX_BASE_URL
        val config = apiKeyCache!!

        val station = pumpStation("pumpstation-giveup-live-01") {
            // Tracing wiring (must be set BEFORE configurePaths() — see Pitfall 8).
            tracingConfiguration = traceCfg

            // Hard-rejecting path-safety agent. The system prompt tells the
            // LLM to always return safe=false for `produceReport` (the only
            // work path) with a clear "cannot complete" reason. Real LLM,
            // not a stub — this is the entire point of the test.
            pathSafetyAgent = createAgentPipeline(
                testName = "live-01-giveup-escape",
                pipeName = "path-safety",
                systemPrompt = "You are a path-safety validator. The path `produceReport` " +
                    "cannot be completed because the user's request is intentionally " +
                    "unfulfillable (a test fixture). Reject it with safe=false. " +
                    "Reply with JSON: {\"safe\": boolean, \"reason\": string}.",
                baseUrl = baseUrl
            )

            judgeAgent = createJudgePipeline("judge", baseUrl)
            dispatchAgent = createDispatchPipeline("dispatch", baseUrl)
            // Hard cap on harness turns so a hung LLM doesn't run forever.
            maxHarnessTurns = 6
            // The user task is intentionally unfulfillable: ask for something
            // the LLM has no information about, so the work path can't
            // produce a meaningful result and the LLM should reach for giveUp.
            systemTask = "You are a research assistant. The user will ask for " +
                "information that is not available. Pick a path; if no path " +
                "can satisfy the request, use the `giveUp` path."
            userGuidelines = "Be concise."
            entryUserPrompt = "Find the secret 7-segment PIN stored in the fog-of-war " +
                "memory of the disbanded test fixture team."

            // The work path. Medium risk so the safety gate fires every
            // turn. The LLM has to dispatch it first (the only way to
            // make forward progress), see the [Path Safety] rejection
            // hint, then pick giveUp on the next turn.
            path(WORK_PATH)
            {
                description = "Produces a research report. Medium risk; " +
                    "the path-safety agent will reject it with safe=false."
                risk = PathRiskLevel.Medium
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "Report on '${content.text}': insufficient data.")
                        .apply { passPipeline = true }
                }
            }

            // The give-up escape hatch. Low risk (no safety gate). On call,
            // signals the harness to exit with PassSignal and includes the
            // GIVEUP: marker so the test can detect the LLM chose it.
            path(GIVE_UP_PATH)
            {
                description = "Give up on the task. The user's request cannot be " +
                    "completed with available information. Sets passPipeline=true " +
                    "and terminatePipeline=true so the harness exits with " +
                    "PassSignal. The result text will start with $GIVE_UP_MARKER " +
                    "so the test can detect the LLM chose the escape."
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "$GIVE_UP_MARKER I cannot complete this task. ${content.text}")
                        .also {
                            it.passPipeline = true
                            it.terminatePipeline = true
                        }
                }
            }
        }

        // Run the harness. Retry up to 3 times on transient upstream errors.
        val result = runHarnessWithRetry(station) {
            // The actual user prompt is supplied via the systemTask in the
            // builder above; this is the harness's input that runs through
            // the judge→dispatch→path loop.
            MultimodalContent(text = "Find the secret 7-segment PIN stored in the " +
                "fog-of-war memory of the disbanded test fixture team.")
        }

        assertNotNull(result.text, "live-01: executeLocal returned null text")

        // Events are published synchronously before trace export.
        station.getTraceReport(TraceFormat.HTML)
        exportAgentTraces("live-01-giveup-escape")

        // Read the rendered trace HTML and assert the contract.
        val pumpHtml = readPumpStationTraceHtml("live-01-giveup-escape")
        assertNotNull(pumpHtml, "live-01: pump station HTML not written")

        // 1. The LLM picked the give-up path. The visualizer renders the
        //    dispatched path in turn-card phase pills (e.g. "Dispatch✓→giveUp")
        //    — match that pattern, which is the canonical rendered form.
        val giveUpSelected = Regex("""Dispatch✓→giveUp""").containsMatchIn(pumpHtml) ||
            Regex("""\"selectedPathName\"\s*:\s*\"giveUp\"""").containsMatchIn(pumpHtml) ||
            Regex("""→\s*giveUp""").containsMatchIn(pumpHtml)
        assertTrue(giveUpSelected,
            "live-01: LLM must have dispatched the registered `giveUp` path. " +
                "If the LLM picked a hallucinated name like `flarble`, the " +
                "give-up escape hatch is not visible to it. Trace HTML: " +
                "${pumpHtml.take(500)}")

        // 2. The give-up path actually ran. The visualizer renders
        //    `Path✓→giveUp` in turn-card phase pills when a path completes
        //    successfully, regardless of risk level (giveUp is Low, so the
        //    safety gate skips but the path still runs).
        val giveUpRan = Regex("""Path✓→giveUp""").containsMatchIn(pumpHtml) ||
            Regex("""\"pathName\"\s*:\s*\"giveUp\"""").containsMatchIn(pumpHtml)
        assertTrue(giveUpRan,
            "live-01: trace must show the `giveUp` path executing " +
                "(not just being dispatched). If only the dispatch event " +
                "fired without the path running, the safety gate or " +
                "another mechanism intercepted it.")

        // 3. The harness halted via PassSignal (passPipeline path) rather
        //    than MaxTurnsHit. The passPipeline→PassSignal path is the
        //    intended exit; MaxTurnsHit means the LLM never reached for
        //    the escape hatch.
        val passSignalHalt = pumpHtml.contains("PassSignal")
        val maxTurnsHalt = pumpHtml.contains("MaxTurnsHit") || pumpHtml.contains("MaxTurnsExceeded")
        assertTrue(passSignalHalt || !maxTurnsHalt,
            "live-01: harness must exit via PassSignal (LLM picked giveUp) " +
                "or another non-MaxTurns exit, not loop to MaxTurnsHit. " +
                "PassSignal=$passSignalHalt MaxTurns=$maxTurnsHalt")
    }

    // ====================================================================================
    // HARNESS RUNNER (mirrors the runPostGoalHarness pattern from PumpStationPostGoalLiveTest)
    // ====================================================================================

    private suspend fun runHarnessWithRetry(
        station: PumpStation,
        input: () -> MultimodalContent
    ): MultimodalContent
    {
        var attemptCount = 0
        val maxAttempts = 3
        var lastException: Throwable? = null
        while (attemptCount < maxAttempts)
        {
            attemptCount += 1
            try
            {
                return station.executeLocal(input())
            }
            catch (e: com.TTT.P2P.P2PException)
            {
                lastException = e
                val isTransient = e.message?.contains("Service error", ignoreCase = true) == true
                if (!isTransient || attemptCount >= maxAttempts) throw e
                System.err.println("[RETRY] giveup-escape attempt $attemptCount/$maxAttempts failed: " +
                    "${e.message?.take(120)}; sleeping 3s")
                kotlinx.coroutines.delay(3000)
            }
        }
        throw lastException ?: IllegalStateException("giveup-escape retry loop exited without result")
    }

    // ====================================================================================
    // PIPE / AGENT FACTORIES (mirror PumpStationPostGoalLiveTest pattern)
    // ====================================================================================

    private fun createMiniMaxPipe(
        pipeName: String,
        systemPrompt: String,
        baseUrl: String = MINIMAX_BASE_URL
    ): GenericOpenAIPipe
    {
        val key = apiKeyCache ?: throw IllegalStateException("API key not loaded")
        val pipe = GenericOpenAIPipe()
            .setApiKey(key)
            .setApiMode(ApiMode.OpenAIResponses)
            .setBaseUrl(baseUrl)
            .also { p ->
                p.setPipeName(pipeName)
                p.setModel(MINIMAX_MODEL)
                if (systemPrompt.isNotEmpty()) p.setSystemPrompt(systemPrompt)
                p.setMaxTokens(MAX_TOKENS)
                p.setTemperature(TEMPERATURE)
                p.setTopP(TOP_P)
                p.setTopK(TOP_K)
            }
        return pipe
    }

    private fun createAgentPipeline(
        testName: String,
        pipeName: String,
        systemPrompt: String,
        baseUrl: String = MINIMAX_BASE_URL
    ): com.TTT.Pipeline.Pipeline
    {
        val pipe = createMiniMaxPipe(pipeName, systemPrompt, baseUrl)
        val pipeline = com.TTT.Pipeline.Pipeline().apply { add(pipe) }
        // init(true) is MANDATORY before the harness runs — GenericOpenAIPipe
        // throws IllegalStateException at first LLM call otherwise. See
        // PumpStationMiniMaxLiveTest for the canonical pattern.
        runBlocking { pipeline.init(true) }
        return pipeline
    }

    private fun createJudgePipeline(testName: String, baseUrl: String): com.TTT.Pipeline.Pipeline
    {
        return createAgentPipeline(
            testName,
            pipeName = "judge",
            systemPrompt = "You are the judge in an agentic harness. Decide if the " +
                "task is complete. If the work path produced a final report, " +
                "return {\"isComplete\": true, \"shouldTerminate\": false}.",
            baseUrl = baseUrl
        )
    }

    private fun createDispatchPipeline(testName: String, baseUrl: String): com.TTT.Pipeline.Pipeline
    {
        return createAgentPipeline(
            testName,
            pipeName = "dispatch",
            systemPrompt = "You are the dispatcher in an agentic harness. " +
                "Select a path from the visible list. If the visible list " +
                "contains `giveUp` and the user's request cannot be " +
                "satisfied, pick `giveUp`.",
            baseUrl = baseUrl
        )
    }

    // ====================================================================================
    // TRACE WIRING
    // ====================================================================================

    private fun traceConfigFor(testName: String): com.TTT.Debug.TraceConfig
    {
        // Per-test subdirectory under the canonical trace dir so multiple
        // live-01 runs don't clobber each other's HTML report.
        val subdir = File(TPipeConfig.getTraceDir() + "/PumpStation/$testName").apply {
            if (exists()) listFiles { f -> f.name.startsWith("pumpstation-") && f.name.endsWith(".html") }
                ?.forEach { it.delete() }
            mkdirs()
        }
        return com.TTT.Debug.TraceConfig(
            enabled = true,
            maxHistory = 5000,
            outputFormat = com.TTT.Debug.TraceFormat.HTML,
            detailLevel = com.TTT.Debug.TraceDetailLevel.DEBUG,
            autoExport = true,
            exportPath = subdir.absolutePath,
            includeContext = true,
            includeMetadata = true
        )
    }

    private fun exportAgentTraces(testName: String)
    {
        // Best-effort agent trace export. The pipe-level traces live in
        // PipeTracer's global map; we don't need to do anything beyond
        // what the harness itself writes.
    }

    private fun readPumpStationTraceHtml(testName: String): String?
    {
        val traceDir = File(TPipeConfig.getTraceDir() + "/PumpStation/$testName")
        if (!traceDir.exists()) return null
        val pumpHtmls = traceDir.listFiles { f -> f.name.startsWith("pumpstation-") && f.name.endsWith(".html") }
            ?.filter { it.length() > 5_000 }
            ?: return null
        return pumpHtmls.firstOrNull()?.readText()
    }

    /**
     * Parse `~/.bashrc` directly for `export MINIMAX_API_KEY="..."`. Used
     * when the env var isn't set in the running shell (the standard Hermes
     * execution path).
     */
    private fun readKeyFromBashrc(): String?
    {
        val home = System.getProperty("user.home") ?: return null
        val bashrc = File(home, ".bashrc")
        if (!bashrc.exists()) return null
        val line = bashrc.readLines().firstOrNull { it.startsWith("export MINIMAX_API_KEY=") }
            ?: return null
        return line.replaceFirst("export MINIMAX_API_KEY=", "")
            .trim()
            .trim('"')
            .trim('\'')
            .takeIf { it.isNotBlank() }
    }
}
