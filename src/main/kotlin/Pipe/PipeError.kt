package com.TTT.Pipe

import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TracePhase
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents an error that occurred during pipe execution.
 * This class captures all relevant information about a failure to enable
 * programmatic error handling and debugging.
 *
 * @property exception The actual exception that caused the failure (not serialized)
 * @property eventType The type of failure event (PIPE_FAILURE, API_CALL_FAILURE, etc.)
 * @property phase The execution phase when the failure occurred
 * @property pipeName The name of the pipe that failed
 * @property pipeId The unique identifier of the pipe that failed
 * @property timestamp When the failure occurred (milliseconds since epoch)
 */
@Serializable
data class PipeError(
    @Transient
    val exception: Throwable? = null,
    val eventType: TraceEventType,
    val phase: TracePhase,
    val pipeName: String,
    val pipeId: String,
    val timestamp: Long = System.currentTimeMillis()
)
{
    /**
     * Human-readable error message extracted from the exception.
     * Returns "Unknown error" if no exception or message is available.
     */
    val message: String
        get() = exception?.message ?: "Unknown error"
}
