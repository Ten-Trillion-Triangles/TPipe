# Junction Harness Todo List

Date: 2026-03-19

## Completed Work Items

- [x] Harden the orchestration strategy layer so `SIMULTANEOUS`, `CONVERSATIONAL`, and `ROUND_ROBIN` each have clearly distinct runtime semantics.
- [x] Implement true nested-container cycle detection for Junction registration and execution.
- [x] Keep max nested depth as a secondary safety guard after graph validation.
- [x] Add convenience helpers for the most common Junction harness workflows.
- [x] Add focused tests for each orchestration mode.
- [x] Add focused tests for direct and indirect cycle rejection.
- [x] Add nested-container integration coverage for `Manifold`-style participants.
- [x] Keep the requirements doc, tracker, and public Junction docs synchronized.

## Tracker Sync

- Update `md/junction-harness-implementation-tracker.md` whenever the Junction workstream changes state.
- Keep the tracker focused on current status and evidence.
- Keep this todo list focused on the completed workstream record and any future follow-up work if the harness is reopened.
- Current workstream state: the Junction harness rollout is complete, the requirements are fully recorded, and this file now serves as a closed-out record of the workstream.
