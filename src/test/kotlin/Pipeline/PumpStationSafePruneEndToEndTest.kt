package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Offline end-to-end PumpStation test that drives SafePrune through a real
 * harness loop using the [ScriptedTestPipe] pattern proven in
 * [PumpStationEndToEndTest].
 *
 * Goal: prove the v1+v2 SafePrune instrumentation works end-to-end. The test
 * uses scripted judge + dispatch agents that return canned JSON, and a real
 * path that returns canned text. The history is pre-seeded with enough
 * identical entries to trigger the SafePrune size gate, and a
 * [com.TTT.Pipeline.SafePruneApplied] event must fire with the right
 * [com.TTT.Pipeline.SafePruneReport] numbers.
 *
 * This is the offline companion to the live
 * [com.TTT.Pipeline.PumpStationSafePruneLiveTest] — the live test exercises
 * the same plumbing through a real M2.7 / OpenRouter call; this one proves
 * the harness plumbing itself works deterministically without network I/O.
 */
class PumpStationSafePruneEndToEndTest
{
    @Test
    fun testSafePruneAppliedFires(): Unit = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        // The judge must NOT exit on the first turn — that path short-circuits to
        // runExitFlow() and never runs runSafePrunePhase. We use a judge that
        // returns isComplete=false on every call, then maxHarnessTurns caps the
        // loop at N turns. SafePrune fires on every turn that has size > threshold.
        val station = buildTestStation(maxHarnessTurns = 4)
            .setJudgeAgent(judgeThatDoesNotComplete())
            .setDispatchAgent(dispatchAlwaysPicks("echo"))
            .setEventObserver { events.add(it) }
            .setMaxGoalFailAttempts(1)
            .also { it.configureSafePruneForTest() }

        repeat(SEED_HISTORY_COUNT) {
            station.turnHistory.add(
                ConverseData(
                    role = ConverseRole.user,
                    content = MultimodalContent(text = "echo")
                )
            )
        }

        // The path returns plain text. It does NOT signal passPipeline — that
        // would Halt the harness before runSafePrunePhase gets called on this turn.
        station.addPath(testPath("echo", returnText = "echo"))

        station.executeLocal(MultimodalContent(text = "echo"))

        // The loop exits via MaxTurnsHit because the judge never signals isComplete.
        assertEquals(PumpStationExitReason.MaxTurnsHit, station.getTaskState().exitReason)

        val applied = synchronized(events) { events.filterIsInstance<SafePruneApplied>() }
        assertTrue(applied.isNotEmpty(), "SafePruneApplied must fire when size > threshold")

        println("=== testSafePruneAppliedFires: SafePruneApplied events (count=${applied.size}) ===")
        applied.forEachIndexed { i, ev ->
            println("  [$i] turnIndex=${ev.turnIndex} originalCount=${ev.report.originalCount} " +
                "finalCount=${ev.report.finalCount} tokensRemoved=${ev.report.tokensRemoved} " +
                "enabledFlags=${ev.report.enabledFlags}")
        }

        val latest = applied.last()
        assertTrue(
            latest.report.originalCount > 4,
            "SafePruneApplied must report originalCount > safePruneSizeThreshold (4), got ${latest.report.originalCount}"
        )
        assertTrue(
            latest.report.finalCount <= latest.report.originalCount,
            "SafePruneApplied.finalCount (${latest.report.finalCount}) must be <= originalCount (${latest.report.originalCount})"
        )
        assertNotNull(latest.report.enabledFlags, "SafePruneApplied must carry enabledFlags")
    }

    @Test
    fun testSafePruneNoEventFiresWhenSizeGateNotMet(): Unit = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val station = buildTestStation(maxHarnessTurns = 3)
            .setJudgeAgent(judgeThatCompletesAfterOneTurn())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            // Disable the first-turn skip: this test seeds history to a known size and
            // expects the harness to exit immediately after the judge returns isComplete=true
            // on turn 0. With skipJudgeOnFirstTurn=true (default), turn 0 jumps straight to
            // dispatch + path execution, which grows history past the size threshold and
            // triggers SafePrune. The test's intent is to verify the size-gate mechanism,
            // not to test the first-turn skip.
            .setSkipJudgeOnFirstTurn(false)
            .setEventObserver { events.add(it) }
            .also { it.configureSafePruneForTest() }

        repeat(4) {
            station.turnHistory.add(
                ConverseData(
                    role = ConverseRole.user,
                    content = MultimodalContent(text = "seed")
                )
            )
        }
        station.addPath(testPath("p1", returnText = "ok"))

        station.executeLocal(MultimodalContent(text = "go"))

        val applied = synchronized(events) { events.filterIsInstance<SafePruneApplied>() }
        assertTrue(applied.isEmpty(), "SafePruneApplied must NOT fire when size <= threshold")
    }

    @Test
    fun testSafePruneDisabledByDefaultNeverFires(): Unit = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val station = buildTestStation(maxHarnessTurns = 3)
            .setJudgeAgent(judgeThatCompletesAfterOneTurn())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setEventObserver { events.add(it) }

        repeat(8) {
            station.turnHistory.add(
                ConverseData(
                    role = ConverseRole.user,
                    content = MultimodalContent(text = "echo")
                )
            )
        }
        station.addPath(testPath("p1", returnText = "echo"))

        station.executeLocal(MultimodalContent(text = "echo"))

        assertEquals(PumpStationExitReason.JudgeComplete, station.getTaskState().exitReason)
        val applied = synchronized(events) { events.filterIsInstance<SafePruneApplied>() }
        assertTrue(applied.isEmpty(), "SafePruneApplied must NOT fire when safePrune is disabled")
    }

    private fun PumpStation.configureSafePruneForTest() {
        setSafePruneEnabled(true)
        setSafePruneSizeThreshold(SEED_HISTORY_COUNT - 2)
        setSafePruneProtectRecentN(1)
        enableSafePruneStrategy(SafePruneStrategy.DropPureEchoes)
    }

    private fun judgeThatCompletesAfterOneTurn(): Pipeline =
        Pipeline().apply {
            add(ScriptedTestPipe(response = judgeScriptedResponse(isComplete = true, shouldTerminate = false).text))
        }

    private fun judgeThatDoesNotComplete(): Pipeline =
        Pipeline().apply {
            add(ScriptedTestPipe(response = judgeScriptedResponse(isComplete = false, shouldTerminate = false).text))
        }

    private fun dispatchAlwaysPicks(pathName: String): Pipeline =
        Pipeline().apply {
            add(ScriptedTestPipe(response = dispatchScriptedResponse(pathName).text))
        }

    private companion object
    {
        const val SEED_HISTORY_COUNT = 6
    }
}
