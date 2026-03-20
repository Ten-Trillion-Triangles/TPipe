# Junction Workflow Extension Todo List

Date: 2026-03-19

## Completed Work Items

- [x] Add workflow recipe models for the original Junction orchestration patterns.
- [x] Extend Junction so it can run plan, vote, act, verify, adjust, and output phases.
- [x] Support any `P2PInterface` as a phase participant or moderator.
- [x] Preserve nested container participants such as `Manifold`.
- [x] Add DSL helpers for workflow setup.
- [x] Add regression tests for the supported workflow recipes.
- [x] Add a real Manifold-backed nesting regression test for Junction workflow coverage.
- [x] Add a companion real Manifold cycle-rejection regression test for workflow safety.
- [x] Wire workflow trace events into the debug system.
- [x] Update the compaction-safe context note and public Junction documentation.

## Follow-Up

- Keep [`md/junction-workflow-extension-tracker.md`](./junction-workflow-extension-tracker.md) current if the workflow harness changes again.
- If a future recipe is added, document it here and in the requirements file before changing runtime behavior.
