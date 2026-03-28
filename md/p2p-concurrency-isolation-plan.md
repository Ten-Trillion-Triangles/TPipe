# P2P Concurrency Isolation Plan

Date: 2026-03-27
Last Updated: 2026-03-27

## Purpose

This file tracks the single current task for the P2P concurrency isolation workstream. It is the short-horizon handoff record that should be updated first when active work changes.

Use this file for the current task only. Move durable implementation truth into [`md/p2p-concurrency-isolation-progress.md`](./p2p-concurrency-isolation-progress.md) and durable intent into [`md/p2p-concurrency-isolation-design.md`](./p2p-concurrency-isolation-design.md).

## Current Task

- Task: Phase 3 — Registry Concurrency Modes (Task 8: Add P2PConcurrencyMode enum)
- Status: not started
- Exact progress: Phase 1 and Phase 2 are complete. The clone function exists and all container classes are annotated. Next step is adding the concurrency mode enum and extending P2PAgentListing.
- Last updated: 2026-03-27
- Files in scope:
  - `src/main/kotlin/P2P/P2PRegistry.kt`
- Last completed step: Annotated all runtime state fields across Pipeline, Manifold, Junction, DistributionGrid, Connector, Splitter. Clean compile and all existing tests pass.
- Current blocker: none
- Next atomic step: Add `P2PConcurrencyMode` enum and `factory` field to `P2PAgentListing`. Then add concurrency mode parameter to explicit `register()` and factory `register()` overload. Then implement clone-per-request in `executeP2pRequest`.
- Verification target: ISOLATED mode clones per request, factory mode calls factory per request, SHARED mode unchanged.

## Milestones

- [x] Create `@RuntimeState` annotation
- [x] Implement generalized `cloneInstance()` reflection clone function
- [x] Annotate Pipeline runtime state fields + test
- [x] Annotate Manifold runtime state fields + test
- [x] Annotate Junction runtime state fields + test
- [x] Annotate DistributionGrid runtime state fields + test
- [x] Annotate Connector, MultiConnector, Splitter runtime state fields + test
- [ ] Add `P2PConcurrencyMode` enum and update `P2PAgentListing`
- [ ] Add concurrency mode to `register()` + factory overload
- [ ] Implement clone-per-request and factory-per-request in `executeP2pRequest`
- [ ] Integration tests — concurrent execution
- [ ] Update DSLs and public docs
- [ ] Build and run full test suite

## Upcoming Queue

- `Task 3: Annotate Pipeline runtime state fields + test`
  Fields: `pipelineId`, `currentPipeIndex`, `isPaused`, `resumeSignal`, `pipelineTokenUsage`, `internalConverseHistory`
  Test: clone-then-compare and clone-then-mutate for a configured Pipeline

- `Task 4: Annotate Manifold runtime state fields + test`
  Fields: `manifoldId`, `workingContentObject`, `currentTaskProgress`, `loopIterationCount`, `agentInteractionMap`, `isPaused`, `resumeSignal`

- `Task 5: Annotate Junction runtime state fields + test`
  Fields: `junctionId`, `discussionState`, `workflowState`, `isPaused`, `resumeSignal`

- `Task 6: Annotate DistributionGrid runtime state fields + test`
  Fields: `gridId`, `initialized`, `pauseRequested`, `sessionRecordsByPeerKey`, `sessionRecordsById`, `discoveredRegistriesById`, `discoveredNodeAdvertisementsById`, `activeRegistryLeasesById`, `localRegisteredNodeAdvertisementsById`, `localRegistrationLeasesById`, `localRegistrationLeaseIdsByNodeId`, `synthesizedPeerOrdinal`

- `Task 7: Annotate Connector, MultiConnector, Splitter runtime state fields + test`
  Connector: `lastConnection`, `pipelineId`
  Splitter: `splitterId`, `contents`, `executionMutex`, `isExecuting`, `completedPipelines`, `totalPipelines`, `splitterCompleted`

## Recent Task History

- 2026-03-27: Created the P2P concurrency isolation steering doc (`md/p2p-concurrency-isolation-design.md`).
- 2026-03-27: Created `@RuntimeState` annotation in `src/main/kotlin/Util/RuntimeState.kt`.
- 2026-03-27: Completed Phase 2 — annotated all runtime state fields across Pipeline (6), Manifold (7), Junction (5), DistributionGrid (12), Connector (2), Splitter (7). Clean compile and all existing tests pass.

## Sync Rules

- Update this file first whenever the active task changes.
- Update the progress file whenever implementation truth, verification evidence, or completed scope changes.
- Update the design file before coding if the intended runtime behavior or core guardrails change.
- Keep this file compact. Do not turn it into a long session diary.
