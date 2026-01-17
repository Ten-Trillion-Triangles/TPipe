# ContextBank Disk-Only Storage Refactor Plan

## Overview

This plan outlines the refactoring of TPipe's ContextBank to support disk-only storage mode, where context windows and todo lists can be persisted to disk without being retained in memory. This enables memory-efficient operation for large-scale context management and memory-constrained environments.

## Current Architecture Analysis

### Existing Behavior

**ContextWindow Storage:**
- `emplace(key, window, persistToDisk)` always stores in memory: `bank[key] = window`
- Optionally writes to disk at `~/.tpipe/TPipe-Default/memory/lorebook/<key>.bank`
- `getContextFromBank(key)` loads from disk if not in memory, but doesn't cache it
- No eviction methods to remove keys from memory

**TodoList Storage:**
- `emplaceTodoList(key, todoList, persistToDisk)` always stores in memory: `todoList[key] = todoList`
- Optionally writes to disk at `~/.tpipe/TPipe-Default/memory/todo/<key>.todo`
- `getPagedTodoList(key)` loads from disk if not in memory, but doesn't cache it
- No eviction methods to remove keys from memory

**Thread Safety:**
- Three mutexes: `swapMutex`, `bankMutex`, `todoMutex`
- All write operations have mutex-protected variants

### Identified Issues

1. **No disk-only mode**: Cannot operate without memory storage
2. **No eviction API**: Cannot remove keys from memory after loading
3. **Inconsistent caching**: `getContextFromBank()` doesn't cache disk loads, but `emplace()` always caches
4. **No storage mode tracking**: System doesn't know which keys are disk-only vs memory-resident
5. **No memory pressure handling**: No automatic eviction when memory is constrained

## Design Goals

1. **Backward Compatibility**: Existing code must continue to work without changes
2. **Explicit Storage Modes**: Clear API for specifying storage behavior per key
3. **Memory Efficiency**: Support large-scale context management without memory bloat
4. **Thread Safety**: Maintain existing thread safety guarantees
5. **Performance**: Minimize disk I/O overhead with optional caching strategies
6. **Consistency**: Unified behavior across ContextWindow and TodoList storage

## Proposed Architecture

### Storage Mode Enum

```kotlin
enum class StorageMode
{
    /**
     * Store only in memory, never persist to disk.
     * Fastest access, no persistence across restarts.
     */
    MEMORY_ONLY,
    
    /**
     * Store in memory AND persist to disk.
     * Fast access with persistence. Default behavior (backward compatible).
     */
    MEMORY_AND_DISK,
    
    /**
     * Store only on disk, load on-demand.
     * Memory efficient, slower access due to disk I/O.
     */
    DISK_ONLY,
    
    /**
     * Store on disk with LRU memory cache.
     * Balanced approach with automatic eviction.
     */
    DISK_WITH_CACHE
}
```

### Storage Metadata Tracking

```kotlin
@Serializable
data class StorageMetadata(
    val key: String,
    val storageMode: StorageMode,
    val lastAccessed: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val sizeBytes: Long = 0
)
```

### Cache Configuration

```kotlin
data class CacheConfig(
    val maxMemoryBytes: Long = 100 * 1024 * 1024, // 100MB default
    val maxEntries: Int = 1000,
    val evictionPolicy: EvictionPolicy = EvictionPolicy.LRU
)

enum class EvictionPolicy
{
    LRU,        // Least Recently Used
    LFU,        // Least Frequently Used
    FIFO,       // First In First Out
    MANUAL      // No automatic eviction
}
```


## API Design

### New ContextBank Methods

```kotlin
// Storage mode configuration
fun setStorageMode(key: String, mode: StorageMode)
fun getStorageMode(key: String): StorageMode
suspend fun setStorageModeWithMutex(key: String, mode: StorageMode)

// Memory eviction
fun evictFromMemory(key: String): Boolean
fun evictAllFromMemory()
suspend fun evictFromMemoryWithMutex(key: String): Boolean
suspend fun evictAllFromMemoryWithMutex()

// Cache management
fun configureCachePolicy(config: CacheConfig)
fun getCacheStatistics(): CacheStatistics
fun clearCache()

// Enhanced emplace with storage mode
fun emplace(key: String, window: ContextWindow, mode: StorageMode = StorageMode.MEMORY_AND_DISK)
suspend fun emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode = StorageMode.MEMORY_AND_DISK)

// Enhanced TodoList emplace with storage mode
fun emplaceTodoList(
    key: String,
    todoList: TodoList,
    mode: StorageMode = StorageMode.MEMORY_AND_DISK,
    allowUpdatesOnly: Boolean = true,
    allowCompletionsOnly: Boolean = false
)
```

### Backward Compatibility Strategy

**Existing API Preservation:**
- Keep existing `emplace(key, window, persistToDisk: Boolean)` signature
- Map `persistToDisk = true` to `StorageMode.MEMORY_AND_DISK`
- Map `persistToDisk = false` to `StorageMode.MEMORY_ONLY`
- Add new overload with `StorageMode` parameter

**Migration Path:**
```kotlin
// Old API (still works)
ContextBank.emplace("key", window, persistToDisk = true)

// New API (recommended)
ContextBank.emplace("key", window, StorageMode.DISK_ONLY)
```

## Implementation Steps

### Phase 1: Foundation (Storage Mode Infrastructure)

#### Step 1.1: Create Storage Mode Enum
**File:** `src/main/kotlin/Context/StorageMode.kt`

**Actions:**
1. Create new file with `StorageMode` enum
2. Add KDoc documentation for each mode
3. Add `@Serializable` annotation for persistence

**Validation:**
- Enum compiles without errors
- All four modes are defined
- Documentation is clear and complete

#### Step 1.2: Create Storage Metadata Classes
**File:** `src/main/kotlin/Context/StorageMetadata.kt`

**Actions:**
1. Create `StorageMetadata` data class
2. Create `CacheConfig` data class
3. Create `EvictionPolicy` enum
4. Create `CacheStatistics` data class
5. Add serialization support

**Validation:**
- All classes compile
- Serialization/deserialization works
- Default values are sensible

#### Step 1.3: Add Storage Tracking to ContextBank
**File:** `src/main/kotlin/Context/ContextBank.kt`

**Actions:**
1. Add `private var storageMetadata = mutableMapOf<String, StorageMetadata>()`
2. Add `private var cacheConfig = CacheConfig()`
3. Add helper method `private fun updateMetadata(key: String, mode: StorageMode)`
4. Add helper method `private fun trackAccess(key: String)`

**Code Changes:**
```kotlin
object ContextBank
{
    // Existing fields...
    
    @Volatile
    private var storageMetadata = mutableMapOf<String, StorageMetadata>()
    
    @Volatile
    private var cacheConfig = CacheConfig()
    
    private fun updateMetadata(key: String, mode: StorageMode)
    {
        val existing = storageMetadata[key]
        storageMetadata[key] = StorageMetadata(
            key = key,
            storageMode = mode,
            lastAccessed = System.currentTimeMillis(),
            accessCount = (existing?.accessCount ?: 0) + 1
        )
    }
    
    private fun trackAccess(key: String)
    {
        val existing = storageMetadata[key] ?: return
        storageMetadata[key] = existing.copy(
            lastAccessed = System.currentTimeMillis(),
            accessCount = existing.accessCount + 1
        )
    }
}
```

**Validation:**
- ContextBank compiles with new fields
- Helper methods work correctly
- No impact on existing functionality


### Phase 2: Memory Eviction API

#### Step 2.1: Implement Basic Eviction Methods
**File:** `src/main/kotlin/Context/ContextBank.kt`

**Actions:**
1. Add `evictFromMemory(key: String): Boolean` method
2. Add `evictFromMemoryWithMutex(key: String): Boolean` method
3. Add `evictAllFromMemory()` method
4. Add `evictAllFromMemoryWithMutex()` method

**Code Changes:**
```kotlin
/**
 * Remove a context window from memory without deleting the disk file.
 * Returns true if the key was in memory and was removed.
 */
fun evictFromMemory(key: String): Boolean
{
    val existed = bank.containsKey(key)
    bank.remove(key)
    return existed
}

suspend fun evictFromMemoryWithMutex(key: String): Boolean
{
    bankMutex.withLock {
        return evictFromMemory(key)
    }
}

fun evictAllFromMemory()
{
    bank.clear()
}

suspend fun evictAllFromMemoryWithMutex()
{
    bankMutex.withLock {
        evictAllFromMemory()
    }
}
```

**Validation:**
- Methods compile and run
- Eviction removes from memory but not disk
- Thread-safe variants work correctly
- Existing disk files remain intact

#### Step 2.2: Implement TodoList Eviction Methods
**File:** `src/main/kotlin/Context/ContextBank.kt`

**Actions:**
1. Add `evictTodoListFromMemory(key: String): Boolean` method
2. Add `evictTodoListFromMemoryWithMutex(key: String): Boolean` method
3. Add `evictAllTodoListsFromMemory()` method

**Code Changes:**
```kotlin
fun evictTodoListFromMemory(key: String): Boolean
{
    val existed = todoList.containsKey(key)
    todoList.remove(key)
    return existed
}

suspend fun evictTodoListFromMemoryWithMutex(key: String): Boolean
{
    todoMutex.withLock {
        return evictTodoListFromMemory(key)
    }
}

fun evictAllTodoListsFromMemory()
{
    todoList.clear()
}
```

**Validation:**
- TodoList eviction works correctly
- Disk files remain intact
- Thread safety maintained

### Phase 3: Storage Mode Configuration

#### Step 3.1: Add Storage Mode Getters/Setters
**File:** `src/main/kotlin/Context/ContextBank.kt`

**Actions:**
1. Add `setStorageMode(key: String, mode: StorageMode)` method
2. Add `getStorageMode(key: String): StorageMode` method
3. Add `setStorageModeWithMutex(key: String, mode: StorageMode)` method

**Code Changes:**
```kotlin
fun setStorageMode(key: String, mode: StorageMode)
{
    updateMetadata(key, mode)
}

fun getStorageMode(key: String): StorageMode
{
    return storageMetadata[key]?.storageMode ?: StorageMode.MEMORY_AND_DISK
}

suspend fun setStorageModeWithMutex(key: String, mode: StorageMode)
{
    bankMutex.withLock {
        setStorageMode(key, mode)
    }
}
```

**Validation:**
- Storage mode can be set and retrieved
- Default mode is MEMORY_AND_DISK (backward compatible)
- Thread-safe operations work

#### Step 3.2: Refactor emplace() to Support Storage Modes
**File:** `src/main/kotlin/Context/ContextBank.kt`

**Actions:**
1. Add new `emplace()` overload with `StorageMode` parameter
2. Keep existing `emplace()` with `persistToDisk` for backward compatibility
3. Update internal logic to respect storage mode

**Code Changes:**
```kotlin
// New primary implementation
fun emplace(key: String, window: ContextWindow, mode: StorageMode = StorageMode.MEMORY_AND_DISK)
{
    val bankDir = "${TPipeConfig.getLorebookDir()}/${key}.bank"
    
    when (mode)
    {
        StorageMode.MEMORY_ONLY ->
        {
            bank[key] = window
            // Don't write to disk
        }
        
        StorageMode.MEMORY_AND_DISK ->
        {
            bank[key] = window
            val value = serialize(window)
            writeStringToFile(bankDir, value)
        }
        
        StorageMode.DISK_ONLY ->
        {
            // Don't store in memory
            val value = serialize(window)
            writeStringToFile(bankDir, value)
        }
        
        StorageMode.DISK_WITH_CACHE ->
        {
            // Store to disk first
            val value = serialize(window)
            writeStringToFile(bankDir, value)
            
            // Then cache in memory with eviction policy
            bank[key] = window
            enforceEvictionPolicy()
        }
    }
    
    updateMetadata(key, mode)
}

// Backward compatible overload
fun emplace(key: String, window: ContextWindow, persistToDisk: Boolean = false)
{
    val mode = if (persistToDisk) StorageMode.MEMORY_AND_DISK else StorageMode.MEMORY_ONLY
    emplace(key, window, mode)
}

suspend fun emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode = StorageMode.MEMORY_AND_DISK)
{
    bankMutex.withLock {
        emplace(key, window, mode)
    }
}
```

**Validation:**
- DISK_ONLY mode doesn't store in memory
- MEMORY_ONLY mode doesn't write to disk
- MEMORY_AND_DISK mode does both (existing behavior)
- Backward compatible overload works
- Thread-safe variant works


#### Step 3.3: Refactor getContextFromBank() to Respect Storage Modes
**File:** `src/main/kotlin/Context/ContextBank.kt`

**Actions:**
1. Update `getContextFromBank()` to check storage mode
2. For DISK_ONLY mode, don't cache in memory after loading
3. For DISK_WITH_CACHE mode, cache with eviction policy
4. Track access for cache statistics

**Code Changes:**
```kotlin
fun getContextFromBank(key: String, copy: Boolean = true) : ContextWindow
{
    // Check if this page is locked
    if (ContextLock.isPageLocked(key))
    {
        return ContextWindow()
    }
    
    trackAccess(key)
    val mode = getStorageMode(key)
    var context: ContextWindow
    
    // Try memory first
    if (bank.containsKey(key))
    {
        context = bank[key]!!
        return if (copy) context.deepCopy() else context
    }
    
    // Load from disk if exists
    val diskPath = "${TPipeConfig.getLorebookDir()}/${key}.bank"
    if (File(diskPath).exists())
    {
        val contextJson = readStringFromFile(diskPath)
        context = deserialize<ContextWindow>(contextJson) ?: ContextWindow()
        
        // Cache based on storage mode
        when (mode)
        {
            StorageMode.DISK_ONLY ->
            {
                // Don't cache, return directly
            }
            
            StorageMode.DISK_WITH_CACHE ->
            {
                // Cache with eviction policy
                bank[key] = context
                enforceEvictionPolicy()
            }
            
            StorageMode.MEMORY_AND_DISK, StorageMode.MEMORY_ONLY ->
            {
                // Cache normally (existing behavior)
                bank[key] = context
            }
        }
        
        return if (copy) context.deepCopy() else context
    }
    
    // Not found anywhere
    return ContextWindow()
}
```

**Validation:**
- DISK_ONLY keys don't get cached after loading
- DISK_WITH_CACHE keys get cached with eviction
- Existing behavior preserved for other modes
- Access tracking works correctly

### Phase 4: Cache Eviction Policies

#### Step 4.1: Implement LRU Eviction Policy
**File:** `src/main/kotlin/Context/ContextBank.kt`

**Actions:**
1. Add `enforceEvictionPolicy()` private method
2. Implement LRU eviction based on `lastAccessed` timestamp
3. Respect `maxMemoryBytes` and `maxEntries` limits

**Code Changes:**
```kotlin
private fun enforceEvictionPolicy()
{
    if (cacheConfig.evictionPolicy == EvictionPolicy.MANUAL)
    {
        return
    }
    
    // Check entry count limit
    while (bank.size > cacheConfig.maxEntries)
    {
        evictLeastValuable()
    }
    
    // Check memory limit (approximate)
    var totalBytes = 0L
    for ((key, window) in bank)
    {
        totalBytes += estimateSize(window)
    }
    
    while (totalBytes > cacheConfig.maxMemoryBytes && bank.isNotEmpty())
    {
        val evictedSize = evictLeastValuable()
        totalBytes -= evictedSize
    }
}

private fun evictLeastValuable(): Long
{
    val candidates = storageMetadata.values
        .filter { bank.containsKey(it.key) }
        .filter { it.storageMode == StorageMode.DISK_WITH_CACHE }
    
    if (candidates.isEmpty())
    {
        return 0L
    }
    
    val toEvict = when (cacheConfig.evictionPolicy)
    {
        EvictionPolicy.LRU ->
        {
            candidates.minByOrNull { it.lastAccessed }
        }
        
        EvictionPolicy.LFU ->
        {
            candidates.minByOrNull { it.accessCount }
        }
        
        EvictionPolicy.FIFO ->
        {
            candidates.minByOrNull { it.key }
        }
        
        else -> null
    }
    
    if (toEvict != null)
    {
        val size = estimateSize(bank[toEvict.key])
        bank.remove(toEvict.key)
        return size
    }
    
    return 0L
}

private fun estimateSize(window: ContextWindow?): Long
{
    if (window == null) return 0L
    return serialize(window).length.toLong()
}
```

**Validation:**
- Eviction triggers when limits exceeded
- LRU policy evicts least recently accessed
- Only DISK_WITH_CACHE entries are evicted
- Size estimation is reasonable

#### Step 4.2: Implement Cache Configuration API
**File:** `src/main/kotlin/Context/ContextBank.kt`

**Actions:**
1. Add `configureCachePolicy(config: CacheConfig)` method
2. Add `getCacheStatistics(): CacheStatistics` method
3. Add `clearCache()` method

**Code Changes:**
```kotlin
fun configureCachePolicy(config: CacheConfig)
{
    cacheConfig = config
    enforceEvictionPolicy()
}

fun getCacheStatistics(): CacheStatistics
{
    val memoryEntries = bank.size
    val diskOnlyEntries = storageMetadata.values.count { it.storageMode == StorageMode.DISK_ONLY }
    val totalBytes = bank.values.sumOf { estimateSize(it) }
    
    return CacheStatistics(
        memoryEntries = memoryEntries,
        diskOnlyEntries = diskOnlyEntries,
        totalMemoryBytes = totalBytes,
        cacheHitRate = calculateHitRate()
    )
}

fun clearCache()
{
    val diskWithCacheKeys = storageMetadata.values
        .filter { it.storageMode == StorageMode.DISK_WITH_CACHE }
        .map { it.key }
    
    for (key in diskWithCacheKeys)
    {
        bank.remove(key)
    }
}

private fun calculateHitRate(): Double
{
    val totalAccesses = storageMetadata.values.sumOf { it.accessCount }
    if (totalAccesses == 0) return 0.0
    
    val hits = storageMetadata.values
        .filter { bank.containsKey(it.key) }
        .sumOf { it.accessCount }
    
    return hits.toDouble() / totalAccesses.toDouble()
}
```

**Validation:**
- Cache configuration updates correctly
- Statistics are accurate
- clearCache() only removes cached entries
- Hit rate calculation is correct


### Phase 5: TodoList Storage Mode Support

#### Step 5.1: Refactor emplaceTodoList() for Storage Modes
**File:** `src/main/kotlin/Context/ContextBank.kt`

**Actions:**
1. Add new `emplaceTodoList()` overload with `StorageMode` parameter
2. Keep existing signature for backward compatibility
3. Update logic to respect storage modes

**Code Changes:**
```kotlin
// New primary implementation
fun emplaceTodoList(
    key: String,
    todoList: TodoList,
    mode: StorageMode = StorageMode.MEMORY_AND_DISK,
    allowUpdatesOnly: Boolean = true,
    allowCompletionsOnly: Boolean = false
)
{
    val validTaskNumbers = mutableListOf<Int>()
    val bankedTasks = ContextBank.todoList[key]
    
    var todoListToEmplace = if (bankedTasks == null)
    {
        todoList
    }
    else
    {
        // Apply write protection logic (existing code)
        applyTodoListWriteProtection(todoList, bankedTasks, allowUpdatesOnly, allowCompletionsOnly)
    }
    
    val todoPath = TPipeConfig.getTodoListDir()
    val fullFilePath = "${todoPath}/${key}.todo"
    
    when (mode)
    {
        StorageMode.MEMORY_ONLY ->
        {
            ContextBank.todoList[key] = todoListToEmplace
            // Don't write to disk
        }
        
        StorageMode.MEMORY_AND_DISK ->
        {
            ContextBank.todoList[key] = todoListToEmplace
            val todoAsString = serialize(todoListToEmplace)
            writeStringToFile(fullFilePath, todoAsString)
        }
        
        StorageMode.DISK_ONLY ->
        {
            // Don't store in memory
            val todoAsString = serialize(todoListToEmplace)
            writeStringToFile(fullFilePath, todoAsString)
        }
        
        StorageMode.DISK_WITH_CACHE ->
        {
            val todoAsString = serialize(todoListToEmplace)
            writeStringToFile(fullFilePath, todoAsString)
            ContextBank.todoList[key] = todoListToEmplace
            enforceTodoListEvictionPolicy()
        }
    }
    
    updateMetadata(key, mode)
}

// Backward compatible overload
fun emplaceTodoList(
    key: String,
    todoList: TodoList,
    allowUpdatesOnly: Boolean = true,
    allowCompletionsOnly: Boolean = false,
    persistToDisk: Boolean = false
)
{
    val mode = if (persistToDisk) StorageMode.MEMORY_AND_DISK else StorageMode.MEMORY_ONLY
    emplaceTodoList(key, todoList, mode, allowUpdatesOnly, allowCompletionsOnly)
}

private fun applyTodoListWriteProtection(
    todoList: TodoList,
    bankedTasks: TodoList,
    allowUpdatesOnly: Boolean,
    allowCompletionsOnly: Boolean
): TodoList
{
    // Extract existing write protection logic here
    // (existing code from lines 320-380)
    val validTaskNumbers = mutableListOf<Int>()
    
    if (allowUpdatesOnly)
    {
        for (task in todoList.tasks.tasks)
        {
            val isValidTaskNumber = bankedTasks.find(task.taskNumber)
            if (isValidTaskNumber != null)
            {
                validTaskNumbers.add(task.taskNumber)
            }
        }
    }
    
    var todoListToEmplace = TodoList()
    
    if (validTaskNumbers.isNotEmpty())
    {
        for (number in validTaskNumbers)
        {
            val task = todoList.find(number)
            if (task != null) todoListToEmplace.tasks.tasks.add(task)
        }
    }
    else
    {
        todoListToEmplace = todoList
    }
    
    if (allowCompletionsOnly)
    {
        for (task in todoListToEmplace.tasks.tasks)
        {
            bankedTasks.find(task.taskNumber)?.isComplete = task.isComplete
        }
        return bankedTasks
    }
    else
    {
        for (task in todoListToEmplace.tasks.tasks)
        {
            if (bankedTasks.tasks.tasks.contains(task))
            {
                bankedTasks.tasks.tasks[bankedTasks.tasks.tasks.indexOf(task)] = task
            }
            else
            {
                bankedTasks.tasks.tasks.add(task)
            }
        }
        return bankedTasks
    }
}
```

**Validation:**
- DISK_ONLY mode doesn't store TodoList in memory
- Write protection logic still works
- Backward compatible overload works
- Thread-safe variant works

#### Step 5.2: Update getPagedTodoList() for Storage Modes
**File:** `src/main/kotlin/Context/ContextBank.kt`

**Actions:**
1. Update `getPagedTodoList()` to respect storage modes
2. Don't cache DISK_ONLY TodoLists
3. Apply eviction policy for DISK_WITH_CACHE

**Code Changes:**
```kotlin
fun getPagedTodoList(key: String, copy: Boolean = true) : TodoList
{
    trackAccess(key)
    val mode = getStorageMode(key)
    
    // Try memory first
    if (todoList.containsKey(key))
    {
        val list = todoList[key]!!
        return if (copy) list.deepCopy() else list
    }
    
    // Load from disk if exists
    val diskPath = TPipeConfig.getTodoListDir()
    val fullFilePath = "${diskPath}/${key}.todo"
    val fileContents = readStringFromFile(fullFilePath)
    
    if (fileContents.isNotEmpty())
    {
        val result = deserialize<TodoList>(fileContents) ?: TodoList()
        
        // Cache based on storage mode
        when (mode)
        {
            StorageMode.DISK_ONLY ->
            {
                // Don't cache
            }
            
            StorageMode.DISK_WITH_CACHE ->
            {
                todoList[key] = result
                enforceTodoListEvictionPolicy()
            }
            
            StorageMode.MEMORY_AND_DISK, StorageMode.MEMORY_ONLY ->
            {
                todoList[key] = result
            }
        }
        
        return if (copy) result.deepCopy() else result
    }
    
    return TodoList()
}

private fun enforceTodoListEvictionPolicy()
{
    if (cacheConfig.evictionPolicy == EvictionPolicy.MANUAL)
    {
        return
    }
    
    while (todoList.size > cacheConfig.maxEntries)
    {
        evictLeastValuableTodoList()
    }
}

private fun evictLeastValuableTodoList()
{
    val candidates = storageMetadata.values
        .filter { todoList.containsKey(it.key) }
        .filter { it.storageMode == StorageMode.DISK_WITH_CACHE }
    
    if (candidates.isEmpty())
    {
        return
    }
    
    val toEvict = when (cacheConfig.evictionPolicy)
    {
        EvictionPolicy.LRU -> candidates.minByOrNull { it.lastAccessed }
        EvictionPolicy.LFU -> candidates.minByOrNull { it.accessCount }
        EvictionPolicy.FIFO -> candidates.minByOrNull { it.key }
        else -> null
    }
    
    toEvict?.let { todoList.remove(it.key) }
}
```

**Validation:**
- DISK_ONLY TodoLists don't get cached
- DISK_WITH_CACHE TodoLists respect eviction policy
- Existing behavior preserved for other modes


### Phase 6: Testing and Validation

#### Step 6.1: Unit Tests for Storage Modes
**File:** `src/test/kotlin/Context/ContextBankStorageModeTest.kt`

**Test Cases:**
1. **MEMORY_ONLY mode**
   - Verify data stored in memory
   - Verify no disk file created
   - Verify data lost after eviction

2. **MEMORY_AND_DISK mode**
   - Verify data stored in both memory and disk
   - Verify data persists after eviction and reload
   - Verify backward compatibility with `persistToDisk = true`

3. **DISK_ONLY mode**
   - Verify data NOT stored in memory after emplace
   - Verify disk file created
   - Verify data loaded from disk on access
   - Verify data NOT cached in memory after load

4. **DISK_WITH_CACHE mode**
   - Verify data stored to disk
   - Verify data cached in memory
   - Verify eviction policy triggers
   - Verify data reloaded from disk after eviction

**Example Test:**
```kotlin
@Test
fun testDiskOnlyMode()
{
    val key = "test-disk-only"
    val window = ContextWindow()
    window.contextElements.add("test data")
    
    // Emplace with DISK_ONLY mode
    ContextBank.emplace(key, window, StorageMode.DISK_ONLY)
    
    // Verify NOT in memory
    assertFalse(ContextBank.getPageKeys().contains(key))
    
    // Verify disk file exists
    val diskPath = "${TPipeConfig.getLorebookDir()}/${key}.bank"
    assertTrue(File(diskPath).exists())
    
    // Load from disk
    val loaded = ContextBank.getContextFromBank(key)
    assertEquals("test data", loaded.contextElements[0])
    
    // Verify still NOT in memory after load
    assertFalse(ContextBank.getPageKeys().contains(key))
    
    // Cleanup
    ContextBank.deletePersistingBankKey(key)
}
```

#### Step 6.2: Unit Tests for Eviction Policies
**File:** `src/test/kotlin/Context/ContextBankEvictionTest.kt`

**Test Cases:**
1. **LRU eviction**
   - Create multiple cached entries
   - Access some entries to update timestamps
   - Trigger eviction
   - Verify least recently used entry evicted

2. **Entry count limit**
   - Configure maxEntries limit
   - Add more entries than limit
   - Verify eviction triggered
   - Verify count stays within limit

3. **Memory size limit**
   - Configure maxMemoryBytes limit
   - Add large context windows
   - Verify eviction triggered when limit exceeded
   - Verify memory usage stays within limit

4. **Manual eviction**
   - Set eviction policy to MANUAL
   - Add many entries
   - Verify no automatic eviction
   - Manually evict entries
   - Verify manual eviction works

#### Step 6.3: Integration Tests
**File:** `src/test/kotlin/Context/ContextBankIntegrationTest.kt`

**Test Cases:**
1. **Concurrent access with disk-only mode**
   - Multiple coroutines accessing same disk-only key
   - Verify thread safety
   - Verify no data corruption

2. **Mixed storage modes**
   - Some keys MEMORY_ONLY
   - Some keys DISK_ONLY
   - Some keys DISK_WITH_CACHE
   - Verify each behaves correctly
   - Verify no interference between modes

3. **TodoList disk-only mode**
   - Emplace TodoList with DISK_ONLY
   - Verify not in memory
   - Load from disk
   - Update and save back
   - Verify still not in memory

4. **Cache statistics accuracy**
   - Perform various operations
   - Check cache statistics
   - Verify hit rate calculation
   - Verify memory usage tracking

#### Step 6.4: Performance Tests
**File:** `src/test/kotlin/Context/ContextBankPerformanceTest.kt`

**Test Cases:**
1. **Disk-only vs memory performance**
   - Measure access time for DISK_ONLY
   - Measure access time for MEMORY_AND_DISK
   - Compare and document trade-offs

2. **Cache eviction overhead**
   - Measure time with eviction enabled
   - Measure time with eviction disabled
   - Verify overhead is acceptable

3. **Large context window handling**
   - Create very large context windows (10MB+)
   - Test DISK_ONLY mode
   - Verify memory usage stays low
   - Verify performance is acceptable

### Phase 7: Documentation

#### Step 7.1: Update API Documentation
**Files to Update:**
- `docs/api/context-bank.md`
- `docs/core-concepts/context-bank-integration.md`

**Content to Add:**
1. Storage mode enum documentation
2. New method signatures and examples
3. Cache configuration guide
4. Performance considerations
5. Migration guide from old API

#### Step 7.2: Create Usage Examples
**File:** `docs/examples/disk-only-storage-examples.md`

**Examples to Include:**
1. Basic disk-only usage
2. Cache configuration
3. Mixed storage modes
4. Memory-constrained environments
5. Large-scale context management

**Example Content:**
```markdown
## Disk-Only Storage Example

```kotlin
// Configure for memory-constrained environment
ContextBank.configureCachePolicy(
    CacheConfig(
        maxMemoryBytes = 50 * 1024 * 1024, // 50MB
        maxEntries = 100,
        evictionPolicy = EvictionPolicy.LRU
    )
)

// Store large context window to disk only
val largeContext = ContextWindow()
// ... populate with data ...
ContextBank.emplace("large-context", largeContext, StorageMode.DISK_ONLY)

// Access when needed (loads from disk)
val context = ContextBank.getContextFromBank("large-context")

// Update and save back to disk
context.contextElements.add("new data")
ContextBank.emplace("large-context", context, StorageMode.DISK_ONLY)

// Check cache statistics
val stats = ContextBank.getCacheStatistics()
println("Memory entries: ${stats.memoryEntries}")
println("Disk-only entries: ${stats.diskOnlyEntries}")
println("Cache hit rate: ${stats.cacheHitRate}")
```
```

#### Step 7.3: Update KDoc Comments
**File:** `src/main/kotlin/Context/ContextBank.kt`

**Actions:**
1. Add comprehensive KDoc for all new methods
2. Document storage mode behavior
3. Add usage examples in KDoc
4. Document thread safety guarantees
5. Add performance notes


## Edge Cases and Considerations

### Thread Safety

**Concern:** Disk I/O operations are slower than memory operations, potentially increasing mutex lock time.

**Solution:**
- Keep disk I/O outside mutex locks where possible
- Use separate mutexes for metadata vs data operations
- Consider async disk writes for non-critical paths

**Implementation Note:**
```kotlin
suspend fun emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode)
{
    // Serialize outside mutex
    val serialized = if (mode != StorageMode.MEMORY_ONLY) serialize(window) else null
    
    bankMutex.withLock {
        // Fast memory operations inside mutex
        if (mode != StorageMode.DISK_ONLY)
        {
            bank[key] = window
        }
        updateMetadata(key, mode)
    }
    
    // Disk I/O outside mutex
    if (serialized != null)
    {
        val bankDir = "${TPipeConfig.getLorebookDir()}/${key}.bank"
        writeStringToFile(bankDir, serialized)
    }
}
```

### Storage Mode Transitions

**Concern:** What happens when storage mode changes for an existing key?

**Solution:**
- Allow mode transitions via `setStorageMode()`
- When transitioning to DISK_ONLY, evict from memory
- When transitioning to MEMORY_AND_DISK, load into memory if not present
- Document transition behavior clearly

**Implementation:**
```kotlin
fun setStorageMode(key: String, mode: StorageMode)
{
    val currentMode = getStorageMode(key)
    
    if (currentMode == mode)
    {
        return
    }
    
    when (mode)
    {
        StorageMode.DISK_ONLY ->
        {
            // Ensure on disk, then evict from memory
            if (bank.containsKey(key))
            {
                val window = bank[key]!!
                val diskPath = "${TPipeConfig.getLorebookDir()}/${key}.bank"
                writeStringToFile(diskPath, serialize(window))
                bank.remove(key)
            }
        }
        
        StorageMode.MEMORY_AND_DISK ->
        {
            // Ensure in both memory and disk
            if (!bank.containsKey(key))
            {
                val window = getContextFromBank(key, copy = false)
                bank[key] = window
            }
            val diskPath = "${TPipeConfig.getLorebookDir()}/${key}.bank"
            if (!File(diskPath).exists())
            {
                writeStringToFile(diskPath, serialize(bank[key]!!))
            }
        }
        
        StorageMode.MEMORY_ONLY ->
        {
            // Ensure in memory, optionally delete from disk
            if (!bank.containsKey(key))
            {
                val window = getContextFromBank(key, copy = false)
                bank[key] = window
            }
        }
        
        StorageMode.DISK_WITH_CACHE ->
        {
            // Ensure on disk, cache in memory
            if (!bank.containsKey(key))
            {
                val window = getContextFromBank(key, copy = false)
                bank[key] = window
            }
            val diskPath = "${TPipeConfig.getLorebookDir()}/${key}.bank"
            if (!File(diskPath).exists())
            {
                writeStringToFile(diskPath, serialize(bank[key]!!))
            }
            enforceEvictionPolicy()
        }
    }
    
    updateMetadata(key, mode)
}
```

### Disk File Corruption

**Concern:** Disk files may become corrupted or unreadable.

**Solution:**
- Wrap deserialization in try-catch
- Return empty ContextWindow on corruption
- Log errors for debugging
- Consider backup/versioning for critical data

**Implementation:**
```kotlin
private fun loadFromDisk(diskPath: String): ContextWindow?
{
    return try
    {
        val contextJson = readStringFromFile(diskPath)
        deserialize<ContextWindow>(contextJson)
    }
    catch (e: Exception)
    {
        // Log error
        println("Error loading context from disk: ${e.message}")
        null
    }
}
```

### Memory Pressure Detection

**Concern:** How to detect when memory pressure requires aggressive eviction?

**Solution:**
- Monitor JVM memory usage
- Trigger aggressive eviction when memory is low
- Provide callback for custom memory pressure handling

**Implementation:**
```kotlin
private fun checkMemoryPressure()
{
    val runtime = Runtime.getRuntime()
    val usedMemory = runtime.totalMemory() - runtime.freeMemory()
    val maxMemory = runtime.maxMemory()
    val memoryUsagePercent = (usedMemory.toDouble() / maxMemory.toDouble()) * 100
    
    if (memoryUsagePercent > 80.0)
    {
        // Aggressive eviction
        val toEvict = bank.keys.filter { 
            getStorageMode(it) == StorageMode.DISK_WITH_CACHE 
        }.take(bank.size / 2)
        
        for (key in toEvict)
        {
            bank.remove(key)
        }
    }
}
```

### Concurrent Disk Access

**Concern:** Multiple processes or threads accessing same disk files.

**Solution:**
- Use file locking for critical operations
- Consider file-based mutex for cross-process safety
- Document single-process assumption if not supporting multi-process

**Note:** Current implementation assumes single process. Multi-process support would require:
- File-based locking mechanism
- Coordination protocol for cache invalidation
- Conflict resolution strategy

### getPageKeys() Behavior

**Concern:** Should `getPageKeys()` return only memory-resident keys or all keys including disk-only?

**Solution:**
- Add new method `getAllPageKeys()` that scans disk
- Keep `getPageKeys()` returning only memory-resident keys (backward compatible)
- Add `getDiskOnlyPageKeys()` for disk-only keys

**Implementation:**
```kotlin
fun getPageKeys(): List<String>
{
    return bank.keys.toList()
}

fun getAllPageKeys(): List<String>
{
    val memoryKeys = bank.keys.toSet()
    val diskKeys = scanDiskForKeys()
    return (memoryKeys + diskKeys).toList()
}

fun getDiskOnlyPageKeys(): List<String>
{
    val memoryKeys = bank.keys.toSet()
    val diskKeys = scanDiskForKeys()
    return (diskKeys - memoryKeys).toList()
}

private fun scanDiskForKeys(): Set<String>
{
    val lorebookDir = File(TPipeConfig.getLorebookDir())
    if (!lorebookDir.exists())
    {
        return emptySet()
    }
    
    return lorebookDir.listFiles()
        ?.filter { it.extension == "bank" }
        ?.map { it.nameWithoutExtension }
        ?.toSet() ?: emptySet()
}
```

## Migration Strategy

### For Existing Codebases

**Phase 1: No Changes Required**
- Existing code continues to work
- `persistToDisk = true` maps to `MEMORY_AND_DISK`
- `persistToDisk = false` maps to `MEMORY_ONLY`

**Phase 2: Gradual Adoption**
- Identify memory-intensive context windows
- Migrate to `StorageMode.DISK_ONLY` or `DISK_WITH_CACHE`
- Monitor performance and adjust

**Phase 3: Optimization**
- Configure cache policies based on usage patterns
- Fine-tune eviction policies
- Monitor cache statistics

### Breaking Changes

**None.** All changes are backward compatible.

### Deprecation Plan

**No deprecations planned.** Old API remains supported indefinitely.

## Performance Considerations

### Disk I/O Overhead

**DISK_ONLY mode:**
- Every access requires disk read: ~1-10ms per operation
- Suitable for infrequently accessed data
- Not suitable for hot paths

**DISK_WITH_CACHE mode:**
- First access: disk read overhead
- Subsequent accesses: memory speed
- Eviction overhead: minimal (LRU tracking)

### Memory Savings

**Example Scenario:**
- 1000 context windows
- Average size: 100KB each
- Total memory without disk-only: 100MB
- With 90% disk-only: 10MB (90% savings)

### Recommended Configurations

**Memory-Constrained (< 512MB heap):**
```kotlin
CacheConfig(
    maxMemoryBytes = 50 * 1024 * 1024,
    maxEntries = 100,
    evictionPolicy = EvictionPolicy.LRU
)
```

**Balanced (512MB - 2GB heap):**
```kotlin
CacheConfig(
    maxMemoryBytes = 200 * 1024 * 1024,
    maxEntries = 500,
    evictionPolicy = EvictionPolicy.LRU
)
```

**Memory-Rich (> 2GB heap):**
```kotlin
CacheConfig(
    maxMemoryBytes = 500 * 1024 * 1024,
    maxEntries = 2000,
    evictionPolicy = EvictionPolicy.LRU
)
```

## Implementation Timeline

**Phase 1 (Foundation):** 2-3 days
- Storage mode infrastructure
- Basic metadata tracking

**Phase 2 (Eviction API):** 1-2 days
- Memory eviction methods
- TodoList eviction support

**Phase 3 (Storage Modes):** 2-3 days
- Refactor emplace() methods
- Update getContextFromBank()

**Phase 4 (Cache Policies):** 2-3 days
- Implement eviction policies
- Cache configuration API

**Phase 5 (TodoList Support):** 1-2 days
- TodoList storage modes
- TodoList eviction

**Phase 6 (Testing):** 3-4 days
- Unit tests
- Integration tests
- Performance tests

**Phase 7 (Documentation):** 2-3 days
- API documentation
- Usage examples
- Migration guide

**Total Estimated Time:** 13-20 days

## Success Criteria

1. **Backward Compatibility:** All existing tests pass without modification
2. **Disk-Only Mode:** Context windows can be stored and accessed without memory retention
3. **Memory Efficiency:** 80%+ memory savings for disk-only keys
4. **Performance:** Disk-only access < 10ms per operation on SSD
5. **Thread Safety:** No race conditions or deadlocks in concurrent tests
6. **Cache Effectiveness:** Hit rate > 80% for DISK_WITH_CACHE mode
7. **Documentation:** Complete API docs and usage examples
8. **Test Coverage:** > 90% code coverage for new functionality

## Future Enhancements

### Phase 8 (Optional): Advanced Features

1. **Compression:** Compress disk files to save space
2. **Encryption:** Encrypt sensitive context data at rest
3. **Async I/O:** Non-blocking disk operations
4. **Batch Operations:** Bulk load/save for efficiency
5. **Metadata Persistence:** Save storage metadata to disk
6. **Cache Warming:** Pre-load frequently accessed keys
7. **Multi-Process Support:** File-based locking for shared access
8. **Metrics Export:** Prometheus/StatsD integration for monitoring

## Conclusion

This refactoring plan provides a comprehensive approach to adding disk-only storage support to ContextBank while maintaining backward compatibility and thread safety. The phased implementation allows for incremental development and testing, with clear success criteria at each stage.

The design prioritizes:
- **Simplicity:** Clear API with sensible defaults
- **Flexibility:** Multiple storage modes for different use cases
- **Performance:** Configurable caching with multiple eviction policies
- **Safety:** Thread-safe operations with proper mutex protection
- **Compatibility:** Existing code continues to work unchanged

Implementation should proceed phase by phase, with thorough testing at each stage before moving to the next.
