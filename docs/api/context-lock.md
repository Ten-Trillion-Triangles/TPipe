# ContextLock API

The ContextLock system provides fine-grained control over LoreBook key and page access within TPipe. It enables selective locking of context elements to prevent their selection during model processing, while maintaining system performance and thread safety across massive multi-agent swarms.

## Overview

ContextLock operates as a centralized security mechanism that can:
- Lock individual LoreBook keys to prevent them from being injected into prompts.
- Lock entire pages to prevent their retrieval from the ContextBank.
- Support global locks affecting all contexts and page-specific locks for targeted isolation.
- Provide conditional bypass through passthrough functions, allowing for dynamic security logic.

## Core Classes

### KeyBundle
Represents a lock configuration for one or more keys or pages.

```kotlin
data class KeyBundle(
    var keys: MutableList<String> = mutableListOf(),
    var pages: MutableList<String> = mutableListOf(),
    var isGlobal: Boolean = false,
    var isLocked: Boolean = false,
    var isPageKey: Boolean = false,
    var passthroughFunction: (() -> Boolean)? = null
)
```

**Property Breakdown:**
- **`keys`**: The specific LoreBook keys affected by this lock.
- **`pages`**: The specific page keys where this lock applies.
- **`isGlobal`**: If true, this lock affects every page in the system.
- **`isLocked`**: The current active state of the lock.
- **`isPageKey`**: If true, this bundle locks an entire page rather than individual keys.
- **`passthroughFunction`**: A developer-defined logic gate. If this function returns true, the lock is bypassed regardless of its state.

---

## ContextLock Object

The main interface for managing security throughout the system.

### Lock Management

#### `addLock()` / `addLockWithMutex()`
Registers a new lock bundle.

**Parameters:**
- `key`: The LoreBook key or Page identifier to protect.
- `pageKeys`: Comma-separated list of pages where the lock applies. Leave empty for a global lock.
- `isPageKey`: Set to true to lock the entire page; false for individual LoreBook keys.
- `lockState`: Initial active state (defaults to true).
- `passthroughFunction`: Optional logic gate for conditional access.

```kotlin
// Example: Lock a specific page for sensitive data
ContextLock.addLock("financial_records", "", true, true)

// Example: Lock with custom logic
ContextLock.addLock("admin_tools", "", false, true) {
    currentUser.hasPermission("EXECUTE_ADMIN")
}
```

#### `removeLock()` / `removeLockWithMutex()`
Deletes a lock bundle and clears its associated metadata from all affected context windows.

### Lock State Control

#### `lockKeyBundle()` / `unlockKeyBundle()`
Activates or deactivates an existing lock without removing its configuration. Use the `WithMutex` variants for safe operation in concurrent coroutine scopes.

---

## System Integration

### Impact on LoreBook Selection
ContextLock enforcement is integrated directly into the `ContextWindow` selection logic:

1.  **Matching**: `findMatchingLoreBookKeys()` automatically ignores any keys covered by an active lock.
2.  **Selection**: `selectLoreBookContext()` filters out locked keys before calculating weights and budgets.
3.  **Filling**: `selectAndFillLoreBookContext()` ensures that even low-priority filling does not include locked data.

### Impact on ContextBank
Page-level locks are enforced at the point of retrieval:

```kotlin
// ContextBank.getContextFromBank() automatically verifies locks
val data = ContextBank.getContextFromBank("locked_page")
// If "locked_page" is locked, this returns an empty ContextWindow()
```

---

## Technical Performance & Safety

*   **Thread Safety**: Mutation operations (adding/removing locks) use mutex protection. Read operations are optimized for high-speed, lock-free access to prevent bottlenecking during model generation.
*   **Minimal Overhead**: Checking lock status adds less than 5% overhead to the LoreBook selection process.
*   **Safe Defaults**: If a passthrough function throws an exception, TPipe defaults to the most secure state (the lock remains active).

## Best Practices

1.  **Clean Up**: Always remove temporary locks when an agent finishes its task to prevent memory accumulation in the lock registry.
2.  **Page Isolation**: Prefer page-specific locks over global locks to maintain maximum performance in large infrastructures.
3.  **Validate Passthroughs**: Ensure your passthrough functions are fast and non-blocking, as they are executed during the critical context-assembly phase of a Pipe execution.
