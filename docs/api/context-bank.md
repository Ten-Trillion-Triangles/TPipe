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

## Overview

The `ContextBank` is a singleton object that manages TPipe's global context window system, enabling context sharing between pipes, pipelines, and parallel operations through a keyed banking system. It supports multiple storage modes, including disk persistence and remote memory delegation.

```kotlin
object ContextBank
```

## Public Properties

**`swapMutex`**
```kotlin
val swapMutex: Mutex = Mutex()
```
Mutex for managing context window swapping operations.

**`bankMutex`**
```kotlin
val bankMutex: Mutex = Mutex()
```
Mutex for managing bank access operations.

**`todoMutex`**
```kotlin
val todoMutex: Mutex = Mutex()
```
Mutex for accessing the todo list system.

## Public Functions

### Current Context Access

#### `getBankedContextWindow(): ContextWindow`
Retrieves direct reference to the currently active banked context window.

#### `copyBankedContextWindow(): ContextWindow?`
Creates a deep copy of the currently active banked context window.

#### `updateBankedContext(newContext: ContextWindow)`
Updates the currently active banked context window (direct assignment).

#### `updateBankedContextWithMutex(newContext: ContextWindow)`
Thread-safe update of the currently active banked context window.

---

### Bank Management

#### `emplace(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`
Stores or replaces a context window with explicit storage mode control.

**Parameters:**
- `key`: The identifier for storing the context window
- `window`: The ContextWindow to store
- `mode`: Storage mode (`MEMORY_ONLY`, `MEMORY_AND_DISK`, `DISK_ONLY`, `DISK_WITH_CACHE`, `REMOTE`)
- `skipRemote`: If true, skip remote delegation even if configured.

#### `emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`
Thread-safe version of `emplace`.

#### `getContextFromBank(key: String, copy: Boolean = true, skipRemote: Boolean = false): ContextWindow`
Retrieves a context window from the bank by key, respecting storage mode and remote settings.

#### `deletePersistingBankKeyWithMutex(key: String, skipRemote: Boolean = false): Boolean`
Deletes the disk file holding a persisting context bank key in a thread-safe manner.

#### `getPageKeys(skipRemote: Boolean = false): List<String>`
Returns a list of all pages (keys) stored in the context bank, including remote keys if enabled.

---

### Remote Memory Integration

#### `enableRemoteHosting(port: Int = 8080)`
Starts a Netty server on the specified port to host this instance's memory for remote access.

#### `connectToRemoteMemory(url: String, token: String = "", useGlobally: Boolean = false)`
Configures this instance to use a remote memory server.

#### `fetchMergeSaveRemoteContext(key: String, localWindow: ContextWindow): Boolean`
Performs a fetch-merge-save operation on a remote context window to resolve versioning conflicts.

---

### Context Swapping

#### `swapBank(key: String, copy: Boolean = true)`
Swaps the active banked context window with one from the bank.

#### `swapBankWithMutex(key: String, copy: Boolean = true)`
Thread-safe context window swapping operation.

---

### Memory Eviction

#### `evictFromMemory(key: String): Boolean`
Removes a context window from memory without deleting the disk file.

#### `evictAllFromMemory()`
Removes all context windows from memory without deleting disk files.

---

### Cache Configuration

#### `configureCachePolicy(config: CacheConfig)`
Configures cache behavior for `DISK_WITH_CACHE` storage mode.

#### `getCacheStatistics(): CacheStatistics`
Returns current cache statistics (memory usage, hit rates).

#### `clearCache()`
Clears all `DISK_WITH_CACHE` entries from memory.

---

### TodoList Integration

#### `getPagedTodoList(key: String, copy: Boolean = true, skipRemote: Boolean = false): TodoList`
Retrieves a TodoList from storage.

#### `emplaceTodoList(key: String, todoList: TodoList, mode: StorageMode, allowUpdatesOnly: Boolean, allowCompletionsOnly: Boolean, skipRemote: Boolean)`
Stores a TodoList with explicit storage mode and write-protection rules.

#### `getTodoListKeys(skipRemote: Boolean = false): List<String>`
Returns all todo list keys in the bank, including remote keys if enabled.

---

## Storage Mode System

ContextBank supports five storage modes:

1.  **`MEMORY_ONLY`**: Fastest, no persistence.
2.  **`MEMORY_AND_DISK`**: Default, persists to disk and keeps in memory.
3.  **`DISK_ONLY`**: Memory efficient, loads from disk on every access.
4.  **`DISK_WITH_CACHE`**: Cached disk storage with automatic eviction (LRU/LFU/FIFO).
5.  **`REMOTE`**: Delegates storage to a remote memory server defined in `TPipeConfig`.
