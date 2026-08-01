package com.TTT.Pipe

import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * End-to-end integration tests for stall detection. Exercises the full path:
 * StreamingStallDetector → onStall callback → PipeTimeoutManager.handleStallSignal
 * → repeatPipe=true / abort() → retry counter increments.
 *
 * Uses a manually-driven detector (not wired into Pipe.executeMultimodal) to isolate
 * the algorithm from the LLM transport layer.
 */
class StreamingStallDetectorIntegrationTest {

    @Test
    fun `full stall cycle — detector fires, retry path triggers, snapshot is restored`() = runBlocking {
        // Wire the production path: detector + PipeTimeoutManager.
        val pipe = DummyPipe()
        pipe.setStreamingEnabled(true)
        pipe.enableStallDetector(
            config = StreamingStallConfig(
                windowSize = 50,
                stddevMultiplier = 3.0,
                stallMinSilenceMs = 1_000L,
                maxStallRetries = 3,
                warmupTokenCount = 10
            )
        )

        // Track callback invocations
        var callbackInvocations = 0
        var lastEvent: StallEvent? = null
        pipe.setStallCallback { event ->
            callbackInvocations++
            lastEvent = event
        }

        // Manually create a detector and feed it tokens to simulate a stall
        // (since we're not running the full execute() path here).
        val detector = StreamingStallDetector(
            pipeName = pipe.pipeName,
            config = pipe.stallDetectorConfig,
            onStall = { stallEvent ->
                // 1. Fire user callback.
                pipe.stallCallback?.invoke(stallEvent)
                // 2. Compute retry/terminate decision.
                val content = MultimodalContent("test prompt")
                content.saveSnapshot()
                PipeTimeoutManager.handleStallSignal(pipe, content, stallEvent)
            }
        )

        // Warmup: 15 tokens at 100ms intervals
        for (i in 0..15) { detector.onTokenReceived("x", i * 100L) }
        // Stall: 2-second gap (12000ms > floor 1000ms)
        detector.onTokenReceived("x", 15 * 100L + 2_000L)

        // The onStall fires asynchronously via GlobalScope.launch — wait for it.
        val deadline = System.currentTimeMillis() + 1000L
        while (callbackInvocations == 0 && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(10)
        }
        // Wait a little more for handleStallSignal (also async)
        val deadline2 = System.currentTimeMillis() + 1000L
        while (PipeTimeoutManager.getStallRetryCount(pipe) == 0 && System.currentTimeMillis() < deadline2) {
            kotlinx.coroutines.delay(10)
        }

        // Verify: callback fired, retry counter incremented, event captured.
        assertEquals(1, callbackInvocations, "Stall callback should fire once")
        assertNotNull(lastEvent)
        assertEquals(2_000L, lastEvent!!.silenceMs, "Stall event silenceMs should match the gap")
        assertEquals(1, PipeTimeoutManager.getStallRetryCount(pipe), "Retry counter should be 1 after first stall")
    }

    @Test
    fun `pipeline-level enableStallDetector propagates to all child pipes`() = runBlocking {
        val pipeline = Pipeline()
        pipeline.enableStallDetector(
            config = StreamingStallConfig(windowSize = 30, stallMinSilenceMs = 7_500L)
        )

        val pipes = listOf(DummyPipe(), DummyPipe(), DummyPipe())
        pipes.forEach { pipeline.add(it) }

        pipeline.init()

        pipes.forEach { pipe ->
            assertTrue(pipe.enableStallDetector, "All pipes should have stall detection enabled")
            assertEquals(30, pipe.stallDetectorConfig.windowSize)
            assertEquals(7_500L, pipe.stallDetectorConfig.stallMinSilenceMs)
        }
    }

    @Test
    fun `stall retry succeeds via snapshot restore — repeatPipe set`() = runBlocking {
        // Verify that when handleStallSignal finds a snapshot, the returned content
        // has repeatPipe=true so the outer execute() loop re-executes.
        val pipe = DummyPipe()
        pipe.enableStallDetector(StreamingStallConfig(maxStallRetries = 3))

        val content = MultimodalContent("test")
        content.saveSnapshot()

        val stallEvent = StallEvent(
            pipeName = "test", elapsedMs = 1000L, tokensSeen = 50, lastTokenTimestamp = 0L,
            silenceMs = 15_000L, expectedIntervalMs = 100.0, actualIntervalMs = 15_000L,
            stddevMultiplier = 3.0, retryAttempt = 0
        )

        val result = PipeTimeoutManager.handleStallSignal(pipe, content, stallEvent)
        assertTrue(result.repeatPipe, "Snapshot restore must set repeatPipe=true to trigger re-execution")
    }

    @Test
    fun `multiple stall events increment retry counter across iterations`() = runBlocking {
        val pipe = DummyPipe()
        pipe.enableStallDetector(StreamingStallConfig(maxStallRetries = 5))

        val stallEvent = StallEvent(
            pipeName = "multi", elapsedMs = 1000L, tokensSeen = 50, lastTokenTimestamp = 0L,
            silenceMs = 15_000L, expectedIntervalMs = 100.0, actualIntervalMs = 15_000L,
            stddevMultiplier = 3.0, retryAttempt = 0
        )

        repeat(4) {
            val content = MultimodalContent("attempt $it")
            content.saveSnapshot()
            val result = PipeTimeoutManager.handleStallSignal(pipe, content, stallEvent)
            assertTrue(result.repeatPipe, "All 4 attempts (1-4) should retry since maxStallRetries=5")
        }
        assertEquals(4, PipeTimeoutManager.getStallRetryCount(pipe))
    }

    @Test
    fun `stall detector and timeout systems use independent retry counters`() = runBlocking {
        // Verify that timeout retries and stall retries don't share state.
        val pipe = DummyPipe()
        pipe.enablePipeTimeout(duration = 300_000L, autoRetry = true, retryLimit = 5)

        // Trigger a stall retry
        val stallEvent = StallEvent(
            pipeName = "x", elapsedMs = 1000L, tokensSeen = 10, lastTokenTimestamp = 0L,
            silenceMs = 15_000L, expectedIntervalMs = 100.0, actualIntervalMs = 15_000L,
            stddevMultiplier = 3.0, retryAttempt = 0
        )
        val stallContent = MultimodalContent("stall")
        stallContent.saveSnapshot()
        PipeTimeoutManager.handleStallSignal(pipe, stallContent, stallEvent)

        // Trigger a timeout retry (separate counter) — only retries if timeoutStrategy=Retry
        val timeoutContent = MultimodalContent("timeout")
        timeoutContent.saveSnapshot()
        PipeTimeoutManager.handleTimeoutSignal(pipe, timeoutContent)

        // Counters must be independent
        assertEquals(1, PipeTimeoutManager.getStallRetryCount(pipe))
        assertEquals(1, PipeTimeoutManager.getRetryCount(pipe))
    }
}