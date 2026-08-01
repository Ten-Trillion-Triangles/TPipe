package com.TTT.Pipe

/**
 * Configuration for [StreamingStallDetector].
 *
 * @param windowSize Number of recent inter-token intervals to maintain for rolling statistics.
 *                   Larger windows adapt more slowly but produce more stable statistics.
 *                   Default 50.
 * @param stddevMultiplier Multiplier on standard deviation for the stall threshold
 *                         (μ + stddevMultiplier × σ). 3.0 corresponds to a "99.7% confidence"
 *                         interval under a normal distribution. Default 3.0.
 * @param stallMinSilenceMs Minimum absolute silence (ms) before stall can fire regardless of
 *                          statistics. Guards against false positives during temporary network
 *                          hiccups that don't violate the statistical profile. Default 10000ms.
 * @param maxStallRetries Maximum number of stall retries before giving up. Default 3.
 * @param warmupTokenCount Tokens to observe before arming the statistical stall test.
 *                         Allows the model to reach steady-state throughput before detection
 *                         is active. Default 20.
 */
data class StreamingStallConfig(
    val windowSize: Int = 50,
    val stddevMultiplier: Double = 3.0,
    val stallMinSilenceMs: Long = 10_000L,
    val maxStallRetries: Int = 3,
    val warmupTokenCount: Int = 20
)

/**
 * Event fired when a streaming stall is detected.
 *
 * @param pipeName Name of the pipe experiencing the stall
 * @param elapsedMs Total elapsed time since stream started (epoch ms)
 * @param tokensSeen Number of tokens received before the stall
 * @param lastTokenTimestamp Timestamp of the last token received (epoch ms)
 * @param silenceMs Duration of the current silence that triggered the stall (ms)
 * @param expectedIntervalMs Expected inter-token interval based on rolling mean (ms)
 * @param actualIntervalMs Actual inter-token interval observed (ms)
 * @param stddevMultiplier The stddevMultiplier that was used in the threshold
 * @param retryAttempt Current retry attempt number (0 = first stall, 1 = second, etc.)
 */
data class StallEvent(
    val pipeName: String,
    val elapsedMs: Long,
    val tokensSeen: Int,
    val lastTokenTimestamp: Long,
    val silenceMs: Long,
    val expectedIntervalMs: Double,
    val actualIntervalMs: Long,
    val stddevMultiplier: Double,
    val retryAttempt: Int
)

/**
 * Callback type for stall detection. Called when a stall is algorithmically detected.
 * Listeners can inspect the stall event and take action (log, metric, alert).
 *
 * Note: The retry is handled separately via [PipeTimeoutManager.handleStallSignal].
 * This callback is for notification/monitoring only — it should not throw exceptions,
 * and it should return quickly (heavy work belongs on a separate dispatcher).
 */
typealias StallCallback = (StallEvent) -> Unit

/**
 * Exposes rolling statistics for test verification.
 * Marked `internal` — only accessible within the same module (test code).
 */
internal data class StatsSnapshot(
    val mean: Double,
    val stddev: Double,
    val n: Int,
    val isArmed: Boolean
)