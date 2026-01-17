# ContextBank Disk-Only Storage - Usage Guide

## Quick Start

### Basic Usage - Disk-Only Storage

```kotlin
// Store a large context window to disk only (no memory retention)
val largeContext = ContextWindow()
largeContext.contextElements.add("Large dataset...")

ContextBank.emplace("large-data", largeContext, StorageMode.DISK_ONLY)

// Access when needed - loads from disk without caching
val loaded = ContextBank.getContextFromBank("large-data")
// Memory remains free - data is NOT cached
```

### Memory-Efficient Configuration

```kotlin
// Configure cache for memory-constrained environment
ContextBank.configureCachePolicy(
    CacheConfig(
        maxMemoryBytes = 50 * 1024 * 1024,  // 50MB limit
        maxEntries = 100,                     // Max 100 cached entries
        evictionPolicy = EvictionPolicy.LRU   // Least Recently Used
    )
)

// Use cached disk storage for frequently accessed data
ContextBank.emplace("hot-data", context, StorageMode.DISK_WITH_CACHE)
```

## How It Works

### When Saving (emplace)

**MEMORY_ONLY:**
```kotlin
ContextBank.emplace("key", window, StorageMode.MEMORY_ONLY)
// ✓ Stored in memory
// ✗ NOT written to disk
// Result: Fast access, lost on restart
```

**MEMORY_AND_DISK (Default):**
```kotlin
ContextBank.emplace("key", window, StorageMode.MEMORY_AND_DISK)
// ✓ Stored in memory
// ✓ Written to disk
// Result: Fast access + persistence (backward compatible)
```

**DISK_ONLY:**
```kotlin
ContextBank.emplace("key", window, StorageMode.DISK_ONLY)
// ✗ NOT stored in memory
// ✓ Written to disk
// Result: Memory efficient, loads on-demand
```

**DISK_WITH_CACHE:**
```kotlin
ContextBank.emplace("key", window, StorageMode.DISK_WITH_CACHE)
// ✓ Stored in memory (with eviction)
// ✓ Written to disk
// Result: Balanced - cached until evicted
```

### When Loading (getContextFromBank)

**If key is in memory:**
```kotlin
val context = ContextBank.getContextFromBank("key")
// Returns from memory immediately (fast)
```

**If key is NOT in memory but on disk:**

**DISK_ONLY mode:**
```kotlin
val context = ContextBank.getContextFromBank("key")
// 1. Loads from disk
// 2. Returns data
// 3. Does NOT cache in memory
// Next access: loads from disk again
```

**DISK_WITH_CACHE mode:**
```kotlin
val context = ContextBank.getContextFromBank("key")
// 1. Loads from disk
// 2. Caches in memory
// 3. Returns data
// Next access: returns from memory (until evicted)
```

**MEMORY_AND_DISK mode:**
```kotlin
val context = ContextBank.getContextFromBank("key")
// 1. Loads from disk
// 2. Caches in memory permanently
// 3. Returns data
// Next access: returns from memory
```

## Common Use Cases

### 1. Large Infrequently Accessed Data

```kotlin
// Store large context that's rarely needed
val archiveContext = ContextWindow()
// ... populate with large dataset ...

ContextBank.emplace("archive-2024", archiveContext, StorageMode.DISK_ONLY)

// Later, when needed:
val archive = ContextBank.getContextFromBank("archive-2024")
// Loads from disk, doesn't consume memory
```

### 2. Hot Data with Memory Limits

```kotlin
// Configure cache
ContextBank.configureCachePolicy(
    CacheConfig(
        maxMemoryBytes = 100 * 1024 * 1024,
        maxEntries = 50,
        evictionPolicy = EvictionPolicy.LRU
    )
)

// Store frequently accessed data
for (i in 1..100) {
    val context = ContextWindow()
    // ... populate ...
    ContextBank.emplace("data-$i", context, StorageMode.DISK_WITH_CACHE)
}

// Only 50 most recently used entries stay in memory
// Others are automatically evicted but remain on disk
```

### 3. Manual Memory Management

```kotlin
// Store with caching
ContextBank.emplace("temp-data", context, StorageMode.MEMORY_AND_DISK)

// Use the data...
val data = ContextBank.getContextFromBank("temp-data")

// Free memory when done
ContextBank.evictFromMemory("temp-data")

// Data still on disk, can reload later
val reloaded = ContextBank.getContextFromBank("temp-data")
```

### 4. Monitoring Cache Performance

```kotlin
// Check cache statistics
val stats = ContextBank.getCacheStatistics()

println("Memory entries: ${stats.memoryEntries}")
println("Disk-only entries: ${stats.diskOnlyEntries}")
println("Total memory: ${stats.totalMemoryBytes / 1024 / 1024} MB")
println("Cache hit rate: ${(stats.cacheHitRate * 100).toInt()}%")

// Clear cache if needed
if (stats.totalMemoryBytes > threshold) {
    ContextBank.clearCache()  // Only clears DISK_WITH_CACHE entries
}
```

## Interaction Flow Diagrams

### Save Flow (emplace)

```
User calls emplace(key, window, mode)
    ↓
Check mode:
    ├─ MEMORY_ONLY → Store in memory only
    ├─ MEMORY_AND_DISK → Store in memory + write to disk
    ├─ DISK_ONLY → Write to disk only (skip memory)
    └─ DISK_WITH_CACHE → Write to disk + cache + enforce eviction
    ↓
Update metadata (storage mode, access count, timestamp)
```

### Load Flow (getContextFromBank)

```
User calls getContextFromBank(key)
    ↓
Track access (update timestamp, increment count)
    ↓
Check if in memory?
    ├─ YES → Return from memory (fast path)
    └─ NO → Check disk
        ↓
    Check if file exists on disk?
        ├─ NO → Return empty ContextWindow()
        └─ YES → Load from disk
            ↓
        Check storage mode:
            ├─ DISK_ONLY → Return data (don't cache)
            ├─ DISK_WITH_CACHE → Cache + enforce eviction + return
            └─ MEMORY_AND_DISK/MEMORY_ONLY → Cache + return
```

## Backward Compatibility

**Existing code continues to work unchanged:**

```kotlin
// Old API still works
ContextBank.emplace("key", window, persistToDisk = true)
// Automatically maps to StorageMode.MEMORY_AND_DISK

ContextBank.emplace("key", window, persistToDisk = false)
// Automatically maps to StorageMode.MEMORY_ONLY

// Default behavior unchanged
ContextBank.emplace("key", window)
// Still defaults to memory-only (persistToDisk = false)
```

## Thread Safety

All operations have mutex-protected variants:

```kotlin
// Thread-safe operations
runBlocking {
    ContextBank.emplaceWithMutex("key", window, StorageMode.DISK_ONLY)
    ContextBank.evictFromMemoryWithMutex("key")
    ContextBank.setStorageModeWithMutex("key", StorageMode.DISK_WITH_CACHE)
}
```

## File Locations

**ContextWindow files:**
```
~/.tpipe/TPipe-Default/memory/lorebook/<key>.bank
```

**TodoList files:**
```
~/.tpipe/TPipe-Default/memory/todo/<key>.todo
```

## Best Practices

1. **Use DISK_ONLY for large, infrequent data**
   - Archive data
   - Historical contexts
   - Large reference datasets

2. **Use DISK_WITH_CACHE for hot data with memory limits**
   - Frequently accessed contexts
   - Working set larger than available memory
   - Automatic eviction handles memory pressure

3. **Use MEMORY_AND_DISK for critical, frequently accessed data**
   - Active pipeline contexts
   - Current working data
   - Data that must be fast

4. **Use MEMORY_ONLY for temporary data**
   - Intermediate results
   - Scratch space
   - Data that doesn't need persistence

5. **Monitor cache performance**
   - Check hit rates regularly
   - Adjust cache size based on usage
   - Clear cache when memory pressure detected

## Example: Complete Workflow

```kotlin
// 1. Configure for memory-constrained environment
ContextBank.configureCachePolicy(
    CacheConfig(
        maxMemoryBytes = 50 * 1024 * 1024,
        maxEntries = 100,
        evictionPolicy = EvictionPolicy.LRU
    )
)

// 2. Store different types of data
val activeContext = ContextWindow()
activeContext.contextElements.add("Current work")
ContextBank.emplace("active", activeContext, StorageMode.MEMORY_AND_DISK)

val archiveContext = ContextWindow()
archiveContext.contextElements.add("Old data")
ContextBank.emplace("archive", archiveContext, StorageMode.DISK_ONLY)

val workingContext = ContextWindow()
workingContext.contextElements.add("Working set")
ContextBank.emplace("working", workingContext, StorageMode.DISK_WITH_CACHE)

// 3. Access data as needed
val active = ContextBank.getContextFromBank("active")      // From memory (fast)
val archive = ContextBank.getContextFromBank("archive")    // From disk (slower)
val working = ContextBank.getContextFromBank("working")    // From cache or disk

// 4. Monitor performance
val stats = ContextBank.getCacheStatistics()
println("Cache hit rate: ${(stats.cacheHitRate * 100).toInt()}%")

// 5. Free memory when needed
ContextBank.evictFromMemory("active")  // Manual eviction
ContextBank.clearCache()                // Clear all cached entries
```
