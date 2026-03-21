package com.TTT.Debug

import com.TTT.Config.TPipeConfig
import com.TTT.Pipe.MultimodalContent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class JunctionTraceVisualizationTest
{
    @Test
    fun generateJunctionHtmlTraceShowsParticipantGraph()
    {
        val trace = generateMockJunctionTrace()
        val visualizer = TraceVisualizer()
        val htmlReport = visualizer.generateHtmlReport(trace)
        val traceDir = File(TPipeConfig.getTraceDir(), "Library/junction-trace-visualization")
        if(!traceDir.exists())
        {
            traceDir.mkdirs()
        }
        File(traceDir, "junction.html").writeText(htmlReport)

        assertTrue(htmlReport.contains("TPipe Junction Execution Analysis"))
        assertTrue(htmlReport.contains("Harness State"))
        assertTrue(htmlReport.contains("Round 1 of 1"))
        assertTrue(htmlReport.contains("Orchestration Flow"))
        assertTrue(htmlReport.contains("Participant Interactions"))
        assertTrue(htmlReport.contains("Junction"))
        assertTrue(htmlReport.contains("Participant: Planner"))
        assertTrue(htmlReport.contains("Participant: Actor"))
        assertTrue(htmlReport.contains("Latest Dispatch"))
        assertTrue(htmlReport.contains("Latest Response"))
        assertTrue(htmlReport.contains("Planner response"))
        assertTrue(htmlReport.contains("Actor response"))
        assertTrue(htmlReport.contains("Planner"))
        assertTrue(htmlReport.contains("Actor"))
        assertFalse(htmlReport.contains("Agent Interactions"))
    }

    @Test
    fun standardAndManifoldReportsRemainUnchanged()
    {
        val visualizer = TraceVisualizer()

        val standardHtml = visualizer.generateHtmlReport(generateMockPipelineTrace())
        val traceDir = File(TPipeConfig.getTraceDir(), "Library/junction-trace-visualization")
        if(!traceDir.exists())
        {
            traceDir.mkdirs()
        }
        File(traceDir, "standard.html").writeText(standardHtml)
        assertTrue(standardHtml.contains("TPipe Pipeline Execution Flow"))
        assertTrue(standardHtml.contains("Interactive Flow Graph"))
        assertFalse(standardHtml.contains("Participant Interactions"))

        val manifoldHtml = visualizer.generateHtmlReport(generateMockManifoldTrace())
        File(traceDir, "manifold.html").writeText(manifoldHtml)
        assertTrue(manifoldHtml.contains("TPipe Manifold Execution Analysis"))
        assertTrue(manifoldHtml.contains("Agent Interactions"))
        assertFalse(manifoldHtml.contains("Participant Interactions"))
    }

    private fun generateMockJunctionTrace(): List<TraceEvent>
    {
        val baseTime = System.currentTimeMillis()
        return listOf(
            TraceEvent(
                timestamp = baseTime,
                pipeId = "junction-001",
                pipeName = "Junction-Moderator",
                eventType = TraceEventType.JUNCTION_START,
                phase = TracePhase.ORCHESTRATION,
                content = MultimodalContent("Debate the best approach."),
                contextSnapshot = null,
                metadata = mapOf("participantCount" to 2, "strategy" to "SIMULTANEOUS")
            ),
            TraceEvent(
                timestamp = baseTime + 50,
                pipeId = "junction-001",
                pipeName = "Junction-Moderator",
                eventType = TraceEventType.JUNCTION_PARTICIPANT_DISPATCH,
                phase = TracePhase.AGENT_COMMUNICATION,
                content = MultimodalContent("Planner prompt"),
                contextSnapshot = null,
                metadata = mapOf("participant" to "Planner", "phase" to "PLAN", "cycle" to 1)
            ),
            TraceEvent(
                timestamp = baseTime + 120,
                pipeId = "junction-001",
                pipeName = "Junction-Moderator",
                eventType = TraceEventType.JUNCTION_PARTICIPANT_RESPONSE,
                phase = TracePhase.AGENT_COMMUNICATION,
                content = MultimodalContent("Planner response"),
                contextSnapshot = null,
                metadata = mapOf("participant" to "Planner", "phase" to "PLAN", "cycle" to 1)
            ),
            TraceEvent(
                timestamp = baseTime + 170,
                pipeId = "junction-001",
                pipeName = "Junction-Moderator",
                eventType = TraceEventType.JUNCTION_PARTICIPANT_DISPATCH,
                phase = TracePhase.AGENT_COMMUNICATION,
                content = MultimodalContent("Actor prompt"),
                contextSnapshot = null,
                metadata = mapOf("participant" to "Actor", "phase" to "ACT", "cycle" to 1)
            ),
            TraceEvent(
                timestamp = baseTime + 260,
                pipeId = "junction-001",
                pipeName = "Junction-Moderator",
                eventType = TraceEventType.JUNCTION_PARTICIPANT_RESPONSE,
                phase = TracePhase.AGENT_COMMUNICATION,
                content = MultimodalContent("Actor response"),
                contextSnapshot = null,
                metadata = mapOf("participant" to "Actor", "phase" to "ACT", "cycle" to 1)
            ),
            TraceEvent(
                timestamp = baseTime + 320,
                pipeId = "junction-001",
                pipeName = "Junction-Moderator",
                eventType = TraceEventType.JUNCTION_SUCCESS,
                phase = TracePhase.CLEANUP,
                content = MultimodalContent("Consensus reached."),
                contextSnapshot = null,
                metadata = mapOf("consensusReached" to true)
            ),
            TraceEvent(
                timestamp = baseTime + 340,
                pipeId = "junction-001",
                pipeName = "Junction-Moderator",
                eventType = TraceEventType.JUNCTION_END,
                phase = TracePhase.CLEANUP,
                content = MultimodalContent("Finished."),
                contextSnapshot = null,
                metadata = mapOf(
                    "participantCount" to 2,
                    "strategy" to "SIMULTANEOUS",
                    "round" to 1,
                    "maxRounds" to 1,
                    "consensusReached" to true
                )
            )
        )
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
            ),
            TraceEvent(
                timestamp = baseTime + 250,
                pipeId = "pipe-001",
                pipeName = "BedrockPipe-Claude",
                eventType = TraceEventType.PIPE_SUCCESS,
                phase = TracePhase.CLEANUP,
                content = MultimodalContent("Final output"),
                contextSnapshot = null,
                metadata = mapOf("success" to true)
            )
        )
    }

    private fun generateMockManifoldTrace(): List<TraceEvent>
    {
        val baseTime = System.currentTimeMillis()
        return listOf(
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
                metadata = mapOf("iteration" to 1)
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
    }
}
