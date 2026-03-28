# DistributionGrid

`DistributionGrid` is TPipe's remote grid-harness container.

One `DistributionGrid` instance represents one node on a larger distributed grid. The long-term design is a router plus worker node that can exchange work with other grid nodes over the normal TPipe P2P layer.

This page describes current shipped behavior only. The evolving full runtime spec lives in the internal steering docs under `md/`.

## Current Implementation Status

`DistributionGrid` is currently implemented through Phase 6 of its rollout:

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

## What Is Still Missing

The following areas remain unimplemented:

- trace report and failure-analysis behavior
- runtime durability behavior
- memory-policy enforcement
- outbound memory shaping and outbound-memory hook invocation
- privacy, auth, and PCP mediation

## Current Shell Surface

The Phase 6 shell now includes configuration, validation, lifecycle methods, a local execution path, explicit-peer remote handoff, and registry discovery or membership behavior.

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
- `getDiscoveredRegistryIds()`
- `getDiscoveredNodeIds()`
- `getActiveRegistryLeaseIds()`
- `probeTrustedRegistries()`
- `registerWithRegistry(...)`
- `renewRegistryLease(...)`
- `tickRegistryMemberships(...)`
- `queryRegistries(...)`
- `clearDiscoveredRegistryState()`
- `getDiscoveryMode()`
- `getRoutingPolicy()`
- `getMemoryPolicy()`
- `getDurableStore()`
- `getMaxHops()`
- `isTracingEnabled()`

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

## Contributing

If you are continuing `DistributionGrid` implementation, follow the internal phased rollout rather than adding features ad hoc:

1. Phase 8: DSL, defaults, public-doc sync, and final coverage cleanup
2. keep Phase 5 through Phase 7 runtime semantics stable unless a targeted repair is required
3. route any new runtime-semantics ideas back through the steering docs before coding

## P2P Concurrency

DistributionGrid is stateful — it maintains session caches, discovery state, pause flags, and lease state during execution. When exposed via P2P, register with `P2PConcurrencyMode.ISOLATED` so each inbound request gets a fresh clone. See [P2P Registry and Routing](../advanced-concepts/p2p/p2p-registry-and-routing.md#concurrency-modes) for details.

---

**Previous:** [← Manifold](manifold.md) | **Next:** [Junction →](junction.md)
