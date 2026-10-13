package com.TTT.Pipe

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Cross-generation stall-detector leak — the overlapping-execution race.
 *
 * [PipeStallDetectorLeakTest] pins the sequential case: each execution's
 * detector callback is removed by that execution's own teardown. The live
 * defect (2026-10-08, Round_1_Turn_0 writing-agent kills) is the *overlapping*
 * variant: when a nested execution registers its detector before the outer
 * execution's teardown runs, the shared registration/teardown fields are
 * clobbered by the inner generation and the outer generation's armed
 * detector — holding its own `lastTokenTimestamp` — is never removed. The
 * orphaned detector then measures the inter-generation gap as silence and
 * reports a stall whose anchor predates the generation being judged.
 *
 * These tests fail before the per-execution registration-handle fix; each
 * asserts the post-fix contract.
 */
class PipeStallDetectorCrossGenerationLeakTest
{
    /**
     * Streaming probe that overlaps two detector generations in one public
     * `execute()`: the outer generation's [generateText] runs a nested
     * [execute] mid-stream. The nested execution registers its detector
     * callback while the outer one's is still live; the nested teardown
     * removes only its own callback, so the outer teardown — reading the
     * now-stale shared field — cannot remove the outer callback.
     *
     * @param chunksPerGeneration Chunks emitted per generation. Must exceed
     *        [StreamingStallConfig.warmupTokenCount] so a detector arms.
     * @param gapBeforeFirstChunkMs Simulated gap applied only from the
     *        third generation onward (the probe's own generations 1-2 stay
     *        fast so the overlap itself produces no legitimate stall).
     */
    private class OverlappingGenerationsPipe(
        private val chunksPerGeneration: Int = 25,
        private val gapBeforeFirstChunkMs: Long = 0L
    ) : Pipe()
    {
        private var generationCount = 0
        private var innerExecuted = false

        override fun truncateModuleContext(): Pipe = this

        override suspend fun generateText(promptInjector: String): String
        {
            generationCount++
            if(generationCount >= 3 && gapBeforeFirstChunkMs > 0L)
            {
                delay(gapBeforeFirstChunkMs)
            }

            repeat(chunksPerGeneration / 2)
            {
                emitStreamingChunk("tok")
            }

            if(!innerExecuted)
            {
                // Nested execution — its detector generation registers while
                // this generation's detector callback is still subscribed,
                // then tears down first. The race the fix must absorb.
                innerExecuted = true
                execute(MultimodalContent("inner-generation"))
            }

            repeat(chunksPerGeneration / 2)
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
     * A detector generation must not survive the execution that created it,
     * even when a nested execution registered — and tore down — in between.
     * Before the fix the outer generation's callback is orphaned: after the
     * public execute() returns, the persistent manager still holds it.
     */
    @Test
    fun overlappingExecutions_leaveNoOrphanedDetectorSubscriptions() = runBlocking {
        val pipe = OverlappingGenerationsPipe()
        pipe.setStreamingEnabled(true)
        pipe.enableStallDetector(probeConfig())

        val before = pipe.liveCallbackCount()

        pipe.execute(MultimodalContent("first"))
        val after = pipe.liveCallbackCount()

        assertEquals(
            before, after,
            "The outer generation's detector subscription must be removed even though a " +
                "nested generation registered and tore down in between; " +
                "orphaned subscriptions: before=$before after=$after"
        )
    }

    /**
     * The user-visible symptom of the orphan: a third, healthy generation that
     * begins after a retry-sized gap must not be terminated by the first
     * generation's armed detector measuring the inter-generation gap as
     * silence. Before the fix the orphaned detector (armed during generation
     * 1, lastToken anchored at generation 1's chunks) fires on generation 3's
     * first chunk.
     */
    @Test
    fun orphanedDetectorCannotFabricateStallOnALaterGeneration() = runBlocking {
        val pipe = OverlappingGenerationsPipe(gapBeforeFirstChunkMs = 400L)
        pipe.setStreamingEnabled(true)
        pipe.enableStallDetector(probeConfig())

        var stallFirings = 0
        pipe.setStallCallback { stallFirings++ }

        // The orphan's abort() can surface as a transport failure; the
        // fabrication is what is under test, not the propagation shape.
        pipe.execute(MultimodalContent("first"))
        runCatching { pipe.execute(MultimodalContent("third")) }

        // onStall is dispatched on GlobalScope — let it land.
        delay(500L)

        assertEquals(
            0, stallFirings,
            "An internally healthy generation must not report a stall attributed to a " +
                "previous generation's anchor. Firings: $stallFirings"
        )
    }
}
