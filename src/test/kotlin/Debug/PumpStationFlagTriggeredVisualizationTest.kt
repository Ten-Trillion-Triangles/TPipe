package com.TTT.Debug

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for the [com.TTT.Debug.TraceEventType.PUMP_STATION_JUDGE_SKIPPED] event in the
 * PumpStation trace visualizer. The event is emitted by the harness in
 * [com.TTT.Pipeline.PumpStationJudgeRunMode.FlagTriggered] mode whenever the judge phase
 * short-circuits because [com.TTT.Pipeline.PumpStationTaskState.requestJudgeNextTurn] is
 * false. The visualizer renders it as a thin "judge skipped" pill in the turn-timeline
 * phase ribbon.
 */
class PumpStationFlagTriggeredVisualizationTest
{
    private val visualizer = TraceVisualizer()

    // U+2298 - the unicode character used in the visualizer's short label for JudgeSkipped.
    private val judgeSkippedPill = "Judge\u2298"

    @Test
    fun reportRendersJudgeSkippedAsPhasePillInFlagTriggeredMode()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_JUDGE_SKIPPED,
                mapOf("runId" to "ps-test", "reason" to "no_flag_set", "judgeRunMode" to "FlagTriggered")),
            createEvent(0, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED,
                mapOf("runId" to "ps-test", "selectedPathName" to "p1")),
            createEvent(0, TraceEventType.PUMP_STATION_PATH_COMPLETED,
                mapOf("runId" to "ps-test", "pathName" to "p1", "riskLevel" to "LOW"))
        )
        val html = visualizer.generateHtmlReport(trace)
        // The phase ribbon should include the JudgeSkipped pill.
        assertTrue(html.contains("ps-phase-pill"),
            "Phase ribbon should still render even when the judge is skipped")
        assertTrue(html.contains(judgeSkippedPill),
            "JudgeSkipped should render as a '$judgeSkippedPill' pill in the phase ribbon")
    }

    @Test
    fun reportKeyFactsDoNotShowJudgeVerdictWhenJudgeWasSkipped()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_JUDGE_SKIPPED,
                mapOf("runId" to "ps-test", "reason" to "no_flag_set", "judgeRunMode" to "FlagTriggered"))
        )
        val html = visualizer.generateHtmlReport(trace)
        // When the judge was skipped, the key-facts line should NOT advertise a Judge
        // verdict (no isComplete / shouldTerminate value).
        assertFalse(html.contains("isComplete="),
            "Key facts must not advertise a judge verdict when the judge was skipped")
    }

    @Test
    fun reportInterleavesJudgeSkippedAcrossTurns()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_JUDGE_SKIPPED,
                mapOf("runId" to "ps-test", "reason" to "no_flag_set", "judgeRunMode" to "FlagTriggered")),
            createEvent(0, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED,
                mapOf("runId" to "ps-test", "selectedPathName" to "p1")),
            createEvent(0, TraceEventType.PUMP_STATION_PATH_COMPLETED,
                mapOf("runId" to "ps-test", "pathName" to "p1", "riskLevel" to "LOW")),
            createEvent(1, TraceEventType.PUMP_STATION_JUDGE_SKIPPED,
                mapOf("runId" to "ps-test", "reason" to "no_flag_set", "judgeRunMode" to "FlagTriggered")),
            createEvent(1, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED,
                mapOf("runId" to "ps-test", "selectedPathName" to "p1")),
            createEvent(1, TraceEventType.PUMP_STATION_PATH_COMPLETED,
                mapOf("runId" to "ps-test", "pathName" to "p1", "riskLevel" to "LOW"))
        )
        val html = visualizer.generateHtmlReport(trace)
        // Both turn cards should mention the JudgeSkipped pill at least once.
        val occurrences = html.split(judgeSkippedPill).size - 1
        assertTrue(occurrences >= 2,
            "Both turn cards should render a JudgeSkipped pill (got $occurrences)")
    }

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
