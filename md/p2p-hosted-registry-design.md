# Hosted P2P Registry Design

Date: 2026-03-28
Last Updated: 2026-03-28

## Goal

Make the hosted-registry subsystem production-complete for both plain `P2PRegistry` users and
`DistributionGrid` users.

The end state is:

- TPipe can host a public or private remote registry over the existing P2P transport stack
- human developers and agents can query it with structured search
- clients can safely publish, renew, update, and remove their own listings
- plain `P2PRegistry` users can import trusted hosted `AGENT` listings without needing a grid
- `DistributionGrid` users can bootstrap from hosted `GRID_REGISTRY` catalogs and publish public node or registry listings
- trust, auth, sanitization, and freshness rules remain explicit and fail-closed

This feature remains additive. It does not replace the local `P2PRegistry`, and it does not relax
existing `DistributionGrid` handshake, session, or trust-verification rules.

## Core Design Rules

- Hosted registries use normal `P2PRequest` and `P2PResponse` transport internally.
- HTTP exposure is provided through a dedicated `POST /p2p/registry` adapter route, not a second hosted-registry
  service implementation.
- One hosted registry service may expose three listing kinds:
  - `AGENT`
  - `GRID_NODE`
  - `GRID_REGISTRY`
- All listings are lease-based and freshness-aware.
- Public listings are sanitized before storage, import, or public re-exposure.
- Hosted-registry admission policy and `DistributionGrid` trust verification are separate concerns.
- Hosted listing presence alone never authorizes routing; grid trust, handshake, and session rules still apply.
- Plain `P2PRegistry` trusted imports stay intentionally lighter than grid trust, but they must still be explicit,
  source-tracked, refreshable, and reject unsafe collisions.
- Future ergonomic work must remain additive. No hosted-registry feature should backdoor a new runtime path around
  the already verified P2P or DistributionGrid flows.

## Current Shipped Architecture

### Hosted registry service

- `P2PHostedRegistry` implements `P2PInterface`
- `P2PHostedRegistryStore` abstracts listing persistence
- `InMemoryP2PHostedRegistryStore` is the first shipped store
- `FileBackedP2PHostedRegistryStore` is now the first durable shipped store
- `P2PHostedRegistryPolicy` controls read/write admission, ownership, and sanitization
- `DefaultP2PHostedRegistryPolicy` ships with:
  - public read
  - authenticated write by default
  - optional anonymous publish
  - optional operator refs for moderation/audit access
  - secret stripping enabled

### Contract models

- `P2PHostedRegistryListing`
- `P2PHostedRegistryQuery`
- `P2PHostedRegistryQueryResult`
- `P2PHostedRegistryPublishRequest`
- `P2PHostedRegistryMutationResult`
- `P2PHostedRegistryRpcMessage`

These models unify human search/publish flows and agent PCP tool flows on the same payload contract.

### Human and agent query surfaces

- `P2PHostedRegistryClient` provides coder-facing helper calls for:
  - info
  - search
  - typed search by listing kind
  - get
  - publish
  - update
  - renew
  - remove
  - pull-to-local-registry import
- `P2PHostedRegistryTools` provides PCP-callable agent tools for:
  - `search_p2p_registry_listings`
  - `search_p2p_agent_listings`
  - `get_p2p_registry_listing`
  - `list_trusted_grid_registries`
  - `publish_p2p_registry_listing`
  - `renew_p2p_registry_listing`
  - `remove_p2p_registry_listing`

Write tools are opt-in when enabling the PCP surface.

### Plain P2P trusted-source integration

Hosted registries also integrate with plain `P2PRegistry` through a lighter trusted-source path:

- `P2PTrustedRegistrySource`
  - source id
  - hosted-registry transport
  - structured query
  - optional auth values
  - optional per-source admission filter
  - auto-pull-on-register flag
  - include-in-auto-refresh flag
- source-tracked imports live directly in the normal client catalog
- only `AGENT` listings are imported into plain `P2PRegistry`
- a lightweight admission policy can now enforce verification evidence, freshness, and provenance labeling
- imported entries are tracked by source so refresh/removal can clean up only source-owned records
- collisions are rejected and recorded instead of overwritten

This path is intentionally lighter than `DistributionGrid` trust verification. It is for plain P2P
agent discovery/import, not grid routing trust.

### DistributionGrid integration

Hosted registries integrate with the grid through two additive concepts:

- `DistributionGridBootstrapCatalogSource`
  - remote hosted-registry endpoint
  - stored query template
  - trust-domain allowlist
  - auto-pull-on-init flag
- `DistributionGridPublicListingOptions`
  - title/summary/categories/tags
  - requested lease seconds
  - optional attestation ref

Grid public methods:

- `addBootstrapCatalogSource(...)`
- `removeBootstrapCatalogSource(...)`
- `getBootstrapCatalogSourceIds()`
- `pullTrustedBootstrapCatalogs(...)`
- `publishPublicNodeListing(...)`
- `updatePublicNodeListing(...)`
- `renewPublicNodeListing(...)`
- `removePublicNodeListing(...)`
- `publishPublicRegistryListing(...)`
- `updatePublicRegistryListing(...)`
- `renewPublicRegistryListing(...)`
- `removePublicRegistryListing(...)`
- `startPublicNodeListingAutoRenew(...)`
- `startPublicRegistryListingAutoRenew(...)`
- `stopPublicListingAutoRenew(...)`

### DSL integration

`DistributionGridDsl.discovery { bootstrapCatalogSource(...) }` is the static configuration seam for hosted
bootstrap-catalog sources. Public publish/renew/remove remain explicit runtime actions.

## Completion Scope

Hosted registry is not considered complete until the following six workstreams are finished.

### 1. Durable hosted-registry persistence

The in-memory store is good enough for the first shipped slice, but not for a long-running public registry.
Completion requires at least one durable store backend.

Required behavior:

- durable persistence of listings, leases, ownership, moderation state, and audit metadata
- startup reload and index rebuild
- expiry sweep on startup and during normal operation
- crash-safe mutation path
- no stored secret-bearing auth material

Recommended first backend:

- file-backed JSON store with atomic write strategy and index rebuild on startup

Possible later backend:

- JDBC or DB-backed store when operational needs justify it

### 2. Registry governance and operator controls

Completion requires more than raw publish/remove. Operators need clear governance hooks.

Required behavior:

- moderation state transitions that are explicit and traceable
- owner-or-operator mutation rules
- registry info/status query expansion
- operator override paths for invalid, abusive, or expired content
- audit trail for publish, update, renew, remove, and moderation outcomes
- clear policy rejection surfaces for both human clients and PCP tools

This must remain policy-driven. The shipped default policy may stay conservative, but the extension seam must be stable.

### 3. Search, filtering, and catalog ergonomics

The current structured search exists, but completion means making it practical for real catalogs.

Required behavior:

- stable pagination guarantees
- richer `sortMode`
- exact, prefix, and text search consistency
- category and tag faceting
- stronger capability-based filtering for both agent and grid listings
- helper query builders for common search intents

Not in scope:

- marketplace ranking
- reviews
- featured placements

### 4. Stronger trusted import rules for plain `P2PRegistry`

Plain P2P users need something stronger than a raw callback, but still lighter than grid trust.

Completion target:

- keep `admissionFilter` support
- add an optional small policy object for trusted imports
- support allowlist or denylist checks, freshness thresholds, and provenance labeling
- surface import provenance and trust labels in local registry inspection APIs
- keep collision handling fail-closed and deterministic

This must not become a hidden parallel trust model for grid routing.

### 5. Full listing lifecycle completion for `DistributionGrid`

The grid already publishes and pulls listings, but operational completeness requires:

- broader renew, update, remove, and republish coverage
- better diagnostics and trace output for hosted publish/pull flows
- optional scheduled renewal helpers for long-lived public nodes
- clearer multi-catalog bootstrap observability
- explicit failure modes when a hosted listing becomes stale, rejected, or unverifiable

Grid semantics remain unchanged:

- hosted catalog presence improves discovery/bootstrap only
- trust verifier, handshake, session negotiation, and runtime policy still gate actual execution

### 6. Defaults, DSL, and documentation polish

This is the final ergonomics layer after the operational core is stable.

Completion target:

- `TPipe-Defaults` helpers for hosted-registry client and source configuration where useful
- optional plain `P2PRegistry` config or DSL helpers for trusted sources
- stronger public examples for:
  - public registry host
  - private authenticated registry
  - plain P2P trusted-source import
  - `DistributionGrid` bootstrap catalog use
  - public grid node publication

This must stay additive and must not auto-publish or auto-import by surprise.

## Security Rules

- Strip secret-bearing auth from stored and imported public listings:
  - `P2PRequest.authBody`
  - `P2PTransport.transportAuthBody`
  - request-template auth bodies
- Keep hosted-registry policy distinct from grid trust:
  - hosted registry policy decides who may read or mutate listings
  - `DistributionGridTrustVerifier` still decides what grid advertisements are admissible
- Keep hosted-registry trusted-source admission distinct from full grid trust:
  - `P2PTrustedRegistrySource.admissionFilter` is only a lightweight local import gate
  - any future plain-P2P trusted-import policy must remain lighter than `DistributionGridTrustVerifier`
- `pullTrustedBootstrapCatalogs(...)` only imports `GRID_REGISTRY` advertisements that still pass verifier checks
- Public node and registry listing publication does not bypass later handshake/session validation
- Plain `P2PRegistry` trusted imports reject duplicate agent-name collisions instead of overwriting existing entries
- Durable stores must never persist private transport credentials or request auth bodies
- PCP write tools must remain operator-gated and return explicit rejections when disabled or unauthorized

## Phased Rollout

### Phase A: Durable store and lifecycle hardening

- ship at least one durable hosted-registry store backend
- add expiry sweep and startup reload/index rebuild
- expand mutation and lease tests against the durable backend

### Phase B: Governance and search hardening

- add moderation/audit/operator controls
- strengthen pagination, sorting, and filtering
- add more complete registry info/status surfaces

### Phase C: Trusted import completion

- add optional plain-P2P trusted-import policy layer
- add provenance inspection and stronger collision diagnostics
- keep refresh and source removal semantics stable

### Phase D: DistributionGrid operational completion

- complete renew/update/remove/publication coverage
- add publish and bootstrap observability and scheduled-renew ergonomics

### Phase E: Defaults and public-doc polish

- add optional defaults helpers
- complete example docs and public guidance

## Verification Expectations

Completion requires all of these to stay green together:

- hosted-registry contract, service, client, and PCP tests
- trusted-source lifecycle and collision tests
- HTTP route tests
- `DistributionGridHostedRegistryIntegrationTest`
- broader `DistributionGrid*` suites
- any new durable-store and governance suites

Validation must continue using serial Gradle runs with a real heap so the Kotlin daemon and test caches do not collide.
