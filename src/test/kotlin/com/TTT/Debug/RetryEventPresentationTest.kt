package com.TTT.Debug

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for how a retry is presented in the trace.
 *
 * GAP (observed 2026-10-01): `PIPE_RETRY` had no case in [EventPriorityMapper], so it fell
 * through to the `else -> STANDARD` default. Pipes do emit `PIPE_RETRY` —
 * [com.TTT.Pipe.PipeTimeoutManager.handleStallSignal] and the transport-retry paths both
 * trace it — but two problems follow from the missing case:
 *
 *   - a retry is only visible from `NORMAL` detail upward, so a `MINIMAL` capture silently
 *     drops the retry steps;
 *   - the viewers classify red strictly by event type (`PIPE_FAILURE`, `API_CALL_FAILURE`,
 *     `VALIDATION_FAILURE`), so a retry renders as a neutral info row even though it is the
 *     first symptom of a pipe that is failing repeatedly.
 *
 * This is why a run that exhausts its retry budget can present with no red rows describing
 * the retries: the events are either filtered out or drawn as info.
 *
 * These tests pin the contract: a retry is a prominent, failure-class event.
 */
class RetryEventPresentationTest
{
    @Test
    fun `PIPE_RETRY is visible at MINIMAL detail level`()
    {
        assertEquals(
            TraceEventPriority.CRITICAL,
            EventPriorityMapper.getPriority(TraceEventType.PIPE_RETRY),
            "PIPE_RETRY must be CRITICAL so a minimal capture still shows pipe retries; it " +
                "currently falls through to the STANDARD default and is dropped below NORMAL"
        )
        assertTrue(
            EventPriorityMapper.shouldTrace(TraceEventType.PIPE_RETRY, TraceDetailLevel.MINIMAL),
            "a retry is a failing pipe's first symptom and must survive MINIMAL filtering"
        )
    }

    @Test
    fun `PIPE_RETRY renders as a failure in the HTML report`()
    {
        val retry = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "retry-presentation-pipe",
            pipeName = "RetryPresentationPipe",
            eventType = TraceEventType.PIPE_RETRY,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("attempt" to 2, "reason" to "validator-terminal")
        )

        val html = TraceVisualizer().generateHtmlReport(listOf(retry))

        assertTrue(
            html.contains("❌ FAILURE"),
            "a PIPE_RETRY row must be classified as a failure so the retry steps read as " +
                "errors rather than neutral info"
        )
    }

    @Test
    fun `PIPE_RETRY marks its node red in the flow graph`()
    {
        val start = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "retry-graph-pipe",
            pipeName = "RetryGraphPipe",
            eventType = TraceEventType.PIPE_START,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null
        )
        val retry = TraceEvent(
            timestamp = System.currentTimeMillis() + 1,
            pipeId = "retry-graph-pipe",
            pipeName = "RetryGraphPipe",
            eventType = TraceEventType.PIPE_RETRY,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("attempt" to 1, "reason" to "stallTimeout")
        )

        val graph = TraceVisualizer().generateHtmlReport(listOf(start, retry))

        assertTrue(
            graph.contains(":::failure"),
            "the flow graph must colour a retrying pipe red; the graph classifies red strictly by " +
                "event type and PIPE_RETRY was not in that set, so retries drew as neutral info"
        )
    }
}
