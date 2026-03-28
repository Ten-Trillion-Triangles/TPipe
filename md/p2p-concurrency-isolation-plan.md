# P2P Concurrency Isolation Plan

Date: 2026-03-27
Last Updated: 2026-03-27

## Purpose

This file tracks the single current task for the P2P concurrency isolation workstream. It is the short-horizon handoff record that should be updated first when active work changes.

Use this file for the current task only. Move durable implementation truth into [`md/p2p-concurrency-isolation-progress.md`](./p2p-concurrency-isolation-progress.md) and durable intent into [`md/p2p-concurrency-isolation-design.md`](./p2p-concurrency-isolation-design.md).

## Current Task

- Task: All phases complete.
- Status: complete
- Exact progress: All 5 phases implemented and verified. The P2P concurrency isolation system is fully operational.
- Last updated: 2026-03-27
- Files in scope:
  - `src/test/kotlin/P2P/P2PConcurrencyModeTest.kt` (extend or new file)
- Last completed step: Implemented clone-per-request and factory-per-request in executeP2pRequest. All 4 concurrency mode tests pass. Full affected suite green.
- Current blocker: none
- Next atomic step: Write concurrent execution tests — send N parallel requests to an ISOLATED agent and verify no cross-request state leakage.
- Verification target: Concurrent P2P requests to ISOLATED containers produce independent results.

## Milestones

- [x] Create `@RuntimeState` annotation
- [x] Implement generalized `cloneInstance()` reflection clone function
- [x] Annotate Pipeline runtime state fields + test
- [x] Annotate Manifold runtime state fields + test
- [x] Annotate Junction runtime state fields + test
- [x] Annotate DistributionGrid runtime state fields + test
- [x] Annotate Connector, MultiConnector, Splitter runtime state fields + test
- [x] Add `P2PConcurrencyMode` enum and update `P2PAgentListing`
- [x] Add concurrency mode to `register()` + factory overload
- [x] Implement clone-per-request and factory-per-request in `executeP2pRequest`
- [ ] Add `P2PConcurrencyMode` enum and update `P2PAgentListing`
- [x] Add concurrency mode to `register()` + factory overload
- [x] Implement clone-per-request and factory-per-request in `executeP2pRequest`
- [x] Integration tests — concurrent execution
- [x] Update DSLs and public docs
- [x] Build and run full test suite

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
- 2026-03-27: Completed Phase 3 — added P2PConcurrencyMode enum (SHARED/ISOLATED), extended P2PAgentListing, added concurrencyMode param to register(), added factory register() overload, implemented clone-per-request and factory-per-request in executeP2pRequest with child agent cleanup. Fixed cloneInstance to set constructor accessible for private classes. All 4 new concurrency mode tests pass. Full affected suite green.

## Sync Rules

- Update this file first whenever the active task changes.
- Update the progress file whenever implementation truth, verification evidence, or completed scope changes.
- Update the design file before coding if the intended runtime behavior or core guardrails change.
- Keep this file compact. Do not turn it into a long session diary.
