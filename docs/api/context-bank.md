# ContextBank Object API

In a massive agentic infrastructure, you don't just need local storage; you need a **Central Reservoir**. `ContextBank` is the global repository for TPipe, allowing you to share, persist, and synchronize context across multiple applications, sessions, or even distributed agents.

While a `ContextWindow` is your local "tank" for a single conversation, `ContextBank` is the municipal water tower that serves the entire city.

```kotlin
object ContextBank
```

## Table of Contents
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
  - [Current Context Access](#current-context-access)
  - [Bank Management](#bank-management)
  - [Storage Mode Management](#storage-mode-management)
  - [Memory Eviction](#memory-eviction)
  - [Cache Configuration](#cache-configuration)
  - [Context Swapping](#context-swapping)
  - [TodoList Integration](#todolist-integration)
- [Key Behaviors](#key-behaviors)

## Public Properties

**`swapMutex`**
```kotlin
val swapMutex: Mutex = Mutex()
```
The Switching Gear mutex. Ensures thread-safe access when changing which reservoir (context window) is currently active.

**`bankMutex`**
```kotlin
val bankMutex: Mutex = Mutex()
```
The Vault Door mutex. Ensures thread-safe access when reading from or writing to the global bank storage.

## Public Functions

### Current Context Access: Drawing from the Reservoir

#### `getBankedContextWindow(): ContextWindow`
Retrieves a direct reference to the currently active banked context.

> [!CAUTION]
> **Not Thread-Safe**: This returns the actual object reference. Use `copyBankedContextWindow()` if you are working with multiple coroutines.

#### `copyBankedContextWindow(): ContextWindow?`
Creates a deep, thread-safe copy of the active banked context. This is the recommended way to draw data for concurrent operations.

#### `updateBankedContextWithMutex(newContext: ContextWindow)`
Thread-safely replaces the currently active banked context with a new reservoir.

---

### Bank Management: The Vault Operations

#### `emplace(key: String, window: ContextWindow, mode: StorageMode)`
Stores a context window in the bank. You can choose how it's stored using `StorageMode`:

*   **`MEMORY_ONLY`**: Fast but lost on restart.
*   **`MEMORY_AND_DISK`**: The standard (default). Fast and persistent.
*   **`DISK_ONLY`**: Memory-efficient. Loads from disk only when requested.
*   **`DISK_WITH_CACHE`**: Automatically caches "hot" data and evicts the rest.

#### `getContextFromBank(key: String, copy: Boolean = true): ContextWindow`
Retrieves a specific "Page" (reservoir) from the bank by its key. Respects all security locks and storage modes.

---

### Memory Eviction: Managing the Load

#### `evictFromMemory(key: String): Boolean`
Flushes a specific context from memory without deleting its disk file. Use this to keep your "Control Room" lean.

#### `evictAllFromMemory()`
Clears all context windows from memory, forcing them to be re-loaded from disk on next access.

---

### Cache Configuration: The Filtration System

#### `configureCachePolicy(config: CacheConfig)`
Sets the rules for the `DISK_WITH_CACHE` storage mode. You can define memory limits and eviction policies like **LRU** (Least Recently Used) or **LFU** (Least Frequently Used).

#### `getCacheStatistics(): CacheStatistics`
Returns a status report on your cache: memory usage, entry counts, and hit rates.

---

### Context Swapping: Changing the Mainline

#### `swapBankWithMutex(key: String)`
Thread-safely swaps the currently active context with another one from the vault. This is the industrial-standard way to switch between different "Mindsets" or "Sessions" in a single pipeline.

---

### TodoList Integration: Task Management

The Bank also serves as the storage for **TodoLists**, enabling task tracking that survives restarts.

#### `getPagedTodoList(key: String, copy: Boolean = true): TodoList`
Retrieves a task list from the bank's specialized Todo storage.

#### `emplaceTodoList(key: String, todoList: TodoList, writeToDisk: Boolean, overwrite: Boolean)`
Stores a task list in the bank. If `writeToDisk` is true, the tasks are saved to `~/.tpipe/TPipe-Default/memory/todo/<key>.todo`.

---

## Key Behaviors

### Thread Safety Model
ContextBank provides both "Fast" (non-mutex) and "Safe" (mutex) methods. In production agent swarms, always use the `WithMutex` variants to prevent data corruption during simultaneous "pumping" operations.

### Copy vs. Reference
Drawing a **Copy** (via serialization) ensures that modifications in one pipe won't accidentally leak into other parallel pipes. Drawing a **Reference** is faster but should only be used in single-threaded, isolated logic.

### Fallback Behavior
The Bank is designed for reliability. If you request a key that doesn't exist, it returns an empty `ContextWindow()` rather than "bursting" (throwing an error).
