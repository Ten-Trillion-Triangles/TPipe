# DistributionGrid Plan

Date: 2026-03-25
Last Updated: 2026-03-25

## Purpose

This file tracks the single current task for the `DistributionGrid` workstream. It is the short-horizon handoff record that should be updated first when active work changes.

Use this file for the current task only. Move durable implementation truth into [`md/distributiongrid-progress.md`](./distributiongrid-progress.md) and durable intent into [`md/distributiongrid-design.md`](./distributiongrid-design.md).

## Current Task

- Task: Phase 4 - implement the first local execution core on top of the validated Phase 3 shell.
- Status: ready
- Exact progress: Phase 0, Phase 1, Phase 2, and Phase 3 are complete; Phase 4 has not started
- Last updated: 2026-03-25
- Files in scope:
  - `src/main/kotlin/Pipeline/DistributionGrid.kt`
  - `src/test/kotlin/Pipeline/DistributionGridExecutionCoreTest.kt`
- Last completed step: landed `init()` validation, ancestry and cycle safety, lifecycle controls, child-pipeline exposure, typed grid metadata support, and the first `DISTRIBUTION_GRID_*` trace vocabulary
- Current blocker: none; Phase 4 may add only the local execution path and must still avoid remote handoff and registry discovery
- Next atomic step: normalize direct and inbound P2P execution into one local envelope-driven runtime path and add the first router-to-worker flow
- Verification target: local-only execution works end to end, direct and inbound execution share one runtime path, and `DistributionGrid` still avoids remote peer dispatch

## Milestones

- [ ] Add a local `execute(...)` entrypoint for the grid shell and normalize it with `executeLocal(...)`.
- [ ] Add local-only envelope normalization and the first router-to-worker orchestration path.
- [ ] Add `executeP2PRequest(...)` normalization so inbound P2P requests reuse the same local runtime path.
- [ ] Add local hop recording, local completion or failure mapping, and the first execution-time grid trace emissions.
- [ ] Verify local execution works without adding remote peer dispatch, registry lookup, or handshake enforcement.

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

## Sync Rules

- Update this file first whenever the active task changes.
- Update the progress file whenever implementation truth, verification evidence, or completed scope changes.
- Update the design file before coding if the intended runtime behavior or core guardrails change.
- Keep this file compact. Do not turn it into a long session diary.
