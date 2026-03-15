package com.TTT.Debug

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

object PipeTracer {
    private val traces = ConcurrentHashMap<String, MutableList<TraceEvent>>()
    private var isEnabled = false
    private var maxTraceHistory = 1000
    
    /**
     * Globally enables tracing for all pipelines.
     */
    fun enable()
    {
        isEnabled = true
    }
    
    /**
     * Globally disables tracing for all pipelines.
     */
    fun disable()
    {
        isEnabled = false
    }
    
    /**
     * Initializes a new trace history for a given pipeline ID.
     */
    fun startTrace(pipelineId: String)
    {
        if(!isEnabled) return
        traces[pipelineId] = Collections.synchronizedList(mutableListOf())
    }
    
    /**
     * Adds a new trace event to the history of a given pipeline.
     * Maintains the maximum history limit by removing oldest events.
     */
    fun addEvent(pipelineId: String, event: TraceEvent)
    {
        if(!isEnabled) return
        
        val traceList = traces.getOrPut(pipelineId) { Collections.synchronizedList(mutableListOf()) }
        
        synchronized(traceList) {
            traceList.add(event)
            
            // Maintain max history limit
            if(traceList.size > maxTraceHistory)
            {
                traceList.removeAt(0)
            }
        }
    }
    
    /**
     * Returns a snapshot of the trace history for a given pipeline.
     */
    fun getTrace(pipelineId: String): List<TraceEvent>
    {
        val list = traces[pipelineId] ?: return emptyList()
        synchronized(list) {
            return list.toList()
        }
    }
    
    /**
     * Returns a map containing all traces for all pipelines.
     */
    fun getAllTraces(): Map<String, List<TraceEvent>>
    {
        return traces.mapValues { entry ->
            synchronized(entry.value) {
                entry.value.toList()
            }
        }
    }
    
    /**
     * Clears the trace history for a given pipeline.
     */
    fun clearTrace(pipelineId: String)
    {
        traces.remove(pipelineId)
    }
    
    /**
     * Replaces the entire trace history for a pipeline with a new list of events.
     */
    fun replaceTrace(pipelineId: String, events: List<TraceEvent>)
    {
        if(!isEnabled) return
        traces[pipelineId] = Collections.synchronizedList(events.toMutableList())
    }
    
    /**
     * Merges new events into the existing trace history for a pipeline,
     * ensuring uniqueness and proper chronological ordering.
     */
    fun mergeTrace(pipelineId: String, newEvents: List<TraceEvent>)
    {
        if(!isEnabled) return
        
        val traceList = traces.getOrPut(pipelineId) { Collections.synchronizedList(mutableListOf()) }
        
        synchronized(traceList) {
            val allEvents = (traceList + newEvents)
                .distinctBy { "${it.timestamp}-${it.pipeId}-${it.eventType}" }
                .sortedBy { it.timestamp }

            traceList.clear()
            traceList.addAll(allEvents)
        }
    }
    
    /**
     * Exports the trace history for a pipeline in the specified format.
     * Triggers automatic remote dispatch if configured.
     */
    fun exportTrace(pipelineId: String, format: TraceFormat): String
    {
        val trace = getTrace(pipelineId)
        val visualizer = TraceVisualizer()
        if(RemoteTraceConfig.dispatchAutomatically)
        {
            RemoteTraceDispatcher.dispatchTrace(pipelineId)
        }
        return when(format)
        {
            TraceFormat.JSON -> exportAsJson(trace)
            TraceFormat.HTML -> visualizer.generateHtmlReport(trace)
            TraceFormat.MARKDOWN -> exportAsMarkdown(trace)
            TraceFormat.CONSOLE -> visualizer.generateConsoleOutput(trace)
        }
    }
    
    /**
     * Analyzes the trace history of a pipeline to identify failures,
     * trace execution paths, and suggest potential fixes.
     */
    fun getFailureAnalysis(pipelineId: String): FailureAnalysis
    {
        val trace = getTrace(pipelineId)
        val failureEvent = trace.firstOrNull { it.eventType == TraceEventType.PIPE_FAILURE || it.eventType == TraceEventType.API_CALL_FAILURE }
        val lastSuccess = trace.lastOrNull { it.eventType == TraceEventType.PIPE_SUCCESS || it.eventType == TraceEventType.API_CALL_SUCCESS }
        
        val failureReason = failureEvent?.error?.message ?: "Unknown failure"
        val executionPath = trace.map { "${it.pipeName}:${it.eventType}" }
        
        val suggestedFixes = mutableListOf<String>()
        failureEvent?.let { failure ->
            when(failure.eventType)
            {
                TraceEventType.API_CALL_FAILURE -> {
                    suggestedFixes.add("Check network connectivity and API credentials")
                    suggestedFixes.add("Verify model availability and parameters")
                }
                TraceEventType.VALIDATION_FAILURE -> {
                    suggestedFixes.add("Review validation function logic")
                    suggestedFixes.add("Check input format and content")
                }
                TraceEventType.TRANSFORMATION_FAILURE -> {
                    suggestedFixes.add("Verify transformation function implementation")
                    suggestedFixes.add("Check data format compatibility")
                }
                else -> suggestedFixes.add("Review pipe configuration and input data")
            }
        }
        
        return FailureAnalysis(
            lastSuccessfulPipe = lastSuccess?.pipeName,
            failurePoint = failureEvent,
            failureReason = failureReason,
            contextAtFailure = failureEvent?.contextSnapshot,
            suggestedFixes = suggestedFixes,
            executionPath = executionPath
        )
    }
    
    private fun exportAsJson(trace: List<TraceEvent>): String
    {
        return try {
            Json.encodeToString(trace)
        }
        catch(e: Exception)
        {
            "Error serializing trace: ${e.message}"
        }
    }
    

    
    private fun exportAsMarkdown(trace: List<TraceEvent>): String
    {
        val md = StringBuilder()
        md.append("# TPipe Trace Report\n\n")
        md.append("| Timestamp | Pipe | Event | Phase | Status |\n")
        md.append("|-----------|------|-------|-------|--------|\n")
        
        trace.forEach { event ->
            val status = when(event.eventType)
            {
                TraceEventType.PIPE_SUCCESS, TraceEventType.API_CALL_SUCCESS, TraceEventType.VALIDATION_SUCCESS -> "✅ SUCCESS"
                TraceEventType.PIPE_FAILURE, TraceEventType.API_CALL_FAILURE, TraceEventType.VALIDATION_FAILURE -> "❌ FAILURE"
                else -> "ℹ️ INFO"
            }
            md.append("| ${event.timestamp} | ${event.pipeName} | ${event.eventType} | ${event.phase} | $status |\n")
        }
        
        return md.toString()
    }
    

}