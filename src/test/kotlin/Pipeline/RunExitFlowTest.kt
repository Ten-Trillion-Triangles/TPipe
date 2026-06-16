package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunExitFlowTest
{
    @Test
    fun testExitFlowHaltWhenNoGoalAgent()
    {
        val station = PumpStation()
        runBlocking {
            val result = station.runExitFlow()
            assertTrue(result is TurnResult.Halt)
            assertEquals(PumpStationExitReason.JudgeComplete, (result as TurnResult.Halt).reason)
        }
    }

    @Test
    fun testExitFlowGoalFailAppendsToHistoryAndContinues()
    {
        val station = PumpStation()
        val goal = MockP2PAgent(script = listOf(
            MultimodalContent(text = "you missed something", terminatePipeline = true)
        ))
        station.setGoalAgent(goal)
        station.setMaxGoalFailAttempts(3)

        runBlocking {
            val result = station.runExitFlow()
            assertTrue(result is TurnResult.Continue)
            assertEquals(1, station.getTaskState().goalFailCount)
        }
    }

    @Test
    fun testExitFlowHaltWhenMaxGoalFailAttemptsExceeded()
    {
        val station = PumpStation()
        val goal = MockP2PAgent(script = listOf(
            MultimodalContent(text = "fail", terminatePipeline = true)
        ))
        station.setGoalAgent(goal)
        station.setMaxGoalFailAttempts(0)
        station.getTaskState().goalFailCount = 1  // already over

        runBlocking {
            val result = station.runExitFlow()
            assertTrue(result is TurnResult.Halt)
            assertEquals(PumpStationExitReason.GoalValidationFailed, (result as TurnResult.Halt).reason)
        }
    }
}
