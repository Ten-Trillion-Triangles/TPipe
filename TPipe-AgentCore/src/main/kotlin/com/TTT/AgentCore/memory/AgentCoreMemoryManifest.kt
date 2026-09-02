package com.TTT.AgentCore.memory

/**
 * Public description of one committed exact-memory generation.
 *
 * @param schemaVersion Envelope schema version.
 * @param key Logical TPipe persistence key.
 * @param kind Stored value kind.
 * @param revision Caller-provided value revision.
 * @param generation Monotonic persistence generation.
 * @param chunkCount Number of payload chunks.
 * @param checksum Checksum of the serialized payload.
 * @param codec Payload codec identifier.
 * @param createdAt Creation timestamp.
 */
data class AgentCoreMemoryManifest(
    val schemaVersion: Int = 1,
    val key: String,
    val kind: String,
    val revision: Long,
    val generation: Long,
    val chunkCount: Int,
    val checksum: String,
    val codec: String = "gzip+base64+sha256-serialized",
    val createdAt: String
)
