package com.TTT.Pipe

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull

/**
 * Tests for the Pipe-level stall detector DSL methods.
 */
class PipeStallDetectorDslTest {

    @Test
    fun `enableStallDetector with default config sets flag and defaults`() {
        val pipe = DummyPipe()
        pipe.enableStallDetector()
        assertTrue(pipe.enableStallDetector)
        assertEquals(50, pipe.stallDetectorConfig.windowSize)
        assertEquals(3.0, pipe.stallDetectorConfig.stddevMultiplier)
        assertEquals(10_000L, pipe.stallDetectorConfig.stallMinSilenceMs)
    }

    @Test
    fun `enableStallDetector with custom config sets fields`() {
        val pipe = DummyPipe()
        pipe.enableStallDetector(
            config = StreamingStallConfig(
                windowSize = 20,
                stddevMultiplier = 2.5,
                stallMinSilenceMs = 15_000L,
                maxStallRetries = 5,
                warmupTokenCount = 30
            )
        )
        assertTrue(pipe.enableStallDetector)
        assertEquals(20, pipe.stallDetectorConfig.windowSize)
        assertEquals(2.5, pipe.stallDetectorConfig.stddevMultiplier)
        assertEquals(15_000L, pipe.stallDetectorConfig.stallMinSilenceMs)
        assertEquals(5, pipe.stallDetectorConfig.maxStallRetries)
        assertEquals(30, pipe.stallDetectorConfig.warmupTokenCount)
    }

    @Test
    fun `enableStallDetector with callback registers it`() {
        val pipe = DummyPipe()
        var callbackFired = false
        pipe.enableStallDetector(callback = { callbackFired = true })
        assertNotNull(pipe.stallCallback)
        // Invoke to verify wiring
        pipe.stallCallback!!.invoke(
            StallEvent(
                pipeName = "test",
                elapsedMs = 0L,
                tokensSeen = 0,
                lastTokenTimestamp = 0L,
                silenceMs = 0L,
                expectedIntervalMs = 0.0,
                actualIntervalMs = 0L,
                stddevMultiplier = 3.0,
                retryAttempt = 0
            )
        )
        assertTrue(callbackFired)
    }

    @Test
    fun `setStallCallback registers without enabling detector`() {
        val pipe = DummyPipe()
        var fired = false
        pipe.setStallCallback { fired = true }
        assertNotNull(pipe.stallCallback)
        // enableStallDetector is still false unless enableStallDetector() was called
        assertEquals(false, pipe.enableStallDetector)
        pipe.stallCallback!!.invoke(
            StallEvent(
                pipeName = "x",
                elapsedMs = 0L,
                tokensSeen = 0,
                lastTokenTimestamp = 0L,
                silenceMs = 0L,
                expectedIntervalMs = 0.0,
                actualIntervalMs = 0L,
                stddevMultiplier = 3.0,
                retryAttempt = 0
            )
        )
        assertTrue(fired)
    }

    @Test
    fun `default state has stall detector disabled and no callback`() {
        val pipe = DummyPipe()
        assertEquals(false, pipe.enableStallDetector)
        assertNull(pipe.stallCallback)
        // Default config is still present (just not enabled)
        assertEquals(50, pipe.stallDetectorConfig.windowSize)
    }
}