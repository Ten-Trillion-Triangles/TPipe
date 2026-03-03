# ContextBank API Reference

The `ContextBank` is the central clearinghouse for all global state in TPipe. It manages the "notebooks" (`ContextWindow`) and "todo lists" (`TodoList`) that your agents share.

---

## Shared Access (Mutexes)

Because TPipe is built for highly concurrent, multi-agent systems, thread safety is paramount. ContextBank provides three primary mutexes to ensure your data stays consistent even when dozens of agents are reading and writing simultaneously.

| Mutex | Purpose |
|-------|---------|
| `bankMutex` | Protects the main storage of `ContextWindow` pages. |
| `swapMutex` | Ensures only one agent can change the "active" banked window at a time. |
| `todoMutex` | Protects the shared `TodoList` registry. |

> **Pro Tip**: When writing code inside a Pipe's transformation or validation function, always use the `WithMutex` variants of the functions below.

---

## Core Operations

### Storing Data (`emplace`)
Adds or updates a page in the bank.

```kotlin
suspend fun emplaceWithMutex(
    key: String,
    window: ContextWindow,
    mode: StorageMode = StorageMode.MEMORY_AND_DISK,
    skipRemote: Boolean = false
)
```
- **`key`**: The unique name for this page (e.g., "user_preferences").
- **`window`**: The data object to store.
- **`mode`**: Controls whether the data stays in RAM, goes to disk, or is sent to a remote server. See [Storage Modes](#storage-modes).
- **`skipRemote`**: If true, forces the data to be stored locally even if remote memory is configured globally.

### Retrieving Data (`getContextFromBank`)
Pulls a page from the bank.

```kotlin
fun getContextFromBank(
    key: String,
    copy: Boolean = true,
    skipRemote: Boolean = false
): ContextWindow
```
- **`copy`**: If `true` (default), returns a deep copy to prevent your agent from accidentally modifying the master copy in the bank. Set to `false` for high-performance, read-only access.

### Changing the Active View (`swapBank`)
Sets the singleton `bankedContextWindow` to a specific page. This is used when you want a pipe to automatically "see" a specific set of banked data without manually loading it.

```kotlin
suspend fun swapBankWithMutex(key: String, copy: Boolean = true)
```

---

## Remote Memory & Versioning

These functions power TPipe's distributed swarm capabilities.

### `enableRemoteHosting(port: Int)`
Turns this TPipe instance into a **Memory Server**. Other instances can then connect to this machine to share data.

### `connectToRemoteMemory(url: String, token: String, useGlobally: Boolean)`
Points this agent at an external Memory Server. If `useGlobally` is true, your agent will no longer save data to its own disk; it will send everything to the server instead.

### `fetchMergeSaveRemoteContext(key: String, localWindow: ContextWindow)`
The standard pattern for safe collaboration. It fetches the latest remote data, merges your local changes into it, and saves it back—all while respecting the server's versioning to prevent data loss.

---

## Cache & Memory Management

TPipe allows you to handle massive amounts of data by automatically moving "cold" pages to disk.

### `configureCachePolicy(config: CacheConfig)`
Sets the global rules for RAM usage.
```kotlin
val config = CacheConfig(
    maxEntries = 100,                   // Keep at most 100 pages in RAM
    maxMemoryBytes = 100 * 1024 * 1024, // Keep RAM usage under 100MB
    evictionPolicy = EvictionPolicy.LRU // Discard oldest accessed items first
)
ContextBank.configureCachePolicy(config)
```

---

## See Also
- [Conceptual Guide: Managing Global Context](../core-concepts/context-bank-integration.md)
- [Conceptual Guide: Remote Memory System](../advanced-concepts/remote-memory.md)
