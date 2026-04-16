package com.TTT.P2P

import com.TTT.P2P.P2PInterface

/**
 * Emergency kill switch that halts agent execution when token consumption exceeds configured limits.
 *
 * When attached to a [P2PInterface], the kill switch monitors input and output token usage and
 * immediately terminates the agent if either limit is exceeded. The termination is absolute —
 * no retry policies, loop re-entry, or generic exception handlers will intercept it.
 *
 * Usage:
 * ```
 * myAgent.setKillSwitch(KillSwitch(
 *     inputTokenLimit = 100_000,
 *     outputTokenLimit = 50_000
 * ))
 * ```
 *
 * @see KillSwitchContext
 * @see KillSwitchException
 */
data class KillSwitch(
    /** Maximum tokens allowed for input (prompt + context). null = no limit. */
    val inputTokenLimit: Int? = null,
    /** Maximum tokens allowed for output (response + reasoning). null = no limit. */
    val outputTokenLimit: Int? = null,
    /** Callback invoked when the kill switch trips. Default throws [KillSwitchException]. */
    val onTripped: (KillSwitchContext) -> Nothing = { ctx -> throw KillSwitchException(ctx) }
)

/**
 * Context passed to [KillSwitch.onTripped] when the kill switch trips.
 *
 * @param p2pInterface The agent whose kill switch tripped
 * @param inputTokensSpent Input tokens consumed by this node at time of trip
 * @param outputTokensSpent Output tokens consumed by this node at time of trip
 * @param elapsedMs Time elapsed since execution started
 * @param reason Human-readable reason for trip (input_exceeded, output_exceeded)
 * @param accumulatedInputTokens Total input tokens accumulated from root agent down to this node
 * @param accumulatedOutputTokens Total output tokens accumulated from root agent down to this node
 * @param depth Nesting depth in the agent hierarchy (0 = root)
 */
data class KillSwitchContext(
    val p2pInterface: P2PInterface,
    val inputTokensSpent: Int,
    val outputTokensSpent: Int,
    val elapsedMs: Long,
    val reason: String,
    val accumulatedInputTokens: Int = inputTokensSpent,
    val accumulatedOutputTokens: Int = outputTokensSpent,
    val depth: Int = 0
)

/**
 * Exception thrown when a [KillSwitch] trips. This is an uncaught exception that propagates
 * through the entire call chain — it bypasses any retry policies, loop re-entry logic, or
 * generic exception handlers. It is the absolute termination of the agent.
 */
class KillSwitchException(val context: KillSwitchContext) : RuntimeException(
    buildString {
        append("KillSwitch tripped: ${context.reason}")
        append(" | inputTokens=${context.inputTokensSpent}")
        append(" | outputTokens=${context.outputTokensSpent}")
        append(" | elapsedMs=${context.elapsedMs}")
    }
)