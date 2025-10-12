package com.TTT

import com.TTT.Debug.*
import com.TTT.Pipe.MultimodalContent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class TraceVisualizationTest {
    
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
            TraceEvent(baseTime, "pipe-001", "BedrockPipe-Claude", TraceEventType.PIPE_START, TracePhase.INITIALIZATION, MultimodalContent("Test input"), null, mapOf("model" to "claude-3-sonnet")),
            TraceEvent(baseTime + 200, "pipe-001", "BedrockPipe-Claude", TraceEventType.API_CALL_START, TracePhase.EXECUTION, MultimodalContent("API input"), null, mapOf("endpoint" to "bedrock.invoke")),
            TraceEvent(baseTime + 1500, "pipe-001", "BedrockPipe-Claude", TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, MultimodalContent("API response"), null, mapOf("responseTokens" to 300)),
            TraceEvent(baseTime + 1700, "pipe-001", "BedrockPipe-Claude", TraceEventType.PIPE_SUCCESS, TracePhase.CLEANUP, MultimodalContent("Final output"), null, mapOf("success" to true))
        )
    }
    
    private fun generateMockManifoldTrace(): List<TraceEvent> {
        val baseTime = System.currentTimeMillis()
        return listOf(
            TraceEvent(baseTime, "manifold-001", "Manifold-TaskManager", TraceEventType.MANIFOLD_START, TracePhase.ORCHESTRATION, MultimodalContent("Multi-agent task"), null, mapOf("workerCount" to 3)),
            TraceEvent(baseTime + 800, "manifold-001", "Manifold-TaskManager", TraceEventType.MANAGER_DECISION, TracePhase.ORCHESTRATION, MultimodalContent("Agent selection"), null, mapOf("iteration" to 1)),
            TraceEvent(baseTime + 900, "manifold-001", "Manifold-TaskManager", TraceEventType.AGENT_DISPATCH, TracePhase.AGENT_COMMUNICATION, null, null, mapOf("agentName" to "DataAnalyzer")),
            TraceEvent(baseTime + 2500, "manifold-001", "Manifold-TaskManager", TraceEventType.AGENT_RESPONSE, TracePhase.AGENT_COMMUNICATION, MultimodalContent("Analysis results"), null, mapOf("agentName" to "DataAnalyzer")),
            TraceEvent(baseTime + 5200, "manifold-001", "Manifold-TaskManager", TraceEventType.MANIFOLD_SUCCESS, TracePhase.CLEANUP, MultimodalContent("Task complete"), null, mapOf("totalIterations" to 2))
        )
    }
}
