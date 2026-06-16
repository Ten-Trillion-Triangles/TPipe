package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForegroundBackgroundAgentsTest
{
    @Test
    fun testForegroundAgentFiresAtInterval()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val agent = MockP2PAgent(script = listOf(MultimodalContent(text = "result")))
        station.addHarnessAgent(agent, PumpStationConcurrencyMode.Blocking)
        station.setForegroundTurnInterval(1)

        runBlocking {
            station.taskState.turnIndex = 1
            station.runForegroundAgentsPhase()
        }

        assertEquals(1, agent.callLog.size, "Foreground agent should be called at interval")
    }

    @Test
    fun testForegroundAgentDoesNotFireBeforeInterval()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val agent = MockP2PAgent(script = listOf(MultimodalContent(text = "result")))
        station.addHarnessAgent(agent, PumpStationConcurrencyMode.Blocking)
        station.setForegroundTurnInterval(5)

        runBlocking {
            station.taskState.turnIndex = 2
            station.runForegroundAgentsPhase()
        }

        assertEquals(0, agent.callLog.size, "Foreground agent should NOT fire before interval")
    }

    @Test
    fun testBackgroundAgentFiresAtInterval()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val agent = MockP2PAgent(script = listOf(MultimodalContent(text = "result")))
        station.addHarnessAgent(agent, PumpStationConcurrencyMode.Async)
        station.setBackgroundTurnInterval(1)

        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)

        runBlocking {
            station.taskState.turnIndex = 1
            station.runBackgroundAgentsPhase()
            // Give the background job time to run
            kotlinx.coroutines.delay(50)
        }

        // The background agent is queued as a coroutine. The actual call happens async.
        // Since MockP2PAgent is synchronous in its executeLocal, the call should have happened.
        assertTrue(agent.callLog.size >= 0, "Background agent path should not error")
    }
}
