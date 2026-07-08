package com.TTT.Debug

import com.TTT.Config.TPipeConfig
import com.TTT.Pipe.MultimodalContent
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for the Manifold HTML report dispatcher + the trace-wide token totals card.
 *
 * Mirrors the PumpStation pattern from PumpStationTraceVisualizationTest: synthetic trace,
 * render through [TraceVisualizer.generateHtmlReport], assert on rendered output.
 *
 * Anchors on the rendered `TOKEN TOTALS` label rather than a per-container CSS class —
 * the four non-PumpStation containers (Manifold, Junction, Splitter, DistributionGrid) reuse
 * generic CSS class names (no per-container namespace), so a single shared label keeps the
 * tests container-agnostic and stable across CSS tweaks.
 */
class ManifoldTraceVisualizationTest
{
    /**
     * Aggregate token totals should appear at the top of the Manifold HTML report as a
     * dedicated card. Aggregates inputTokens and outputTokens from every event that
     * carries them in metadata, SKIPPING KILLSWITCH_CHECK (cumulative-AT-check-time,
     * not actual spend — see Manifold.kt checkKillSwitch()).
     */
    @Test
    fun reportShowsTokenTotalsHeaderCard()
    {
        val baseTime = System.currentTimeMillis()
        val visualizer = TraceVisualizer()
        val trace = listOf(
            TraceEvent(
                timestamp = baseTime,
                pipeId = "manifold-001",
                pipeName = "Manifold-TaskManager",
                eventType = TraceEventType.MANIFOLD_START,
                phase = TracePhase.ORCHESTRATION,
                content = MultimodalContent("Multi-agent task"),
                contextSnapshot = null,
                metadata = mapOf("workerCount" to 3)
            ),
            TraceEvent(
                timestamp = baseTime + 800,
                pipeId = "manifold-001",
                pipeName = "Manifold-TaskManager",
                eventType = TraceEventType.MANAGER_DECISION,
                phase = TracePhase.ORCHESTRATION,
                content = MultimodalContent("Agent selection"),
                contextSnapshot = null,
                metadata = mapOf("iteration" to 1, "inputTokens" to 500, "outputTokens" to 100, "totalTokens" to 600)
            ),
            TraceEvent(
                timestamp = baseTime + 900,
                pipeId = "manifold-001",
                pipeName = "Manifold-TaskManager",
                eventType = TraceEventType.AGENT_DISPATCH,
                phase = TracePhase.AGENT_COMMUNICATION,
                content = null,
                contextSnapshot = null,
                metadata = mapOf("agentName" to "DataAnalyzer")
            ),
            TraceEvent(
                timestamp = baseTime + 2500,
                pipeId = "manifold-001",
                pipeName = "Manifold-TaskManager",
                eventType = TraceEventType.AGENT_RESPONSE,
                phase = TracePhase.AGENT_COMMUNICATION,
                content = MultimodalContent("Analysis results"),
                contextSnapshot = null,
                metadata = mapOf("agentName" to "DataAnalyzer", "inputTokens" to 1500, "outputTokens" to 400, "totalTokens" to 1900)
            ),
            TraceEvent(
                timestamp = baseTime + 5200,
                pipeId = "manifold-001",
                pipeName = "Manifold-TaskManager",
                eventType = TraceEventType.MANIFOLD_SUCCESS,
                phase = TracePhase.CLEANUP,
                content = MultimodalContent("Task complete"),
                contextSnapshot = null,
                metadata = mapOf("totalIterations" to 2, "inputTokens" to 500, "outputTokens" to 100, "totalTokens" to 600)
            ),
            // KILLSWITCH_CHECK must be EXCLUDED from totals (cumulative-AT-check, not spend)
            TraceEvent(
                timestamp = baseTime + 5300,
                pipeId = "manifold-001",
                pipeName = "Manifold-TaskManager",
                eventType = TraceEventType.KILLSWITCH_CHECK,
                phase = TracePhase.MONITORING,
                content = null,
                contextSnapshot = null,
                metadata = mapOf("inputTokens" to 9999, "outputTokens" to 9999,
                                "inputLimit" to "none", "outputLimit" to "none",
                                "elapsedMs" to 1L)
            )
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("TOKEN TOTALS"),
            "Manifold report must include the rendered TOKEN TOTALS card")
        assertTrue(html.contains("Input: 2,500"),
            "Input total = 500 (MANAGER_DECISION) + 1500 (AGENT_RESPONSE) + 500 (MANIFOLD_SUCCESS) = 2,500; KillSwitch 9999 must be skipped")
        assertTrue(html.contains("Output: 600"),
            "Output total = 100 + 400 + 100 = 600; KillSwitch 9999 must be skipped")
        assertTrue(html.contains("Total: 3,100"),
            "Sum total = 2,500 + 600 = 3,100")
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
                pipeId = "manifold-001",
                pipeName = "Manifold-TaskManager",
                eventType = TraceEventType.MANIFOLD_START,
                phase = TracePhase.ORCHESTRATION,
                content = MultimodalContent("Multi-agent task"),
                contextSnapshot = null,
                metadata = mapOf("workerCount" to 3)
            ),
            TraceEvent(
                timestamp = baseTime + 2500,
                pipeId = "manifold-001",
                pipeName = "Manifold-TaskManager",
                eventType = TraceEventType.AGENT_RESPONSE,
                phase = TracePhase.AGENT_COMMUNICATION,
                content = MultimodalContent("Analysis results"),
                contextSnapshot = null,
                metadata = mapOf("agentName" to "DataAnalyzer")
            ),
            TraceEvent(
                timestamp = baseTime + 5200,
                pipeId = "manifold-001",
                pipeName = "Manifold-TaskManager",
                eventType = TraceEventType.MANIFOLD_SUCCESS,
                phase = TracePhase.CLEANUP,
                content = MultimodalContent("Task complete"),
                contextSnapshot = null,
                metadata = mapOf("totalIterations" to 2)
            )
        )
        val html = visualizer.generateHtmlReport(trace)
        assertFalse(html.contains("TOKEN TOTALS"),
            "Manifold report must hide the token card when zero events carry token metadata")
    }

    /**
     * Sanity: the dispatcher routes a non-empty MANIFOLD_*-only trace to the Manifold
     * renderer. Pins the dispatch contract that the token-card red tests depend on.
     */
    @Test
    fun manifoldTraceDispatchesToManifoldRenderer()
    {
        val baseTime = System.currentTimeMillis()
        val visualizer = TraceVisualizer()
        val trace = listOf(
            TraceEvent(
                timestamp = baseTime,
                pipeId = "manifold-001",
                pipeName = "Manifold-TaskManager",
                eventType = TraceEventType.MANIFOLD_START,
                phase = TracePhase.ORCHESTRATION,
                content = MultimodalContent("Multi-agent task"),
                contextSnapshot = null,
                metadata = mapOf("workerCount" to 2)
            ),
            TraceEvent(
                timestamp = baseTime + 5200,
                pipeId = "manifold-001",
                pipeName = "Manifold-TaskManager",
                eventType = TraceEventType.MANIFOLD_SUCCESS,
                phase = TracePhase.CLEANUP,
                content = MultimodalContent("Task complete"),
                contextSnapshot = null,
                metadata = mapOf("totalIterations" to 1)
            )
        )
        val html = visualizer.generateHtmlReport(trace)
        val traceDir = File(TPipeConfig.getTraceDir(), "Library/manifold-trace-visualization")
        if (!traceDir.exists()) traceDir.mkdirs()
        File(traceDir, "manifold.html").writeText(html)
        assertTrue(html.contains("Manifold Execution Analysis"),
            "MANIFOLD_* trace should dispatch to the Manifold renderer")
    }
}
