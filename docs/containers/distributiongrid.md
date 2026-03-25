# DistributionGrid

`DistributionGrid` is TPipe's remote grid-harness container.

One `DistributionGrid` instance represents one node on a larger distributed grid. The long-term design is a router plus worker node that can exchange work with other grid nodes over the normal TPipe P2P layer.

This page describes current shipped behavior only. The evolving full runtime spec lives in the internal steering docs under `md/`.

## Current Implementation Status

`DistributionGrid` is currently implemented through Phase 3 of its rollout:

- the contract-model layer exists for runtime, memory, durability, and protocol vocabulary
- the `DistributionGrid` class now provides a non-executing configuration shell
- the shell stores grid-level `P2PInterface` identity state
- the shell supports router and worker binding
- the shell supports local peer registration and external peer-descriptor registration
- the shell synthesizes safe local defaults for descriptor, transport, and requirements when those values are omitted
- the shell supports duplicate-peer rejection plus local peer removal and replacement helpers
- the shell now validates required bindings, local ownership, duplicate registration state, ancestry cycles, and nested depth through `init()`
- the shell now exposes child pipelines through `getPipelinesFromInterface()`
- the shell now supports pause/resume flags, runtime-state clearing, and trace clearing
- typed `distributionGridMetadata` now exists on `P2PDescriptor`
- `DISTRIBUTION_GRID_*` trace vocabulary now exists for validation and lifecycle events

## What Is Still Missing

`DistributionGrid` does not execute tasks yet.

The following areas remain unimplemented:

- local router-to-worker execution
- remote peer handoff
- registry discovery and leased membership
- handshake, negotiated policy, and session enforcement
- trace report and failure-analysis behavior
- runtime durability behavior
- memory-policy enforcement
- DITL orchestration hooks
- privacy, auth, and PCP mediation

## Current Shell Surface

The Phase 3 shell is still non-executing, but it now includes configuration, validation, and lifecycle methods.

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

### Readback helpers

- `getRouterBindingKey()`
- `getWorkerBindingKey()`
- `getLocalPeerKeys()`
- `getExternalPeerKeys()`
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

These files define the vocabulary for the future runtime, but they do not by themselves make the grid executable yet.

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

## Verification

The current shipped slice has focused coverage through:

- `DistributionGridContractModelsTest`
- `DistributionGridShellRegistrationTest`
- `DistributionGridValidationLifecycleTest`

## Contributing

If you are continuing `DistributionGrid` implementation, follow the internal phased rollout rather than adding features ad hoc:

1. Phase 4: local execution core
2. Phase 5: explicit remote peer handoff
3. Phase 6: registry discovery and membership
4. later hardening, DSL, and public-doc sync

---

**Previous:** [← Manifold](manifold.md) | **Next:** [Junction →](junction.md)
