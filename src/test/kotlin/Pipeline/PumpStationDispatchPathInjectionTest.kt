package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.testing.TestCapturingPipe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression test for Defect 8 (HIGH 🔴): dispatch LLM sees no path descriptors.
 *
 * Background (audit 2026-07-10): the path-injection block at Pipe.kt:2319-2341 is
 * gated by `getNearestPumpStationParent()` returning a PumpStation. runAgent()
 * at PumpStationLoop.kt:199 calls `agent.execute(input)` directly without
 * `setParentInterface(station)`, so the parent chain never reaches the dispatch
 * pipe and `autoInjectPathDataFromPumpStation` silently no-ops. ALL 13 trace
 * dispatch HTMLs show zero PathDescriptionList / "Available paths" references.
 *
 * Fix: patch runAgent() so it calls `agent.setParentInterface(this)` on the agent
 * pipeline before invoking it, IF the agent does not already have a parent set.
 *
 * Test wiring: this test verifies the precondition signal that is reachable
 * without requiring the kotlinx-serialization compiler plugin (which is wired
 * only through Gradle's kotlin plugin and is unavailable under direct
 * kotlinc execution in this sandbox). The signal:
 * - Pre-fix: `dispatchAgent.getNearestPumpStationParent()` returns null after
 *   executeLocal because the parent chain is never established.
 * - Post-fix: returns the PumpStation because runAgent sets the parent.
 *
 * A separate full-Green verification runs under `./gradlew test` and asserts
 * the path-injection block emits PathDescriptionList into the composed prompt.
 */
class PumpStationDispatchPathInjectionTest
{
    /** Build a station with a capturing dispatch pipe. */
    private fun stationWithCapturingDispatch(
        responseJson: String = """{"pathName": "alpha", "pathSchema": "{}"}"""
    ): Pair<PumpStation, TestCapturingPipe>
    {
        val capturePipe = TestCapturingPipe(response = responseJson)
        val dispatchAgent = Pipeline().apply { add(capturePipe) }
        val judgePipe = ScriptedTestPipe(
            name = "judge",
            response = """{"isComplete": true, "shouldTerminate": false}"""
        )
        val judgeAgent = Pipeline().apply { add(judgePipe) }
        val station = PumpStation()
            .setJudgeAgent(judgeAgent)
            .setDispatchAgent(dispatchAgent)
        station.addPath(PathObject().apply {
            pathName = "alpha"
            pathDescription = "the alpha path"
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "alpha result")
            }
        })
        station.addPath(PathObject().apply {
            pathName = "beta"
            pathDescription = "the beta path"
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "beta result")
            }
        })
        return station to capturePipe
    }

    @Test
    fun dispatch_agent_nearest_pumpstation_parent_is_station_after_executeLocal()
    {
        // RED signal: pre-fix runAgent calls agent.execute() without setting
        // the parent. The dispatch pipeline's nearestPumpStationParent walks
        // up the ownership tree and returns null because the parent is null.
        //
        // POST-FIX: runAgent calls setParentInterface(this) on the agent
        // pipeline, so the parent chain reaches the PumpStation.
        runBlocking {
            val (station, _) = stationWithCapturingDispatch()
            station.executeLocal(MultimodalContent(text = "research X"))

            val nearest = station.getDispatchAgent()?.getNearestPumpStationParent()
            assertNotNull(
                nearest,
                "Defect 8: dispatchAgent.getNearestPumpStationParent() returned null after " +
                    "executeLocal. runAgent (PumpStationLoop.kt:199) must call " +
                    "setParentInterface(this) so the path-injection block at " +
                    "Pipe.kt:2319-2341 can walk the ownership tree to the PumpStation."
            )
            assertTrue(
                nearest is PumpStation,
                "Defect 8: nearest parent must be a PumpStation instance, was ${nearest?.javaClass?.simpleName}"
            )
        }
    }

    @Test
    fun dispatch_agent_nearest_parent_walks_via_pipes_up_to_pumpstation()
    {
        // Path-based check: every pipe inside the dispatch pipeline should walk
        // up its parent chain (pipe → pipeline → pumpstation) and find the station.
        // Pre-fix: returns null at every pipe. Post-fix: returns the station.
        runBlocking {
            val (station, capturePipe) = stationWithCapturingDispatch()
            station.executeLocal(MultimodalContent(text = "research X"))

            val nearestFromPipe = capturePipe.getNearestPumpStationParent()
            assertNotNull(
                nearestFromPipe,
                "Defect 8: dispatch PIPE's nearestPumpStationParent returned null. " +
                    "Even if the Pipeline's parent is set, individual pipes inside " +
                    "the chain need to walk Pipeline → PumpStation."
            )
            assertTrue(
                nearestFromPipe is PumpStation,
                "Defect 8: dispatch PIPE's nearest parent must reach a PumpStation, " +
                    "got ${nearestFromPipe?.javaClass?.simpleName}"
            )
        }
    }
}
