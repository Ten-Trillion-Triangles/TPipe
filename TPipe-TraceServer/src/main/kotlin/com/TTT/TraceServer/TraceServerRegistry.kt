package com.TTT.TraceServer

import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

@Serializable
data class TraceSummary(val id: String, val timestamp: Long, val name: String, val status: String)

@Serializable
data class TracePayload(val pipelineId: String, val htmlContent: String, val name: String, val status: String)

object TraceServerRegistry {
    var globalAuthMechanism: (suspend (authHeader: String?) -> Boolean)? = null
    val traces = ConcurrentHashMap<String, TracePayload>()
    val connections = Collections.synchronizedSet(LinkedHashSet<WebSocketSession>())

    fun registerTrace(payload: TracePayload) {
        traces[payload.pipelineId] = payload
    }

    fun getAllSummaries(): List<TraceSummary> {
        return traces.values.map {
            TraceSummary(it.pipelineId, System.currentTimeMillis(), it.name, it.status)
        }.sortedByDescending { it.timestamp }
    }
}
