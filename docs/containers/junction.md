# Junction

> 💡 **Tip:** Junction is TPipe's harness for collaborative decision-making and workflow handoff. It coordinates P2P-capable participants, gathers their opinions, tallies votes, and can chain plan, act, verify, adjust, and output phases when configured.

## Table of Contents
- [Current Status](#current-status)
- [What Junction Is](#what-junction-is)
- [Core API](#core-api)
- [Discussion Models](#discussion-models)
- [Execution Flow](#execution-flow)
- [Discussion Strategies](#discussion-strategies)
- [Tracing and Control](#tracing-and-control)
- [Usage Example](#usage-example)
- [Implementation Notes](#implementation-notes)

## Current Status

`Junction` is implemented in `src/main/kotlin/Pipeline/Junction.kt` and now acts as a real `P2PInterface` harness.

The discussion-harness requirements live in [`md/junction-harness-requirements.md`](../../md/junction-harness-requirements.md), the completed discussion rollout is tracked in [`md/junction-harness-implementation-tracker.md`](../../md/junction-harness-implementation-tracker.md), and the workflow extension has its own requirements and tracker in [`md/junction-workflow-extension-requirements.md`](../../md/junction-workflow-extension-requirements.md) and [`md/junction-workflow-extension-tracker.md`](../../md/junction-workflow-extension-tracker.md).

It now supports:
- accept any `P2PInterface` as moderator or participant
- support nested containers such as `Manifold`
- run a bounded discussion loop with strategy, round, and threshold controls
- support all three original orchestration strategies with distinct runtime semantics
- reject direct and indirect container cycles before execution
- emit structured trace events
- return a serialized `DiscussionDecision`
- run workflow recipes for plan/vote/act/verify/adjust/output handoff chains
- return a serialized `JunctionWorkflowOutcome` when a workflow recipe is selected

## What Junction Is

Junction is TPipe's decision harness for collaborative discussion.

It sits above normal pipe sequencing and below future consensus-driven orchestration layers. In practice, it:
- accepts a topic as `MultimodalContent`
- dispatches the prompt to participants through P2P
- gathers `ParticipantOpinion` entries
- tallies votes into `VotingResult` records
- optionally asks the moderator for a `ModeratorDirective`
- stops when consensus, intervention, or the round limit ends the discussion

In discussion-only mode, the harness intentionally stops at decision production and does not attempt to execute follow-up plan/act workflows.

When a workflow recipe is selected, the harness becomes a full workflow runner instead of a discussion-only loop. The discussion path remains the default.

## Core API

### Registration

```kotlin
val junction = Junction()
    .setModerator(moderatorHarness)
    .addParticipant("security", securityHarness)
    .addParticipant("performance", performanceHarness)
    .addParticipant("ux", uxHarness)
```

Moderator and participant types are `P2PInterface`, so this can be a pipeline adapter, `Manifold`, `Connector`, `Splitter`, or another container that exposes P2P behavior.

### Configuration

```kotlin
junction
    .setStrategy(DiscussionStrategy.SIMULTANEOUS)
    .setRounds(3)
    .setVotingThreshold(0.75)
    .setModeratorIntervention(true)
    .setMaxNestedDepth(8)
    .enableTracing()
```

### Execution

```kotlin
val result = junction.execute(
    MultimodalContent(text = "Should we ship the new API?")
)
```

`conductDiscussion(...)` is also available as a semantic alias for `execute(...)`.

## Discussion Models

The harness uses a small set of serializable models in `src/main/kotlin/Pipeline/JunctionModels.kt`:

- `DiscussionStrategy`
- `ParticipantOpinion`
- `VotingResult`
- `ModeratorDirective`
- `DiscussionState`
- `DiscussionDecision`

The final response is stored as:
- `MultimodalContent.text` containing serialized `DiscussionDecision`
- `metadata["junctionDecision"]`
- `metadata["junctionState"]`

## Execution Flow

The runtime flow is:

1. `init()` validates the moderator, participants, round limit, threshold, and nested-depth guard.
2. `execute(...)` copies the input content and derives the topic.
3. The harness iterates through rounds until consensus or the round limit is reached.
4. Each round dispatches participant requests through P2P.
5. Participant responses are parsed into `ParticipantOpinion`.
6. Votes are tallied into `VotingResult`.
7. The moderator may return a `ModeratorDirective` to continue, stop, or refine the next round.
8. The final `DiscussionDecision` is serialized back into the returned `MultimodalContent`.

## Discussion Strategies

### Simultaneous

All selected participants are dispatched in parallel, then the harness aggregates their opinions.

### Round Robin

Participants are dispatched in stable order within the round.

### Conversational

The harness honors `selectedParticipants` from the current state when present. If the selected list is empty or invalid, it falls back to the registered participant set.

## Workflow Recipes

Junction can also run built-in workflow recipes when you want the harness to handle action-oriented orchestration without hand-wiring a custom class.

The supported recipes are:

- `Vote -> Act -> Verify -> Repeat`
- `Act -> Vote -> Verify -> Repeat`
- `Vote -> Plan -> Act -> Verify -> Repeat`
- `Plan -> Vote -> Act -> Verify -> Repeat`
- `Vote -> Plan -> Output instructions as prompt -> Exit`
- `Plan -> Vote -> Adjust -> Output instructions as prompt -> Exit`

Workflow participants are still `P2PInterface` instances, so the planner, actor, verifier, adjuster, output handler, and moderator can each be pipelines or nested containers such as `Manifold`.

The workflow API exposes:

- `conductWorkflow(...)` for recipe execution
- `setWorkflowRecipe(...)` and the recipe-specific helpers
- `setPlanner(...)`, `setActor(...)`, `setVerifier(...)`, `setAdjuster(...)`, and `setOutputHandler(...)`

Workflow results are serialized as `JunctionWorkflowOutcome` and stored in both the returned content text and metadata.

## Tracing and Control

Junction supports tracing and runtime control hooks:

- `enableTracing(config)`
- `disableTracing()`
- `getTraceReport(format)`
- `getFailureAnalysis()`
- `getTraceId()`
- `pause()`
- `resume()`
- `isPaused()`
- `canPause()`

Trace events use dedicated `JUNCTION_*` entries so the harness is visible in the trace system alongside `PIPE_*`, `MANIFOLD_*`, and `SPLITTER_*` events.

Pause and resume are checkpointed between rounds and before the next participant dispatch.

## Usage Example

```kotlin
val moderator = buildModeratorHarness()
val security = buildSecurityHarness()
val performance = buildPerformanceHarness()

val junction = Junction()
    .setModerator("moderator", moderator)
    .addParticipant("security", security)
    .addParticipant("performance", performance)
    .setStrategy(DiscussionStrategy.CONVERSATIONAL)
    .setRounds(4)
    .setVotingThreshold(0.8)
    .enableTracing()

val result = junction.conductDiscussion(
    MultimodalContent(text = "Should we make this change?")
)
```

The returned content contains a structured decision payload, not just free-form prose.

## Implementation Notes

- Nested containers are first-class participants because `Junction` speaks to `P2PInterface`, not to pipelines directly.
- The harness performs real cycle detection before execution and uses max-nested-depth as a secondary guard.
- The moderator is optional at configuration time but required before `init()` succeeds.
- The implementation is deterministic in the sense that it always returns a decision artifact, even if it falls back to the best available vote or topic text.

**Next:** [Container Overview →](container-overview.md)
