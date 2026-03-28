# P2P Concurrency Isolation Progress

Date: 2026-03-27
Last Updated: 2026-03-27

## Overview

This file is the living implementation record for the P2P concurrency isolation workstream. It is intended to survive compaction and let a future session resume from the current truth without re-deriving status from the source tree.

Steering-set ownership:

- Keep architecture, interfaces, and guardrails in [`md/p2p-concurrency-isolation-design.md`](./p2p-concurrency-isolation-design.md).
- Keep the single current task in [`md/p2p-concurrency-isolation-plan.md`](./p2p-concurrency-isolation-plan.md).
- Keep this file focused on current implementation truth, approved decisions, evidence, and revision history.

## Current Status

- The `@RuntimeState` annotation exists at `src/main/kotlin/Util/RuntimeState.kt` (package `com.TTT.Util`).
- The generalized `cloneInstance()` and `cloneValue()` functions exist in `src/main/kotlin/Util/Util.kt`.
- The clone function handles the full property classification table from the steering doc:
  - Primitives, Strings, Enums: copy directly
  - Data classes: `deepCopy()`
  - Collections (List, Set, Map, and mutable variants): deep copy contents recursively
  - `Pipe` subclasses: `constructPipeFromTemplate()` with `copyFunctions=true`, `copyPipes=true`, `copyMetadata=true`
  - `P2PInterface` implementations: recursive `cloneInstance()`
  - Lambdas / Function types: share by reference
  - Classes with no-arg constructors: recursive `cloneInstance()`
  - Everything else: share by reference
  - `@RuntimeState` annotated properties: skipped (left at default)
  - `@Transient` annotated properties: skipped (left at default)
- No container classes have been annotated with `@RuntimeState` yet.
- No P2PRegistry changes have been made yet.
- No tests have been written yet for the clone function.
- The existing `constructPipeFromTemplate`, `copyPipeline`, and `deepCopy` functions are unchanged.

## Phase Status Board

- `Phase 1: Generalize the Reflection Clone` — complete
- `Phase 2: Class Annotation Audit` — complete
- `Phase 3: Registry Concurrency Modes` — complete
- `Phase 4: Integration Tests` — not started
- `Phase 5: Public Docs and DSL Updates` — not started

## Phase Evidence

- `Phase 2`: All container classes annotated with `@RuntimeState` on runtime-transient fields. Pipeline (6 fields), Manifold (7), Junction (5), DistributionGrid (12), Connector (2), Splitter (7). Clean compile and all existing tests pass after annotation.
- `Phase 3`: `P2PConcurrencyMode` enum added (SHARED/ISOLATED). `P2PAgentListing` extended with `concurrencyMode` and `factory` fields. Explicit `register()` accepts concurrency mode. Factory `register()` overload stores factory and sets ISOLATED. `executeP2pRequest` branches on mode: SHARED passes directly, ISOLATED clones or calls factory, child agents cleaned up in finally block. 4 focused tests pass (shared default, isolated independence, factory invocation, child cleanup).

## Plan At A Glance

- [x] Create `@RuntimeState` annotation
- [x] Implement generalized `cloneInstance()` clone function
- [ ] Phase 1 clone tests (Pipeline, Manifold, Junction, DistributionGrid)
- [ ] Phase 2 annotation audit (Pipeline, Manifold, Junction, DistributionGrid, Connector, MultiConnector, Splitter)
- [ ] Phase 3 registry concurrency modes (`P2PConcurrencyMode`, `register()` overloads, `executeP2pRequest` clone path)
- [ ] Phase 4 integration tests (concurrent execution, factory mode, cleanup, regression)
- [ ] Phase 5 DSL and public docs

## Completed Work

- Created the steering doc with problem statement, design, implementation program, class audit appendix, and test strategy appendix.
- Created `@RuntimeState` annotation targeting PROPERTY with RUNTIME retention.
- Implemented `cloneInstance<T>(template: T): T` — creates fresh instance via no-arg constructor, walks mutable properties via reflection, classifies and copies per the steering doc property classification table, skips `@RuntimeState` and `@Transient` annotated properties.
- Implemented `cloneValue(value: Any): Any` — the property classifier that routes each value type to the correct copy strategy.

## Approved Decisions

- Reflection-based cloning over settings round-trip (zero maintenance when classes change).
- `@RuntimeState` annotation over interface getters (no interface bloat).
- Share-by-reference for lambdas (configuration, not state).
- Share-by-reference for external resources like `DistributionGridDurableStore` (backend integrations).
- `SHARED` mode is the default for backward compatibility.
- `ISOLATED` mode clones per request; factory mode calls factory per request.
- Child agents registered during clone `init()` must be cleaned up after execution.

## Files Modified

- `src/main/kotlin/Util/RuntimeState.kt` — created (annotation)
- `src/main/kotlin/Util/Util.kt` — added `cloneInstance()`, `cloneValue()`, and `P2PInterface` import

## Context From Prior Session

The P2P concurrency isolation workstream was identified during a review of the DistributionGrid implementation. The review found that `pauseRequested` is grid-global (not per-task) and there is no per-run state isolation for concurrent tasks. Tracing the issue upstream revealed it is structural: the P2P registry routes all inbound requests to a single shared object instance, and all stateful containers (Manifold, Junction, DistributionGrid, Pipeline) have mutable runtime state that races under concurrent access.

The same session also:
- Identified and fixed a coding agent's violation of the P2P auth contract (container-level auth in Junction and Manifold that should have been registry-only)
- Restored the two-layer auth contract: transport perimeter handles `globalAuthMechanism`, registry handles `requirements.authMechanism`, containers do no auth
- Added fail-fast registration check for `requiresAuth=true` without any mechanism
- Added `globalAuthMechanism` fallback in `checkAgentRequirements`
- Reviewed the DistributionGrid implementation against its spec and TPipe standards
- Fixed hop record transport regression, separated credential injection from policy validation, eliminated ManifoldDsl build/buildSuspend duplication, documented PCP forwarding behavioral change

## Commands Used

- `./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.jvmargs=-Xmx6g compileKotlin` — verified clean compilation after clone function addition
- `./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.jvmargs=-Xmx6g clean compileKotlin compileTestKotlin` — full clean compile
- `./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.daemon.jvmargs=-Xmx6g test --tests "com.TTT.P2PRegistryRequirementsTest" --tests "com.TTT.Pipeline.ManifoldDslTest" --tests "com.TTT.Pipeline.DistributionGrid*" --tests "com.TTT.Pipeline.JunctionTest" -x :TPipe-Bedrock:test -x :TPipe-Defaults:test -x :TPipe-MCP:test -x :TPipe-Ollama:test -x :TPipe-TraceServer:test -x :TPipe-Tuner:test` — run affected test suites
