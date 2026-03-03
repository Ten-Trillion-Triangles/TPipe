# ContextBank Object API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
  - [Current Context Access](#current-context-access)
  - [Bank Management](#bank-management)
  - [Remote Memory Integration](#remote-memory-integration)
  - [Context Swapping](#context-swapping)
  - [Memory Eviction](#memory-eviction)
  - [Cache Configuration](#cache-configuration)
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
**Warning:** Not safe for use in coroutines. Use `copyBankedContextWindow()` for concurrent access.

#### `copyBankedContextWindow(): ContextWindow?`
Creates a deep copy of the currently active banked context window via serialization.

#### `updateBankedContext(newContext: ContextWindow)`
Updates the currently active banked context window directly.

#### `updateBankedContextWithMutex(newContext: ContextWindow)`
Thread-safe update of the currently active banked context window using `bankMutex`.

---

### Bank Management

#### `emplace(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`
Stores or replaces a context window with explicit storage mode control.
- `key`: Identifier for the window.
- `window`: The `ContextWindow` to store.
- `mode`: Controls persistence (MEMORY_ONLY, MEMORY_AND_DISK, DISK_ONLY, DISK_WITH_CACHE, REMOTE).
- `skipRemote`: If true, bypasses remote delegation even if configured.

#### `emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`
Thread-safe version of `emplace`. Recommended for updates inside pipes.

#### `getContextFromBank(key: String, copy: Boolean = true, skipRemote: Boolean = false): ContextWindow`
Retrieves a context window by key. respect storage mode and remote delegation.
- `copy`: Returns a deep copy if true (default).
- `skipRemote`: Bypasses remote server if true.

#### `getPageKeys(skipRemote: Boolean = false): List<String>`
Returns all stored page keys, merging local and remote keys if applicable.

#### `deletePersistingBankKeyWithMutex(key: String, skipRemote: Boolean = false): Boolean`
Thread-safe deletion of a banked context and its associated disk file.

---

### Remote Memory Integration

#### `enableRemoteHosting(port: Int = 8080)`
Starts a Netty server on the specified port to host this instance's memory for remote access.

#### `connectToRemoteMemory(url: String, token: String = "", useGlobally: Boolean = false)`
Configures the instance to delegate memory operations to a remote server.

#### `fetchMergeSaveRemoteContext(key: String, localWindow: ContextWindow): Boolean`
Performs a fetch-merge-save operation on a remote window. Resolves conflicts by pulling the latest remote version, merging local changes, and pushing back with an incremented version.

---

### Context Swapping

#### `swapBank(key: String, copy: Boolean = true)`
Swaps the active `bankedContextWindow` with one from the bank. **Warning:** Not safe for concurrent use.

#### `swapBankWithMutex(key: String, copy: Boolean = true)`
Thread-safe bank swap using both `bankMutex` and `swapMutex`.

---

### Memory Eviction

#### `evictFromMemory(key: String): Boolean`
Removes a context window from memory without deleting the disk file.

#### `evictAllFromMemory()`
Removes all context windows from memory (RAM only).

#### `evictTodoListFromMemory(key: String): Boolean`
Removes a TodoList from memory without deleting its `.todo` file.

---

### Cache Configuration

#### `configureCachePolicy(config: CacheConfig)`
Configures LRU/LFU/FIFO limits for `DISK_WITH_CACHE` storage mode.
```kotlin
ContextBank.configureCachePolicy(CacheConfig(maxMemoryBytes = 50 * 1024 * 1024))
```

#### `getCacheStatistics(): CacheStatistics`
Returns current hit rates and memory usage statistics.

#### `clearCache()`
Clears all DISK_WITH_CACHE entries from memory.

---

### TodoList Integration

#### `getPagedTodoList(key: String, copy: Boolean = true, skipRemote: Boolean = false): TodoList`
Retrieves a TodoList by key, respecting storage modes.

#### `emplaceTodoList(key: String, todoList: TodoList, mode: StorageMode, allowUpdatesOnly: Boolean, allowCompletionsOnly: Boolean, skipRemote: Boolean)`
Stores a TodoList with optional write protections.

#### `getTodoListKeys(skipRemote: Boolean = false): List<String>`
Returns all todo list keys in the bank.

---

## Key Behaviors

### Storage Modes
- **MEMORY_ONLY**: Fast, ephemeral.
- **MEMORY_AND_DISK**: Default, persists to `.bank` file.
- **DISK_ONLY**: Loads from disk on demand, no RAM caching.
- **DISK_WITH_CACHE**: Manages RAM usage via `CacheConfig` policies.
- **REMOTE**: Delegates to a `MemoryServer` via HTTP.

### Thread Safety
Always use `WithMutex` methods inside coroutines. Mutexes are acquired in a consistent order (`bankMutex` -> `swapMutex`) to prevent deadlocks.

### Distributed Versioning
Remote operations use version-based conflict resolution. Server rejects writes with outdated versions, requiring a `fetchMergeSave` cycle.
