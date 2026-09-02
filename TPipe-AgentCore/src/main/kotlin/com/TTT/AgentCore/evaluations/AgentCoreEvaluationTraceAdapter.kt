package com.TTT.AgentCore.evaluations

import com.TTT.Debug.TraceEvent

/** Converts TPipe trace events to a redacted, evaluation-friendly map. */
object AgentCoreEvaluationTraceAdapter {
    /**
     * Convert traces without exporting prompt/context content by default.
     * Metadata already present on a trace is retained for evaluator labels.
     */
    fun toRecords(events: Iterable<TraceEvent>, includeContent: Boolean = false): List<Map<String, Any?>> =
        events.map { event ->
            buildMap {
                put("traceId", event.pipeId)
                put("eventId", event.id)
                put("eventType", event.eventType.name)
                put("phase", event.phase.name)
                put("pipeName", event.pipeName)
                put("timestamp", event.timestamp)
                put("metadata", event.metadata)
                if (includeContent) put("content", event.content?.text.orEmpty())
            }
        }
}
