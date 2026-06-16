package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
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

    @Test
    fun testCompactionEmitsInflatedEventWhenOutputExceedsInput()
    {
        // Summary agent returns a much larger string than the input. The orchestrator
        // should emit a CompactionInflated event before retrying or handing off.
        val station = PumpStation().setDispatchAgent(Pipeline())
        repeat(10) { station.turnHistory.add(com.TTT.Context.ConverseData(role = com.TTT.Context.ConverseRole.user, content = com.TTT.Pipe.MultimodalContent(text = "a".repeat(40)))) }
        station.setSummaryAgent(MockP2PAgent(script = listOf(com.TTT.Pipe.MultimodalContent(text = "x".repeat(2000)))))
        station.setCompactionThreshold(0.0)
        station.setMaxCompactionAttempts(1)

        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)
        runBlocking { station.runCompactionPhase() }

        val inflated = events.filterIsInstance<CompactionInflated>()
        assertTrue(inflated.isNotEmpty(), "expected CompactionInflated event, got $events")
        assertEquals(false, inflated.first().willRetry)
    }

    @Test
    fun testCompactionEmitsHandedOffEventOnFinalFailure()
    {
        // After the retry budget is exhausted, the orchestrator hands off to truncation
        // and emits a CompactionHandedOffToTruncation event.
        val station = PumpStation().setDispatchAgent(Pipeline())
        repeat(10) { station.turnHistory.add(com.TTT.Context.ConverseData(role = com.TTT.Context.ConverseRole.user, content = com.TTT.Pipe.MultimodalContent(text = "a".repeat(40)))) }
        station.setSummaryAgent(MockP2PAgent(script = listOf(com.TTT.Pipe.MultimodalContent(text = "x".repeat(2000)))))
        station.setCompactionThreshold(0.0)
        station.setMaxCompactionAttempts(1)

        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)
        runBlocking { station.runCompactionPhase() }

        assertTrue(events.any { it is CompactionHandedOffToTruncation }, "expected CompactionHandedOffToTruncation event")
    }
}

/**
 * Regression test for the v3 CompactionInflated isFailure fix.
 *
 * Before the fix: `runFinalizationPhase` did not include `CompactionInflated` in its
 * `isFailure` list, so a harness that completed a task but tripped `handOffToTruncation`
 * mid-loop ended with `status=Completed` AND `lastError=CompactionInflated`. The TaskState
 * signaled failure while the harness signaled success — a confusing state.
 *
 * After the fix: `taskState.status = PumpStationStatus.Failed` and
 * `taskState.lastError = PumpStationError.CompactionInflated` are consistent.
 */
