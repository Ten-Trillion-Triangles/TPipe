package com.TTT.Pipeline

import kotlinx.serialization.Serializable

/**
 * Serializable task snapshot stored for save, load, and resume behavior.
 *
 * @param taskId Stable task identifier.
 * @param stateVersion Contract version for the durable snapshot shape.
 * @param envelope Latest envelope snapshot associated with the task.
 * @param sessionRef Optional session reference associated with the latest checkpoint.
 * @param checkpointReason Human-readable reason for the checkpoint.
 * @param completed Whether the task had already reached terminal completion when saved.
 * @param updatedAtEpochMillis Timestamp of the most recent save.
 * @param archived Whether the state has already been archived from the active store.
 */
@Serializable
data class DistributionGridDurableState(
    var taskId: String = "",
    var stateVersion: Int = 1,
    var envelope: DistributionGridEnvelope = DistributionGridEnvelope(),
    var sessionRef: DistributionGridSessionRef? = null,
    var checkpointReason: String = "",
    var completed: Boolean = false,
    var updatedAtEpochMillis: Long = 0L,
    var archived: Boolean = false
)

/**
 * Backend-agnostic contract for saving and restoring `DistributionGrid` task state.
 */
interface DistributionGridDurableStore {
    /**
     * Persist the latest durable snapshot for a task.
     *
     * @param state Durable snapshot to store.
     */
    suspend fun saveState(state: DistributionGridDurableState)

    /**
     * Load the latest stored state for a task without implying active resumption.
     *
     * @param taskId Stable task identifier.
     * @return Stored durable state or `null` when no checkpoint exists.
     */
    suspend fun loadState(taskId: String): DistributionGridDurableState?

    /**
     * Load the state that should be used to resume a paused or interrupted task.
     *
     * @param taskId Stable task identifier.
     * @return Durable state that may be resumed or `null` when none exists.
     */
    suspend fun resumeState(taskId: String): DistributionGridDurableState?

    /**
     * Remove active stored state for a task.
     *
     * @param taskId Stable task identifier.
     * @return `true` when the state was cleared successfully.
     */
    suspend fun clearState(taskId: String): Boolean

    /**
     * Archive completed or inactive task state.
     *
     * @param taskId Stable task identifier.
     * @return `true` when the state was archived successfully.
     */
    suspend fun archiveState(taskId: String): Boolean
}
