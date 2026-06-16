package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PumpStationSnapshotTest
{
    @Test
    fun testSnapshotCapturesState()
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
            station.executeLocal(MultimodalContent(text = "task"))

            val snapshot = station.saveSnapshot()
            assertNotNull(snapshot)
            assertEquals(station.getTaskState().runId, snapshot.taskState.runId)
        }
    }
}
