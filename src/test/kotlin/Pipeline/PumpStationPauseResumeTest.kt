package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PumpStationPauseResumeTest
{
    @Test
    fun testPauseAtBeforeJudgeSuspendsExecution()
    {
        runBlocking {
            val station = buildTestStation()
            val judge = Pipeline().apply {
                val pipe = ScriptedTestPipe().apply { response = """{"isComplete": true, "shouldTerminate": false}""" }
                add(pipe)
            }
            val dispatch = Pipeline().apply {
                val pipe = ScriptedTestPipe().apply { response = """{"pathName": "p1", "pathSchema": "{}"}""" }
                add(pipe)
            }
            station.setJudgeAgent(judge).setDispatchAgent(dispatch)
            station.addPath(testPath("p1", returnText = "result1"))
            station.pauseAt(PumpStationPausePhase.BeforeJudge)

            val events = mutableListOf<PumpStationEvent>()
            station.setEventObserver(events::add)

            val job = launch { station.executeLocal(MultimodalContent(text = "task")) }
            delay(50)  // let it reach the pause point
            assertTrue(station.getTaskState().isPaused, "Station should be paused")
            assertTrue(events.any { it is HarnessSuspended }, "HarnessSuspended event should be emitted")

            station.resume()
            job.join()

            assertFalse(station.getTaskState().isPaused, "Station should be resumed")
        }
    }
}
