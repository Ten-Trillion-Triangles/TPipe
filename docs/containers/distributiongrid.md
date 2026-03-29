# DistributionGrid

`DistributionGrid` is TPipe's remote grid-harness container.

One `DistributionGrid` instance represents one node on a larger distributed grid. The long-term design is a router plus worker node that can exchange work with other grid nodes over the normal TPipe P2P layer.

This page describes current shipped behavior only. The evolving full runtime spec lives in the internal steering docs under `md/`.

## Current Implementation Status

`DistributionGrid` is now implemented through Phase 8 of its rollout:

- the contract-model layer exists for runtime, memory, durability, and protocol vocabulary
- the `DistributionGrid` class now provides the validated node shell used by the local and explicit-peer remote runtime
- the shell stores grid-level `P2PInterface` identity state
- the shell supports router and worker binding
- the shell supports local peer registration and external peer-descriptor registration
- the shell synthesizes safe local defaults for descriptor, transport, and requirements when those values are omitted
- the shell supports duplicate-peer rejection plus local peer removal and replacement helpers
- the shell now validates required bindings, local ownership, duplicate registration state, ancestry cycles, and nested depth through `init()`
- the shell now exposes child pipelines through `getPipelinesFromInterface()`
- the shell now supports pause/resume flags, runtime-state clearing, and trace clearing
- the shell now executes local work through `execute(...)`, `executeLocal(...)`, and `executeP2PRequest(...)`
- the shell now supports a first local router-to-worker flow
- the shell now records local hop, outcome, and failure metadata on terminal content
- the shell now exposes grid-level DITL hook registration for the local execution flow
- typed `distributionGridMetadata` now exists on `P2PDescriptor`
- `DISTRIBUTION_GRID_*` trace vocabulary now exists for validation, lifecycle, and local execution events
- the shell now supports explicit remote peer handoff through configured external peer descriptors
- the shell now performs explicitly framed serialized grid RPC over the normal P2P boundary for handshake and task-exchange traffic
- the shell now supports mandatory explicit-peer handshake, authoritative negotiated policy, and in-memory session reuse
- cached explicit-peer sessions are now reused only when they still satisfy the current task policy, and widened handshake acknowledgements are rejected
- inbound remote envelopes now record the caller's return address as the sender transport, and peer-authored handshake rejection details are preserved
- the shell now supports inbound explicit remote task handoff and locally finalizes remote returns or failures
- the shell now supports bootstrap registry configuration, trusted registry probing, explicit lease registration and renewal, and structured registry queries
- discovered node advertisements are now verified and cached before they are used for remote handoff
- registry-capable nodes can now serve mixed-role or dedicated registry RPC behavior for probe, register, renew, and query flows
- the shell now supports outbound memory shaping, privacy/auth/PCP mediation, durable checkpoints, `resumeTask(taskId)`, retry/alternate-peer runtime behavior, and trace export/failure-analysis helpers
- the Kotlin DSL now supports full-node assembly through `distributionGrid { ... }`, including router/worker binding, discovery, policies, tracing, hooks, and operational tuning
- `TPipe-Defaults` now provides an additive `defaults { bedrock(...) }` / `defaults { ollama(...) }` bridge for the grid DSL plus matching raw defaults factories
- the shell now supports hosted-registry bootstrap catalog sources plus explicit public node/registry listing publication helpers
- public docs and tests now reflect the shipped runtime rather than the earlier Phase 5/6 rollout point

## What Is Still Missing

The following work is intentionally deferred:

- any new runtime semantics beyond the shipped Phase 7 behavior
- future convenience or provider integrations that would extend the DSL without changing the core runtime

## Current Shell Surface

The Phase 8 shell now includes configuration, validation, lifecycle methods, local execution, explicit-peer remote handoff, registry discovery or membership, hardening behavior, and a Kotlin DSL.

### Grid-level P2P identity

- `setP2pDescription(...)`
- `getP2pDescription()`
- `setP2pTransport(...)`
- `getP2pTransport()`
- `setP2pRequirements(...)`
- `getP2pRequirements()`
- `setContainerObject(...)`
- `getContainerObject()`

### Node bindings and peer registration

- `setRouter(...)`
- `setWorker(...)`
- `addPeer(...)`
- `addPeerDescriptor(...)`
- `removePeer(...)`
- `replacePeer(...)`

### Stored shell configuration

- `setDiscoveryMode(...)`
- `setRoutingPolicy(...)`
- `setMemoryPolicy(...)`
- `setDurableStore(...)`
- `setRegistryMetadata(...)`
- `getRegistryMetadata()`
- `setTrustVerifier(...)`
- `getTrustVerifier()`
- `setMaxHops(...)`
- `setRpcTimeout(...)`
- `setMaxSessionDuration(...)`
- `enableTracing(...)`
- `disableTracing()`

### Validation and lifecycle

- `init()`
- `pause()`
- `resume()`
- `isPaused()`
- `canPause()`
- `clearRuntimeState()`
- `clearTrace()`
- `resumeTask(...)`

### Local execution and hooks

- `execute(...)`
- `executeLocal(...)`
- `executeP2PRequest(...)`
- `setBeforeRouteHook(...)`
- `setBeforeLocalWorkerHook(...)`
- `setAfterLocalWorkerHook(...)`
- `setBeforePeerDispatchHook(...)`
- `setAfterPeerResponseHook(...)`
- `setOutboundMemoryHook(...)`
- `setFailureHook(...)`
- `setOutcomeTransformationHook(...)`

### Readback helpers

- `getRouterBindingKey()`
- `getWorkerBindingKey()`
- `getLocalPeerKeys()`
- `getExternalPeerKeys()`
- `addBootstrapRegistry(...)`
- `removeBootstrapRegistry(...)`
- `getBootstrapRegistryIds()`
- `addBootstrapCatalogSource(...)`
- `removeBootstrapCatalogSource(...)`
- `getBootstrapCatalogSourceIds()`
- `getDiscoveredRegistryIds()`
- `getDiscoveredNodeIds()`
- `getActiveRegistryLeaseIds()`
- `probeTrustedRegistries()`
- `pullTrustedBootstrapCatalogs(...)`
- `getBootstrapCatalogSourceStatuses()`
- `registerWithRegistry(...)`
- `renewRegistryLease(...)`
- `tickRegistryMemberships(...)`
- `queryRegistries(...)`
- `publishPublicNodeListing(...)`
- `renewPublicNodeListing(...)`
- `removePublicNodeListing(...)`
- `publishPublicRegistryListing(...)`
- `renewPublicRegistryListing(...)`
- `removePublicRegistryListing(...)`
- `getPublicListingAutoRenewStatuses()`
- `clearDiscoveredRegistryState()`
- `getDiscoveryMode()`
- `getRoutingPolicy()`
- `getMemoryPolicy()`
- `getDurableStore()`
- `getMaxHops()`
- `getRpcTimeout()`
- `getMaxSessionDuration()`
- `isTracingEnabled()`
- `getTraceReport(...)`
- `getFailureAnalysis()`

## DSL Builder

If you want the grid assembled, validated, and initialized in one place, prefer the Kotlin DSL:

```kotlin
import com.TTT.Pipeline.distributionGrid

val grid = distributionGrid {
    p2p {
        agentName("research-grid-node")
        transportAddress("research-grid-node")
        transportMethod(Transport.Tpipe)
    }

    router(routerPipeline)
    worker(workerPipeline)

    routing {
        allowRetrySamePeer(true)
        maxRetryCount(1)
        maxHopCount(8)
    }

    memory {
        outboundTokenBudget(4096)
        summaryBudget(512)
    }

    tracing {
        enabled()
    }
}
```

The DSL returns an initialized grid by default. Use `DistributionGridDsl.buildSuspend()` when you need coroutine-safe startup.

### Hosted Bootstrap Catalogs

If you want the grid to discover trusted registries from a remotely hosted public catalog on startup, add a
bootstrap catalog source inside `discovery { }`:

```kotlin
distributionGrid {
    router(routerPipeline)
    worker(workerPipeline)

    discovery {
        bootstrapCatalogSource(
            DistributionGridBootstrapCatalogSource(
                sourceId = "public-grid-registry-catalog",
                transport = P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "public-grid-registry-catalog"
                ),
                query = P2PHostedRegistryQuery(
                    listingKinds = mutableListOf(P2PHostedListingKind.GRID_REGISTRY),
                    categories = mutableListOf("grid/registry")
                ),
                autoPullOnInit = true
            )
        )
    }
}
```

Hosted bootstrap catalogs are still discovery aids only. Pulled `GRID_REGISTRY` advertisements still have to pass
the configured `DistributionGridTrustVerifier`, and later remote routing still uses the normal handshake and
session rules.

`DistributionGrid` now also exposes hosted-registry observability for this path, including:

- bootstrap source pull attempt and success timestamps
- accepted versus trust-rejected hosted registry counts
- active public-listing auto-renew loop status with last success/failure details

For non-grid clients that only need plain remote agent discovery/import, use `P2PRegistry` trusted hosted sources
instead. That lighter path imports only `AGENT` listings and does not replace grid trust verification.

### Public Listing Helpers

`DistributionGrid` can also publish its own outward node or registry state into a hosted public catalog:

```kotlin
val result = grid.publishPublicNodeListing(
    transport = P2PTransport(Transport.Tpipe, "public-grid-catalog"),
    options = DistributionGridPublicListingOptions(
        title = "Public Research Grid Node",
        summary = "Remote worker entrypoint for research workloads.",
        categories = mutableListOf("grid/node"),
        tags = mutableListOf("research", "worker")
    ),
    authBody = "publisher-token"
)
```

These helpers sanitize public listing data and keep hosted-catalog visibility separate from actual runtime trust,
handshake, and session enforcement.

The hosted-listing helper surface also supports:

- `updatePublicNodeListing(...)`
- `updatePublicRegistryListing(...)`
- `startPublicNodeListingAutoRenew(...)`
- `startPublicRegistryListingAutoRenew(...)`
- `stopPublicListingAutoRenew(...)`

The renewal helpers are opt-in. `DistributionGrid` does not auto-publish or auto-renew public listings unless the
caller explicitly starts those loops.

### DSL blocks

The `distributionGrid { }` builder supports these top-level blocks:

| Block | Required | Description |
|-------|----------|-------------|
| `p2p { }` | No | Configures the outward grid-node descriptor, transport, requirements, and container object |
| `security { }` | No | Configures outward auth/privacy-related descriptor and requirement hints |
| `router { }` or `router(...)` | Yes | Binds the local router role |
| `worker { }` or `worker(...)` | Yes | Binds the local worker role |
| `peer(...)` | No | Attaches a local peer binding |
| `peerDescriptor(...)` | No | Registers an external peer descriptor |
| `discovery { }` | No | Configures discovery mode, bootstrap registries, registry metadata, and trust verifier |
| `routing { }` | No | Stores routing policy settings |
| `memory { }` | No | Stores memory-policy settings |
| `durability { }` | No | Binds a durable store |
| `tracing { }` | No | Enables or disables tracing |
| `hooks { }` | No | Configures orchestration hooks |
| `operations { }` | No | Configures max hops, RPC timeout, and session-duration caps |

### Defaults Extension

When `TPipe-Defaults` is on the classpath, the grid DSL also supports a provider-backed defaults bridge:

```kotlin
import Defaults.BedrockGridConfiguration
import Defaults.defaults
import com.TTT.Pipeline.distributionGrid

val grid = distributionGrid {
    defaults {
        bedrock(
            BedrockGridConfiguration(
                region = "us-east-1",
                model = "anthropic.claude-3-haiku-20240307-v1:0"
            )
        )
    }
}
```

That extension seeds a provider-backed router and worker for the node and may also seed optional node-level policy blocks when the defaults configuration explicitly provides them. The extension lives in `TPipe-Defaults`; core `DistributionGrid` remains provider-agnostic.

For hosted-registry adoption, `TPipe-Defaults` now also provides thin helpers that build:

- `DistributionGridBootstrapCatalogSource`
- `DistributionGridPublicListingOptions`

Those helpers stay additive and only scaffold the existing hosted-registry discovery/publication types. They do not
auto-publish listings or start renew loops.

### Build modes

- `distributionGrid { ... }` uses `build()` and returns an initialized grid
- `DistributionGridDsl.build()` initializes synchronously with `runBlocking`
- `DistributionGridDsl.buildSuspend()` initializes asynchronously without blocking the caller

## Runtime Contract Files

The current contract layer is split into four source files:

- `src/main/kotlin/Pipeline/DistributionGridModels.kt`
- `src/main/kotlin/Pipeline/DistributionGridMemoryModels.kt`
- `src/main/kotlin/Pipeline/DistributionGridDurabilityModels.kt`
- `src/main/kotlin/Pipeline/DistributionGridProtocolModels.kt`

These files define the vocabulary for the runtime, and the grid now uses them for both local execution and explicit-peer remote handoff.

## Intended Architecture

The approved design target is:

- one `DistributionGrid` object equals one node
- every node has a router role and a local worker role
- the router decides where work goes next
- task exchange happens over normal TPipe P2P transports
- grid-specific policy, memory, tracing, durability, and handshake behavior are layered on top of that P2P substrate

For the full future-facing spec, use:

- `md/distributiongrid-design.md`
- `md/distributiongrid-progress.md`
- `md/distributiongrid-plan.md`

## PCP Forwarding Policy (Phase 7)

Non-stdio transports no longer silently strip PCP payloads before remote handoff. Instead, PCP forwarding is gated by the `distributionGridAllowRemotePcpForwarding` attribute on the envelope or content metadata. Requests that carry real PCP tooling (beyond stdio session options) to a non-stdio peer will receive a `POLICY_REJECTED` failure unless the attribute is explicitly set to `"true"`. Set the attribute on the envelope before dispatch to opt in:

```kotlin
envelope.attributes["distributionGridAllowRemotePcpForwarding"] = "true"
```

## Verification

The current shipped slice has focused coverage through:

- `DistributionGridContractModelsTest`
- `DistributionGridShellRegistrationTest`
- `DistributionGridValidationLifecycleTest`
- `DistributionGridExecutionCoreTest`
- `DistributionGridRemoteHandoffTest`
- `DistributionGridRegistryDiscoveryTest`
- `DistributionGridHardeningTest`
- `DistributionGridDslTest`

## Contributing

If you are continuing `DistributionGrid` implementation, follow the internal phased rollout rather than adding features ad hoc:

1. Phase 8 is now the shipped ergonomics/docs layer; keep Phase 5 through Phase 7 runtime semantics stable unless a targeted repair is required
2. future work should treat additional convenience layers as additive extensions rather than runtime redesign
3. route any new runtime-semantics ideas back through the steering docs before coding

## P2P Concurrency

DistributionGrid is stateful — it maintains session caches, discovery state, pause flags, and lease state during execution. When exposed via P2P, register with `P2PConcurrencyMode.ISOLATED` so each inbound request gets a fresh clone. See [P2P Registry and Routing](../advanced-concepts/p2p/p2p-registry-and-routing.md#concurrency-modes) for details.

---

**Previous:** [← Manifold](manifold.md) | **Next:** [Junction →](junction.md)
