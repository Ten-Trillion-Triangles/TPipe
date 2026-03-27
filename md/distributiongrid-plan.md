# DistributionGrid Plan

Date: 2026-03-25
Last Updated: 2026-03-27

## Purpose

This file tracks the single current task for the `DistributionGrid` workstream. It is the short-horizon handoff record that should be updated first when active work changes.

Use this file for the current task only. Move durable implementation truth into [`md/distributiongrid-progress.md`](./distributiongrid-progress.md) and durable intent into [`md/distributiongrid-design.md`](./distributiongrid-design.md).

## Current Task

- Task: Phase 7 follow-up repair pass.
- Status: complete
- Exact progress: Phase 7 follow-up repairs are complete; the remaining review items were credential-identity enforcement and resumable after-peer-response checkpoints, and both are now fixed and verified.
- Last updated: 2026-03-27
- Files in scope:
  - `src/main/kotlin/Pipeline/DistributionGrid.kt`
  - `src/test/kotlin/Pipeline/DistributionGridHardeningTest.kt`
  - `md/distributiongrid-progress.md`
  - `docs/containers/distributiongrid.md`
- Last completed step: fixed the credential-auth gate and resumable after-peer-response pause checkpoint, then reran the focused and broader `DistributionGrid` test sweeps successfully.
- Current blocker: none
- Next atomic step: move into Phase 8 planning and UI/doc finalization work.
- Verification target: Phase 7 hardening behavior remains green after the repair pass and the codebase is ready for Phase 8 prep.

## Milestones

- [x] Add bootstrap trust-anchor inputs for registry discovery.
- [x] Add registry and node advertisement verification for discovered candidates.
- [x] Add structured registry query execution and candidate filtering.
- [x] Add lease-based membership and renewal bookkeeping.
- [x] Verify discovered peers still require explicit grid metadata plus handshake or valid session state before routing.

## Upcoming Queue

- `Phase 8: DSL, Defaults, Public Docs, And Final Coverage`
  Scope: finish the user-facing DSL, default-policy ergonomics, public docs, and final coverage cleanup now that the runtime phases are complete.
  Must not touch: the shipped Phase 5 through Phase 7 runtime semantics except for tightly scoped follow-up fixes.
  Verification target: public APIs and docs expose the completed runtime coherently without regressing the verified handoff, discovery, and hardening paths.

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
- 2026-03-27: Completed Phase 7 by landing outbound memory shaping, best-effort durability checkpoints and `resumeTask(taskId)`, PCP or credential or privacy policy enforcement on remote handoff, retry and alternate-peer runtime handling, and grid trace-export/failure-analysis wrappers.
- 2026-03-27: Started a Phase 7 repair pass to fix terminal pause cleanup, retry-hop accounting, and configured summary-budget enforcement regressions identified in review.
- 2026-03-27: Completed the Phase 7 follow-up repair pass by fixing the credential-identity gate, preserving auth lookup parity with the P2P path, and making after-peer-response pause checkpoints resumable instead of terminal.

## Sync Rules

- Update this file first whenever the active task changes.
- Update the progress file whenever implementation truth, verification evidence, or completed scope changes.
- Update the design file before coding if the intended runtime behavior or core guardrails change.
- Keep this file compact. Do not turn it into a long session diary.
