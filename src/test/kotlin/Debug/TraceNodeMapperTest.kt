package com.TTT.Debug

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TraceNodeMapperTest 
{
    @Test
    fun testEventToNodeMapping() 
    {
        val events = listOf(
            createTraceEvent("pipe1", TraceEventType.PIPE_START),
            createTraceEvent("pipe1", TraceEventType.PIPE_SUCCESS),
            createTraceEvent("pipe2", TraceEventType.PIPE_FAILURE)
        )
        
        val nodes = TraceNodeMapper.mapEventsToNodes(events)
        
        assertEquals(2, nodes.size)
        assertEquals(NodeStatus.SUCCESS, nodes.find { it.pipeName == "pipe1" }?.status)
        assertEquals(NodeStatus.FAILURE, nodes.find { it.pipeName == "pipe2" }?.status)
    }
    
    @Test
    fun testNodeIdGeneration() 
    {
        val events = listOf(createTraceEvent("TestPipe", TraceEventType.PIPE_START))
        val nodes = TraceNodeMapper.mapEventsToNodes(events)
        
        assertEquals(1, nodes.size)
        assertTrue(nodes[0].nodeId.startsWith("node-"))
        assertEquals("TestPipe", nodes[0].pipeName)
    }

    @Test
    fun testDistributionGridEventsUseGridAwareNodeKeys()
    {
        val events = listOf(
            createTraceEvent(
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_PEER_HANDOFF,
                metadata = mapOf("peerKey" to "peer-b", "targetNodeId" to "node-b")
            ),
            createTraceEvent(
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_REGISTRY_QUERY,
                metadata = mapOf("registryId" to "registry-1")
            ),
            createTraceEvent(
                pipeName = "DistributionGrid-node-a",
                eventType = TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING,
                metadata = mapOf("listingId" to "listing-123", "listingKind" to "GRID_NODE")
            )
        )

        val nodes = TraceNodeMapper.mapEventsToNodes(events)

        assertTrue(nodes.any { it.pipeName.contains("DistributionGrid-Remote-peer-b") })
        assertTrue(nodes.any { it.pipeName.contains("DistributionGrid-Registry-registry-1") })
        assertTrue(nodes.any { it.pipeName.contains("DistributionGrid-Listing-listing-123") })
    }

    @Test
    fun testFailureDominatesSuccessForRetryingPipe()
    {
        val events = listOf(
            createTraceEvent("retrying-pipe", TraceEventType.PIPE_SUCCESS),
            createTraceEvent("retrying-pipe", TraceEventType.PIPE_RETRY),
            createTraceEvent("retrying-pipe", TraceEventType.PIPE_SUCCESS)
        )

        val node = TraceNodeMapper.mapEventsToNodes(events).single()

        assertEquals(
            NodeStatus.FAILURE,
            node.status,
            "a later successful recovery must not hide that this pipe retried after a failure"
        )
    }

    @Test
    fun testRetryingPipeStaysRedInFlowGraphDespiteLaterSuccess()
    {
        // Mirrors the live failure: the resource-detection pipe hit PIPE_FAILURE six times
        // interleaved with successful recoveries, and the graph rendered it neutral because the
        // last emitted class line for the node came from a benign event.
        val events = listOf(
            createTraceEvent("resource detection pipe", TraceEventType.PIPE_SUCCESS),
            createTraceEvent("resource detection pipe", TraceEventType.PIPE_FAILURE),
            createTraceEvent("resource detection pipe", TraceEventType.PIPE_SUCCESS),
            createTraceEvent("resource detection pipe", TraceEventType.PIPE_FAILURE),
            createTraceEvent("resource detection pipe", TraceEventType.PIPE_SUCCESS)
        )

        val visualizer = TraceVisualizer()
        val html = visualizer.generateHtmlReport(events)

        val node = TraceNodeMapper.mapEventsToNodes(events).single()
        assertEquals(NodeStatus.FAILURE, node.status)

        val classLines = Regex("""^\s*${Regex.escape(node.nodeId)}:::(\w+)$""", RegexOption.MULTILINE)
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            listOf("failure"),
            classLines,
            "the flow graph must emit exactly one style class per node, and a node that failed must stay red"
        )
    }

    @Test
    fun testPipeRetryMapsToFailureAndRendersAsRed()
    {
        val events = listOf(createTraceEvent("retrying-pipe", TraceEventType.PIPE_RETRY))

        val node = TraceNodeMapper.mapEventsToNodes(events).single()
        val visualizer = TraceVisualizer()
        val graph = visualizer.generateFlowChart(events)
        val timeline = visualizer.generateTimeline(events)
        val markdown = visualizer.generateMarkdownOutput(events)
        val html = visualizer.generateHtmlReport(events)
        assertTrue(graph.contains("❌ retrying-pipe -> PIPE_RETRY"))
        assertTrue(html.contains(":::failure"))
        assertTrue(markdown.contains("| FAILURE |"))
        assertEquals(NodeStatus.FAILURE, node.status)
    }

    private fun createTraceEvent(
        pipeName: String,
        eventType: TraceEventType,
        metadata: Map<String, Any> = emptyMap()
    ): TraceEvent
    {
        return TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe",
            pipeName = pipeName,
            eventType = eventType,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null,
            metadata = metadata
        )
    }
}
