# P2P API Reference

The P2P package allows you to build a **collaborative office** of agents. It handles the discovery, addressing, and security of agent-to-agent requests.

---

## Agent Registration (`P2PRegistry`)

The `P2PRegistry` is the "Front Desk" of your office.

### `register(agent: P2PInterface)`
Adds an agent to the registry so it can be called by others.
- **`agent`**: Any class that implements `P2PInterface` (usually a `Pipeline`).

### `globalAuthMechanism`
A lambda that acts as the "Office Security Guard."
```kotlin
P2PRegistry.globalAuthMechanism = { token ->
    // Return true if the token is allowed to enter the network
    token == "secret-swarm-password"
}
```

---

## Request & Response Payloads

### `P2PRequest`
What an agent sends when it needs help.
- `prompt`: The multimodal message (text, images, etc.).
- `context`: A `ContextWindow` containing the data the collaborator needs to see.
- `authBody`: The credentials used to pass through `globalAuthMechanism`.

### `P2PResponse`
The result of a collaboration.
- `output`: The `MultimodalContent` produced by the collaborator.
- `rejection`: Contains an `errorType` (e.g., `auth`, `content`) if the request was denied.

---

## Connection Details (`P2PTransport`)

Defines where an agent is located.

- **`transportMethod`**:
  - `Transport.Tpipe`: The collaborator is in the same building (JVM).
  - `Transport.Http`: The collaborator is in a different building (Server).
  - `Transport.Stdio`: The collaborator is a standalone script (Process).
- **`transportAddress`**: The name, URL, or filepath of the collaborator.

---

## Standalone Hosting (`P2PHost`)

Objects that turn your TPipe app into a dedicated server for collaborators.

### `P2PStdioHost`
- `runOnce()`: Processes one JSON request from `stdin` and exits.
- `runLoop()`: Stays alive, processing requests until "exit".

---

## See Also
- [Conceptual Guide: Collaborative Agents with P2P](../advanced-concepts/p2p/p2p-overview.md)
