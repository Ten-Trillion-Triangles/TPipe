# P2P Overview

P2P (Pipe-to-Pipe) enables TPipe agents to call each other through a registry system without exposing internal implementation details to LLMs.

## Core Components

| Component | File | Purpose |
|-----------|------|---------|
| `P2PInterface` | `P2PInterface.kt` | Contract for pipelines to participate in P2P calls |
| `P2PDescriptor` | `P2PDescriptor.kt` | Agent capability description |
| `P2PRequirements` | `P2PRequirements.kt` | Runtime validation rules |
| `P2PRequest/Response` | `P2PRequest.kt`, `P2PResponse.kt` | Request/response payloads |
| `P2PRegistry` | `P2PRegistry.kt` | Agent registry and dispatcher |

## Basic Usage

### 1. Implement P2PInterface

```kotlin
class MyPipeline : Pipeline(), P2PInterface {
    init {
        setP2pDescription(P2PDescriptor(
            agentName = "my-agent",
            agentDescription = "Does X, Y, Z",
            transport = P2PTransport(Transport.Tpipe, "my-agent"),
            requiresAuth = false,
            usesConverse = true,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none
        ))
        
        setP2pRequirements(P2PRequirements(
            maxTokens = 8192,
            allowExternalConnections = true
        ))
    }
    
    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse {
        // Handle the request
        val result = MultimodalContent().apply {
            addText("Processing completed successfully")
        }
        return P2PResponse(output = result)
    }
}
```

### 2. Register Agent

```kotlin
val agent = MyPipeline()
P2PRegistry.register(agent)
```

### 3. Call Agent

```kotlin
val request = AgentRequest(
    agentName = "my-agent",
    prompt = "Process this data",
    content = inputData
)

val response = P2PRegistry.sendP2pRequest(request)
```

## Transport Support

Currently only `Transport.Tpipe` (in-process) is implemented. HTTP, STDIO, and Python transports are planned but not yet available.

## When to Use P2P

- **Agent orchestration**: Multiple agents need to call each other
- **Team boundaries**: Different teams expose discoverable pipelines  
- **Future remote hosting**: Want stable contracts before implementing remote calls

For simple function calls, use PCP instead. For full pipeline-as-tool scenarios, use P2P.



## Cross-Node Orchestration

TPipe goes beyond local process execution. The P2P protocol enables Pipes running on completely different machines, written in completely different languages, to query each other and share context.

> 💡 **Tip:** A P2P network is an inter-municipal aqueduct. If your local Reservoir doesn't have the context it needs to answer a prompt, it can query an adjacent city's (Agent's) Reservoir seamlessly over HTTP or STDIO, combining its context window before reasoning.


## Next Steps

**→ [P2P Descriptors and Transport](p2p-descriptors-and-transport.md)** - Understand how agents discover and address each other on the network.