package com.TTT.Pipe

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests that Pipe.emitStreamEnd() routes through StreamingCallbackManager
 * and fires every registered complete callback exactly once.
 *
 * The protected [Pipe.emitStreamEnd] is exposed via [PublicEmitPipe]
 * so the test can invoke it directly from outside the [Pipe] subclass.
 */
class PipeEmitStreamEndTest {

    /**
     * Test pipe that extends Pipe directly (DummyPipe is final) and
     * exposes emitStreamEnd publicly for the test harness to call.
     */
    private class PublicEmitPipe : Pipe()
    {
        override suspend fun generateText(promptInjector: String): String = ""
        override fun truncateModuleContext(): Pipe = this
        suspend fun publicEmitStreamEnd() = emitStreamEnd()
    }

    @Test
    fun `emitStreamEnd fires complete callbacks registered via obtainStreamingCallbackManager`()
    {
        val pipe = PublicEmitPipe()
        var completeCount = 0
        pipe.obtainStreamingCallbackManager().addCompleteCallback { completeCount++ }

        runBlocking { pipe.publicEmitStreamEnd() }

        assertEquals(1, completeCount)
    }

    @Test
    fun `emitStreamEnd does not fire chunk callbacks`()
    {
        val pipe = PublicEmitPipe()
        var chunkCount = 0
        var completeCount = 0
        pipe.obtainStreamingCallbackManager().addCallback { chunkCount++ }
        pipe.obtainStreamingCallbackManager().addCompleteCallback { completeCount++ }

        runBlocking { pipe.publicEmitStreamEnd() }

        assertEquals(0, chunkCount, "emitStreamEnd should not emit chunk callbacks")
        assertEquals(1, completeCount)
    }

    @Test
    fun `emitStreamEnd with no subscribers is a no-op`()
    {
        val pipe = PublicEmitPipe()
        // Must not throw.
        runBlocking { pipe.publicEmitStreamEnd() }
    }
}