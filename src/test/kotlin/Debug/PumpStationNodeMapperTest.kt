package com.TTT.Debug

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for the PumpStation-specific node resolution in TraceNodeMapper. Verifies that events
 * are keyed by turn (so the visualizer can group them into turn cards) and that goal validation
 * events get a nested sub-loop key.
 */
class PumpStationNodeMapperTest
{
    @Test
    fun pumpStationEventsAreTurnKeyed()
    {
        val event = createPumpStationEvent(
            turnIndex = 3,
            eventType = TraceEventType.PUMP_STATION_JUDGE_COMPLETED
        )
        val key = TraceNodeMapper.resolveNodeKey(event)
        assertTrue(key.startsWith("TURN_3-"), "Expected TURN_3- prefix but got: $key")
        assertTrue(key.contains("PUMP_STATION_JUDGE_COMPLETED"), "Key should contain event type name: $key")
    }

    @Test
    fun goalValidationEventsGetSubLoopKey()
    {
        val started = createPumpStationEvent(
            turnIndex = 2,
            eventType = TraceEventType.PUMP_STATION_GOAL_VALIDATION_STARTED
        )
        val completed = createPumpStationEvent(
            turnIndex = 2,
            eventType = TraceEventType.PUMP_STATION_GOAL_VALIDATION_COMPLETED
        )
        assertTrue(TraceNodeMapper.resolveNodeKey(started).contains("GOAL_SUBLOOP"))
        assertTrue(TraceNodeMapper.resolveNodeKey(completed).contains("GOAL_SUBLOOP"))
    }

    @Test
    fun reserveRevealEventsClusterByPathName()
    {
        val reveal = createPumpStationEvent(
            turnIndex = 4,
            eventType = TraceEventType.PUMP_STATION_RESERVE_PATH_REVEALED,
            metadata = mapOf("pathName" to "debug-tool")
        )
        val key = TraceNodeMapper.resolveNodeKey(reveal)
        assertTrue(key.startsWith("RESERVE_REVEAL-debug-tool-"), "Expected RESERVE_REVEAL-debug-tool- but got: $key")
    }

    @Test
    fun eventsInSameTurnGroupTogether()
    {
        val events = listOf(
            createPumpStationEvent(0, TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED),
            createPumpStationEvent(0, TraceEventType.PUMP_STATION_JUDGE_COMPLETED),
            createPumpStationEvent(0, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED),
            createPumpStationEvent(0, TraceEventType.PUMP_STATION_PATH_COMPLETED)
        )
        val nodes = TraceNodeMapper.mapEventsToNodes(events)
        // 4 distinct event types → 4 distinct nodes (each gets its own TURN_0-EVENT key)
        assertEquals(4, nodes.size)
        assertTrue(nodes.all { it.pipeName.startsWith("TURN_0-") })
    }

    @Test
    fun eventsAcrossTurnsDoNotGroupTogether()
    {
        val events = listOf(
            createPumpStationEvent(0, TraceEventType.PUMP_STATION_JUDGE_COMPLETED),
            createPumpStationEvent(1, TraceEventType.PUMP_STATION_JUDGE_COMPLETED)
        )
        val nodes = TraceNodeMapper.mapEventsToNodes(events)
        // Two separate turns → two separate nodes
        assertEquals(2, nodes.size)
        assertEquals(1, nodes.count { it.pipeName.startsWith("TURN_0-") })
        assertEquals(1, nodes.count { it.pipeName.startsWith("TURN_1-") })
    }

    private fun createPumpStationEvent(
        turnIndex: Int,
        eventType: TraceEventType,
        metadata: Map<String, Any> = emptyMap()
    ): TraceEvent
    {
        val baseMeta = mutableMapOf<String, Any>("turnIndex" to turnIndex, "runId" to "ps-test-1234")
        baseMeta.putAll(metadata)
        return TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "ps-test-1234",
            pipeName = "PumpStation",
            eventType = eventType,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null,
            metadata = baseMeta
        )
    }
}
