package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the `skipJudgeOnFirstTurn` PumpStation DSL field.
 *
 * Background: a live judge LLM may see the pre-dispatch state (just the system task and user
 * prompt) and return `isComplete = true` based on a hallucinated brief. The harness then
 * short-circuits before any path ever fires and the loop is permanently broken. The fix
 * adds a per-harness boolean that, when true, skips the judge on turn 0 unconditionally
 * and forces dispatch to run at least once before the judge gets a verdict vote.
 *
 * Default is `true`. Set to `false` to restore the legacy "judge fires on turn 0" behavior.
 *
 * The skip emits a [JudgeSkipped] event with `reason = "first_turn"` so traces and the
 * HTML visualizer make it visible when the judge was bypassed.
 */
class PumpStationSkipJudgeOnFirstTurnTest
{
    // ---- Helpers ----

    private fun alwaysDoneJudge(): Pipeline
    {
        // A judge that ALWAYS returns isComplete=true. With skipJudgeOnFirstTurn=true
        // the harness must not call this judge on turn 0 — otherwise the loop short-circuits
        // before any path ever runs.
        val pipe = ScriptedTestPipe(response = """{"isComplete": true, "shouldTerminate": false}""")
        return Pipeline().apply { add(pipe) }
    }

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
     * The harness intentionally emits every event to the synchronous observer twice (once at
     * emit, once at finalization drain). Collapse duplicates by (turnIndex, timestamp) so
     * the tests see the logical stream the developer would experience.
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

    // ---- Default (skipJudgeOnFirstTurn = true): judge NEVER fires on turn 0 ----

    @Test
    fun testDefaultSkipsJudgeOnTurnZero()
    {
        runBlocking {
            val (events, observer) = eventRecorder()
            val station = buildTestStation(maxHarnessTurns = 3)
                .setJudgeAgent(alwaysDoneJudge())
                .setDispatchAgent(dispatchAlwaysPicks("p1"))
                .setEventObserver(observer)
            station.addPath(testPath("p1", returnText = "did something"))

            station.executeLocal(MultimodalContent(text = "task"))

            val skipped = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeSkipped>() })
            val started = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeStarted>() })
            val completed = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeCompleted>() })
            val dispatched = uniqueBy(synchronized(events) { events.filterIsInstance<DispatchStarted>() })

            // Turn 0 must have a JudgeSkipped event with reason="first_turn"
            val turnZeroSkip = skipped.firstOrNull { it.turnIndex == 0 }
            assertTrue(turnZeroSkip != null, "JudgeSkipped must fire on turn 0 by default")
            assertEquals("first_turn", turnZeroSkip!!.reason)

            // JudgeStarted/JudgeCompleted must NOT fire on turn 0
            val turnZeroStarted = started.firstOrNull { it.turnIndex == 0 }
            assertTrue(turnZeroStarted == null, "JudgeStarted must NOT fire on turn 0 with skipJudgeOnFirstTurn=true")

            // But dispatch MUST fire on turn 0 — the whole point of skipping the judge
            val turnZeroDispatch = dispatched.firstOrNull { it.turnIndex == 0 }
            assertTrue(turnZeroDispatch != null, "DispatchStarted must fire on turn 0 even when judge is skipped")

            // JudgeStarted must fire on turn 1 (skip is only first-turn)
            val turnOneStarted = started.firstOrNull { it.turnIndex == 1 }
            assertTrue(turnOneStarted != null, "JudgeStarted must fire on turn 1+ (skip is first-turn only)")
        }
    }

    // ---- The core regression test: with alwaysDoneJudge, skip must prevent short-circuit ----

    @Test
    fun testAlwaysDoneJudgeDoesNotShortCircuitOnTurnZeroWithSkipEnabled()
    {
        runBlocking {
            val (events, observer) = eventRecorder()
            val pathCallCount = IntArray(1)
            val station = buildTestStation(maxHarnessTurns = 4)
                .setJudgeAgent(alwaysDoneJudge())
                .setDispatchAgent(dispatchAlwaysPicks("p1"))
                .setEventObserver(observer)
            station.addPath(testPath("p1", returnText = "did something", callCount = pathCallCount))

            station.executeLocal(MultimodalContent(text = "task"))

            // If judge had been called on turn 0 with isComplete=true, the harness would have
            // returned via runExitFlow and the path would NEVER have been invoked.
            assertTrue(pathCallCount[0] >= 1, "Path 'p1' must be invoked at least once. " +
                "If this fires, the always-done judge short-circuited the loop on turn 0.")

            // The loop should have terminated via MaxTurnsExceeded (judge returned isComplete=true
            // on turn 1+, the harness exited cleanly), NOT via JudgeComplete-on-turn-0.
            val finalState = station.getTaskState()
            val terminatedByJudge = finalState.exitReason == PumpStationExitReason.JudgeComplete &&
                pathCallCount[0] == 0
            assertFalse(terminatedByJudge,
                "Harness terminated via JudgeComplete on turn 0 — skipJudgeOnFirstTurn is broken")
        }
    }

    // ---- Opt-out: skipJudgeOnFirstTurn = false → legacy behavior preserved ----

    @Test
    fun testOptOutFiresJudgeOnTurnZero()
    {
        runBlocking {
            val (events, observer) = eventRecorder()
            val station = buildTestStation(maxHarnessTurns = 3)
                .setJudgeAgent(alwaysDoneJudge())
                .setDispatchAgent(dispatchAlwaysPicks("p1"))
                .setSkipJudgeOnFirstTurn(false)
                .setEventObserver(observer)
            station.addPath(testPath("p1", returnText = "did something"))

            station.executeLocal(MultimodalContent(text = "task"))

            val skipped = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeSkipped>() })
            val started = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeStarted>() })

            // No first_turn skip should fire when explicitly disabled
            val firstTurnSkip = skipped.firstOrNull { it.turnIndex == 0 && it.reason == "first_turn" }
            assertTrue(firstTurnSkip == null,
                "JudgeSkipped(reason=first_turn) must NOT fire when skipJudgeOnFirstTurn=false")

            // JudgeStarted must fire on turn 0 (legacy behavior)
            val turnZeroStarted = started.firstOrNull { it.turnIndex == 0 }
            assertTrue(turnZeroStarted != null,
                "JudgeStarted must fire on turn 0 when skipJudgeOnFirstTurn=false")
        }
    }

    // ---- skipJudgeOnFirstTurn must NOT interact with PumpStationJudgeRunMode.FlagTriggered ----
    // FlagTriggered already has its own skip path; first-turn skip must be a no-op there.

    @Test
    fun testFlagTriggeredModeTakesPrecedenceOverFirstTurnSkip()
    {
        runBlocking {
            val (events, observer) = eventRecorder()
            val station = buildTestStation(maxHarnessTurns = 2)
                .setJudgeAgent(alwaysDoneJudge())
                .setDispatchAgent(dispatchAlwaysPicks("p1"))
                .setJudgeRunMode(PumpStationJudgeRunMode.FlagTriggered)
                // skipJudgeOnFirstTurn left at default (true). The skip reason should still
                // be the FlagTriggered no_flag_set reason — not first_turn.
                .setEventObserver(observer)
            station.addPath(testPath("p1", returnText = "did something"))

            station.executeLocal(MultimodalContent(text = "task"))

            val skipped = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeSkipped>() })
            // Every skip in FlagTriggered mode must carry the canonical reason — no first_turn slips in
            assertTrue(skipped.isNotEmpty(), "JudgeSkipped must fire in FlagTriggered mode")
            skipped.forEach { s ->
                assertEquals("no_flag_set", s.reason,
                    "FlagTriggered must keep its canonical reason; first_turn leaked: ${s}")
                assertEquals(PumpStationJudgeRunMode.FlagTriggered, s.judgeRunMode)
            }
        }
    }

    // ---- DSL getter / setter round-trip ----

    @Test
    fun testDslGetterSetterRoundTrip()
    {
        // Default value
        val station = buildTestStation()
        assertTrue(station.getSkipJudgeOnFirstTurn(),
            "Default value of skipJudgeOnFirstTurn must be true")

        // Setter flips it
        station.setSkipJudgeOnFirstTurn(false)
        assertFalse(station.getSkipJudgeOnFirstTurn())
        station.setSkipJudgeOnFirstTurn(true)
        assertTrue(station.getSkipJudgeOnFirstTurn())
    }

    // ---- skipJudgeOnFirstTurn composes with FlagTriggered: FlagTriggered wins on turn 0 too ----
    // When developer opts into FlagTriggered, they want strict skip-every-turn semantics.
    // The first-turn skip is a convenience for Always mode; it must NOT change the skip
    // reason in FlagTriggered mode. Both skips are functionally identical (no judge LLM call)
    // — the reason field is for trace clarity, and FlagTriggered's no_flag_set reason is the
    // canonical one for "skip because no judge run was requested".

    @Test
    fun testFirstTurnSkipPlusFlagTriggeredKeepsFlagTriggeredReason()
    {
        runBlocking {
            val (events, observer) = eventRecorder()
            val pathCallCount = IntArray(1)
            val station = buildTestStation(maxHarnessTurns = 3)
                .setJudgeAgent(notDoneJudge())
                .setDispatchAgent(dispatchAlwaysPicks("p1"))
                .setJudgeRunMode(PumpStationJudgeRunMode.FlagTriggered)
                .setSkipJudgeOnFirstTurn(true)
                .setEventObserver(observer)
            station.addPath(testPath("p1", returnText = "did something", callCount = pathCallCount))

            station.executeLocal(MultimodalContent(text = "task"))

            // Path must run on turn 0 (judge skipped) AND on every subsequent turn.
            assertTrue(pathCallCount[0] >= 3,
                "Path should be invoked once per turn (3 turns). Got ${pathCallCount[0]}")

            val skipped = uniqueBy(synchronized(events) { events.filterIsInstance<JudgeSkipped>() })
            // Every skip in FlagTriggered mode carries the canonical no_flag_set reason
            // — the first_turn reason does NOT leak into FlagTriggered mode.
            assertTrue(skipped.isNotEmpty(), "JudgeSkipped must fire in FlagTriggered mode")
            skipped.forEach { s ->
                assertEquals("no_flag_set", s.reason,
                    "FlagTriggered must keep its canonical reason on every turn including turn 0. " +
                        "first_turn must NOT replace no_flag_set in FlagTriggered mode. Got: $s")
                assertEquals(PumpStationJudgeRunMode.FlagTriggered, s.judgeRunMode)
            }
        }
    }
}