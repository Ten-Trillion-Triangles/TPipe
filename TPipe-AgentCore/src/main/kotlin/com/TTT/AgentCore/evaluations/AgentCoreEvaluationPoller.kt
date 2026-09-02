package com.TTT.AgentCore.evaluations

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Bounded, cancellation-aware polling helper for asynchronous evaluations. */
object AgentCoreEvaluationPoller {
    /**
     * Poll [load] until [isTerminal] is true or [timeoutMillis] elapses.
     *
     * @param initialDelayMillis Initial delay between status requests.
     * @param maxDelayMillis Maximum exponential-backoff delay.
     */
    suspend fun <T> await(
        timeoutMillis: Long,
        initialDelayMillis: Long = 250L,
        maxDelayMillis: Long = 5_000L,
        load: suspend () -> T,
        isTerminal: (T) -> Boolean
    ): T = withTimeout<T>(timeoutMillis) {
        var delayMillis = initialDelayMillis.coerceAtLeast(1L)
        var terminalResult: Result<T>? = null
        while (terminalResult == null) {
            val status = load()
            if (isTerminal(status)) {
                terminalResult = Result.success(status)
            }
            else {
                delay(delayMillis)
                delayMillis = (delayMillis * 2L).coerceAtMost(maxDelayMillis.coerceAtLeast(delayMillis))
            }
        }
        terminalResult!!.getOrThrow()
    }
}
