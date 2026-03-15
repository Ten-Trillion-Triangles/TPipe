package com.TTT.TraceServer

import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import java.util.UUID

@Serializable
data class TraceSummary(val id: String, val timestamp: Long, val name: String, val status: String)
{
}

@Serializable
data class TracePayload(val pipelineId: String, val htmlContent: String, val name: String, val status: String)
{
}

@Serializable
data class AuthRequest(val key: String)
{
}

@Serializable
data class AuthResponse(val token: String)
{
}

object TraceServerRegistry {
    /**
     * Authentication mechanism for Agents (RemoteTraceDispatcher).
     * Validates the Authorization header on POST /api/traces.
     */
    var agentAuthMechanism: (suspend (authHeader: String?) -> Boolean)? = null

    /**
     * Authentication mechanism for Human Clients (Dashboard).
     * Validates the login payload (e.g. key/password) and returns true if authorized.
     */
    var clientAuthMechanism: (suspend (key: String) -> Boolean)? = null

    // Active client sessions (token -> expirationTimeMillis)
    val clientSessions = ConcurrentHashMap<String, Long>()

    val traces = ConcurrentHashMap<String, TracePayload>()
    val connections = Collections.synchronizedSet(LinkedHashSet<WebSocketSession>())

    /**
     * Registers a new trace payload or updates an existing one by pipeline ID.
     */
    fun registerTrace(payload: TracePayload)
    {
        traces[payload.pipelineId] = payload
    }

    /**
     * Returns a list of all current trace summaries, sorted by timestamp descending.
     */
    fun getAllSummaries(): List<TraceSummary>
    {
        return traces.values.map {
            TraceSummary(it.pipelineId, System.currentTimeMillis(), it.name, it.status)
        }.sortedByDescending { it.timestamp }
    }

    /**
     * Creates a new authenticated client session token valid for 24 hours.
     */
    fun createSession(): String
    {
        val token = UUID.randomUUID().toString()
        // 24 hour session
        clientSessions[token] = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
        return token
    }

    /**
     * Validates if the provided session token is valid and not expired.
     */
    fun validateSession(token: String?): Boolean
    {
        if(token == null) return false
        val expiresAt = clientSessions[token] ?: return false
        if(System.currentTimeMillis() > expiresAt)
        {
            clientSessions.remove(token)
            return false
        }
        return true
    }
}
