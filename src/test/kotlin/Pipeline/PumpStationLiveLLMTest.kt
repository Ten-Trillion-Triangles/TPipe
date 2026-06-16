package com.TTT.Pipeline

import Defaults.OpenRouterConfiguration
import Defaults.PumpStationDefaults
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Live end-to-end integration test for `PumpStation` against a real OpenRouter model.
 *
 * This test is **gated on the `TPIPE_LIVE_LLM_TEST` env var** — when unset, it silently
 * skips (returns immediately, does not fail, does not pass). When the env var is `"true"`
 * AND `OPENROUTER_API_KEY` is set, the test runs a real harness loop against the live
 * OpenRouter model and asserts the harness completes cleanly.
 *
 * To run:
 * ```
 * export TPIPE_LIVE_LLM_TEST=true
 * export OPENROUTER_API_KEY=sk-or-...
 * ./gradlew :test --tests "*.PumpStationLiveLLMTest" --rerun-tasks
 * ```
 *
 * CI does not run this test by default (AGENTS.md: "CI is Gemini CLI agent-focused, NOT
 * build/test CI — no ./gradlew build in workflows").
 */
class PumpStationLiveLLMTest
{
    @Test
    fun openRouterGpt4oMiniEndToEnd() = runBlocking {
        if (System.getenv("TPIPE_LIVE_LLM_TEST") != "true") return@runBlocking
        val apiKey = System.getenv("OPENROUTER_API_KEY")
        if (apiKey.isNullOrBlank()) return@runBlocking

        val config = OpenRouterConfiguration(
            model = "openai/gpt-4o-mini",
            apiKey = apiKey,
            pipeCount = 1
        )
        val station = PumpStationDefaults.withOpenRouter(config) {
            path("answer") {
                description = "Produces a one-sentence answer and signals pass-pipeline."
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "ok: ${content.text}").apply { passPipeline = true }
                }
            }
        }
        val result = station.executeLocal(
            MultimodalContent(text = "List 3 fruits. After the list, write 'DONE.'")
        )

        assertEquals(PumpStationExitReason.JudgeComplete, station.getTaskState().exitReason)
        assertNotNull(result.text)
    }
}
