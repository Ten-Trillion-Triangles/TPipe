package com.TTT.Debug

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

class ManifoldTracingTest {
    
    @BeforeEach
    fun setup() {
        PipeTracer.enable()
    }
    
    @AfterEach
    fun cleanup() {
        PipeTracer.disable()
    }
    
    @Test
    fun testManifoldEventTypesExist() {
        // Verify all new Manifold event types are defined
        assertNotNull(TraceEventType.MANIFOLD_START)
        assertNotNull(TraceEventType.MANIFOLD_END)
        assertNotNull(TraceEventType.MANAGER_DECISION)
        assertNotNull(TraceEventType.AGENT_DISPATCH)
        assertNotNull(TraceEventType.P2P_REQUEST_START)
    }
    
    @Test
    fun testManifoldPhasesExist() {
        // Verify all new Manifold phases are defined
        assertNotNull(TracePhase.ORCHESTRATION)
        assertNotNull(TracePhase.AGENT_COMMUNICATION)
        assertNotNull(TracePhase.TASK_MANAGEMENT)
        assertNotNull(TracePhase.P2P_TRANSPORT)
    }
    
    @Test
    fun testEventPriorityMapping() {
        // Test CRITICAL events
        assertEquals(TraceEventPriority.CRITICAL, EventPriorityMapper.getPriority(TraceEventType.MANIFOLD_FAILURE))
        assertEquals(TraceEventPriority.CRITICAL, EventPriorityMapper.getPriority(TraceEventType.AGENT_REQUEST_INVALID))
        
        // Test STANDARD events
        assertEquals(TraceEventPriority.STANDARD, EventPriorityMapper.getPriority(TraceEventType.MANIFOLD_START))
        assertEquals(TraceEventPriority.STANDARD, EventPriorityMapper.getPriority(TraceEventType.MANAGER_DECISION))
        
        // Test DETAILED events
        assertEquals(TraceEventPriority.DETAILED, EventPriorityMapper.getPriority(TraceEventType.TASK_PROGRESS_UPDATE))
        assertEquals(TraceEventPriority.DETAILED, EventPriorityMapper.getPriority(TraceEventType.P2P_TRANSPORT_SEND))
        
        // Test INTERNAL events
        assertEquals(TraceEventPriority.INTERNAL, EventPriorityMapper.getPriority(TraceEventType.MANIFOLD_LOOP_ITERATION))
        assertEquals(TraceEventPriority.INTERNAL, EventPriorityMapper.getPriority(TraceEventType.CONVERSE_HISTORY_UPDATE))
    }
    
    @Test
    fun testVerbosityFiltering() {
        // Test MINIMAL level - only CRITICAL events
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.MANIFOLD_FAILURE, TraceDetailLevel.MINIMAL))
        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.MANIFOLD_START, TraceDetailLevel.MINIMAL))
        
        // Test NORMAL level - CRITICAL + STANDARD events
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.MANIFOLD_FAILURE, TraceDetailLevel.NORMAL))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.MANIFOLD_START, TraceDetailLevel.NORMAL))
        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.TASK_PROGRESS_UPDATE, TraceDetailLevel.NORMAL))
        
        // Test VERBOSE level - CRITICAL + STANDARD + DETAILED events
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.TASK_PROGRESS_UPDATE, TraceDetailLevel.VERBOSE))
        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.MANIFOLD_LOOP_ITERATION, TraceDetailLevel.VERBOSE))
        
        // Test DEBUG level - all events
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.MANIFOLD_LOOP_ITERATION, TraceDetailLevel.DEBUG))
    }
    
    @Test
    fun testExistingEventsPriorityPreserved() {
        // Verify existing event priorities are unchanged
        assertEquals(TraceEventPriority.CRITICAL, EventPriorityMapper.getPriority(TraceEventType.PIPE_FAILURE))
        assertEquals(TraceEventPriority.STANDARD, EventPriorityMapper.getPriority(TraceEventType.PIPE_START))
        assertEquals(TraceEventPriority.DETAILED, EventPriorityMapper.getPriority(TraceEventType.VALIDATION_START))
        assertEquals(TraceEventPriority.INTERNAL, EventPriorityMapper.getPriority(TraceEventType.PIPE_END))
    }
}
