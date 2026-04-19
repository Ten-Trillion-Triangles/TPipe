package com.TTT.MCP.Server

import com.TTT.P2P.P2PConcurrencyMode
import com.TTT.PipeContextProtocol.PcpContext
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manages multiple concurrent MCP client sessions.
 * Uses MCP SDK's server.sessions for client tracking and
 * TPipe's P2PConcurrencyMode for context isolation.
 *
 * @param server The MCP server with sessions
 * @param pcpContext The base PCP context
 * @param concurrencyMode SHARED (one context) or ISOLATED (clone per session)
 */
class McpSessionManager(
    private val server: Server,
    private val pcpContext: PcpContext,
    private val concurrencyMode: P2PConcurrencyMode = P2PConcurrencyMode.SHARED
) {
    private val sessionContexts = mutableMapOf<String, PcpContext>()
    private val liveSessions = mutableMapOf<String, ServerSession>()
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Get all active session IDs.
     */
    fun getActiveSessionIds(): Set<String> = server.sessions.keys.toSet()

    /**
     * Get session count.
     */
    fun getSessionCount(): Int = server.sessions.size

    /**
     * Get context for a specific session.
     * Returns shared context for SHARED mode, or session-specific clone for ISOLATED mode.
     * For ISOLATED mode, creates a new PcpContext instance per session.
     */
    fun getContextForSession(sessionId: String): PcpContext {
        return when (concurrencyMode) {
            P2PConcurrencyMode.SHARED -> pcpContext
            P2PConcurrencyMode.ISOLATED -> sessionContexts.getOrPut(sessionId) {
                createIsolatedContext()
            }
            else -> pcpContext // Default fallback for unknown modes
        }
    }

    /**
     * Create a fresh PcpContext for isolated session mode.
     * Factory method that subclasses can override for custom context creation.
     */
    protected open fun createIsolatedContext(): PcpContext {
        // Create new context with same base configuration
        // In a full implementation, this might clone specific fields from pcpContext
        return PcpContext(cinit = false)
    }

    /**
     * Get client info for a specific session.
     */
    fun getClientInfoForSession(sessionId: String): String? {
        return server.sessions[sessionId]?.clientVersion?.name
    }

    /**
     * Register lifecycle callbacks for connect/disconnect.
     * Note: The MCP SDK's onConnect/onClose don't provide sessionId directly,
     * so this uses global callbacks. For session-specific tracking, use
     * getActiveSessionIds() and getClientInfoForSession().
     */
    fun registerLifecycleCallbacks(
        onConnect: (sessionId: String, clientInfo: String?) -> Unit,
        onDisconnect: (sessionId: String) -> Unit
    ) {
        // FIX S4: Use Set snapshot diff instead of count diff
        var previousSessionIds = server.sessions.keys.toSet()

        server.onConnect {
            scope.launch {
                // Find newly connected session(s) using set diff
                val currentSessionIds = server.sessions.keys.toSet()
                val newSessionIds = currentSessionIds - previousSessionIds
                newSessionIds.forEach { sessionId ->
                    val session = server.sessions[sessionId]!!
                    // Register per-session onClose callback for precise sessionId tracking
                    liveSessions[sessionId] = session
                    session.onClose {
                        scope.launch {
                            sessionContexts.remove(sessionId)
                            liveSessions.remove(sessionId)
                            onDisconnect(sessionId)
                        }
                    }
                    val clientInfo = session.clientVersion?.name
                    onConnect(sessionId, clientInfo)
                    if (concurrencyMode == P2PConcurrencyMode.ISOLATED) {
                        sessionContexts.getOrPut(sessionId) { createIsolatedContext() }
                    }
                }
                previousSessionIds = currentSessionIds
            }
        }

        server.onClose {
            scope.launch {
                // Per-session onClose already cleaned up sessionContexts and liveSessions.
                // Clear any orphaned entries just in case.
                val currentSessionIds = server.sessions.keys.toSet()
                sessionContexts.keys.removeAll { it !in currentSessionIds }
                liveSessions.keys.removeAll { it !in currentSessionIds }
            }
        }
    }

    /**
     * Remove a session's context (cleanup).
     */
    fun removeSessionContext(sessionId: String) {
        sessionContexts.remove(sessionId)
    }

    /**
     * Clear all session contexts.
     */
    fun clearAllContexts() {
        sessionContexts.clear()
    }

    /**
     * Get all sessions as a map of sessionId to ServerSession.
     */
    fun getSessions(): Map<String, ServerSession> = server.sessions
}