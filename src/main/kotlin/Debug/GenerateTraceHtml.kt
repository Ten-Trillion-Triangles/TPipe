package com.TTT.Debug

import com.TTT.Pipe.MultimodalContent
import java.io.File

fun main() {
    println("Generating HTML trace files...")
    
    // Generate standard pipeline trace
    val pipelineTrace = generateMockPipelineTrace()
    val visualizer = TraceVisualizer()
    val pipelineHtml = visualizer.generateHtmlReport(pipelineTrace)
    
    File("standard-pipeline-trace.html").writeText(pipelineHtml)
    println("✅ Standard pipeline HTML trace written to: standard-pipeline-trace.html")
    
    // Generate Manifold trace
    val manifoldTrace = generateMockManifoldTrace()
    val manifoldHtml = visualizer.generateHtmlReport(manifoldTrace)
    
    File("manifold-trace.html").writeText(manifoldHtml)
    println("✅ Manifold HTML trace written to: manifold-trace.html")
    
    println("🎉 HTML trace generation complete!")
}

private fun generateMockPipelineTrace(): List<TraceEvent> {
    val baseTime = System.currentTimeMillis()
    return listOf(
        TraceEvent(
            timestamp = baseTime,
            pipeId = "pipe-001",
            pipeName = "BedrockPipe-Claude",
            eventType = TraceEventType.PIPE_START,
            phase = TracePhase.INITIALIZATION,
            content = MultimodalContent("Test input content"),
            contextSnapshot = null,
            metadata = mapOf("model" to "claude-3-sonnet", "provider" to "bedrock")
        ),
        TraceEvent(
            timestamp = baseTime + 50,
            pipeId = "pipe-001",
            pipeName = "BedrockPipe-Claude",
            eventType = TraceEventType.CONTEXT_PULL,
            phase = TracePhase.CONTEXT_PREPARATION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("contextSize" to 1024, "truncated" to false)
        ),
        TraceEvent(
            timestamp = baseTime + 100,
            pipeId = "pipe-001",
            pipeName = "BedrockPipe-Claude",
            eventType = TraceEventType.VALIDATION_START,
            phase = TracePhase.PRE_VALIDATION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("validationType" to "input")
        ),
        TraceEvent(
            timestamp = baseTime + 120,
            pipeId = "pipe-001",
            pipeName = "BedrockPipe-Claude",
            eventType = TraceEventType.VALIDATION_SUCCESS,
            phase = TracePhase.PRE_VALIDATION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("validationPassed" to true)
        ),
        TraceEvent(
            timestamp = baseTime + 200,
            pipeId = "pipe-001",
            pipeName = "BedrockPipe-Claude",
            eventType = TraceEventType.API_CALL_START,
            phase = TracePhase.EXECUTION,
            content = MultimodalContent("Processed input for API"),
            contextSnapshot = null,
            metadata = mapOf("endpoint" to "bedrock.invoke", "tokenCount" to 150)
        ),
        TraceEvent(
            timestamp = baseTime + 1500,
            pipeId = "pipe-001",
            pipeName = "BedrockPipe-Claude",
            eventType = TraceEventType.API_CALL_SUCCESS,
            phase = TracePhase.EXECUTION,
            content = MultimodalContent("API response content"),
            contextSnapshot = null,
            metadata = mapOf("responseTokens" to 300, "latency" to 1300)
        ),
        TraceEvent(
            timestamp = baseTime + 1600,
            pipeId = "pipe-001",
            pipeName = "BedrockPipe-Claude",
            eventType = TraceEventType.TRANSFORMATION_START,
            phase = TracePhase.TRANSFORMATION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("transformationType" to "output")
        ),
        TraceEvent(
            timestamp = baseTime + 1650,
            pipeId = "pipe-001",
            pipeName = "BedrockPipe-Claude",
            eventType = TraceEventType.TRANSFORMATION_SUCCESS,
            phase = TracePhase.TRANSFORMATION,
            content = MultimodalContent("Final transformed output"),
            contextSnapshot = null,
            metadata = mapOf("transformationApplied" to true)
        ),
        TraceEvent(
            timestamp = baseTime + 1700,
            pipeId = "pipe-001",
            pipeName = "BedrockPipe-Claude",
            eventType = TraceEventType.PIPE_SUCCESS,
            phase = TracePhase.CLEANUP,
            content = MultimodalContent("Final pipeline output"),
            contextSnapshot = null,
            metadata = mapOf("totalDuration" to 1700, "success" to true)
        )
    )
}

private fun generateMockManifoldTrace(): List<TraceEvent> {
    val baseTime = System.currentTimeMillis()
    return listOf(
        TraceEvent(
            timestamp = baseTime,
            pipeId = "manifold-001",
            pipeName = "Manifold-TaskManager",
            eventType = TraceEventType.MANIFOLD_START,
            phase = TracePhase.ORCHESTRATION,
            content = MultimodalContent("Complex multi-agent task"),
            contextSnapshot = null,
            metadata = mapOf(
                "inputContentSize" to 256,
                "hasBinaryContent" to false,
                "managerPipeline" to "TaskManager",
                "workerCount" to 3
            )
        ),
        TraceEvent(
            timestamp = baseTime + 100,
            pipeId = "manifold-001",
            pipeName = "Manifold-TaskManager",
            eventType = TraceEventType.CONVERSE_HISTORY_UPDATE,
            phase = TracePhase.ORCHESTRATION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("action" to "createInitialHistory")
        ),
        TraceEvent(
            timestamp = baseTime + 200,
            pipeId = "manifold-001",
            pipeName = "Manifold-TaskManager",
            eventType = TraceEventType.MANIFOLD_LOOP_ITERATION,
            phase = TracePhase.ORCHESTRATION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("iteration" to 1, "terminateFlag" to false, "passFlag" to false)
        ),
        TraceEvent(
            timestamp = baseTime + 250,
            pipeId = "manifold-001",
            pipeName = "Manifold-TaskManager",
            eventType = TraceEventType.MANAGER_TASK_ANALYSIS,
            phase = TracePhase.ORCHESTRATION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("managerPipeline" to "TaskManager")
        ),
        TraceEvent(
            timestamp = baseTime + 800,
            pipeId = "manifold-001",
            pipeName = "Manifold-TaskManager",
            eventType = TraceEventType.MANAGER_DECISION,
            phase = TracePhase.ORCHESTRATION,
            content = MultimodalContent("Agent selection decision"),
            contextSnapshot = null,
            metadata = mapOf("responseLength" to 180, "iteration" to 1)
        ),
        TraceEvent(
            timestamp = baseTime + 900,
            pipeId = "manifold-001",
            pipeName = "Manifold-TaskManager",
            eventType = TraceEventType.AGENT_DISPATCH,
            phase = TracePhase.AGENT_COMMUNICATION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("agentName" to "DataAnalyzer", "interactionCount" to 1, "iteration" to 1)
        ),
        TraceEvent(
            timestamp = baseTime + 2500,
            pipeId = "manifold-001",
            pipeName = "Manifold-TaskManager",
            eventType = TraceEventType.AGENT_RESPONSE,
            phase = TracePhase.AGENT_COMMUNICATION,
            content = MultimodalContent("Data analysis results"),
            contextSnapshot = null,
            metadata = mapOf("agentName" to "DataAnalyzer", "responseLength" to 450, "iteration" to 1)
        ),
        TraceEvent(
            timestamp = baseTime + 3300,
            pipeId = "manifold-001",
            pipeName = "Manifold-TaskManager",
            eventType = TraceEventType.AGENT_DISPATCH,
            phase = TracePhase.AGENT_COMMUNICATION,
            content = null,
            contextSnapshot = null,
            metadata = mapOf("agentName" to "ReportGenerator", "interactionCount" to 1, "iteration" to 2)
        ),
        TraceEvent(
            timestamp = baseTime + 4800,
            pipeId = "manifold-001",
            pipeName = "Manifold-TaskManager",
            eventType = TraceEventType.AGENT_RESPONSE,
            phase = TracePhase.AGENT_COMMUNICATION,
            content = MultimodalContent("Generated report"),
            contextSnapshot = null,
            metadata = mapOf("agentName" to "ReportGenerator", "responseLength" to 800, "iteration" to 2)
        ),
        TraceEvent(
            timestamp = baseTime + 5200,
            pipeId = "manifold-001",
            pipeName = "Manifold-TaskManager",
            eventType = TraceEventType.MANIFOLD_SUCCESS,
            phase = TracePhase.CLEANUP,
            content = MultimodalContent("Task completed successfully"),
            contextSnapshot = null,
            metadata = mapOf("totalIterations" to 2, "finalContentSize" to 2048, "agentInteractions" to 2)
        ),
        TraceEvent(
            timestamp = baseTime + 5250,
            pipeId = "manifold-001",
            pipeName = "Manifold-TaskManager",
            eventType = TraceEventType.MANIFOLD_END,
            phase = TracePhase.CLEANUP,
            content = MultimodalContent("Final manifold output"),
            contextSnapshot = null,
            metadata = mapOf("executionComplete" to true)
        )
    )
}
