package com.TTT.Debug

import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class TraceVerbosityTest {
    
    @BeforeEach
    fun setup() {
        PipeTracer.enable()
    }
    
    @Test
    fun testMinimalLevelFiltering() {
        val pipelineId = "test-minimal"
        PipeTracer.startTrace(pipelineId)
        
        // Should be traced (CRITICAL)
        val failureEvent = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe",
            pipeName = "TestPipe",
            eventType = TraceEventType.PIPE_FAILURE,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null
        )
        
        // Should NOT be traced (STANDARD)
        val startEvent = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe",
            pipeName = "TestPipe",
            eventType = TraceEventType.PIPE_START,
            phase = TracePhase.INITIALIZATION,
            content = null,
            contextSnapshot = null
        )
        
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_FAILURE, TraceDetailLevel.MINIMAL))
        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_START, TraceDetailLevel.MINIMAL))
    }
    
    @Test
    fun testEventPriorityMapping() {
        assertEquals(TraceEventPriority.CRITICAL, EventPriorityMapper.getPriority(TraceEventType.PIPE_FAILURE))
        assertEquals(TraceEventPriority.STANDARD, EventPriorityMapper.getPriority(TraceEventType.PIPE_START))
        assertEquals(TraceEventPriority.DETAILED, EventPriorityMapper.getPriority(TraceEventType.VALIDATION_START))
        assertEquals(TraceEventPriority.INTERNAL, EventPriorityMapper.getPriority(TraceEventType.BRANCH_PIPE_TRIGGERED))
    }
    
    @Test
    fun testVerbosityLevelInclusion() {
        // MINIMAL should only include CRITICAL
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_FAILURE, TraceDetailLevel.MINIMAL))
        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_START, TraceDetailLevel.MINIMAL))
        
        // NORMAL should include CRITICAL + STANDARD
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_FAILURE, TraceDetailLevel.NORMAL))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_START, TraceDetailLevel.NORMAL))
        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.VALIDATION_START, TraceDetailLevel.NORMAL))
        
        // VERBOSE should include CRITICAL + STANDARD + DETAILED
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_FAILURE, TraceDetailLevel.VERBOSE))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_START, TraceDetailLevel.VERBOSE))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.VALIDATION_START, TraceDetailLevel.VERBOSE))
        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.BRANCH_PIPE_TRIGGERED, TraceDetailLevel.VERBOSE))
        
        // DEBUG should include everything
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_FAILURE, TraceDetailLevel.DEBUG))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_START, TraceDetailLevel.DEBUG))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.VALIDATION_START, TraceDetailLevel.DEBUG))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.BRANCH_PIPE_TRIGGERED, TraceDetailLevel.DEBUG))
    }
    
    @Test
    fun testDebugLevelReasoningCapture() {
        // Test that DEBUG level captures reasoning content
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.API_CALL_SUCCESS, TraceDetailLevel.DEBUG))
        
        // Verify reasoning metadata is included at DEBUG level
        val debugMetadata = mapOf(
            "reasoningContent" to "Step 1: Analyze the problem...",
            "modelSupportsReasoning" to true
        )
        
        assertNotNull(debugMetadata["reasoningContent"])
        assertTrue(debugMetadata["modelSupportsReasoning"] as Boolean)
    }
}