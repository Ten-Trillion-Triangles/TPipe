package com.TTT.Pipe

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for the new completion-callback surface on StreamingCallbackManager.
 * Pins the contract that subscribers registered via addCompleteCallback fire
 * exactly once when emitCompleteToAll() is invoked, and that completion
 * callbacks are isolated from chunk-callback errors.
 */
class StreamingCallbackManagerCompleteTest {

    @Test
    fun `emitCompleteToAll fires every registered complete callback exactly once`()
    {
        val manager = StreamingCallbackManager()
        var firstCount = 0
        var secondCount = 0
        manager.addCompleteCallback { firstCount++ }
        manager.addCompleteCallback { secondCount++ }

        runBlocking { manager.emitCompleteToAll() }

        assertEquals(1, firstCount)
        assertEquals(1, secondCount)
    }

    @Test
    fun `removeCompleteCallback deregisters a previously-added complete callback`()
    {
        val manager = StreamingCallbackManager()
        var count = 0
        val lambda: suspend () -> Unit = { count++ }
        manager.addCompleteCallback(lambda)
        manager.removeCompleteCallback(lambda)

        runBlocking { manager.emitCompleteToAll() }

        assertEquals(0, count)
    }

    @Test
    fun `emitCompleteToAll with no subscribers is a no-op`()
    {
        val manager = StreamingCallbackManager()
        // Must not throw.
        runBlocking { manager.emitCompleteToAll() }
    }

    @Test
    fun `completion callbacks fire independently of chunk callbacks`()
    {
        val manager = StreamingCallbackManager()
        var chunks = 0
        var completes = 0
        manager.addCallback { chunks++ }
        manager.addCompleteCallback { completes++ }

        runBlocking {
            manager.emitToAll("a")
            manager.emitToAll("b")
            manager.emitCompleteToAll()
        }

        assertEquals(2, chunks)
        assertEquals(1, completes)
    }

    @Test
    fun `completion callback exceptions are isolated by onError handler`()
    {
        val manager = StreamingCallbackManager(
            onError = { _, _ -> /* swallow */ }
        )
        var firstCountTracked = 0
        var secondCount = 0
        manager.addCompleteCallback {
            firstCountTracked++
            throw IllegalStateException("boom")
        }
        manager.addCompleteCallback { secondCount++ }

        runBlocking { manager.emitCompleteToAll() }

        assertEquals(1, firstCountTracked, "First throwing callback should still have run")
        assertEquals(1, secondCount, "Subsequent callback should still fire after one throws")
    }
}