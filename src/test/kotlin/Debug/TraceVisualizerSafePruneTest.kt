package com.TTT.Debug

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceVisualizerSafePruneTest
{
    @Test
    fun testSafePruneEventsRenderWithEmojiAndPopup()
    {
        // Build a synthetic trace with one SafePruneApplied and one SafePruneDryRunCompleted.
        // The PumpStation HTML report groups events by turnIndex metadata; without it the
        // timeline section is empty. Use a sentinel turnIndex value to surface both events.
        val trace = listOf(
            TraceEvent(
                timestamp = 1000L,
                pipeId = "test",
                pipeName = "PumpStation",
                eventType = TraceEventType.PUMP_STATION_SAFE_PRUNE_APPLIED,
                phase = TracePhase.CONTEXT_PREPARATION,
                content = null,
                contextSnapshot = null,
                metadata = mapOf(
                    "turnIndex" to 5L,
                    "originalCount" to "100",
                    "finalCount" to "70",
                    "tokensRemoved" to "120",
                    "enabledFlags" to "ReplaceWithSummaryRef,DropPureEchoes"
                )
            ),
            TraceEvent(
                timestamp = 2000L,
                pipeId = "test",
                pipeName = "PumpStation",
                eventType = TraceEventType.PUMP_STATION_SAFE_PRUNE_DRY_RUN_COMPLETED,
                phase = TracePhase.CONTEXT_PREPARATION,
                content = null,
                contextSnapshot = null,
                metadata = mapOf(
                    "turnIndex" to 6L,
                    "originalCount" to "50",
                    "finalCount" to "30",
                    "tokensRemoved" to "60",
                    "enabledFlags" to "DeduplicateByHash"
                )
            )
        )
        val html = TraceVisualizer().generateHtmlReport(trace)
        // 1. Both event types get a popup wrapper span.
        assertTrue(html.contains("ps-phase-wrap"), "Applied event must be wrapped in ps-phase-wrap popup container")
        assertTrue(html.contains("ps-safe-prune-popup"), "Popup CSS class must be present")
        // 2. Popover content shows the report fields.
        assertTrue(html.contains("Original"), "Original field label must be in popup")
        assertTrue(html.contains("Final"), "Final field label must be in popup")
        assertTrue(html.contains("Tokens removed"), "Tokens removed label must be in popup")
        assertTrue(html.contains("Strategies"), "Strategies label must be in popup")
        // 3. Both event types get their distinct titles.
        assertTrue(html.contains("SafePrune Applied Report"), "Applied report title must be in popup")
        assertTrue(html.contains("SafePrune Dry-Run Report"), "DryRun report title must be in popup")
        // 4. The PumpStation timeline pill renders the strategy short label (✂ / 🔍
        // appear in the standard event-card pipeline, not the PumpStation phase ribbon).
        // Verify the report payload values appear literally in the popup content.
        assertTrue(html.contains("100 entries"), "originalCount must be rendered in popup")
        assertTrue(html.contains("70 entries"), "finalCount must be rendered in popup")
        assertTrue(html.contains("120"), "tokensRemoved must be rendered in popup")
        assertTrue(html.contains("ReplaceWithSummaryRef"), "enabledFlags must be rendered in popup")
        // 5. The dry-run emoji 🔍 appears in the trace emoji summary at the top of the
        // PumpStation report. Verify that too.
        assertTrue(html.contains("✂") || html.contains("🔍"), "SafePrune event emoji must appear somewhere in the rendered HTML")
    }
}