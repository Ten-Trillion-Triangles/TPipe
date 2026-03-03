# ContextBank - Global Context Integration

## Table of Contents
- [What is ContextBank?](#what-is-contextbank)
- [Core ContextBank Operations](#core-contextbank-operations)
- [Storage Modes](#storage-modes)
- [Remote Memory Integration](#remote-memory-integration)
- [Common Usage Patterns](#common-usage-patterns)
- [Integration with Pipes](#integration-with-pipes)
- [Advanced Usage Patterns](#advanced-usage-patterns)
- [Best Practices](#best-practices)

ContextBank is TPipe's global context management system that enables context sharing across pipes, pipelines, and even separate applications. It acts as a centralized repository where context can be stored, retrieved, and shared between different processing stages.

## What is ContextBank?

ContextBank is a singleton object that provides:
- **Global context storage**: Share context across multiple pipes and pipelines
- **Named context pages**: Store different contexts with unique keys
- **Thread-safe operations**: Mutex-protected operations for concurrent access
- **Context persistence**: Maintain context across processing sessions
- **Remote delegation**: Share context between different TPipe instances

```kotlin
object ContextBank {
    // Global context storage with named keys
    private var bank = mutableMapOf<String, ContextWindow>()
    
    // Currently active context window
    private var bankedContextWindow = ContextWindow()
}
```

## Core ContextBank Operations

### Storing Context
```kotlin
// Store context with a specific key
val contextWindow = ContextWindow()
contextWindow.contextElements.add("Important information to share")

// Thread-safe storage (recommended for pipes)
ContextBank.emplaceWithMutex("sessionData", contextWindow, mode = StorageMode.MEMORY_AND_DISK)
```

### Retrieving Context
```kotlin
// Get context by key (returns copy by default for safety)
val retrievedContext = ContextBank.getContextFromBank("sessionData")

// Get the currently active banked context
val activeContext = ContextBank.getBankedContextWindow()
val safeCopy = ContextBank.copyBankedContextWindow()
```

## Storage Modes

ContextBank supports five storage modes to control memory and persistence behavior:

| Mode | Behavior | Use Case |
|------|----------|----------|
| `MEMORY_ONLY` | Store only in memory. | Temporary data, high-frequency access. |
| `MEMORY_AND_DISK` | Store in memory and persist to disk (Default). | Standard application state. |
| `DISK_ONLY` | Store on disk, load on-demand without caching. | Large, infrequently accessed contexts. |
| `DISK_WITH_CACHE` | Store on disk with LRU memory cache. | Large datasets with "hot" entries. |
| `REMOTE` | Delegate storage to a remote memory server. | Shared state between multiple agents. |

## Remote Memory Integration

ContextBank can be configured to delegate its operations to a remote TPipe server, enabling distributed agent coordination.

```kotlin
// Connect to a remote memory host
ContextBank.connectToRemoteMemory(
    url = "https://memory.example.com",
    token = "secure-token",
    useGlobally = true
)
```

For more details, see the **[Remote Memory System](../advanced-concepts/remote-memory.md)** guide.

## Common Usage Patterns

### Pattern 1: Temporary Context Storage
```kotlin
// Store intermediate results for later use
suspend fun storeChapterContent(content: MultimodalContent): MultimodalContent {
    val chapterWindow = ContextWindow()
    chapterWindow.contextElements.add(content.text)
    
    // Store for use by other pipes in the pipeline
    ContextBank.emplaceWithMutex("prevChapter", chapterWindow, mode = StorageMode.MEMORY_ONLY)
    return content
}
```

### Pattern 2: Context Retrieval and Processing
```kotlin
// Retrieve stored context for validation or processing
suspend fun validateWithStoredContext(content: MultimodalContent): Boolean {
    val prevChapter = ContextBank.getContextFromBank("prevChapter")
    
    // Use stored context for validation logic
    if (prevChapter.contextElements.isNotEmpty()) {
        val previousContent = prevChapter.contextElements[0]
        return validateConsistency(content.text, previousContent)
    }
    
    return true
}
```

## Best Practices

### 1. Always Use Mutex Operations in Pipes
```kotlin
// Good: Thread-safe operations
ContextBank.emplaceWithMutex("key", contextWindow, mode = StorageMode.MEMORY_AND_DISK)
ContextBank.swapBankWithMutex("key")
```

### 2. Choose the Right Storage Mode
Use `DISK_WITH_CACHE` for large knowledge bases and `MEMORY_ONLY` for transient session state to optimize performance and resource usage.

### 3. Use Remote Memory for Distributed Swarms
If you are running multiple independent TPipe processes that need to coordinate, use `StorageMode.REMOTE` or enable global remote memory.

## Next Steps

Now that you understand global context management, learn about remote coordination:

**→ [Remote Memory System](../advanced-concepts/remote-memory.md)** - Distributed memory
