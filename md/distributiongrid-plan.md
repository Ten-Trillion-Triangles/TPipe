# DistributionGrid Plan

Date: 2026-03-25
Last Updated: 2026-03-26

## Purpose

This file tracks the single current task for the `DistributionGrid` workstream. It is the short-horizon handoff record that should be updated first when active work changes.

Use this file for the current task only. Move durable implementation truth into [`md/distributiongrid-progress.md`](./distributiongrid-progress.md) and durable intent into [`md/distributiongrid-design.md`](./distributiongrid-design.md).

## Current Task

- Task: Phase 6 stabilization repair - registry cache revalidation and safe registration fallback.
- Status: in progress
- Exact progress: Phase 6 is complete, but a short repair pass is in flight to keep discovered-node cache entries registry-scoped and replace stale live leases when a node re-registers
- Last updated: 2026-03-26
- Files in scope:
  - `src/main/kotlin/Pipeline/DistributionGrid.kt`
  - `src/test/kotlin/Pipeline/DistributionGridRegistryDiscoveryTest.kt`
- Last completed step: completed Phase 6 by adding bootstrap-registry probing, trust-verifier admission, lease-based membership registration and renewal, structured registry queries, and discovered-node routing back into the existing handoff path
- Current blocker: none
- Next atomic step: finish the registry-scoped discovery cache and lease-replacement repair, then resume Phase 7 planning
- Verification target: the repair preserves the shipped Phase 6 discovery model while preventing registry aliasing and stale live lease state

## Milestones

- [x] Add bootstrap trust-anchor inputs for registry discovery.
- [x] Add registry and node advertisement verification for discovered candidates.
- [x] Add structured registry query execution and candidate filtering.
- [x] Add lease-based membership and renewal bookkeeping.
- [x] Verify discovered peers still require explicit grid metadata plus handshake or valid session state before routing.

## Upcoming Queue

- `Phase 7: Cross-Cutting Runtime Hardening`
  Scope: add outbound memory shaping, durability behavior, privacy or auth or PCP mediation, trace export alignment, and safe pause checkpoints.
  Must not touch: DSL ergonomics or final public-doc completion claims beyond shipped hardening behavior.
  Verification target: the remote runtime matches the approved TPipe memory, privacy, auth, durability, and tracing policies.

## Recent Task History

- 2026-03-25: Created the initial `DistributionGrid` steering-doc scaffold and linked it from the compaction starting-point note.
- 2026-03-25: Replaced the placeholder steering content with the approved full architecture spec for the future runtime.
- 2026-03-25: Extended the approved architecture to define grid-specific node identity, registry discovery, handshake, and session behavior.
- 2026-03-25: Rolled back the accidental runtime prototype and reset the repo to the stub-only baseline while keeping the approved design steering in place.
- 2026-03-25: Codified the phase-by-phase implementation order so runtime work can proceed in distinct, guarded slices.
- 2026-03-25: Completed Phase 1 by landing the contract model files and focused contract-model tests from the clean stub baseline.
- 2026-03-25: Completed Phase 2 by replacing the stub shell with the non-executing configuration shell and focused registration tests.
- 2026-03-25: Completed Phase 3 by adding validation, lifecycle controls, child-pipeline exposure, grid descriptor metadata, and focused validation or lifecycle tests.
- 2026-03-25: Completed Phase 4 by landing the first local execution core, shared direct or local or inbound P2P runtime normalization, local DITL hooks, hop or outcome or failure mapping, and focused execution-core tests.
- 2026-03-25: Completed Phase 5 by landing explicit-peer remote handoff, grid RPC over normal P2P requests, mandatory handshake, in-memory session reuse, and focused remote-handoff tests.
- 2026-03-26: Stabilized Phase 5 by fixing stale-session recovery after `SESSION_REJECT`, making negotiated session policy authoritative on remote execution, framing grid RPC prompts explicitly, and aligning remote-success `hopCount` with recorded hops.
- 2026-03-26: Hardened Phase 5 policy integrity by revalidating cached sessions against current task policy and rejecting handshake acknowledgements that widen the requested trace, routing, or credential policy.
- 2026-03-26: Completed Phase 6 by adding bootstrap registry probing, pluggable trust verification, lease-based registration and renewal, structured registry queries, and discovered-node routing through the existing handoff runtime.

## Sync Rules

- Update this file first whenever the active task changes.
- Update the progress file whenever implementation truth, verification evidence, or completed scope changes.
- Update the design file before coding if the intended runtime behavior or core guardrails change.
- Keep this file compact. Do not turn it into a long session diary.
