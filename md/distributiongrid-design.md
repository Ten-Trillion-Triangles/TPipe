# DistributionGrid Design

Date: 2026-03-25
Last Updated: 2026-03-25

## Purpose

This file is the authoritative design record for the `DistributionGrid` workstream. It exists to preserve the intended runtime shape, standards compliance, safety rules, and implementation contract for future sessions without forcing the design to be re-derived from old stub comments.

Use this file for stable architecture and interface decisions. Use [`md/distributiongrid-progress.md`](./distributiongrid-progress.md) for current implementation truth and [`md/distributiongrid-plan.md`](./distributiongrid-plan.md) for the single current task.

## Current Implementation Boundary

`DistributionGrid` is still a stub in the current working tree.

Verified shipped behavior:

- `DistributionGridTask` exists as a stub-era task model.
- `DistributionGridJudgement` exists as a stub-era judge model.
- `setEntryPipeline()` validates that one pipe emits the expected `DistributionGridTask` JSON schema before storing the pipeline reference.

Verified missing behavior:

- no node router implementation
- no node worker implementation
- no execution loop
- no peer discovery
- no P2P routing logic
- no durability support
- no tracing surface
- no memory policy
- no DITL orchestration hooks

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
- `pause()`
- `resume()`
- `isPaused()`
- `canPause()`
- `init()`
- `execute(...)`
- `executeLocal(...)`
- `executeP2PRequest(...)`

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

### Privacy behavior

Requester trace and privacy policy is enforced, not advisory.

The grid must support:

- refusing nodes whose tracing behavior conflicts with the task policy
- mandatory redaction even when tracing is allowed
- no-tracing requests
- storage restrictions for trace and task persistence

## P2P, PCP, Auth, And Security Standards

### P2P alignment

The grid must use normal TPipe P2P models and transport handling.

Do not invent a separate transport framework for the grid.

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

## Test Expectations

The first serious implementation wave must include coverage for:

- missing router or worker rejection
- local ancestry and cycle rejection
- local execute and P2P execute sharing one runtime path
- policy-driven return to sender, origin, and explicit transport
- return-after-first-local-work
- hybrid discovery behavior
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
