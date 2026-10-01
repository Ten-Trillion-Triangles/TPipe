package com.TTT.Pipe

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression tests for the stall-detector subscription leak.
 *
 * BUG: [Pipe.executeMultimodal] constructs a fresh [StreamingStallDetector] and
 * registers a chunk callback on the pipe's *persistent* [StreamingCallbackManager]
 * on every execution (Pipe.kt:6861-6883). Nothing ever removes that callback, and
 * [StreamingCallbackManager.addCallback] dedups by reference identity, so each
 * execution leaves behind an additional live, armed detector holding the timestamp
 * of the last chunk it observed.
 *
 * On the next execution the orphaned detectors see the first chunk and compute
 *
 *     silence = now - <stale anchor from the previous execution>
 *
 * which spans the entire retry gap (stream teardown + reconnect). That is a
 * fabricated stall: it is the period during which the pipe was correctly *not*
 * streaming. Because [PipeTimeoutManager.clearStallRetryCount] is never called,
 * the per-pipe retry counter is already terminal, so the fabrication terminates
 * the pipe on the first chunk of a healthy execution.
 *
 * Observed in production (2026-09-29, NeoWritingAgent): a stall fired 1.592s into
 * an execution reporting `silenceMs = 144_009`, and three orphaned detectors fired
 * in the same millisecond with `tokensSeen` 2866 / 5237 / 2855.
 *
 * These tests pin the contract; each one fails before the fix.
 */
class PipeStallDetectorLeakTest
{
    /**
     * Test double for a streaming provider: emits [chunksPerExecution] chunks
     * during [generateText], optionally sleeping [gapBeforeFirstChunkMs] before
     * the first chunk to model the retry/reconnect gap between executions.
     *
     * @param chunksPerExecution Chunks emitted per execution. Must exceed
     *        [StreamingStallConfig.warmupTokenCount] so the detector arms.
     * @param gapBeforeFirstChunkMs Simulated inter-execution gap.
     */
    private class StreamingProbePipe(
        private val chunksPerExecution: Int = 25,
        private val gapBeforeFirstChunkMs: Long = 0L
    ) : Pipe()
    {
        override fun truncateModuleContext(): Pipe = this

        override suspend fun generateText(promptInjector: String): String
        {
            if (gapBeforeFirstChunkMs > 0L)
            {
                delay(gapBeforeFirstChunkMs)
            }
            repeat(chunksPerExecution)
            {
                emitStreamingChunk("tok")
            }
            return "ok"
        }

        /** Exposes the persistent subscription count for leak assertions. */
        fun liveCallbackCount(): Int = obtainStreamingCallbackManager().callbackCount()
    }

    private fun probeConfig() = StreamingStallConfig(
        windowSize = 50,
        stddevMultiplier = 3.0,
        stallMinSilenceMs = 50L,
        maxStallRetries = 3,
        warmupTokenCount = 20
    )

    /**
     * A stall detector's chunk subscription must not survive the execution that
     * created it. Before the fix, every execution adds one and none are removed.
     */
    @Test
    fun `stall detector callback does not leak across executions`() = runBlocking {
        val pipe = StreamingProbePipe()
        pipe.setStreamingEnabled(true)
        pipe.enableStallDetector(probeConfig())

        val before = pipe.liveCallbackCount()

        pipe.execute(MultimodalContent("first"))
        val afterFirst = pipe.liveCallbackCount()

        pipe.execute(MultimodalContent("second"))
        val afterSecond = pipe.liveCallbackCount()

        assertEquals(
            0, afterFirst,
            "After one execution the detector subscription must be removed; " +
                "leaked subscriptions: before=$before afterFirst=$afterFirst"
        )
        assertEquals(
            0, afterSecond,
            "Detector subscriptions must not accumulate across executions; " +
                "before=$before afterFirst=$afterFirst afterSecond=$afterSecond"
        )
    }

    /**
     * The user-visible symptom: a healthy execution must not be terminated by a
     * stall measured from a previous execution's chunks. Two consecutive healthy
     * executions, separated by a retry-sized gap, must produce zero stall events.
     */
    @Test
    fun `no stall fires on the first chunk of a later execution after a retry gap`() = runBlocking {
        // 400ms gap between executions vs a 50ms floor: an orphaned detector
        // holding the previous execution's anchor reports ~400ms of "silence".
        val pipe = StreamingProbePipe(gapBeforeFirstChunkMs = 400L)
        pipe.setStreamingEnabled(true)
        pipe.enableStallDetector(probeConfig())

        var stallFirings = 0
        pipe.setStallCallback { stallFirings++ }

        // The abort() on a (fabricated) stall can surface as a transport failure;
        // the fabrication is what is under test, not the propagation shape.
        runCatching { pipe.execute(MultimodalContent("first")) }
        runCatching { pipe.execute(MultimodalContent("second")) }

        // onStall is dispatched on GlobalScope — let it land.
        delay(500L)

        assertEquals(
            0, stallFirings,
            "An internally healthy execution must not report a stall. A firing here " +
                "means an orphaned detector measured the inter-execution gap as silence."
        )
    }

    /**
     * The per-pipe stall retry counter is keyed on the Pipe object and is only ever
     * incremented, so a pipe that has spent its retry budget is permanently terminal.
     * A successful execution must start from a fresh budget.
     */
    @Test
    fun `stall retry counter resets per execution`() = runBlocking {
        val pipe = StreamingProbePipe()
        pipe.setStreamingEnabled(true)
        pipe.enableStallDetector(probeConfig())

        // Exhaust the budget the way a prior run would have.
        repeat(3) { PipeTimeoutManager.incrementStallRetryCount(pipe) }
        assertEquals(3, PipeTimeoutManager.getStallRetryCount(pipe), "precondition: budget exhausted")

        runCatching { pipe.execute(MultimodalContent("clean run")) }

        assertEquals(
            0, PipeTimeoutManager.getStallRetryCount(pipe),
            "A new execution must start with a fresh stall retry budget; " +
                "otherwise a single earlier stall makes the pipe permanently terminal."
        )
    }
}
