# Hosted P2P Registry Plan

Date: 2026-03-28
Last Updated: 2026-03-29

## Purpose

This file tracks the active implementation task for remote hosted P2P registries and their
`DistributionGrid` integration.

Use this file for the current task only. Durable implementation truth belongs in
[`md/p2p-hosted-registry-progress.md`](./p2p-hosted-registry-progress.md), and durable intent belongs in
[`md/p2p-hosted-registry-design.md`](./p2p-hosted-registry-design.md).

## Current Task

- Task: hosted-registry productionization and completion rollout.
- Status: in progress
- Exact progress: the durable file-backed store, minimal governance and audit surfaces, lightweight plain-P2P trusted import policy/provenance, `DistributionGrid` public-listing update/auto-renew helpers, the structured search plus observability layer, and the first `TPipe-Defaults` ergonomics helpers are now landed. The remaining work is mostly broader examples/public docs and any future catalog ergonomics beyond the current deterministic search/status scope.
- Last updated: 2026-03-29
- Files in scope:
  - `src/main/kotlin/P2P/P2PHostedRegistryModels.kt`
  - `src/main/kotlin/P2P/P2PHostedRegistry.kt`
  - `src/main/kotlin/P2P/P2PHostedRegistryTools.kt`
  - `src/main/kotlin/P2P/P2PRegistry.kt`
  - `src/main/kotlin/Routing.kt`
  - `src/main/kotlin/Pipeline/DistributionGrid.kt`
  - `src/main/kotlin/Pipeline/DistributionGridDsl.kt`
  - future durable-store and governance files under `src/main/kotlin/P2P`
  - hosted-registry and grid integration tests under `src/test/kotlin`
- Last completed step: landed the first `TPipe-Defaults` hosted-registry helper surface, added focused defaults coverage, synced the defaults/public docs, and reran focused defaults plus hosted-registry verification.
- Current blocker: none
- Next atomic step: finish the remaining completion work around defaults/helpers and broader public documentation polish without changing the existing hosted-registry or `DistributionGrid` trust model.
- Verification target: hosted-registry unit coverage, trusted-source lifecycle coverage, HTTP route coverage, durable-store/governance coverage, and existing `DistributionGrid*` suites all pass together.

## Upcoming Queue

- `Phase A: Durable hosted-registry persistence`
  Scope: file-backed store, startup reload, expiry sweep, index rebuild, durable mutation path, focused durable-store tests.
  Must not touch: the existing hosted-registry wire contract, `P2PRegistry` trusted-source semantics, or `DistributionGrid` runtime trust behavior.
  Verification target: in-memory and durable stores both satisfy the same hosted-registry service contract.

- `Phase B: Governance and search hardening`
  Scope: operator controls, audit trail, moderation state handling, registry info/status expansion, stable pagination and richer sorting/filtering.
  Must not touch: public listing sanitization rules or the additive nature of the hosted-registry subsystem.
  Verification target: policy rejections, owner/operator mutations, and structured search all behave deterministically.

- `Phase C: Plain P2P trusted-import completion`
  Scope: optional trusted-import policy object, provenance inspection, stronger freshness rules, collision/provenance observability.
  Must not touch: `DistributionGridTrustVerifier` or grid-specific trust semantics.
  Verification target: plain `P2PRegistry` imports remain lighter than grid trust, but no longer feel under-specified.

- `Phase D: DistributionGrid hosted-listing completion`
  Scope: full listing lifecycle coverage for publish, renew, update, remove, republish, and bootstrap observability.
  Must not touch: the shipped Phase 5 through Phase 8 `DistributionGrid` routing, handshake, durability, or retry semantics except for additive hosted-registry hooks.
  Verification target: hosted catalogs improve discovery/bootstrap only; trust verifier and handshake/session checks still govern real routing.

- `Phase E: Defaults and docs polish`
  Scope: optional `TPipe-Defaults` helpers, plain P2P and grid examples, broader public-doc polish.
  Must not touch: core provider-agnostic runtime behavior.
  Verification target: ergonomic additions stay thin and do not create shadow configuration paths.
