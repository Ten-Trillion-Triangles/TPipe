# DistributionGrid Design

Date: 2026-03-25
Last Updated: 2026-03-26

## Purpose

This file is the authoritative design record for the `DistributionGrid` workstream. It exists to preserve the intended runtime shape, standards compliance, safety rules, and implementation contract for future sessions without forcing the design to be re-derived from old stub comments.

Use this file for stable architecture and interface decisions. Use [`md/distributiongrid-progress.md`](./distributiongrid-progress.md) for current implementation truth and [`md/distributiongrid-plan.md`](./distributiongrid-plan.md) for the single current task.

## Current Implementation Boundary

`DistributionGrid` is no longer a pure stub in the current working tree, but it is still only an explicit-peer remote-capable harness.

Verified shipped behavior:

- the Phase 1 contract-model layer exists for runtime, memory, durability, and protocol contracts
- the Phase 2 configuration shell exists on `DistributionGrid`
- the shell now stores grid-level P2P identity state
- the shell now supports router and worker binding plus local peer and external peer-descriptor registration
- the shell now synthesizes deterministic local descriptor, transport, and requirements defaults for local bindings
- the shell now enforces duplicate-peer rejection and local peer replacement or removal rules
- the shell now validates required bindings, local ownership, duplicate registration state, ancestry cycles, and nested depth through `init()`
- the shell now exposes child pipelines through `getPipelinesFromInterface()`
- the shell now supports pause/resume flags, runtime-state clearing, and trace clearing
- the shell now executes a local router-to-worker path through `execute(...)`, `executeLocal(...)`, and inbound `executeP2PRequest(...)`
- the shell now records local hop, outcome, and failure metadata and preserves normal TPipe content success or failure flags
- the shell now exposes public grid-level DITL hook registration for local route, local-worker, failure, and outcome-transformation stages
- typed `distributionGridMetadata` now exists on `P2PDescriptor`
- `DISTRIBUTION_GRID_*` trace vocabulary now exists for validation, lifecycle, and local execution phases
- explicit remote peer handoff now works for configured external peer descriptors
- explicitly framed serialized grid RPC messages now ride over the normal P2P request and response boundary
- mandatory first-contact handshake and in-memory session reuse now exist for explicit peers
- negotiated session policy is now authoritative for outbound and inbound remote task execution
- cached explicit-peer sessions are now reused only when their negotiated policy still satisfies the current task request
- widened handshake acknowledgements are now rejected before session caching or task handoff
- inbound remote envelopes now use the caller's return address as the recorded sender transport
- peer-authored handshake rejection details are preserved instead of being replaced by a generic session failure
- inbound remote task handoff now maps back through the same local execution core in single-node mode
- peer-dispatch and peer-response hooks now run on the explicit remote path
- focused tests exist for contract models, shell registration semantics, validation/lifecycle behavior, local execution behavior, and explicit remote handoff behavior

Verified missing behavior:

- no node router implementation
- no node worker implementation
- no peer discovery
- no registry-driven P2P routing logic
- no registry discovery
- no leased membership behavior
- no runtime durability behavior
- no runtime memory-policy behavior
- no outbound-memory hook invocation or memory-envelope shaping

This file records the intended architecture. It must not imply that the runtime below already exists.

## Design Summary

`DistributionGrid` is TPipe's remote grid harness.

One `DistributionGrid` instance represents one node on the grid, not the entire grid.

Every node contains two required runtime roles:

- a router harness that receives work, applies policy, selects peers, validates completion, handles failures, and decides where the task goes next
- a local worker harness that performs the job this node advertises to the grid

The wider grid emerges from node-to-node P2P communication across `Transport.Tpipe`, `Transport.Http`, and `Transport.Stdio`.

This is not a decentralized `Manifold`. The defining characteristic is that the task moves between independent nodes, services, or owners, and each node's router makes the next routing decision under TPipe policy and security controls.

## Required Architectural Decisions

The following decisions are locked for this workstream:

- one `DistributionGrid` object equals one node
- both router and worker are required on every node
- one task has one active downstream hop at a time
- separate top-level requests may still execute independently
- the router owns completion validation and next-hop decisions
- failure returns upstream by default
- completion routing is policy-driven
- outbound sharing is least-privilege by default
- requester trace and privacy policy is enforced, not advisory
- remote transports are first-class in the design, not deferred
- the new runtime must use a new envelope-first model, not the stub-era task/judge pair as the primary public contract
- durability is first-class and must use a pluggable store contract
- the harness must support raw API, Kotlin DSL, and Defaults-friendly ergonomics

## Implementation Program

The implementation order for `DistributionGrid` is locked. It exists to minimize churn, keep cross-cutting TPipe standards aligned, and prevent later-phase behavior from leaking into earlier slices.

Sequencing rules:

- each phase may depend only on completed earlier phases
- no phase may silently pull later-phase behavior forward
- if a phase discovers a new dependency boundary or guardrail, update this design file before code is written
- each phase must have explicit entry criteria, exclusions, and exit criteria
- public docs must continue to describe shipped behavior only, not future-phase behavior

### Phase 0: Steering Alignment

Purpose:

- codify the implementation order, task boundaries, exclusions, and acceptance targets in the steering set

Required scope:

- update the design, progress, and plan steering docs so the implementation sequence is explicit and phase-driven

Explicit exclusions:

- no runtime code changes
- no shared infrastructure changes
- no public docs that imply new shipped behavior

Entry criteria:

- approved architecture spec exists in the steering set
- runtime code is still at the clean stub baseline

Exit criteria:

- the steering docs agree on one implementation order
- the active task after this phase is `Phase 1: Foundation Contracts`

### Phase 1: Foundation Contracts

Purpose:

- define the stable contract vocabulary that later runtime, transport, and discovery work will depend on

Required scope:

- add the runtime contract model file
- add the memory model file
- add the durability model file
- add the protocol model file
- add a focused contract-model test file for serialization, defaults, and the durable-store interface shape

Explicit exclusions:

- do not modify `DistributionGrid.kt`
- do not modify `P2PDescriptor.kt`
- do not modify `TraceEventType.kt`
- do not add shell behavior, execution logic, registry logic, or tracing behavior

Entry criteria:

- `Phase 0` is complete
- the repo is still at the stub-only `DistributionGrid` baseline

Exit criteria:

- the new contract files compile cleanly
- the focused contract-model tests pass
- the codebase has the approved vocabulary for envelopes, directives, policy, memory, durability, and protocol metadata
- runtime behavior remains unchanged from the stub baseline

### Phase 2: Container Shell And Registration Semantics

Purpose:

- replace the stub-only class shell with the raw node-oriented API surface and safe registration behavior

Required scope:

- add router, worker, peer, discovery, routing, memory, durability, and tracing configuration APIs
- implement safe default descriptor and requirement generation for local bindings
- implement rebind cleanup, duplicate-peer handling, and one-node outward identity rules

Explicit exclusions:

- no real execution path
- no handshake enforcement
- no registry discovery
- no tracing event family yet unless strictly needed by lifecycle signatures

Entry criteria:

- `Phase 1` contract files exist and compile

Exit criteria:

- the raw configuration shell compiles
- registration semantics are explicit and safe
- the runtime still does not execute tasks

### Phase 3: Validation, Shared Infra, And Lifecycle

Purpose:

- add the guardrails and shared surfaces the shell needs before any real orchestration begins

Required scope:

- implement `init()` validation
- add local ancestry and cycle safety
- add `getPipelinesFromInterface()`
- add `pause()`, `resume()`, `isPaused()`, `canPause()`, `clearRuntimeState()`, and `clearTrace()`
- add explicit `P2PDescriptor` support for grid metadata
- add `DISTRIBUTION_GRID_*` trace event vocabulary

Explicit exclusions:

- no local worker orchestration yet
- no remote handoff yet
- no registry discovery yet

Entry criteria:

- `Phase 2` shell and registration behavior exists

Exit criteria:

- invalid configurations fail fast
- shared infra exists only where this phase requires it
- lifecycle and validation surfaces compile without introducing task routing

### Phase 4: Local Execution Core

Purpose:

- implement the first real runtime behavior through a local-only router-to-worker path

Required scope:

- normalize direct and inbound P2P execution into one envelope-driven runtime path
- implement local router decision handling
- dispatch the local worker and map local completion and failure behavior
- record hops, surface dual error states, and invoke orchestration-level DITL hooks in a defined order

Explicit exclusions:

- no remote peer dispatch
- no registry queries
- no registry lease behavior

Entry criteria:

- `Phase 3` validation and lifecycle scaffolding exists

Exit criteria:

- `execute(...)`, `executeLocal(...)`, and `executeP2PRequest(...)` share one normalized runtime path
- local-only execution works end to end
- terminal content success and failure semantics align with normal TPipe behavior

### Phase 5: Explicit Remote Peer Handoff

Purpose:

- add remote routing to explicitly configured peers before introducing registry discovery complexity

Required scope:

- support handoff to explicit peer descriptors only
- implement mandatory handshake, negotiated policy, session creation, session reuse, and remote return or failure mapping

Explicit exclusions:

- no registry discovery
- no registry lease renewal
- no trust-anchor discovery expansion beyond explicit peers

Entry criteria:

- `Phase 4` local execution path is working

Exit criteria:

- explicit peer handoff requires valid grid metadata plus handshake or valid session state
- remote returns and failures map back through the approved P2P boundary contract

### Phase 6: Registry Discovery And Membership

Purpose:

- introduce trusted discovery only after explicit peer routing is already proven

Required scope:

- add bootstrap trust anchors
- add registry advertisements and node advertisements
- add lease-based registration and renewal contracts
- add structured registry queries and candidate verification

Explicit exclusions:

- no widening of trust rules beyond the approved trust-anchor model
- no shortcut that treats registry presence alone as sufficient proof of grid identity

Entry criteria:

- `Phase 5` explicit peer handoff is working

Exit criteria:

- registries are discovered and queried through the trust-anchor model
- discovered peers are admitted only after verification plus handshake rules

### Phase 7: Cross-Cutting Runtime Hardening

Purpose:

- make the runtime standards-complete against the approved TPipe memory, privacy, auth, durability, and tracing expectations

Required scope:

- implement outbound memory shaping and redaction
- implement durable-store checkpoints and resume behavior
- implement privacy-policy enforcement and credential-routing behavior
- implement PCP mediation and safe pause checkpoints
- align trace export with the normal `PipeTracer.exportTrace(...)` path

Explicit exclusions:

- no new transport model
- no public doc claims beyond shipped behavior

Entry criteria:

- `Phase 6` discovery and remote routing rules are in place

Exit criteria:

- cross-cutting runtime behavior matches the approved policies in this document
- the runtime is standards-complete enough for DSL and public-doc stabilization

### Phase 8: DSL, Defaults, Public Docs, And Final Coverage

Purpose:

- finish the developer-facing ergonomics and sync all outward-facing documentation and tests to the shipped runtime

Required scope:

- add `DistributionGridDsl.kt`
- add Defaults-friendly builder targets
- update public docs to match the shipped runtime
- complete the full test matrix for local execution, remote handoff, discovery, policy, memory, tracing, and durability

Explicit exclusions:

- do not describe behavior that still is not shipped

Entry criteria:

- `Phase 7` hardening is complete

Exit criteria:

- DSL and Defaults ergonomics reflect real runtime behavior
- public docs describe shipped behavior accurately
- final tests cover the major runtime and policy paths

## Runtime Model

### Node shape

Every `DistributionGrid` node must own:

- one router binding
- one worker binding
- zero or more explicitly attached local peers
- zero or more externally loaded peer descriptors
- routing policy
- memory policy
- trace and privacy policy support
- credential propagation policy
- durability integration
- tracing state
- pause and resume state
- local ancestry guards
- remote hop-loop guards

### Execution stance

One task may only have one active downstream hop at a time.

This means the grid's core harness is sequential per task, not globally single-threaded. Different top-level tasks may still run concurrently in separate executions.

The grid is request and response aware, but the design must also support durable resumability through an explicit persistence contract rather than assuming the task always lives only in the current stack frame.

### Router responsibilities

The router is the node's control surface. It must be able to:

- accept inbound work from local execution or P2P
- normalize the task into the grid envelope
- inspect local worker capabilities, peer capabilities, policy, and current task state
- decide whether to run local work, hand off, return, retry, or fail
- mediate PCP requests
- mediate outbound auth behavior
- enforce trace and privacy policy
- enforce outbound redaction and memory budgets
- save and load durable state through the configured store

### Worker responsibilities

The local worker harness performs the node's advertised job.

The worker:

- executes local business logic or model-driven reasoning
- may use normal TPipe pipe and pipeline features
- may use local DITL, context, PCP, and tracing features
- does not own peer selection
- does not own final return routing

## Public Runtime Contract

The new runtime must be built around an envelope-first contract.

### Primary public models

#### `DistributionGridEnvelope`

The main task object that moves through the grid.

It must carry at minimum:

- stable task id
- origin transport and origin node identity
- immediate sender transport and sender node identity
- current node identity
- `MultimodalContent`
- task intent and current local objective
- routing policy
- trace and privacy policy
- credential propagation policy
- compact execution notes
- hop history
- completion status
- latest failure or rejection state
- durability metadata

#### `DistributionGridDirective`

Structured router decision output.

It must support:

- run local worker
- hand off to specific peer
- return to sender
- return to origin
- return to explicit transport
- retry same peer
- try alternate peer
- reject or terminate

#### `DistributionGridOutcome`

Structured final result emitted by the harness and stored in the returned `MultimodalContent`.

It must summarize:

- terminal status
- final return target
- task id
- final content
- completion notes
- hop count
- any terminal failure data

#### `DistributionGridFailure`

Structured error or rejection record.

It must include:

- failure type
- source node
- target peer if applicable
- transport method
- reason
- policy cause if applicable
- whether the failure is retryable

#### `DistributionGridHopRecord`

Compact audit record for one hop.

It must include:

- source node or sender
- destination node or peer
- transport method
- router action
- privacy decision
- result summary
- timestamps or elapsed time fields

#### `DistributionGridRoutingPolicy`

Policy DSL governing completion and failure behavior.

It must support:

- return to sender
- return to origin
- explicit return transport
- return after first local node so the caller router takes over again
- failure return upstream by default
- retry and alternate-peer behavior
- max hop limits

#### `DistributionGridTracePolicy`

Requester-controlled privacy and tracing contract.

It must support:

- tracing allowed
- tracing disallowed
- mandatory redaction
- reject nodes that require tracing or disallowed storage
- distinguish between local debug state and outbound persisted trace content

#### `DistributionGridCredentialPolicy`

Credential-routing DSL.

It must support:

- no secret forwarding by default
- explicit credential references
- explicit per-hop credential selection
- optional transforms or replacements by policy
- explicit denial of secret propagation

#### `DistributionGridMemoryPolicy`

Container-level memory and outbound redaction policy.

It must support:

- hard token budgets
- deterministic compaction
- optional summarization subordinate to hard budgets
- section-level redaction
- trace-storage redaction rules
- safety reserve tokens
- compact execution notes
- hop-history retention limits

#### `DistributionGridMemoryEnvelope`

Least-privilege outbound memory snapshot for a hop.

It should contain compact sections derived from the current envelope rather than the full local execution state.

#### `DistributionGridDurableState`

Stored task snapshot for save, load, and resume behavior.

It must be stable enough for:

- in-process resume
- remote resume
- inspection after failures
- transport-agnostic persistence

#### `DistributionGridNodeMetadata`

Explicit metadata block that marks a `P2PDescriptor` as a grid node rather than a generic P2P agent.

It must carry at minimum:

- stable node id
- supported grid protocol versions
- router and worker capability summary
- registry memberships
- supported transports
- handshake requirements
- default trace and privacy stance
- default routing-policy range

#### `DistributionGridRegistryMetadata`

Explicit metadata block for registries.

It must carry:

- stable registry id
- trust domain identity
- bootstrap or trust-anchor relationship
- lease policy
- supported protocol versions
- dedicated-registry vs mixed-role mode

#### `DistributionGridNodeAdvertisement`

Structured registry-returned advertisement for a candidate grid node.

It must wrap:

- node descriptor
- node metadata
- registry identity
- lease state
- freshness timestamps
- attestation or signature data

#### `DistributionGridRegistryAdvertisement`

Structured advertisement for a discovered registry.

It must wrap:

- registry descriptor or access path
- registry metadata
- trust-chain or attestation data
- freshness fields

#### `DistributionGridRegistrationRequest`

Node-to-registry registration payload.

It must support:

- initial registration
- node advertisement update
- requested lease duration
- health and capability restatement

#### `DistributionGridRegistrationLease`

Returned lease state for a registered node.

It must include:

- lease id
- node id
- registry id
- granted duration
- expiry timestamp
- renewal requirements

#### `DistributionGridRegistryQuery`

Structured router query for eligible downstream nodes.

It must support:

- required role or capability filters
- transport constraints
- registry or trust-domain filters
- policy compatibility constraints
- freshness and health requirements

#### `DistributionGridRegistryQueryResult`

Registry response containing eligible node advertisements, not just raw descriptors.

#### `DistributionGridHandshakeRequest`

Mandatory first-contact node-to-node handshake payload.

It must exchange:

- node identity
- registry relationship in use for the session
- protocol versions
- supported transports
- role and capability claims
- trace and privacy stance
- auth and credential-routing requirements
- accepted routing-policy range
- requested session duration

#### `DistributionGridHandshakeResponse`

Handshake result payload.

It must contain:

- accepted or rejected status
- negotiated protocol version
- negotiated policy intersection
- rejection reason if denied
- session information if accepted

#### `DistributionGridNegotiatedPolicy`

Strictest compatible policy intersection between requester and responder.

#### `DistributionGridSessionRecord`

Cached trust and compatibility record for a successful node-to-node relationship.

It must include:

- session id
- participating node ids
- registry relationship used
- negotiated protocol version
- negotiated policy
- expiry and invalidation metadata

#### `DistributionGridSessionRef`

Lightweight session reference reused in later grid RPC messages.

#### `DistributionGridRpcMessage`

Versioned grid message envelope carried over normal P2P request and response flows.

It must include:

- message type
- sender node id
- target node or registry id
- protocol version
- optional session reference
- typed payload

Framed RPC traffic is fail-closed: if a prompt carries the grid RPC prefix but the payload cannot be deserialized,
the request must be rejected as protocol traffic instead of falling through to ordinary execution.
Handshake acknowledgements must also echo the session identity carried by the RPC wrapper before the sender caches
the negotiated session record.

### Legacy stub models

`DistributionGridTask` and `DistributionGridJudgement` remain historical stub-era models.

They should be treated as:

- documentation of the original placeholder direction
- possible migration notes if needed later
- not the primary public runtime contract for the new grid

Do not design the new runtime around preserving these as the main task envelope.

### Grid identity and node marking

Grid nodes must be explicitly marked.

Do not infer grid identity from:

- `agentName`
- free-form description text
- `P2PSkills`
- the presence of a router-like system prompt

A path is only treated as a valid grid node when all of the following are true:

- the `P2PDescriptor` contains valid `DistributionGridNodeMetadata`
- the node is surfaced by a trusted registry advertisement or trusted explicit bootstrap record
- the mandatory node handshake succeeds

This keeps generic P2P agents distinct from grid nodes and prevents the router from treating ordinary P2P tools as trusted grid peers.

## Public API Expectations

### Raw configuration API

The raw API should provide:

- `setRouter(...)`
- `setWorker(...)`
- `addPeer(...)`
- `addPeerDescriptor(...)`
- `setDiscoveryMode(...)`
- `setRoutingPolicy(...)`
- `setMemoryPolicy(...)`
- `setDurableStore(...)`
- `setMaxHops(...)`
- `enableTracing(...)`
- `disableTracing()`
- `clearRuntimeState()`
- `clearTrace()`
- `getTraceReport(...)`
- `getFailureAnalysis()`
- `pause()`
- `resume()`
- `isPaused()`
- `canPause()`
- `init()`
- `execute(...)`
- `executeLocal(...)`
- `executeP2PRequest(...)`

Optional `P2PDescriptor` and `P2PRequirements` arguments on router, worker, and peer registration methods are part of the intended public ergonomics.

If those values are omitted, the grid should synthesize secure local defaults rather than forcing the caller to hand-author every descriptor and requirement object.

### DITL-style container hooks

`DistributionGrid` must expose orchestration-level DITL hooks.

The container should support hook points for:

- before route evaluation
- before local worker execution
- after local worker execution
- before peer dispatch
- after peer response
- outbound memory shaping
- failure handling
- final outcome transformation

This is a hybrid DITL model:

- normal pipe and pipeline DITL remains on the child router and worker harnesses
- orchestration-level DITL exists on the container where routing and policy decisions happen

The container-level hooks are not read-only observer callbacks.

They must be able, within explicit hook contracts, to:

- transform envelope content
- reroute or replace a directive
- request retry behavior
- reject a hop
- terminate execution
- reshape outbound memory before handoff

### Kotlin DSL and Defaults ergonomics

The first real runtime should support:

- `DistributionGridDsl.kt`
- explicit DSL blocks for `router`, `worker`, `peer`, `routing`, `memory`, `durability`, `security`, and `tracing`
- a runtime layout that future `TPipe-Defaults` builders can target cleanly

The grid should not require callers to hand-wire every repetitive integration if a normal TPipe builder pattern can simplify the setup.

## Peer Discovery

Peer discovery must support three modes:

- explicit peers only
- registry or catalog only
- hybrid

The default design target is hybrid discovery.

That means the router may consider:

- explicit local peer bindings attached to the node
- peers visible through `P2PRegistry`
- remote descriptors loaded into the client-side catalog

The router must still filter visibility through node policy before handing candidates to the routing logic.

## Local Registration And Visibility

`DistributionGrid` is one outward-facing grid node, but it still manages nested local bindings internally.

Default visibility rules:

- router binding is local by default
- worker binding is local by default
- attached local peers are local by default
- outward advertisement happens at the node level unless explicitly expanded in a future opt-in mode

This means the node should normally appear as one grid endpoint to external discovery even though it contains multiple internal roles.

### Local container binding

During `init()` the grid must bind local router and worker objects to the grid container before local registry registration.

This keeps nested bindings from leaking into global visibility accidentally and preserves the local-container semantics used by other TPipe harnesses.

### Safe default registration behavior

When router, worker, or peer bindings are registered without explicit `P2PDescriptor` or `P2PRequirements`, the grid should synthesize secure local defaults.

Those defaults should follow the same broad safety posture used by other harnesses:

- local-only visibility unless explicitly advertised
- conservative auth and context behavior
- no blind duplication or custom-schema exposure
- enough descriptor detail for internal routing, tracing, and debugging

### Rebinding and duplicate handling

Role rebinding should be explicit and safe.

Expected behavior:

- `setRouter(...)` replaces any previous router binding
- `setWorker(...)` replaces any previous worker binding
- replacing a role removes stale local registry state before the new binding is registered
- `addPeer(...)` rejects duplicate peer identity unless the old peer has been explicitly removed or replaced through a dedicated path

Canonical duplicate-peer identity should use:

- explicit grid node id when available
- transport identity as fallback when no stable grid node id is present

### Outward advertisement shape

The default outward advertisement model is one public node descriptor.

That public descriptor may contain:

- transport path for the grid node
- `DistributionGridNodeMetadata`
- high-level router and worker capability summary
- request-template and auth hints needed to contact the node

Router and worker internals should not be advertised as separate public P2P agents by default.

### Nested pipeline exposure

Even though outward discovery treats the grid as one node endpoint, `getPipelinesFromInterface()` should still expose the nested child pipelines required for:

- tracing propagation
- container interoperability
- nested harness inspection

## Registry Discovery And Membership

### Discovery model

The grid uses bootstrap-plus-registry discovery.

This means:

- nodes begin from explicit bootstrap trust anchors
- bootstrap entries may be registry URLs, stdio programs, or explicitly trusted seed nodes
- additional registries may be learned from trusted registry advertisements
- newly learned registries must chain back to a configured trust anchor through attestation or equivalent verification

The design must not assume:

- one permanent central registry
- unauthenticated open discovery
- peer gossip as the only discovery path

### Registry roles

Both of the following are first-class deployment models:

- dedicated registry services
- mixed-role nodes that also expose registry behavior

The runtime must distinguish these explicitly through `DistributionGridRegistryMetadata`. Callers must not have to guess from hostnames or path layouts whether an endpoint is acting as a registry.

### Membership model

Nodes may belong to multiple registries or trust domains at once.

The handshake and session record must identify which registry relationship is being used for the current interaction.

### Registration lifecycle

Node registration is lease-based.

Required behavior:

- initial registration returns a lease record
- nodes renew before lease expiry
- stale memberships are removed automatically
- lease renewal is the authoritative freshness signal
- registries may optionally perform probes for richer health data

### Registry query shape

Routers must send structured queries, not full task envelopes and not “list everything” requests.

Structured query matching should include:

- required node role or capabilities
- accepted transport constraints
- registry or trust-domain filter
- policy compatibility constraints
- freshness and health limits

Registry responses must return signed or attested candidate advertisements rather than plain raw descriptors only.

## Trust Model

Trust starts from configured bootstrap trust anchors.

The design target is not:

- trust on first use
- open unauthenticated discovery
- “trust the registry blindly forever”

Instead:

- bootstrap trust anchors define the initial trusted roots
- registry advertisements and node advertisements must be verifiable against those roots
- node-to-node task handoff still requires its own handshake even when the registry is trusted

The exact cryptographic backend may remain pluggable, but the spec requires explicit verification and attestation fields in the advertisement and handshake models.

## Node Handshake And Session Protocol

### Handshake requirement

First contact between grid nodes requires a mandatory versioned handshake.

The handshake is required even if:

- the node was returned by a trusted registry
- the transport path is already known
- the peer descriptor appears valid

### Versioning rule

Protocol compatibility follows:

- major version must match
- minor version is negotiated to the highest mutually supported value

If no compatible version exists, the handshake must fail before task handoff.

### Policy negotiation

Policy negotiation is intersection-or-reject.

That means:

- requester policy does not silently override responder policy
- responder policy does not silently erase requester constraints
- the session uses the strictest compatible overlap
- the handshake fails if no safe overlap exists

This applies at minimum to:

- trace and privacy policy
- credential-routing rules
- routing-policy ranges
- storage restrictions

### Session behavior

Successful handshake creates a cached session record.

Session rules:

- later handoffs reference a `DistributionGridSessionRef`
- repeated hops should not replay the full handshake state when a valid session still exists
- sessions expire and must be renewed or renegotiated
- session invalidation must occur on expiry, trust change, revocation, or protocol incompatibility

Transport connection identity alone must not be treated as the session record.

### Session storage

Negotiated sessions live in memory by default.

The design should also support optional durable-store integration so long-running tasks can resume safely after interruption or process restart.

Durable storage of sessions is optional, not automatic.

## Grid RPC Message Family

The grid protocol rides on normal TPipe P2P transport. It is not a separate transport stack.

The new grid language should therefore be modeled as a family of grid RPC messages carried inside normal P2P request and response flows.

Expected message types include:

- `REGISTER_NODE`
- `RENEW_LEASE`
- `QUERY_REGISTRY`
- `PROBE_REGISTRY`
- `HANDSHAKE_INIT`
- `HANDSHAKE_ACK`
- `TASK_HANDOFF`
- `TASK_RETURN`
- `TASK_FAILURE`
- `SESSION_CLOSE`
- `SESSION_REJECT`

This keeps `DistributionGrid` aligned with TPipe's existing transport, auth, descriptor, and request-template systems while still giving grid nodes a distinct language for:

- discovery
- trust establishment
- policy negotiation
- durable task exchange

## P2P Boundary Contract

`DistributionGrid` must remain P2P-native at the transport boundary.

That means:

- the outward execution surface still uses normal `P2PRequest`
- `executeP2PRequest(...)` still returns normal `P2PResponse`
- grid-native models ride inside the request and response flow rather than replacing it

### Failure mapping

Grid-specific failures must map cleanly into the normal P2P response surface.

This includes:

- handshake rejection
- session rejection
- registry or trust rejection
- policy rejection
- task-level grid failure

The response should return standard `P2PRejection` semantics at the boundary while preserving grid-specific detail in structured grid metadata.

### Return-path alignment

The grid envelope may track richer routing state such as origin, sender, and return policy, but the outer P2P call must still preserve normal `returnAddress` behavior so it remains compatible with existing TPipe transport flows.

### Content-level completion semantics

Grid-native outcome records complement normal TPipe content semantics rather than replacing them.

Expected behavior:

- successful terminal grid completion should preserve normal successful content-return behavior
- unrecoverable terminal grid failure should preserve normal failure or termination behavior
- structured grid outcome and failure metadata should be attached in addition to the normal content-level signals

### Error-surface compatibility

When a failure occurs after child router or worker execution has started, callers should be able to inspect both:

- grid-native structured failure metadata
- native content or pipeline error state when child execution already produced it

## Registry And Node Verification Rules

Before a router may send a task to a discovered endpoint, it must be certain the target is a valid grid peer.

The minimum acceptance chain is:

- trusted bootstrap or trusted registry advertisement
- valid node advertisement with explicit grid metadata
- successful node handshake
- active session reference or fresh handshake result

If any of these are missing, the endpoint must be treated as a generic P2P agent or rejected outright, not silently elevated to a grid node.

## Memory And Durability Standards

### Grid-owned memory behavior

`DistributionGrid` must have robust built-in memory management comparable to other serious TPipe harnesses.

This includes:

- hard outbound token budgeting
- deterministic compaction
- optional summarization only as a secondary aid
- compact execution-note management
- bounded hop history
- outbound least-privilege memory shaping
- separation of local node memory from cross-node memory

The grid must not blindly forward full `ConverseHistory` or other rich local memory structures to downstream nodes.

### Standard memory integration hooks

Beyond the grid's own memory policy, the harness should define standard hook points for broader TPipe memory systems.

The design target is a broad but still structured hook set for:

- loading task state before route
- persisting task state after each hop
- loading task or support pages before local work
- persisting task pages after local work
- remote-memory sync before handoff
- remote-memory sync after return
- scoped memory-introspection policy for the router
- scoped memory-introspection policy for the worker

This keeps the grid native to TPipe's memory ecosystem without turning the container into the owner of every memory backend.

### Inbound mutation safety

Inbound `P2PRequest` content and context must be normalized into deep-copied execution state before local execution begins.

This prevents callers from observing accidental mutation when the grid reuses one runtime path for direct execution and inbound P2P execution.

### Durability contract

Durability is first-class in the spec.

The harness must define a pluggable `DistributionGridDurableStore` contract that supports:

- save task state
- load task state
- resume task state
- clear or archive finished task state

The durable store must be backend-agnostic so applications can implement it using:

- disk
- ContextBank-backed storage
- remote memory services
- custom business storage

## Tracing And Privacy Standards

`DistributionGrid` must meet full harness tracing standards.

### Trace behavior

It must support:

- dedicated `DISTRIBUTION_GRID_*` trace events
- shared trace correlation across local node execution
- safe checkpoint-based pause and resume
- structured failure analysis

Expected trace event families include:

- start, end, success, failure
- router decision
- local worker dispatch and response
- peer dispatch and response
- return routing
- memory-envelope build and redaction
- trace-policy enforcement
- durable save and load
- loop guard and rejection events

The tracing surface should also include the same practical lifecycle controls used by the more mature harnesses:

- manual trace clearing
- clear per-run runtime state without destroying configuration
- trace-report export
- structured failure analysis access

`getTraceReport(...)` should use the normal `PipeTracer.exportTrace(...)` path so existing trace export behavior, including optional remote dispatch through `RemoteTraceConfig`, continues to work without a separate grid-only export system.

### Privacy behavior

Requester trace and privacy policy is enforced, not advisory.

The grid must support:

- refusing nodes whose tracing behavior conflicts with the task policy
- mandatory redaction even when tracing is allowed
- no-tracing requests
- storage restrictions for trace and task persistence

Existing `P2PDescriptor` privacy hints such as prompt recording and interaction recording remain descriptor-level signals, not absolute enforcement.

However, when requester privacy policy requires stricter handling, the router must treat incompatible descriptor privacy hints as hard compatibility filters during peer selection and handshake.

## P2P, PCP, Auth, And Security Standards

### P2P alignment

The grid must use normal TPipe P2P models and transport handling.

Do not invent a separate transport framework for the grid.

Outbound request shaping should preserve the normal TPipe request-template precedence:

- caller override first
- then peer descriptor `requestTemplate`
- then registry template fallback

### PCP mediation

PCP requests are router-mediated.

That means the router decides, by policy and peer capability, whether a PCP request:

- executes locally
- is stripped
- is forwarded
- causes rejection

PCP should not be free-forwarded without the router checking policy first.

### Auth behavior

Credential behavior is policy-driven.

The default assumption must be:

- requester secrets are not blindly forwarded
- nodes resolve their own outbound auth where possible through `AuthRegistry`, request templates, or explicit policy references

If a task needs more complex auth routing, it must do so through the credential DSL rather than informal secret propagation.

### Validation order

The grid should use split-phase validation rather than collapsing every concern into one step.

Expected order:

1. basic transport and identity checks
2. grid marker, trust-anchor, and advertisement validation
3. session validation or mandatory handshake
4. policy negotiation
5. standard `P2PRequirements` validation for content, converse rules, duplication, token budgets, auth, and related request constraints
6. task execution

This preserves normal TPipe validation behavior while still ensuring the router never executes a task against an untrusted or non-grid endpoint.

## Loop And Safety Guards

The grid needs two separate safety systems.

### Local graph safety

During `init()` the node must validate:

- required roles exist
- local nested binding ancestry is safe
- local object cycles are rejected
- local depth caps are sane

### Remote routing safety

During execution the node must enforce:

- visited-hop guards
- max hop caps
- explicit rejection of runaway routes
- durable-state consistency during resume

## Runtime Lifecycle

The grid should follow the same broad runtime hygiene as the mature harnesses.

### Per-run reset behavior

Each execution starts from a clean runtime snapshot for transient execution state.

Reset between runs:

- active hop state
- current directive state
- pause tokens
- transient route caches
- per-run execution notes

Preserve across runs unless explicitly cleared:

- static configuration
- router and worker bindings
- trust anchors and peer descriptors
- trace configuration
- durable-store configuration

### Manual lifecycle controls

`clearRuntimeState()` should clear transient execution state without discarding configuration.

`clearTrace()` should clear accumulated trace history without disabling tracing or removing trace configuration.

### Pause checkpoints

Pause and resume must only be honored at safe routing boundaries.

The grid must not suspend:

- mid-hop
- mid-response merge
- mid-policy negotiation
- mid-durable-state transition

## Test Expectations

The first serious implementation wave must include coverage for:

- missing router or worker rejection
- local ancestry and cycle rejection
- local execute and P2P execute sharing one runtime path
- P2P response and rejection mapping for grid-native failures
- policy-driven return to sender, origin, and explicit transport
- return-after-first-local-work
- hybrid discovery behavior
- local-by-default binding visibility and outward single-node advertisement behavior
- upstream failure propagation
- PCP mediation behavior
- least-privilege outbound redaction
- trace and privacy enforcement
- credential policy enforcement
- hop-loop and max-hop rejection
- durable save, load, and resume hooks
- pause and resume checkpoints
- DSL validation failures
- explicit grid-node marking and rejection of unmarked generic P2P agents
- registry advertisement validation
- lease registration, renewal, and expiry handling
- structured registry queries and candidate advertisement matching
- mandatory node handshake behavior
- major-version match and minor-version negotiation
- policy intersection and rejection on incompatible constraints
- session reference reuse and expiry
- optional durable-session restore behavior
- inbound request deep-copy normalization
- mixed dedicated-registry and mixed-role registry deployments

## Non-Goals

The following are not goals of the current design record:

- claiming the runtime is already implemented
- preserving the old stub task and judge types as the main long-term contract
- turning the grid into a second `Manifold`
- assuming all nodes are equally trusted
- assuming full local memory should be visible cross-node

## Source References

- `src/main/kotlin/Pipeline/DistributionGrid.kt`
- `src/main/kotlin/Pipeline/Manifold.kt`
- `src/main/kotlin/Pipeline/Junction.kt`
- `src/main/kotlin/P2P/P2PRegistry.kt`
- `src/main/kotlin/P2P/P2PHost.kt`
- `docs/containers/distributiongrid.md`
- `docs/advanced-concepts/p2p/p2p-registry-and-routing.md`
- `docs/advanced-concepts/p2p/p2p-descriptors-and-transport.md`
- `docs/core-concepts/developer-in-the-loop.md`
- `docs/core-concepts/tracing-and-debugging.md`
- `docs/advanced-concepts/memory-introspection.md`
- `docs/advanced-concepts/remote-memory.md`
