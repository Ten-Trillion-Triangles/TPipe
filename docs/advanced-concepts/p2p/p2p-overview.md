# Collaborative Agents with P2P

In a simple application, one agent does everything. But in complex systems, you need specialists. You might have one agent that's an expert at **data analysis**, another that's a **UI designer**, and a third that's a **project manager**.

**P2P (Pipe-to-Pipe)** is TPipe's agent-to-agent communication protocol. It allows agents to discover and call each other as if they were simple functions, without needing to know the internal code of the agent they are talking to.

---

## Tools vs. Collaborators

- **PCP (Tools)**: An agent using a hammer. (e.g., "Run this command").
- **P2P (Collaborators)**: An agent asking a coworker for help. (e.g., "Hey AnalysisAgent, can you summarize this CSV for me?").

---

## How it Works: The Registry

Every agent in a TPipe system registers itself with a **P2PDescriptor**. This is the agent's "business card"—it tells everyone else who they are and what they can do.

### 1. Registering your Agent
```kotlin
class AnalystAgent : Pipeline(), P2PInterface {
    init {
        setP2pDescription(P2PDescriptor(
            agentName = "data-analyst",
            agentDescription = "I analyze CSV files and provide statistical summaries.",
            transport = P2PTransport(Transport.Tpipe, "data-analyst") // Reach me locally
        ))
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse {
        // Your analysis logic here
    }
}

// Add the agent to the office
P2PRegistry.register(AnalystAgent())
```

### 2. Calling an Agent
When an LLM needs help, it produces an `AgentRequest`. TPipe's registry then figures out where that agent lives and how to talk to them.

```kotlin
val request = AgentRequest(
    agentName = "data-analyst",
    prompt = "Please summarize this sales data."
)

val response = P2PRegistry.sendP2pRequest(request)
```

---

## Cross-Process Communication (Transports)

Collaborators don't have to live in the same app. TPipe supports three main "Transports" for P2P:

| Transport | Usage |
|-----------|-------|
| **`Tpipe`** | **Local**. The agent is in the same JVM. Fast and simple. |
| **`Http`** | **Remote**. The agent is on another server. Used for distributed swarms. |
| **`Stdio`**| **Inter-Language**. The agent is a standalone process (could be written in Python or Rust). |

---

## Standalone Agent Hosting

You can turn any TPipe instance into a dedicated agent host.

### The Agent Server (HTTP)
Run your app with the `--http` flag. Any agents you've registered locally are now available to the network via the `/p2p` endpoint.

### The Agent Script (Stdio)
Perfect for calling TPipe agents from a shell script or a different language:
```bash
echo '{...JSON P2PRequest...}' | java -jar tpipe.jar --stdio-once
```

---

## Safeguarding the Team

When agents talk to each other over a network, security is critical.

- **Requirements**: Every agent can define `P2PRequirements` (e.g., "I only accept requests with less than 8,000 tokens" or "I only accept encrypted connections").
- **Global Auth**: Use `P2PRegistry.globalAuthMechanism` to set a password for your entire agent network.

---

## Summary

P2P allows you to build **modular AI systems**. Instead of one giant, fragile prompt, you build a team of focused agents that work together, share data securely, and can be scaled independently across your infrastructure.
