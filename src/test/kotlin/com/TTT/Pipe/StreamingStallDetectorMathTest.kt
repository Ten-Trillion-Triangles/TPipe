package com.TTT.Pipe

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertTrue
import kotlin.math.sqrt

/**
 * Math verification tests for StreamingStallDetector's rolling statistics.
 * These tests prove the ring buffer, rolling mean, and rolling stddev are
 * correct to machine precision before the stall detection logic is trusted.
 */
class StreamingStallDetectorMathTest {

    // ---------------------------------------------------------------------------
    // Ring buffer correctness
    // ---------------------------------------------------------------------------

    @Test
    fun `ring buffer overwrites oldest value after window is full`()
    {
        // Window=3. Emit tokens at intervals 10,20,30,40,50.
        // After fill: buffer holds [30, 40, 50] (oldest two evicted).
        val detector = StreamingStallDetector(
            pipeName = "ringBufferTest",
            config = StreamingStallConfig(windowSize = 3, warmupTokenCount = 1),
            onStall = { }
        )
        // Token timestamps: 0, 10, 30, 60, 100, 150
        // Intervals:        -, 10, 20, 30, 40, 50
        // Last 3 intervals: [30, 40, 50] → mean = 40
        val timestamps = listOf(0L, 10L, 30L, 60L, 100L, 150L)
        timestamps.forEach { t -> detector.onTokenReceived("x", t) }
        val stats = detector.getStatsSnapshot()
        assertEquals(40.0, stats.mean, 0.001, "Mean of [30,40,50] should be 40")
        assertEquals(3, stats.n)
    }

    @Test
    fun `ring buffer mean is correct at every buffer size`()
    {
        // Window=5. Emit tokens at 100ms intervals starting from t=0.
        // After each token from step=2 onward (1+ interval recorded), mean should be 100ms.
        for (size in 2..7)
        {
            val detector = StreamingStallDetector(
                pipeName = "sizeTest",
                config = StreamingStallConfig(windowSize = 5, warmupTokenCount = 1),
                onStall = { }
            )
            for (step in 1..size)
            {
                val t = (step * 100L)
                detector.onTokenReceived("x", t)
                if (step >= 2)
                {
                    val stats = detector.getStatsSnapshot()
                    assertEquals(100.0, stats.mean, 0.001, "After $step tokens at 100ms, mean should be 100")
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Rolling mean correctness
    // ---------------------------------------------------------------------------

    @Test
    fun `rolling mean of uniform intervals is exact`()
    {
        val detector = StreamingStallDetector(
            pipeName = "uniformMean",
            config = StreamingStallConfig(windowSize = 10, warmupTokenCount = 1),
            onStall = { }
        )
        // Start with t=0 first token, then 50ms intervals thereafter.
        detector.onTokenReceived("x", 0L)
        for (i in 1..20)
        {
            detector.onTokenReceived("x", i * 50L)
            val stats = detector.getStatsSnapshot()
            assertEquals(50.0, stats.mean, 1e-9, "Mean of all-50ms intervals should be exactly 50")
        }
    }

    @Test
    fun `rolling mean of two alternating values converges to their average`()
    {
        // Tokens at: 0, 40, 100, 140, 200, 240, ... → intervals [40,60,40,60,...]
        // Mean should converge to 50.
        val detector = StreamingStallDetector(
            pipeName = "alternatingMean",
            config = StreamingStallConfig(windowSize = 10, warmupTokenCount = 1),
            onStall = { }
        )
        var t = 0L
        for (i in 0..20)
        {
            t = if (i % 2 == 0) t + 40L else t + 60L
            detector.onTokenReceived("x", t)
        }
        val stats = detector.getStatsSnapshot()
        assertEquals(50.0, stats.mean, 0.5, "Mean of alternating 40/60ms should converge to 50")
    }

    @Test
    fun `rolling mean handles small integer intervals without floating point drift`()
    {
        // Intervals [1, 2, 3, 4, 5] → mean = 3.0 exactly.
        val detector = StreamingStallDetector(
            pipeName = "smallInts",
            config = StreamingStallConfig(windowSize = 10, warmupTokenCount = 1),
            onStall = { }
        )
        // Token timestamps: 0, 1, 3, 6, 10, 15 → intervals [1,2,3,4,5]
        val timestamps = listOf(0L, 1L, 3L, 6L, 10L, 15L)
        timestamps.forEach { t -> detector.onTokenReceived("x", t) }
        val stats = detector.getStatsSnapshot()
        assertEquals(3.0, stats.mean, 1e-9, "Mean of [1,2,3,4,5] should be exactly 3.0")
    }

    // ---------------------------------------------------------------------------
    // Rolling standard deviation correctness
    // ---------------------------------------------------------------------------

    @Test
    fun `rolling stddev of uniform intervals is exactly zero`()
    {
        val detector = StreamingStallDetector(
            pipeName = "uniformStddev",
            config = StreamingStallConfig(windowSize = 10, warmupTokenCount = 1),
            onStall = { }
        )
        for (i in 1..20)
        {
            detector.onTokenReceived("x", i * 50L)
            val stats = detector.getStatsSnapshot()
            assertEquals(0.0, stats.stddev, 1e-12, "Stddev of uniform intervals must be exactly 0")
        }
    }

    @Test
    fun `rolling stddev of known sample matches analytical population result`()
    {
        // Intervals [10, 10, 10, 10, 30] → mean = 14, variance = ((4*16)+(256))/5 = 320/5 = 64, stddev = 8.
        val detector = StreamingStallDetector(
            pipeName = "knownSample",
            config = StreamingStallConfig(windowSize = 10, warmupTokenCount = 1),
            onStall = { }
        )
        // Token timestamps: 0, 10, 20, 30, 40, 70 → intervals [10,10,10,10,30]
        val timestamps = listOf(0L, 10L, 20L, 30L, 40L, 70L)
        timestamps.forEach { t -> detector.onTokenReceived("x", t) }
        val stats = detector.getStatsSnapshot()
        val expectedStddev = sqrt(64.0)
        assertEquals(expectedStddev, stats.stddev, 0.001, "Stddev of [10,10,10,10,30] should be 8.0")
        assertEquals(8.0, stats.stddev, 0.001)
    }

    @Test
    fun `rolling stddev uses population variance, not sample variance`()
    {
        // Intervals [10, 20, 30, 40], mean=25.
        // Population variance = ((225+25+25+225)/4) = 125. stddev = sqrt(125) ≈ 11.180.
        // Sample variance = (500/3) ≈ 166.67. stddev = sqrt(166.67) ≈ 12.910.
        // We use population variance (divide by N).
        val detector = StreamingStallDetector(
            pipeName = "popVariance",
            config = StreamingStallConfig(windowSize = 10, warmupTokenCount = 1),
            onStall = { }
        )
        // Token timestamps: 0, 10, 30, 60, 100 → intervals [10, 20, 30, 40]
        val timestamps = listOf(0L, 10L, 30L, 60L, 100L)
        timestamps.forEach { t -> detector.onTokenReceived("x", t) }
        val stats = detector.getStatsSnapshot()
        val expectedPopStddev = sqrt(125.0)
        assertEquals(expectedPopStddev, stats.stddev, 0.001, "Must use population variance (divide by N)")
        assertTrue(stats.stddev < 13.0, "Sample stddev would be ~12.91, must be <13")
        assertTrue(stats.stddev > 11.0, "Population stddev is ~11.18, must be >11")
    }

    @Test
    fun `rolling stddev of large dataset matches analytical reference`()
    {
        // Intervals from normal distribution N(100, 15^2) — java.util.Random seeded for reproducibility.
        // Use a fixed window of 50 and check against the last 50 intervals.
        val rng = java.util.Random(42)
        val detector = StreamingStallDetector(
            pipeName = "largeDataset",
            config = StreamingStallConfig(windowSize = 50, warmupTokenCount = 1),
            onStall = { }
        )
        var t = 0L
        val intervals = mutableListOf<Long>()
        repeat(200) {
            val interval = (100.0 + rng.nextGaussian() * 15).toLong().coerceAtLeast(1)
            intervals.add(interval)
            t += interval
            detector.onTokenReceived("x", t)
        }
        val stats = detector.getStatsSnapshot()
        val last50 = intervals.takeLast(50)
        val analyticalMean = last50.average()
        val analyticalVariance = last50.map { (it - analyticalMean) * (it - analyticalMean) }.average()
        val analyticalStddev = sqrt(analyticalVariance)
        assertEquals(analyticalMean, stats.mean, 0.5, "Mean of last-50 should match analytical")
        assertEquals(analyticalStddev, stats.stddev, 0.5, "Stddev of last-50 should match analytical")
    }

    // ---------------------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------------------

    @Test
    fun `stddev is zero when buffer has fewer than 2 distinct values`()
    {
        // Intervals [50, 50, 50] → mean=50, variance=0, stddev=0.
        val detector = StreamingStallDetector(
            pipeName = "zeroStddev",
            config = StreamingStallConfig(windowSize = 10, warmupTokenCount = 1),
            onStall = { }
        )
        // Token timestamps: 0, 50, 100, 150 → intervals [50, 50, 50]
        val timestamps = listOf(0L, 50L, 100L, 150L)
        timestamps.forEach { t -> detector.onTokenReceived("x", t) }
        val stats = detector.getStatsSnapshot()
        assertEquals(50.0, stats.mean, 0.001)
        assertEquals(0.0, stats.stddev, 1e-12, "Stddev of identical values must be 0")
    }

    @Test
    fun `warmup prevents statistical test before window reaches threshold`()
    {
        val detector = StreamingStallDetector(
            pipeName = "warmupTest",
            config = StreamingStallConfig(windowSize = 50, warmupTokenCount = 20),
            onStall = { }
        )
        // Emit 21 tokens: t=0..20*100ms.
        // 20 intervals recorded → bufferCount=20 → isArmed = true.
        for (i in 0..20)
        {
            detector.onTokenReceived("x", i * 100L)
        }
        assertTrue(detector.isArmed, "Must be armed once bufferCount >= warmupTokenCount")
    }

    @Test
    fun `first token does not contribute interval`()
    {
        // First token at t=0. Second at t=500. Interval = 500ms.
        // After 2 tokens: 1 interval of 500ms, mean = 500.
        val detector = StreamingStallDetector(
            pipeName = "firstToken",
            config = StreamingStallConfig(windowSize = 50, warmupTokenCount = 1),
            onStall = { }
        )
        detector.onTokenReceived("x", 0L)
        detector.onTokenReceived("x", 500L)
        val stats = detector.getStatsSnapshot()
        assertEquals(500.0, stats.mean, 0.001, "First interval = 500ms, mean = 500ms")
        assertEquals(1, stats.n, "Only 1 interval after 2 tokens")
    }
}