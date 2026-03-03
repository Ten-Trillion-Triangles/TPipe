# ContextBank Object API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
  - [Current Context Access](#current-context-access)
  - [Bank Management](#bank-management)
  - [Remote Memory Integration](#remote-memory-integration)
  - [Context Swapping](#context-swapping)
  - [Memory Eviction & Cache Management](#memory-eviction--cache-management)
  - [TodoList Integration](#todolist-integration)
- [Storage Mode System](#storage-mode-system)
- [Cache Configuration and Eviction Policies](#cache-configuration-and-eviction-policies)
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

#### `copyBankedContextWindow(): ContextWindow?`
Creates a deep copy of the currently active banked context window.

#### `updateBankedContextWithMutex(newContext: ContextWindow)`
Thread-safe update of the currently active banked context window.

---

### Bank Management

#### `emplace(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`
Stores or replaces a context window with explicit storage mode control.

#### `emplaceWithMutex(key: String, window: ContextWindow, mode: StorageMode, skipRemote: Boolean = false)`
Safely emplace a context window using the bank mutex. Recommended for updates inside pipes or pipelines.

#### `getContextFromBank(key: String, copy: Boolean = true, skipRemote: Boolean = false): ContextWindow`
Retrieves a context window from the bank by key, respecting storage mode, locking, and cache state.

---

### Remote Memory Integration

#### `enableRemoteHosting(port: Int = 8080)`
Enable remote hosting for this TPipe instance's memory. This starts a Netty server on the specified port.

#### `connectToRemoteMemory(url: String, token: String = "", useGlobally: Boolean = false)`
Connect to a remote TPipe memory server.

#### `fetchMergeSaveRemoteContext(key: String, localWindow: ContextWindow): Boolean`
Perform a fetch-merge-save operation on a remote context window. Helps resolve versioning conflicts by pulling the latest remote state and merging it locally before pushing back.

---

### Context Swapping

#### `swapBank(key: String, copy: Boolean = true)`
Bank swap the context window for one that is on another page.

#### `swapBankWithMutex(key: String, copy: Boolean = true)`
Function to safely bank swap inside a coroutine or multithreaded environment. Uses both `bankMutex` and `swapMutex`.

---

### Memory Eviction & Cache Management

#### `evictFromMemory(key: String): Boolean`
Remove a context window from memory without deleting the disk file. Useful for freeing memory while keeping data persisted.

#### `evictAllFromMemory()`
Remove all context windows from memory without deleting disk files.

#### `configureCachePolicy(config: CacheConfig)`
Configure cache behavior for `DISK_WITH_CACHE` storage mode. Controls memory limits and eviction behavior.

**Example:**
```kotlin
ContextBank.configureCachePolicy(
    CacheConfig(
        maxMemoryBytes = 50 * 1024 * 1024,  // 50MB limit
        maxEntries = 100,                   // Keep 100 items in memory
        evictionPolicy = EvictionPolicy.LRU // Evict oldest accessed
    )
)
```

#### `getCacheStatistics(): CacheStatistics`
Returns a snapshot of current cache performance including hit rates and memory usage.

#### `clearCache()`
Clears all cached entries (`DISK_WITH_CACHE` mode only) from memory. Does not affect disk-persisted data.

---

### TodoList Integration

#### `getPagedTodoList(key: String, copy: Boolean = true, skipRemote: Boolean = false): TodoList`
Get a todo list by its page key.

#### `emplaceTodoList(key: String, todoList: TodoList, mode: StorageMode, allowUpdatesOnly: Boolean, allowCompletionsOnly: Boolean, skipRemote: Boolean)`
Emplace a new todo list into the context bank with explicit storage mode and write-protection rules.

---

## Storage Mode System

The `StorageMode` enum defines how context data is handled:

| Mode | Description |
|------|-------------|
| `MEMORY_ONLY` | Store only in memory, no disk persistence. Fastest access. |
| `MEMORY_AND_DISK` | Store in memory and persist to disk (Default). |
| `DISK_ONLY` | Store only on disk, load on-demand without caching. Memory efficient. |
| `DISK_WITH_CACHE` | Store on disk with memory cache and automatic eviction. |
| `REMOTE` | Store context data on a remote server. |

## Cache Configuration and Eviction Policies

When using `StorageMode.DISK_WITH_CACHE`, TPipe manages memory usage automatically based on a configurable `CacheConfig`.

### CacheConfig
- `maxEntries`: The maximum number of context windows to keep in memory (Default: 100).
- `maxMemoryBytes`: The maximum approximate memory size (Default: 100MB).
- `evictionPolicy`: The strategy for choosing which items to remove from memory when limits are hit.

### EvictionPolicy Options
- **`EvictionPolicy.LRU` (Least Recently Used)**: Evicts the entry that was accessed longest ago. Best for most general-purpose workloads.
- **`EvictionPolicy.LFU` (Least Frequently Used)**: Evicts the entry that has been accessed the fewest times. Best for workloads with "hot" data.
- **`EvictionPolicy.FIFO` (First In First Out)**: Evicts the oldest entries regardless of access patterns.
- **`EvictionPolicy.MANUAL`**: Disables automatic eviction; memory must be managed manually using `evictFromMemory`.

## Key Behaviors

### Thread Safety Model
ContextBank uses specialized mutexes (`bankMutex`, `swapMutex`, `todoMutex`) to ensure safety in concurrent environments. Always use the `WithMutex` variants of functions when working within pipes or coroutines.

### Automatic Eviction
When configured limits (`maxEntries` or `maxMemoryBytes`) are reached, ContextBank automatically triggers the selected `EvictionPolicy`. Only items stored with `DISK_WITH_CACHE` are candidates for automatic eviction. Items stored as `MEMORY_ONLY` or `MEMORY_AND_DISK` are protected and remain in memory until explicitly removed.
