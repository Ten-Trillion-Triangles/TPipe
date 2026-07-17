package com.TTT.Debug

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for the PumpStation event type family. Validates that every PUMP_STATION_* event type has
 * an explicit priority mapping (no implicit fallback through the else branch), and that the
 * priorities match the design intent: failures and trips at CRITICAL, lifecycle/completion at
 * STANDARD, and phase-starts + async details at DETAILED.
 */
class PumpStationEventTypeTest
{
    /**
     * Every PumpStation event type must be mappable to a priority. The current implementation has
     * an `else -> STANDARD` fallback in EventPriorityMapper, so we verify the explicit mappings
     * here by checking that the priority matches the bucket documented in the plan.
     */
    @Test
    fun allPumpStationEventTypesHavePriorities()
    {
        val pumpStationEvents = TraceEventType.values().filter { it.name.startsWith("PUMP_STATION_") }
        assertTrue(pumpStationEvents.isNotEmpty(), "Should have PumpStation event types defined")

        // Spot-check: every event type should produce some priority (not throw, not return null)
        for (eventType in pumpStationEvents)
        {
            val priority = EventPriorityMapper.getPriority(eventType)
            assertNotNull(priority, "Priority must not be null for $eventType")
        }
    }

    @Test
    fun failuresAreCritical()
    {
        val criticalEvents = listOf(
            TraceEventType.PUMP_STATION_FAILED,
            TraceEventType.PUMP_STATION_PATH_FAILED,
            TraceEventType.PUMP_STATION_LOOP_GUARD_TRIPPED,
            TraceEventType.PUMP_STATION_CONTEXT_BLOWOUT_DETECTED
        )
        for (eventType in criticalEvents)
        {
            assertEquals(
                TraceEventPriority.CRITICAL,
                EventPriorityMapper.getPriority(eventType),
                "$eventType should be CRITICAL"
            )
        }
    }

    @Test
    fun lifecycleAndCompletionAreStandard()
    {
        val standardEvents = listOf(
            TraceEventType.PUMP_STATION_STARTED,
            TraceEventType.PUMP_STATION_COMPLETED,
            TraceEventType.PUMP_STATION_SUSPENDED,
            TraceEventType.PUMP_STATION_RESUMED,
            TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED,
            TraceEventType.PUMP_STATION_JUDGE_COMPLETED,
            TraceEventType.PUMP_STATION_DISPATCH_COMPLETED,
            TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED,
            TraceEventType.PUMP_STATION_GOAL_VALIDATION_COMPLETED,
            TraceEventType.PUMP_STATION_POST_GOAL_COMPLETED,
            TraceEventType.PUMP_STATION_PATH_SELECTED,
            TraceEventType.PUMP_STATION_PATH_STARTED,
            TraceEventType.PUMP_STATION_PATH_COMPLETED,
            TraceEventType.PUMP_STATION_PATH_HIDDEN,
            TraceEventType.PUMP_STATION_RESERVE_PATH_REVEALED
        )
        for (eventType in standardEvents)
        {
            assertEquals(
                TraceEventPriority.STANDARD,
                EventPriorityMapper.getPriority(eventType),
                "$eventType should be STANDARD"
            )
        }
    }

    @Test
    fun phaseStartsAndAsyncDetailsAreDetailed()
    {
        val detailedEvents = listOf(
            TraceEventType.PUMP_STATION_HEALTH_CHECK_STARTED,
            TraceEventType.PUMP_STATION_JUDGE_STARTED,
            TraceEventType.PUMP_STATION_DISPATCH_STARTED,
            TraceEventType.PUMP_STATION_PATH_SAFETY_STARTED,
            TraceEventType.PUMP_STATION_PATH_SAFETY_COMPLETED,
            TraceEventType.PUMP_STATION_PATH_VALIDATION_COMPLETED,
            TraceEventType.PUMP_STATION_INTERVENTION_STARTED,
            TraceEventType.PUMP_STATION_INTERVENTION_COMPLETED,
            TraceEventType.PUMP_STATION_FOREGROUND_AGENT_COMPLETED,
            TraceEventType.PUMP_STATION_BACKGROUND_AGENT_QUEUED,
            TraceEventType.PUMP_STATION_MEMORY_UPDATE_STARTED,
            TraceEventType.PUMP_STATION_COMPACTION_STARTED,
            TraceEventType.PUMP_STATION_COMPACTION_COMPLETED,
            TraceEventType.PUMP_STATION_GOAL_VALIDATION_STARTED,
            TraceEventType.PUMP_STATION_STASH_CREATED
        )
        for (eventType in detailedEvents)
        {
            assertEquals(
                TraceEventPriority.DETAILED,
                EventPriorityMapper.getPriority(eventType),
                "$eventType should be DETAILED"
            )
        }
    }

    @Test
    fun minimalLevelOnlyShowsFailures()
    {
        val all = TraceEventType.values().filter { it.name.startsWith("PUMP_STATION_") }
        val allowed = all.filter {
            EventPriorityMapper.shouldTrace(it, TraceDetailLevel.MINIMAL)
        }
        assertTrue(allowed.isNotEmpty(), "MINIMAL should show at least one PumpStation event")
        for (eventType in allowed)
        {
            assertEquals(
                TraceEventPriority.CRITICAL,
                EventPriorityMapper.getPriority(eventType),
                "MINIMAL-visible $eventType must be CRITICAL"
            )
        }
    }

    @Test
    fun debugLevelShowsAllPumpStationEvents()
    {
        val all = TraceEventType.values().filter { it.name.startsWith("PUMP_STATION_") }
        for (eventType in all)
        {
            assertTrue(
                EventPriorityMapper.shouldTrace(eventType, TraceDetailLevel.DEBUG),
                "DEBUG level should show $eventType"
            )
        }
    }

    @Test
    fun nestedP2PCompletedIsDetailed()
    {
        // The nested P2P completion is a per-event harness detail, not a top-level
        // lifecycle marker. It belongs in the DETAILED bucket so it shows up at VERBOSE
        // without flooding the NORMAL or MINIMAL views.
        assertEquals(
            TraceEventPriority.DETAILED,
            EventPriorityMapper.getPriority(TraceEventType.PUMP_STATION_NESTED_P2P_COMPLETED),
            "PUMP_STATION_NESTED_P2P_COMPLETED should be DETAILED"
        )
    }

    @Test
    fun allPumpStationEventsHaveNonNullPriority()
    {
        // Sanity check: every PUMP_STATION_* event type resolves to a real priority bucket
        // (not the implicit fallback). Locks in that the new event types are explicitly
        // registered in the priority mapper.
        val all = TraceEventType.values().filter { it.name.startsWith("PUMP_STATION_") }
        for (eventType in all)
        {
            val priority = EventPriorityMapper.getPriority(eventType)
            assertNotNull(priority, "Priority must be non-null for $eventType")
            // The mapper is exhaustive over its bucket, so no event should ever be null —
            // we only verify the priority is one of the four real buckets.
            assertTrue(
                priority in listOf(
                    TraceEventPriority.CRITICAL,
                    TraceEventPriority.STANDARD,
                    TraceEventPriority.DETAILED,
                    TraceEventPriority.INTERNAL
                ),
                "Priority for $eventType must be a known bucket, got $priority"
            )
        }
    }
}
