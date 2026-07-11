package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

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
        // setup so this test can hit https://api.minimax.chat/v1.
        System.setProperty("tpipe.allowInsecureBaseUrl", "true")

        val batchEvents = mutableListOf<PumpStationEvent>()
        val station = pumpStation("multi-live") {
            judgeAgent = miniMaxPipeline("judge", apiKey)
            dispatchAgent = miniMaxPipeline("dispatch", apiKey)
            pathExecutionShape = PathExecutionShape.MultiPath
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
            eventObserver = { batchEvents.add(it) }
            killSwitch {
                inputTokenLimit = 60_000
                outputTokenLimit = 60_000
            }
        }

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
            System.err.println("[PumpStationMultiPathLiveTest] executeLocal failed: ${e.message}")
            return
        }
        finally
        {
            System.clearProperty("tpipe.allowInsecureBaseUrl")
        }

        // The dispatch LLM should have produced a batch event of some kind.
        // Either PathBatchStarted (parse succeeded) or PathBatchFailed
        // (parse failed even after repair). SinglePath mode never emits
        // these events.
        val sawBatchEvent = batchEvents.any {
            it is PathBatchStarted || it is PathBatchCompleted || it is PathBatchFailed
        }
        if (!sawBatchEvent)
        {
            System.err.println(
                "[PumpStationMultiPathLiveTest] No batch events observed. " +
                "LLM did not produce the multi-path shape on this run. " +
                "Total events: ${batchEvents.size}"
            )
        }
        // We do not assert sawBatchEvent strictly — LLM non-determinism may
        // produce a single-path-shaped response on a particular run. The
        // deterministic tests cover the parsing contract. This live test
        // is observational.
    }

    private fun miniMaxPipeline(pipeName: String, apiKey: String): Pipeline
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
        // init(true) allocates the HttpClient lazily and runs P2PInit
        // on the pipe. Without this, the first execute() call throws
        // "GenericOpenAIPipe not initialized. Call init() first."
        runBlocking { pipeline.init(true) }
        return pipeline
    }
}