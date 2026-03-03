# Managing Global Context with ContextBank

In a standard AI application, "memory" is often just a list of previous chat messages. But for complex, multi-agent systems, that's not enough. You need a way for different parts of your application to share knowledge, maintain long-term goals, and coordinate actions without losing track of what's important.

**ContextBank** is TPipe's centralized knowledge repository. It allows your agents to "drop off" information into named pages and "pick up" that information later, even in a different pipeline or session.

---

## The Mental Model: The Central Library

Think of your TPipe application as a research team:
- **Pipes** are researchers.
- **ContextWindows** are notebooks.
- **ContextBank** is the **Central Library**.

Researchers can check books out (load a page), add findings (save a page), or reserve sections (locking) so they don't get interrupted.

---

## Technical Core: Pages and Storage Modes

Everything in the `ContextBank` is organized by a **Page Key**. You control where that page lives based on your performance and durability needs:

| Mode | Location | Use Case |
|------|----------|----------|
| **`MEMORY_ONLY`** | RAM | Temporary observations or logs. |
| **`MEMORY_AND_DISK`** | RAM + File | **Default**. Essential agent state. |
| **`DISK_ONLY`** | File | Cold storage for massive datasets. |
| **`DISK_WITH_CACHE`**| RAM + File | Large knowledge bases needing fast access. |
| **`REMOTE`** | Network | Shared state for distributed agent swarms. |

---

## Advanced Management

### Cache and Eviction (Scalability)
If you have thousands of pages, you can't keep them all in RAM. Use `DISK_WITH_CACHE` and configure a global policy:

```kotlin
ContextBank.configureCachePolicy(
    CacheConfig(
        maxMemoryBytes = 500 * 1024 * 1024, // 500MB limit
        evictionPolicy = EvictionPolicy.LRU // Discard least recently used
    )
)
```

### Collaborative Patterns
When sharing state across agents, use the **Fetch-Merge-Save** pattern to prevent overwriting others' work:

```kotlin
// Safely update shared state 'swarm_goals'
ContextBank.fetchMergeSaveRemoteContext("swarm_goals", localUpdates)
```

---

## Best Practices

1. **Use Mutexes**: Always use `emplaceWithMutex` or `swapBankWithMutex` inside pipes to ensure thread safety.
2. **Key Namespacing**: Use descriptive keys like `session_123_intent_analysis` instead of generic names like `data`.
3. **Storage Tiering**: Use `MEMORY_ONLY` for session-scoped data to avoid unnecessary disk I/O.

---

## See Also
- **[ContextBank API Reference](../api/context-bank.md)**: Exhaustive technical signatures.
- **[Remote Memory guide](../advanced-concepts/remote-memory.md)**: Tutorial on distributed state.
