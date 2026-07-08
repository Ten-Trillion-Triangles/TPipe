package com.TTT.Debug

import com.TTT.Config.TPipeConfig
import com.TTT.Pipe.MultimodalContent
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for the Splitter HTML report dispatcher + the trace-wide token totals card.
 *
 * Anchors on the rendered `TOKEN TOTALS` label rather than a per-container CSS class —
 * see ManifoldTraceVisualizationTest header for the rationale.
 */
class SplitterTraceVisualizationTest
{
    /**
     * Aggregate token totals should appear at the top of the Splitter HTML report as a
     * dedicated card. Aggregates inputTokens and outputTokens from every event that
     * carries them in metadata, SKIPPING KILLSWITCH_CHECK (cumulative-AT-check-time,
     * not actual spend — see Splitter.kt checkKillSwitch()).
     */
    @Test
    fun reportShowsTokenTotalsHeaderCard()
    {
        val baseTime = System.currentTimeMillis()
        val visualizer = TraceVisualizer()
        val trace = listOf(
            TraceEvent(
                timestamp = baseTime,
                pipeId = "splitter-001",
                pipeName = "Splitter-Dispatcher",
                eventType = TraceEventType.SPLITTER_START,
                phase = TracePhase.ORCHESTRATION,
                content = MultimodalContent("Broadcast"),
                contextSnapshot = null,
                metadata = mapOf("branchCount" to 2)
            ),
            TraceEvent(
                timestamp = baseTime + 100,
                pipeId = "splitter-001",
                pipeName = "Splitter-Dispatcher",
                eventType = TraceEventType.SPLITTER_CONTENT_DISTRIBUTION,
                phase = TracePhase.EXECUTION,
                content = null,
                contextSnapshot = null,
                metadata = mapOf("branch" to "worker-A", "inputTokens" to 800, "outputTokens" to 200, "totalTokens" to 1000)
            ),
            TraceEvent(
                timestamp = baseTime + 200,
                pipeId = "splitter-001",
                pipeName = "Splitter-Dispatcher",
                eventType = TraceEventType.SPLITTER_PIPELINE_COMPLETION,
                phase = TracePhase.AGENT_COMMUNICATION,
                content = MultimodalContent("worker-A response"),
                contextSnapshot = null,
                metadata = mapOf("branch" to "worker-A", "inputTokens" to 1200, "outputTokens" to 400, "totalTokens" to 1600)
            ),
            TraceEvent(
                timestamp = baseTime + 300,
                pipeId = "splitter-001",
                pipeName = "Splitter-Dispatcher",
                eventType = TraceEventType.SPLITTER_PIPELINE_COMPLETION,
                phase = TracePhase.AGENT_COMMUNICATION,
                content = MultimodalContent("worker-B response"),
                contextSnapshot = null,
                metadata = mapOf("branch" to "worker-B", "inputTokens" to 2000, "outputTokens" to 600, "totalTokens" to 2600)
            ),
            // KILLSWITCH_CHECK must be EXCLUDED from totals (cumulative-AT-check, not spend)
            TraceEvent(
                timestamp = baseTime + 400,
                pipeId = "splitter-001",
                pipeName = "Splitter-Dispatcher",
                eventType = TraceEventType.KILLSWITCH_CHECK,
                phase = TracePhase.MONITORING,
                content = null,
                contextSnapshot = null,
                metadata = mapOf("inputTokens" to 7777, "outputTokens" to 7777,
                                "inputLimit" to "none", "outputLimit" to "none",
                                "elapsedMs" to 1L)
            ),
            TraceEvent(
                timestamp = baseTime + 500,
                pipeId = "splitter-001",
                pipeName = "Splitter-Dispatcher",
                eventType = TraceEventType.SPLITTER_SUCCESS,
                phase = TracePhase.CLEANUP,
                content = MultimodalContent("All branches collected"),
                contextSnapshot = null,
                metadata = mapOf("branches" to 2)
            )
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("TOKEN TOTALS"),
            "Splitter report must include the rendered TOKEN TOTALS card")
        assertTrue(html.contains("Input: 4,000"),
            "Input total = 800 (distribution) + 1200 (worker-A) + 2000 (worker-B) = 4,000; KillSwitch 7777 must be skipped")
        assertTrue(html.contains("Output: 1,200"),
            "Output total = 200 + 400 + 600 = 1,200; KillSwitch 7777 must be skipped")
        assertTrue(html.contains("Total: 5,200"),
            "Sum total = 4,000 + 1,200 = 5,200")
    }

    /**
     * Card hidden when no event carries token metadata.
     */
    @Test
    fun reportHidesTokenCardWhenNoTokenMetadata()
    {
        val baseTime = System.currentTimeMillis()
        val visualizer = TraceVisualizer()
        val trace = listOf(
            TraceEvent(
                timestamp = baseTime,
                pipeId = "splitter-001",
                pipeName = "Splitter-Dispatcher",
                eventType = TraceEventType.SPLITTER_START,
                phase = TracePhase.ORCHESTRATION,
                content = MultimodalContent("Broadcast"),
                contextSnapshot = null,
                metadata = mapOf("branchCount" to 2)
            ),
            TraceEvent(
                timestamp = baseTime + 500,
                pipeId = "splitter-001",
                pipeName = "Splitter-Dispatcher",
                eventType = TraceEventType.SPLITTER_SUCCESS,
                phase = TracePhase.CLEANUP,
                content = MultimodalContent("Done"),
                contextSnapshot = null,
                metadata = mapOf("branches" to 2)
            )
        )
        val html = visualizer.generateHtmlReport(trace)
        assertFalse(html.contains("TOKEN TOTALS"),
            "Splitter report must hide the token card when zero events carry token metadata")
    }

    /**
     * Sanity: the dispatcher routes a non-empty SPLITTER_*-only trace to the Splitter
     * renderer.
     */
    @Test
    fun splitterTraceDispatchesToSplitterRenderer()
    {
        val baseTime = System.currentTimeMillis()
        val visualizer = TraceVisualizer()
        val trace = listOf(
            TraceEvent(
                timestamp = baseTime,
                pipeId = "splitter-001",
                pipeName = "Splitter-Dispatcher",
                eventType = TraceEventType.SPLITTER_START,
                phase = TracePhase.ORCHESTRATION,
                content = MultimodalContent("Broadcast"),
                contextSnapshot = null,
                metadata = mapOf("branchCount" to 2)
            ),
            TraceEvent(
                timestamp = baseTime + 500,
                pipeId = "splitter-001",
                pipeName = "Splitter-Dispatcher",
                eventType = TraceEventType.SPLITTER_SUCCESS,
                phase = TracePhase.CLEANUP,
                content = MultimodalContent("Done"),
                contextSnapshot = null,
                metadata = mapOf("branches" to 2)
            )
        )
        val html = visualizer.generateHtmlReport(trace)
        val traceDir = File(TPipeConfig.getTraceDir(), "Library/splitter-trace-visualization")
        if (!traceDir.exists()) traceDir.mkdirs()
        File(traceDir, "splitter.html").writeText(html)
        // Splitter uses generic CSS; assert on the dispatcher's content shape (no namespace prefix).
        assertTrue(html.contains("Splitter"),
            "SPLITTER_* trace should dispatch to the Splitter renderer")
    }
}
