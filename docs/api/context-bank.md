# ContextBank Object API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
  - [Current Context Access](#current-context-access)
  - [Bank Management](#bank-management)
  - [Remote Memory Integration](#remote-memory-integration)
  - [Context Swapping](#context-swapping)
  - [Thread-Safe Operations](#thread-safe-operations)
  - [Utilities](#utilities)
  - [TodoList Integration](#todolist-integration)

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

**`todoMutex`**
```kotlin
val todoMutex: Mutex = Mutex()
```
Mutex for accessing the todo list system in this context bank.

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

#### `emplace(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`
Stores or replaces a context window with explicit storage mode control.

**Parameters:**
- `key`: The identifier for storing the context window
- `window`: The ContextWindow to store
- `mode`: Storage mode controlling memory and disk persistence behavior
- `skipRemote`: If true, skip remote delegation even if configured.

**Storage Modes:**
- `StorageMode.MEMORY_ONLY` - Store only in memory, no disk persistence
- `StorageMode.MEMORY_AND_DISK` - Store in both memory and disk (default behavior)
- `StorageMode.DISK_ONLY` - Store only on disk, load on-demand without caching
- `StorageMode.DISK_WITH_CACHE` - Store on disk with LRU memory cache and automatic eviction
- `StorageMode.REMOTE` - Delegate storage to a remote server

**Example:**
```kotlin
// Disk-only for large, infrequently accessed contexts
ContextBank.emplace("large-context", window, StorageMode.DISK_ONLY)

// Cached disk storage with automatic eviction
ContextBank.emplace("hot-data", window, StorageMode.DISK_WITH_CACHE)
```

#### `emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`
Thread-safe storage with explicit storage mode control.

**Parameters:**
- `key`: The identifier for storing the context window
- `window`: The ContextWindow to store
- `mode`: Storage mode controlling memory and disk persistence behavior
- `skipRemote`: If true, skip remote delegation even if configured.

**Behavior:** Uses `bankMutex` to ensure exclusive access during bank modification. Recommended method for updating bank contents in concurrent environments.

#### `getContextFromBank(key: String, copy: Boolean = true, skipRemote: Boolean = false): ContextWindow`
Retrieves a context window from the bank by key, respecting storage mode.

**Behavior:**
- **Page Lock Check**: Returns empty `ContextWindow()` if page is locked via ContextLock
- **Remote Delegation**: Automatically calls `MemoryClient` if mode is `REMOTE` or global remote memory is active (unless `skipRemote` is true).
- **Storage Mode Aware**: DISK_ONLY loads without caching, DISK_WITH_CACHE caches with eviction
- **With `copy = true` (default)**: Returns deep copy via serialization for thread safety
- **With `copy = false`**: Returns direct reference for performance but without thread safety
- **Missing key**: Returns empty `ContextWindow()` if key doesn't exist
- **Automatic Disk Loading**: Loads from disk if file exists but not in memory

#### `deletePersistingBankKeyWithMutex(key: String, skipRemote: Boolean = false): Boolean`
Delete the key file that is holding a persisting context bank key, and lock with the bank mutex for thread safety.

#### `getPageKeys(skipRemote: Boolean = false): List<String>`
Access function to get all the pages that are stored inside the context bank. Merges local and remote keys if applicable.

---

### Remote Memory Integration

#### `enableRemoteHosting(port: Int = 8080)`
Enable remote hosting for this TPipe instance's memory. This starts a Netty server on the specified port if it's not already running.

#### `connectToRemoteMemory(url: String, token: String = "", useGlobally: Boolean = false)`
Connect to a remote TPipe memory server.
- `url`: The base URL of the remote memory server.
- `token`: Optional authentication token.
- `useGlobally`: If true, all memory operations will delegate to the remote server regardless of StorageMode.

#### `fetchMergeSaveRemoteContext(key: String, localWindow: ContextWindow): Boolean`
Perform a fetch-merge-save operation on a remote context window. This helps resolve versioning conflicts by pulling the latest remote state and merging it locally before pushing back.

---

### Context Swapping

#### `swapBank(key: String, copy: Boolean = true)`
Swaps the active banked context window with one from the bank.

**Behavior:**
- **With `copy = true` (default)**: Creates deep copy of banked context for safety
- **With `copy = false`**: Uses direct reference for performance
- **Missing key**: Uses empty `ContextWindow()` if key doesn't exist
- **Thread safety**: **Warning:** Not safe for concurrent use, use `swapBankWithMutex()` instead

#### `swapBankWithMutex(key: String, copy: Boolean = true)`
Thread-safe context window swapping operation.

**Behavior:** Uses both `bankMutex` and `swapMutex` for comprehensive thread safety. Always performs copying by default for safety. Recommended method for context swapping in concurrent environments.

---

### Memory Eviction

#### `evictFromMemory(key: String): Boolean`
Removes a context window from memory without deleting the disk file.

**Returns:** `true` if the key was in memory and was removed, `false` otherwise

#### `evictAllFromMemory()`
Removes all context windows from memory without deleting disk files.

#### `evictTodoListFromMemory(key: String): Boolean`
Removes a TodoList from memory without deleting the disk file.

---

### Cache Configuration

#### `configureCachePolicy(config: CacheConfig)`
Configures cache behavior for DISK_WITH_CACHE storage mode.

**Parameters:**
- `config`: Cache configuration with memory limits and eviction policy

**Eviction Policies:**
- `EvictionPolicy.LRU` - Least Recently Used (default)
- `EvictionPolicy.LFU` - Least Frequently Used
- `EvictionPolicy.FIFO` - First In First Out
- `EvictionPolicy.MANUAL` - No automatic eviction

#### `getCacheStatistics(): CacheStatistics`
Returns current cache statistics (memory usage, hit rates).

#### `clearCache()`
Clears all DISK_WITH_CACHE entries from memory.

---

### TodoList Integration

#### `getPagedTodoList(key: String, copy: Boolean = true, skipRemote: Boolean = false): TodoList`
Retrieves a TodoList from ContextBank storage.

#### `emplaceTodoList(key: String, todoList: TodoList, mode: StorageMode, allowUpdatesOnly: Boolean = true, allowCompletionsOnly: Boolean = false, skipRemote: Boolean = false)`
Stores a TodoList in ContextBank with explicit storage mode and write protections.

---

## Key Behaviors

### Thread Safety Model
ContextBank provides both thread-safe and non-thread-safe variants of operations. Mutex methods ensure safety in concurrent environments at the cost of synchronization overhead.

### Distributed Versioning
When using remote memory, `ContextWindow` and `TodoList` objects include a `version` field. The server uses this for conflict resolution, rejecting writes with outdated versions. The `fetchMergeSaveRemoteContext` helper is the recommended pattern for safe updates.

### Storage Mode System
ContextBank supports five storage modes for fine-grained memory, disk, and network management. `DISK_WITH_CACHE` is recommended for large local datasets, while `REMOTE` is used for inter-agent coordination.
