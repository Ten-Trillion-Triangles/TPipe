package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Regression tests for the [WarningCode.NoExitSignalConfigured] advisory predicate at
 * [PumpStationLoop.runPreInitPhase] (PumpStationLoop.kt:2395).
 *
 * The v3 predicate was incomplete: it respected only two of the four legitimate exit
 * mechanisms documented in the warning message — judge agent and FlagTriggered
 * `requestJudgeNextTurn`. It did NOT recognize `MultimodalContent.passPipeline = true` or
 * `MultimodalContent.terminatePipeline = true` returned from a path's `executionFunction`
 * as a legitimate exit signal, even though the warning text (PumpStationLoop.kt:2402-2406)
 * explicitly lists them as such.
 *
 * These tests pin the corrected contract: a configuration that has any path whose
 * execution function returns `passPipeline = true` (or `terminatePipeline = true`)
 * should NOT emit the advisory. A configuration with no judge, no flag, no pass-pipeline
 * path, and no terminate-pipeline path SHOULD emit the advisory.
 */
class PumpStationNoExitSignalWarningTest
{
    /**
     * Helper: build a dispatch Pipeline that always picks [pathName]. Mirrors the
     * `dispatchAlwaysPicks` helper in PumpStationFlagTriggeredJudgeTest. The dispatch
     * agent is required by PumpStation.init() — without it the harness throws
     * "dispatchAgent must be a Pipeline".
     */
    private fun dispatchAlwaysPicks(pathName: String): Pipeline
    {
        val pipe = ScriptedTestPipe(response = """{"pathName": "$pathName", "pathSchema": "{}"}""")
        return Pipeline().apply { add(pipe) }
    }

    /**
     * Helper: build a PumpStation with no judge, no flag, multi-turn budget, and an
     * event observer that captures all events. Returns the station plus the recorded
     * events list so the test can assert on the captured stream.
     */
    private fun stationWithObserver(pathName: String, maxHarnessTurns: Int = 5): Pair<PumpStation, MutableList<PumpStationEvent>>
    {
        val events = mutableListOf<PumpStationEvent>()
        val observer: (PumpStationEvent) -> Unit = { ev -> synchronized(events) { events.add(ev) } }
        val station = buildTestStation(maxHarnessTurns = maxHarnessTurns)
            .setDispatchAgent(dispatchAlwaysPicks(pathName))
            .setEventObserver(observer)
        return station to events
    }

    /**
     * Build a path whose `executionFunction` returns a [MultimodalContent] with
     * `passPipeline = true` (the developer-readable exit signal).
     */
    private fun passPipelinePath(name: String): PathObject
    {
        return PathObject().apply {
            pathName = name
            pathDescription = "Test path that signals pass-pipeline exit"
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "done").apply { passPipeline = true }
            }
        }
    }

    /**
     * Build a path whose `executionFunction` returns a [MultimodalContent] with
     * `terminatePipeline = true` (the failure exit signal).
     */
    private fun terminatePipelinePath(name: String): PathObject
    {
        return PathObject().apply {
            pathName = name
            pathDescription = "Test path that signals terminate-pipeline exit"
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "failed").apply { terminatePipeline = true }
            }
        }
    }

    /**
     * Build a path that has NO [PathObject.setExecutionFunction] — only an internal agent
     * wired in. Such a path returns whatever the internal agent produces and CANNOT signal
     * passPipeline or terminatePipeline from a developer-controlled function. This is the
     * configuration the warning is meant to flag: a no-judge, no-flag, no-exit-function
     * harness that will run until maxTurns and fail with MaxTurnsExceeded.
     */
    private fun plainPath(name: String): PathObject
    {
        return PathObject().apply {
            pathName = name
            pathDescription = "Test path with no exit signal"
            // Wire an internal agent but no executionFunction. The path will execute
            // the agent and return its output verbatim; no exit signal can be set.
            setInternalAgent(MockP2PAgent(script = listOf(MultimodalContent(text = "produced output, no exit"))))
        }
    }

    /**
     * Case 1 (red before fix, green after fix): no judge, no flag, multi-turn budget,
     * one path that returns `passPipeline = true`. The advisory should NOT be emitted
     * because the path's exit signal IS a legitimate exit mechanism.
     */
    @Test
    fun noExitSignalWarning_passPipelineOnly_doesNotEmit()
    {
        runBlocking {
            val (station, events) = stationWithObserver(pathName = "done", maxHarnessTurns = 5)
            station.addPath(passPipelinePath("done"))

            station.executeLocal(MultimodalContent(text = "task"))

            val warnings = synchronized(events) {
                events.filterIsInstance<HarnessWarning>()
                    .filter { it.code == WarningCode.NoExitSignalConfigured }
            }
            assertTrue(
                warnings.isEmpty(),
                "Expected no NoExitSignalConfigured warning when path returns passPipeline=true; " +
                    "got ${warnings.size} warning(s): ${warnings.map { it.message }}"
            )
        }
    }

    /**
     * Case 2 (red before fix, green after fix): no judge, no flag, multi-turn budget,
     * one path that returns `terminatePipeline = true`. The advisory should NOT be
     * emitted because terminatePipeline IS a legitimate exit mechanism.
     */
    @Test
    fun noExitSignalWarning_terminatePipelineOnly_doesNotEmit()
    {
        runBlocking {
            val (station, events) = stationWithObserver(pathName = "fail", maxHarnessTurns = 5)
            station.addPath(terminatePipelinePath("fail"))

            station.executeLocal(MultimodalContent(text = "task"))

            val warnings = synchronized(events) {
                events.filterIsInstance<HarnessWarning>()
                    .filter { it.code == WarningCode.NoExitSignalConfigured }
            }
            assertTrue(
                warnings.isEmpty(),
                "Expected no NoExitSignalConfigured warning when path returns terminatePipeline=true; " +
                    "got ${warnings.size} warning(s): ${warnings.map { it.message }}"
            )
        }
    }

    /**
     * Case 3 (positive control — pinned for regression): no judge, no flag,
     * multi-turn budget, path that returns plain content without any exit signal.
     * The advisory SHOULD be emitted because no legitimate exit mechanism exists.
     */
    @Test
    fun noExitSignalWarning_noExitMechanism_emitsWarning()
    {
        runBlocking {
            val (station, events) = stationWithObserver(pathName = "plain", maxHarnessTurns = 5)
            station.addPath(plainPath("plain"))

            station.executeLocal(MultimodalContent(text = "task"))

            val warnings = synchronized(events) {
                events.filterIsInstance<HarnessWarning>()
                    .filter { it.code == WarningCode.NoExitSignalConfigured }
            }
            assertTrue(
                warnings.isNotEmpty(),
                "Expected at least one NoExitSignalConfigured warning when no judge, no flag, " +
                    "no pass-pipeline, no terminate-pipeline; got 0"
            )
            // The warning should enumerate all four legitimate mechanisms so the developer
            // can pick one to resolve the advisory. Multiple emissions of the same warning
            // are expected (the harness emits each event to the observer at emit time AND
            // at finalization drain), so assert on the message contents of the first match.
            val first = warnings.first()
            val mechanisms = first.mechanisms
            assertTrue(ExitMechanism.JudgeAlways in mechanisms, "Warning should list JudgeAlways mechanism")
            assertTrue(ExitMechanism.JudgeFlagTriggered in mechanisms, "Warning should list JudgeFlagTriggered mechanism")
            assertTrue(ExitMechanism.PathPassPipeline in mechanisms, "Warning should list PathPassPipeline mechanism")
            assertTrue(ExitMechanism.PathTerminatePipeline in mechanisms, "Warning should list PathTerminatePipeline mechanism")
        }
    }
}