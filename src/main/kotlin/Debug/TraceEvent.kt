package com.TTT.Debug

import com.TTT.Pipe.MultimodalContent
import com.TTT.Context.ContextWindow

data class TraceEvent(
    val timestamp: Long,
    val pipeId: String,
    val pipeName: String,
    val eventType: TraceEventType,
    val phase: TracePhase,
    val content: MultimodalContent?,
    val contextSnapshot: ContextWindow?,
    val metadata: Map<String, Any> = emptyMap(),
    val error: Throwable? = null
)