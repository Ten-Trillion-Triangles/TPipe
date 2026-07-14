package com.TTT.Pipeline

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.P2P.P2PInterface
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
 *
 * ## Summarization backends
 *
 * Two summarization backends are supported:
 *
 * 1. **[summarizer][summaryAgent]** — a [P2PInterface] agent. When set, Junction calls
 *    [executeLocal][P2PInterface.executeLocal] with a [JunctionSummarizerContext] in the input
 *    metadata and uses the returned [MultimodalContent.text] as the summary. This backend takes
 *    absolute priority when both it and the lambda are configured.
 *
 * 2. **[summarizer][summarizerLambda]** — a [kotlin.coroutines.Continuation] lambda [((String) -> String)?].
 *    When the agent backend is absent, Junction invokes this lambda with the older history text.
 *    The lambda is optional; if neither backend is configured, the older history is included verbatim.
 *
 * Both backends are wrapped in [runCatching]: exceptions produce blank output and the verbatim
 * fallback is used; blank text from the agent also triggers verbatim fallback.
 *
 * @param outboundTokenBudget Total outbound token budget for this participant.
 * @param safetyReserveTokens Tokens held back from distribution to prevent overflow.
 * @param minimumCriticalBudget Minimum tokens for critical recent context.
 * @param minimumRecentBudget Minimum tokens for recent history.
 * @param enableSummarization When true, older history beyond [recentDiscussionEntries] may be summarized.
 * @param summaryBudget Tokens reserved for the optional summary section.
 * @param maxSummaryCharacters Maximum input characters fed to the summarizer or lambda.
 * @param recentDiscussionEntries How many discussion round entries to keep in the recent window.
 * @param recentOpinionCount How many recent opinions to include verbatim.
 * @param recentPhaseResultCount How many recent phase results to include verbatim.
 * @param summaryAgent Optional [P2PInterface] agent for summarization. Takes priority over [summarizerLambda].
 * @param summarizerLambda Optional lambda for summarization. Fallback when [summaryAgent] is null.
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
    @kotlinx.serialization.Transient var summarizer: ((String) -> String)? = null,
    @kotlinx.serialization.Transient var summaryAgent: P2PInterface? = null
)

/**
 * Contextual metadata passed to a [P2PInterface] summary agent when summarizing older history.
 *
 * @param roleKind The Junction memory role the summary is being prepared for.
 * @param phase The active workflow phase, or null if in DISCUSSION mode.
 * @param summaryBudget Token budget allocated for the summary section.
 * @param summarySeed Raw older-history text to be summarized.
 */
data class JunctionSummarizerContext(
    val roleKind: JunctionMemoryRole,
    val phase: JunctionWorkflowPhase?,
    val summaryBudget: Int,
    val summarySeed: String
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
