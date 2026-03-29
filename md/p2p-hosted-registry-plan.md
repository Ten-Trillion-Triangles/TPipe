# Hosted P2P Registry Plan

Date: 2026-03-28
Last Updated: 2026-03-28

## Purpose

This file tracks the active implementation task for remote hosted P2P registries and their
`DistributionGrid` integration.

Use this file for the current task only. Durable implementation truth belongs in
[`md/p2p-hosted-registry-progress.md`](./p2p-hosted-registry-progress.md), and durable intent belongs in
[`md/p2p-hosted-registry-design.md`](./p2p-hosted-registry-design.md).

## Current Task

- Task: extend the hosted-registry slice with plain `P2PRegistry` trusted hosted sources, source-tracked imports, optional refresh, and a dedicated HTTP route.
- Status: complete
- Exact progress: trusted hosted-registry sources now exist on `P2PRegistry`, imports are source-tracked with collision diagnostics, the optional auto-refresh ticker is implemented, and hosted registries are reachable through `/p2p/registry`.
- Last updated: 2026-03-28
- Files in scope:
  - `src/main/kotlin/P2P/P2PHostedRegistryModels.kt`
  - `src/main/kotlin/P2P/P2PHostedRegistry.kt`
  - `src/main/kotlin/P2P/P2PHostedRegistryTools.kt`
  - `src/main/kotlin/P2P/P2PRegistry.kt`
  - `src/main/kotlin/Routing.kt`
  - `src/test/kotlin/P2PHostedRegistryTest.kt`
  - `src/test/kotlin/P2PHostedRegistryHttpRouteTest.kt`
  - `src/test/kotlin/P2PHostedRegistryToolsTest.kt`
  - `src/test/kotlin/P2PRegistryTrustedRegistrySourceTest.kt`
  - `src/test/kotlin/Pipeline/DistributionGridHostedRegistryIntegrationTest.kt`
- Last completed step: landed trusted-source lifecycle management on `P2PRegistry`, added the dedicated hosted-registry HTTP route, and reran focused hosted-registry plus broader `DistributionGrid*` verification.
- Current blocker: none
- Next atomic step: keep the hosted-registry and trusted-source behavior stable and only extend it additively from here.
- Verification target: hosted-registry unit coverage, trusted-source lifecycle coverage, HTTP route coverage, and existing `DistributionGrid*` suites all pass together.

## Upcoming Queue

- `Hosted registry rollout completion`
  Scope: broader verification, public docs, and any targeted fixes needed to keep the hosted-registry rollout aligned with the existing P2P and DistributionGrid trust model.
  Must not touch: the previously shipped DistributionGrid Phase 5 through Phase 8 runtime semantics except where hosted-registry bootstrap/publish integration requires additive hooks.
  Verification target: hosted registry remains additive, lease-based, sanitized, and trust-gated.
