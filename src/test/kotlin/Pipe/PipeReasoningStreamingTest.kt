package com.TTT.Pipe

import com.TTT.Util.ReasoningStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD spec for the base-level reasoning streaming hooks added to [Pipe]
 * (reasoning-stream normalization plan, task-1).
 *
 * Contract under test:
 * - [Pipe.emitReasoningStreamingStart] / [Pipe.emitReasoningStreamingChunk] /
 *   [Pipe.emitReasoningStreamingEnd] deliver internal model reasoning deltas to
 *   the SAME streaming callback channel as text, wrapped in a reasoning segment
 *   delimited by the "
" open and "
" close markers (the wire
 *   convention Ollama think models already ship, so one split regex covers both
 *   streamed and legacy embedded reasoning).
 * - Delivery is gated on [Pipe.streamModelReasoning] (default true, Bedrock
 *   parity). Flag off: zero reasoning bytes reach the callback.
 * - Segment state is idempotent: repeated start emits one open marker; repeated
 *   end emits one close marker; end without start emits nothing.
 * - [Pipe.emitReasoningStreamingChunk] auto-opens the segment when needed.
 * - [Pipe.emitStreamEnd] closes an open reasoning segment as a safety net before
 *   firing the completion callbacks.
 * - [Pipe.setStreamModelReasoning] is a builder returning this Pipe.
 */
class PipeReasoningStreamingTest
{

    /**
     * Test double exposing the protected base hooks for direct driving.
     * Mirrors the house MockTokenPipe pattern: minimal abstract overrides,
     * no generateContent override so the base wrapper runs.
     */
    private class TestPipe : Pipe()
    {
        override fun truncateModuleContext(): Pipe = this

        override suspend fun generateText(promptInjector: String): String = "no-op"

        // Expose the protected hooks
        suspend fun callReasoningStart() = emitReasoningStreamingStart()
        suspend fun callReasoningChunk(chunk: String) = emitReasoningStreamingChunk(chunk)
        suspend fun callReasoningEnd() = emitReasoningStreamingEnd()
        suspend fun callEmitStreamEnd() = emitStreamEnd()
        suspend fun callTextChunk(chunk: String) = emitStreamingChunk(chunk)
    }

    private fun newPipe(flag: Boolean = true) =
        TestPipe().setPipeName("reasoning-stream-test").setStreamModelReasoning(flag) as TestPipe

    /** Collector over the real StreamingCallbackManager, capturing chunk order. */
    private class ChunkCollector
    {
        val chunks = mutableListOf<String>()
        var completeFired = 0
        val callback: suspend (String) -> Unit = { chunk -> chunks.add(chunk) }
        val completeCallback: suspend () -> Unit = { completeFired++ }
    }

    //==== gating: flag off produces zero reasoning bytes ====

    @Test
    fun flagOffEmitsZeroReasoningBytes() = runBlocking {
        val pipe = newPipe(flag = false)
        val collector = ChunkCollector()
        pipe.obtainStreamingCallbackManager().addCallback(collector.callback)
        pipe.callReasoningStart()
        pipe.callReasoningChunk("thought-a")
        pipe.callReasoningChunk("thought-b")
        pipe.callReasoningEnd()
        assertTrue(collector.chunks.isEmpty(), "flag off must emit nothing, got ${collector.chunks}")
    }

    @Test
    fun flagOffEmitStreamEndStillFiresCompletion() = runBlocking {
        val pipe = newPipe(flag = false)
        val collector = ChunkCollector()
        pipe.obtainStreamingCallbackManager().addCallback(collector.callback)
        pipe.obtainStreamingCallbackManager().addCompleteCallback(collector.completeCallback)
        pipe.callReasoningStart()
        pipe.callReasoningChunk("thought")
        pipe.callEmitStreamEnd()
        assertTrue(collector.chunks.isEmpty())
        assertEquals(1, collector.completeFired)
    }

    //==== gating: flag on (default) delivers delimited reasoning ====

    @Test
    fun flagOnDeliversReasoningWrappedInDelimiter() = runBlocking {
        val pipe = newPipe()
        val collector = ChunkCollector()
        pipe.obtainStreamingCallbackManager().addCallback(collector.callback)
        pipe.callReasoningStart()
        pipe.callReasoningChunk("thought-a")
        pipe.callReasoningChunk("thought-b")
        pipe.callReasoningEnd()
        assertEquals(
            listOf(ReasoningStream.OPEN_TAG, "thought-a", "thought-b", ReasoningStream.CLOSE_TAG),
            collector.chunks
        )
    }

    @Test
    fun defaultFlagIsTrue() = runBlocking {
        val pipe = TestPipe().setPipeName("default-flag") as TestPipe
        val collector = ChunkCollector()
        pipe.obtainStreamingCallbackManager().addCallback(collector.callback)
        pipe.callReasoningChunk("x")
        assertFalse(collector.chunks.isEmpty(), "default streamModelReasoning must be true (Bedrock parity)")
    }

    //==== segment idempotency ====

    @Test
    fun repeatedStartEmitsOneOpenMarker() = runBlocking {
        val pipe = newPipe()
        val collector = ChunkCollector()
        pipe.obtainStreamingCallbackManager().addCallback(collector.callback)
        pipe.callReasoningStart()
        pipe.callReasoningStart()
        pipe.callReasoningStart()
        assertEquals(listOf(ReasoningStream.OPEN_TAG), collector.chunks)
    }

    @Test
    fun repeatedEndEmitsOneCloseMarker() = runBlocking {
        val pipe = newPipe()
        val collector = ChunkCollector()
        pipe.obtainStreamingCallbackManager().addCallback(collector.callback)
        pipe.callReasoningStart()
        pipe.callReasoningEnd()
        pipe.callReasoningEnd()
        pipe.callReasoningEnd()
        assertEquals(listOf(ReasoningStream.OPEN_TAG, ReasoningStream.CLOSE_TAG), collector.chunks)
    }

    @Test
    fun endWithoutStartEmitsNothing() = runBlocking {
        val pipe = newPipe()
        val collector = ChunkCollector()
        pipe.obtainStreamingCallbackManager().addCallback(collector.callback)
        pipe.callReasoningEnd()
        assertTrue(collector.chunks.isEmpty())
    }

    @Test
    fun chunkWithoutStartAutoOpensSegment() = runBlocking {
        val pipe = newPipe()
        val collector = ChunkCollector()
        pipe.obtainStreamingCallbackManager().addCallback(collector.callback)
        pipe.callReasoningChunk("t")
        assertEquals(listOf(ReasoningStream.OPEN_TAG, "t"), collector.chunks)
    }

    //==== emitStreamEnd safety-net close ====

    @Test
    fun emitStreamEndClosesOpenReasoningSegmentBeforeCompletion() = runBlocking {
        val pipe = newPipe()
        val collector = ChunkCollector()
        pipe.obtainStreamingCallbackManager().addCallback(collector.callback)
        pipe.obtainStreamingCallbackManager().addCompleteCallback(collector.completeCallback)
        pipe.callReasoningStart()
        pipe.callReasoningChunk("t")
        pipe.callEmitStreamEnd()
        assertEquals(listOf(ReasoningStream.OPEN_TAG, "t", ReasoningStream.CLOSE_TAG), collector.chunks)
        assertEquals(1, collector.completeFired)
    }

    @Test
    fun explicitEndThenStreamEndDoesNotDoubleClose() = runBlocking {
        val pipe = newPipe()
        val collector = ChunkCollector()
        pipe.obtainStreamingCallbackManager().addCallback(collector.callback)
        pipe.callReasoningStart()
        pipe.callReasoningEnd()
        pipe.callEmitStreamEnd()
        assertEquals(listOf(ReasoningStream.OPEN_TAG, ReasoningStream.CLOSE_TAG), collector.chunks)
    }

    //==== builder contract ====

    @Test
    fun setStreamModelReasoningReturnsPipeForChaining() = runBlocking {
        val pipe = TestPipe()
        val chained = pipe.setStreamModelReasoning(false)
        assertTrue(chained is Pipe)
        val collector = ChunkCollector()
        (chained as TestPipe).obtainStreamingCallbackManager().addCallback(collector.callback)
        chained.callReasoningChunk("x")
        assertTrue(collector.chunks.isEmpty(), "chained setter must have flipped the gate off")
    }

    //==== text chunks are unaffected ====

    @Test
    fun textChunksFlowUnchangedBesideReasoningSegment() = runBlocking {
        val pipe = newPipe()
        val collector = ChunkCollector()
        pipe.obtainStreamingCallbackManager().addCallback(collector.callback)
        // Simulate provider sequencing: reasoning segment, then text chunks
        pipe.callReasoningStart()
        pipe.callReasoningChunk("thinking")
        pipe.callReasoningEnd()
        pipe.callTextChunk("answer")
        assertEquals(
            listOf(ReasoningStream.OPEN_TAG, "thinking", ReasoningStream.CLOSE_TAG, "answer"),
            collector.chunks
        )
    }
}
