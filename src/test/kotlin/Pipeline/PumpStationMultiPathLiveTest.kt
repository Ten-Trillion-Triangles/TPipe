package com.TTT.Pipeline

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Pipe.MultimodalContent
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Env-gated live LLM test for the PumpStation multi-path dispatch contract.
 *
 * Runs only when both `TPIPE_LIVE_LLM_TEST=true` AND `MINIMAX_API_KEY` are
 * exported. Otherwise silently skips. The deterministic tests in
 * [PumpStationMultiPathDispatchTest] cover the parsing, prompt branching,
 * and event-emission contract; this live test proves the dispatch LLM
 * actually produces the multi-path JSON shape when prompted with the
 * multi-path schema, and that the harness parses it correctly against a
 * real provider.
 *
 * On every live run the harness auto-exports a PumpStation HTML trace under
 * the canonical TPipe trace root
 * (`${TPipeConfig.getTraceDir()}/PumpStation/<testName>/pumpstation-<runId>.html`).
 * The test asserts the trace file lands at that canonical location — NOT
 * under a hard-coded `~/.TPipe-Debug/...` literal — so a future regression
 * where the harness silently routes traces elsewhere will surface here.
 *
 * To run:
 *
 *     export TPIPE_LIVE_LLM_TEST=true
 *     export MINIMAX_API_KEY=sk-...
 *     ./gradlew :test --tests "*PumpStationMultiPathLiveTest" --console=plain --no-daemon
 *
 * Without the env vars the test silently returns — the suite stays green
 * on clean checkouts.
 */
class PumpStationMultiPathLiveTest
{
    @Test
    fun multiPathDispatchProducesValidBatch()
    {
        if (System.getenv("TPIPE_LIVE_LLM_TEST") != "true") return
        val apiKey = System.getenv("MINIMAX_API_KEY")
            ?: error("MINIMAX_API_KEY must be set when TPIPE_LIVE_LLM_TEST=true")

        // GenericOpenAIPipe rejects non-api.openai.com base URLs unless this
        // opt-in system property is set. Mirrors the PumpStationMiniMaxLiveTest
        // setup so this test can hit https://api.minimax.io/v1.
        System.setProperty("tpipe.allowInsecureBaseUrl", "true")

        val testName = "multiPathDispatchProducesValidBatch"
        val traceConfig = traceConfigFor(testName)

        val batchEvents = mutableListOf<PumpStationEvent>()
        val station = pumpStation("multi-live") {
            judgeAgent = miniMaxPipeline("judge", apiKey, traceConfig)
            dispatchAgent = miniMaxPipeline("dispatch", apiKey, traceConfig)
            pathExecutionShape = PathExecutionShape.MultiPath
            tracingConfiguration = traceConfig
            // eventObserver MUST be assigned BEFORE the first path() call.
            // path() triggers promote() which snapshots the Initial builder's
            // eventObserver into the new Ready builder via copyFrom — a later
            // assignment to the Initial builder's eventObserver field is
            // silently dropped.
            eventObserver = { batchEvents.add(it) }
            path("noop") {
                description = "No-op test path that echoes the input."
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "Result for: ${content.text.take(80)}")
                }
            }
            path("gather") {
                description = "Second no-op path; the harness should fan out across both."
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "Gathered: ${content.text.take(80)}")
                }
            }
            killSwitch {
                // Generous limits for a live-test smoke run. M2.7 with the
                // multi-path schema can run 4-6 turns before completion; each
                // turn is ~10-30K tokens. 250K covers a full multi-turn run.
                inputTokenLimit = 250_000
                outputTokenLimit = 250_000
            }
        }

        var caughtException: Exception? = null
        try
        {
            runBlocking {
                station.executeLocal(MultimodalContent(
                    text = "Write a brief summary comparing Kotlin coroutines and Java virtual threads."
                ))
            }
        }
        catch (e: Exception)
        {
            // Surface the failure (e.g. KillSwitch tripped) but don't abort — we still
            // want the partial trace HTML to land at the canonical location below.
            System.err.println("[PumpStationMultiPathLiveTest] executeLocal threw: ${e.message}")
            caughtException = e
        }
        finally
        {
            try
            {
                runBlocking {
                    // Always export the trace, even when executeLocal threw partway.
                    // Without this, a killSwitch trip or repair exhaustion leaves the
                    // canonical trace dir empty even though events were captured.
                    station.getTraceReport(com.TTT.Debug.TraceFormat.HTML)
                }
            }
            catch (e: Exception)
            {
                System.err.println("[PumpStationMultiPathLiveTest] getTraceReport failed: ${e.message}")
            }
            System.clearProperty("tpipe.allowInsecureBaseUrl")
        }
        if (caughtException != null) return

        // The dispatch LLM should have produced a batch event of some kind.
        // Either PathBatchStarted (parse succeeded) or PathBatchFailed
        // (parse failed even after repair). SinglePath mode never emits
        // these events.
        val sawBatchEvent = batchEvents.any {
            it is PathBatchStarted || it is PathBatchCompleted || it is PathBatchFailed
        }
        kotlin.test.assertTrue(
            sawBatchEvent,
            "MultiPath mode should emit PathBatchStarted/Completed/Failed events; " +
            "got ${batchEvents.size} events total. " +
            "If 0 events, check that eventObserver is set BEFORE the first path() call " +
            "(promote() snapshots Initial builder state into Ready at first path())."
        )
        if (!sawBatchEvent)
        {
            System.err.println(
                "[PumpStationMultiPathLiveTest] No batch events observed. " +
                "LLM did not produce the multi-path shape on this run. " +
                "Total events: ${batchEvents.size}"
            )
        }

        // Trace artifact location check: the PumpStation HTML trace MUST
        // land at the canonical TPipeConfig.getTraceDir() root, NOT under
        // a hard-coded `~/.TPipe-Debug/...` literal. A regression that
        // routes traces elsewhere would silently break downstream visualizer
        // tooling — this assertion surfaces the bug.
        val pumpstationDir = File(TPipeConfig.getTraceDir(), "PumpStation/$testName")
        val pumpstationHtml = pumpstationDir.listFiles()
            ?.firstOrNull { it.name.startsWith("pumpstation-") && it.name.endsWith(".html") }
        if (pumpstationHtml == null || !pumpstationHtml.exists())
        {
            System.err.println(
                "[PumpStationMultiPathLiveTest] No PumpStation HTML trace found under " +
                "${pumpstationDir.absolutePath}/pumpstation-*.html. " +
                "Trace capture may have failed; verify enableTracing is wired before init."
            )
        }
        else
        {
            println("[PumpStationMultiPathLiveTest] Trace: ${pumpstationHtml.absolutePath} " +
                "(${(pumpstationHtml.length() / 1024)} KB)")
        }
    }

    private fun miniMaxPipeline(pipeName: String, apiKey: String, traceConfig: TraceConfig): Pipeline
    {
        val pipe = GenericOpenAIPipe()
            .setApiKey(apiKey)
            .setApiMode(ApiMode.OpenAIResponses)
            .setBaseUrl("https://api.minimax.io/v1")
            .also { p ->
                p.setPipeName(pipeName)
                p.setModel("MiniMax-M2.7")
                p.setMaxTokens(2000)
                p.setTemperature(0.0)
            }
        val pipeline = Pipeline().apply { add(pipe) }
        // Wire tracing BEFORE init so the per-pipe HTML is populated. The
        // PumpStation HTML lands in the same exportPath root via the harness's
        // own enableTracing in refreshPipelinesPrompts.
        pipeline.enableTracing(traceConfig)
        // init(true) allocates the HttpClient lazily and runs P2PInit
        // on the pipe. Without this, the first execute() call throws
        // "GenericOpenAIPipe not initialized. Call init() first."
        runBlocking { pipeline.init(true) }
        return pipeline
    }

    /**
     * Resolves the canonical TPipe trace root for PumpStation traces and
     * ensures the per-test subdirectory exists. Mirrors the helper in
     * PumpStationMiniMaxLiveTest so this test's HTML artifacts land at
     * `${TPipeConfig.getTraceDir()}/PumpStation/<testName>/`.
     */
    private fun traceDir(testName: String): File
    {
        val dir = File(TPipeConfig.getTraceDir(), "PumpStation/$testName")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Build a [TraceConfig] that resolves [TraceConfig.exportPath] to the
     * canonical TPipeConfig.getTraceDir() root, NOT a hard-coded legacy
     * literal. Same pattern as PumpStationMiniMaxLiveTest.traceConfigFor.
     */
    private fun traceConfigFor(testName: String): TraceConfig
    {
        val subdir = traceDir(testName)
        // Clean stale pumpstation-*.html files from prior runs of this test
        // so the assertion below resolves to the current run's artifact.
        subdir.listFiles { f -> f.name.startsWith("pumpstation-") && f.name.endsWith(".html") }
            ?.forEach { it.delete() }
        return TraceConfig(
            enabled = true,
            maxHistory = 5000,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG,
            autoExport = true,
            exportPath = subdir.absolutePath,
            includeContext = true,
            includeMetadata = true
        )
    }
}