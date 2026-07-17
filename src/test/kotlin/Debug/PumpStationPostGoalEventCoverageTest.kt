package com.TTT.Debug

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Verifies that the post-success intervention event
 * [TraceEventType.PUMP_STATION_POST_GOAL_COMPLETED] is wired into every visualizer
 * surface that already tracks [TraceEventType.PUMP_STATION_GOAL_VALIDATION_COMPLETED].
 *
 * The test asserts presence of the new event in every mapping the visualizer exposes
 * (priority, node-map cluster key, HTML report by-name, metadata-driven renderer).
 *
 * Each test pins ONE surface so a future regression is attributed to the specific
 * site that broke (per the "tier-table per-bucket test-failure triage" pattern from
 * session memory).
 */
class PumpStationPostGoalEventCoverageTest
{
    private val visualizer = TraceVisualizer()

    // ---- Priority ----

    @Test
    fun postGoalCompletedIsStandardPriority()
    {
        // The event is a completion marker analogous to GOAL_VALIDATION_COMPLETED.
        // STANDARD is the canonical bucket for lifecycle completion events — same
        // as goal-validation-completed — so it shows up at NORMAL and above but
        // not at MINIMAL.
        assertEquals(
            TraceEventPriority.STANDARD,
            EventPriorityMapper.getPriority(TraceEventType.PUMP_STATION_POST_GOAL_COMPLETED),
            "PUMP_STATION_POST_GOAL_COMPLETED should be STANDARD"
        )
    }

    @Test
    fun postGoalCompletedShowsAtNormalAndAbove()
    {
        assertFalse(
            EventPriorityMapper.shouldTrace(TraceEventType.PUMP_STATION_POST_GOAL_COMPLETED, TraceDetailLevel.MINIMAL),
            "MINIMAL should NOT show STANDARD-priority events"
        )
        assertTrue(
            EventPriorityMapper.shouldTrace(TraceEventType.PUMP_STATION_POST_GOAL_COMPLETED, TraceDetailLevel.NORMAL),
            "NORMAL should show STANDARD events"
        )
        assertTrue(
            EventPriorityMapper.shouldTrace(TraceEventType.PUMP_STATION_POST_GOAL_COMPLETED, TraceDetailLevel.VERBOSE),
            "VERBOSE should show STANDARD events"
        )
        assertTrue(
            EventPriorityMapper.shouldTrace(TraceEventType.PUMP_STATION_POST_GOAL_COMPLETED, TraceDetailLevel.DEBUG),
            "DEBUG should show STANDARD events"
        )
    }

    // ---- Node-map clustering ----

    @Test
    fun postGoalCompletedClustersWithGoalSubloop()
    {
        val event = createEvent(
            turnIndex = 5,
            eventType = TraceEventType.PUMP_STATION_POST_GOAL_COMPLETED
        )
        val key = TraceNodeMapper.resolveNodeKey(event)
        assertTrue(
            key.contains("GOAL_SUBLOOP"),
            "post-goal event should cluster under GOAL_SUBLOOP (visually adjacent to goal events); got: $key"
        )
        assertTrue(
            key.startsWith("TURN_5-"),
            "node key should anchor on turnIndex; got: $key"
        )
    }

    @Test
    fun postGoalCompletedAndGoalValidationCompletedShareClusterPrefix()
    {
        // Both events must land under the same TURN_X-GOAL_SUBLOOP-* prefix so the
        // visualizer's funnel renders them adjacent in the goal-validation subgraph.
        val goalCompleted = createEvent(
            turnIndex = 3,
            eventType = TraceEventType.PUMP_STATION_GOAL_VALIDATION_COMPLETED
        )
        val postGoal = createEvent(
            turnIndex = 3,
            eventType = TraceEventType.PUMP_STATION_POST_GOAL_COMPLETED
        )
        val goalKey = TraceNodeMapper.resolveNodeKey(goalCompleted)
        val postGoalKey = TraceNodeMapper.resolveNodeKey(postGoal)
        val goalPrefix = goalKey.substringBeforeLast("-")
        val postGoalPrefix = postGoalKey.substringBeforeLast("-")
        assertEquals(
            goalPrefix, postGoalPrefix,
            "goal and post-goal events on the same turn should share a cluster prefix; " +
                "got '$goalPrefix' vs '$postGoalPrefix'"
        )
    }

    // ---- Visualizer HTML rendering ----
    //
    // We pin the visualizer output for an event stream containing the new event.
    // The pump station HTML report embeds event metadata through the
    // generateHtmlReport path; verifying the event name and metadata fields appear
    // ensures the visualizer doesn't drop the event on the floor (silent fallback
    // to a generic label).

    @Test
    fun postGoalCompletedAppearsInPumpStationHtmlReport()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED),
            createEvent(0, TraceEventType.PUMP_STATION_POST_GOAL_COMPLETED,
                metadata = mapOf("passed" to true, "transformedContent" to true))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(
            html.contains("PUMP_STATION_POST_GOAL_COMPLETED"),
            "pump station HTML report must include the new event type by name; " +
                "report length: ${html.length}"
        )
    }

    @Test
    fun postGoalCompletedMetadataRenderedInTurnDetail()
    {
        // The pump-station turn-detail renderer surfaces each metadata field as a
        // labeled row: <span class='ps-meta-key'>passed:</span><span class='ps-meta-val'>true</span>.
        // Verify BOTH the post-goal-specific 'transformedContent' label appears (proving the
        // new event reaches the pump-station-specific renderer) and the 'passed' value
        // round-trips through the metadata-driven renderer.
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED),
            createEvent(0, TraceEventType.PUMP_STATION_POST_GOAL_COMPLETED,
                metadata = mapOf("passed" to true, "transformedContent" to false))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(
            html.contains("transformedContent") && html.contains("ps-meta-val'>false"),
            "turn-detail renderer must surface transformedContent=false for post-goal event"
        )
        assertTrue(
            html.contains("PUMP_STATION_POST_GOAL_COMPLETED") &&
                html.contains("passed:") && html.contains("ps-meta-val'>true"),
            "turn-detail renderer must surface passed=true for post-goal event"
        )
    }

    // ---- Helper (mirrors PumpStationTraceVisualizationTest.createEvent) ----

    private fun createEvent(
        turnIndex: Int,
        eventType: TraceEventType,
        metadata: Map<String, Any> = emptyMap()
    ): TraceEvent
    {
        val baseMeta = mutableMapOf<String, Any>("turnIndex" to turnIndex, "runId" to "ps-test")
        baseMeta.putAll(metadata)
        return TraceEvent(
            timestamp = System.currentTimeMillis() + turnIndex * 1000L,
            pipeId = "ps-test",
            pipeName = "PumpStation",
            eventType = eventType,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null,
            metadata = baseMeta
        )
    }
}
