package com.TTT.Debug

import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class InteractiveTraceVisualizationTest 
{
    @Test
    fun testInteractiveHtmlGeneration() 
    {
        val trace = generateMockPipelineTrace()
        val visualizer = TraceVisualizer()
        val htmlReport = visualizer.generateHtmlReport(trace)
        
        // Verify interactive elements
        assertTrue(htmlReport.contains("click node"))
        assertTrue(htmlReport.contains("scrollToEvent"))
        assertTrue(htmlReport.contains("data-pipe="))
        assertTrue(htmlReport.contains("trace-event-"))
        
        // Verify CSS classes
        assertTrue(htmlReport.contains("highlighted"))
        assertTrue(htmlReport.contains("flash-highlight"))
        
        // Write test file
        File("interactive-trace-test.html").writeText(htmlReport)
    }
    
    @Test
    fun testNodeIdGeneration() 
    {
        val events = generateMockPipelineTrace()
        val nodes = TraceNodeMapper.mapEventsToNodes(events)
        
        nodes.forEach { node ->
            assertTrue(node.nodeId.startsWith("node-"))
            assertTrue(node.eventIds.all { it.startsWith("trace-event-") })
        }
    }
    
    private fun generateMockPipelineTrace(): List<TraceEvent> 
    {
        val baseTime = System.currentTimeMillis()
        return listOf(
            TraceEvent(
                timestamp = baseTime,
                pipeId = "pipe-001",
                pipeName = "BedrockPipe-Claude",
                eventType = TraceEventType.PIPE_START,
                phase = TracePhase.INITIALIZATION,
                content = MultimodalContent("Test input"),
                contextSnapshot = null,
                metadata = mapOf("model" to "claude-3-sonnet")
            ),
            TraceEvent(
                timestamp = baseTime + 200,
                pipeId = "pipe-001",
                pipeName = "BedrockPipe-Claude",
                eventType = TraceEventType.API_CALL_SUCCESS,
                phase = TracePhase.EXECUTION,
                content = MultimodalContent("API response"),
                contextSnapshot = null,
                metadata = mapOf("responseTokens" to 300)
            )
        )
    }
}
