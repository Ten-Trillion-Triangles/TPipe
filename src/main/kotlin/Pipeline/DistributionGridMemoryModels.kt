package com.TTT.Pipeline

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Pipe.TruncationSettings
import kotlinx.serialization.Serializable

/**
 * Describes one compact section included in an outbound grid memory envelope.
 *
 * @param name Human-readable section label.
 * @param tokenBudget Token budget assigned to the section.
 * @param text Final text after budgeting and optional summarization.
 * @param truncated Whether the section was truncated to fit budget.
 * @param tokenCount Approximate token count for the final section text.
 */
@Serializable
data class DistributionGridMemorySection(
    var name: String = "",
    var tokenBudget: Int = 0,
    var text: String = "",
    var truncated: Boolean = false,
    var tokenCount: Int = 0
)

/**
 * Controls how much memory a node may retain locally and expose to downstream peers.
 *
 * Deterministic compaction is the primary budget control. Optional summarization may assist when enabled,
 * but it must always remain subordinate to hard budget enforcement.
 *
 * @param outboundTokenBudget Hard token budget for outbound memory shaping.
 * @param safetyReserveTokens Tokens reserved for prompt overhead and stability.
 * @param minimumCriticalBudget Minimum budget for critical instructions and live state.
 * @param minimumRecentBudget Minimum budget for recent execution history.
 * @param maxExecutionNotes Maximum number of execution-note entries kept in the envelope.
 * @param maxHopRecords Maximum number of hop records retained for outbound sharing.
 * @param enableSummarization Whether optional summarization is allowed.
 * @param summaryBudget Budget reserved for the summarized older-history tail.
 * @param maxSummaryCharacters Maximum character count allowed in the summary text.
 * @param redactContentSections Whether content sections should be redacted before outbound handoff.
 * @param redactTraceMetadata Whether trace metadata should be removed from outbound memory views.
 * @param summarizer Optional summarizer callback used only when summarization is enabled.
 */
@Serializable
data class DistributionGridMemoryPolicy(
    var outboundTokenBudget: Int = 8192,
    var safetyReserveTokens: Int = 256,
    var minimumCriticalBudget: Int = 384,
    var minimumRecentBudget: Int = 256,
    var maxExecutionNotes: Int = 8,
    var maxHopRecords: Int = 16,
    var enableSummarization: Boolean = false,
    var summaryBudget: Int = 1024,
    var maxSummaryCharacters: Int = 4096,
    var redactContentSections: Boolean = false,
    var redactTraceMetadata: Boolean = true,
    @kotlinx.serialization.Transient var summarizer: ((String) -> String)? = null,
    @kotlinx.serialization.Transient var truncationSettings: TruncationSettings = TruncationSettings()
)

/**
 * Compact least-privilege outbound memory snapshot prepared for one downstream hop.
 *
 * @param targetNodeId Node that will receive the memory envelope.
 * @param resolvedBudget Smallest safe token budget resolved for the request.
 * @param availableBudget Budget left after reserving safety margin.
 * @param safetyReserveTokens Tokens reserved for overhead and stability.
 * @param criticalBudget Budget allocated to critical state and instructions.
 * @param recentBudget Budget allocated to recent execution history.
 * @param summaryBudget Budget allocated to summarized older history.
 * @param summarizationUsed Whether the optional summarizer was used.
 * @param compacted Whether any data had to be compacted or truncated.
 * @param failureReason Optional reason populated when a safe outbound envelope could not be prepared.
 * @param sections Ordered memory sections prepared for the target node.
 * @param contextWindow Compact context-window payload for downstream prompt injection.
 * @param miniBank Compact MiniBank payload for downstream prompt injection.
 */
@Serializable
data class DistributionGridMemoryEnvelope(
    var targetNodeId: String = "",
    var resolvedBudget: Int = 0,
    var availableBudget: Int = 0,
    var safetyReserveTokens: Int = 0,
    var criticalBudget: Int = 0,
    var recentBudget: Int = 0,
    var summaryBudget: Int = 0,
    var summarizationUsed: Boolean = false,
    var compacted: Boolean = false,
    var failureReason: String = "",
    var sections: MutableList<DistributionGridMemorySection> = mutableListOf(),
    var contextWindow: ContextWindow = ContextWindow(),
    var miniBank: MiniBank = MiniBank()
)
