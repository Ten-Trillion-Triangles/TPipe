package com.TTT.Debug

import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression: generated trace.html files must be self-contained.
 *
 * GAP (observed 2026-10-30): every report template shipped
 * `<script src="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js">` — an
 * unversioned third-party CDN dependency with no fallback. Mermaid is the sole
 * thing that renders the flow graph; when that single request fails (offline box,
 * DNS failure, file:// sandbox, proxy, CDN outage) the raw graph source stays in
 * `div.mermaid` as plain text and renders as a jumbled wall of
 * `graph TD / node-… / click … / classDef …` over the report. An unversioned pin
 * also drifts: the same static artifact renders differently on different days.
 *
 * Contract pinned here — the file is a static debug artifact and must open
 * correctly with zero network:
 *   - no external `<script src=>` tag in any report template;
 *   - the pinned mermaid runtime is inlined into the page;
 *   - a guard is present so that if the runtime is ever unavailable the raw
 *     graph source is never left as the visible fallback content.
 */
class TraceHtmlSelfContainedTest
{
    private fun standardTrace(): List<TraceEvent> = listOf(
        TraceEvent(
            timestamp = 1_000L,
            pipeId = "p-1",
            pipeName = "alpha pipe",
            eventType = TraceEventType.PIPE_START,
            phase = TracePhase.INITIALIZATION,
            content = MultimodalContent("input"),
            contextSnapshot = null,
            metadata = mapOf("model" to "agnes-3.0-flash")
        ),
        TraceEvent(
            timestamp = 2_000L,
            pipeId = "p-1",
            pipeName = "alpha pipe",
            eventType = TraceEventType.PIPE_SUCCESS,
            phase = TracePhase.CLEANUP,
            content = MultimodalContent("output"),
            contextSnapshot = null,
            metadata = emptyMap()
        )
    )

    private fun manifoldTrace(): List<TraceEvent> = listOf(
        TraceEvent(
            timestamp = 1_000L,
            pipeId = "m-1",
            pipeName = "Manifold-X",
            eventType = TraceEventType.MANIFOLD_START,
            phase = TracePhase.ORCHESTRATION,
            content = MultimodalContent("task"),
            contextSnapshot = null,
            metadata = emptyMap()
        ),
        TraceEvent(
            timestamp = 2_000L,
            pipeId = "m-1",
            pipeName = "Manifold-X",
            eventType = TraceEventType.MANIFOLD_END,
            phase = TracePhase.CLEANUP,
            content = MultimodalContent("result"),
            contextSnapshot = null,
            metadata = emptyMap()
        )
    )

    private fun junctionTrace(): List<TraceEvent> = listOf(
        TraceEvent(
            timestamp = 1_000L,
            pipeId = "j-1",
            pipeName = "junction-1",
            eventType = TraceEventType.JUNCTION_START,
            phase = TracePhase.EXECUTION,
            content = MultimodalContent("in"),
            contextSnapshot = null,
            metadata = emptyMap()
        ),
        TraceEvent(
            timestamp = 2_000L,
            pipeId = "j-1",
            pipeName = "junction-1",
            eventType = TraceEventType.JUNCTION_END,
            phase = TracePhase.CLEANUP,
            content = MultimodalContent("out"),
            contextSnapshot = null,
            metadata = emptyMap()
        )
    )

    private fun distributionGridTrace(): List<TraceEvent> = listOf(
        TraceEvent(
            timestamp = 1_000L,
            pipeId = "d-1",
            pipeName = "grid-1",
            eventType = TraceEventType.DISTRIBUTION_GRID_INIT,
            phase = TracePhase.ORCHESTRATION,
            content = MultimodalContent("in"),
            contextSnapshot = null,
            metadata = emptyMap()
        ),
        TraceEvent(
            timestamp = 2_000L,
            pipeId = "d-1",
            pipeName = "grid-1",
            eventType = TraceEventType.DISTRIBUTION_GRID_VALIDATION_SUCCESS,
            phase = TracePhase.ORCHESTRATION,
            content = MultimodalContent("out"),
            contextSnapshot = null,
            metadata = emptyMap()
        )
    )

    private fun splitterTrace(): List<TraceEvent> = listOf(
        TraceEvent(
            timestamp = 1_000L,
            pipeId = "s-1",
            pipeName = "splitter-1",
            eventType = TraceEventType.SPLITTER_START,
            phase = TracePhase.ORCHESTRATION,
            content = MultimodalContent("in"),
            contextSnapshot = null,
            metadata = emptyMap()
        ),
        TraceEvent(
            timestamp = 2_000L,
            pipeId = "s-1",
            pipeName = "splitter-1",
            eventType = TraceEventType.SPLITTER_END,
            phase = TracePhase.CLEANUP,
            content = MultimodalContent("out"),
            contextSnapshot = null,
            metadata = emptyMap()
        )
    )

    /**
     * External script tags in a generated report. A static debug artifact must
     * not reach out to a CDN.
     */
    private fun externalScriptTags(html: String): List<String> =
        Regex("<script\\s+src\\s*=", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()

    @Test
    fun `standard report has zero external script tags`()
    {
        val html = TraceVisualizer().generateHtmlReport(standardTrace())
        assertEquals(
            emptyList(),
            externalScriptTags(html),
            "the standard report must be self-contained; every external script tag is a " +
                "network dependency that leaves the raw mermaid source visible when it fails"
        )
    }

    @Test
    fun `all five report templates ship zero external script tags`()
    {
        val visualizer = TraceVisualizer()
        val reports = mapOf(
            "standard" to visualizer.generateHtmlReport(standardTrace()),
            "manifold" to visualizer.generateHtmlReport(manifoldTrace()),
            "junction" to visualizer.generateHtmlReport(junctionTrace()),
            "distribution-grid" to visualizer.generateHtmlReport(distributionGridTrace()),
            "splitter" to visualizer.generateHtmlReport(splitterTrace())
        )
        reports.forEach { (name, html) ->
            assertEquals(
                emptyList(),
                externalScriptTags(html),
                "the $name report must be self-contained"
            )
        }
    }

    @Test
    fun `the pinned mermaid runtime is inlined into the standard report`()
    {
        val html = TraceVisualizer().generateHtmlReport(standardTrace())
        assertTrue(
            html.contains("TPipe mermaid runtime"),
            "the inlined runtime banner must be present; without it the flow graph has no renderer"
        )
        assertTrue(
            html.length > 1_000_000,
            "the inlined mermaid bundle inflates the artifact; a small file means the runtime was not inlined"
        )
    }

    @Test
    fun `an unavailable runtime degrades instead of leaking raw graph source`()
    {
        val html = TraceVisualizer().generateHtmlReport(standardTrace())
        assertTrue(
            html.contains("mermaid-fallback"),
            "the guard must style the degraded view; without it the raw graph source renders as text"
        )
        assertTrue(
            html.contains("diagram renderer unavailable"),
            "the guard must tell the reader the diagram did not render and why"
        )
    }
}
