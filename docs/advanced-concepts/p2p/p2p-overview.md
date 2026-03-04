# P2P Overview - Distributed Agent Communication

In a large-scale infrastructure, not every agent lives on the same mainline. **P2P (Pipe-to-Pipe)** is the protocol that allows TPipe agents to discover, communicate, and collaborate across different systems, services, or even different machines.

Think of P2P as the **Inter-Municipal Infrastructure**—the high-capacity lines that connect separate water systems into a unified network. It allows agents to call each other as if they were simple tools, without the caller needing to know how the destination agent is implemented.

## The Core Components

To build a P2P network, TPipe uses several key components:

| Component | Purpose |
| :--- | :--- |
| **`P2PInterface`** | The Standard Connector that any pipeline must implement to join the P2P network. |
| **`P2PDescriptor`** | The Technical Spec that describes an agent's name, capabilities, and how to reach it. |
| **`P2PRegistry`** | The Central Directory (or Switching Station) that tracks every available agent and routes requests to them. |
| **`P2PRequest / Response`** | The standardized Cargo that travels between agents. |
| **`P2PRequirements`** | The Security Gates that define runtime validation rules (like token limits or auth). |

---

## Setting Up a P2P Connection

### 1. Describing the Agent
Every P2P-ready agent needs a `P2PDescriptor`. This tells the network what the agent is called and what "fittings" it has.

```kotlin
val descriptor = P2PDescriptor(
    agentName = "data-sanitizer",
    agentDescription = "Cleans and reformats raw sensor data.",
    transport = P2PTransport(Transport.Tpipe, "sanitizer-endpoint"),
    usesConverse = true, // Supports conversation history
    maxTokens = 4096
)
```

### 2. Implementation
Any `Pipeline` can be made P2P-ready by implementing the `P2PInterface`.

```kotlin
class MyAgent : Pipeline(), P2PInterface {
    init {
        setP2pDescription(descriptor)
    }
    
    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse {
        // Handle incoming data
        val input = request.content
        val output = this.execute(input)

        return P2PResponse(output = output)
    }
}
```

### 3. Registration & Discovery
Once built, you register the agent with the `P2PRegistry`. Other agents can now "find" it by name.

```kotlin
val agent = MyAgent()
P2PRegistry.register(agent)
```

---

## Calling an Agent: Sending the Flow

Once an agent is registered, calling it is a simple, abstracted process. You don't call the pipeline directly; you ask the `P2PRegistry` to route a request.

```kotlin
val request = AgentRequest(
    agentName = "data-sanitizer",
    prompt = "Sanitize this log dump",
    content = rawData
)

// The Registry finds the sanitizer and manages the handoff
val response = P2PRegistry.sendP2pRequest(request)
```

---

## Transport: The Pipes Between Systems

P2P is designed to work across many different "pipe types" (Transports).

*   **Tpipe**: In-process communication (Fastest, same JVM).
*   **HTTP**: Cross-service communication via REST (Planned).
*   **Stdio**: Communication via command-line pipes (Planned).

---

## P2P vs. PCP: Choosing the Right Tool

> [!TIP]
> **Use PCP (Pipe Context Protocol)** when a model needs to call a **Function or Script**. It's for small, atomic tools.
>
> **Use P2P (Pipe-to-Pipe)** when a model needs to call **Another Agent or Pipeline**. It's for full Pipeline-as-a-Tool scenarios.

---

## Next Steps

Learn how to define exactly what an agent can do and how it can be reached.

**→ [P2P Descriptors and Transport](p2p-descriptors-and-transport.md)** - Agent discovery and addressing.
