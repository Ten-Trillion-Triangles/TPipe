package com.TTT.AgentCore.memory

/** Details returned by an AgentCore Memory batch operation for one failed record. */
data class AgentCoreMemoryRecordFailure(
    val recordId: String?,
    val status: String?,
    val message: String?
)

/**
 * Structured failure from an AgentCore Memory batch operation.
 *
 * Partial batch success is not silently converted into a successful TPipe
 * persistence operation. Callers can inspect the failed record identifiers and
 * statuses before deciding whether to retry or reconcile orphaned records.
 */
class AgentCoreMemoryBackendException(
    val operation: String,
    val failures: List<AgentCoreMemoryRecordFailure>,
    cause: Throwable? = null
) : RuntimeException(
    buildString {
        append("AgentCore Memory ")
        append(operation)
        append(" failed for ")
        append(failures.size)
        append(" record(s): ")
        append(failures.joinToString { failure ->
            listOfNotNull(failure.recordId, failure.status, failure.message).joinToString(": ")
        })
    },
    cause
)
