package Defaults

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.PumpStationBuilder
import com.TTT.Pipeline.PumpStationMemoryManagementMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [PumpStationDefaults] — verifies the factory wires judge, dispatch, killSwitch,
 * and memory defaults correctly without requiring a live LLM call.
 *
 * These tests construct the station and inspect its configuration; they do NOT call
 * `executeLocal`. A live end-to-end test against a real OpenRouter model lives in
 * `src/test/kotlin/Pipeline/PumpStationLiveLLMTest.kt` (env-gated).
 */
class PumpStationDefaultsTest
{
    /**
     * Helper: register a no-op "done" path so `PumpStationBuilder.build()` succeeds
     * (it requires at least one path). The path returns `passPipeline = true` after one
     * invocation so any real harness that calls it exits cleanly.
     */
    private fun PumpStationBuilder<*>.addDonePath(name: String = "done")
    {
        path(name) {
            description = "Test path that returns a passPipeline flag immediately."
            setExecutionFunction { _, _, _, _ ->
                MultimodalContent(text = "ok").apply { passPipeline = true }
            }
        }
    }

    @Test
    fun `withOpenRouter returns non-null PumpStation with all required slots filled`()
    {
        val config = OpenRouterConfiguration(
            model = "openai/gpt-4o-mini",
            apiKey = "test-key-not-real",
            pipeCount = 1
        )
        val station = PumpStationDefaults.withOpenRouter(config) { addDonePath() }

        assertNotNull(station)
        assertNotNull(station.getJudgeAgent(), "judgeAgent must be set")
        assertNotNull(station.getDispatchAgent(), "dispatchAgent must be set")
        val killSwitch = station.getConfiguredKillSwitch()
        assertNotNull(killSwitch, "killSwitch must be set")
        assertEquals(50_000, killSwitch.inputTokenLimit)
        assertEquals(10_000, killSwitch.outputTokenLimit)
    }

    @Test
    fun `recommendedMemoryConfig returns Truncation mode at 0_85 threshold`()
    {
        val (mode, threshold) = PumpStationDefaults.recommendedMemoryConfig()
        assertEquals(PumpStationMemoryManagementMode.Truncation, mode)
        assertEquals(0.85, threshold)
    }

    @Test
    fun `builder block can override the judge agent`()
    {
        val config = OpenRouterConfiguration(
            model = "openai/gpt-4o-mini",
            apiKey = "test-key-not-real",
            pipeCount = 1
        )
        var overrideCalled = false
        val station = PumpStationDefaults.withOpenRouter(config) {
            overrideCalled = true
            // Override the judge to null so we can prove the builder block runs after defaults.
            // Must be set BEFORE addDonePath() — path { } promotes the builder to a new
            // Ready-stage copy, so any field set after path { } lands on the discarded
            // initial builder and is never seen by the final build().
            judgeAgent = null
            addDonePath()
        }
        assertTrue(overrideCalled, "builder block must be invoked")
        assertEquals(null, station.getJudgeAgent(), "builder block should override judge to null")
    }

    @Test
    fun `withOpenRouter rejects blank model`()
    {
        val badConfig = OpenRouterConfiguration(
            model = "",
            apiKey = "test-key",
            pipeCount = 1
        )
        assertFailsWith<IllegalArgumentException> {
            PumpStationDefaults.withOpenRouter(badConfig) { addDonePath() }
        }
    }

    @Test
    fun `withOpenRouter rejects blank apiKey`()
    {
        val badConfig = OpenRouterConfiguration(
            model = "openai/gpt-4o-mini",
            apiKey = "",
            pipeCount = 1
        )
        assertFailsWith<IllegalArgumentException> {
            PumpStationDefaults.withOpenRouter(badConfig) { addDonePath() }
        }
    }

    @Test
    fun `withCodex wires judge and dispatch without requiring an API key`()
    {
        val station = PumpStationDefaults.withCodex(
            CodexConfiguration(model = "gpt-5-codex", pipeCount = 1)
        ) { addDonePath() }

        assertNotNull(station.getJudgeAgent())
        assertNotNull(station.getDispatchAgent())
    }

    @Test
    fun `recommendedKillSwitchConfig returns 50K input and 10K output`()
    {
        val ks = PumpStationDefaults.recommendedKillSwitchConfig()
        assertEquals(50_000, ks.inputTokenLimit)
        assertEquals(10_000, ks.outputTokenLimit)
    }
}
