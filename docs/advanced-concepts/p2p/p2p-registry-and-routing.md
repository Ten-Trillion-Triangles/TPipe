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

### Hosted Public Registry Catalog

For searchable remote catalog hosting, use `P2PHostedRegistry` instead of stretching `loadAgents(...)` into a
remote directory system.

Hosted registries support:

- public `AGENT`, `GRID_NODE`, and `GRID_REGISTRY` listings
- optional hosted-registry scoped auth using the normal TPipe auth-hook style
- lease-based publication and renewal
- structured filtering by kind, category, tag, transport, auth requirement, content type, capability, and trust-domain metadata
- exact-title and title-prefix filtering
- text search across titles, summaries, descriptions, categories, tags, and capability labels
- facet reads for kind/category/tag/transport/auth/trust-domain/moderation buckets
- remote registry status reads plus filtered audit inspection
- import of sanitized `AGENT` listings into the local static registry through `P2PHostedRegistryClient.pullListingsToLocalRegistry(...)`
- import into plain `P2PRegistry` trusted sources with collision-safe source tracking

Example:

```kotlin
val hostedResults = P2PHostedRegistryClient.searchListings(
    transport = P2PTransport(Transport.Tpipe, "public-agent-catalog"),
    query = P2PHostedRegistryQuery(
        textQuery = "research",
        categories = mutableListOf("research/agent"),
        listingKinds = mutableListOf(P2PHostedListingKind.AGENT)
    )
)
```

Hosted registries are still just P2P services under the hood, so they can be exposed in-process, over HTTP
`/p2p`, or over stdio hosts without a second API stack.

When a hosted registry enables read or write auth, clients keep using the normal TPipe request auth fields:

- `authBody` for P2P-style request auth
- `transportAuthBody` / HTTP `Authorization` for transport-level auth

Hosted-registry auth remains pluggable. A host may validate credentials with a hosted-registry-specific auth hook
or fall back to `P2PRegistry.globalAuthMechanism`. The optional principal resolver is only for owner/operator/audit
identity after auth succeeds; it does not replace TPipe's normal auth validation hooks.

If you implement a custom `P2PHostedRegistryPolicy` instead of using `DefaultP2PHostedRegistryPolicy`, the early auth gate falls back to `globalAuthMechanism` only. Set `globalAuthMechanism` or handle credential validation inside your policy's `canRead`/`canPublish`/`canMutate` methods.

### Trusted Hosted Sources for Plain P2P Clients

If you are not using `DistributionGrid` but still want structured remote discovery, `P2PRegistry` now supports
trusted hosted-registry sources:

```kotlin
P2PRegistry.addTrustedRegistrySource(
    P2PTrustedRegistrySource(
        sourceId = "public-agent-catalog",
        transport = P2PTransport(Transport.Http, "https://catalog.example.com"),
        autoPullOnRegister = true
    )
)
```

This path:

- imports only `AGENT` listings into the normal client catalog
- tracks which source owns each imported entry
- can enforce a lightweight trusted-import policy for freshness/verification
- exposes provenance for imported entries
- supports per-source admission filters
- rejects duplicate collisions instead of overwriting existing entries
- supports explicit pull plus optional auto-refresh
- exposes per-source pull status, failure reason, and imported-agent counts

If you want a thinner setup path, `TPipe-Defaults` now also provides helpers for:

- building public or private `P2PHostedRegistry` hosts
- building trusted `P2PRegistry` hosted-source configs

Those helpers return the same runtime types directly and do not create a second hosted-registry runtime path.

### Dedicated HTTP Route

Hosted registries now have a focused HTTP adapter endpoint:

- `POST /p2p/registry`

`P2PHostedRegistryClient` uses this route automatically for `Transport.Http` calls, while the generic `/p2p`
route remains available for the broader P2P runtime.

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

## Transport Support

P2PRegistry supports these transports:

- `Transport.Tpipe`
- `Transport.Http`
- `Transport.Stdio`

```kotlin
P2PTransport(Transport.Tpipe, "local-agent")
P2PTransport(Transport.Http, "https://remote-agent")
P2PTransport(Transport.Stdio, "/path/to/agent")
```

`Transport.Python`, `Transport.Kotlin`, `Transport.JavaScript`, `Transport.Unknown`, and `Transport.Auto`
are not used for P2P.

## Registry State

The registry maintains:
- **Local agents**: Registered P2PInterface implementations
- **Remote catalog**: External agent descriptors
- **Request templates**: Reusable request configurations
- **Hosted catalog imports**: Sanitized public `AGENT` listings loaded from hosted registries when explicitly pulled

All operations are thread-safe via internal mutex protection.

## Concurrency Modes

By default, the registry routes all inbound requests for a transport address to the same object instance (`SHARED` mode). For stateful containers like Manifold, Junction, and DistributionGrid, concurrent inbound requests can race on mutable runtime state.

`ISOLATED` mode solves this by creating a fresh clone of the registered agent for each inbound request. The clone gets its own runtime state while sharing configuration, hooks, and external resources with the template.

### Registering with ISOLATED Mode

```kotlin
P2PRegistry.register(
    agent = myManifold,
    transport = myTransport,
    descriptor = myDescriptor,
    requirements = myRequirements,
    concurrencyMode = P2PConcurrencyMode.ISOLATED
)
```

### Registering with a Factory Function

For cases where automatic cloning cannot capture the full setup (external resources, custom initialization), supply a factory function:

```kotlin
P2PRegistry.register(
    factory = {
        val m = Manifold()
        m.setManagerPipeline(buildManager())
        m.addWorkerPipeline(buildWorker())
        m.init()
        m
    },
    transport = myTransport,
    descriptor = myDescriptor,
    requirements = myRequirements
)
```

Factory mode implies ISOLATED — every request calls the factory, executes against the fresh instance, and discards it.

### When to Use Each Mode

| Mode | Use When |
|------|----------|
| `SHARED` (default) | Stateless agents, simple pipes, or when you want shared conversation history across requests |
| `ISOLATED` | Stateful containers (Manifold, Junction, DistributionGrid) exposed to concurrent P2P traffic |
| Factory | Complex setups with external resources that automatic cloning cannot capture |

### Child Agent Cleanup

When an ISOLATED container's execution registers child agents (e.g., Manifold registers its worker pipelines), those child registrations are automatically cleaned up after the request completes.
