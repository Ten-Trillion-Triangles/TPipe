package com.TTT.Debug

import com.TTT.Pipe.MultimodalContent
import com.TTT.Context.ContextWindow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TraceEventTest {
    
    @Test
    fun testTraceEventCreation() {
        val content = MultimodalContent("test content")
        val context = ContextWindow()
        val metadata = mapOf("key" to "value")
        
        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe-id",
            pipeName = "TestPipe",
            eventType = TraceEventType.PIPE_START,
            phase = TracePhase.INITIALIZATION,
            content = content,
            contextSnapshot = context,
            metadata = metadata
        )
        
        assertEquals("test-pipe-id", event.pipeId)
        assertEquals("TestPipe", event.pipeName)
        assertEquals(TraceEventType.PIPE_START, event.eventType)
        assertEquals(TracePhase.INITIALIZATION, event.phase)
        assertEquals(content, event.content)
        assertEquals(context, event.contextSnapshot)
        assertEquals(metadata, event.metadata)
        assertNull(event.error)
    }
    
    @Test
    fun testTraceEventWithError() {
        val error = RuntimeException("Test error")
        
        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe-id",
            pipeName = "TestPipe",
            eventType = TraceEventType.PIPE_FAILURE,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null,
            error = error
        )
        
        assertEquals(TraceEventType.PIPE_FAILURE, event.eventType)
        assertEquals(error, event.error)
        assertEquals("Test error", event.error?.message)
    }
}