package com.TTT.P2P

import com.TTT.Pipe.Pipe
import com.TTT.Pipe.StreamingCallbackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies callback-specific recursive removal without using a provider or live
 * model call.
 */
class P2PRecursiveStreamingRemovalTest
{
    @Test
    fun callbackRegistrationIsIdentityDeduplicatedUnderConcurrency()
    {
        runBlocking {
            val manager = StreamingCallbackManager()
            val callback: suspend (String) -> Unit = {}
            coroutineScope {
                repeat(100) {
                    launch(Dispatchers.Default) { manager.addCallback(callback) }
                }
            }
            assertEquals(1, manager.callbackCount())
        }
    }

    private class FakePipe : Pipe()
    {
        override fun truncateModuleContext(): Pipe = this

        override suspend fun generateText(promptInjector: String): String = promptInjector

        suspend fun emit(chunk: String)
        {
            emitStreamingChunk(chunk)
        }
    }

    @Test
    fun `removes only the requested callback across a cyclic pipe graph`()
    {
        runBlocking {
            val root = FakePipe()
            val child = FakePipe()
            root.setValidatorPipe(child)
            child.setValidatorPipe(root)

            val removedChunks = mutableListOf<String>()
            val retainedChunks = mutableListOf<String>()
            val removed: suspend (String) -> Unit = { removedChunks.add(it) }
            val retained: suspend (String) -> Unit = { retainedChunks.add(it) }

            root.propagateStreamingCallback(removed)
            root.propagateStreamingCallback(retained)
            root.removeStreamingCallbackRecursive(removed)

            root.emit("root")
            child.emit("child")

            assertTrue(removedChunks.isEmpty())
            assertEquals(listOf("root", "child"), retainedChunks)
        }
    }
}
