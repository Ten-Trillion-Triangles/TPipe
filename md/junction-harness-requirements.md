# Junction Harness Requirements

Date: 2026-03-19

## Purpose

Junction is TPipe's harness for collaborative discussion and decision-making. It is the harness itself, not a thin helper around a harness, and it should make common multi-agent workflows faster to build in Junction than by hand-wiring a custom orchestration class.

The harness must support multiple orchestration configurations, preserve nested P2P containers as first-class participants, and produce a structured decision artifact at the end of execution.

## Required Participants

- Junction must accept any `P2PInterface` as a moderator or participant.
- Nested containers such as `Manifold` must remain first-class participants.
- Junction must preserve each participant's own P2P behavior and metadata instead of flattening the participant into a plain pipeline.
- A participant may be a pipeline, a container, or a future P2P-capable harness, as long as it satisfies `P2PInterface`.

## Required Orchestration Modes

Junction must support the three orchestration modes described in the original class intent:

### `SIMULTANEOUS`

- Dispatch all active participants for a round together.
- Collect the opinions and votes after the full fan-out completes.
- Use the aggregated result to determine whether another round is needed.

### `ROUND_ROBIN`

- Dispatch participants in stable order.
- Preserve round checkpoints between turns.
- Evaluate the round after the full turn sequence completes.

### `CONVERSATIONAL`

- Let the moderator influence which participants speak next.
- Allow the selected participant set to evolve between rounds.
- Fall back to the registered participant set if the selected set is empty or invalid.

## Harness Safety Requirements

- Direct self-reference must be rejected.
- Indirect container cycles must be rejected.
- Repeated container identity along a traversal path must be rejected.
- Maximum nested depth must remain a secondary guard, not the only safety mechanism.
- Cycle rejection must fail fast with a clear error so the harness does not enter runaway recursion.

## Harness Behavior Requirements

- Junction must support moderator registration, participant registration, and optional voting weights.
- Junction must emit trace events for start, end, round boundaries, dispatch, response, vote tally, consensus checks, and pause/resume.
- Junction must support pause/resume at safe checkpoints.
- Junction must return a structured `DiscussionDecision` payload rather than free-form text.
- Junction must keep the current discussion state in a serializable form so future sessions and trace tools can reason about it.

## Convenience Requirements

- Junction should provide ergonomic setup helpers so common harness workflows take less code than hand-rolling a custom orchestrator.
- The DSL and any future helper methods must stay aligned with the runtime behavior.
- Convenience helpers must not hide or weaken the underlying P2P participant contract.

## Non-Goals

- Junction is not a downstream plan/act/verify orchestrator in v1.
- Junction must not silently flatten nested containers into plain pipelines.
- Junction must not auto-repair graph cycles by mutating the participant graph behind the caller's back.

## Success Criteria

Junction is considered complete for the current harness workstream when:

- all three orchestration modes are supported with distinct behavior
- nested P2P containers can participate safely
- cycle detection is real, not just depth-based
- the harness remains easy to configure for repeated use
- the tracker and todo files describe the current state without requiring the code to be re-read

## Status

As of 2026-03-19, the working tree implements the requirements described in this file, including the required orchestration modes, nested P2P participant support, real cycle detection, convenience helpers, trace integration, and the structured decision output.
