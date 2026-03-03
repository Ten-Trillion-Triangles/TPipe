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
- [Storage Mode System](#storage-mode-system)
- [Key Behaviors](#key-behaviors)

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

**Behavior:** Uses serialization/deserialization for deep copying to ensure thread safety. Recommended for use within coroutines and concurrent operations.

#### `updateBankedContext(newContext: ContextWindow)`
Updates the currently active banked context window.

**Behavior:** Direct assignment without thread safety. **Warning:** Not safe for concurrent use. Use `updateBankedContextWithMutex()` for thread-safe updates.

#### `updateBankedContextWithMutex(newContext: ContextWindow)`
Thread-safe update of the currently active banked context window.

---

### Bank Management

#### `emplace(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`
Stores or replaces a context window with explicit storage mode control.

**Parameters:**
- `key`: The identifier for storing the context window
- `window`: The ContextWindow to store
- `mode`: Storage mode controlling memory and disk persistence behavior
- `skipRemote`: If true, skip remote delegation even if configured.

**Example:**
```kotlin
// Disk-only for large, infrequently accessed contexts
ContextBank.emplace("large-context", window, StorageMode.DISK_ONLY)
```

#### `emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`
Safely emplace a context window using the bank mutex. Recommended for updates inside pipes or pipelines.

#### `getContextFromBank(key: String, copy: Boolean = true, skipRemote: Boolean = false): ContextWindow`
Retrieves a context window from the bank by key, respecting storage mode and locking.

**Behavior:**
- **Page Lock Check**: Returns empty `ContextWindow()` if page is locked via ContextLock.
- **Remote Delegation**: If `StorageMode.REMOTE` or global remote memory is active, calls the configured remote server.
- **Copying**: By default, returns a deep copy for thread safety.

#### `deletePersistingBankKeyWithMutex(key: String, skipRemote: Boolean = false): Boolean`
Deletes the key file that is holding a persisting context bank key.

#### `getPageKeys(skipRemote: Boolean = false): List<String>`
Access function to get all the pages that are stored inside the context bank, including remote keys if available.

---

### Remote Memory Integration

#### `enableRemoteHosting(port: Int = 8080)`
Enable remote hosting for this TPipe instance's memory. This starts a Netty server on the specified port.

#### `connectToRemoteMemory(url: String, token: String = "", useGlobally: Boolean = false)`
Connect to a remote TPipe memory server.
- `url`: The base URL of the remote memory server.
- `token`: Optional authentication token.
- `useGlobally`: If true, all memory operations will delegate to the remote server regardless of StorageMode.

#### `fetchMergeSaveRemoteContext(key: String, localWindow: ContextWindow): Boolean`
Perform a fetch-merge-save operation on a remote context window. Helps resolve versioning conflicts by pulling the latest remote state and merging it locally before pushing back.

---

### Context Swapping

#### `swapBank(key: String, copy: Boolean = true)`
Bank swap the context window for one that is on another page.

**Warning:** Do not call this inside a coroutine or outside the main thread. Use `swapBankWithMutex` instead.

#### `swapBankWithMutex(key: String, copy: Boolean = true)`
Function to safely bank swap inside a coroutine or multithreaded environment. Uses both `bankMutex` and `swapMutex`.

---

### Memory Eviction

#### `evictFromMemory(key: String): Boolean`
Remove a context window from memory without deleting the disk file. Useful for freeing memory while keeping data persisted.

#### `evictAllFromMemory()`
Remove all context windows from memory without deleting disk files.

---

### Cache Configuration

#### `configureCachePolicy(config: CacheConfig)`
Configure cache policy for `DISK_WITH_CACHE` storage mode. Controls memory limits and eviction behavior (LRU, LFU, FIFO).

#### `getCacheStatistics(): CacheStatistics`
Get current cache statistics including memory usage and hit rates.

---

### TodoList Integration

#### `getPagedTodoList(key: String, copy: Boolean = true, skipRemote: Boolean = false): TodoList`
Get a todo list by its page key.

#### `emplaceTodoList(key: String, todoList: TodoList, mode: StorageMode, allowUpdatesOnly: Boolean, allowCompletionsOnly: Boolean, skipRemote: Boolean)`
Emplace a new todo list into the context bank with explicit storage mode and write-protection rules.

**Write Protection:**
- `allowUpdatesOnly`: Only existing tasks can be modified; no new tasks added.
- `allowCompletionsOnly`: Only the `isComplete` status of tasks can be changed.

---

## Storage Mode System

The `StorageMode` enum defines how context data is handled:

| Mode | Description |
|------|-------------|
| `MEMORY_ONLY` | Store only in memory, no disk persistence. Fastest access. |
| `MEMORY_AND_DISK` | Store in memory and persist to disk (Default). |
| `DISK_ONLY` | Store only on disk, load on-demand without caching. Memory efficient. |
| `DISK_WITH_CACHE` | Store on disk with LRU memory cache and automatic eviction. |
| `REMOTE` | Store context data on a remote server (requires `TPipeConfig` setup). |

## Key Behaviors

### Thread Safety Model
ContextBank uses specialized mutexes (`bankMutex`, `swapMutex`, `todoMutex`) to ensure safety in concurrent environments. Always use the `WithMutex` variants of functions when working within pipes or coroutines.

### Copy vs Reference
By default, retrieval functions return deep copies to prevent side effects across threads. If performance is critical and thread safety is managed elsewhere, you can set `copy = false` to receive a direct reference.

### Distributed State
With Remote Memory enabled, ContextBank becomes a gateway to shared state across multiple agent instances. The `fetchMergeSave` pattern is the recommended way to handle updates in these environments to avoid losing data due to race conditions.
