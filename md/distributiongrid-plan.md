# DistributionGrid Plan

Date: 2026-03-25
Last Updated: 2026-03-25

## Purpose

This file tracks the single current task for the `DistributionGrid` workstream. It is the short-horizon handoff record that should be updated first when active work changes.

Use this file for the current task only. Move durable implementation truth into [`md/distributiongrid-progress.md`](./distributiongrid-progress.md) and durable intent into [`md/distributiongrid-design.md`](./distributiongrid-design.md).

## Current Task

- Task: Phase 5 - implement explicit remote peer handoff on top of the shipped local execution core.
- Status: ready
- Exact progress: Phase 0, Phase 1, Phase 2, Phase 3, and Phase 4 are complete; Phase 5 has not started
- Last updated: 2026-03-25
- Files in scope:
  - `src/main/kotlin/Pipeline/DistributionGrid.kt`
  - `src/test/kotlin/Pipeline/DistributionGridExecutionCoreTest.kt`
  - `src/test/kotlin/Pipeline/DistributionGridRemoteHandoffTest.kt`
- Last completed step: landed the first local-only execution runtime, grid-level DITL hook registration, local hop or outcome or failure mapping, and Phase 4 execution tracing
- Current blocker: none; Phase 5 may add only explicit-peer remote handoff and must still avoid registry discovery or lease-based membership
- Next atomic step: add the explicit-peer handshake and session path, then route one remote handoff through configured peer descriptors only
- Verification target: explicit peer handoff requires valid grid metadata plus handshake or valid session state, and remote returns or failures map back through the normal `P2PResponse` boundary

## Milestones

- [ ] Add explicit remote peer dispatch for configured peer bindings or descriptors only.
- [ ] Add mandatory handshake and negotiated policy validation before first remote handoff.
- [ ] Add session creation and valid session reuse for repeated explicit-peer calls.
- [ ] Map remote returns and remote failures back into the local envelope and terminal content surface.
- [ ] Verify explicit remote handoff works without adding registry discovery, lease renewal, or trust-anchor expansion.

## Upcoming Queue

- `Phase 5: Explicit Remote Peer Handoff`
  Scope: add remote handoff to explicitly configured peers with mandatory handshake, negotiated policy, session creation, and mapped remote returns or failures.
  Must not touch: registry discovery, lease renewal, trust-anchor expansion beyond explicit peers.
  Verification target: explicit peer handoff requires valid grid metadata plus handshake or valid session state.

- `Phase 6: Registry Discovery And Membership`
  Scope: add bootstrap-trust discovery, lease-based registration, structured registry queries, and candidate advertisement verification.
  Must not touch: broader runtime hardening, DSL ergonomics, or public-doc completion claims beyond shipped discovery behavior.
  Verification target: trusted registries can be queried safely and discovered candidates are verified before routing.

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

## Sync Rules

- Update this file first whenever the active task changes.
- Update the progress file whenever implementation truth, verification evidence, or completed scope changes.
- Update the design file before coding if the intended runtime behavior or core guardrails change.
- Keep this file compact. Do not turn it into a long session diary.
