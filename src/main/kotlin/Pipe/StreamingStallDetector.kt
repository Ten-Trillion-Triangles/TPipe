package com.TTT.Pipe

import kotlin.math.sqrt

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
@kotlinx.serialization.Serializable
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

/**
 * Streaming stall detector that tracks token arrival timestamps and uses
 * statistical deviation to detect abnormally long silences.
 *
 * Algorithm:
 * 1. Maintain a ring buffer of the last [StreamingStallConfig.windowSize] inter-token intervals.
 * 2. Maintain running sum and sum-of-squares for O(1) population mean and variance.
 * 3. After [StreamingStallConfig.warmupTokenCount] tokens, the statistical test arms.
 * 4. Each new token arrival tests:
 *      silence > max(mean + stddevMultiplier × stddev, stallMinSilenceMs)
 *    YES → stall detected, fire [onStall] callback with a [StallEvent].
 *    NO  → update rolling stats and continue.
 *
 * The [StreamingStallConfig.stallMinSilenceMs] floor prevents false positives during temporary
 * network hiccups that don't violate the statistical profile.
 * The statistical test (μ + kσ) handles models with variable but bounded throughput.
 * The [StreamingStallConfig.warmupTokenCount] prevents false positives during cold-start throughput ramp-up.
 *
 * @param pipeName Display name for this detector's pipe (used in stall events and logging).
 * @param config Detection thresholds and window parameters.
 * @param onStall Callback fired when a stall is detected. The retry is handled
 *                separately via [PipeTimeoutManager.handleStallSignal] (see Pipe.kt); this
 *                callback is for notification/monitoring only.
 */
class StreamingStallDetector(
    val pipeName: String,
    val config: StreamingStallConfig = StreamingStallConfig(),
    private val onStall: StallCallback
) {
    private val intervalBuffer: LongArray = LongArray(config.windowSize)
    private var bufferIndex = 0
    private var bufferCount = 0

    // Running aggregates for O(1) population mean and variance.
    // sumIntervals = Σx, sumSquares = Σx².
    private var sumIntervals: Long = 0L
    private var sumSquares: Long = 0L

    private var lastTokenTimestamp: Long = -1L
    private var tokensSeen: Int = 0
    private var lastInterval: Long = 0L

    /**
     * Whether the statistical stall test is armed.
     * True once [StreamingStallConfig.warmupTokenCount] intervals have been observed.
     */
    internal val isArmed: Boolean get() = bufferCount >= config.warmupTokenCount

    /**
     * Returns a snapshot of the current rolling statistics. Test-only visibility.
     */
    internal fun getStatsSnapshot(): StatsSnapshot {
        val n = bufferCount.coerceAtLeast(1)
        val mean = sumIntervals.toDouble() / n
        val variance = (sumSquares.toDouble() / n) - (mean * mean)
        val stddev = if (variance > 0.0) sqrt(variance) else 0.0
        return StatsSnapshot(mean = mean, stddev = stddev, n = bufferCount, isArmed = isArmed)
    }

    /**
     * Called by the streaming callback for each token received.
     *
     * @param tokenText The token text (ignored for timing — only the timestamp matters).
     * @param timestamp Epoch milliseconds at which this token arrived.
     */
    fun onTokenReceived(tokenText: String, timestamp: Long) {
        if (lastTokenTimestamp < 0L) {
            // First token — record but don't compute an interval or test.
            lastTokenTimestamp = timestamp
            tokensSeen++
            return
        }

        val interval = timestamp - lastTokenTimestamp
        // Capture the previous token's timestamp before updating so checkForStall
        // can compute silence = currentTimestamp - lastArrivedTokenTimestamp.
        val previousTimestamp = lastTokenTimestamp
        lastTokenTimestamp = timestamp
        lastInterval = interval
        tokensSeen++

        // Update ring buffer and aggregates.
        if (bufferCount < config.windowSize) {
            bufferCount++
        } else {
            // Evict oldest value from aggregates before overwriting.
            val oldest = intervalBuffer[bufferIndex]
            sumIntervals -= oldest
            sumSquares -= oldest * oldest
        }
        intervalBuffer[bufferIndex] = interval
        sumIntervals += interval
        sumSquares += interval * interval
        bufferIndex = (bufferIndex + 1) % config.windowSize

        if (isArmed) {
            checkForStall(timestamp, previousTimestamp)
        }
    }

    private fun checkForStall(currentTimestamp: Long, previousTokenTimestamp: Long) {
        val silenceMs = currentTimestamp - previousTokenTimestamp
        val n = bufferCount.coerceAtLeast(1)
        val mean = sumIntervals.toDouble() / n
        val variance = (sumSquares.toDouble() / n) - (mean * mean)
        val stddev = if (variance > 0.0) sqrt(variance) else 0.0
        val threshold = maxOf(mean + config.stddevMultiplier * stddev, config.stallMinSilenceMs.toDouble())

        if (silenceMs > threshold) {
            onStall(
                StallEvent(
                    pipeName = pipeName,
                    elapsedMs = currentTimestamp,
                    tokensSeen = tokensSeen,
                    lastTokenTimestamp = previousTokenTimestamp,
                    silenceMs = silenceMs,
                    expectedIntervalMs = mean,
                    actualIntervalMs = lastInterval,
                    stddevMultiplier = config.stddevMultiplier,
                    retryAttempt = 0
                )
            )
        }
    }
}