package com.TTT.Debug

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class PipeTracerTest {
    
    @BeforeEach
    fun setup() {
        PipeTracer.enable()
    }
    
    @Test
    fun testStartTrace() {
        val pipelineId = "test-pipeline"
        PipeTracer.startTrace(pipelineId)
        
        val trace = PipeTracer.getTrace(pipelineId)
        assertTrue(trace.isEmpty())
    }
    
    @Test
    fun testAddEvent() {
        val pipelineId = "test-pipeline"
        PipeTracer.startTrace(pipelineId)
        
        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe",
            pipeName = "TestPipe",
            eventType = TraceEventType.PIPE_START,
            phase = TracePhase.INITIALIZATION,
            content = null,
            contextSnapshot = null
        )
        
        PipeTracer.addEvent(pipelineId, event)
        
        val trace = PipeTracer.getTrace(pipelineId)
        assertEquals(1, trace.size)
        assertEquals(event, trace.first())
    }
    
    @Test
    fun testExportTrace() {
        val pipelineId = "test-pipeline"
        PipeTracer.startTrace(pipelineId)
        
        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe",
            pipeName = "TestPipe",
            eventType = TraceEventType.PIPE_SUCCESS,
            phase = TracePhase.CLEANUP,
            content = null,
            contextSnapshot = null
        )
        
        PipeTracer.addEvent(pipelineId, event)
        
        val consoleOutput = PipeTracer.exportTrace(pipelineId, TraceFormat.CONSOLE)
        assertTrue(consoleOutput.contains("TestPipe"))
        assertTrue(consoleOutput.contains("PIPE_SUCCESS"))
    }
    
    @Test
    fun testFailureAnalysis() {
        val pipelineId = "test-pipeline"
        PipeTracer.startTrace(pipelineId)
        
        val successEvent = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "pipe1",
            pipeName = "SuccessfulPipe",
            eventType = TraceEventType.PIPE_SUCCESS,
            phase = TracePhase.CLEANUP,
            content = null,
            contextSnapshot = null
        )
        
        val failureEvent = TraceEvent(
            timestamp = System.currentTimeMillis() + 100,
            pipeId = "pipe2",
            pipeName = "FailedPipe",
            eventType = TraceEventType.PIPE_FAILURE,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null,
            error = RuntimeException("Test failure")
        )
        
        PipeTracer.addEvent(pipelineId, successEvent)
        PipeTracer.addEvent(pipelineId, failureEvent)
        
        val analysis = PipeTracer.getFailureAnalysis(pipelineId)
        assertEquals("SuccessfulPipe", analysis.lastSuccessfulPipe)
        assertEquals(failureEvent, analysis.failurePoint)
        assertEquals("Test failure", analysis.failureReason)
        assertTrue(analysis.suggestedFixes.isNotEmpty())
    }
}