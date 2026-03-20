# Junction Workflow Extension Tracker

Date: 2026-03-19

## Overview

This file is the compaction-safe status record for the Junction workflow extension. It exists so the plan for plan/vote/act/verify/adjust/output recipes can be resumed or audited without re-deriving the intent from code comments.

## Current Status

- The workflow extension has been implemented in the working tree.
- The runtime now supports discussion-only execution plus the original workflow recipes from the Junction stub comments.
- The focused Junction regression tests compile and pass.
- The remaining work is documentation synchronization and any future feature expansion.

## Plan At A Glance

- [x] Add workflow recipe models.
- [x] Extend Junction runtime to execute workflow phase bindings.
- [x] Add DSL helpers for workflow setup.
- [x] Add regression tests for workflow recipes.
- [x] Wire workflow trace events into the debug system.
- [x] Update the public Junction docs and compaction notes.

## Completed Work

- Added `JunctionWorkflowRecipe`, `JunctionWorkflowPhase`, `JunctionWorkflowPhaseResult`, `JunctionWorkflowState`, and `JunctionWorkflowOutcome`.
- Extended `Junction` with planner, actor, verifier, adjuster, and output handler bindings.
- Kept discussion-only execution as the default behavior.
- Added `conductWorkflow(...)` for recipe-based harness execution.
- Added workflow-specific trace events and trace visualization support.
- Added focused workflow regression coverage.
- Added a real Manifold-backed workflow nesting regression test so Junction is covered with an actual container ancestry chain, not only a test-double parent.
- Added a companion real Manifold cycle-rejection regression test to prove Junction rejects indirect ancestry loops before execution.

## Decisions Log

- The original stub orchestration patterns are the source of truth for supported recipes.
- Discussion-only remains the default recipe so the original harness behavior stays intact.
- Workflow phase participants are still `P2PInterface` first, so nested containers remain first-class harness elements.
- Handoff recipes should produce structured output instead of silently executing downstream side effects.
- The discussion harness tracker remains the closed record for the original discussion-only rollout.

## Verification Log

- `./gradlew testClasses --no-daemon -Dorg.gradle.jvmargs=-Xmx2g -Dkotlin.daemon.jvm.options=-Xmx2g`
- `./gradlew test --tests com.TTT.Pipeline.JunctionTest -x :TPipe-Bedrock:test -x :TPipe-Defaults:test -x :TPipe-MCP:test -x :TPipe-Ollama:test -x :TPipe-TraceServer:test -x :TPipe-Tuner:test --no-daemon -Dorg.gradle.jvmargs=-Xmx2g -Dkotlin.daemon.jvm.options=-Xmx2g`
- Verified the workflow handoff recipe and the full Junction regression set pass after the recipe-name correction.

## Revision History

- 2026-03-19: Workflow extension tracker created after the harness implementation landed.
- 2026-03-19: Workflow recipes, DSL helpers, trace events, and docs were synchronized with the implementation.
- 2026-03-19: Added a real Manifold-backed nesting regression test for Junction workflow coverage.
- 2026-03-19: Added a real Manifold cycle-rejection regression test for nested harness safety.
