package com.TTT.Debug

import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Connector

/**
 * Utility for merging trace streams in hierarchical parent-child relationships.
 * Supports containerized merging where child traces bubble up into parent traces.
 */
object TraceStreamMerger 
{
    
    /**
     * Merge traces in hierarchical bubbling: each child merges into its parent.
     * Index 0 = parent, 1 = child, 2 = grandchild, etc.
     * Supports infinite nesting depth.
     * 
     * @param objects Traceable objects in parent->child order
     */
    fun bubbleMerge(vararg objects: Any) 
    {
        if (objects.size < 2) return
        
        // Validate all objects have trace IDs before starting
        val traceIds = objects.mapNotNull { getTraceId(it) }
        if (traceIds.size != objects.size) return
        
        // Bubble from deepest child up to parent
        for (i in objects.size - 1 downTo 1) 
        {
            val child = objects[i]
            val parent = objects[i - 1]
            
            val childId = getTraceId(child)
            val parentId = getTraceId(parent)
            
            // Skip if either trace ID is null
            if (childId == null || parentId == null) continue
            
            val childEvents = PipeTracer.getTrace(childId)
            
            // Get traces - if child is empty, skip merge
            if (childEvents.isEmpty()) continue
            
            // FIXED: Use mergeTrace instead of replaceTrace to preserve parent events
            PipeTracer.mergeTrace(parentId, childEvents)
        }
    }
    
    /**
     * Extract trace ID from various traceable objects.
     * 
     * @param obj The object to extract trace ID from
     * @return Trace ID string or null if not supported
     */
    private fun getTraceId(obj: Any): String? 
    {
        return when (obj) 
        {
            is Pipeline -> obj.getTraceId()
            is Manifold -> obj.getTraceId()
            is Connector -> obj.getTraceId()
            
            else -> null
        }
    }
}
