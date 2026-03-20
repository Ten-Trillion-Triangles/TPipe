# Junction Harness Implementation Tracker

Date: 2026-03-19

## Overview

This file is the living record for the Junction harness workstream. It is intended to survive compaction and let a future session resume without re-deriving the plan from scratch.

Primary goal:

- Keep the authoritative Junction intent and requirements in [`md/junction-harness-requirements.md`](./junction-harness-requirements.md).
- Keep the active execution list in [`md/junction-harness-todo.md`](./junction-harness-todo.md) so short-term tasks stay separate from the longer-form tracker.
- Keep the implementation aligned with the repository's TTT formatting rules from `.amazonq/rules/TPipe-Formatting.md`.

## Intent And Requirements

Junction is the harness itself. It exists to make collaborative discussion workflows faster and easier than hand-building a custom orchestrator.

The harness must:

- support all three orchestration modes from the original design: `SIMULTANEOUS`, `CONVERSATIONAL`, and `ROUND_ROBIN`
- accept any `P2PInterface` as a moderator or participant
- preserve nested containers such as `Manifold` as first-class participants
- keep nested P2P behavior intact instead of flattening containers into raw pipelines
- provide ergonomic helpers so common Junction workflows are quicker to assemble than bespoke harness code
- return a structured decision artifact, not just prose
- enforce real cycle detection for nested participant graphs
- use max nested depth as a guardrail, not the only safety mechanism
- stop at decision production and leave downstream plan/act/verify orchestration out of v1

## Current Status

- Junction is fully implemented as a reusable P2P harness.
- The current code supports P2P registration, DSL setup, trace wiring, all three orchestration strategies, real cycle detection, convenience helpers, and regression coverage.
- The living tracker, requirements doc, and todo list now exist together as the compaction-safe Junction steering set.
- The remaining work for this workstream is future extension only, not core harness correctness.

## Plan At A Glance

- [x] Phase 1: Build the baseline Junction runtime, models, DSL, and trace wiring.
- [x] Phase 2: Harden the orchestration strategy semantics for all three required modes.
- [x] Phase 3: Implement real nested-container cycle detection.
- [x] Phase 4: Add convenience helpers for common Junction harness workflows.
- [x] Phase 5: Expand tests and docs to reflect the final strategy and safety model.

## Completed Work

- Junction now accepts any `P2PInterface` moderator or participant, including nested containers such as `Manifold`.
- Junction now supports `SIMULTANEOUS`, `CONVERSATIONAL`, and `ROUND_ROBIN` with distinct behavior.
- Junction now performs real nested-container cycle detection before execution.
- Junction now exposes convenience helpers for common harness setup flows.
- Junction now emits dedicated trace events and produces a structured `DiscussionDecision`.
- The steering set is complete and can be used to resume or review the harness without re-deriving intent.

## Decisions Log

- Junction will accept any `P2PInterface` as a moderator or participant.
- Nested containers such as `Manifold` will be supported as first-class participants.
- Junction will be a deliberation harness, not a downstream act/verify orchestrator in v1.
- The living tracker will be kept in `md/junction-harness-implementation-tracker.md`.
- The full intent and requirements now live in `md/junction-harness-requirements.md`.
- TTT formatting rules will be applied to every touched Kotlin file.
- Junction trace events will use dedicated `JUNCTION_*` enum values and participate in the existing trace tooling.
- The three original orchestration modes remain mandatory and must all be supported.
- Proper cycle detection is required and must reject indirect participant loops, not just self-reference.
- The harness now exposes convenience helpers for common setup workflows, including bulk participant registration and strategy presets.
- The original strategy intent from the stub comments is now implemented directly in the runtime, not just preserved in docs.

## Risks And Guardrails

- Risk: recursive participant graphs can create cycles or runaway nesting.
- Guardrail: the implementation validates the graph for direct and indirect cycles before execution, with max depth as a secondary guard.
- Risk: discussion prompts can grow quickly when multiple participants are containers.
- Guardrail: the runtime will keep discussion state structured and bounded.
- Risk: the harness can become too Manifold-like and blur responsibilities.
- Guardrail: Junction will stop at decision production and leave downstream action orchestration external.

## Verification Log

- `./gradlew testClasses --no-daemon`
- `./gradlew test --tests com.TTT.Pipeline.JunctionTest -x :TPipe-Bedrock:test -x :TPipe-Defaults:test -x :TPipe-MCP:test -x :TPipe-Ollama:test -x :TPipe-TraceServer:test -x :TPipe-Tuner:test --no-daemon`
- Verified that the baseline Junction runtime compiles and the current regression test passes.
- Verified that the three strategy modes, cycle detection, and convenience helpers all compile and behave as expected.
- Verified that the trace enums, visualizer updates, and docs compile together with the harness.
- Verified that the requirements doc now records the full intent and required strategy/cycle-safety rules.

## Revision History

- 2026-03-19: Initial tracker created from the approved Junction harness plan.
- 2026-03-19: Junction implementation, DSL, trace events, regression tests, and documentation updates landed.
- 2026-03-19: Requirements were split into a dedicated steering doc to preserve full intent and avoid re-gathering context.
- 2026-03-19: Strategy presets, bulk participant helpers, and real nested-container cycle detection were added and verified.
- 2026-03-19: Steering docs were synchronized to mark the Junction harness rollout complete.
