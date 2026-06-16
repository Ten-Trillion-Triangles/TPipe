package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PumpStationEndToEndTest
{
    @Test
    fun testHappyPath3TurnsComplete()
    {
        runBlocking {
            val station = buildTestStation(maxHarnessTurns = 5)
            val judgePipe = ScriptedTestPipe(response = """{"isComplete": true, "shouldTerminate": false}""")
            val judge = Pipeline().apply { add(judgePipe) }
            val dispatchPipe = ScriptedTestPipe(response = """{"pathName": "p1", "pathSchema": "{}"}""")
            val dispatch = Pipeline().apply { add(dispatchPipe) }
            station.setJudgeAgent(judge).setDispatchAgent(dispatch)
            station.addPath(testPath("p1", returnText = "result1"))

            val result = station.executeLocal(MultimodalContent(text = "do the thing"))

            assertEquals(PumpStationExitReason.JudgeComplete, station.getTaskState().exitReason)
        }
    }

    @Test
    fun testLoopStopsAtMaxTurns()
    {
        runBlocking {
            val station = buildTestStation(maxHarnessTurns = 2)
            val judgePipe = ScriptedTestPipe(response = """{"isComplete": false, "shouldTerminate": false}""")
            val judge = Pipeline().apply { add(judgePipe) }
            val dispatchPipe = ScriptedTestPipe(response = """{"pathName": "p1", "pathSchema": "{}"}""")
            val dispatch = Pipeline().apply { add(dispatchPipe) }
            station.setJudgeAgent(judge).setDispatchAgent(dispatch)
            station.addPath(testPath("p1"))

            station.executeLocal(MultimodalContent(text = "task"))

            assertEquals(PumpStationError.MaxTurnsExceeded, station.getTaskState().lastError)
        }
    }
}
