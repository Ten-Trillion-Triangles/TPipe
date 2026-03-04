package com.TTT

import com.TTT.Debug.*
import com.TTT.Pipe.MultimodalContent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class TraceVisualizationTest {

    @Test
    fun generateInputOutputHtmlTrace() {
        val trace = generateMockPipelineTrace()
        val visualizer = TraceVisualizer()
        val htmlReport = visualizer.generateHtmlReport(trace)

        // Verify it contains the expandable details for Input and Output
        assertTrue(htmlReport.contains("Input Content"), "HTML did not contain Input Content")
        assertTrue(htmlReport.contains("Output Content"), "HTML did not contain Output Content")
        assertTrue(htmlReport.contains("Test input"), "HTML did not contain Test input")
        assertTrue(htmlReport.contains("API response"), "HTML did not contain API response")
    }

    
    @Test
    fun generateStandardPipelineHtmlTrace() {
        val trace = generateMockPipelineTrace()
        val visualizer = TraceVisualizer()
        val htmlReport = visualizer.generateHtmlReport(trace)
        
        // Verify it's standard pipeline HTML (not Manifold)
        assertFalse(htmlReport.contains("Manifold"))
        assertTrue(htmlReport.contains("Pipeline"))
        
        // Write to file for manual inspection
        val file = File("standard-pipeline-trace.html")
        file.writeText(htmlReport)
        println("✅ Standard pipeline HTML trace written to: ${file.absolutePath}")
        
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
    }
    
    @Test
    fun generateManifoldHtmlTrace() {
        val trace = generateMockManifoldTrace()
        val visualizer = TraceVisualizer()
        val htmlReport = visualizer.generateHtmlReport(trace)
        
        // Write to file first for inspection
        val file = File("manifold-trace.html")
        file.writeText(htmlReport)
        println("✅ Manifold HTML trace written to: ${file.absolutePath}")
        
        // Verify it's Manifold HTML (check for any Manifold-specific content)
        assertTrue(htmlReport.contains("Manifold") || htmlReport.contains("MANIFOLD"))
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
    }
    
    private fun generateMockPipelineTrace(): List<TraceEvent> {
        val baseTime = System.currentTimeMillis()
        return listOf(
            TraceEvent(timestamp = baseTime, pipeId = "pipe-001", pipeName = "BedrockPipe-Claude", eventType = TraceEventType.PIPE_START, phase = TracePhase.INITIALIZATION, content = MultimodalContent("Test input"), contextSnapshot = null, metadata = mapOf("model" to "claude-3-sonnet")),
            TraceEvent(timestamp = baseTime + 200, pipeId = "pipe-001", pipeName = "BedrockPipe-Claude", eventType = TraceEventType.API_CALL_START, phase = TracePhase.EXECUTION, content = MultimodalContent("API input"), contextSnapshot = null, metadata = mapOf("endpoint" to "bedrock.invoke")),
            TraceEvent(timestamp = baseTime + 1500, pipeId = "pipe-001", pipeName = "BedrockPipe-Claude", eventType = TraceEventType.API_CALL_SUCCESS, phase = TracePhase.EXECUTION, content = MultimodalContent("API response"), contextSnapshot = null, metadata = mapOf("responseTokens" to 300)),
            TraceEvent(timestamp = baseTime + 1700, pipeId = "pipe-001", pipeName = "BedrockPipe-Claude", eventType = TraceEventType.PIPE_SUCCESS, phase = TracePhase.CLEANUP, content = MultimodalContent("Final output"), contextSnapshot = null, metadata = mapOf("success" to true))
        )
    }
    
    private fun generateMockManifoldTrace(): List<TraceEvent> {
        val baseTime = System.currentTimeMillis()
        return listOf(
            TraceEvent(timestamp = baseTime, pipeId = "manifold-001", pipeName = "Manifold-TaskManager", eventType = TraceEventType.MANIFOLD_START, phase = TracePhase.ORCHESTRATION, content = MultimodalContent("Multi-agent task"), contextSnapshot = null, metadata = mapOf("workerCount" to 3)),
            TraceEvent(timestamp = baseTime + 800, pipeId = "manifold-001", pipeName = "Manifold-TaskManager", eventType = TraceEventType.MANAGER_DECISION, phase = TracePhase.ORCHESTRATION, content = MultimodalContent("Agent selection"), contextSnapshot = null, metadata = mapOf("iteration" to 1)),
            TraceEvent(timestamp = baseTime + 900, pipeId = "manifold-001", pipeName = "Manifold-TaskManager", eventType = TraceEventType.AGENT_DISPATCH, phase = TracePhase.AGENT_COMMUNICATION, content = null, contextSnapshot = null, metadata = mapOf("agentName" to "DataAnalyzer")),
            TraceEvent(timestamp = baseTime + 2500, pipeId = "manifold-001", pipeName = "Manifold-TaskManager", eventType = TraceEventType.AGENT_RESPONSE, phase = TracePhase.AGENT_COMMUNICATION, content = MultimodalContent("Analysis results"), contextSnapshot = null, metadata = mapOf("agentName" to "DataAnalyzer")),
            TraceEvent(timestamp = baseTime + 5200, pipeId = "manifold-001", pipeName = "Manifold-TaskManager", eventType = TraceEventType.MANIFOLD_SUCCESS, phase = TracePhase.CLEANUP, content = MultimodalContent("Task complete"), contextSnapshot = null, metadata = mapOf("totalIterations" to 2))
        )
    }
}
