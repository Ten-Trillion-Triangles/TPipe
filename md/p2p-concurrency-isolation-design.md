# P2P Concurrency Isolation Design

Date: 2026-03-27
Last Updated: 2026-03-27

## Purpose

This file is the authoritative design record for the P2P concurrency isolation workstream. It exists to document the structural concurrency problem in TPipe's P2P registry, define the solution approach, and guide the phased implementation without forcing the design to be re-derived.

Use this file for stable architecture and interface decisions. Implementation progress should be tracked in a separate plan or progress file once work begins.

## Problem Statement

### The Single-Instance Routing Model

TPipe's P2P registry maps one transport address to one live object:

```kotlin
// P2PRegistry.kt
Agents[transport] = P2PAgentListing(descriptor, requirements, agent)
```

Every inbound P2P request for that transport routes to the same object instance:

```kotlin
// P2PRegistry.executeP2pRequest
val agent = Agents[request.transport]
// ...
agent.container.executeP2PRequest(request)
```

This works correctly when the program creates instances and drives them directly. The caller controls when execution happens and can ensure sequential access.

### Where It Breaks

When requests arrive through the P2P transport layer (HTTP, Stdio, or internal TPipe routing), the caller does not control timing. Multiple inbound requests can arrive concurrently and all route to the same shared object. Stateful containers have mutable runtime state that is not safe for concurrent access.

### Affected Containers and Their Mutable State

**Manifold:**
- `workingContentObject` — shared working content mutated during execution
- `currentTaskProgress` — task progress tracker
- `loopIterationCount` — manager loop counter
- `agentInteractionMap` — per-agent interaction counts
- `isPaused` / `resumeSignal` — pause state shared across all requests

**Junction:**
- `discussionState` — shared discussion state mutated during rounds
- `workflowState` — workflow progress tracker
- `isPaused` / `resumeSignal` — pause state shared across all requests

**DistributionGrid:**
- `sessionRecordsByPeerKey` / `sessionRecordsById` — session cache written by concurrent handshakes
- `pauseRequested` — single boolean shared across all tasks
- `discoveredNodeAdvertisementsById` — discovery cache mutated during execution
- `activeRegistryLeasesById` — lease state mutated during execution

**Pipeline:**
- `currentPipeIndex` — execution position mutated during pipe traversal
- `isPaused` / `resumeSignal` — pause state
- `pipelineTokenUsage` — token accounting mutated during execution
- `internalConverseHistory` — conversation history appended during execution

### Why It Does Not Manifest in Normal Usage

In the typical TPipe usage pattern, the program creates container instances and calls `execute()` directly:

```kotlin
val manifold = Manifold()
manifold.setManagerPipeline(...)
manifold.addWorkerPipeline(...)
manifold.init()
val result = manifold.execute(content) // caller controls timing
```

The caller naturally serializes access. Concurrent execution requires the caller to create separate instances.

The P2P system inverts this. The registry holds one instance and routes external requests to it. The caller (the transport layer) has no knowledge of the container's internal state and no mechanism to serialize access.

### Existing Copy Infrastructure

TPipe already has reflection-based copy machinery:

**`constructPipeFromTemplate<T>(template: Pipe)`** in `Util.kt`:
- Creates a fresh instance via no-arg constructor
- Walks all mutable properties via reflection
- Skips `@Transient` annotated properties
- Deep-copies values using `deepCopy()`
- Handles Pipe-specific fields (functions, sub-pipes, metadata) with explicit flags
- Works for all Pipe subclasses including BedrockPipe, OllamaPipe, etc.

**`deepCopy<T>()`** in `Util.kt`:
- Handles primitives, strings, collections, and data classes
- Falls through to `else -> obj` (return same reference) for non-data classes
- This means containers (Manifold, Junction, Pipeline, DistributionGrid) are NOT deep-copied — they are returned as the same reference

**`copyPipeline(originalPipeline: Pipeline)`** in `Util.kt`:
- Creates a fresh Pipeline
- Copies pipeline-level properties
- Uses `constructPipeFromTemplate` for each pipe in the pipeline
- Does NOT copy pipeline-level runtime state (pause, tracing, converse history, etc.)

### The Gap

`constructPipeFromTemplate` proves the reflection-based clone pattern works for Pipes. But it is hardcoded to the `Pipe` type. `deepCopy` handles data classes but falls through on containers. No mechanism exists to recursively clone a full container tree (container → child pipelines → child pipes).

## Design Summary

Generalize the existing `constructPipeFromTemplate` reflection strategy to work on any TPipe class with a no-arg constructor. Add a concurrency mode to P2P registration so the registry can transparently clone the template per request when isolation is needed.

## Generalized Reflection Clone

### Strategy

The clone function applies the same strategy as `constructPipeFromTemplate` but generalized:

1. Create a fresh instance of the target class via its no-arg constructor
2. Walk all mutable properties via Kotlin reflection
3. For each property, classify and copy based on its type
4. Skip properties annotated with `@RuntimeState` (new) or `@Transient` (existing)
5. Return the fresh instance with all configuration state copied and all runtime state at defaults

### Property Classification

When the clone function encounters a property value, it applies the following classification:

| Value Type | Action | Rationale |
|---|---|---|
| `null` | Set null | Nothing to copy |
| Primitive, String, Enum | Copy directly | Immutable, safe to share |
| Data class | `deepCopy()` | Existing infrastructure handles this correctly |
| `List`, `Set`, `Map` | Deep copy contents recursively | Collections may contain data classes or other copyable types |
| `Pipe` subclass | `constructPipeFromTemplate()` | Existing infrastructure handles all Pipe subclasses including BedrockPipe, OllamaPipe |
| `Pipeline` | Recursive clone (new instance + copy pipes + copy config) | Extends `copyPipeline` pattern with full property walk |
| `P2PInterface` implementation | Recursive clone via the same generalized function | Handles nested containers (Manifold holding Pipelines, Junction holding P2PInterface participants) |
| Lambda / Function type | Share by reference | Lambdas are configuration (hooks, validators, transformers). They are stateless closures over the caller's logic. Copying them is neither possible nor necessary. |
| External resource interfaces (`DistributionGridDurableStore`, `DistributionGridTrustVerifier`, etc.) | Share by reference | These are backend integrations provided by the caller. The clone should use the same backend, not create a new one. |
| `Channel`, `Mutex`, `AtomicInteger` | Skip (leave at default) | These are concurrency primitives that are runtime state, not configuration |
| `UUID` / generated IDs | Skip (leave at default) | The fresh instance should generate its own identity |
| Everything else | Share by reference | Conservative fallback — unknown types are treated as external resources |

### The `@RuntimeState` Annotation

A new annotation marks fields that should be skipped during cloning:

```kotlin
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class RuntimeState
```

Fields annotated with `@RuntimeState` are left at their default value in the fresh instance. This covers:
- Execution counters (`loopIterationCount`, `currentPipeIndex`)
- Pause state (`isPaused`, `pauseRequested`, `resumeSignal`)
- Session caches (`sessionRecordsByPeerKey`, `sessionRecordsById`)
- Working state (`workingContentObject`, `discussionState`, `workflowState`)
- Initialization flags (`initialized`)
- Generated identities (`gridId`, `manifoldId`, `junctionId`, `pipelineId`)
- Token usage accumulators (`pipelineTokenUsage`, `pipeTokenUsage`)

The default behavior is: **unannotated mutable properties are cloned. Annotated properties are skipped.** This means new configuration fields added to any class are automatically included in the clone without maintenance. Only new runtime state fields need the annotation, and forgetting it is safe — the clone just carries extra state that gets reset on `init()`.

### Decision Rationale

**Why reflection over settings round-trip:**
Settings classes (`PipeSettings`) require manual maintenance — every new field must be added to both the class and the settings data class. Reflection auto-discovers fields. The constraint is zero maintenance when classes change.

**Why annotation over interface getters:**
Expanding `P2PInterface` with getters for every configuration property would bloat the interface with methods that only apply to specific container types (`getRoutingPolicy()` is meaningless on a Connector). The annotation approach keeps the interface clean and puts the classification knowledge on the field itself.

**Why share-by-reference for lambdas:**
Lambdas cannot be deep-copied. They are configuration provided by the caller (DITL hooks, validation functions, transformation functions). Sharing them across clones is correct — the caller's logic should apply identically to every clone. If a lambda closes over mutable external state, that is the caller's responsibility, not the framework's.

**Why share-by-reference for external resources:**
`DistributionGridDurableStore`, `DistributionGridTrustVerifier`, ContextBank references, and similar interfaces represent external backends. Cloning them would create duplicate connections or duplicate state. The clone should use the same backend as the original.

## P2P Registry Concurrency Modes

### Concurrency Mode Enum

```kotlin
enum class P2PConcurrencyMode {
    SHARED,   // Current behavior — one instance handles all requests (default)
    ISOLATED  // Each request gets a fresh clone
}
```

### Registration API

The explicit `register()` overload gains an optional concurrency mode parameter:

```kotlin
fun register(
    agent: P2PInterface,
    transport: P2PTransport,
    descriptor: P2PDescriptor,
    requirements: P2PRequirements,
    concurrencyMode: P2PConcurrencyMode = P2PConcurrencyMode.SHARED
)
```

A new overload accepts a factory function instead of a template instance:

```kotlin
fun register(
    factory: suspend () -> P2PInterface,
    transport: P2PTransport,
    descriptor: P2PDescriptor,
    requirements: P2PRequirements
)
```

Factory mode implies ISOLATED — every request calls the factory to produce a fresh instance.

### SHARED Mode Behavior (Default)

Identical to current behavior. The registry stores the agent reference and routes all requests to it. No cloning, no overhead. Backward compatible.

### ISOLATED Mode Behavior

1. At registration time, the registry stores the template agent as a blueprint source
2. On each inbound request via `executeP2pRequest`:
   a. Clone the template using the generalized reflection clone
   b. Call `init()` on the clone (if the clone is a container that requires initialization)
   c. Execute the request against the clone
   d. Clean up child agents registered by the clone's `init()`
   e. Discard the clone
3. The template is never mutated by request execution

### Factory Mode Behavior

1. At registration time, the registry stores the factory function, descriptor, and requirements
2. On each inbound request:
   a. Call the factory to produce a fresh instance
   b. Execute the request against the fresh instance
   c. Clean up child agents registered by the instance
   d. Discard the instance
3. The factory controls all construction logic — no reflection involved

### Child Agent Cleanup

When a container's `init()` runs, it typically registers child agents with the P2P registry (e.g., Manifold registers its manager and worker pipelines). In ISOLATED mode, these child registrations must be cleaned up after the request completes to prevent registry pollution.

The cleanup contract:
- Before execution, snapshot the registry's agent list
- After execution, remove any agents that were added during the clone's lifecycle
- This must be safe even if the clone's execution fails or throws

### Backward Compatibility

- `SHARED` is the default — all existing code works unchanged
- The auto-register overload (`register(agent: P2PInterface)`) continues to use SHARED mode
- No existing API signatures change
- No existing behavior changes unless the caller explicitly opts into ISOLATED or factory mode

## Implementation Program

### Phase 1: Generalize the Reflection Clone

Purpose: extend the existing copy infrastructure to handle full container trees.

Required scope:
- Add the `@RuntimeState` annotation
- Generalize `constructPipeFromTemplate`'s reflection strategy into a new function that works on any class with a no-arg constructor
- Handle the property classification table defined in this document
- Recursively clone nested P2PInterface implementations and Pipelines
- Unit test the clone on Pipeline, Manifold, Junction, DistributionGrid with representative configurations

Explicit exclusions:
- Do not modify P2PRegistry
- Do not modify any container class beyond adding `@RuntimeState` annotations
- Do not add concurrency modes yet

Entry criteria:
- This design document is approved

Exit criteria:
- The generalized clone function exists in Util.kt
- Cloning a configured Manifold produces a fresh instance with identical configuration and independent runtime state
- Cloning a configured DistributionGrid produces a fresh instance with identical configuration and independent runtime state
- Cloning a configured Junction produces a fresh instance with identical configuration and independent runtime state
- The existing `constructPipeFromTemplate` and `copyPipeline` behavior is unchanged

### Phase 2: Class Annotation Audit

Purpose: annotate every runtime-state field across all container classes.

Required scope:
- Walk every field in Pipeline, Pipe, Manifold, Junction, DistributionGrid, Connector, MultiConnector, Splitter
- Annotate runtime-transient fields with `@RuntimeState`
- Verify clone correctness by comparing configuration state between original and clone

Explicit exclusions:
- Do not modify P2PRegistry
- Do not add concurrency modes

Entry criteria:
- Phase 1 clone function exists and passes basic tests

Exit criteria:
- Every container class has its runtime fields annotated
- Clone-then-compare tests pass for all container types
- Clone-then-mutate tests confirm independence

### Phase 3: Registry Concurrency Modes

Purpose: add ISOLATED and factory modes to P2PRegistry.

Required scope:
- Add `P2PConcurrencyMode` enum
- Add the concurrency mode parameter to the explicit `register()` overload
- Add the factory `register()` overload
- Implement clone-per-request in `executeP2pRequest` for ISOLATED mode
- Implement factory-per-request for factory mode
- Implement child agent cleanup after isolated execution

Explicit exclusions:
- Do not change SHARED mode behavior
- Do not change the auto-register overload behavior

Entry criteria:
- Phase 2 annotations are complete and clone tests pass

Exit criteria:
- ISOLATED mode clones the template per request and discards after execution
- Factory mode calls the factory per request and discards after execution
- Child agents registered during clone init are cleaned up
- SHARED mode behavior is unchanged

### Phase 4: Integration Tests

Purpose: verify concurrency safety under realistic conditions.

Required scope:
- Send concurrent P2P requests to an ISOLATED Manifold and verify no shared state leakage
- Send concurrent P2P requests to an ISOLATED Junction and verify no shared state leakage
- Send concurrent P2P requests to an ISOLATED DistributionGrid and verify no shared state leakage
- Verify factory mode produces fresh instances per request
- Verify child agent cleanup after isolated execution
- Verify SHARED mode regression (existing behavior unchanged)

Entry criteria:
- Phase 3 registry changes are complete

Exit criteria:
- Concurrent execution tests pass without shared state races
- Factory mode tests pass
- SHARED mode regression tests pass

### Phase 5: Public Docs and DSL Updates

Purpose: expose concurrency mode configuration through user-facing surfaces.

Required scope:
- Update ManifoldDsl, JunctionDsl, and future DistributionGridDsl to support concurrency mode configuration
- Update P2P documentation to describe SHARED vs ISOLATED modes
- Update container documentation to recommend ISOLATED for P2P-exposed stateful containers

Entry criteria:
- Phase 4 integration tests pass

Exit criteria:
- DSL builders expose concurrency mode
- Public docs describe the concurrency model
- Users can opt into isolation without understanding the clone internals

## Class Audit Appendix

### Pipeline

| Field | Classification | Action |
|---|---|---|
| `pipelineName` | Config | Clone |
| `pipes` | Config (list of Pipe) | Clone each via `constructPipeFromTemplate` |
| `context` | Config | Deep copy |
| `pageKey` | Config | Clone |
| `tracingEnabled` | Config | Clone |
| `traceConfig` | Config | Deep copy |
| `wrapContentWithConverseHistory` | Config | Clone |
| `wrapPipeContentWithConverseHistory` | Config | Clone |
| `pipelineConverseRole` | Config | Clone |
| `pipeConverseRole` | Config | Clone |
| `userConverseRole` | Config | Clone |
| `wrapTextResponseOnly` | Config | Clone |
| `enablePipeTimeout` | Config | Clone |
| `pipeTimeout` | Config | Clone |
| `timeoutStrategy` | Config | Clone |
| `maxRetryAttempts` | Config | Clone |
| `pipeRetryFunction` | Lambda | Share by reference |
| `applyTimeoutRecursively` | Config | Clone |
| `pauseBeforePipes` | Config | Clone |
| `pauseAfterPipes` | Config | Clone |
| `pauseBeforeJumps` | Config | Clone |
| `pauseAfterRepeats` | Config | Clone |
| `pauseOnCompletion` | Config | Clone |
| `pausingEnabled` | Config | Clone |
| `pipelineId` | Runtime (generated ID) | `@RuntimeState` |
| `currentPipeIndex` | Runtime | `@RuntimeState` |
| `isPaused` | Runtime | `@RuntimeState` |
| `resumeSignal` | Runtime (Channel) | `@RuntimeState` |
| `pipelineTokenUsage` | Runtime | `@RuntimeState` |
| `internalConverseHistory` | Runtime | `@RuntimeState` |
| P2P fields (descriptor, transport, requirements) | Config | Deep copy |

### Manifold

| Field | Classification | Action |
|---|---|---|
| `managerPipeline` | Config (Pipeline) | Recursive clone |
| `workerPipelines` | Config (list of Pipeline) | Clone each recursively |
| `workerPipelinesByAgentName` | Config (map) | Clone with recursive Pipeline clone |
| `agentPaths` | Config | Deep copy |
| `agentPipeNames` | Config | Deep copy |
| `autoTruncateContext` | Config | Clone |
| `truncationMethod` | Config | Clone |
| `managerTokenBudget` | Config | Deep copy |
| `managerContextWindowOverride` | Config | Clone |
| `contextTruncationFunction` | Lambda | Share by reference |
| `workerValidatorFunction` | Lambda | Share by reference |
| `failureFunction` | Lambda | Share by reference |
| `transformationFunction` | Lambda | Share by reference |
| `tracingEnabled` | Config | Clone |
| `traceConfig` | Config | Deep copy |
| `manifoldId` | Runtime (generated ID) | `@RuntimeState` |
| `workingContentObject` | Runtime | `@RuntimeState` |
| `currentTaskProgress` | Runtime | `@RuntimeState` |
| `loopIterationCount` | Runtime | `@RuntimeState` |
| `agentInteractionMap` | Runtime | `@RuntimeState` |
| `isPaused` | Runtime | `@RuntimeState` |
| `resumeSignal` | Runtime (Channel) | `@RuntimeState` |
| P2P fields (descriptor, transport, requirements) | Config | Deep copy |
| `containerObject` | External reference | Share by reference |

### Junction

| Field | Classification | Action |
|---|---|---|
| `moderatorBinding` | Config (P2PInterface wrapper) | Recursive clone |
| `participantBindings` | Config (list of P2PInterface wrappers) | Clone each recursively |
| `participantBindingsByName` | Config (map) | Clone with recursive binding clone |
| `plannerBinding` | Config | Recursive clone |
| `actorBinding` | Config | Recursive clone |
| `verifierBinding` | Config | Recursive clone |
| `adjusterBinding` | Config | Recursive clone |
| `outputBinding` | Config | Recursive clone |
| `workflowRecipe` | Config | Clone |
| `moderatorInterventionEnabled` | Config | Clone |
| `defaultMaxNestedDepth` | Config | Clone |
| `junctionMemoryPolicy` | Config | Deep copy |
| `tracingEnabled` | Config | Clone |
| `traceConfig` | Config | Deep copy |
| `junctionId` | Runtime (generated ID) | `@RuntimeState` |
| `discussionState` | Runtime | `@RuntimeState` |
| `workflowState` | Runtime | `@RuntimeState` |
| `isPaused` | Runtime | `@RuntimeState` |
| `resumeSignal` | Runtime (Channel) | `@RuntimeState` |
| P2P fields (descriptor, transport, requirements) | Config | Deep copy |
| `containerObject` | External reference | Share by reference |

### DistributionGrid

| Field | Classification | Action |
|---|---|---|
| `routerBinding` | Config (P2PInterface wrapper) | Recursive clone |
| `workerBinding` | Config (P2PInterface wrapper) | Recursive clone |
| `localPeerBindingsByKey` | Config (map of P2PInterface wrappers) | Clone each recursively |
| `externalPeerDescriptorsByKey` | Config (map of descriptors) | Deep copy |
| `discoveryMode` | Config | Clone |
| `routingPolicy` | Config | Deep copy |
| `memoryPolicy` | Config | Deep copy |
| `maxHops` | Config | Clone |
| `tracingEnabled` | Config | Clone |
| `traceConfig` | Config | Deep copy |
| `registryMetadata` | Config | Deep copy |
| `bootstrapRegistriesById` | Config | Deep copy |
| `defaultMaxNestedDepth` | Config | Clone |
| `beforeRouteHook` | Lambda | Share by reference |
| `beforeLocalWorkerHook` | Lambda | Share by reference |
| `afterLocalWorkerHook` | Lambda | Share by reference |
| `beforePeerDispatchHook` | Lambda | Share by reference |
| `afterPeerResponseHook` | Lambda | Share by reference |
| `outboundMemoryHook` | Lambda | Share by reference |
| `failureHook` | Lambda | Share by reference |
| `outcomeTransformationHook` | Lambda | Share by reference |
| `durableStore` | External resource | Share by reference |
| `trustVerifier` | External resource | Share by reference |
| `gridId` | Runtime (generated ID) | `@RuntimeState` |
| `initialized` | Runtime | `@RuntimeState` |
| `pauseRequested` | Runtime | `@RuntimeState` |
| `sessionRecordsByPeerKey` | Runtime | `@RuntimeState` |
| `sessionRecordsById` | Runtime | `@RuntimeState` |
| `discoveredRegistriesById` | Runtime | `@RuntimeState` |
| `discoveredNodeAdvertisementsById` | Runtime | `@RuntimeState` |
| `activeRegistryLeasesById` | Runtime | `@RuntimeState` |
| `localRegisteredNodeAdvertisementsById` | Runtime | `@RuntimeState` |
| `localRegistrationLeasesById` | Runtime | `@RuntimeState` |
| `localRegistrationLeaseIdsByNodeId` | Runtime | `@RuntimeState` |
| `synthesizedPeerOrdinal` | Runtime | `@RuntimeState` |
| P2P fields (descriptor, transport, requirements) | Config | Deep copy |
| `containerObject` | External reference | Share by reference |

### Connector

| Field | Classification | Action |
|---|---|---|
| `branches` | Config (map of Pipeline) | Clone each recursively |
| `lastConnection` | Runtime | `@RuntimeState` |
| `pipelineId` | Runtime (generated ID) | `@RuntimeState` |
| P2P fields (descriptor, transport, requirements) | Config | Deep copy |

### MultiConnector

| Field | Classification | Action |
|---|---|---|
| `connectors` | Config (list of Connector) | Clone each recursively |
| `executionMode` | Config | Clone |
| P2P fields (descriptor, transport, requirements) | Config | Deep copy |
| `containerObject` | External reference | Share by reference |

### Splitter

| Field | Classification | Action |
|---|---|---|
| `activatorKeys` | Config (map of Pipeline + content) | Clone pipelines recursively, deep copy content |
| `onPipeLineFinish` | Lambda | Share by reference |
| `onSplitterFinish` | Lambda | Share by reference |
| `tracingEnabled` | Config | Clone |
| `traceConfig` | Config | Deep copy |
| `splitterId` | Runtime (generated ID) | `@RuntimeState` |
| `contents` | Runtime (ConcurrentHashMap) | `@RuntimeState` |
| `executionMutex` | Runtime (Mutex) | `@RuntimeState` |
| `isExecuting` | Runtime | `@RuntimeState` |
| `completedPipelines` | Runtime (AtomicInteger) | `@RuntimeState` |
| `totalPipelines` | Runtime | `@RuntimeState` |
| `splitterCompleted` | Runtime | `@RuntimeState` |

### Pipe (Handled by Existing Infrastructure)

Pipe subclasses are already handled by `constructPipeFromTemplate`. The generalized clone function delegates to it when it encounters a Pipe-typed property. No new annotations are needed on Pipe — the existing `@Transient` annotations and the explicit function/pipe/metadata copy flags already handle the classification.

BedrockPipe and OllamaPipe subclass-specific fields (region, service tier, model ID, streaming callbacks, etc.) are automatically discovered by `constructPipeFromTemplate`'s reflection walk. No per-subclass maintenance is required.

## Test Strategy Appendix

### Clone Correctness Tests

**Clone-then-compare:** Clone a fully configured container. Walk all non-`@RuntimeState` properties on both original and clone. Verify values are equal but not the same reference (for mutable types).

**Clone-then-mutate:** Clone a container. Mutate a configuration property on the original. Verify the clone is unaffected. Mutate a configuration property on the clone. Verify the original is unaffected.

**Clone-then-execute:** Clone a container. Execute a request on the clone. Verify the original's runtime state is unchanged.

**Nested clone:** Clone a Manifold containing multiple worker pipelines each containing multiple pipes. Verify the entire tree is independent.

### Concurrency Tests

**Parallel execution:** Register an ISOLATED Manifold. Send N concurrent P2P requests. Verify all N produce correct results. Verify no cross-request state leakage (each request sees its own conversation history, loop count, working content).

**Pause isolation:** Register an ISOLATED DistributionGrid. Send two concurrent requests. Pause one. Verify the other continues unaffected.

**Session isolation:** Register an ISOLATED DistributionGrid. Send two concurrent requests that both trigger peer handshake. Verify each gets its own session cache.

### Factory Mode Tests

**Factory invocation:** Register a factory. Send N requests. Verify the factory was called N times.

**Factory cleanup:** Register a factory that produces a Manifold. Send a request. Verify child agents registered during init are cleaned up after execution.

### Regression Tests

**SHARED mode unchanged:** Register a SHARED agent. Send sequential requests. Verify behavior matches current TPipe behavior exactly (shared state, conversation history accumulation, etc.).

**Existing copy infrastructure:** Verify `constructPipeFromTemplate`, `copyPipeline`, and `deepCopy` behavior is unchanged after the generalization.

## Source References

- `src/main/kotlin/P2P/P2PRegistry.kt` — single-instance routing
- `src/main/kotlin/P2P/P2PInterface.kt` — execution contract
- `src/main/kotlin/Util/Util.kt` — `constructPipeFromTemplate`, `deepCopy`, `copyPipeline`
- `src/main/kotlin/Pipeline/Manifold.kt` — stateful container
- `src/main/kotlin/Pipeline/Junction.kt` — stateful container
- `src/main/kotlin/Pipeline/DistributionGrid.kt` — stateful container
- `src/main/kotlin/Pipeline/Pipeline.kt` — stateful execution host
- `src/main/kotlin/Pipeline/Connector.kt` — lightweight container
- `src/main/kotlin/Pipeline/MultiConnector.kt` — lightweight container
- `src/main/kotlin/Pipeline/Splitter.kt` — concurrent container
- `src/main/kotlin/Pipe/Pipe.kt` — base pipe class
- `src/main/kotlin/Structs/PipeSettings.kt` — existing settings snapshot pattern
