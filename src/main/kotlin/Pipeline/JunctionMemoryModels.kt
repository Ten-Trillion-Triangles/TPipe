package com.TTT.Pipeline

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import kotlinx.serialization.Serializable

/**
 * Declares the runtime role a Junction memory envelope is preparing for.
 */
@Serializable
enum class JunctionMemoryRole
{
    DISCUSSION_PARTICIPANT,
    DISCUSSION_MODERATOR,
    WORKFLOW_PLANNER,
    WORKFLOW_ACTOR,
    WORKFLOW_VERIFIER,
    WORKFLOW_ADJUSTER,
    WORKFLOW_OUTPUT
}

/**
 * Describes one compact section inside a Junction outbound memory envelope.
 *
 * @param name Human-readable section label.
 * @param tokenBudget Token budget assigned to the section.
 * @param text The section text after budgeting and optional summarization.
 * @param truncated Whether the section was truncated to fit budget.
 * @param tokenCount Approximate token count for the final section text.
 */
@Serializable
data class JunctionMemorySection(
    var name: String = "",
    var tokenBudget: Int = 0,
    var text: String = "",
    var truncated: Boolean = false,
    var tokenCount: Int = 0
)

/**
 * Controls how much memory Junction may expose to downstream participants.
 *
 * Deterministic compaction is the first line of defense. Optional summarization may be enabled as a
 * co-support mechanism, but it is always subordinate to hard budget enforcement.
 */
@Serializable
data class JunctionMemoryPolicy(
    var outboundTokenBudget: Int = 8192,
    var safetyReserveTokens: Int = 256,
    var minimumCriticalBudget: Int = 384,
    var minimumRecentBudget: Int = 256,
    var recentDiscussionEntries: Int = 4,
    var recentOpinionCount: Int = 4,
    var recentPhaseResultCount: Int = 6,
    var enableSummarization: Boolean = false,
    var summaryBudget: Int = 1024,
    var maxSummaryCharacters: Int = 4096,
    @kotlinx.serialization.Transient var summarizer: ((String) -> String)? = null
)

/**
 * Captures the compact outbound memory view Junction prepared for one participant request.
 *
 * @param roleName Target role that will receive this view.
 * @param roleKind The runtime role category for the request.
 * @param resolvedBudget The smallest safe token ceiling Junction resolved for the request.
 * @param availableBudget The budget left after reserving safety margin.
 * @param safetyReserveTokens Tokens reserved for prompt overhead and stability.
 * @param criticalBudget Budget allocated to live state and instructions.
 * @param recentBudget Budget allocated to the recent-history tail.
 * @param summaryBudget Budget allocated to the older-history summary.
 * @param summarizationUsed Whether an optional summarizer was used for the older history tail.
 * @param compacted Whether any part of the request had to be compacted.
 * @param failureReason Populated when Junction cannot safely produce a request envelope.
 * @param sections Ordered outbound sections used to build the prompt.
 * @param contextWindow Compact section payload for prompt-context injection.
 * @param miniBank Page-based prompt payload for receivers that use MiniBank-style injection.
 */
@Serializable
data class JunctionMemoryEnvelope(
    var roleName: String = "",
    var roleKind: JunctionMemoryRole = JunctionMemoryRole.DISCUSSION_PARTICIPANT,
    var resolvedBudget: Int = 0,
    var availableBudget: Int = 0,
    var safetyReserveTokens: Int = 0,
    var criticalBudget: Int = 0,
    var recentBudget: Int = 0,
    var summaryBudget: Int = 0,
    var summarizationUsed: Boolean = false,
    var compacted: Boolean = false,
    var failureReason: String = "",
    var sections: MutableList<JunctionMemorySection> = mutableListOf(),
    var contextWindow: ContextWindow = ContextWindow(),
    var miniBank: MiniBank = MiniBank()
)
