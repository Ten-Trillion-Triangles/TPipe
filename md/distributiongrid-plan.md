# DistributionGrid Plan

Date: 2026-03-25
Last Updated: 2026-03-25

## Purpose

This file tracks the single current task for the `DistributionGrid` workstream. It is the short-horizon handoff record that should be updated first when active work changes.

Use this file for the current task only. Move durable implementation truth into [`md/distributiongrid-progress.md`](./distributiongrid-progress.md) and durable intent into [`md/distributiongrid-design.md`](./distributiongrid-design.md).

## Current Task

- Task: Implement the first `DistributionGrid` runtime slice around the approved envelope-first node architecture and grid protocol layer.
- Status: ready
- Exact progress: 0 of 8 implementation milestones completed
- Last updated: 2026-03-25
- Files in scope:
  - `src/main/kotlin/Pipeline/DistributionGrid.kt`
  - `src/main/kotlin/Pipeline/DistributionGridModels.kt`
  - `src/main/kotlin/Pipeline/DistributionGridMemoryModels.kt`
  - `src/main/kotlin/Pipeline/DistributionGridDurabilityModels.kt`
  - `src/main/kotlin/Pipeline/DistributionGridProtocolModels.kt`
  - `src/main/kotlin/Pipeline/DistributionGridDsl.kt`
  - `src/main/kotlin/Debug/TraceEventType.kt`
  - `src/test/kotlin/Pipeline/DistributionGridTest.kt`
- Last completed step: approved and recorded the grid registry, node identity, handshake, and session protocol requirements in the steering docs
- Current blocker: none at the design level; the runtime does not yet have the protocol models, descriptor metadata extension, or session scaffolding
- Next atomic step: add the new protocol model layer for node metadata, advertisements, registration leases, handshake payloads, and session references alongside the envelope and policy models
- Verification target: the runtime exposes the grid identity, discovery, handshake, and session models cleanly enough for registry and node validation code to build against

## Milestones

- [ ] Add the new public model layer for envelope, directive, routing, failure, tracing policy, credential policy, and outcome types.
- [ ] Add memory and durability model files plus the pluggable durable-store contract.
- [ ] Add protocol model files for grid metadata, advertisements, registration, handshake, and session records.
- [ ] Extend `TraceEventType` with `DISTRIBUTION_GRID_*` events.
- [ ] Define how grid metadata is attached to descriptor and request flows without replacing normal P2P transport handling.
- [ ] Replace the stub-era `DistributionGrid` API with router, worker, peer, policy, and trace configuration methods.
- [ ] Add init validation, local ancestry guards, and remote hop-loop guard scaffolding.
- [ ] Add registry and handshake validation scaffolding with session-cache interfaces.
- [ ] Add focused tests for the model layer, init validation, protocol defaults, and registry or handshake policy behavior.

## Recent Task History

- 2026-03-25: Created the initial `DistributionGrid` steering-doc scaffold and linked it from the compaction starting-point note.
- 2026-03-25: Replaced the placeholder steering content with the approved full architecture spec for the future runtime.
- 2026-03-25: Extended the approved architecture to define grid-specific node identity, registry discovery, handshake, and session behavior.

## Sync Rules

- Update this file first whenever the active task changes.
- Update the progress file whenever implementation truth, verification evidence, or completed scope changes.
- Update the design file before coding if the intended runtime behavior or core guardrails change.
- Keep this file compact. Do not turn it into a long session diary.
