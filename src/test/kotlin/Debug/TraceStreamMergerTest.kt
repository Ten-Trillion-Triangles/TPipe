package com.TTT.Debug

import com.TTT.Pipeline.Pipeline
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class TraceStreamMergerTest {
    
    @BeforeEach
    fun setup() {
        PipeTracer.enable()
        // Clear any existing traces
        PipeTracer.clearTrace("parent")
        PipeTracer.clearTrace("child")
    }
    
    @Test
    fun `bubbleMerge FAILS - loses parent events due to clearTrace bug`() {
        // Setup parent trace with events
        PipeTracer.startTrace("parent")
        PipeTracer.addEvent("parent", createTraceEvent("parent", "PARENT_START", 1000))
        PipeTracer.addEvent("parent", createTraceEvent("parent", "PARENT_PROCESS", 3000))
        
        // Setup child trace with events  
        PipeTracer.startTrace("child")
        PipeTracer.addEvent("child", createTraceEvent("child", "CHILD_START", 2000))
        PipeTracer.addEvent("child", createTraceEvent("child", "CHILD_END", 4000))
        
        // Verify initial state
        assertEquals(2, PipeTracer.getTrace("parent").size, "Parent should start with 2 events")
        assertEquals(2, PipeTracer.getTrace("child").size, "Child should start with 2 events")
        
        println("=== BEFORE MERGE ===")
        println("Parent events: ${PipeTracer.getTrace("parent").size}")
        PipeTracer.getTrace("parent").forEach { event ->
            println("  - ${event.pipeId}: ${event.pipeName} at ${event.timestamp}")
        }
        println("Child events: ${PipeTracer.getTrace("child").size}")
        PipeTracer.getTrace("child").forEach { event ->
            println("  - ${event.pipeId}: ${event.pipeName} at ${event.timestamp}")
        }
        
        // Create Pipeline objects - use reflection to set pipelineId
        val parent = Pipeline()
        val child = Pipeline()
        
        // Use reflection to set the private pipelineId field
        val pipelineIdField = Pipeline::class.java.getDeclaredField("pipelineId")
        pipelineIdField.isAccessible = true
        pipelineIdField.set(parent, "parent")
        pipelineIdField.set(child, "child")
        
        // Execute bubbleMerge
        TraceStreamMerger.bubbleMerge(parent, child)
        
        // THE BUG: Parent trace gets cleared and loses its original events!
        val mergedTrace = PipeTracer.getTrace("parent")
        
        println("\n=== AFTER MERGE ===")
        println("Parent events after merge: ${mergedTrace.size}")
        println("Expected: 4 events (2 parent + 2 child)")
        println("Actual events:")
        mergedTrace.forEach { event ->
            println("  - ${event.pipeId}: ${event.pipeName} at ${event.timestamp}")
        }
        
        // This assertion will FAIL, proving the bug
        if (mergedTrace.size != 4) {
            println("\n🚨 BUG CONFIRMED: bubbleMerge() lost events!")
            println("Expected 4 events, got ${mergedTrace.size}")
            println("The clearTrace() call destroys the merged data!")
        }
        
        // Show the bug in action
        assertTrue(mergedTrace.size < 4, "BUG: bubbleMerge should have 4 events but has ${mergedTrace.size}")
    }
    
    private fun createTraceEvent(pipeId: String, eventName: String, timestamp: Long): TraceEvent {
        return TraceEvent(
            timestamp = timestamp,
            pipeId = pipeId,
            pipeName = eventName,
            eventType = TraceEventType.PIPE_START,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null
        )
    }
}
