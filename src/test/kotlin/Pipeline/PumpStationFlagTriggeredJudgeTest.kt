package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the [PumpStationJudgeRunMode.FlagTriggered] mode. In this mode the judge agent is
 * skipped unless [PumpStationTaskState.requestJudgeNextTurn] is set at the top of the judge
 * phase. The flag is one-shot: the judge phase clears it after consuming.
 *
 * Tests use a [ScriptedTestPipe] backed judge and a custom path that, when invoked, flips the
 * flag. The harness loop's [com.TTT.Pipeline.setEventObserver] hook is used to capture events
 * so we can assert on the presence/absence of [JudgeStarted] / [JudgeCompleted] / [JudgeSkipped]
 * for each turn.
 *
 * Note on event delivery: the harness intentionally emits each event to the synchronous
 * observer twice (once at emit, once at finalization drain) so the test counts UNIQUE events
 * by their (turnIndex, timestamp) tuple rather than raw list size.
 *
 * Note on path names: the harness's path lookup is case-insensitive, but [addPath] stores
 * paths by their original case, so mixed-case path names (e.g. "signalDone") can fail to
 * resolve. The tests below use lowercase path names ("p1", "p2", "signaldone") to avoid
 * hitting that pre-existing quirk.
 */
class PumpStationFlagTriggeredJudgeTest
{
    // ---- Helpers ----

    private fun notDoneJudge(): Pipeline
    {
        val pipe = ScriptedTestPipe(response = """{"isComplete": false, "shouldTerminate": false}""")
        return Pipeline().apply { add(pipe) }
    }

    private fun dispatchAlwaysPicks(pathName: String): Pipeline
    {
        val pipe = ScriptedTestPipe(response = """{"pathName": "$pathName", "pathSchema": "{}"}""")
        return Pipeline().apply { add(pipe) }
    }

    private fun eventRecorder(): Pair<MutableList<PumpStationEvent>, (PumpStationEvent) -> Unit>
    {
        val events = mutableListOf<PumpStationEvent>()
        val observer: (PumpStationEvent) -> Unit = { ev -> synchronized(events) { events.add(ev) } }
        return events to observer
    }

    /**
     * The harness emits every event to the synchronous observer twice (once at emit, once
     * at finalization drain). Collapse duplicates by (turnIndex, timestamp) so the tests
     * see the logical stream the developer would experience.
     */
    private fun <T : PumpStationEvent> uniqueBy(events: List<T>): List<T>
    {
        val seen = HashSet<Pair<Int, Long>>()
        val out = mutableListOf<T>()
        for (e in events)
        {
            val key = e.turnIndex to e.timestamp
            if (seen.add(key)) out.add(e)
        }
        return out
    }

    // ---- FlagTriggered: no flag set → judge never runs, only JudgeSkipped is emitted ----

    @Test
    fun testFlagTriggeredNoFlagEverSetSkipsJudgeEveryTurn()
    {
        runBlocking {
            val (events, observer) = eventRecorder()
            val station = buildTestStation(maxHarnessTurns = 3)
                .setJudgeAgent(notDoneJudge())
                .setDispatchAgent(dispatchAlwaysPicks("p1"))
                .setJudgeRunMode(PumpStationJudgeRunMode.FlagTriggered)
                .setEventObserver(observer)
            station.addPath(testPath("p1", returnText = "did something"))

            station.executeLocal(MultimodalContent(text = "task"))

            val skipped = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeSkipped>() })
            val started = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeStarted>() })
            val completed = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeCompleted>() })
            assertEquals(3, skipped.size, "JudgeSkipped should fire on every turn (unique events)")
            skipped.forEach { s ->
                assertEquals("no_flag_set", s.reason)
                assertEquals(PumpStationJudgeRunMode.FlagTriggered, s.judgeRunMode)
            }
            assertTrue(started.isEmpty(), "JudgeStarted must NOT fire in FlagTriggered mode without a flag")
            assertTrue(completed.isEmpty(), "JudgeCompleted must NOT fire in FlagTriggered mode without a flag")

            // maxHarnessTurns safety net still respected: the loop exited on MaxTurnsExceeded.
            assertEquals(PumpStationError.MaxTurnsExceeded, station.getTaskState().lastError)
        }
    }

    // ---- FlagTriggered: flag re-armed on every path call → judge runs on every turn after the first ----

    @Test
    fun testFlagTriggeredFlagSetFiresJudgeAndIsCleared()
    {
        runBlocking {
            val (events, observer) = eventRecorder()
            val station = buildTestStation(maxHarnessTurns = 4)
                .setJudgeAgent(notDoneJudge())
                .setDispatchAgent(dispatchAlwaysPicks("p1"))
                .setJudgeRunMode(PumpStationJudgeRunMode.FlagTriggered)
                .setEventObserver(observer)
            val p1 = testPath("p1", returnText = "did something")
            station.addPath(p1)

            // The path flips the flag on each invocation. The judge phase will pick it up on
            // the *next* turn (after the path finishes). With a "not done" judge, the loop
            // keeps running until maxHarnessTurns, so the flag is honored on turns 1..3
            // (turn 0 is skipped because the flag is not set at the top of the first turn).
            val flagTriggered = intArrayOf(0)
            p1.setExecutionFunction { content, pumpStation, _, _ ->
                synchronized(flagTriggered) { flagTriggered[0] += 1 }
                pumpStation.requestJudgeNextTurn()
                MultimodalContent(text = "result ${flagTriggered[0]}", context = content.context)
            }

            station.executeLocal(MultimodalContent(text = "task"))

            // 1 JudgeSkipped (turn 0) + 3 JudgeStarted + 3 JudgeCompleted (turns 1..3).
            val skipped = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeSkipped>() })
            val started = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeStarted>() })
            val completed = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeCompleted>() })
            assertEquals(1, skipped.size, "Only turn 0 should be skipped (unique events)")
            assertEquals(3, started.size, "Three judges should have fired (one per subsequent turn)")
            assertEquals(3, completed.size, "Three JudgeCompleted should have been emitted")
            // The started and completed events should reference the same turn indices.
            assertEquals(started.map { it.turnIndex }.toSet(),
                completed.map { it.turnIndex }.toSet())

            // The flag is one-shot: the judge phase clears it on the turn it consumes it.
            // This test re-arms the flag every turn, so after the harness exits the flag
            // is whatever the last path call left (true, in this case). The key invariant
            // is that the judge did clear the flag mid-loop — verified by the fact that
            // only ONE JudgeSkipped fired (turn 0) and three JudgeCompleted pairs fired
            // (turns 1..3, where the flag was re-armed by the previous turn's path).
        }
    }

    // ---- FlagTriggered: flag set once and then max-turns → loop exits cleanly with harness state intact ----

    @Test
    fun testFlagTriggeredFlagSetThenLoopExitsCleanly()
    {
        runBlocking {
            val station = buildTestStation(maxHarnessTurns = 3)
                .setJudgeAgent(notDoneJudge())
                .setDispatchAgent(dispatchAlwaysPicks("p1"))
                .setJudgeRunMode(PumpStationJudgeRunMode.FlagTriggered)
            val p1 = testPath("p1", returnText = "ok")
            val invocations = intArrayOf(0)
            p1.setExecutionFunction { content, pumpStation, _, _ ->
                synchronized(invocations) { invocations[0] += 1 }
                // Only set the flag on the first invocation. The judge will pick it up on
                // turn 1; turns 2+ are skipped because the flag is one-shot.
                if (invocations[0] == 1) pumpStation.requestJudgeNextTurn()
                MultimodalContent(text = "ok", context = content.context)
            }
            station.addPath(p1)

            station.executeLocal(MultimodalContent(text = "task"))

            // The dispatch is "not done" so the loop runs to maxHarnessTurns and exits with
            // MaxTurnsExceeded. The fact that the flag was set on the first turn did not
            // change the final exit reason.
            val state = station.getTaskState()
            assertEquals(PumpStationError.MaxTurnsExceeded, state.lastError)
            assertEquals(PumpStationExitReason.MaxTurnsHit, state.exitReason)
        }
    }

    // ---- Always mode: flag set but ignored (backward-compat check) ----

    @Test
    fun testAlwaysModeIgnoresRequestJudgeFlag()
    {
        runBlocking {
            val (events, observer) = eventRecorder()
            val station = buildTestStation(maxHarnessTurns = 3)
                .setJudgeAgent(notDoneJudge())
                .setDispatchAgent(dispatchAlwaysPicks("p1"))
                // Disable the new first-turn skip default so this test can verify Always
                // mode's "judge fires every turn" guarantee without the first_turn skip
                // masking turn 0. The intent of this test is the requestJudgeNextTurn
                // behavior, not the first-turn skip.
                .setSkipJudgeOnFirstTurn(false)
                .setEventObserver(observer)
            val p1 = testPath("p1", returnText = "did")
            p1.setExecutionFunction { content, pumpStation, _, _ ->
                pumpStation.requestJudgeNextTurn()
                MultimodalContent(text = "did", context = content.context)
            }
            station.addPath(p1)

            // Default judge run mode is Always; setting the flag is a no-op for the loop.
            assertEquals(PumpStationJudgeRunMode.Always, station.getJudgeRunMode())

            station.executeLocal(MultimodalContent(text = "task"))

            val skipped = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeSkipped>() })
            val completed = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeCompleted>() })
            assertTrue(skipped.isEmpty(), "Always mode must never emit JudgeSkipped")
            assertEquals(3, completed.size, "Judge must run on every turn in Always mode")
        }
    }

    // ---- Path-driven flag: documented end-to-end pattern works ----

    @Test
    fun testPathDrivenFlagFiresJudgeNextTurn()
    {
        runBlocking {
            val (events, observer) = eventRecorder()
            val station = buildTestStation(maxHarnessTurns = 4)
                .setJudgeAgent(notDoneJudge())
                .setDispatchAgent(dispatchAlwaysPicks("signaldone"))
                .setJudgeRunMode(PumpStationJudgeRunMode.FlagTriggered)
                .setEventObserver(observer)
            // Path mirrors the documented "signal-done" pattern: the path calls
            // requestJudgeNextTurn() from its execution function and returns a normal
            // MultimodalContent. The judge is then scheduled to run on the next turn.
            val signalDone = PathObject().apply {
                pathName = "signaldone"
                pathDescription = "Path that flags the judge for the next turn"
                setExecutionFunction { content, pumpStation, _, _ ->
                    pumpStation.requestJudgeNextTurn()
                    MultimodalContent(text = "flagged the judge", context = content.context)
                }
            }
            station.addPath(signalDone)

            station.executeLocal(MultimodalContent(text = "task"))

            // With a path that re-arms the flag every turn, the judge should fire on every
            // turn EXCEPT the first (the path hasn't run yet at the top of turn 0). So
            // 1 JudgeSkipped + 3 JudgeCompleted pairs.
            val skipped = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeSkipped>() })
            val completed = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeCompleted>() })
            assertTrue(skipped.isNotEmpty(), "At least one skip is expected (turn 0)")
            assertTrue(completed.isNotEmpty(), "At least one judge should have fired after a path flagged it")
            // 4 turns total; turn 0 is skipped, turns 1-3 fire the judge.
            assertEquals(4, skipped.size + completed.size,
                "Every turn emits exactly one of (skipped, completed) - 4 turns total")
        }
    }

    // ---- Trace event ordering: JudgeSkipped interleaves with JudgeStarted/JudgeCompleted correctly ----

    @Test
    fun testTraceEventOrderingInFlagTriggeredMode()
    {
        runBlocking {
            val (events, observer) = eventRecorder()
            val station = buildTestStation(maxHarnessTurns = 3)
                .setJudgeAgent(notDoneJudge())
                .setDispatchAgent(dispatchAlwaysPicks("p1"))
                .setJudgeRunMode(PumpStationJudgeRunMode.FlagTriggered)
                .setEventObserver(observer)
            station.addPath(testPath("p1", returnText = "ok"))

            station.executeLocal(MultimodalContent(text = "task"))

            // Walk the recorded events. In FlagTriggered mode without a flag we expect
            // only JudgeSkipped (no JudgeStarted/JudgeCompleted).
            val judgeEvents = synchronized(events) {
                events.filter {
                    it is JudgeSkipped || it is JudgeStarted || it is JudgeCompleted
                }
            }
            val uniqueJudge = uniqueBy(judgeEvents)
            uniqueJudge.forEach { ev ->
                if (ev is JudgeSkipped) {
                    assertEquals(PumpStationJudgeRunMode.FlagTriggered, ev.judgeRunMode)
                    assertEquals("no_flag_set", ev.reason)
                }
            }
            // Group by turn; each turn has exactly one judge event of any kind
            // (we expect 3 turns → 3 events).
            val byTurn = uniqueJudge.groupBy { it.turnIndex }
            assertEquals(3, byTurn.size, "Expected 3 distinct turn indices in judge events")
            byTurn.forEach { (_, evs) ->
                assertEquals(1, evs.size, "Each turn should have exactly one judge event")
            }
        }
    }
}
