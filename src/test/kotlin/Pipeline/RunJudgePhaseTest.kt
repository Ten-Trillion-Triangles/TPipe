package com.TTT.Pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class RunJudgePhaseTest
{
    @Test
    fun testJudgePhaseParsesIsComplete()
    {
        val station = buildTestStation()
        val judgePipe = ScriptedTestPipe(response = """{"isComplete": true, "shouldTerminate": false}""")
        val judge = Pipeline().apply { add(judgePipe) }
        station.setJudgeAgent(judge)

        runBlocking {
            val verdict = station.runJudgePhase()
            assertTrue(verdict.isComplete)
        }
    }

    @Test
    fun testJudgePhaseEmitsStartedAndCompletedEvents()
    {
        val station = buildTestStation()
        val judgePipe = ScriptedTestPipe(response = """{"isComplete": false, "shouldTerminate": false}""")
        val judge = Pipeline().apply { add(judgePipe) }
        station.setJudgeAgent(judge)

        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)

        runBlocking { station.runJudgePhase() }
        assertTrue(events.any { it is JudgeStarted }, "JudgeStarted should be emitted")
        assertTrue(events.any { it is JudgeCompleted }, "JudgeCompleted should be emitted")
    }
}
