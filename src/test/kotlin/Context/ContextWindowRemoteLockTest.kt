package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.P2P.P2PRegistry
import com.TTT.module
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for remote-aware lorebook lock selection.
 */
class ContextWindowRemoteLockTest
{
    /**
     * Verifies that the new suspend-native lorebook selection path honors remote lock state while the legacy sync
     * selection path remains local-only.
     */
    @Test
    fun selectLoreBookContextSuspendHonorsRemoteLocks() = runBlocking {
        val port = ServerSocket(0).use { serverSocket ->
            serverSocket.localPort
        }
        val server = embeddedServer(Netty, port = port, module = module()).start(wait = false)
        val originalRemoteMemoryEnabled = TPipeConfig.remoteMemoryEnabled
        val originalRemoteMemoryUrl = TPipeConfig.remoteMemoryUrl
        val originalUseRemoteMemoryGlobally = TPipeConfig.useRemoteMemoryGlobally
        val originalAuthMechanism = P2PRegistry.globalAuthMechanism
        val remoteLockKey = "remote-lock-${System.nanoTime()}"

        try
        {
            TPipeConfig.remoteMemoryEnabled = true
            TPipeConfig.remoteMemoryUrl = "http://127.0.0.1:$port"
            TPipeConfig.useRemoteMemoryGlobally = true
            P2PRegistry.globalAuthMechanism = { true }

            assertTrue(MemoryClient.addLock(LockRequest(key = remoteLockKey, lockState = true)))
            delay(200)

            val contextWindow = ContextWindow()
            contextWindow.metaData["isLocked"] = true
            contextWindow.addLoreBookEntry(remoteLockKey, "Remote locked lore entry.", 10)
            contextWindow.addLoreBookEntry("sword", "Unlocked lore entry.", 5)

            val syncSelection = contextWindow.selectLoreBookContext("$remoteLockKey sword", 100)
            assertTrue(syncSelection.contains(remoteLockKey))

            val suspendSelection = contextWindow.selectLoreBookContextSuspend("$remoteLockKey sword", 100)
            assertFalse(suspendSelection.contains(remoteLockKey))
            assertTrue(suspendSelection.contains("sword"))
        }

        finally
        {
            try
            {
                MemoryClient.removeLock(remoteLockKey)
            }
            catch(_: Exception)
            {
            }

            TPipeConfig.remoteMemoryEnabled = originalRemoteMemoryEnabled
            TPipeConfig.remoteMemoryUrl = originalRemoteMemoryUrl
            TPipeConfig.useRemoteMemoryGlobally = originalUseRemoteMemoryGlobally
            P2PRegistry.globalAuthMechanism = originalAuthMechanism
            server.stop(1000, 2000)
        }
    }
}
