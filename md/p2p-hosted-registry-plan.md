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

- Task: land the first hosted public P2P registry service, client, PCP tools, and `DistributionGrid` bootstrap/publish integration.
- Status: in progress
- Exact progress: the service contracts, in-memory store, P2P transport RPC service, human client, PCP tools, and first `DistributionGrid` bootstrap/publish hooks are implemented and under focused verification.
- Last updated: 2026-03-28
- Files in scope:
  - `src/main/kotlin/P2P/P2PHostedRegistryModels.kt`
  - `src/main/kotlin/P2P/P2PHostedRegistry.kt`
  - `src/main/kotlin/P2P/P2PHostedRegistryTools.kt`
  - `src/main/kotlin/P2P/P2PRegistry.kt`
  - `src/main/kotlin/Pipeline/DistributionGridProtocolModels.kt`
  - `src/main/kotlin/Pipeline/DistributionGrid.kt`
  - `src/main/kotlin/Pipeline/DistributionGridDsl.kt`
  - `src/test/kotlin/P2PHostedRegistryTest.kt`
  - `src/test/kotlin/P2PHostedRegistryToolsTest.kt`
  - `src/test/kotlin/Pipeline/DistributionGridHostedRegistryIntegrationTest.kt`
- Last completed step: landed the hosted-registry service/client path and fixed the focused integration harness so the new tests exercise the real runtime path.
- Current blocker: broader verification and public-doc sync still need to be finished.
- Next atomic step: run the hosted-registry and wider grid suites serially, then sync public docs with the shipped bootstrap-catalog and public-listing surface.
- Verification target: hosted-registry unit coverage, grid hosted-registry integration coverage, and existing `DistributionGrid*` suites all pass together.

## Upcoming Queue

- `Hosted registry rollout completion`
  Scope: broader verification, public docs, and any targeted fixes needed to keep the hosted-registry rollout aligned with the existing P2P and DistributionGrid trust model.
  Must not touch: the previously shipped DistributionGrid Phase 5 through Phase 8 runtime semantics except where hosted-registry bootstrap/publish integration requires additive hooks.
  Verification target: hosted registry remains additive, lease-based, sanitized, and trust-gated.
