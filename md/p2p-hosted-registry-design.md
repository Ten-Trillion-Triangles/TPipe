# Hosted P2P Registry Design

Date: 2026-03-28
Last Updated: 2026-03-28

## Goal

Add a remotely hosted registry layer that lets TPipe systems publish, search, renew, and remove public
P2P listings over the existing P2P transport model, while allowing `DistributionGrid` nodes to pull
trusted grid-registry advertisements and publish their own public listings.

This feature is additive. It does not replace the local `P2PRegistry`, and it does not relax any
existing `DistributionGrid` handshake, session, or trust-verification rules.

## Core Design Rules

- Hosted registries use normal `P2PRequest` and `P2PResponse` transport internally.
- HTTP exposure is provided through a dedicated `POST /p2p/registry` adapter route, not a second hosted-registry
  service implementation.
- One hosted registry service may expose three listing kinds:
  - `AGENT`
  - `GRID_NODE`
  - `GRID_REGISTRY`
- All listings are lease-based.
- Public listings are sanitized before storage or import.
- Hosted registry admission policy and `DistributionGrid` trust verification are separate concerns.
- Hosted listing presence alone never authorizes routing; grid trust, handshake, and session rules still apply.

## Shipped Architecture

### Hosted registry service

- `P2PHostedRegistry` implements `P2PInterface`
- `P2PHostedRegistryStore` abstracts listing persistence
- `InMemoryP2PHostedRegistryStore` is the first shipped store
- `P2PHostedRegistryPolicy` controls read/write admission, ownership, and sanitization
- `DefaultP2PHostedRegistryPolicy` ships with:
  - public read
  - authenticated write by default
  - optional anonymous publish
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
- `renewPublicNodeListing(...)`
- `removePublicNodeListing(...)`
- `publishPublicRegistryListing(...)`
- `renewPublicRegistryListing(...)`
- `removePublicRegistryListing(...)`

### DSL integration

`DistributionGridDsl.discovery { bootstrapCatalogSource(...) }` is the static configuration seam for hosted
bootstrap-catalog sources. Public publish/renew/remove remain explicit runtime actions.

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
  - it must not be treated as equivalent to `DistributionGridTrustVerifier`
- `pullTrustedBootstrapCatalogs(...)` only imports `GRID_REGISTRY` advertisements that still pass verifier checks
- Public node and registry listing publication does not bypass later handshake/session validation
- Plain `P2PRegistry` trusted imports reject duplicate agent-name collisions instead of overwriting existing entries

## Deferred Work

The first hosted-registry slice intentionally leaves these out:

- marketplace ranking and review systems
- auto-publish side effects on grid init
- durable hosted-registry backing stores beyond the in-memory store
- automatic defaults-module integration for hosted-registry publishing
