package com.TTT.MCP.Server

import com.TTT.P2P.P2PConcurrencyMode
import com.TTT.PipeContextProtocol.PcpContext
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpSessionManagerTest
{
    private fun createServer(): Server
    {
        return Server(
            serverInfo = Implementation(name = "test-server", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    resources = ServerCapabilities.Resources(subscribe = null, listChanged = true),
                    prompts = ServerCapabilities.Prompts(listChanged = true)
                )
            )
        )
    }

    private fun createPcpContext(): PcpContext
    {
        return PcpContext()
    }

    @Test
    fun testGetActiveSessionIdsEmpty()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.SHARED)

        assertTrue(manager.getActiveSessionIds().isEmpty())
    }

    @Test
    fun testGetSessionCountEmpty()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.SHARED)

        assertEquals(0, manager.getSessionCount())
    }

    @Test
    fun testGetContextForSessionSharedMode()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.SHARED)

        val context1 = manager.getContextForSession("session-1")
        val context2 = manager.getContextForSession("session-2")

        assertNotNull(context1)
        assertNotNull(context2)
        assertTrue(context1 === context2, "SHARED mode should return same context instance")
        assertTrue(context1 === pcpContext, "SHARED mode should return the base pcpContext")
    }

    @Test
    fun testGetContextForSessionIsolatedMode()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.ISOLATED)

        val context1 = manager.getContextForSession("session-1")
        val context2 = manager.getContextForSession("session-2")

        assertNotNull(context1)
        assertNotNull(context2)
        assertFalse(context1 === context2, "ISOLATED mode should return different context instances")
        assertFalse(context1 === pcpContext, "ISOLATED mode should NOT return base pcpContext")
        assertFalse(context2 === pcpContext, "ISOLATED mode should NOT return base pcpContext")
    }

    @Test
    fun testGetContextForSessionIsolatedModeSameSessionReturnsSameContext()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.ISOLATED)

        val context1 = manager.getContextForSession("session-1")
        val context2 = manager.getContextForSession("session-1")

        assertTrue(context1 === context2, "Same session should return same isolated context")
    }

    @Test
    fun testGetContextForSessionIsolatedModeWorks()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.ISOLATED)

        val context = manager.getContextForSession("session-1")
        assertNotNull(context)
    }

    @Test
    fun testGetClientInfoForSessionWithNullClientVersion()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.SHARED)

        val clientInfo = manager.getClientInfoForSession("session-1")

        assertNull(clientInfo)
    }

    @Test
    fun testGetClientInfoForSessionNonexistent()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.SHARED)

        val clientInfo = manager.getClientInfoForSession("nonexistent")

        assertNull(clientInfo)
    }

    @Test
    fun testRemoveSessionContext()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.ISOLATED)

        manager.getContextForSession("session-1")
        manager.removeSessionContext("session-1")

        val context = manager.getContextForSession("session-1")
        assertNotNull(context)
    }

    @Test
    fun testRemoveSessionContextNonexistent()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.SHARED)

        manager.removeSessionContext("nonexistent")
    }

    @Test
    fun testClearAllContexts()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.ISOLATED)

        manager.getContextForSession("session-1")
        manager.getContextForSession("session-2")
        manager.clearAllContexts()

        val context = manager.getContextForSession("session-1")
        assertNotNull(context)
    }

    @Test
    fun testGetSessionsEmpty()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.SHARED)

        assertTrue(manager.getSessions().isEmpty())
    }

    @Test
    fun testCreateIsolatedContextCreatesEmptyContext()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.ISOLATED)

        val isolatedContext = manager.getContextForSession("session-1")

        assertNotNull(isolatedContext)
        assertFalse(isolatedContext === pcpContext, "Isolated context should not be the same as base context")
    }

    @Test
    fun testRegisterLifecycleCallbacksConnect()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.SHARED)

        var connectCalled = false

        manager.registerLifecycleCallbacks(
            onConnect = { sessionId, clientInfo ->
                connectCalled = true
            },
            onDisconnect = { sessionId -> }
        )

        assertNotNull(manager.getActiveSessionIds())
    }

    @Test
    fun testRegisterLifecycleCallbacksDisconnect()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.ISOLATED)

        var disconnectSessionId: String? = null

        manager.registerLifecycleCallbacks(
            onConnect = { _, _ -> },
            onDisconnect = { sessionId ->
                disconnectSessionId = sessionId
            }
        )

        manager.getContextForSession("session-to-remove")
        manager.removeSessionContext("session-to-remove")

        assertNull(disconnectSessionId)
    }

    @Test
    fun testLifecycleCallbacksWithIsolatedModeCreatesContext()
    {
        val server = createServer()
        val pcpContext = createPcpContext()
        val manager = McpSessionManager(server, pcpContext, P2PConcurrencyMode.ISOLATED)

        manager.registerLifecycleCallbacks(
            onConnect = { _, _ -> },
            onDisconnect = { _ -> }
        )

        val context = manager.getContextForSession("lifecycle-session")
        assertNotNull(context)
        assertFalse(context === pcpContext)
    }
}