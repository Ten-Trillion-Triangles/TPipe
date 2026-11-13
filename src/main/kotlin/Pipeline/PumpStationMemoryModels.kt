package com.TTT.Pipeline

/**
 * A proposed memory write produced by a harness memory agent, presented to the
 * developer's [com.TTT.Pipeline.PumpStation.setPreMemoryFunction] hook before it
 * lands in persistent memory.
 *
 * The hook returns the write to apply — possibly modified — or `null` to reject
 * it. A rejected write does not land and (for lorebook) does not advance the
 * lorebook cursor, so the underlying turns are re-offered on the next memory
 * update.
 *
 * @see com.TTT.Pipeline.LorebookAgentOutput
 * @see com.TTT.Pipeline.LorebookUpdate
 */
@kotlinx.serialization.Serializable
sealed interface MemoryWriteProposal
{
    /** The run identifier of the harness that produced the write. */
    val runId: String

    /** The harness turn index at the moment the write was evaluated. */
    val turnIndex: Int

    /**
     * A proposed lorebook mutation.
     *
     * @property updates Entries to merge into or replace within the lorebook map.
     * @property deletions Lorebook keys to remove before applying [updates].
     * @property compactedThroughTurn Highest turn index the agent claims to have
     *   folded into the lorebook. Only meaningful on the typed path; the legacy
     *   path supplies the cursor's next value.
     * @property legacy True when the proposal came from the legacy free-form JSON
     *   contract rather than the typed [LorebookAgentOutput] envelope.
     */
    @kotlinx.serialization.Serializable
    data class Lorebook(
        override val runId: String,
        override val turnIndex: Int,
        val updates: List<LorebookUpdate> = emptyList(),
        val deletions: List<String> = emptyList(),
        val compactedThroughTurn: Int = -1,
        val legacy: Boolean = false
    ) : MemoryWriteProposal
    {
        /**
         * Convert back to the typed envelope consumed by the lorebook applier.
         *
         * @return A [LorebookAgentOutput] carrying this proposal's updates, deletions, and turn marker.
         */
        fun toLorebookAgentOutput(): LorebookAgentOutput =
            LorebookAgentOutput(
                updates = updates,
                deletions = deletions,
                compactedThroughTurn = compactedThroughTurn
            )
    }

    /**
     * A proposed summary write. The text replaces `turnSummary` wholesale.
     *
     * @property text The proposed summary body.
     */
    @kotlinx.serialization.Serializable
    data class Summary(
        override val runId: String,
        override val turnIndex: Int,
        val text: String = ""
    ) : MemoryWriteProposal
}
