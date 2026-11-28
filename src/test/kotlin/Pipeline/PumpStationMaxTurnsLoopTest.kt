package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression tests for the [PumpStation.setMaxTurns] / [PumpStation.setMaxHarnessTurns]
 * wire-up into the harness loop. After the DSL-completion work, [maxTurns] is the
 * canonical loop-guard field; [setMaxHarnessTurns] is a delegating alias that writes
 * the same backing field; both [getMaxTurns] and [getMaxHarnessTurns] read it.
 *
 * These tests prove:
 *  1. [setMaxTurns] is the canonical setter: the harness loop honors it and terminates
 *     with [PumpStationError.MaxTurnsExceeded] when the cap is hit, even though the
 *     [maxHarnessTurns] field no longer exists.
 *  2. [setMaxHarnessTurns] is a working alias: calls to it also bound the loop.
 *  3. The DSL's `maxTurns = N` builder var propagates into the runtime via the build
 *     step, and the resulting station obeys the cap.
 *  4. Both getters ([getMaxTurns] and [getMaxHarnessTurns]) report the same value when
 *     only one setter has been called, proving they share the backing field.
 */
class PumpStationMaxTurnsLoopTest
{
    /**
     * Build a minimal [PumpStation] that drives a non-terminating harness: the judge
     * never declares the task complete, the dispatch always picks the same path, and
     * the path does not set passPipeline. With no path-driven exit, the only stop
     * signal is the loop-guard cap.
     */
    private fun nonTerminatingStation(): PumpStation
    {
        val judgePipe = ScriptedTestPipe(
            response = """{"isComplete": false, "shouldTerminate": false}"""
        )
        val dispatchPipe = ScriptedTestPipe(
            response = """{"pathName": "p1", "pathSchema": "{}"}"""
        )
        return PumpStation()
            .setJudgeAgent(Pipeline().apply { add(judgePipe) })
            .setDispatchAgent(Pipeline().apply { add(dispatchPipe) })
            .apply { addPath(testPath("p1")) }
    }

    @Test
    fun testSetMaxTurnsEndsLoop()
    {
        runBlocking {
            val station = nonTerminatingStation()
            station.setMaxTurns(2)

            station.executeLocal(MultimodalContent(text = "task"))

            val state = station.getTaskState()
            assertEquals(
                PumpStationError.MaxTurnsExceeded,
                state.lastError,
                "Loop must terminate with MaxTurnsExceeded when setMaxTurns(2) is hit"
            )
            assertEquals(
                PumpStationExitReason.MaxTurnsHit,
                state.exitReason,
                "Loop must report MaxTurnsHit exit reason"
            )
            // turnIndex is incremented after the last completed turn. The loop
            // boundary is `turnIndex < maxTurnsInternal` so the last turn executed
            // is turnIndex == maxTurns - 1, then turnIndex is bumped to == maxTurns.
            assertEquals(
                2,
                state.turnIndex,
                "Loop must run exactly 2 turns and stop (turnIndex should be 2, not 50)"
            )
            // The canonical getter should report the value we set.
            assertEquals(2, station.getMaxTurns())
        }
    }

    @Test
    fun testSetMaxHarnessTurnsStillWorksAsAlias()
    {
        runBlocking {
            val station = nonTerminatingStation()
            // Use the delegating alias - it must still bound the loop.
            station.setMaxHarnessTurns(3)

            station.executeLocal(MultimodalContent(text = "task"))

            val state = station.getTaskState()
            assertEquals(
                PumpStationError.MaxTurnsExceeded,
                state.lastError,
                "setMaxHarnessTurns(3) must still bound the loop as a delegating alias"
            )
            assertEquals(
                3,
                state.turnIndex,
                "Loop must run exactly 3 turns and stop when setMaxHarnessTurns(3) is set"
            )
            // Both getters must report the same value because they share the field.
            assertEquals(3, station.getMaxTurns(), "getMaxTurns must reflect the alias write")
            assertEquals(3, station.getMaxHarnessTurns(), "getMaxHarnessTurns must reflect the alias write")
        }
    }

    @Test
    fun testSetMaxTurnsOverridesEarlierSetMaxHarnessTurnsCall()
    {
        runBlocking {
            val station = nonTerminatingStation()
            // Write the alias first, then the canonical setter. The canonical setter
            // is the more recent write and must win (the field is shared).
            station.setMaxHarnessTurns(7)
            station.setMaxTurns(4)

            station.executeLocal(MultimodalContent(text = "task"))

            val state = station.getTaskState()
            assertEquals(
                4,
                state.turnIndex,
                "Most-recent setter wins; setMaxTurns(4) overrides earlier setMaxHarnessTurns(7)"
            )
            assertEquals(4, station.getMaxTurns())
            assertEquals(4, station.getMaxHarnessTurns())
        }
    }

    @Test
    fun testDslMaxTurnsBuildsStationThatObeysCap()
    {
        runBlocking {
            // Build a station via the DSL with maxTurns = 2. The harness loop must
            // honor the DSL's configured value once the build step has applied it.
            val station = pumpStation("dsl-max-turns-test") {
                dispatchAgent = Pipeline()
                judgeAgent = Pipeline()
                maxTurns = 2
                path("p1") {
                    description = "p1"
                    setInternalAgent(SgTestAgent(agentTag = "p1"))
                }
            }
            // Replace the agents with scripted ones that drive a non-terminating loop.
            // (Building the station with real LLMs is out of scope for unit tests.)
            val scriptedJudgePipe = ScriptedTestPipe(
                response = """{"isComplete": false, "shouldTerminate": false}"""
            )
            val scriptedDispatchPipe = ScriptedTestPipe(
                response = """{"pathName": "p1", "pathSchema": "{}"}"""
            )
            station.setJudgeAgent(Pipeline().apply { add(scriptedJudgePipe) })
            station.setDispatchAgent(Pipeline().apply { add(scriptedDispatchPipe) })
            // Replace the SgTestAgent-backed path with one that uses a real exec fn so
            // the loop can iterate without LLM calls.
            station.addPath(testPath("p1"))

            assertEquals(2, station.getMaxTurns(), "DSL's maxTurns must round-trip onto the station")

            station.executeLocal(MultimodalContent(text = "task"))

            val state = station.getTaskState()
            assertEquals(
                PumpStationError.MaxTurnsExceeded,
                state.lastError,
                "DSL-built station must honor maxTurns = 2 in the harness loop"
            )
            assertEquals(2, state.turnIndex)
        }
    }

    @Test
    fun testDslMaxHarnessTurnsStillPropagatesAsAlias()
    {
        runBlocking {
            // The DSL's `maxHarnessTurns = N` should still work. After this plan it
            // is a delegating accessor on the builder that writes through to maxTurns.
            val station = pumpStation("dsl-max-harness-turns-test") {
                dispatchAgent = Pipeline()
                judgeAgent = Pipeline()
                maxHarnessTurns = 3
                path("p1") {
                    description = "p1"
                    setInternalAgent(SgTestAgent(agentTag = "p1"))
                }
            }
            val scriptedJudgePipe = ScriptedTestPipe(
                response = """{"isComplete": false, "shouldTerminate": false}"""
            )
            val scriptedDispatchPipe = ScriptedTestPipe(
                response = """{"pathName": "p1", "pathSchema": "{}"}"""
            )
            station.setJudgeAgent(Pipeline().apply { add(scriptedJudgePipe) })
            station.setDispatchAgent(Pipeline().apply { add(scriptedDispatchPipe) })
            station.addPath(testPath("p1"))

            // The DSL var `maxHarnessTurns = 3` should have written the same field as
            // `maxTurns = 3` would have.
            assertEquals(3, station.getMaxHarnessTurns(), "DSL's maxHarnessTurns must round-trip")
            assertEquals(3, station.getMaxTurns(), "DSL's maxHarnessTurns must write the canonical field")

            station.executeLocal(MultimodalContent(text = "task"))

            assertEquals(
                PumpStationError.MaxTurnsExceeded,
                station.getTaskState().lastError,
                "DSL's maxHarnessTurns alias must bound the harness loop"
            )
            assertEquals(3, station.getTaskState().turnIndex)
        }
    }

    @Test
    fun testSetMaxTurnsZeroRunsUnlimitedUntilExitSignal()
    {
        runBlocking {
            val calls = AtomicInteger(0)
            val passOnFifthCallPath = PathObject().apply {
                pathName = "p1"
                pathDescription = "Passes on 5th call"
                setExecutionFunction { _, _, _, _ ->
                    val count = calls.incrementAndGet()
                    MultimodalContent(text = "step $count").apply {
                        if(count >= 5) passPipeline = true
                    }
                }
            }
            val judgePipe = ScriptedTestPipe(
                response = """{"isComplete": false, "shouldTerminate": false}"""
            )
            val dispatchPipe = ScriptedTestPipe(
                response = """{"pathName": "p1", "pathSchema": "{}"}"""
            )
            val station = PumpStation()
                .setJudgeAgent(Pipeline().apply { add(judgePipe) })
                .setDispatchAgent(Pipeline().apply { add(dispatchPipe) })
                .setMaxTurns(0)
                .apply { addPath(passOnFifthCallPath) }

            assertEquals(0, station.getMaxTurns(), "getMaxTurns must return 0 when unlimited")
            assertEquals(0, station.getMaxHarnessTurns(), "getMaxHarnessTurns must return 0 when unlimited")

            station.executeLocal(MultimodalContent(text = "task"))

            val state = station.getTaskState()
            assertEquals(5, calls.get(), "Unlimited loop (maxTurns = 0) must run until exit signal on turn 5")
            assertNull(state.lastError, "Unlimited loop must not fail with MaxTurnsExceeded")
            assertEquals(PumpStationExitReason.PassSignal, state.exitReason)
        }
    }

    @Test
    fun testDslMaxHarnessTurnsZeroRunsUnlimited()
    {
        runBlocking {
            val calls = AtomicInteger(0)
            val station = pumpStation("dsl-unlimited-test") {
                dispatchAgent = Pipeline()
                judgeAgent = Pipeline()
                maxHarnessTurns = 0
                path("p1") {
                    description = "p1"
                    setInternalAgent(SgTestAgent(agentTag = "p1"))
                }
            }
            val scriptedJudgePipe = ScriptedTestPipe(
                response = """{"isComplete": false, "shouldTerminate": false}"""
            )
            val scriptedDispatchPipe = ScriptedTestPipe(
                response = """{"pathName": "p1", "pathSchema": "{}"}"""
            )
            val passOnFourthCallPath = PathObject().apply {
                pathName = "p1"
                pathDescription = "Passes on 4th call"
                setExecutionFunction { _, _, _, _ ->
                    val count = calls.incrementAndGet()
                    MultimodalContent(text = "step $count").apply {
                        if(count >= 4) passPipeline = true
                    }
                }
            }
            station.setJudgeAgent(Pipeline().apply { add(scriptedJudgePipe) })
            station.setDispatchAgent(Pipeline().apply { add(scriptedDispatchPipe) })
            station.addPath(passOnFourthCallPath)

            assertEquals(0, station.getMaxTurns())
            assertEquals(0, station.getMaxHarnessTurns())

            station.executeLocal(MultimodalContent(text = "task"))

            val state = station.getTaskState()
            assertEquals(4, calls.get(), "DSL maxHarnessTurns = 0 must run unlimited turns until exit signal")
            assertNull(state.lastError)
            assertEquals(PumpStationExitReason.PassSignal, state.exitReason)
        }
    }

    @Test
    fun testNegativeMaxTurnsRunsUnlimited()
    {
        runBlocking {
            val calls = AtomicInteger(0)
            val passOnThirdCallPath = PathObject().apply {
                pathName = "p1"
                pathDescription = "Passes on 3rd call"
                setExecutionFunction { _, _, _, _ ->
                    val count = calls.incrementAndGet()
                    MultimodalContent(text = "step $count").apply {
                        if(count >= 3) passPipeline = true
                    }
                }
            }
            val judgePipe = ScriptedTestPipe(
                response = """{"isComplete": false, "shouldTerminate": false}"""
            )
            val dispatchPipe = ScriptedTestPipe(
                response = """{"pathName": "p1", "pathSchema": "{}"}"""
            )
            val station = PumpStation()
                .setJudgeAgent(Pipeline().apply { add(judgePipe) })
                .setDispatchAgent(Pipeline().apply { add(dispatchPipe) })
                .setMaxTurns(-1)
                .apply { addPath(passOnThirdCallPath) }

            station.executeLocal(MultimodalContent(text = "task"))

            val state = station.getTaskState()
            assertEquals(3, calls.get(), "Negative maxTurns (-1) must disable the turn cap")
            assertNull(state.lastError)
            assertEquals(PumpStationExitReason.PassSignal, state.exitReason)
        }
    }
}
