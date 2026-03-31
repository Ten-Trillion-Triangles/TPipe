package com.TTT.Debug

import com.TTT.Config.TPipeConfig
import com.TTT.Pipe.MultimodalContent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DistributionGridTraceVisualizationTest
{
    @Test
    fun generateDistributionGridHtmlTraceShowsGridSpecificSections()
    {
        val visualizer = TraceVisualizer()
        val htmlReport = visualizer.generateHtmlReport(generateMockDistributionGridTrace())

        assertTrue(htmlReport.contains("TPipe DistributionGrid Execution Analysis"))
        assertTrue(htmlReport.contains("Grid State"))
        assertTrue(htmlReport.contains("Grid Orchestration Flow"))
        assertTrue(htmlReport.contains("Routing, Handoff, and Decision Timeline"))
        assertTrue(htmlReport.contains("Discovery, Registry, and Public Listing Activity"))
        assertTrue(htmlReport.contains("Bootstrap Catalog"))
        assertTrue(htmlReport.contains("Public Listing"))
        assertTrue(htmlReport.contains("Remote Peer"))
        assertTrue(htmlReport.contains("Router"))
        assertTrue(htmlReport.contains("tracePolicyAllowTracePersistence"))
        assertTrue(htmlReport.contains("overflow-wrap: anywhere;"))
        assertTrue(htmlReport.contains("min-width: 0;"))
    }

    @Test
    fun generateDistributionGridTextOutputsUseGridSpecificHeadings()
    {
        val trace = generateMockDistributionGridTrace()
        val visualizer = TraceVisualizer()

        val flowChart = visualizer.generateFlowChart(trace)
        val timeline = visualizer.generateTimeline(trace)
        val console = visualizer.generateConsoleOutput(trace)

        assertTrue(flowChart.contains("DistributionGrid Orchestration Flow"))
        assertTrue(timeline.contains("DistributionGrid Timeline"))
        assertTrue(console.contains("TPipe DistributionGrid Trace"))
        assertTrue(console.contains("Remote Peer"))
    }

    @Test
    fun pipeTracerMarkdownExportUsesGridSpecificRenderer()
    {
        val traceId = "distribution-grid-trace-visualization"
        PipeTracer.enable()
        PipeTracer.replaceTrace(traceId, generateMockDistributionGridTrace())

        val markdown = PipeTracer.exportTrace(traceId, TraceFormat.MARKDOWN)

        assertTrue(markdown.contains("TPipe DistributionGrid Trace Report"))
        assertTrue(markdown.contains("Remote Peer"))
        assertTrue(markdown.contains("Bootstrap Catalog"))

        PipeTracer.clearTrace(traceId)
    }

    @Test
    fun generateDistributionGridHtmlTraceFileInDefaultTraceDirectory()
    {
        val visualizer = TraceVisualizer()
        val htmlReport = visualizer.generateHtmlReport(generateMockDistributionGridTrace())
        val traceDir = File(TPipeConfig.getTraceDir(), "Library/distribution-grid-trace-visualization")
        if(!traceDir.exists())
        {
            traceDir.mkdirs()
        }

        val outputFile = File(traceDir, "distribution-grid.html")
        outputFile.writeText(htmlReport)

        assertTrue(traceDir.exists())
        assertTrue(outputFile.exists())
        assertTrue(outputFile.length() > 0L)
        assertTrue(htmlReport.contains("TPipe DistributionGrid Execution Analysis"))
        assertTrue(htmlReport.contains("Grid State"))
        assertTrue(htmlReport.contains("Grid Orchestration Flow"))
        assertTrue(htmlReport.contains("Discovery, Registry, and Public Listing Activity"))
        assertTrue(htmlReport.contains("tracePolicyAllowTracePersistence"))
        assertTrue(htmlReport.contains("overflow-wrap: anywhere;"))
    }

    private fun generateMockDistributionGridTrace(): List<TraceEvent>
    {
        val baseTime = System.currentTimeMillis()
        return listOf(
            TraceEvent(
                timestamp = baseTime,
                pipeId = "grid-001",
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_START,
                phase = TracePhase.ORCHESTRATION,
                content = MultimodalContent("Start task"),
                contextSnapshot = null,
                metadata = mapOf("taskId" to "task-123", "nodeId" to "node-a")
            ),
            TraceEvent(
                timestamp = baseTime + 15,
                pipeId = "grid-001",
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_POLICY_EVALUATION,
                phase = TracePhase.ORCHESTRATION,
                content = MultimodalContent("Policy debug"),
                contextSnapshot = null,
                metadata = mapOf(
                    "tracePolicyAllowTracing" to true,
                    "tracePolicyAllowTracePersistence" to true,
                    "tracePolicyRequireRedaction" to false,
                    "tracePolicyRejectNonCompliantNodes" to true,
                    "currentNodeId" to "node-a"
                )
            ),
            TraceEvent(
                timestamp = baseTime + 30,
                pipeId = "grid-001",
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_ROUTER_DECISION,
                phase = TracePhase.ORCHESTRATION,
                content = MultimodalContent("Route to peer"),
                contextSnapshot = null,
                metadata = mapOf(
                    "taskId" to "task-123",
                    "directiveKind" to "HAND_OFF_TO_PEER",
                    "targetNodeId" to "node-b"
                )
            ),
            TraceEvent(
                timestamp = baseTime + 60,
                pipeId = "grid-001",
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_MEMORY_ENVELOPE,
                phase = TracePhase.CONTEXT_PREPARATION,
                content = null,
                contextSnapshot = null,
                metadata = mapOf(
                    "taskId" to "task-123",
                    "targetNodeId" to "node-b",
                    "resolvedBudget" to 4096,
                    "compacted" to true
                )
            ),
            TraceEvent(
                timestamp = baseTime + 90,
                pipeId = "grid-001",
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_PEER_HANDOFF,
                phase = TracePhase.P2P_TRANSPORT,
                content = MultimodalContent("Send to peer"),
                contextSnapshot = null,
                metadata = mapOf(
                    "taskId" to "task-123",
                    "peerKey" to "peer-b",
                    "targetNodeId" to "node-b"
                )
            ),
            TraceEvent(
                timestamp = baseTime + 140,
                pipeId = "grid-001",
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_BOOTSTRAP_CATALOG_PULL,
                phase = TracePhase.P2P_TRANSPORT,
                content = null,
                contextSnapshot = null,
                metadata = mapOf(
                    "sourceId" to "public-catalog",
                    "operation" to "complete",
                    "acceptedCount" to 1,
                    "trustRejectedCount" to 0
                )
            ),
            TraceEvent(
                timestamp = baseTime + 170,
                pipeId = "grid-001",
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_REGISTRY_QUERY,
                phase = TracePhase.P2P_TRANSPORT,
                content = null,
                contextSnapshot = null,
                metadata = mapOf(
                    "registryId" to "registry-1",
                    "queryKinds" to "worker"
                )
            ),
            TraceEvent(
                timestamp = baseTime + 210,
                pipeId = "grid-001",
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING,
                phase = TracePhase.CLEANUP,
                content = null,
                contextSnapshot = null,
                metadata = mapOf(
                    "operation" to "publish",
                    "listingKind" to "GRID_NODE",
                    "listingId" to "listing-1",
                    "accepted" to true
                )
            ),
            TraceEvent(
                timestamp = baseTime + 260,
                pipeId = "grid-001",
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_SUCCESS,
                phase = TracePhase.CLEANUP,
                content = MultimodalContent("Done"),
                contextSnapshot = null,
                metadata = mapOf("taskId" to "task-123", "nodeId" to "node-a")
            ),
            TraceEvent(
                timestamp = baseTime + 280,
                pipeId = "grid-001",
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_END,
                phase = TracePhase.CLEANUP,
                content = MultimodalContent("Finished"),
                contextSnapshot = null,
                metadata = mapOf("taskId" to "task-123", "nodeId" to "node-a")
            )
        )
    }
}
