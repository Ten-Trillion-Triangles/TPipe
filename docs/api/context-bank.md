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
- [Key Behaviors](#key-behaviors)

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

---

## Public Functions

### Current Context Access

#### `getBankedContextWindow(): ContextWindow`
Retrieves direct reference to the currently active banked context window.
**Warning:** Returns the actual object reference without copying. Not safe for use in coroutines. Use `copyBankedContextWindow()` for concurrent access.

#### `copyBankedContextWindow(): ContextWindow?`
Creates a deep copy of the currently active banked context window via serialization. Recommended for use within coroutines.

#### `updateBankedContext(newContext: ContextWindow)`
Updates the currently active banked context window via direct assignment. Not safe for concurrent use.

#### `updateBankedContextWithMutex(newContext: ContextWindow)`
Thread-safe update of the currently active banked context window using `bankMutex`.

---

### Bank Management

#### `emplace(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`
Stores or replaces a context window with explicit storage mode control.
- `key`: The identifier for storing the context window.
- `window`: The `ContextWindow` to store.
- `mode`: Controls persistence (MEMORY_ONLY, MEMORY_AND_DISK, DISK_ONLY, DISK_WITH_CACHE, REMOTE).
- `skipRemote`: If true, skip remote delegation even if configured globally.

#### `emplace(key: String, window: ContextWindow, persistToDisk: Boolean = false)`
Backward compatible storage method using boolean flag.

#### `emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`
Thread-safe version of `emplace` using `bankMutex`. Recommended for updates inside pipes.

#### `getContextFromBank(key: String, copy: Boolean = true, skipRemote: Boolean = false): ContextWindow`
Retrieves a context window from the bank by key.
- **Page Lock Check**: Returns empty `ContextWindow()` if page is locked via `ContextLock`.
- **Remote Delegation**: Automatically calls `MemoryClient` if mode is `REMOTE` or global remote memory is active (unless `skipRemote` is true).
- **Storage Mode Aware**: Respects caching and eviction rules for the given key.

#### `getPageKeys(skipRemote: Boolean = false): List<String>`
Returns a list of all pages (keys) stored in the context bank. Merges local and remote keys if available.

#### `deletePersistingBankKeyWithMutex(key: String, skipRemote: Boolean = false): Boolean`
Thread-safely deletes the key file holding a persisting context bank key.

---

### Remote Memory Integration

#### `enableRemoteHosting(port: Int = 8080)`
Starts a Netty server on the specified port to host this instance's memory for remote access.

#### `connectToRemoteMemory(url: String, token: String = "", useGlobally: Boolean = false)`
Configures the instance to delegate memory operations to a remote server.
- `url`: Base URL of the remote memory server.
- `token`: Authentication token for headers.
- `useGlobally`: If true, all memory operations delegate to remote regardless of `StorageMode`.

#### `fetchMergeSaveRemoteContext(key: String, localWindow: ContextWindow): Boolean`
Performs a fetch-merge-save operation on a remote window. Resolves conflicts by pulling the latest remote state, merging local changes, and pushing back with an advanced version.

---

### Context Swapping

#### `swapBank(key: String, copy: Boolean = true)`
Swaps the active `bankedContextWindow` with one from the bank. **Warning:** Not safe for concurrent use.

#### `swapBankWithMutex(key: String, copy: Boolean = true)`
Thread-safe context window swapping operation using both `bankMutex` and `swapMutex`.

---

### Memory Eviction

#### `evictFromMemory(key: String): Boolean`
Removes a context window from memory without deleting the disk file. Returns true if removed.

#### `evictAllFromMemory()`
Removes all context windows from RAM without affecting disk persistence.

#### `evictTodoListFromMemory(key: String): Boolean`
Removes a TodoList from memory without deleting its `.todo` file.

---

### Cache Configuration

#### `configureCachePolicy(config: CacheConfig)`
Configures LRU/LFU/FIFO limits for `DISK_WITH_CACHE` storage mode.
```kotlin
ContextBank.configureCachePolicy(
    CacheConfig(
        maxMemoryBytes = 50 * 1024 * 1024,
        maxEntries = 100,
        evictionPolicy = EvictionPolicy.LRU
    )
)
```

#### `getCacheStatistics(): CacheStatistics`
Returns current hit rates, entry counts, and memory usage statistics.

#### `clearCache()`
Clears all DISK_WITH_CACHE entries from memory.

---

### TodoList Integration

#### `getPagedTodoList(key: String, copy: Boolean = true, skipRemote: Boolean = false): TodoList`
Retrieves a TodoList by its page key, respecting storage modes and remote settings.

#### `emplaceTodoList(key: String, todoList: TodoList, mode: StorageMode, allowUpdatesOnly: Boolean = true, allowCompletionsOnly: Boolean = false, skipRemote: Boolean = false)`
Stores a TodoList with optional write protections and storage mode control.

#### `getTodoListKeys(skipRemote: Boolean = false): List<String>`
Returns all todo list keys in the bank.

---

## Key Behaviors

### Thread Safety Model
Always use `WithMutex` methods inside coroutines. Mutexes are acquired in a consistent order (`bankMutex` -> `swapMutex`) to prevent deadlocks.

### Distributed Versioning
Remote operations use version-based conflict resolution. The server rejects writes with outdated versions, requiring the `fetchMergeSave` pattern to ensure data integrity.

### Storage Mode System
ContextBank supports five storage modes (MEMORY_ONLY, MEMORY_AND_DISK, DISK_ONLY, DISK_WITH_CACHE, REMOTE) for fine-grained memory, disk, and network management.
