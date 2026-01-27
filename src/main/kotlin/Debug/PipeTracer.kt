package com.TTT.Debug

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

object PipeTracer {
    private val traces = ConcurrentHashMap<String, MutableList<TraceEvent>>()
    private var isEnabled = false
    private var maxTraceHistory = 1000
    
    fun enable() {
        isEnabled = true
    }
    
    fun disable() {
        isEnabled = false
    }
    
    fun startTrace(pipelineId: String) {
        if (!isEnabled) return
        traces[pipelineId] = Collections.synchronizedList(mutableListOf())
    }
    
    fun addEvent(pipelineId: String, event: TraceEvent) {
        if (!isEnabled) return
        
        val traceList = traces.getOrPut(pipelineId) { Collections.synchronizedList(mutableListOf()) }
        
        synchronized(traceList) {
            traceList.add(event)
            
            // Maintain max history limit
            if (traceList.size > maxTraceHistory) {
                traceList.removeAt(0)
            }
        }
    }
    
    fun getTrace(pipelineId: String): List<TraceEvent> {
        val list = traces[pipelineId] ?: return emptyList()
        synchronized(list) {
            return list.toList()
        }
    }
    
    fun getAllTraces(): Map<String, List<TraceEvent>> {
        return traces.mapValues { entry ->
            synchronized(entry.value) {
                entry.value.toList()
            }
        }
    }
    
    fun clearTrace(pipelineId: String) {
        traces.remove(pipelineId)
    }
    
    fun replaceTrace(pipelineId: String, events: List<TraceEvent>) {
        if (!isEnabled) return
        traces[pipelineId] = Collections.synchronizedList(events.toMutableList())
    }
    
    fun mergeTrace(pipelineId: String, newEvents: List<TraceEvent>) {
        if (!isEnabled) return
        
        val existingEvents = traces[pipelineId] ?: mutableListOf()
        val allEvents: List<TraceEvent>
        
        synchronized(existingEvents) {
            allEvents = (existingEvents + newEvents)
                .distinctBy { "${it.timestamp}-${it.pipeId}-${it.eventType}" }
                .sortedBy { it.timestamp }
        }
        
        traces[pipelineId] = Collections.synchronizedList(allEvents.toMutableList())
    }
    
    fun exportTrace(pipelineId: String, format: TraceFormat): String {
        val trace = getTrace(pipelineId)
        val visualizer = TraceVisualizer()
        return when (format) {
            TraceFormat.JSON -> exportAsJson(trace)
            TraceFormat.HTML -> visualizer.generateHtmlReport(trace)
            TraceFormat.MARKDOWN -> exportAsMarkdown(trace)
            TraceFormat.CONSOLE -> visualizer.generateConsoleOutput(trace)
        }
    }
    
    fun getFailureAnalysis(pipelineId: String): FailureAnalysis {
        val trace = getTrace(pipelineId)
        val failureEvent = trace.firstOrNull { it.eventType == TraceEventType.PIPE_FAILURE || it.eventType == TraceEventType.API_CALL_FAILURE }
        val lastSuccess = trace.lastOrNull { it.eventType == TraceEventType.PIPE_SUCCESS || it.eventType == TraceEventType.API_CALL_SUCCESS }
        
        val failureReason = failureEvent?.error?.message ?: "Unknown failure"
        val executionPath = trace.map { "${it.pipeName}:${it.eventType}" }
        
        val suggestedFixes = mutableListOf<String>()
        failureEvent?.let { failure ->
            when (failure.eventType) {
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
    
    private fun exportAsJson(trace: List<TraceEvent>): String {
        return try {
            Json.encodeToString(trace)
        } catch (e: Exception) {
            "Error serializing trace: ${e.message}"
        }
    }
    

    
    private fun exportAsMarkdown(trace: List<TraceEvent>): String {
        val md = StringBuilder()
        md.append("# TPipe Trace Report\n\n")
        md.append("| Timestamp | Pipe | Event | Phase | Status |\n")
        md.append("|-----------|------|-------|-------|--------|\n")
        
        trace.forEach { event ->
            val status = when (event.eventType) {
                TraceEventType.PIPE_SUCCESS, TraceEventType.API_CALL_SUCCESS, TraceEventType.VALIDATION_SUCCESS -> "✅ SUCCESS"
                TraceEventType.PIPE_FAILURE, TraceEventType.API_CALL_FAILURE, TraceEventType.VALIDATION_FAILURE -> "❌ FAILURE"
                else -> "ℹ️ INFO"
            }
            md.append("| ${event.timestamp} | ${event.pipeName} | ${event.eventType} | ${event.phase} | $status |\n")
        }
        
        return md.toString()
    }
    

}