# ContextBank Object API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
  - [Current Context Access](#current-context-access)
  - [Bank Management](#bank-management)
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

#### `emplace(key: String, window: ContextWindow, persistToDisk: Boolean = false)`
Stores or replaces a context window in the bank with the specified key.

**Parameters:**
- `key`: The identifier for storing the context window
- `window`: The ContextWindow to store
- `persistToDisk`: When true, forces the context window to be saved to disk. When false (default), only saves to disk if a bank file already exists for this key

**Behavior:** Direct map assignment without thread safety. Automatically handles disk persistence based on the `persistToDisk` parameter and existing file state. **Warning:** Not safe for concurrent use. Use `emplaceWithMutex()` for thread-safe operations.

#### `emplaceWithMutex(key: String, window: ContextWindow, persistToDisk: Boolean = false)`
Thread-safe storage or replacement of a context window in the bank.

**Parameters:**
- `key`: The identifier for storing the context window
- `window`: The ContextWindow to store
- `persistToDisk`: When true, forces the context window to be saved to disk. When false (default), only saves to disk if a bank file already exists for this key

**Behavior:** Uses `bankMutex` to ensure exclusive access during bank modification. Automatically handles disk persistence based on the `persistToDisk` parameter and existing file state. Recommended method for updating bank contents in concurrent environments.

#### `getContextFromBank(key: String, copy: Boolean = true): ContextWindow`
Retrieves a context window from the bank by key.

**Behavior:** 
- **With `copy = true` (default)**: Returns deep copy via serialization for thread safety
- **With `copy = false`**: Returns direct reference for performance but without thread safety
- **Missing key**: Returns empty `ContextWindow()` if key doesn't exist
- **Copy failure**: Returns empty `ContextWindow()` if serialization fails

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

All mutex-protected operations follow these patterns:

**Single Mutex Operations:**
- `emplaceWithMutex()` - Uses `bankMutex` for bank modifications
- `updateBankedContextWithMutex()` - Uses `bankMutex` for banked context updates

**Dual Mutex Operations:**
- `swapBankWithMutex()` - Uses both `bankMutex` and `swapMutex` for comprehensive safety during context swapping

**Mutex Ordering:** When multiple mutexes are required, they are acquired in consistent order (`bankMutex` then `swapMutex`) to prevent deadlocks.

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
- `key` - The page key identifying the TodoList
- `copy` - If `true`, returns a deep copy; if `false`, returns direct reference

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
- `key` - Unique identifier for this TodoList
- `todoList` - The TodoList object to store
- `writeToDisk` - If `true`, persists to `~/.tpipe/TPipe-Default/memory/todo/<key>.todo`
- `overwrite` - If `true`, replaces existing TodoList with same key; if `false`, merges with existing

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

## Key Behaviors

### Thread Safety Model
ContextBank provides both thread-safe and non-thread-safe variants of operations. Non-mutex methods offer better performance but require careful usage in single-threaded contexts. Mutex methods ensure safety in concurrent environments at the cost of synchronization overhead.

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
