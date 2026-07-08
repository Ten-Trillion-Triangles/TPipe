// PumpStation B3: applyRationaleNudgeIfNeeded unbounded append.
// RED test asserts that when requirePathSelectionRationale=true and the
// dispatch LLM emits an empty/null pathSelectionRationale every turn,
// the harness appends AT MOST ONE [Harness Notice] message to
// turnHistory (per run). The current bug is that every empty-rationale
// dispatch appends a fresh nudge, leading to N duplicate messages.

package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class PumpStationRationaleNudgeDedupTest
{
    @Test
    fun rationaleNudgeAppendsAtMostOncePerRun()
    {
        val station = buildTestStation(maxHarnessTurns = 5)
        station.setFailurePolicy(
            PumpStationFailurePolicy(requirePathSelectionRationale = true)
        )
        // Judge always says not complete so the harness keeps looping.
        val judgePipe = ScriptedTestPipe(
            name = "judge",
            response = """{"isComplete": false, "shouldTerminate": false, "reason": "keep going"}"""
        )
        val judge = Pipeline().apply { add(judgePipe) }
        // Dispatch emits a PathRequest with NO pathSelectionRationale field.
        // Per PathRequest data class, the missing field defaults to null,
        // which trips applyRationaleNudgeIfNeeded every turn.
        val dispatchPipe = ScriptedTestPipe(
            name = "dispatch",
            response = """{"pathName": "p1", "pathSchema": "{}"}"""
        )
        val dispatch = Pipeline().apply { add(dispatchPipe) }
        station.setJudgeAgent(judge)
        station.setDispatchAgent(dispatch)
        station.addPath(testPath("p1", returnText = "ok"))

        runBlocking {
            station.executeLocal(MultimodalContent(text = "drive the loop"))
        }

        val hintCount = station.turnHistory.history.count { conv ->
            conv.content.text?.contains("[Harness Notice]") == true
        }

        assertTrue(
            hintCount <= 1,
            "B3 RED: rationale nudge appended $hintCount times " +
                "(expected <=1 after dedup)"
        )
    }
}