package com.TTT.Pipe

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral tests for StreamingStallDetector stall-detection logic.
 * Verifies that the algorithm fires on real stalls and does not fire on slow-but-alive streams.
 */
class StreamingStallDetectorBehaviorTest {

    @Test
    fun `stall fires on 150ms gap post-warmup when statistical test dominates`() {
        // Tokens at 100ms intervals → mean=100ms, stddev≈0.
        // minSilenceMs = 50ms (low floor so statistical test dominates).
        // After warmup: 3σ ≈ 0. threshold = max(100+0, 50) = 100ms.
        // A 150ms gap > 100ms threshold → stall fires.
        val events = mutableListOf<StallEvent>()
        val detector = StreamingStallDetector(
            pipeName = "statTrigger",
            config = StreamingStallConfig(
                windowSize = 50,
                stddevMultiplier = 3.0,
                stallMinSilenceMs = 50L,
                warmupTokenCount = 10
            ),
            onStall = { events.add(it) }
        )
        // Warmup: 15 tokens at 100ms intervals
        for (i in 0..15) { detector.onTokenReceived("x", i * 100L) }
        // Token 17 arrives 150ms late (gap from t=1500 to t=1650 = 150ms)
        detector.onTokenReceived("x", 1650L)
        assertEquals(1, events.size, "150ms gap > max(100+0, 50)=100ms → stall")
    }

    @Test
    fun `stall fires on 12 second silence post-warmup regardless of statistics`() {
        // Fast tokens at 10ms → mean≈10ms, stddev≈0.
        // minSilenceMs=10s. A 12s gap > 10s floor → stall.
        val events = mutableListOf<StallEvent>()
        val detector = StreamingStallDetector(
            pipeName = "floorTrigger",
            config = StreamingStallConfig(
                windowSize = 50,
                stddevMultiplier = 3.0,
                stallMinSilenceMs = 10_000L,
                warmupTokenCount = 20
            ),
            onStall = { events.add(it) }
        )
        for (i in 0..25) { detector.onTokenReceived("x", i * 10L) }
        // Next token 12 seconds later (silence = 12000ms)
        detector.onTokenReceived("x", 25 * 10L + 12_000L)
        assertEquals(1, events.size, "12s silence > 10s floor → stall")
        assertEquals(12_000L, events[0].silenceMs)
    }

    @Test
    fun `no stall during steady throughput for 10 seconds`() {
        // Steady 50ms intervals for 10 seconds = 200 tokens.
        val detector = StreamingStallDetector(
            pipeName = "steady",
            config = StreamingStallConfig(stallMinSilenceMs = 10_000L, warmupTokenCount = 5),
            onStall = { throw AssertionError("Should not fire stall") }
        )
        for (i in 0..200) { detector.onTokenReceived("x", i * 50L) }
        assertTrue(true)
    }

    @Test
    fun `slow but alive model — 3s gap with 10s floor does not fire`() {
        // Slow model: tokens every 2s. 3s gap < 10s floor → no stall.
        val events = mutableListOf<StallEvent>()
        val detector = StreamingStallDetector(
            pipeName = "slowAlive",
            config = StreamingStallConfig(
                windowSize = 50,
                stddevMultiplier = 3.0,
                stallMinSilenceMs = 10_000L,
                warmupTokenCount = 10
            ),
            onStall = { events.add(it) }
        )
        // Warmup: 15 tokens at 2000ms intervals
        for (i in 0..15) { detector.onTokenReceived("x", i * 2000L) }
        // Slow but alive: next token 3s later
        detector.onTokenReceived("x", 15 * 2000L + 3_000L)
        assertEquals(0, events.size, "3s gap < 10s floor → no stall (slow but alive)")
    }

    @Test
    fun `slow model truly dead — 11s gap with 10s floor fires stall`() {
        // Slow model dies: 2s intervals, then 11s silence.
        val events = mutableListOf<StallEvent>()
        val detector = StreamingStallDetector(
            pipeName = "slowDead",
            config = StreamingStallConfig(
                windowSize = 50,
                stddevMultiplier = 3.0,
                stallMinSilenceMs = 10_000L,
                warmupTokenCount = 10
            ),
            onStall = { events.add(it) }
        )
        for (i in 0..15) { detector.onTokenReceived("x", i * 2000L) }
        // Dead: next token 11s later
        detector.onTokenReceived("x", 15 * 2000L + 11_000L)
        assertEquals(1, events.size, "11s > 10s floor → stall")
        assertEquals(11_000L, events[0].silenceMs)
    }

    @Test
    fun `stall event carries correct statistical metadata`() {
        val events = mutableListOf<StallEvent>()
        val detector = StreamingStallDetector(
            pipeName = "metaTest",
            config = StreamingStallConfig(
                windowSize = 50,
                stddevMultiplier = 3.0,
                stallMinSilenceMs = 50L,
                warmupTokenCount = 10
            ),
            onStall = { events.add(it) }
        )
        // Warmup: 15 tokens at 100ms intervals
        for (i in 0..15) { detector.onTokenReceived("x", i * 100L) }
        // Fire stall: gap = 200ms (token 17 expected at 1700ms, arrives at 1900ms)
        detector.onTokenReceived("x", 17 * 100L + 200L)
        assertEquals(1, events.size)
        val ev = events[0]
        assertEquals("metaTest", ev.pipeName)
        assertTrue(ev.tokensSeen >= 15)
        assertTrue(ev.silenceMs >= 200L)
        // Expected interval ≈ 100ms; the stall-causing 400ms gap is in the window's mean.
        // 15 intervals × 100ms + 1 × 400ms = 1900ms / 16 ≈ 118.75ms. Allow up to 120.
        assertTrue(ev.expectedIntervalMs in 95.0..125.0, "Expected interval should be ~100-120ms (got ${ev.expectedIntervalMs})")
        assertEquals(3.0, ev.stddevMultiplier)
    }

    @Test
    fun `no stall before warmup completes`() {
        // warmupTokenCount = 50. Emit 30 tokens with crazy gaps.
        // No stall should fire because the statistical test is not armed.
        val events = mutableListOf<StallEvent>()
        val detector = StreamingStallDetector(
            pipeName = "noStallBeforeWarmup",
            config = StreamingStallConfig(
                windowSize = 50,
                stddevMultiplier = 3.0,
                stallMinSilenceMs = 100L,
                warmupTokenCount = 50
            ),
            onStall = { events.add(it) }
        )
        // 30 tokens with 1-second gaps each
        for (i in 0 until 30) {
            detector.onTokenReceived("x", i * 1000L)
        }
        assertEquals(0, events.size, "No stall should fire before warmup completes")
    }
}