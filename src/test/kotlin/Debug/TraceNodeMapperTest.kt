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
    
    private fun createTraceEvent(pipeName: String, eventType: TraceEventType): TraceEvent 
    {
        return TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe",
            pipeName = pipeName,
            eventType = eventType,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null
        )
    }
}
