# Junction Workflow Extension Requirements

Date: 2026-03-19

## Purpose

This file records the requirements for the workflow extension to Junction. The discussion harness was the original stub intent, but Junction now also needs to support built-in plan/vote/act/verify/adjust/output recipes so common workflow harnesses are faster to assemble than hand-wiring a custom orchestrator.

## Required Behavior

- Junction must keep `DISCUSSION_ONLY` as the safe default recipe.
- Junction must support the original orchestration patterns from the stub comments:
  - `VOTE_ACT_VERIFY_REPEAT`
  - `ACT_VOTE_VERIFY_REPEAT`
  - `VOTE_PLAN_ACT_VERIFY_REPEAT`
  - `PLAN_VOTE_ACT_VERIFY_REPEAT`
  - `VOTE_PLAN_OUTPUT_EXIT`
  - `PLAN_VOTE_ADJUST_OUTPUT_EXIT`
- Junction must accept any `P2PInterface` in planner, actor, verifier, adjuster, output, and moderator roles.
- Junction must preserve nested containers such as `Manifold` as first-class participants instead of flattening them into plain pipelines.
- Junction must support both in-process action execution and handoff-only output recipes.
- Junction must serialize a structured `JunctionWorkflowOutcome` for workflow runs and keep the phase history in metadata.
- Junction must apply the same nested graph cycle detection rules to workflow bindings that it applies to discussion participants.
- Junction must not silently auto-repair participant graph cycles.
- Junction must not silently flatten nested containers.

## Workflow Semantics

- Discussion-only runs stop at `DiscussionDecision`.
- Workflow runs may execute phase bindings for plan, vote, act, verify, adjust, and output.
- `VOTE` still uses the discussion participant set and the configured discussion strategy.
- `PLAN`, `ACT`, `VERIFY`, `ADJUST`, and `OUTPUT` are phase roles that may be satisfied by P2P-capable containers.
- Handoff-only recipes should emit instructions rather than trying to force a downstream execution stage.
- The workflow output must remain deterministic and traceable even when a binding is missing, by falling back to a safe synthesized phase artifact.

## Non-Goals

- Junction is not a generic arbitrary workflow engine.
- Junction is not a replacement for custom downstream business logic.
- Junction should not introduce new side-effect behavior beyond the explicitly configured phase participants.

## Completion Criteria

The workflow extension is considered complete when:

- all supported recipes run through the Junction harness
- nested container participants remain intact
- cycle detection fails fast
- discussion-only mode still behaves as before
- workflow output and trace reporting remain structured and serializable

