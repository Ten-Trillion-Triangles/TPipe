// PumpStation B4: No PathTimeout error subtype — transport timeouts are
// misclassified as PathExecutionException. RED test asserts that when a
// path call throws a SocketTimeoutException, the emitted PathFailed
// event has error=PathTimeout, NOT error=PathExecutionException.

package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import kotlin.test.assertEquals

class PumpStationPathTimeoutTest
{
    @Test
    fun pathTimeoutEmitsPathTimeoutError()
    {
        val captured = mutableListOf<PathFailed>()
        val seen = mutableSetOf<Pair<Int, Long>>()
        val station = buildTestStation(maxHarnessTurns = 1)
        val judgePipe = ScriptedTestPipe(
            name = "judge",
            response = """{"isComplete": false, "shouldTerminate": false, "reason": "keep going"}"""
        )
        val judge = Pipeline().apply { add(judgePipe) }
        val dispatchPipe = ScriptedTestPipe(
            name = "dispatch",
            response = """{"pathName": "timeout-path", "pathSchema": "{}"}"""
        )
        val dispatch = Pipeline().apply { add(dispatchPipe) }
        station.setJudgeAgent(judge)
        station.setDispatchAgent(dispatch)

        // Path that throws a SocketTimeoutException — the harness must
        // classify this as PathTimeout, not PathExecutionException.
        val timeoutPath = PathObject().apply {
            pathName = "timeout-path"
            pathDescription = "Path that simulates a network timeout"
            setExecutionFunction { _, _, _, _ ->
                throw SocketTimeoutException("simulated read timeout")
            }
        }
        station.addPath(timeoutPath)

        station.setEventObserver { event ->
            if (event is PathFailed) {
                // Observer fires twice per event (emit + finalization drain);
                // dedupe by (turnIndex, timestamp) per oracle pitfall #1.
                val key = event.turnIndex to event.timestamp
                if (seen.add(key)) {
                    captured.add(event)
                }
            }
        }

        runBlocking {
            station.executeLocal(MultimodalContent(text = "go"))
        }

        val pf = captured.firstOrNull()
        assertEquals(
            PumpStationError.PathTimeout, pf?.error,
            "B4 RED: expected PathTimeout for SocketTimeoutException, got ${pf?.error}"
        )
    }
}