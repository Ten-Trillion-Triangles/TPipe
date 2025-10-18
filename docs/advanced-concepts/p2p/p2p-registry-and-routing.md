# P2P Registry and Routing

P2PRegistry manages agent registration, discovery, and request routing. It's a global singleton that handles all P2P communication.

## Agent Registration

### Basic Registration
```kotlin
val agent = MyPipeline()  // implements P2PInterface
P2PRegistry.register(agent)
```

### Manual Registration
```kotlin
P2PRegistry.register(
    agent = myAgent,
    transport = P2PTransport(Transport.Tpipe, "my-agent"),
    descriptor = myDescriptor,
    requirements = myRequirements
)
```

## Making Requests

### Simple Request
```kotlin
val request = AgentRequest(
    agentName = "data-processor",
    prompt = "Process this CSV file",
    content = csvData
)

val response = P2PRegistry.sendP2pRequest(request)
if (response.output != null) {
    println(response.output)
} else {
    println("Error: ${response.rejection?.reason}")
}
```

### Request with Template
```kotlin
val template = P2PRequest().apply {
    authBody = "my-auth-token"
    context = myContextWindow
}

val response = P2PRegistry.sendP2pRequest(
    agentRequest = request,
    template = template
)
```

## Agent Discovery

### List Available Agents
```kotlin
val agents = P2PRegistry.listGlobalAgents()
agents.forEach { descriptor ->
    println("${descriptor.agentName}: ${descriptor.agentDescription}")
}
```

### Find Specific Agent
```kotlin
val agent = P2PRegistry.listGlobalAgents()
    .find { it.agentName == "data-processor" }
```

## Remote Agent Catalog

Load external agent descriptors for cross-system calls:

```kotlin
val remoteDescriptors = loadFromExternalSource()
P2PRegistry.loadAgents(remoteDescriptors)
```

## Request Validation

The registry validates all requests before execution:

```kotlin
val (isValid, rejection) = P2PRegistry.checkAgentRequirements(
    request = myRequest,
    requirements = agentRequirements,
    agent = targetAgent
)

if (!isValid) {
    println("Validation failed: ${rejection?.reason}")
    return
}
```

## Error Handling

```kotlin
val response = P2PRegistry.sendP2pRequest(request)

when {
    response.output != null -> {
        // Handle successful response
        processResult(response.output)
    }
    response.rejection != null -> {
        when (response.rejection.errorType) {
            P2PError.auth -> handleAuthError()
            P2PError.transport -> handleTransportError()
            P2PError.content -> handleContentError()
            else -> handleGenericError()
        }
    }
}
```

## Transport Limitations

Currently only `Transport.Tpipe` (in-process) is supported. Remote transports will throw:

```kotlin
// This works
P2PTransport(Transport.Tpipe, "local-agent")

// These throw IllegalArgumentException
P2PTransport(Transport.Http, "https://remote-agent")
P2PTransport(Transport.Stdio, "/path/to/agent")
```

## Registry State

The registry maintains:
- **Local agents**: Registered P2PInterface implementations
- **Remote catalog**: External agent descriptors
- **Request templates**: Reusable request configurations

All operations are thread-safe via internal mutex protection.
