# P2P Overview

P2P (Pipe-to-Pipe) enables TPipe agents to call each other through a registry system without exposing internal implementation details to LLMs. It supports both in-process calls and cross-process communication via HTTP or Stdio.

## Core Components

| Component | File | Purpose |
|-----------|------|---------|
| `P2PInterface` | `P2PInterface.kt` | Contract for pipelines to participate in P2P calls |
| `P2PDescriptor` | `P2PDescriptor.kt` | Agent capability description |
| `P2PRequirements` | `P2PRequirements.kt` | Runtime validation rules |
| `P2PRequest/Response` | `P2PRequest.kt`, `P2PResponse.kt` | Request/response payloads |
| `P2PRegistry` | `P2PRegistry.kt` | Agent registry and dispatcher |
| `P2PHost` | `P2PHost.kt` | Standalone P2P server for Stdio/HTTP |

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

TPipe supports multiple transport methods for P2P communication:

- **`Transport.Tpipe`**: In-process calls between agents in the same JVM.
- **`Transport.Http`**: Cross-process calls over a network. Used by the `P2PRegistry` to delegate requests to remote TPipe instances.
- **`Transport.Stdio`**: Communication via standard input/output. Useful for calling TPipe agents from other languages or command-line tools.

## Standalone P2P Hosting

TPipe can be hosted as a standalone P2P service to receive requests from external processes.

### HTTP Hosting
Run TPipe with the `--http` flag. External agents can then send `P2PRequest` JSON to the `/p2p` endpoint.

### Stdio Hosting
TPipe provides `P2PStdioHost` for handling requests over standard streams.
- **One-Shot Mode**: Processes a single JSON request from `stdin` and exits. Run with `--stdio-once`.
- **Loop Mode**: Processes multiple JSON requests in a loop until "exit" is received. Run with `--stdio-loop`.

## Security

For remote P2P calls, you can configure a global authentication mechanism in the `P2PRegistry`:

```kotlin
P2PRegistry.globalAuthMechanism = { authBody ->
    // Validate the transportAuthBody from the P2PRequest
    authBody == "secret-token-123"
}
```

This mechanism is used by both the HTTP and Stdio hosts to validate incoming requests before execution.

## When to Use P2P

- **Agent orchestration**: Multiple agents need to call each other
- **Team boundaries**: Different teams expose discoverable pipelines  
- **Remote hosting**: Distribute agents across different servers or containers.

For simple tool calls within a single agent, use PCP instead. For full pipeline-as-tool scenarios, use P2P.
