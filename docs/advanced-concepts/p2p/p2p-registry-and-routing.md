# P2P Registry and Routing - The Central Switching Station

The `P2PRegistry` is the Central Switching Station of the TPipe network. It is a global singleton responsible for agent registration, discovery, and request routing. Every inter-agent communication flows through the registry, which performs critical Pressure Tests (validations) before allowing data to move between specialized valves.

## Table of Contents
- [Agent Registration](#agent-registration)
- [Sending Requests: Opening the Flow](#sending-requests-opening-the-flow)
- [Discovery: Finding the Right Expert](#discovery-finding-the-right-expert)
- [Remote Agent Catalog](#remote-agent-catalog)
- [Operational Safety and Rejection](#operational-safety-and-rejection)

---

## Agent Registration: Plumbing into the Network

To join the P2P network, a component must register itself. While TPipe can automatically infer most settings from an agent's `P2PInterface`, you can also provide explicit configuration for high-security environments.

### Basic Registration
The standard way to add an agent to the local switching station.

```kotlin
val auditor = AuditPipeline() // Implements P2PInterface
P2PRegistry.register(auditor)
```

### Manual Registration
Allows you to override the transport address or security requirements during registration.

```kotlin
P2PRegistry.register(
    agent = myAgent,
    transport = P2PTransport(Transport.Tpipe, "high-security-vault"),
    requirements = P2PRequirements(authMechanism = ::myAuthCheck)
)
```

---

## Sending Requests: Opening the Flow

You don't call an agent's pipeline directly. Instead, you send a request through the registry, which handles the handoff, isolation (duplication), and security checks.

```kotlin
val simpleRequest = AgentRequest(
    agentName = "data-processor",
    prompt = "Sanitize this JSON feed",
    content = rawFeed
)

// The Registry finds the processor and manages the entire flow
val response = P2PRegistry.sendP2pRequest(simpleRequest)

if (response.output != null) {
    println("Flow Result: ${response.output.text}")
}
```

---

## Discovery: Finding the Right Expert

In complex swarms (like a **Manifold**), a Manager agent may not know the exact name of every worker. It can use the registry to discover available specialists.

```kotlin
// List all agents visible to the global network
val experts = P2PRegistry.listGlobalAgents()

experts.forEach { spec ->
    println("Agent Found: ${spec.agentName} - ${spec.agentDescription}")
}
```

---

## Remote Agent Catalog

TPipe supports cross-system communication by allowing you to load "Remote Blueprints" into your local registry. This tells your system that an agent exists on another server, even if the local JVM doesn't have the code for it.

```kotlin
val remoteSpecs = fetchRemoteAgentManifest()
P2PRegistry.loadAgents(remoteSpecs)
```

---

## Operational Safety and Rejection

Before any data enters an agent's mainline, the `P2PRegistry` performs a comprehensive validation check against the agent's `P2PRequirements`.

If the request is unsafe, the registry returns a **Rejection Packet** instead of executing the model:

```kotlin
val response = P2PRegistry.sendP2pRequest(request)

if (response.rejection != null) {
    when (response.rejection.errorType) {
        P2PError.auth -> println("Valve Closed: Authentication failed.")
        P2PError.transport -> println("Valve Missing: Target agent unreachable.")
        P2PError.json -> println("Blueprint Mismatch: Schema not allowed.")
    }
}
```

---

## Key Operational Behaviors

### 1. Thread-Safe Switching
The registry uses high-performance mutexes to manage its agent list. This ensures that even in massive, concurrent agent swarms, registration and routing remain deterministic and free of race conditions.

### 2. Transport Abstraction
Currently, TPipe optimized for `Transport.Tpipe` (high-speed in-process communication). The registry API is designed to be transport-agnostic, meaning your routing logic won't have to change when remote HTTP and Stdio transports are enabled.

### 3. Isolation by Design
The registry is the only component that can trigger **Agent Duplication**. It ensures that if an agent is marked for isolation, a fresh Valve Clone is created for every single request, preventing data leakage between callers.

## Next Steps

Now that you can route data between agents, learn how to build complex requests and templates.

**→ [P2P Requests and Templates](p2p-requests-and-templates.md)** - Building inter-agent cargo units.
