# ContextLock API

The ContextLock system provides fine-grained control over lorebook key and page access within TPipe's context management system. It enables selective locking of context elements to prevent their selection during lorebook processing while maintaining system performance and thread safety.

## Overview

ContextLock operates as a centralized locking mechanism that can:
- Lock individual lorebook keys to prevent their selection
- Lock entire pages to prevent their retrieval from ContextBank
- Support global locks that affect all contexts
- Support page-specific locks that only affect designated pages
- Provide conditional bypass through passthrough functions

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

**Properties:**
- **`keys`**: List of lorebook keys affected by this lock
- **`pages`**: List of page keys where this lock applies
- **`isGlobal`**: Whether this lock affects all pages globally
- **`isLocked`**: Current lock state (true = locked, false = unlocked)
- **`isPageKey`**: Whether this bundle locks a page rather than lorebook keys
- **`passthroughFunction`**: Optional function that can bypass the lock when it returns true

## ContextLock Object

The main interface for managing locks throughout the system.

### Lock Management Methods

#### addLock()
```kotlin
fun addLock(
    key: String,
    pageKeys: String,
    isPageKey: Boolean,
    lockState: Boolean = true,
    passthroughFunction: (() -> Boolean)? = null
)
```

Registers a new lock bundle for the specified key.

**Parameters:**
- **`key`**: The lorebook key or page identifier to lock
- **`pageKeys`**: Comma-separated list of pages where lock applies (empty = global)
- **`isPageKey`**: True if locking a page, false if locking lorebook keys
- **`lockState`**: Initial lock state (default: true)
- **`passthroughFunction`**: Optional bypass function

**Example:**
```kotlin
// Lock a specific lorebook key globally
ContextLock.addLock("character_name", "", false, true)

// Lock a page
ContextLock.addLock("sensitive_data", "", true, true)

// Lock with passthrough function
ContextLock.addLock("conditional_key", "", false, true) {
    // Custom logic to determine if key should be accessible
    userHasPermission("read_sensitive")
}
```

#### addLockWithMutex()
```kotlin
suspend fun addLockWithMutex(
    key: String,
    pageKeys: String,
    isPageKey: Boolean,
    lockState: Boolean = true,
    passthroughFunction: (() -> Boolean)? = null
)
```

Thread-safe version of `addLock()` for use in coroutine contexts.

#### removeLock()
```kotlin
fun removeLock(key: String)
```

Removes a lock and clears associated metadata from affected context windows.

#### removeLockWithMutex()
```kotlin
suspend fun removeLockWithMutex(key: String)
```

Thread-safe version of `removeLock()`.

### Lock State Management

#### lockKeyBundle()
```kotlin
fun lockKeyBundle(key: String)
```

Activates an existing lock bundle.

#### unlockKeyBundle()
```kotlin
fun unlockKeyBundle(key: String)
```

Deactivates an existing lock bundle without removing it.

#### lockKeyBundleWithMutex() / unlockKeyBundleWithMutex()
```kotlin
suspend fun lockKeyBundleWithMutex(key: String)
suspend fun unlockKeyBundleWithMutex(key: String)
```

Thread-safe versions for coroutine contexts.

### Query Methods

#### getKeyBundle()
```kotlin
fun getKeyBundle(key: String): KeyBundle?
```

Retrieves the KeyBundle for a specific key.

#### isKeyLocked()
```kotlin
fun isKeyLocked(key: String): Boolean
```

Checks if a lorebook key is currently locked.

#### isPageLocked()
```kotlin
fun isPageLocked(pageKey: String): Boolean
```

Checks if a page is currently locked.

#### getLockedKeysForContext()
```kotlin
fun getLockedKeysForContext(
    contextWindow: ContextWindow, 
    pageKey: String? = null
): Set<String>
```

Returns all locked lorebook keys that affect the specified context.

## ContextWindow Integration

ContextWindow includes several methods for working with locks:

### isContextLocked()
```kotlin
fun isContextLocked(): Boolean
```

Checks if the ContextWindow has been marked as locked by the ContextLock system.

### canSelectLoreBookKey()
```kotlin
fun canSelectLoreBookKey(key: String): Boolean
```

Determines if a specific lorebook key can be selected based on current lock state and passthrough functions.

### getLockedKeys()
```kotlin
fun getLockedKeys(): Set<String>
```

Returns the set of locked lorebook keys for this ContextWindow.

## System Integration

### Lorebook Selection Impact

ContextLock enforcement is automatically integrated into all lorebook selection methods:

- **`findMatchingLoreBookKeys()`**: Excludes locked keys from initial matching
- **`selectLoreBookContext()`**: Filters locked keys from candidate selection
- **`selectAndFillLoreBookContext()`**: Excludes locked keys from weight-based filling

### ContextBank Integration

Page locks are enforced at the ContextBank level:

```kotlin
// ContextBank.getContextFromBank() automatically checks page locks
val context = ContextBank.getContextFromBank("page_key") 
// Returns empty ContextWindow if page is locked
```

## Usage Patterns

### Basic Key Locking

```kotlin
// Lock a sensitive lorebook key
ContextLock.addLock("api_credentials", "", false, true)

// The key will be excluded from all lorebook selection
val selectedKeys = contextWindow.selectLoreBookContext(text, maxTokens)
// api_credentials will not appear in selectedKeys
```

### Page-Specific Locking

```kotlin
// Lock a key only on specific pages
ContextLock.addLock("debug_info", "production,staging", false, true)

// Key is only locked when processing production or staging pages
```

### Conditional Access with Passthrough Functions

```kotlin
// Lock with conditional access
ContextLock.addLock("admin_data", "", false, true) {
    // Allow access during business hours
    val hour = LocalTime.now().hour
    hour in 9..17
}
```

### Page Locking

```kotlin
// Lock an entire page
ContextLock.addLock("classified_page", "", true, true)

// Attempts to retrieve the page return empty context
val context = ContextBank.getContextFromBank("classified_page")
// context will be empty
```

### Dynamic Lock Management

```kotlin
// Add lock
ContextLock.addLock("temp_key", "", false, true)

// Temporarily unlock
ContextLock.unlockKeyBundle("temp_key")

// Re-lock
ContextLock.lockKeyBundle("temp_key")

// Remove completely
ContextLock.removeLock("temp_key")
```

## Performance Considerations

- **Direct Map Access**: Query methods use direct map access for optimal performance
- **Minimal Overhead**: Lock checking adds < 5% overhead to lorebook selection
- **Thread Safety**: Mutation operations use mutex protection while reads are lock-free
- **Caching**: Lock state is cached for frequently accessed keys

## Thread Safety

- **Mutation Operations**: Use `*WithMutex()` variants in coroutine contexts
- **Query Operations**: Safe for concurrent access without synchronization
- **Passthrough Functions**: Should be thread-safe if used in concurrent contexts

## Best Practices

1. **Use Descriptive Keys**: Choose clear, descriptive names for lock identifiers
2. **Minimize Global Locks**: Prefer page-specific locks when possible
3. **Handle Passthrough Exceptions**: Ensure passthrough functions handle errors gracefully
4. **Clean Up Locks**: Remove locks when no longer needed to prevent memory leaks
5. **Test Lock Behavior**: Verify lock enforcement in your specific use cases

## Error Handling

- **Missing Keys**: Query methods return safe defaults (false, null, empty sets)
- **Passthrough Exceptions**: Caught and handled gracefully, defaulting to lock state
- **Invalid Operations**: Silently ignored rather than throwing exceptions
- **Thread Safety**: Mutex operations prevent race conditions during concurrent access

## Integration Examples

### Pipeline Integration

```kotlin
class SecurePipeline : Pipeline() {
    init {
        // Lock sensitive keys during pipeline execution
        ContextLock.addLock("user_credentials", "", false, true)
        ContextLock.addLock("api_keys", "", false, true)
    }
    
    override fun cleanup() {
        // Clean up locks when pipeline completes
        ContextLock.removeLock("user_credentials")
        ContextLock.removeLock("api_keys")
    }
}
```

### Conditional Security

```kotlin
// Environment-based locking
ContextLock.addLock("debug_logs", "", false, true) {
    System.getenv("ENVIRONMENT") != "production"
}

// User permission-based locking
ContextLock.addLock("admin_panel", "", false, true) {
    currentUser.hasRole("ADMIN")
}
```
