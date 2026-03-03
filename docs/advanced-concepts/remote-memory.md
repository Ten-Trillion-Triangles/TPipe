# Remote Memory System

The Remote Memory system allows multiple TPipe instances to share context windows, todo lists, and locks in real-time. This enables distributed agent architectures where separate processes can coordinate through a centralized memory state.

## Overview

Remote Memory works via a client-server architecture:
- **Memory Server**: A TPipe instance acting as a host, exposing REST endpoints for memory operations.
- **Memory Client**: TPipe instances configured to delegate their memory operations to a remote server.

## Hosting a Memory Server

You can start a TPipe instance as a memory server using either the command line or the API.

### Via Command Line
Start the application with the `--remote-memory` or `--http` flag:
```bash
java -jar tpipe.jar --remote-memory
```
By default, this starts a Netty server on port 8080.

### Via API
Call `enableRemoteHosting` on the `ContextBank`:
```kotlin
// Starts a memory server on port 8080
ContextBank.enableRemoteHosting(port = 8080)
```

## Connecting to a Remote Server

To use a remote memory server, you must configure your TPipe instance to point to the host.

### Configuration Settings
Update `TPipeConfig` to match your server's details:

| Property | Default | Description |
|----------|---------|-------------|
| `remoteMemoryEnabled` | `false` | Must be `true` to enable remote delegation. |
| `remoteMemoryUrl` | `http://localhost:8080` | The base URL of the memory server. |
| `remoteMemoryAuthToken` | `""` | Optional token for authentication. |
| `useRemoteMemoryGlobally` | `false` | If true, ALL memory operations delegate to remote. |
| `enforceMemoryVersioning` | `false` | Enables version-based conflict resolution. |

### Connection Helper
Use the `connectToRemoteMemory` helper for quick setup:
```kotlin
ContextBank.connectToRemoteMemory(
    url = "https://memory.example.com",
    token = "your-secret-token",
    useGlobally = true
)
```

## Using Remote Storage

Remote storage can be applied globally or on a per-key basis.

### Global Delegation
When `TPipeConfig.useRemoteMemoryGlobally` is set to `true`, every call to `ContextBank` or `ContextLock` will automatically use the `MemoryClient` to talk to the remote server.

### Per-Key Delegation
You can specify `StorageMode.REMOTE` when emplacing data to force it onto the remote server even if global delegation is disabled:

```kotlin
val sharedWindow = ContextWindow()
sharedWindow.addLoreBookEntry("shared_fact", "This is visible to all agents.")

// This specific key will be stored remotely
ContextBank.emplace("global_state", sharedWindow, mode = StorageMode.REMOTE)
```

## Memory Versioning and Conflict Resolution

When multiple agents modify the same remote context window simultaneously, race conditions can occur. TPipe uses a versioning system to prevent accidental overwrites.

### How it Works
1. Each `ContextWindow` and `TodoList` has a `version` field (Long).
2. When `enforceMemoryVersioning` is enabled, the server rejects POST requests where the incoming version is lower than the server's version.
3. On successful writes, the server increments the version.

### The Fetch-Merge-Save Pattern
To resolve versioning conflicts, use the `fetchMergeSaveRemoteContext` helper. It pulls the latest state, merges it with your local changes, and attempts the save again.

```kotlin
val myUpdates = ContextWindow()
myUpdates.contextElements.add("New observation")

// Safely update the remote key "agent_log"
val success = ContextBank.fetchMergeSaveRemoteContext("agent_log", myUpdates)
```

## Security and Authentication

### Transport Authentication
The memory server uses the same `globalAuthMechanism` as the P2P system. You can define a custom lambda to validate the `Authorization` header:

```kotlin
P2PRegistry.globalAuthMechanism = { authToken ->
    // Validate the token against your database or environment
    authToken == System.getenv("TPIPE_SECRET")
}
```

### Client Credentials
Clients must provide the matching token in `TPipeConfig.remoteMemoryAuthToken`. This token is sent in the `Authorization` header of every REST request.

## Supported Memory Features

The following systems are fully supported over remote memory:
- **ContextBank**: Store and retrieve named `ContextWindow` objects.
- **TodoList**: Share task lists and track completions across agents.
- **ContextLock**: Distributed locking to prevent multiple agents from accessing specific context pages or keys simultaneously.
