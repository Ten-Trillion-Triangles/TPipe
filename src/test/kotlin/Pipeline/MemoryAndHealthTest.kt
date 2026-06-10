package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class MemoryAndHealthTest
{
    @Test
    fun testHealthCheckFiresWhenIntervalReached()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        station.setHealthAgent(MockP2PAgent(script = listOf(MultimodalContent(text = "{}"))))
        station.setHealthAgentTurnInterval(2)
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)

        runBlocking {
            station.taskState.turnIndex = 2
            station.runHealthCheckPhase()
        }

        assertTrue(events.any { it is HealthCheckStarted }, "HealthCheckStarted should be emitted when interval reached")
    }

    @Test
    fun testMemoryUpdateEmitsStartedAndCompletedEvents()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val summary = MockP2PAgent(script = listOf(MultimodalContent(text = "summary")))
        station.setSummaryAgent(summary)
        station.setBackgroundTurnInterval(1)

        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)

        runBlocking {
            station.runMemoryUpdatePhase()
        }

        assertTrue(events.any { it is MemoryUpdateStarted }, "MemoryUpdateStarted should be emitted")
        assertTrue(events.any { it is MemoryUpdateCompleted }, "MemoryUpdateCompleted should be emitted")
    }

    @Test
    fun testCompactionPhaseSkippedWhenBelowThreshold()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        station.setCompactionThreshold(0.99)  // very high; nothing triggers

        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)

        runBlocking {
            station.runCompactionPhase()
        }

        // No CompactionStarted event expected since ratio < threshold
        assertTrue(events.none { it is CompactionStarted }, "CompactionStarted should NOT be emitted below threshold")
    }
}
