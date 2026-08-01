package com.TTT.Pipe

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StreamingStallDetectorTest {

    @Test
    fun `stallDetectorConfig defaults are sensible`() {
        val config = StreamingStallConfig()
        assertEquals(50, config.windowSize)
        assertEquals(3.0, config.stddevMultiplier)
        assertEquals(10_000L, config.stallMinSilenceMs)
        assertEquals(3, config.maxStallRetries)
        assertEquals(20, config.warmupTokenCount)
    }

    @Test
    fun `stallDetectorConfig builder sets all fields`() {
        val config = StreamingStallConfig(
            windowSize = 20,
            stddevMultiplier = 2.5,
            stallMinSilenceMs = 15_000L,
            maxStallRetries = 5,
            warmupTokenCount = 30
        )
        assertEquals(20, config.windowSize)
        assertEquals(2.5, config.stddevMultiplier)
        assertEquals(15_000L, config.stallMinSilenceMs)
        assertEquals(5, config.maxStallRetries)
        assertEquals(30, config.warmupTokenCount)
    }

    @Test
    fun `StallEvent carries correct fields`() {
        val event = StallEvent(
            pipeName = "testPipe",
            elapsedMs = 5000L,
            tokensSeen = 100,
            lastTokenTimestamp = 4900L,
            silenceMs = 12_000L,
            expectedIntervalMs = 100.0,
            actualIntervalMs = 12_000L,
            stddevMultiplier = 3.0,
            retryAttempt = 0
        )
        assertEquals("testPipe", event.pipeName)
        assertEquals(5000L, event.elapsedMs)
        assertEquals(100, event.tokensSeen)
        assertEquals(12_000L, event.silenceMs)
        assertEquals(100.0, event.expectedIntervalMs)
        assertEquals(12_000L, event.actualIntervalMs)
        assertEquals(3.0, event.stddevMultiplier)
        assertEquals(0, event.retryAttempt)
    }
}