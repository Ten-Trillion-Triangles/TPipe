package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.P2P.P2PRegistry
import com.TTT.module
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression tests for remote-aware lorebook lock selection.
 */
class ContextWindowRemoteLockTest
{
    /**
     * Verifies that the suspend-native lorebook selection path excludes remotely locked lorebook keys.
     */
    @Test
    fun selectLoreBookContextSuspendHonorsRemoteLocks() = runBlocking {
        val port = ServerSocket(0).use { serverSocket ->
            serverSocket.localPort
        }
        val server = embeddedServer(Netty, port = port, module = module()).start(wait = false)
        val originalConfigDir = TPipeConfig.configDir
        val originalInstanceId = TPipeConfig.instanceID
        val originalRemoteMemoryEnabled = TPipeConfig.remoteMemoryEnabled
        val originalRemoteMemoryUrl = TPipeConfig.remoteMemoryUrl
        val originalRemoteMemoryAuthToken = TPipeConfig.remoteMemoryAuthToken
        val originalUseRemoteMemoryGlobally = TPipeConfig.useRemoteMemoryGlobally
        val originalAuthMechanism = P2PRegistry.globalAuthMechanism
        val isolatedConfigDir = Files.createTempDirectory("tpipe-remote-lock-test").toFile()
        val remoteLockKey = "remote-lock-${System.nanoTime()}"

        try
        {
            TPipeConfig.configDir = isolatedConfigDir.absolutePath
            TPipeConfig.instanceID = "ContextWindowRemoteLockTest-${System.nanoTime()}"
            TPipeConfig.remoteMemoryEnabled = true
            TPipeConfig.remoteMemoryUrl = "http://127.0.0.1:$port"
            TPipeConfig.remoteMemoryAuthToken = ""
            TPipeConfig.useRemoteMemoryGlobally = true
            P2PRegistry.globalAuthMechanism = { true }
            MemoryClient.clearCaches()

            assertIs<MemoryOperationResult.Success<Unit>>(
                MemoryClient.addLock(LockRequest(key = remoteLockKey, lockState = true))
            )
            delay(200)

            val contextWindow = ContextWindow()
            contextWindow.metaData["isLocked"] = true
            contextWindow.addLoreBookEntry(remoteLockKey, "Remote locked lore entry.", 10)
            contextWindow.addLoreBookEntry("sword", "Unlocked lore entry.", 5)

            val suspendSelection = contextWindow.selectLoreBookContextSuspend("$remoteLockKey sword", 100)
            assertFalse(suspendSelection.contains(remoteLockKey))
            assertTrue(suspendSelection.contains("sword"))
        }

        finally
        {
            try
            {
                ContextLock.removeLockSuspend(remoteLockKey, skipRemote = true)
            }
            catch(_: Exception)
            {
            }

            MemoryClient.clearCaches()
            ContextBank.evictAllFromMemory()
            ContextBank.evictAllTodoListsFromMemory()
            ContextBank.clearCache()
            ContextLock.getLockKeys(skipRemote = true).toList().forEach { lockKey ->
                ContextLock.removeLock(lockKey, skipRemote = true)
            }
            TPipeConfig.configDir = originalConfigDir
            TPipeConfig.instanceID = originalInstanceId
            TPipeConfig.remoteMemoryEnabled = originalRemoteMemoryEnabled
            TPipeConfig.remoteMemoryUrl = originalRemoteMemoryUrl
            TPipeConfig.remoteMemoryAuthToken = originalRemoteMemoryAuthToken
            TPipeConfig.useRemoteMemoryGlobally = originalUseRemoteMemoryGlobally
            P2PRegistry.globalAuthMechanism = originalAuthMechanism
            server.stop(1000, 2000)
            isolatedConfigDir.deleteRecursively()
        }
    }
}
