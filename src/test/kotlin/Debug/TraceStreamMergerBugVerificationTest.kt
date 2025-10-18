package com.TTT.Debug

import com.TTT.Pipeline.Pipeline
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class TraceStreamMergerBugVerificationTest {
    
    @BeforeEach
    fun setup() {
        PipeTracer.enable()
        PipeTracer.clearTrace("parent")
        PipeTracer.clearTrace("child")
        PipeTracer.clearTrace("grandchild")
    }
    
    @Test
    fun `verify bubbleMerge fix - should merge child events into parent`() {
        // Setup parent trace with 2 events
        PipeTracer.startTrace("parent")
        PipeTracer.addEvent("parent", createTraceEvent("parent", "PARENT_START", 1000))
        PipeTracer.addEvent("parent", createTraceEvent("parent", "PARENT_PROCESS", 3000))
        
        // Setup child trace with 2 events
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
        
        // Create Pipeline objects with proper trace IDs
        val parent = Pipeline().apply { 
            // Use reflection to set pipelineId if needed
            try {
                val field = Pipeline::class.java.getDeclaredField("pipelineId")
                field.isAccessible = true
                field.set(this, "parent")
            } catch (e: Exception) {
                // If reflection fails, the fix might handle this differently
                println("Note: Could not set pipelineId via reflection for parent")
            }
        }
        
        val child = Pipeline().apply {
            try {
                val field = Pipeline::class.java.getDeclaredField("pipelineId")
                field.isAccessible = true
                field.set(this, "child")
            } catch (e: Exception) {
                println("Note: Could not set pipelineId via reflection for child")
            }
        }
        
        // Execute bubbleMerge
        TraceStreamMerger.bubbleMerge(parent, child)
        
        // Verify results
        val mergedTrace = PipeTracer.getTrace("parent")
        
        println("\n=== AFTER MERGE ===")
        println("Parent events after merge: ${mergedTrace.size}")
        println("Expected: 4 events (2 parent + 2 child)")
        println("Actual events:")
        mergedTrace.forEach { event ->
            println("  - ${event.pipeId}: ${event.pipeName} at ${event.timestamp}")
        }
        
        // Test if bug is fixed
        if (mergedTrace.size == 4) {
            println("\n✅ BUG FIXED: bubbleMerge() now works correctly!")
            
            // Verify chronological ordering
            val timestamps = mergedTrace.map { it.timestamp }
            assertEquals(listOf(1000L, 2000L, 3000L, 4000L), timestamps, 
                "Events should be sorted chronologically")
            
            // Verify all events are present
            val eventNames = mergedTrace.map { it.pipeName }
            assertTrue(eventNames.contains("PARENT_START"), "Should contain PARENT_START")
            assertTrue(eventNames.contains("PARENT_PROCESS"), "Should contain PARENT_PROCESS")
            assertTrue(eventNames.contains("CHILD_START"), "Should contain CHILD_START")
            assertTrue(eventNames.contains("CHILD_END"), "Should contain CHILD_END")
            
        } else {
            println("\n❌ BUG STILL EXISTS: Expected 4 events, got ${mergedTrace.size}")
            fail("bubbleMerge() is still broken - child events not merged properly")
        }
    }
    
    @Test
    fun `verify three-level hierarchy merge works`() {
        // Setup three-level hierarchy
        PipeTracer.startTrace("parent")
        PipeTracer.addEvent("parent", createTraceEvent("parent", "PARENT_EVENT", 1000))
        
        PipeTracer.startTrace("child")
        PipeTracer.addEvent("child", createTraceEvent("child", "CHILD_EVENT", 2000))
        
        PipeTracer.startTrace("grandchild")
        PipeTracer.addEvent("grandchild", createTraceEvent("grandchild", "GRANDCHILD_EVENT", 3000))
        
        val parent = createPipelineWithId("parent")
        val child = createPipelineWithId("child")
        val grandchild = createPipelineWithId("grandchild")
        
        // Execute three-level merge
        TraceStreamMerger.bubbleMerge(parent, child, grandchild)
        
        val parentTrace = PipeTracer.getTrace("parent")
        val childTrace = PipeTracer.getTrace("child")
        
        println("\n=== THREE-LEVEL MERGE RESULTS ===")
        println("Parent trace size: ${parentTrace.size}")
        println("Child trace size: ${childTrace.size}")
        
        // Verify hierarchical bubbling worked
        assertTrue(parentTrace.size >= 2, "Parent should have at least 2 events after bubbling")
        assertTrue(childTrace.size >= 2, "Child should have at least 2 events after receiving grandchild")
    }
    
    @Test
    fun `verify empty trace handling`() {
        // Setup parent with events, child with no events
        PipeTracer.startTrace("parent")
        PipeTracer.addEvent("parent", createTraceEvent("parent", "PARENT_EVENT", 1000))
        
        PipeTracer.startTrace("child") // Empty child trace
        
        val parent = createPipelineWithId("parent")
        val child = createPipelineWithId("child")
        
        TraceStreamMerger.bubbleMerge(parent, child)
        
        val parentTrace = PipeTracer.getTrace("parent")
        
        // Should handle empty child gracefully
        assertEquals(1, parentTrace.size, "Parent should retain its original event")
        assertEquals("PARENT_EVENT", parentTrace[0].pipeName)
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
    
    private fun createPipelineWithId(id: String): Pipeline {
        return Pipeline().apply {
            try {
                val field = Pipeline::class.java.getDeclaredField("pipelineId")
                field.isAccessible = true
                field.set(this, id)
            } catch (e: Exception) {
                // Fallback if reflection doesn't work
                println("Warning: Could not set pipelineId via reflection for $id")
            }
        }
    }
}
