package com.TTT.AgentCore.memory

/** Public description of one committed exact-memory generation. */
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
