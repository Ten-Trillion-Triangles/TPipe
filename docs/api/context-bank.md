# ContextBank Object API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
  - [Current Context Access](#current-context-access)
  - [Bank Management](#bank-management)
  - [Context Swapping](#context-swapping)
  - [Thread-Safe Operations](#thread-safe-operations)
  - [Suspend Methods](#other-suspend-methods)
  - [Utilities](#utilities)
  - [TodoList Integration](#todolist-integration)
  - [Retrieval Functions](#retrieval-functions)
  - [Write Back Functions](#write-back-functions)

## Overview

The `ContextBank` is a singleton object that manages TPipe's global context window system, enabling context sharing between pipes, pipelines, and parallel operations through a keyed banking system.

```kotlin
object ContextBank
```

## Public Properties

**`swapMutex`**
```kotlin
val swapMutex: Mutex = Mutex()
```
Mutex for managing context window swapping operations. Ensures thread-safe access when changing the active banked context window.

**`bankMutex`**
```kotlin
val bankMutex: Mutex = Mutex()
```
Mutex for managing bank access operations. Ensures thread-safe access when reading from or writing to the context bank storage.

## Public Functions

### Current Context Access

#### `getBankedContextWindow(): ContextWindow`
Retrieves direct reference to the currently active banked context window.

**Behavior:** Returns the actual object reference without copying. **Warning:** Not safe for use in coroutines or multi-threaded environments due to potential race conditions. Use `copyBankedContextWindow()` for concurrent access.

#### `copyBankedContextWindow(): ContextWindow?`
Creates a deep copy of the currently active banked context window.

**Behavior:** Uses serialization/deserialization for deep copying to ensure thread safety. Returns null if serialization fails. Recommended for use within coroutines and concurrent operations.

#### `updateBankedContext(newContext: ContextWindow)`
Updates the currently active banked context window.

**Behavior:** Direct assignment without thread safety. **Warning:** Not safe for concurrent use. Use `updateBankedContextWithMutex()` for thread-safe updates.

#### `updateBankedContextWithMutex(newContext: ContextWindow)`
Thread-safe update of the currently active banked context window.

**Behavior:** Uses `bankMutex` to ensure exclusive access during update. Recommended method for updating banked context in concurrent environments.

---

### Bank Management

#### `emplace(key: String, window: ContextWindow, mode: StorageMode)`
Stores or replaces a context window with explicit storage mode control.

**Parameters:**
- `key`: The identifier for storing the context window
- `window`: The ContextWindow to store
- `mode`: Storage mode controlling memory and disk persistence behavior

**Storage Modes:**
- **`StorageMode.MEMORY_ONLY`**: Store only in memory, no disk persistence
- **`StorageMode.MEMORY_AND_DISK`**: Store in both memory and disk (default behavior)
- **`StorageMode.DISK_ONLY`**: Store only on disk, load on-demand without caching
- **`StorageMode.DISK_WITH_CACHE`**: Store on disk with LRU memory cache and automatic eviction

**Example:**
```kotlin
// Disk-only for large, infrequently accessed contexts
ContextBank.emplace("large-context", window, StorageMode.DISK_ONLY)

// Cached disk storage with automatic eviction
ContextBank.emplace("hot-data", window, StorageMode.DISK_WITH_CACHE)
```

#### `emplace(key: String, window: ContextWindow, persistToDisk: Boolean = false)`
Backward compatible storage method using boolean flag.

**Parameters:**
- `key`: The identifier for storing the context window
- `window`: The ContextWindow to store
- `persistToDisk`: When true, stores to both memory and disk; when false, memory only

**Behavior:** Maps to storage modes: `persistToDisk=true` → `MEMORY_AND_DISK`, `persistToDisk=false` → `MEMORY_ONLY`. Maintained for backward compatibility with existing code.

#### `emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode)`
Thread-safe storage with explicit storage mode control.

**Parameters:**
- `key`: The identifier for storing the context window
- `window`: The ContextWindow to store
- `mode`: Storage mode controlling memory and disk persistence behavior

**Behavior:** Uses `bankMutex` to ensure exclusive access during bank modification. Recommended method for updating bank contents in concurrent environments.

#### `emplaceWithMutex(key: String, window: ContextWindow, persistToDisk: Boolean = false)`
Thread-safe backward compatible storage method.

**Parameters:**
- `key`: The identifier for storing the context window
- `window`: The ContextWindow to store
- `persistToDisk`: When true, stores to both memory and disk; when false, memory only

**Behavior:** Uses `bankMutex` to ensure exclusive access. Maintained for backward compatibility.

#### `getContextFromBank(key: String, copy: Boolean = true): ContextWindow`
Retrieves a context window from the bank by key, respecting storage mode.

**Behavior:** 
- **Page Lock Check**: Returns empty `ContextWindow()` if page is locked via ContextLock
- **Storage Mode Aware**: DISK_ONLY loads without caching, DISK_WITH_CACHE caches with eviction
- **With `copy = true` (default)**: Returns deep copy via serialization for thread safety
- **With `copy = false`**: Returns direct reference for performance but without thread safety
- **Missing key**: Returns empty `ContextWindow()` if key doesn't exist
- **Automatic Disk Loading**: Loads from disk if file exists but not in memory

**Example:**
```kotlin
// Set disk-only mode
ContextBank.emplace("data", window, StorageMode.DISK_ONLY)

// Loads from disk without caching in memory
val loaded = ContextBank.getContextFromBank("data")
```

---

### Storage Mode Management

#### `setStorageMode(key: String, mode: StorageMode)`
Sets the storage mode for a specific key.

**Parameters:**
- `key`: The context bank key
- `mode`: The storage mode to apply

**Example:**
```kotlin
ContextBank.setStorageMode("my-key", StorageMode.DISK_ONLY)
```

#### `getStorageMode(key: String): StorageMode`
Gets the storage mode for a specific key.

**Returns:** The storage mode, or `MEMORY_AND_DISK` if not set (default for backward compatibility)

#### `setStorageModeWithMutex(key: String, mode: StorageMode)`
Thread-safe version of `setStorageMode()`.

---

### Memory Eviction

#### `evictFromMemory(key: String): Boolean`
Removes a context window from memory without deleting the disk file.

**Returns:** `true` if the key was in memory and was removed, `false` otherwise

**Example:**
```kotlin
// Free memory while keeping disk file
ContextBank.evictFromMemory("large-context")
```

#### `evictFromMemoryWithMutex(key: String): Boolean`
Thread-safe version of `evictFromMemory()`.

#### `evictAllFromMemory()`
Removes all context windows from memory without deleting disk files.

#### `evictAllFromMemoryWithMutex()`
Thread-safe version of `evictAllFromMemory()`.

#### `evictTodoListFromMemory(key: String): Boolean`
Removes a TodoList from memory without deleting the disk file.

#### `evictTodoListFromMemoryWithMutex(key: String): Boolean`
Thread-safe version of `evictTodoListFromMemory()`.

#### `evictAllTodoListsFromMemory()`
Removes all TodoLists from memory without deleting disk files.

#### `evictAllTodoListsFromMemoryWithMutex()`
Thread-safe version of `evictAllTodoListsFromMemory()`.

---

### Cache Configuration

#### `configureCachePolicy(config: CacheConfig)`
Configures cache behavior for DISK_WITH_CACHE storage mode.

**Parameters:**
- `config`: Cache configuration with memory limits and eviction policy

**Example:**
```kotlin
ContextBank.configureCachePolicy(
    CacheConfig(
        maxMemoryBytes = 50 * 1024 * 1024,  // 50MB
        maxEntries = 100,
        evictionPolicy = EvictionPolicy.LRU
    )
)
```

**Eviction Policies:**
- **`EvictionPolicy.LRU`**: Least Recently Used (default)
- **`EvictionPolicy.LFU`**: Least Frequently Used
- **`EvictionPolicy.FIFO`**: First In First Out
- **`EvictionPolicy.MANUAL`**: No automatic eviction

#### `getCacheStatistics(): CacheStatistics`
Returns current cache statistics.

**Returns:** `CacheStatistics` with:
- `memoryEntries`: Number of entries in memory
- `diskOnlyEntries`: Number of disk-only entries
- `totalMemoryBytes`: Total memory usage
- `cacheHitRate`: Cache hit rate (0.0 to 1.0)

**Example:**
```kotlin
val stats = ContextBank.getCacheStatistics()
println("Memory entries: ${stats.memoryEntries}")
println("Cache hit rate: ${stats.cacheHitRate}")
```

#### `clearCache()`
Clears all DISK_WITH_CACHE entries from memory.

**Behavior:** Only removes cached entries, does not affect MEMORY_ONLY or MEMORY_AND_DISK entries.

---

### Bank Management

---

### Context Swapping

#### `swapBank(key: String, copy: Boolean = true)`
Swaps the active banked context window with one from the bank.

**Behavior:**
- **With `copy = true` (default)**: Creates deep copy of banked context for safety
- **With `copy = false`**: Uses direct reference for performance
- **Missing key**: Uses empty `ContextWindow()` if key doesn't exist
- **Thread safety**: **Warning:** Not safe for concurrent use, use `swapBankWithMutex()` instead

#### `swapBankWithMutex(key: String)`
Thread-safe context window swapping operation.

**Behavior:** Uses both `bankMutex` and `swapMutex` for comprehensive thread safety. Always performs copying for safety. Recommended method for context swapping in concurrent environments.

---

### Thread-Safe Operations

ContextBank provides two tiers of thread-safe access:

**Legacy mutex methods** use the global `bankMutex` and are suitable for simple concurrent access:

- **`emplaceWithMutex()`**: Uses `bankMutex` for bank modifications
- **`updateBankedContextWithMutex()`**: Uses `bankMutex` for banked context updates
- **`swapBankWithMutex()`**: Uses both `bankMutex` and `swapMutex` for context swapping

**Suspend methods** use per-page mutexes so that operations on unrelated page keys can proceed concurrently without blocking each other. These are the recommended approach for coroutine-based code:

#### `getContextFromBankSuspend(key: String, copy: Boolean = true, skipRemote: Boolean = false): ContextWindow`

Suspend-safe retrieval of a context window. Acquires the page-level mutex for `key` only, so reads on other keys are not blocked.

```kotlin
val context = ContextBank.getContextFromBankSuspend("user-profile")
```

#### `emplaceSuspend(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`

Suspend-safe storage with explicit storage mode. Acquires the page-level mutex for `key`.

```kotlin
ContextBank.emplaceSuspend("user-profile", updatedContext, StorageMode.MEMORY_AND_DISK)
```

#### `mutateContextWindowSuspend(key: String, mode: StorageMode, skipRemote: Boolean, block: (ContextWindow) -> Unit): ContextWindow`

Atomic read-modify-write on a context window. Loads the current value, applies `block`, and stores the result — all while holding the page mutex. This prevents lost updates when multiple coroutines modify the same key.

```kotlin
val updated = ContextBank.mutateContextWindowSuspend("session-state", StorageMode.MEMORY_AND_DISK) { window ->
    window.addText("New session event at ${System.currentTimeMillis()}")
}
```

#### `withContextWindowReferenceSuspend(key: String, skipRemote: Boolean, block: (ContextWindow) -> Unit): ContextWindow`

Similar to `mutateContextWindowSuspend` but intended for metadata-only mutations that should not immediately persist to disk. The block receives the shared reference and can modify it in place while the page mutex is held.

```kotlin
ContextBank.withContextWindowReferenceSuspend("counters") { window ->
    val count = (window.metadata["requestCount"] as? Int ?: 0) + 1
    window.metadata["requestCount"] = count
}
```

#### Other Suspend Methods

| Method | Description |
|--------|-------------|
| `getBankedContextWindowSuspend(copy)` | Suspend-safe access to the active banked context |
| `updateBankedContextSuspend(newContext)` | Suspend-safe update of the active banked context |
| `swapBankSuspend(key, copy)` | Suspend-safe context window swapping |
| `deletePersistingBankKeySuspend(key)` | Suspend-safe deletion of a persisted bank key |
| `deleteContextWindowSuspend(key)` | Suspend-safe deletion of a context window |
| `deleteTodoListSuspend(key)` | Suspend-safe deletion of a todo list |
| `contextWindowExistsSuspend(key)` | Suspend-safe existence check for a context window |
| `todoListExistsSuspend(key)` | Suspend-safe existence check for a todo list |
| `getPageKeysSuspend(skipRemote)` | Suspend-safe listing of all page keys |
| `getTodoListKeysSuspend(skipRemote)` | Suspend-safe listing of all todo list keys |
| `getPagedTodoListSuspend(key, copy)` | Suspend-safe retrieval of a todo list |
| `emplaceTodoListSuspend(key, todoList, ...)` | Suspend-safe storage of a todo list |
| `configureCachePolicySuspend(config)` | Suspend-safe cache policy configuration |
| `clearCacheSuspend()` | Suspend-safe cache clearing |

**When to use which:**
- Use `*WithMutex()` methods when calling from blocking code or simple coroutine contexts
- Use `*Suspend()` methods when calling from coroutine-heavy code where per-page concurrency matters (e.g., manifold workers operating on different page keys simultaneously)

---

### Utilities

#### `clearBankedContext()`
Resets the currently active banked context window to empty state.

**Behavior:** Replaces banked context with new empty `ContextWindow()`. Useful for cleanup operations and conditional logic that checks for context presence.

---

### TodoList Integration

ContextBank provides storage and retrieval for TodoList objects, enabling task management across pipes and pipelines.

#### `getPagedTodoList(key: String, copy: Boolean = true): TodoList`
Retrieves a TodoList from ContextBank storage.

**Parameters:**
- **`key`**: The page key identifying the TodoList
- **`copy`**: If `true`, returns a deep copy; if `false`, returns direct reference

**Behavior:** 
1. Checks in-memory storage for the key
2. If not found in memory and disk persistence is enabled, attempts to load from `~/.tpipe/TPipe-Default/memory/todo/<key>.todo`
3. Returns the TodoList if found, or an empty TodoList if not found
4. When `copy = true`, uses serialization for deep copying to ensure thread safety

**Example:**
```kotlin
// Get a copy (safe for concurrent use)
val todoList = ContextBank.getPagedTodoList("my-tasks")

// Get direct reference (faster but not thread-safe)
val todoListRef = ContextBank.getPagedTodoList("my-tasks", copy = false)
```

#### `emplaceTodoList(key: String, todoList: TodoList, writeToDisk: Boolean, overwrite: Boolean)`
Stores a TodoList in ContextBank.

**Parameters:**
- **`key`**: Unique identifier for this TodoList
- **`todoList`**: The TodoList object to store
- **`writeToDisk`**: If `true`, persists to `~/.tpipe/TPipe-Default/memory/todo/<key>.todo`
- **`overwrite`**: If `true`, replaces existing TodoList with same key; if `false`, merges with existing

**Behavior:**
1. Stores TodoList in memory under the specified key
2. If `writeToDisk = true`, serializes and saves to disk for persistence across runs
3. If `overwrite = false` and a TodoList exists with this key, merges the new list with the existing one
4. Creates the todolist directory if it doesn't exist

**Example:**
```kotlin
val todoList = TodoList()
// ... populate tasks ...

ContextBank.emplaceTodoList(
    key = "code-review",
    todoList = todoList,
    writeToDisk = true,
    overwrite = true
)
```

#### `emplaceTodoListWithMutex(key: String, todoList: TodoList, writeToDisk: Boolean, overwrite: Boolean)`
Thread-safe version of `emplaceTodoList()`.

**Behavior:** Identical to `emplaceTodoList()` but uses mutex locking to ensure thread safety. Use this when multiple coroutines or threads might save TodoLists simultaneously.

**Example:**
```kotlin
runBlocking {
    ContextBank.emplaceTodoListWithMutex(
        key = "shared-tasks",
        todoList = todoList,
        writeToDisk = true,
        overwrite = true
    )
}
```

**TodoList Storage Location:**

TodoLists are stored in the TPipe configuration directory:
- **Path**: `~/.tpipe/TPipe-Default/memory/todo/<key>.todo`
- **Format**: JSON serialization of TodoList object
- **Persistence**: Survives application restarts when `writeToDisk = true`

See [TodoList API](todolist.md) for complete TodoList documentation and usage examples.

---

### Retrieval Functions

Retrieval functions enable lazy-loading of context windows from external sources like databases, APIs, or remote services. When a key with a bound retrieval function is requested, the function executes automatically to fetch the data.

#### `RetrievalFunction` Type

```kotlin
typealias RetrievalFunction = suspend (String) -> ContextWindow?
```

A suspending function that takes a context bank key and returns a `ContextWindow` if retrieval succeeds, or `null` if it fails.

**Parameters:**
- **`key`**: The context bank key being requested

**Returns:** `ContextWindow?` - The retrieved context window, or null on failure

#### `registerRetrievalFunction(key: String, function: RetrievalFunction)`
Binds a retrieval function to a specific context bank key.

**Parameters:**
- **`key`**: The context bank key to bind the function to
- **`function`**: The suspending function that will fetch the context window

**Behavior:**
- When `getContextFromBank(key)` is called and the key is not in memory/disk, the retrieval function executes
- The function result is cached in memory after successful retrieval
- Thread-safe: uses `ConcurrentHashMap` for function storage
- Retrieval functions are not persisted across application restarts

**Example:**
```kotlin
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow

// Register database retrieval function
ContextBank.registerRetrievalFunction("user-profile") { key ->
    val data = database.query("SELECT * FROM profiles WHERE key = ?", key)
    if (data != null)
    {
        val context = ContextWindow()
        context.addText(data.toString())
        context
    }
    else null
}

// Later, when accessed, automatically fetches from database
val profile = ContextBank.getContextFromBank("user-profile")
```

#### `removeRetrievalFunction(key: String)`
Removes a retrieval function binding from a key.

**Parameters:**
- **`key`**: The context bank key to unbind

**Behavior:**
- Removes the retrieval function for the specified key
- Does not affect any cached context windows already loaded
- Thread-safe operation

**Example:**
```kotlin
// Remove retrieval function
ContextBank.removeRetrievalFunction("user-profile")

// Now getContextFromBank() will return empty context if not in memory/disk
```

### Retrieval Function Use Cases

**Database-Backed Context:**
```kotlin
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow

// Register retrieval for database-backed lorebook
ContextBank.registerRetrievalFunction("knowledge-base") { key ->
    val entries = database.getLoreBookEntries(key)
    val context = ContextWindow()
    
    entries.forEach { entry ->
        context.addLoreBookEntry(
            key = entry.triggerKey,
            value = entry.content,
            weight = entry.weight
        )
    }
    
    context
}
```

**API-Backed Context:**
```kotlin
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Util.httpGet
import com.TTT.Util.deserialize

// Register retrieval from REST API
ContextBank.registerRetrievalFunction("external-data") { key ->
    try
    {
        val response = httpGet("https://api.example.com/context/$key")
        deserialize<ContextWindow>(response)
    }
    catch (e: Exception)
    {
        println("Failed to retrieve context: ${e.message}")
        null
    }
}
```

**Remote Memory Integration:**
```kotlin
import com.TTT.Context.ContextBank
import com.TTT.Context.MemoryClient

// Register retrieval from remote memory server
ContextBank.registerRetrievalFunction("remote-context") { key ->
    MemoryClient.getContextWindow(key)
}

// Now local ContextBank transparently fetches from remote server
val context = ContextBank.getContextFromBank("remote-context")
```

**Computed Context:**
```kotlin
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow

// Register dynamic context generation
ContextBank.registerRetrievalFunction("system-stats") { key ->
    val context = ContextWindow()
    
    val runtime = Runtime.getRuntime()
    val stats = """
        Memory: ${runtime.totalMemory() / 1024 / 1024} MB
        Free: ${runtime.freeMemory() / 1024 / 1024} MB
        Processors: ${runtime.availableProcessors()}
    """.trimIndent()
    
    context.addText(stats)
    context
}
```

### Retrieval Function Behavior

**Execution Flow:**
1. `getContextFromBank(key)` is called
2. Check if key exists in memory → return if found
3. Check if key exists on disk → load and return if found
4. Check if retrieval function is registered for key → execute if found
5. Cache result in memory after successful retrieval
6. Return empty `ContextWindow()` if all methods fail

**Performance Considerations:**
- Retrieval functions execute only on cache miss
- Results are cached in memory after first retrieval
- Use `evictFromMemory(key)` to force re-retrieval on next access
- Retrieval functions are suspending for async I/O operations

**Error Handling:**
```kotlin
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow

// Retrieval function with error handling
ContextBank.registerRetrievalFunction("resilient-data") { key ->
    var retries = 3
    var lastError: Exception? = null
    
    while (retries > 0)
    {
        try
        {
            return@registerRetrievalFunction fetchFromExternalSource(key)
        }
        catch (e: Exception)
        {
            lastError = e
            retries--
            if (retries > 0) kotlinx.coroutines.delay(1000)
        }
    }
    
    println("Failed to retrieve $key after 3 attempts: ${lastError?.message}")
    null
}
```

---

### Write Back Functions

Write back functions are the write-side complement to retrieval functions. When a context bank key with a bound write back function is written via `emplace` or `emplaceWithMutex`, the function executes to persist the data to an external destination such as a database, API, or remote service.

#### `WriteBackFunction` Type

```kotlin
typealias WriteBackFunction = suspend (String, ContextWindow) -> Boolean
```

A suspending function that receives the context bank key and the context window being written, and returns `true` if the write succeeded.

**Parameters:**
- **`key`**: The context bank key being written
- **`window`**: The context window to persist

**Returns:** `true` if the write back was successful, `false` otherwise

#### `registerWriteBackFunction(key: String, function: WriteBackFunction)`

Binds a write back function to a specific context bank key.

**Parameters:**
- **`key`**: The context bank key to bind the function to
- **`function`**: The suspending function that will persist the context window

**Behavior:**
- The function is called after the context window is stored locally
- Write back functions are stored in a `ConcurrentHashMap` and are thread-safe
- Write back functions are not persisted across application restarts

**Example:**
```kotlin
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow

// Register a write back function that saves to a database
ContextBank.registerWriteBackFunction("user-profile") { key, window ->
    try
    {
        database.save(key, serialize(window))
        true
    }
    catch (e: Exception)
    {
        println("Failed to write back $key: ${e.message}")
        false
    }
}

// When this key is written, the write back function fires automatically
val context = ContextWindow()
context.addText("Updated profile data")
ContextBank.emplaceWithMutex("user-profile", context, persistToDisk = true)
```

#### `removeWriteBackFunction(key: String)`

Removes a write back function binding from a key.

**Parameters:**
- **`key`**: The context bank key to unbind

**Example:**
```kotlin
ContextBank.removeWriteBackFunction("user-profile")
```

#### `clearWriteBackFunctions()`

Removes all registered write back functions.

```kotlin
ContextBank.clearWriteBackFunctions()
```

#### Write Back Function Use Cases

**Database Sync:**
```kotlin
ContextBank.registerWriteBackFunction("session-state") { key, window ->
    database.upsert("context_windows", key, serialize(window))
    true
}
```

**Remote Memory Sync:**
```kotlin
ContextBank.registerWriteBackFunction("shared-context") { key, window ->
    val result = MemoryClient.putContextWindow(key, window)
    result is MemoryOperationResult.Success
}
```

**Audit Logging:**
```kotlin
ContextBank.registerWriteBackFunction("sensitive-data") { key, window ->
    auditLog.record("context_write", key, System.currentTimeMillis())
    true
}
```

#### Retrieval + Write Back Pattern

Combine retrieval and write back functions for full bidirectional sync with an external store:

```kotlin
// Read from database on cache miss
ContextBank.registerRetrievalFunction("synced-context") { key ->
    database.load(key)?.let { deserialize<ContextWindow>(it) }
}

// Write back to database on every update
ContextBank.registerWriteBackFunction("synced-context") { key, window ->
    database.save(key, serialize(window))
    true
}
```

---

## Key Behaviors

### Thread Safety Model
ContextBank provides three tiers of thread-safe access:

1. **Non-mutex methods** (e.g., `emplace()`, `getContextFromBank()`) — fastest, but require external synchronization in concurrent environments
2. **Legacy mutex methods** (e.g., `emplaceWithMutex()`) — use the global `bankMutex` for simple thread safety
3. **Suspend methods** (e.g., `emplaceSuspend()`, `mutateContextWindowSuspend()`) — use per-page mutexes so operations on unrelated keys proceed concurrently

The suspend methods are the recommended approach for coroutine-based code. They use `ConcurrentHashMap`-backed per-page locks internally, meaning two coroutines writing to different page keys will never block each other.

Disk persistence uses atomic temp-file replacement with sidecar lock files (`MemoryPersistence`) to prevent corruption from concurrent writes or crashes during file I/O.

### Copy vs Reference Strategy
Most operations offer choice between copying (safe but slower) and direct references (fast but potentially unsafe). Copying uses serialization for deep copying, ensuring complete isolation between contexts.

### Page-Based Organization
The banking system uses string keys as "pages" to organize different context domains. This enables:
- **Context Isolation**: Different pipelines can use separate pages
- **Context Sharing**: Multiple operations can access the same page
- **Context Switching**: Active context can be swapped between pages

### Fallback Behavior
Operations gracefully handle missing keys by returning empty `ContextWindow()` objects rather than throwing exceptions. This ensures robust operation even with invalid page keys.

### Global State Management
As a singleton, ContextBank maintains global state across the entire TPipe system. This enables:
- **Cross-Pipeline Context**: Multiple pipelines can share context
- **Persistent Context**: Context survives individual pipe/pipeline execution
- **Centralized Management**: Single point of control for all global context

### Storage Mode System
ContextBank supports four storage modes for fine-grained memory and disk management:

**MEMORY_ONLY:**
- Fastest access, no disk I/O
- Data lost on application restart
- Use for temporary or frequently accessed data

**MEMORY_AND_DISK (Default):**
- Fast access with persistence
- Backward compatible with existing code
- Use for important data needing both speed and durability

**DISK_ONLY:**
- Most memory efficient
- Loads from disk on every access without caching
- Use for large, infrequently accessed contexts

**DISK_WITH_CACHE:**
- Balanced approach with automatic eviction
- Configurable cache policies (LRU/LFU/FIFO)
- Use for large datasets where hot data should be cached

**Example Usage:**
```kotlin
// Configure cache for memory-constrained environment
ContextBank.configureCachePolicy(
    CacheConfig(
        maxMemoryBytes = 50 * 1024 * 1024,
        maxEntries = 100,
        evictionPolicy = EvictionPolicy.LRU
    )
)

// Store large context to disk only
val largeContext = ContextWindow()
// ... populate ...
ContextBank.emplace("large-data", largeContext, StorageMode.DISK_ONLY)

// Access when needed (loads from disk)
val loaded = ContextBank.getContextFromBank("large-data")

// Monitor cache performance
val stats = ContextBank.getCacheStatistics()
println("Cache hit rate: ${stats.cacheHitRate}")
```
