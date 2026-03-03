# Shared State with Remote Memory

The **Remote Memory System** provides the technical infrastructure for distributed TPipe instances to share state, lorebooks, and task lists in real-time. It follows a client-server architecture powered by the Ktor framework.

---

## Technical Architecture

- **Memory Server**: A TPipe instance running with `--remote-memory`. It exposes a REST API for bank, todo, and lock operations.
- **Memory Client**: Any TPipe instance where `TPipeConfig.remoteMemoryEnabled` is `true`. It delegates its internal `ContextBank` calls to the server via the `MemoryClient` object.

---

## Conflict Resolution: Versioning

In a distributed swarm, multiple agents may attempt to write to the same `ContextWindow` simultaneously. TPipe prevents data loss using a **Versioned Write** system.

### How it Works
1. Every `ContextWindow` and `TodoList` contains a `version: Long` field.
2. When the server receives a `POST` request, it checks the incoming version.
3. If `TPipeConfig.enforceMemoryVersioning` is enabled and the incoming version is lower than the server's version, the write is rejected with a `P2PError.transport` error.
4. On a successful write, the server increments the version.

### The Standard Pattern: Fetch-Merge-Save
To ensure safety, developers should use the built-in helper which implements this atomic cycle:

```kotlin
// Safely update the 'team_knowledge' page
val success = ContextBank.fetchMergeSaveRemoteContext("team_knowledge", localWindow)
```

---

## Configuration Reference

| Setting | Type | Purpose |
|---------|------|---------|
| `remoteMemoryUrl` | String | The base endpoint of the server (e.g., `https://memory.swarm.internal`). |
| `remoteMemoryAuthToken`| String | Sent in the `Authorization` header. Validated by `P2PRegistry.globalAuthMechanism`. |
| `useRemoteMemoryGlobally`| Boolean| If enabled, the agent becomes effectively stateless, delegating ALL local memory calls to the network. |

---

## API Integration

### Server-Side Auth
```kotlin
P2PRegistry.globalAuthMechanism = { authToken ->
    // Logic to validate the secret swarm key
    authToken == "secret-token-xyz"
}
```

### Client-Side Connection
```kotlin
ContextBank.connectToRemoteMemory(
    url = "https://memory.tpipe.cloud",
    token = "secret-token-xyz",
    useGlobally = true
)
```

---

## See Also
- [Core Concept: Managing Global Context](../core-concepts/context-bank-integration.md)
- [API Reference: ContextBank API Reference](../../api/context-bank.md)
