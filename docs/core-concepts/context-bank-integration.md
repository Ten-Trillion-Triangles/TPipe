# ContextBank - The Central Reservoir

In a distributed agentic infrastructure, you often need context that lives beyond a single conversation or session. `ContextBank` is the **Central Reservoir** for TPipe. It is a global repository that allows you to share, persist, and synchronize context across multiple applications, distributed agents, and long-running pipelines.

While a `ContextWindow` is your local tank for an individual interaction, `ContextBank` is the industrial water tower that serves your entire facility.

## Why use ContextBank?

*   **Persistence**: Securely save and load your agent's state—including its LoreBooks, history, and custom rules—across application restarts.
*   **Global Access**: Allow separate pipelines to draw from the same specialized knowledge reservoir.
*   **High-Concurrency Syncing**: Synchronize context between multiple agents working in parallel using version-based conflict resolution.
*   **Remote Storage**: Connect to a remote `MemoryServer` to share knowledge across different physical machines.

## Page Keys: Organizing the Reservoir

The Bank is organized into **Page Keys**. Each key represents a distinct reservoir (a `ContextWindow`) within the Bank. This allows you to isolate data for different users, projects, or knowledge domains.

```kotlin
// Initialize the Bank with a disk storage path
ContextBank.init("path/to/storage")

// Access a specialized reservoir (Page)
val safetyManual = ContextBank.getPage("industrial_safety")
```

---

## The Synchronization Cycle

ContextBank provides a high-reliability helper called `fetchMergeSaveRemoteContext`. This is designed for environments where multiple agents might be reading from and writing to the same global reservoir at once.

1.  **Fetch**: TPipe pulls the latest version of the context from the global Bank (or remote server).
2.  **Merge**: It intelligently combines that data with your local modifications using specific conflict resolution rules.
3.  **Save**: It pushes the updated, unified context back to the central Bank.

```kotlin
contextBank.fetchMergeSaveRemoteContext(
    key = "site_logs",
    localContext = currentMemory,
    storageMode = StorageMode.REMOTE
)
```

---

## Remote Access: The Pumping Station

`ContextBank` can delegate its operations to a remote REST-based API. This allows you to host your infrastructure's knowledge on a dedicated memory server that all your agents can reach over the network.

```kotlin
// Configure the Bank for remote network access
TPipeConfig.setRemoteMemoryUrl("https://memory.internal.net")
TPipeConfig.setRemoteMemoryAuth("secure-token-123")

// This operation now automatically executes a network call to the server
ContextBank.loadPage("global_rules", mode = StorageMode.REMOTE)
```

---

## Security: The Vault Logic

When multiple agents are drawing from the same central reservoir, data security is paramount. TPipe integrates `ContextBank` with the **ContextLock** system to ensure that sensitive data remains "valved off" from unauthorized access.

*   **Access Enforcement**: Only agents with the correct credentials or in the correct security context can pull data from locked pages.
*   **Safe Inference**: Even if an LLM is compromised, it cannot "force open" a locked reservoir; the protection is enforced at the Kotlin/Runtime level.

**→ [ContextLock API](../api/context-lock.md)** - Learn how to secure your knowledge reservoirs.

## Next Steps

Now that you have a central reservoir, learn how to organize your data into distinct pages for efficient retrieval.

**→ [Page Keys and Global Context](page-keys-and-global-context.md)** - Organizing context for retrieval.
