package com.TTT.Context

/**
 * Defines cache eviction policies for DISK_WITH_CACHE storage mode.
 * Controls which entries are removed from memory when cache limits are exceeded.
 */
enum class EvictionPolicy
{
    /**
     * Least Recently Used - evicts entries that haven't been accessed recently.
     * Best for workloads with temporal locality.
     */
    LRU,

    /**
     * Least Frequently Used - evicts entries with lowest access count.
     * Best for workloads where some data is consistently more popular.
     */
    LFU,

    /**
     * First In First Out - evicts oldest entries first.
     * Simple policy with predictable behavior.
     */
    FIFO,

    /**
     * Manual eviction only - no automatic eviction.
     * Application must explicitly call eviction methods.
     */
    MANUAL
}

/**
 * Metadata tracking for a single context bank entry.
 * Used for cache management and eviction policy decisions.
 *
 * @param key The context bank key
 * @param storageMode How this entry is stored
 * @param lastAccessed Timestamp of last access in milliseconds
 * @param accessCount Number of times this entry has been accessed
 * @param sizeBytes Approximate size in bytes (for memory limit enforcement)
 */
@kotlinx.serialization.Serializable
data class StorageMetadata(
    val key: String,
    val storageMode: StorageMode,
    val lastAccessed: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val sizeBytes: Long = 0
)

/**
 * Configuration for cache behavior in ContextBank.
 * Controls memory limits and eviction policies for DISK_WITH_CACHE mode.
 *
 * @param maxMemoryBytes Maximum total memory for cached entries in bytes (default 100MB)
 * @param maxEntries Maximum number of cached entries (default 1000)
 * @param evictionPolicy Policy for selecting entries to evict (default LRU)
 */
data class CacheConfig(
    val maxMemoryBytes: Long = 100 * 1024 * 1024,
    val maxEntries: Int = 1000,
    val evictionPolicy: EvictionPolicy = EvictionPolicy.LRU
)

/**
 * Statistics about cache performance and memory usage.
 * Useful for monitoring and tuning cache configuration.
 *
 * @param memoryEntries Number of entries currently in memory
 * @param diskOnlyEntries Number of entries stored only on disk
 * @param totalMemoryBytes Approximate total memory used by cached entries
 * @param cacheHitRate Ratio of cache hits to total accesses (0.0 to 1.0)
 */
data class CacheStatistics(
    val memoryEntries: Int,
    val diskOnlyEntries: Int,
    val totalMemoryBytes: Long,
    val cacheHitRate: Double
)
