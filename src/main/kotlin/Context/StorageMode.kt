package com.TTT.Context

/**
 * Defines how context data is stored in ContextBank, controlling memory and disk persistence behavior.
 * Storage modes allow fine-grained control over memory usage and performance trade-offs.
 *
 * @see ContextBank
 */
@kotlinx.serialization.Serializable
enum class StorageMode
{
    /**
     * Store only in memory, never persist to disk.
     * Fastest access with no disk I/O overhead, but data is lost on application restart.
     * Use for temporary or frequently accessed data that doesn't need persistence.
     */
    MEMORY_ONLY,

    /**
     * Store in memory AND persist to disk.
     * Fast access with persistence across restarts. This is the default behavior for backward compatibility.
     * Use for important data that needs both fast access and durability.
     */
    MEMORY_AND_DISK,

    /**
     * Store only on disk, load on-demand without caching.
     * Most memory efficient but slower access due to disk I/O on every read.
     * Use for large context windows that are infrequently accessed.
     */
    DISK_ONLY,

    /**
     * Store on disk with LRU memory cache and automatic eviction.
     * Balanced approach providing persistence with cached access for frequently used data.
     * Use for large datasets where hot data should be cached but memory is constrained.
     */
    DISK_WITH_CACHE,

    /**
     * Store context data on a remote server.
     * Requires TPipeConfig.remoteMemoryEnabled to be true and a valid remoteMemoryUrl.
     * This mode allows multiple TPipe instances to share context in real-time.
     */
    REMOTE
}
