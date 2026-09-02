package com.TTT.AgentCore

import com.TTT.AgentCore.identity.AgentCoreIdentityAuthProvider
import com.TTT.AgentCore.identity.AgentCoreTokenLoader
import com.TTT.AgentCore.runtime.AgentCoreRuntimeProtocol
import com.TTT.AgentCore.runtime.AgentCoreSessionContext
import com.TTT.AgentCore.runtime.AgentCoreSessionFactory
import com.TTT.AgentCore.runtime.AgentCoreSessionMode
import com.TTT.AgentCore.runtime.AgentCoreSessionRegistry
import com.TTT.P2P.P2PInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentCoreSessionRegistryTest {
    @Test
    fun createsOneRootPerSessionAndSerializesSameSession() = runBlocking {
        var created = 0
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondEntered = false
        val registry = AgentCoreSessionRegistry(
            mode = AgentCoreSessionMode.ISOLATED,
            factory = AgentCoreSessionFactory {
                created++
                FakeP2pInterface()
            }
        )

        val first = async {
            registry.withSession("same", AgentCoreRuntimeProtocol.HTTP) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            registry.withSession("same", AgentCoreRuntimeProtocol.HTTP) {
                secondEntered = true
            }
        }
        delay(50)
        assertFalse(secondEntered)
        releaseFirst.complete(Unit)
        awaitAll(first, second)

        registry.withSession("same", AgentCoreRuntimeProtocol.HTTP) { }
        registry.withSession("different", AgentCoreRuntimeProtocol.HTTP) { }
        assertEquals(2, created)
        assertEquals(2, registry.size())
    }

    @Test
    fun queuedRequestPreventsIdleEviction() = runBlocking {
        var now = 100L
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val registry = AgentCoreSessionRegistry(
            mode = AgentCoreSessionMode.ISOLATED,
            factory = AgentCoreSessionFactory { FakeP2pInterface() },
            now = { now }
        )

        val first = async {
            registry.withSession("queued", AgentCoreRuntimeProtocol.HTTP) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            registry.withSession("queued", AgentCoreRuntimeProtocol.HTTP) {
                secondEntered.complete(Unit)
            }
        }

        delay(50)
        now = 200L
        assertTrue(registry.evictIdle(150L).isEmpty())
        releaseFirst.complete(Unit)
        awaitAll(first, second)
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun sharedEvictionReturnsCanonicalSessionId() = runBlocking {
        val registry = AgentCoreSessionRegistry(
            mode = AgentCoreSessionMode.SHARED,
            factory = AgentCoreSessionFactory { FakeP2pInterface() },
            now = { 100L }
        )

        registry.withSession("canonical", AgentCoreRuntimeProtocol.HTTP) { }

        assertEquals(listOf("canonical"), registry.evictIdle(101L))
        assertEquals(0, registry.size())
    }

    @Test
    fun sharedEvictionRunsCleanupRegisteredUnderAnAlias() = runBlocking {
        var cleanupCalls = 0
        val registry = AgentCoreSessionRegistry(
            mode = AgentCoreSessionMode.SHARED,
            factory = AgentCoreSessionFactory { FakeP2pInterface() },
            now = { 100L }
        )

        registry.withSession("canonical", AgentCoreRuntimeProtocol.HTTP) { }
        registry.withSession("alias", AgentCoreRuntimeProtocol.HTTP) { }
        registry.registerSessionCleanup("alias", "browser") { cleanupCalls++ }

        assertEquals(listOf("canonical"), registry.evictIdle(101L))
        assertEquals(1, cleanupCalls)
    }

    @Test
    fun closeAbortsActiveRootsAndRejectsNewAdmissions() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val aborted = CompletableDeferred<Unit>()
        val root = object : FakeP2pInterface() {
            override suspend fun abortRecursive() {
                aborted.complete(Unit)
                release.complete(Unit)
            }
        }
        val registry = AgentCoreSessionRegistry(
            mode = AgentCoreSessionMode.ISOLATED,
            factory = AgentCoreSessionFactory { root }
        )
        val active = async {
            registry.withSession("closing", AgentCoreRuntimeProtocol.HTTP) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        registry.closeSuspend()
        assertTrue(aborted.isCompleted)
        assertFailsWith<IllegalStateException> {
            registry.withSession("new", AgentCoreRuntimeProtocol.HTTP) { }
        }
        active.await()
    }

    @Test
    fun closeDoesNotAbortIdleRootsAndRunsRegisteredCleanup() = runBlocking {
        var now = 100L
        var cleanupCalls = 0
        val root = object : FakeP2pInterface() {
            var aborted = false
            var callbacksCleared = false

            override suspend fun abortRecursive() {
                aborted = true
            }

            override fun clearStreamingCallbacksRecursive() {
                callbacksCleared = true
            }
        }
        val registry = AgentCoreSessionRegistry(
            mode = AgentCoreSessionMode.ISOLATED,
            factory = AgentCoreSessionFactory { root },
            now = { now }
        )

        registry.withSession("owner", AgentCoreRuntimeProtocol.HTTP) { }
        registry.registerSessionCleanup("owner", "browser") { cleanupCalls++ }
        registry.closeSuspend()

        assertEquals(1, cleanupCalls)
        assertFalse(root.aborted)
        assertTrue(root.callbacksCleared)
        now = 200L
    }

    @Test
    fun identityProviderRefreshesOnlyAfterExpiry() = runBlocking {
        var now = 100L
        var loads = 0
        val provider = AgentCoreIdentityAuthProvider(
            loader = AgentCoreTokenLoader { loads++; "token-$loads" },
            tokenLifetimeMillis = 10L,
            now = { now }
        )

        assertEquals("Bearer token-1", provider.headers()["Authorization"])
        now = 105L
        assertEquals("Bearer token-1", provider.headers()["Authorization"])
        now = 110L
        assertEquals("Bearer token-2", provider.headers()["Authorization"])
        assertEquals(2, loads)
    }

    private open class FakeP2pInterface : P2PInterface {
        override var killSwitch: com.TTT.P2P.KillSwitch? = null
    }
}
