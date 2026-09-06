// PumpStation B2: Loop-guard counter increments past maxConsecutiveSamePath.
// RED test asserts that when the maxConsecutiveSamePath guard fires, the
// consecutivePathCount is reset so re-selecting the same path doesn't
// keep incrementing past the limit. The current bug is that consecutive
// reaches 3, 4, 5, 6 as dispatch keeps picking the same path; the fix
// resets the counter so only one trip per "burst" of repeats.

package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class PumpStationLoopGuardResetTest
{
    @Test
    fun loopGuardCounterResetsAfterTrip()
    {
        val trips = mutableListOf<Pair<Int, Int>>() // (turnIndex, consecutive)
        val seen = mutableSetOf<Pair<Int, Int>>()
        val station = buildTestStation(maxHarnessTurns = 6)
        station.setMaxConsecutiveSamePath(3)
        // Judge always says not complete so the harness keeps looping.
        val judgePipe = ScriptedTestPipe(
            name = "judge",
            response = """{"isComplete": false, "shouldTerminate": false, "reason": "keep going"}"""
        )
        val judge = Pipeline().apply { add(judgePipe) }
        // Dispatch always picks the same path — the worst case for the guard.
        val dispatchPipe = ScriptedTestPipe(
            name = "dispatch",
            response = """{"pathName": "p1", "pathSchema": "{}"}"""
        )
        val dispatch = Pipeline().apply { add(dispatchPipe) }
        station.setJudgeAgent(judge)
        station.setDispatchAgent(dispatch)
        station.addPath(testPath("p1", returnText = "ok"))

        station.setEventObserver { event ->
            if (event is LoopGuardTripped && event.guard == "maxConsecutiveSamePath") {
                val consecutiveStr = event.detail.substringAfter("consecutive=")
                    .substringBefore(",")
                // The observer receives each publication once through the funnel.
                val key = event.turnIndex to event.timestamp.toInt()
                if (seen.add(key)) {
                    trips.add(event.turnIndex to consecutiveStr.toInt())
                }
            }
        }

        runBlocking {
            station.executeLocal(MultimodalContent(text = "drive the loop"))
        }

        // With the bug: trips grow monotonically — [3, 4, 5] over 6 turns.
        // With the fix: the guard fires once when consecutive reaches 3,
        // then the counter resets, so the next burst of 3 also fires once,
        // giving us at most 2 trips across 6 turns.
        assertTrue(
            trips.size <= 2,
            "B2 RED: loop guard fired ${trips.size} times across 6 turns " +
                "(expected <=2 after counter reset). Trip details: $trips"
        )
        assertTrue(
            trips.all { it.second == 3 },
            "B2 RED: every guard trip should fire at consecutive=3 " +
                "(limit), got: $trips"
        )
    }
}
