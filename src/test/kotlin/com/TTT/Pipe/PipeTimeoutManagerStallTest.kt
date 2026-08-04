package com.TTT.Pipe

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Tests for PipeTimeoutManager.handleStallSignal().
 * Validates the retry path: incrementing stall retry counter, restoring from snapshot,
 * setting repeatPipe=true on success, and terminating after max retries exceeded.
 */
class PipeTimeoutManagerStallTest {

    @Test
    fun `handleStallSignal increments stall retry count and returns repeatPipe content`()
    {
        val pipe = DummyPipe()
        pipe.enableStallDetector(StreamingStallConfig(maxStallRetries = 3))

        val content = MultimodalContent("test prompt")
        content.saveSnapshot()

        val stallEvent = StallEvent(
            pipeName = "testPipe",
            elapsedMs = 5000L,
            tokensSeen = 50,
            lastTokenTimestamp = 4900L,
            silenceMs = 12_000L,
            expectedIntervalMs = 100.0,
            actualIntervalMs = 12_000L,
            stddevMultiplier = 3.0,
            retryAttempt = 0
        )

        val result = PipeTimeoutManager.handleStallSignal(pipe, content, stallEvent)
        assertTrue(result.repeatPipe, "Result should have repeatPipe=true to trigger pipe re-execution")
        assertEquals(1, PipeTimeoutManager.getStallRetryCount(pipe), "Retry count should be 1 after first stall")
    }

    @Test
    fun `handleStallSignal without snapshot terminates content`()
    {
        val pipe = DummyPipe()
        pipe.enableStallDetector(StreamingStallConfig(maxStallRetries = 3))

        val content = MultimodalContent("test prompt")
        // No saveSnapshot() call.

        val stallEvent = StallEvent(
            pipeName = "testPipe",
            elapsedMs = 5000L,
            tokensSeen = 50,
            lastTokenTimestamp = 4900L,
            silenceMs = 12_000L,
            expectedIntervalMs = 100.0,
            actualIntervalMs = 12_000L,
            stddevMultiplier = 3.0,
            retryAttempt = 0
        )

        val result = PipeTimeoutManager.handleStallSignal(pipe, content, stallEvent)
        assertTrue(result.terminatePipeline, "Result should be terminated when no snapshot available")
        // Retry counter is still incremented even on snapshot failure (per mirror with handleTimeoutSignal)
        assertEquals(1, PipeTimeoutManager.getStallRetryCount(pipe))
    }

    @Test
    fun `handleStallSignal terminates after maxStallRetries exceeded`()
    {
        val pipe = DummyPipe()
        pipe.enableStallDetector(StreamingStallConfig(maxStallRetries = 2))

        val stallEvent = StallEvent(
            pipeName = "testPipe",
            elapsedMs = 5000L,
            tokensSeen = 50,
            lastTokenTimestamp = 4900L,
            silenceMs = 12_000L,
            expectedIntervalMs = 100.0,
            actualIntervalMs = 12_000L,
            stddevMultiplier = 3.0,
            retryAttempt = 0
        )

        // With maxStallRetries=2, attempts 1 and 2 retry (attempts < 2), attempt 3 terminates.
        // Attempt 1: 0 < 2 → retry, count=1
        val content1 = MultimodalContent("attempt 1")
        content1.saveSnapshot()
        val r1 = PipeTimeoutManager.handleStallSignal(pipe, content1, stallEvent)
        assertTrue(r1.repeatPipe, "First stall should retry")
        assertEquals(1, PipeTimeoutManager.getStallRetryCount(pipe))

        // Attempt 2: 1 < 2 → retry, count=2
        val content2 = MultimodalContent("attempt 2")
        content2.saveSnapshot()
        val r2 = PipeTimeoutManager.handleStallSignal(pipe, content2, stallEvent)
        assertTrue(r2.repeatPipe, "Second stall (attempt 1) should still retry")
        assertEquals(2, PipeTimeoutManager.getStallRetryCount(pipe))

        // Attempt 3: 2 < 2 is false → terminates
        val content3 = MultimodalContent("attempt 3")
        content3.saveSnapshot()
        val r3 = PipeTimeoutManager.handleStallSignal(pipe, content3, stallEvent)
        assertTrue(r3.terminatePipeline, "Third stall (attempt 2, equals maxStallRetries) must be terminated")
        assertEquals(2, PipeTimeoutManager.getStallRetryCount(pipe))
    }

    @Test
    fun `clearStallRetryCount resets counter`()
    {
        val pipe = DummyPipe()
        pipe.enableStallDetector(StreamingStallConfig(maxStallRetries = 5))

        val content = MultimodalContent("test")
        content.saveSnapshot()
        val stallEvent = StallEvent(
            pipeName = "x", elapsedMs = 0L, tokensSeen = 0, lastTokenTimestamp = 0L,
            silenceMs = 0L, expectedIntervalMs = 0.0, actualIntervalMs = 0L,
            stddevMultiplier = 3.0, retryAttempt = 0
        )

        PipeTimeoutManager.handleStallSignal(pipe, content, stallEvent)
        assertEquals(1, PipeTimeoutManager.getStallRetryCount(pipe))

        PipeTimeoutManager.clearStallRetryCount(pipe)
        assertEquals(0, PipeTimeoutManager.getStallRetryCount(pipe))
    }
}