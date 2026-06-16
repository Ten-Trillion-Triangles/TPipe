package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class RefreshAgentInstancesTest
{
    @Test
    fun testJudgeBuilderFunctionIsInvokedPerTurn()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        var judgeCallCount = 0
        station.setJudgeAgentBuilderFunction { harness ->
            judgeCallCount++
            Pipeline()
        }
        runBlocking {
            station.refreshAgentInstances()
            station.refreshAgentInstances()
            station.refreshAgentInstances()
        }
        assertTrue(judgeCallCount >= 3, "Judge builder should be called every refresh")
    }
}
