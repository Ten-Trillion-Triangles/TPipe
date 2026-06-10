package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmergencyHaltTest
{
    @Test
    fun testTripKillSwitchStopsLoopOnNextIteration()
    {
        runBlocking {
            val station = buildTestStation(maxHarnessTurns = 10)
            val judge = Pipeline().apply {
                val pipe = ScriptedTestPipe().apply { response = """{"isComplete": false, "shouldTerminate": false}""" }
                add(pipe)
            }
            val dispatch = Pipeline().apply {
                val pipe = ScriptedTestPipe().apply { response = """{"pathName": "p1", "pathSchema": "{}"}""" }
                add(pipe)
            }
            station.setJudgeAgent(judge).setDispatchAgent(dispatch)
            station.addPath(testPath("p1"))
            station.tripKillSwitch()
            station.executeLocal(MultimodalContent(text = "task"))
            assertEquals(PumpStationError.KillSwitchTripped, station.getTaskState().lastError)
        }
    }

    @Test
    fun testForceHaltSetsExitReason()
    {
        runBlocking {
            val station = buildTestStation()
            station.forceHalt(PumpStationExitReason.InterventionTerminated)
            assertEquals(PumpStationExitReason.InterventionTerminated, station.getTaskState().exitReason)
        }
    }
}
