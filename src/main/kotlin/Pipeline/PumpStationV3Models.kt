package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Pipe.MultimodalContent
import kotlinx.serialization.Serializable

/**
 * Models for the PumpStation v3 compaction architecture.
 *
 * Introduces:
 * - A generation-cursor model ([CompactionCursor], [LorebookCursor]) that lets a later-arriving
 *   compaction or lorebook update detect that an earlier one already covered the work and drop
 *   its own attempt without re-running the LLM.
 * - A [ChunkFanoutMode] for the [PumpStationCompactionStrategy.Chunked] strategy: parallel
 *   (bounded-concurrency fan-out) or sequential (running-summary reduce).
 * - A [CompactionResult] sealed type so strategy functions can communicate "applied", "discarded
 *   because pre-empted", "inflated; retry me with smaller scope", "rolled back from a backup",
 *   or "gave up; handed off to truncation".
 * - Four new [PumpStationEvent]s emitted by the orchestrator so the visualizer can show the
 *   per-attempt journey.
 *
 * Lives in a separate file from [PumpStationModels.kt] so the v3 changes can be reviewed and
 * reverted as a unit. The kill switch remains an independent cost-control system; it is not
 * in this cascade.
 */

//=========================================ChunkFanoutMode============================================================

/**
 * Controls how the [PumpStationCompactionStrategy.Chunked] strategy partitions and processes
 * its chunks.
 *
 * - [Sequential] (default): chunks are processed in order, each new chunk's summary carries
 *   forward the previous chunk's running summary. Causal ordering is preserved. Each chunk
 *   call goes through `summaryMutex` so the kill-switch propagation and async summary
 *   serialization are respected.
 *
 * - [Parallel]: chunks are summarized concurrently with bounded concurrency (semaphore permit
 *   count = `maxParallelChunks`). All chunks are then folded by a second summary call.
 *   Cancellation through `coroutineScope` propagates to in-flight chunks on kill switch trip;
 *   the fold call re-checks the cursor CAS before applying.
 */
enum class ChunkFanoutMode
{
    Sequential,
    Parallel
}

//=========================================CompactionCursor==========================================================

/**
 * Per-PumpStation cursor that tracks the latest committed compaction. A strategy function
 * captures the next [generation] on entry, performs the LLM work, and CAS-applies the result
 * if and only if the cursor's [generation] still matches its captured value when the LLM call
 * returns. If a second caller has already advanced the generation in the meantime, the first
 * caller's work is stale and is returned as [CompactionResult.DiscardedPreEmpted] without
 * mutating `turnHistory`.
 */
@Serializable
data class CompactionCursor(
    val generation: Long = 0L,
    val lastCompactedTurnIndex: Int = -1,
    val lastCompactionStrategy: PumpStationCompactionStrategy? = null,
    val lastCompactionInputTokens: Int = 0,
    val lastCompactionOutputTokens: Int = 0,
    val lastCompactionTimestamp: Long = 0L,
    val lastFanoutMode: ChunkFanoutMode? = null
)

//=========================================LorebookCursor============================================================

/**
 * Per-PumpStation cursor for the lorebook agent. Mirrors [CompactionCursor] in shape but
 * tracks the highest `taskState.turnIndex` whose turns have been folded into the lorebook.
 * The `updateLorebook` orchestrator builds its input from
 * `turnHistory.history.filter { it.turnIndex > lastUpdatedTurnIndex }` and discards an agent
 * response whose `LorebookAgentOutput.compactedThroughTurn` is `<= lastUpdatedTurnIndex`.
 */
@Serializable
data class LorebookCursor(
    val lastUpdatedTurnIndex: Int = -1,
    val lastUpdateTimestamp: Long = 0L,
    val lastUpdateGeneration: Long = 0L
)

//=========================================CompactionBackup==========================================================

/**
 * Immutable snapshot of the [PumpStation]'s pre-compaction state. Captured before every
 * compaction attempt and pushed to the compaction-backups ring buffer. Used by
 * `restoreFromBackup` to roll back to a known-good state when a compaction attempt is
 * [CompactionResult.Inflated], is rolled back by the DITL hook, or needs to be discarded
 * because the cursor moved during the LLM call.
 */
@Serializable
data class CompactionBackup(
    val generation: Long,
    val turnIndex: Int,
    val turnHistory: List<ConverseData>,
    val latestContent: MultimodalContent?,
    val contextWindow: ContextWindow,
    val miniBank: MiniBank,
    val createdAt: Long = System.currentTimeMillis()
)

//=========================================CompactionResult==========================================================

/**
 * Sealed type returned by every compaction strategy function and by the top-level
 * `runCompactionAttempt` and `runCompactionPhase` orchestrators. Lets the orchestrator decide
 * whether to retry with a smaller scope, roll back to a backup, hand off to truncation, or
 * commit the result.
 */
@kotlinx.serialization.Serializable
sealed class CompactionResult
{
    /** Strategy was below its threshold; no work was done. */
    object SkippedBelowThreshold : CompactionResult()

    /** No summary agent is bound; strategy cannot run. */
    object SkippedNoAgent : CompactionResult()

    /**
     * The cursor shows the work range has not changed since the last successful compaction.
     * No work was done.
     */
    object SkippedCursorAlreadyAdvanced : CompactionResult()

    /**
     * The compaction was applied. `turnHistory` has been replaced with the summary.
     */
    data class Applied(
        val inputTokens: Int,
        val outputTokens: Int,
        val generation: Long,
        val fanout: ChunkFanoutMode? = null
    ) : CompactionResult()

    /**
     * The LLM returned a summary whose estimated token count is greater than the input's.
     * The orchestrator will restore the most recent [CompactionBackup] and retry with a
     * smaller scope, or hand off to truncation if the retry budget is exhausted.
     */
    data class Inflated(
        val inputTokens: Int,
        val outputTokens: Int,
        val attempt: Int
    ) : CompactionResult()

    /**
     * The cursor moved while the LLM call was in flight (a concurrent compaction committed
     * first). The result of the LLM call is dropped without mutating `turnHistory`.
     */
    data class DiscardedPreEmpted(
        val observedGeneration: Long,
        val currentGeneration: Long
    ) : CompactionResult()

    /**
     * A backup was restored to the PumpStation. Either the DITL hook returned a replacement
     * backup, or the orchestrator restored the most-recent one as part of an [Inflated] retry.
     */
    data class RolledBack(
        val backupGeneration: Long,
        val reason: String
    ) : CompactionResult()

    /**
     * The retry budget is exhausted and the orchestrator has handed off to the existing
     * `failurePolicy`-driven truncation path. The harness continues; the kill switch is NOT
     * tripped (kill switch is an independent cost-control system).
     */
    data class HandedOffToTruncation(
        val contextWindowBefore: Int,
        val contextWindowAfter: Int
    ) : CompactionResult()
}
