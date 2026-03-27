# DistributionGrid Progress

Date: 2026-03-25
Last Updated: 2026-03-26

## Overview

This file is the living implementation record for the `DistributionGrid` workstream. It is intended to survive compaction and let a future session resume from the current truth without re-deriving status from the source tree.

Steering-set ownership:

- Keep architecture, interfaces, and guardrails in [`md/distributiongrid-design.md`](./distributiongrid-design.md).
- Keep the single current task in [`md/distributiongrid-plan.md`](./distributiongrid-plan.md).
- Keep this file focused on current implementation truth, approved decisions, evidence, and revision history.

## Current Status

- `DistributionGrid` is now a registry-aware, Phase 6 harness as of 2026-03-26.
- The old stub-era `setEntryPipeline()` surface and legacy task or judgement placeholder runtime are no longer the active implementation shape.
- The contract layer from Phase 1 is in place:
  - `DistributionGridModels.kt`
  - `DistributionGridMemoryModels.kt`
  - `DistributionGridDurabilityModels.kt`
  - `DistributionGridProtocolModels.kt`
- The Phase 2 configuration shell remains in place:
  - grid-level `P2PInterface` identity storage
  - router and worker binding APIs
  - local peer and external peer-descriptor registration APIs
  - safe synthesized local defaults for descriptor, transport, and requirements
  - duplicate-peer rejection, local replacement or removal helpers, and tracing configuration storage
- The Phase 3 validation and lifecycle slice is now in place:
  - `init()` validation for router, worker, max hops, ownership, duplicate-key safety, and ancestry
  - synthesized outward node identity plus typed `distributionGridMetadata`
  - `getPipelinesFromInterface()` child-pipeline exposure for local bindings
  - `pause()`, `resume()`, `isPaused()`, `canPause()`, `clearRuntimeState()`, and `clearTrace()`
  - `DISTRIBUTION_GRID_*` trace vocabulary and validation/lifecycle trace emission
- The Phase 4 local execution slice is now in place:
  - `execute(...)`, `executeLocal(...)`, and `executeP2PRequest(...)` now share one normalized local runtime path
  - local router-to-worker execution now works end to end
  - router return, reject, terminate, and unsupported remote-directive paths now map to terminal local outcomes or failures
  - local hop history, outcome metadata, failure metadata, and dual content-level success or failure signaling now exist
  - public grid-level DITL hook registration now exists for route, local-worker, failure, and outcome-transformation stages
  - local execution-time grid tracing now emits start, decision, local worker, success or failure, and end events
- The Phase 5 explicit remote handoff slice is now in place:
  - explicit remote handoff now works through configured external peer descriptors
  - grid RPC now rides over normal P2P requests through explicitly framed serialized `DistributionGridRpcMessage` payloads
  - first-contact handshake, negotiated policy, and in-memory session reuse now exist for explicit peers
  - negotiated session policy is now enforced for outbound and inbound remote task execution
  - cached sessions are now reused only when they still satisfy the current task policy, otherwise a fresh handshake is required
  - widened handshake acknowledgements are now rejected before session caching or task handoff
  - inbound remote envelopes now record the caller's return address as the sender transport
  - peer-authored handshake rejection details are now preserved instead of being flattened into a generic session rejection
  - inbound remote `HANDSHAKE_INIT`, `TASK_HANDOFF`, `TASK_RETURN`, `TASK_FAILURE`, and `SESSION_REJECT` handling now exists
  - remote returns and failures now map back into the sender envelope and terminal content surface, and stale sessions are invalidated on `SESSION_REJECT`
  - peer-dispatch and peer-response hooks now run on the explicit remote path
- The Phase 6 registry discovery and membership slice is now in place:
  - bootstrap registry advertisements can now be configured, probed, and cached as verified discovery roots
  - a pluggable `DistributionGridTrustVerifier` now gates registry and node advertisement admission
  - `DistributionGrid` now supports explicit registry metadata plus inbound `PROBE_REGISTRY`, `REGISTER_NODE`, `RENEW_LEASE`, and `QUERY_REGISTRY` RPC handling
  - nodes can now register with trusted registries, renew leases explicitly, and keep local `registryMemberships` synchronized from active leases
  - structured registry queries now return verified `DistributionGridNodeAdvertisement` candidates that are cached by node id
  - discovered nodes can now feed the existing remote-handoff runtime when `discoveryMode` allows it, while still requiring explicit grid metadata plus node handshake or valid node session state
- Phase 6 discovery and membership now also harden the cache boundary:
  - discovered nodes are evicted before routing when they are stale or when their trust anchor is removed
  - discovered nodes are cached with registry-scoped identity so the same node id can be learned from multiple registries without overwriting earlier entries
  - discovered node advertisements are canonicalized under the resolved node id instead of being cached under an empty-string alias
  - registry lease replies are shape-validated before active lease or membership state can change
  - cached registry advertisements are revalidated before reuse so expired registries are evicted instead of being contacted indefinitely
  - descriptor-less registrations no longer fall back to the registry transport when the caller does not supply a return address
  - fresh registrations replace the previous live lease for the same node instead of leaving stale lease state behind
- Focused regression coverage now exists for contract models, shell registration behavior, validation/lifecycle behavior, local execution behavior, and explicit remote handoff behavior:
  - `DistributionGridContractModelsTest.kt`
  - `DistributionGridShellRegistrationTest.kt`
  - `DistributionGridValidationLifecycleTest.kt`
  - `DistributionGridExecutionCoreTest.kt`
  - `DistributionGridRemoteHandoffTest.kt`
  - `DistributionGridRegistryDiscoveryTest.kt`
- `DistributionGrid` still does not perform durable-session persistence, runtime durability behavior, outbound memory shaping, or full privacy or auth or PCP mediation yet.
- The steering-doc set now contains the approved full node-based architecture specification for the future runtime.
- The implementation order has now been codified into explicit phases so runtime work can proceed without crossing phase boundaries accidentally.

## Phase Status Board

- `Phase 0: Steering Alignment` — complete
- `Phase 1: Foundation Contracts` — complete
- `Phase 2: Container Shell And Registration Semantics` — complete
- `Phase 3: Validation, Shared Infra, And Lifecycle` — complete
- `Phase 4: Local Execution Core` — complete
- `Phase 5: Explicit Remote Peer Handoff` — complete
- `Phase 6: Registry Discovery And Membership` — complete
- `Phase 7: Cross-Cutting Runtime Hardening` — not started
- `Phase 8: DSL, Defaults, Public Docs, And Final Coverage` — not started

## Phase Evidence

- `Phase 0`: the steering docs now define the implementation order, phase boundaries, exclusions, and acceptance targets.
- `Phase 1`: the contract model files and focused contract-model tests now compile and pass without changing `DistributionGrid.kt`, `P2PDescriptor.kt`, or `TraceEventType.kt`.
- `Phase 2`: the old stub shell has been replaced with the non-executing configuration shell, and focused shell-registration tests now pass without adding execution flow, descriptor metadata changes, or trace vocabulary changes.
- `Phase 3`: `DistributionGrid` now validates its local graph, exposes child pipelines, carries typed grid metadata on descriptors, and passes focused validation/lifecycle tests without adding task execution.
- `Phase 4`: `DistributionGrid` now executes the local router-to-worker path through one normalized runtime flow, preserves normal TPipe success or failure semantics, exposes grid-level DITL hooks, and passes focused execution-core tests without adding remote handoff or discovery.
- `Phase 5`: `DistributionGrid` now performs explicit-peer remote handoff through explicitly framed grid RPC messages, enforces handshake or valid-session requirements for explicit peers, and passes focused remote-handoff tests without adding registry discovery.
- `Phase 5`: `DistributionGrid` now preserves caller return-address transport on inbound remote envelopes and peer-authored handshake rejection details on `SESSION_REJECT`.
- `Phase 5`: `DistributionGrid` now invalidates cached explicit-peer sessions when the peer rejects a task handoff at the transport boundary or returns a non-grid response.
- `Phase 5`: `DistributionGrid` now uses a structured explicit-peer session cache key so peer and registry strings cannot alias through separator collisions.
- `Phase 6`: `DistributionGrid` now performs trusted bootstrap-registry probing, lease-based registration and renewal, structured registry queries, and discovered-node admission without weakening the existing node-handshake requirements.
- `Phase 6`: `DistributionGrid` now revalidates cached registry advertisements before reuse and rejects descriptor-less registrations that omit a caller return address.

## Plan At A Glance

- [x] Phase 0: Steering Alignment
- [x] Phase 1: Foundation Contracts
- [x] Phase 2: Container Shell And Registration Semantics
- [x] Phase 3: Validation, Shared Infra, And Lifecycle
- [x] Phase 4: Local Execution Core
- [x] Phase 5: Explicit Remote Peer Handoff
- [x] Phase 6: Registry Discovery And Membership
- [ ] Phase 7: Cross-Cutting Runtime Hardening
- [ ] Phase 8: DSL, Defaults, Public Docs, And Final Coverage

## Completed Work

- The Phase 1 contract layer now exists for envelopes, directives, policies, memory, durability, and protocol metadata.
- The Phase 1 durable-store interface contract now exists as a backend-agnostic public contract.
- Focused contract-model tests now cover serialization, defaults, protocol-version negotiation, and the durable-store interface shape.
- The Phase 2 shell now exposes grid-level P2P identity storage and non-executing configuration APIs for router, worker, peer, discovery, routing, memory, durability, and tracing state.
- The Phase 2 shell now synthesizes deterministic local defaults for router, worker, and peer bindings when explicit descriptor or requirements values are omitted.
- The Phase 2 shell now enforces duplicate-peer rejection, supports local peer replacement and removal, and preserves one outward node identity at the grid level.
- Focused shell-registration tests now cover binding defaults, rebinding behavior, peer key handling, external descriptor storage, and the non-executing Phase 2 boundary.
- The Phase 3 shell now validates required bindings, local ownership, duplicate registration state, ancestry cycles, and nested depth before execution is allowed later.
- The Phase 3 shell now synthesizes a safe outward node descriptor, transport, requirements, and typed `distributionGridMetadata` during `init()` when they are missing.
- The Phase 3 shell now exposes child pipelines for router, worker, and local peers through `getPipelinesFromInterface()`.
- The Phase 3 shell now supports pause/resume flags, runtime-state clearing, trace clearing, and validation/lifecycle trace emission without adding task execution.
- Focused Phase 3 tests now cover init validation, foreign-owner rejection, cycle detection, nested-depth enforcement, lifecycle state, and trace clearing.
- The Phase 4 shell now executes local work through `execute(...)`, `executeLocal(...)`, and `executeP2PRequest(...)` using one normalized envelope-driven local runtime path.
- The Phase 4 shell now supports local router-to-worker execution, router return/reject/terminate handling, local hop recording, and terminal outcome or failure metadata.
- The Phase 4 shell now exposes grid-level DITL hook registration for the local execution flow and reserves peer or outbound-memory hook surfaces for later remote phases.
- Focused Phase 4 tests now cover local execution normalization, direct and inbound P2P execution, directive handling, local failure preservation, hook ordering, and execution-time tracing.
- The Phase 5 shell now performs explicit remote peer handoff through configured external peer descriptors only and keeps attached local peers out of the remote target set.
- The Phase 5 shell now serializes grid RPC messages into `P2PRequest.prompt.text` so handshake and task-exchange traffic can ride over the normal `Transport.Tpipe`, `Transport.Http`, and `Transport.Stdio` request paths.
- The Phase 5 shell now enforces mandatory first-contact handshake for explicit peers, caches valid sessions in memory, invalidates stale sessions on `SESSION_REJECT`, and reuses those sessions on repeated explicit-peer calls until invalidation or expiry.
- The Phase 5 shell now supports inbound handshake and task-handoff RPC handling while forcing inbound remote task execution to stay in single-node mode.
- The Phase 5 shell now treats negotiated session policy as authoritative during remote task execution, revalidates cached sessions against the current task envelope before reuse, rejects widened handshake acknowledgements, preserves caller return-address transport on inbound remote envelopes, preserves peer-authored handshake rejection details, and no longer auto-detects internal RPC traffic from ordinary JSON prompts.
- The Phase 5 shell now evicts explicit-peer sessions after peer-level transport rejections and malformed remote responses so the next call can renegotiate cleanly.
- The Phase 5 shell now keeps explicit-peer cache keys structured instead of concatenating raw peer and registry strings.
- Focused Phase 5 tests now cover explicit remote handoff success, session reuse, explicit-peer metadata rejection, inbound nested-handoff rejection, peer hook invocation, local-peer exclusion, and boundary handshake rejection.
- Internal docs already describe `DistributionGrid` as incomplete rather than production-ready.
- The `DistributionGrid` steering set now has dedicated design, progress, and plan files.
- The design doc now records the approved remote node architecture, the envelope-first public contract, and the TPipe standards integration surface for the future runtime.
- The steering set now also records the approved phased implementation order and the acceptance boundary for each phase.

## Approved Decisions

- One `DistributionGrid` instance represents one node.
- Every node requires both a router and a local worker harness.
- The router owns completion validation and next-hop decisions.
- One task has one active downstream hop at a time, though separate top-level requests may still run independently.
- Completion routing is policy-driven rather than hardcoded to immediate sender behavior.
- The new runtime will use a new envelope-first public contract.
- `DistributionGridTask` and `DistributionGridJudgement` are treated as legacy stub-era models, not the long-term runtime contract.
- Peer discovery is hybrid by design.
- Remote transports are first-class in the design target.
- The grid must use least-privilege outbound memory sharing by default.
- The grid must expose hybrid DITL support: orchestration hooks at the container layer and normal DITL inside child pipelines.
- The grid must meet full harness tracing standards.
- Requester trace and privacy policy is enforced, not advisory.
- The grid must support a credential-routing DSL rather than blind auth forwarding.
- The grid must define a pluggable durable store contract.
- The grid must support raw API, DSL, and Defaults-friendly ergonomics.
- Grid nodes must be explicitly marked through grid metadata rather than inferred from generic P2P descriptor text or skills.
- Registry discovery uses bootstrap trust anchors plus trusted registry advertisements.
- Both dedicated registries and mixed node-registry roles are first-class deployment models.
- Node registration is lease-based with renewal and expiry semantics.
- Registry queries return signed or attested node advertisements rather than raw descriptor lists alone.
- First contact between grid nodes requires a mandatory versioned handshake.
- Protocol compatibility requires major-version match and minor-version negotiation.
- Policy negotiation is intersection-or-reject.
- Successful handshakes create cached session records that later handoffs may reference.
- Implementation order is locked to minimize churn: contracts, shell, validation and lifecycle, local execution, explicit remote handoff, registry discovery, hardening, then DSL and public-doc sync.
- Shared infrastructure changes such as grid descriptor metadata and trace vocabulary must land only in the phase that actually requires them.
- Explicit remote peer handoff must be implemented before registry discovery.
- Phase 2 hard-replaces the stub shell rather than keeping a deprecated compatibility bridge.
- Phase 2 includes `removePeer(...)` and `replacePeer(...)` as part of the shell surface.
- Phase 2 remains a configuration shell only and intentionally defers `init()`, validation, lifecycle controls, and execution behavior to later phases.
- Phase 3 uses `Reject Foreign` ownership validation rather than rebinding local components during `init()`.
- Phase 3 adds shell-level pause/resume state now, but real execution checkpoints remain deferred to Phase 4.
- Phase 3 adds an optional typed `distributionGridMetadata` field directly on `P2PDescriptor`.
- Phase 3 synthesizes the grid node's outward identity when missing rather than requiring callers to supply it up front.
- Phase 4 exposes public grid-level DITL hook registration now rather than deferring the public hook surface to later hardening phases.
- Phase 4 defaults a missing router directive to `RUN_LOCAL_WORKER` and currently resolves router decisions from `MultimodalContent.metadata["distributionGridDirective"]`.
- Phase 4 preserves TPipe-style terminal content semantics by setting `passPipeline = true` on successful local completion and `terminatePipeline = true` on terminal local failure.
- Phase 5 supports explicit remote handoff only through `addPeerDescriptor(...)` external peers; attached local peers remain local-only.
- Phase 5 currently finalizes locally after one remote reply rather than resuming a full multi-hop routing loop at the sender.
- Phase 5 currently supports only primary `HAND_OFF_TO_PEER`; retry and alternate-peer directives remain deferred.

## Risks And Guardrails

- Risk: future sessions may assume the old dispatcher or judge placeholder model still governs the design.
- Guardrail: the design doc is now the architectural source of truth, and the old stub models are explicitly marked as legacy.

- Risk: the public container doc still reflects the earlier placeholder architecture too strongly.
- Guardrail: keep public docs explicitly scoped to current shipped behavior until the runtime catches up, and point design-specific decisions to the steering set.

- Risk: node-to-node routing could accidentally bypass normal TPipe privacy, auth, or PCP constraints.
- Guardrail: the router is required to mediate trace policy, credential policy, PCP forwarding, and outbound memory redaction.

- Risk: a generic P2P agent could be mistaken for a grid node if the runtime relies on names or free-form descriptions.
- Guardrail: grid identity now requires explicit grid metadata plus trusted advertisement and handshake success.

- Risk: open or weak discovery could let untrusted registries or stale nodes steer task routing.
- Guardrail: registry discovery must begin from trust anchors, use lease freshness, and return verifiable advertisements.

- Risk: the steering set can become stale if only code changes are tracked.
- Guardrail: update this file whenever implementation truth or approved design decisions materially change.

- Risk: implementation work may again jump ahead of the approved dependency order and create rework.
- Guardrail: the phase board is now authoritative for what may be implemented next, and each phase has explicit exclusions in the design doc.

- Risk: the explicit-peer remote slice could be mistaken for full discovery-ready distributed routing.
- Guardrail: all docs and tests must continue to state clearly that after Phase 5 only explicit-peer remote handoff is shipped; registry discovery, leased membership, and cross-cutting hardening remain unimplemented.

## Verification Log

- Reviewed `src/main/kotlin/Pipeline/DistributionGrid.kt` to confirm the current shell surface, non-executing boundary, and registration semantics.
- Reviewed `src/main/kotlin/Pipeline/Manifold.kt` and `src/main/kotlin/Pipeline/Junction.kt` to align the grid with existing TPipe harness standards.
- Reviewed `src/main/kotlin/P2P/P2PRegistry.kt`, `P2PDescriptor.kt`, `P2PRequest.kt`, and `P2PRequirements.kt` to ground the remote transport, auth, and request-template design.
- Reviewed `src/main/kotlin/P2P/P2PHost.kt` and P2P docs to ground stdio hosting, descriptor transport semantics, and the current lack of built-in grid handshake support.
- Reviewed `docs/core-concepts/developer-in-the-loop.md` and `docs/core-concepts/developer-in-the-loop-pipes.md` to ground the DITL surface.
- Reviewed `docs/core-concepts/tracing-and-debugging.md` to ground the tracing and privacy surface.
- Reviewed `docs/advanced-concepts/memory-introspection.md` and `docs/advanced-concepts/remote-memory.md` to ground optional memory integrations and durable-state expectations.
- Ran `./gradlew compileKotlin compileTestKotlin` after landing the Phase 1 contract files.
- Ran `./gradlew test --tests "com.TTT.Pipeline.DistributionGridContractModelsTest" -x :TPipe-Bedrock:test -x :TPipe-Defaults:test -x :TPipe-MCP:test -x :TPipe-Ollama:test -x :TPipe-TraceServer:test -x :TPipe-Tuner:test` to verify the contract-model test slice.
- Reviewed `src/main/kotlin/Pipeline/DistributionGrid.kt` and `src/test/kotlin/Pipeline/DistributionGridShellRegistrationTest.kt` while implementing the Phase 2 shell and registration semantics.
- Ran `./gradlew compileKotlin compileTestKotlin` after landing the Phase 2 shell.
- Ran `./gradlew test --tests "com.TTT.Pipeline.DistributionGridShellRegistrationTest" -x :TPipe-Bedrock:test -x :TPipe-Defaults:test -x :TPipe-MCP:test -x :TPipe-Ollama:test -x :TPipe-TraceServer:test -x :TPipe-Tuner:test` to verify the Phase 2 shell-registration slice.
- Reviewed `src/main/kotlin/Pipeline/DistributionGrid.kt`, `src/main/kotlin/P2P/P2PDescriptor.kt`, and `src/main/kotlin/Debug/TraceEventType.kt` while implementing the Phase 3 validation and shared-infra slice.
- Ran `./gradlew compileKotlin compileTestKotlin` after landing the Phase 3 validation/lifecycle changes.
- Ran `./gradlew test --tests "com.TTT.Pipeline.DistributionGridShellRegistrationTest" --tests "com.TTT.Pipeline.DistributionGridValidationLifecycleTest" -x :TPipe-Bedrock:test -x :TPipe-Defaults:test -x :TPipe-MCP:test -x :TPipe-Ollama:test -x :TPipe-TraceServer:test -x :TPipe-Tuner:test` to verify the Phase 2 and Phase 3 test slices together.
- Reviewed `src/main/kotlin/Pipeline/DistributionGrid.kt`, `src/main/kotlin/P2P/P2PRequest.kt`, `src/main/kotlin/P2P/P2PResponse.kt`, `src/main/kotlin/Pipeline/Junction.kt`, and `docs/core-concepts/developer-in-the-loop.md` while implementing the Phase 4 local execution flow.
- Ran `./gradlew compileKotlin compileTestKotlin` after landing the Phase 4 execution changes.
- Ran `./gradlew test --tests "com.TTT.Pipeline.DistributionGrid*" -x :TPipe-Bedrock:test -x :TPipe-Defaults:test -x :TPipe-MCP:test -x :TPipe-Ollama:test -x :TPipe-TraceServer:test -x :TPipe-Tuner:test` to verify the DistributionGrid contract, shell, lifecycle, and local execution slices together.
- Reviewed `src/main/kotlin/Pipeline/DistributionGrid.kt`, `src/main/kotlin/P2P/P2PRegistry.kt`, `src/main/kotlin/P2P/P2PRequest.kt`, and `src/main/kotlin/P2P/P2PResponse.kt` while implementing Phase 5 explicit remote handoff and grid RPC transport.
- Ran `./gradlew compileKotlin compileTestKotlin` after landing the Phase 5 remote-handoff runtime.
- Ran `./gradlew test --tests "com.TTT.Pipeline.DistributionGrid*" -x :TPipe-Bedrock:test -x :TPipe-Defaults:test -x :TPipe-MCP:test -x :TPipe-Ollama:test -x :TPipe-TraceServer:test -x :TPipe-Tuner:test` again after landing the Phase 5 remote-handoff slice and focused tests.
- Reviewed the Phase 5 stabilization findings for stale-session recovery, negotiated-policy enforcement, RPC framing, and hop-count consistency before landing the follow-up fixes.
- Reviewed the follow-up Phase 5 policy-integrity findings for cached-session reuse and widened handshake acknowledgements before landing the second stabilization pass.
- Reviewed the Phase 5 transport and rejection-detail findings for inbound sender transport and peer-authored handshake failures before landing the final repair pass.
- Reviewed the final Phase 5 boundary findings for fail-closed framed RPC rejection and handshake ACK session-identity validation before landing the last repair pass.

Commands used during the architecture pass:

- `rg -n "DistributionGrid|Manifold|Junction|P2PRegistry|ConverseHistory|TokenBudget|enableTracing|PcPRequest" ...`
- `sed -n '1,260p' src/main/kotlin/Pipeline/DistributionGrid.kt`
- `sed -n '1,260p' src/main/kotlin/Pipeline/Manifold.kt`
- `sed -n '1,260p' src/main/kotlin/Pipeline/Junction.kt`
- `sed -n '1,320p' src/main/kotlin/P2P/P2PRegistry.kt`
- `sed -n '1,260p' src/main/kotlin/P2P/P2PHost.kt`
- `sed -n '1,260p' docs/containers/distributiongrid.md`
- `sed -n '1,240p' docs/advanced-concepts/p2p/p2p-descriptors-and-transport.md`
- `sed -n '1,260p' docs/core-concepts/developer-in-the-loop.md`
- `sed -n '1,260p' docs/core-concepts/tracing-and-debugging.md`

## Revision History

- 2026-03-25: Initial `DistributionGrid` steering set created with dedicated design, progress, and plan files.
- 2026-03-25: `md/tpipe-context-starting-point.md` updated to point future sessions at the new steering set.
- 2026-03-25: Full node-based `DistributionGrid` architecture spec approved and written into the steering set.
- 2026-03-25: Registry discovery, node marking, handshake, and session protocol requirements were added to the approved design.
- 2026-03-25: Accidental runtime prototype changes were rolled back so implementation can restart from the clean stub baseline in the intended order.
- 2026-03-25: The steering docs were updated to codify the exact phased implementation order, phase exclusions, and acceptance targets.
- 2026-03-25: Phase 1 landed the new contract model files and focused contract-model tests without changing the stub runtime shell or shared infrastructure.
- 2026-03-25: Phase 2 replaced the stub shell with the non-executing configuration shell and added focused shell-registration tests.
- 2026-03-25: Phase 3 added validation, lifecycle controls, child-pipeline exposure, typed grid metadata, and focused validation/lifecycle tests.
- 2026-03-25: Phase 4 added the first local execution core, grid-level DITL hook registration, local terminal outcome or failure mapping, and focused execution-core tests.
- 2026-03-25: Phase 5 added explicit-peer remote handoff, grid RPC over the normal P2P boundary, mandatory handshake, in-memory session reuse, and focused remote-handoff tests.
- 2026-03-26: Phase 5 repair pass started to preserve exact requested session lifetimes and serialize post-hook remote content instead of the pre-hook envelope snapshot.
- 2026-03-26: Phase 6 discovery repair pass hardened discovered-node freshness, registry-scoped discovered-node cache identity, registry lease-response validation, and stale-live-lease replacement on re-registration.
