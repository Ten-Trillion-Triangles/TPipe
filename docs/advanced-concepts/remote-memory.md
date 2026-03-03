# Sharing State with Remote Memory

When you're building a single agent, keeping track of what's happened is easy—it all lives in one process. But what happens when you have a swarm of agents, or a complex pipeline distributed across multiple servers? How do they share their "thoughts," "lessons learned," and "todo lists" without constantly passing massive JSON objects back and forth?

TPipe's **Remote Memory System** is the solution. It turns your local `ContextBank` into a distributed data store, allowing independent TPipe instances to read and write to a shared state in real-time.

---

## The Mental Model: Shared Notebooks

Imagine each of your agents has a notebook (a `ContextWindow`).
- **Without Remote Memory**: Each agent has its own private notebook. If Agent A learns something, Agent B has no way of knowing unless Agent A specifically tells them.
- **With Remote Memory**: All agents are working out of a shared filing cabinet. When Agent A writes a new page, Agent B can pull that same page a millisecond later, even if they're running on a completely different machine.

---

## Setting Up Your Shared Filing Cabinet (The Server)

To start sharing memory, one TPipe instance needs to act as the "Source of Truth" (the Memory Server).

### 1. Launch from the CLI
The easiest way to start a server is using a simple flag. This is great for Docker containers or quick local testing.
```bash
java -jar tpipe.jar --remote-memory
```
*This starts a server on port 8080 by default.*

### 2. Launch from your Code
If you want to integrate the server into your existing application:
```kotlin
// Start the memory server on a specific port
ContextBank.enableRemoteHosting(port = 8080)
```

---

## Connecting Your Agents (The Clients)

Once your server is running, your agents need to know where to find it. You can configure this globally so that every memory operation automatically goes to the server.

### The "Set and Forget" Method
Use this helper to quickly point your agent at the shared server:
```kotlin
ContextBank.connectToRemoteMemory(
    url = "https://memory.agent-swarm.local",
    token = "your-secure-auth-token",
    useGlobally = true // Every emplace/get will now use the server
)
```

### The "Hybrid" Method
Sometimes you want some memory to be local (fast, private) and some to be remote (shared). You can do this by setting the **Storage Mode** on a per-key basis:

```kotlin
// This context stays on this machine only
ContextBank.emplace("my_private_thoughts", localWindow, mode = StorageMode.MEMORY_ONLY)

// This context is instantly visible to the whole swarm
ContextBank.emplace("global_knowledge", sharedWindow, mode = StorageMode.REMOTE)
```

---

## Safe Collaborations: Versioning & Conflicts

When two agents try to write to the same shared page at the exact same time, there's a risk that one will overwrite the other's work. TPipe handles this using **Versioned Memory**.

### The "Fetch-Merge-Save" Pattern
TPipe provides a built-in pattern to ensure you never lose data. Instead of blindly overwriting a page, your agent should:
1. **Fetch** the latest version from the server.
2. **Merge** their new information into it.
3. **Save** it back.

The `fetchMergeSaveRemoteContext` helper does all of this for you in one safe operation:

```kotlin
val newObservation = ContextWindow()
newObservation.contextElements.add("I noticed the user is interested in sports.")

// This safely updates the 'user_profile' page on the server,
// ensuring any changes made by other agents are preserved.
ContextBank.fetchMergeSaveRemoteContext("user_profile", newObservation)
```

---

## Security: Protecting Your Swarm's Thoughts

Sharing memory over a network requires safety. TPipe uses a **Global Authentication Mechanism** to protect your data.

1. **On the Server**: Define how to validate incoming requests.
   ```kotlin
   P2PRegistry.globalAuthMechanism = { token ->
       token == "super-secret-swarm-key"
   }
   ```

2. **On the Client**: Provide that key in your config.
   ```kotlin
   TPipeConfig.remoteMemoryAuthToken = "super-secret-swarm-key"
   ```

Now, any request without the correct token will be rejected, keeping your agent's internal state safe from unauthorized access.

---

## Summary: When to use Remote Memory?

- ✅ **Multi-Agent Systems**: When agents need to coordinate on a single task.
- ✅ **Stateless Containers**: When your agents run in ephemeral environments (like AWS Lambda or Kubernetes) and need to persist their state somewhere else.
- ✅ **Real-time Collaboration**: When one agent provides tools (PCP) that update state for another agent.
- ❌ **Single-Process Apps**: If everything is in one JVM, standard local memory is faster and simpler.

---

## See Also
- [ContextBank API Reference](../api/context-bank.md)
- [TPipeConfig API Reference](../api/tpipe-config.md)
