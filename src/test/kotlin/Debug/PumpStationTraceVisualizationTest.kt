package com.TTT.Debug

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for the custom PumpStation HTML report. Verifies that the report:
 *  - is dispatched from generateHtmlReport when a trace contains PUMP_STATION_* events
 *  - contains the turn-centric layout primitives: state ribbon, sparkline, path inventory, turn
 *    cards, and outcome panel
 *  - has the expected custom CSS classes
 *  - works for all four output formats (HTML, JSON, MARKDOWN, CONSOLE)
 */
class PumpStationTraceVisualizationTest
{
    private val visualizer = TraceVisualizer()

    @Test
    fun generateHtmlReportDispatchesToPumpStationRenderer()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_COMPLETED, mapOf("runId" to "ps-test"))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("PumpStation Trace"), "Should use PumpStation title")
        assertTrue(html.contains("ps-container"), "Should use custom ps- CSS namespace")
    }

    @Test
    fun reportContainsStateRibbon()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED,
                mapOf("runId" to "ps-test", "selectedPathName" to "search"))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-ribbon"), "Should include the state ribbon")
        assertTrue(html.contains("ps-ribbon-card"), "Should include ribbon cards")
    }

    @Test
    fun reportContainsPathInventory()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_PATH_SELECTED,
                mapOf("runId" to "ps-test", "pathName" to "search", "riskLevel" to "LOW"))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-paths-section"), "Should include paths section")
        assertTrue(html.contains("search"), "Should mention the path name")
    }

    @Test
    fun reportContainsTurnTimelineWithPhaseRibbon()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(1, TraceEventType.PUMP_STATION_JUDGE_COMPLETED,
                mapOf("runId" to "ps-test", "isComplete" to false, "shouldTerminate" to false)),
            createEvent(1, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED,
                mapOf("runId" to "ps-test", "selectedPathName" to "search")),
            createEvent(1, TraceEventType.PUMP_STATION_PATH_COMPLETED,
                mapOf("runId" to "ps-test", "pathName" to "search", "riskLevel" to "LOW"))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-turns-section"), "Should include turn timeline section")
        assertTrue(html.contains("Turn 1"), "Should label the turn")
        assertTrue(html.contains("ps-turn-card"), "Should use turn card CSS class")
        assertTrue(html.contains("ps-phase-pill"), "Should render phase ribbon pills")
    }

    @Test
    fun reportContainsMemorySparkline()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(1, TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED,
                mapOf("runId" to "ps-test", "memoryMode" to "Hybrid", "compactionPercent" to 0.42)),
            createEvent(2, TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED,
                mapOf("runId" to "ps-test", "memoryMode" to "Hybrid", "compactionPercent" to 0.55))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-sparkline"), "Should include the memory sparkline")
        assertTrue(html.contains("Memory pressure"), "Should label the sparkline")
        assertTrue(html.contains("blowout"), "Should show the blowout threshold line")
    }

    @Test
    fun reportShowsBackgroundStripForMemoryAndAsyncEvents()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(1, TraceEventType.PUMP_STATION_FOREGROUND_AGENT_COMPLETED,
                mapOf("runId" to "ps-test", "agentName" to "validator")),
            createEvent(1, TraceEventType.PUMP_STATION_STASH_CREATED,
                mapOf("runId" to "ps-test", "stashId" to "stash-1", "reason" to "TokenOverflow"))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-bg-strip"), "Should include background strip")
        assertTrue(html.contains("Background:"), "Should label background strip")
    }

    @Test
    fun reportShowsOutcomePanelForFailure()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_FAILED,
                mapOf("runId" to "ps-test", "error" to "MaxTurnsExceeded", "errorMessage" to "Turn cap", "exitReason" to "MaxTurnsHit"))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-outcome"), "Should include outcome panel")
        assertTrue(html.contains("FAILED"), "Should show failure status")
        assertTrue(html.contains("MaxTurnsHit"), "Should show exit reason")
    }

    @Test
    fun reportHandlesEmptyTraceGracefully()
    {
        // Contract: when the dispatcher cannot identify a container from the trace (empty list =>
        // no PUMP_STATION_* events to key on), it must NOT throw and must produce a well-formed
        // HTML document via the standard layout. We assert the four invariants that make the
        // handling genuinely "graceful" rather than a silent pass:
        //   1. The call returns without throwing (implicit in the @Test passing).
        //   2. The output is non-blank (no swallowed exception or empty string).
        //   3. The output is structurally valid HTML (open + close <html> tags).
        //   4. The output carries a recognizable TPipe heading so a developer opening an empty
        //      trace file in a browser sees something coherent rather than a blank page.
        val html = visualizer.generateHtmlReport(emptyList())
        assertTrue(html.isNotBlank(), "Empty trace must not produce blank HTML")
        assertTrue(html.contains("<html>") && html.contains("</html>"),
            "Empty trace must still produce well-formed HTML (open and close <html> tags)")
        assertTrue(html.contains("TPipe"),
            "Empty trace must still carry a TPipe heading so a developer can tell the report rendered")
    }

    @Test
    fun pumpStationGeneratorHandlesEmptyTraceDirectly()
    {
        // Companion check: the PumpStation-specific generator (bypassing the dispatcher) has its
        // OWN empty-trace branch that returns a PumpStation-styled placeholder. This is the path
        // a developer would hit if they already know they are debugging a PumpStation and call
        // the specialized renderer. We assert the placeholder is returned verbatim.
        val placeholder = visualizer.generatePumpStationHtmlReport(emptyList())
        assertTrue(placeholder.contains("PumpStation Trace"),
            "PumpStation-specific generator should label its empty-trace placeholder as PumpStation")
        assertTrue(placeholder.contains("(empty trace)"),
            "PumpStation-specific generator should explicitly mark the trace as empty")
    }

    @Test
    fun flowChartDetectsPumpStation()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_COMPLETED, mapOf("runId" to "ps-test"))
        )
        val flow = visualizer.generateFlowChart(trace)
        assertTrue(flow.contains("PumpStation Orchestration Flow"), "Flow chart heading should identify PumpStation")
    }

    @Test
    fun timelineDetectsPumpStation()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_COMPLETED, mapOf("runId" to "ps-test"))
        )
        val timeline = visualizer.generateTimeline(trace)
        assertTrue(timeline.contains("PumpStation Timeline"), "Timeline heading should identify PumpStation")
    }

    @Test
    fun consoleDetectsPumpStation()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_COMPLETED, mapOf("runId" to "ps-test"))
        )
        val console = visualizer.generateConsoleOutput(trace)
        assertTrue(console.contains("TPipe PumpStation Trace"), "Console heading should identify PumpStation")
    }

    @Test
    fun markdownDetectsPumpStation()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_COMPLETED, mapOf("runId" to "ps-test"))
        )
        val md = visualizer.generateMarkdownOutput(trace)
        assertTrue(md.contains("TPipe PumpStation Trace Report"), "Markdown heading should identify PumpStation")
    }

    @Test
    fun statusDetectionWorks()
    {
        val completedTrace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_COMPLETED, mapOf("runId" to "ps-test"))
        )
        val completedHtml = visualizer.generateHtmlReport(completedTrace)
        assertTrue(completedHtml.contains("ps-status-completed"), "Should mark trace as completed")

        val failedTrace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_FAILED, mapOf("runId" to "ps-test"))
        )
        val failedHtml = visualizer.generateHtmlReport(failedTrace)
        assertTrue(failedHtml.contains("ps-status-failed"), "Should mark trace as failed")
    }

    //=================================================Content extras + token chips==================================

    @Test
    fun pathCompletedExtrasBlockRendersWhenContentPresent()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_PATH_COMPLETED, mapOf(
                "runId" to "ps-test",
                "pathName" to "search",
                "riskLevel" to "LOW",
                "contentPreview" to "text=found 3 results",
                "contentLength" to 16
            ))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-event-extras"), "Path content toggle must render when content is present")
        assertTrue(html.contains("ps-event-text"), "Path text block must render when content is present")
        assertTrue(html.contains("found 3 results"), "Path text content must appear in extras block")
    }

    @Test
    fun pathCompletedExtrasBlockAbsentWhenContentEmpty()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_PATH_COMPLETED, mapOf(
                "runId" to "ps-test",
                "pathName" to "search",
                "riskLevel" to "LOW"
                // no contentPreview, no tokens -> no extras block
            ))
        )
        val html = visualizer.generateHtmlReport(trace)
        // The CSS class is referenced in the stylesheet, but no actual `<details class="ps-event-extras">` instance
        assertFalse(html.contains("<details class=\"ps-event-extras\""),
            "Path with no content should not produce an extras block instance")
    }

    @Test
    fun judgeReasoningToggleRendersWhenReasoningPresent()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_JUDGE_COMPLETED, mapOf(
                "runId" to "ps-test",
                "isComplete" to true,
                "shouldTerminate" to true,
                "contentPreview" to "text=verdict: complete",
                "modelReasoning" to "Reasoning chain: the task asked for X. I observed Y. So Z is complete.",
                "modelReasoningLen" to 65
            ))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-event-reasoning"), "Reasoning toggle must render when reasoning is present")
        assertTrue(html.contains("ps-event-reasoning-text"), "Reasoning text block must render when reasoning is present")
        assertTrue(html.contains("the task asked for X"), "Reasoning text must appear in the block")
    }

    @Test
    fun dispatchOutputToggleRendersWhenContentPresent()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED, mapOf(
                "runId" to "ps-test",
                "selectedPathName" to "search",
                "pathRequest" to "{}",
                "contentPreview" to "text=\"search path request\"",
                "contentLength" to 22
            ))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-event-extras"), "Dispatch output toggle must render when content is present")
        assertTrue(html.contains("ps-event-text"), "Dispatch text block must render when content is present")
    }

    @Test
    fun foregroundAgentContentToggleRendersWhenContentPresent()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_FOREGROUND_AGENT_COMPLETED, mapOf(
                "runId" to "ps-test",
                "agentName" to "validator",
                "contentPreview" to "text=validator output",
                "contentLength" to 16
            ))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-event-extras"), "Foreground-agent toggle must render when content is present")
        assertTrue(html.contains("validator output"), "Foreground-agent text content must appear")
    }

    @Test
    fun tokenChipRowRendersWhenTokensSet()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_PATH_COMPLETED, mapOf(
                "runId" to "ps-test",
                "pathName" to "search",
                "riskLevel" to "LOW",
                "contentPreview" to "text=ok",
                "contentLength" to 2,
                "inputTokens" to 1240,
                "outputTokens" to 412,
                "totalTokens" to 1652
            ))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-token-row"), "Per-event token chip row must render when tokens are set")
        assertTrue(html.contains("ps-token-chip-in"), "Input chip must be present")
        assertTrue(html.contains("ps-token-chip-out"), "Output chip must be present")
        assertTrue(html.contains("ps-token-chip-total"), "Total chip must be present")
        assertTrue(html.contains("1,240"), "Input token count must be formatted with thousands separator")
    }

    @Test
    fun tokenChipsAbsentWhenAllTokensNull()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_PATH_COMPLETED, mapOf(
                "runId" to "ps-test",
                "pathName" to "search",
                "riskLevel" to "LOW",
                "contentPreview" to "text=ok",
                "contentLength" to 2
                // no inputTokens/outputTokens/totalTokens
            ))
        )
        val html = visualizer.generateHtmlReport(trace)
        // The chip-row class only appears inside the per-event extras when tokens are set.
        // The CSS class is in the stylesheet, so we look for the chip-row instance, not the class.
        // Since the event has content but no tokens, buildPumpStationEventExtras renders
        // extras but no token row. We assert the absence of an inline ps-token-row instance.
        assertFalse(html.contains("class='ps-token-row'"),
            "Token chip row must be absent when all token fields are null")
    }

    @Test
    fun tokenSummaryRowAggregatesPerTurnTotals()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_PATH_COMPLETED, mapOf(
                "runId" to "ps-test",
                "pathName" to "search",
                "riskLevel" to "LOW",
                "inputTokens" to 1000,
                "outputTokens" to 200,
                "totalTokens" to 1200
            )),
            createEvent(0, TraceEventType.PUMP_STATION_JUDGE_COMPLETED, mapOf(
                "runId" to "ps-test",
                "isComplete" to true,
                "shouldTerminate" to false,
                "inputTokens" to 240,
                "outputTokens" to 50,
                "totalTokens" to 290
            ))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-token-summary"), "Per-turn token summary row must render")
        assertTrue(html.contains("Turn tokens:"), "Per-turn token summary must have a label")
        // 1000 + 240 = 1240, 200 + 50 = 250, 1200 + 290 = 1490
        assertTrue(html.contains("1,240"), "Aggregated input tokens must be 1,240")
        assertTrue(html.contains("250"), "Aggregated output tokens must be 250")
        assertTrue(html.contains("1,490"), "Aggregated total tokens must be 1,490")
    }

    @Test
    fun nestedP2PBlockRendersWhenEventsPresent()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_NESTED_P2P_COMPLETED, mapOf(
                "runId" to "ps-test",
                "pathName" to "research",
                "agentName" to "helper",
                "contentPreview" to "text=helper output",
                "contentLength" to 13,
                "inputTokens" to 100,
                "outputTokens" to 50,
                "totalTokens" to 150
            ))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertTrue(html.contains("ps-nested-p2p"), "Nested P2P block must render when events are present")
        assertTrue(html.contains("Nested P2P calls"), "Nested P2P block must have a header")
        assertTrue(html.contains("helper"), "Nested P2P agent name must appear")
    }

    @Test
    fun pathContentIsHtmlEscaped()
    {
        // XSS test — if the visualizer forgot to escape the path content, the <script> tag
        // would appear verbatim in the output and a browser could execute it.
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(0, TraceEventType.PUMP_STATION_PATH_COMPLETED, mapOf(
                "runId" to "ps-test",
                "pathName" to "search",
                "riskLevel" to "LOW",
                "contentPreview" to "text=<script>alert(1)</script>",
                "contentLength" to 26
            ))
        )
        val html = visualizer.generateHtmlReport(trace)
        assertFalse(html.contains("<script>alert(1)</script>"),
            "Path content must be HTML-escaped, raw <script> tag must not appear")
        assertTrue(html.contains("&lt;script&gt;") || html.contains("&lt;script>"),
            "Path content must be escaped to &lt;script&gt;")
    }

    /**
     * Aggregate token totals should appear at the top of the HTML report as a
     * dedicated card. Aggregates inputTokens and outputTokens from every event
     * that carries them in metadata, SKIPPING KILLSWITCH_CHECK (which reports
     * cumulative-AT-check-time, not actual spend — the underlying
     * JUDGE_COMPLETED / DISPATCH_COMPLETED / PATH_COMPLETED events already
     * cover that ground).
     */
    @Test
    fun reportShowsTokenTotalsHeaderCard()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            // Real spend events — these DO contribute to the totals.
            createEvent(1, TraceEventType.PUMP_STATION_JUDGE_COMPLETED,
                mapOf("runId" to "ps-test", "isComplete" to false, "shouldTerminate" to false,
                      "inputTokens" to 1000, "outputTokens" to 500, "totalTokens" to 1500)),
            createEvent(1, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED,
                mapOf("runId" to "ps-test", "selectedPathName" to "search",
                      "inputTokens" to 2000, "outputTokens" to 800, "totalTokens" to 2800)),
            // KILLSWITCH_CHECK — must be EXCLUDED from the totals aggregate
            // (input=3000, output=1300 below must NOT push the totals to 6,000 in / 2,600 out).
            createEvent(1, TraceEventType.KILLSWITCH_CHECK,
                mapOf("runId" to "ps-test",
                      "inputTokens" to 3000, "outputTokens" to 1300,
                      "inputLimit" to "none", "outputLimit" to "none",
                      "elapsedMs" to 1L))
        )
        val html = visualizer.generateHtmlReport(trace)
        // Card exists in the PumpStation namespace. Targets the rendered span,
        // not the bare class name — the bare name also matches the CSS
        // selector in the embedded <style> block.
        assertTrue(html.contains("<span class=\"trace-token-card\">"),
            "Report must include the rendered trace-token-card element in the header")
        // Comma-formatted sums (KillSwitch input=3000/output=1300 must NOT contribute).
        assertTrue(html.contains("Input: 3,000"),
            "Input total = 1000 (judge) + 2000 (dispatch) = 3,000; KillSwitch 3000 must be skipped")
        assertTrue(html.contains("Output: 1,300"),
            "Output total = 500 (judge) + 800 (dispatch) = 1,300; KillSwitch 1300 must be skipped")
        assertTrue(html.contains("Total: 4,300"),
            "Sum total = 3,000 + 1,300 = 4,300")
        // The KillSwitch row should not inflate the visible totals past 4,300.
        assertFalse(html.contains("Total: 7,900") || html.contains("Total: 7,600"),
            "Header must not double-count KILLSWITCH_CHECK into the totals")
    }

    /**
     * Card hidden when no event carries token metadata — short harness runs
     * (e.g. only a PUMP_STATION_STARTED and one path turn) should not show
     * a misleading "0 tokens" card.
     *
     * The assertion targets the rendered `<span class="ps-token-card">` opening
     * tag rather than the bare class name — the bare name also matches the
     * CSS selector in the embedded `<style>` block, which would always be
     * present once the feature ships.
     */
    @Test
    fun reportHidesTokenCardWhenNoTokenMetadata()
    {
        val trace = listOf(
            createEvent(0, TraceEventType.PUMP_STATION_STARTED, mapOf("runId" to "ps-test")),
            createEvent(1, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED,
                mapOf("runId" to "ps-test", "selectedPathName" to "search"))
            // No inputTokens / outputTokens in any metadata.
        )
        val html = visualizer.generateHtmlReport(trace)
        assertFalse(html.contains("<span class=\"trace-token-card\">"),
            "Header must hide the token card when zero events carry token metadata")
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
