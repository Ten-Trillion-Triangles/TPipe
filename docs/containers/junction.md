# Junction

> 💡 **Tip:** Junction is TPipe's harness for collaborative decision-making and workflow handoff. It coordinates P2P-capable participants, gathers their opinions, tallies votes, and can chain plan, act, verify, adjust, and output phases when configured.

## Table of Contents
- [What Junction Is](#what-junction-is)
- [Core API](#core-api)
- [Discussion Models](#discussion-models)
- [Execution Flow](#execution-flow)
- [Discussion Strategies](#discussion-strategies)
- [Tracing and Control](#tracing-and-control)
- [Usage Example](#usage-example)
- [Implementation Notes](#implementation-notes)

## What Junction Is

Junction is TPipe's harness for collaborative discussion and workflow handoff.

It sits above normal pipe sequencing and coordinates P2P-capable participants. In practice, it:
- accepts a topic as `MultimodalContent`
- dispatches the prompt to participants through P2P
- gathers `ParticipantOpinion` entries
- tallies votes into `VotingResult` records
- optionally asks the moderator for a `ModeratorDirective`
- stops when consensus, intervention, or the round limit ends the discussion

In discussion-only mode, the harness intentionally stops at decision production and does not attempt to execute follow-up plan/act workflows.

When a workflow recipe is selected, the harness runs plan, vote, act, verify, adjust, and output phases through the same P2P binding model. The discussion path remains the default.

## Agent Contract

Understanding the input/output contract between the junction harness and your participants is critical for writing conforming moderator and participant pipelines.

### What the Junction Provides to Participants

At each discussion round, the junction dispatches to participants via P2P. Each participant receives:

- **`MultimodalContent` with the discussion topic** — The participant's pipe receives `content.text` containing the current topic or question being discussed
- **Round context in metadata** — The junction may include `junctionRound`, `junctionTopic`, or other contextual metadata

### What Participants Must Provide Back

Participants must return `MultimodalContent` with:

- **`content.text`** — The participant's opinion or vote as structured text (e.g., JSON or plain text that the junction can parse)
- **`content.terminatePipeline = true`** — Only if the participant wants to halt the entire discussion (rare)

The junction parses participant responses into `ParticipantOpinion` entries using JSON or text pattern matching.

### What the Junction Provides to the Moderator

After collecting participant opinions, the junction may dispatch to the moderator for:

- **Consensus assessment** — If consensus is unclear, the moderator can provide guidance
- **Round directive** — The moderator can signal `continue`, `stop`, or `refine` for the next round

The moderator receives `content.text` containing the aggregated `VotingResult` and participant opinions.

### What the Moderator Must Provide Back

The moderator pipeline must output JSON via `setJsonOutput(ModeratorDirective())`:

```json
{
  "decision": "continue",
  "reason": "optional explanation",
  "refinements": []
}
```

Valid `decision` values: `continue`, `stop`, `refine`, `override`.

### DSL Settings That Affect the Contract

| Setting | Effect on Contract |
|---------|-------------------|
| `rounds(n)` | Maximum discussion rounds. Junction exits when limit is reached even without consensus. |
| `threshold(t)` | Consensus threshold (0.0-1.0). If votes exceed this, discussion stops early. |
| `intervention(true/false)` | Enables moderator intervention between rounds. When `true`, moderator can redirect the discussion. |
| `strategy(strategy)` | Controls participant dispatch order: `SIMULTANEOUS` (parallel), `ROUND_ROBIN` (sequential), `CONVERSATIONAL` (dynamic selection). |
| `workflowRecipe(recipe)` | Switches from discussion-only to workflow phases. Changes which roles (planner, actor, verifier, etc.) are used. |
| `maxNestedDepth(depth)` | Guard against deep P2P recursion when nested containers are participants. |
| `killSwitch(input, output, onTripped)` | Halts execution if token limits are exceeded. |
| `concurrencyMode(ISOLATED)` | Required for P2P exposure. Each request gets a fresh junction state. |
| `memoryPolicy { }` | Shapes outbound memory (e.g., token budget for what participants receive). |

### Workflow Recipe Contract

When using workflow recipes, the junction cycles through different role phases:

| Recipe | Roles Used |
|--------|------------|
| `VOTE_PLAN_OUTPUT_EXIT` | voter, planner, outputHandler |
| `PLAN_VOTE_ADJUST_OUTPUT_EXIT` | planner, voter, adjuster, outputHandler |
| `VOTE_ACT_VERIFY_REPEAT` | voter, actor, verifier (loops until condition met) |
| `ACT_VOTE_VERIFY_REPEAT` | actor, voter, verifier (loops until condition met) |

Each role receives specific input and must produce specific output:

- **Planner** — Receives task context, outputs a plan as JSON
- **Actor** — Receives plan/task, executes, outputs result
- **Verifier** — Receives actor output, validates, outputs pass/fail
- **Adjuster** — Receives failed verification, modifies plan

### Voting Contract

The junction tallies votes from participants into a `VotingResult`:

```kotlin
data class VotingResult(
    var votesFor: Int = 0,
    var votesAgainst: Int = 0,
    var votesAbstain: Int = 0,
    var consensusReached: Boolean = false,
    var summary: String = ""
)
```

Participants should format their opinions so the junction can extract:
- `votesFor` — Count of positive votes
- `votesAgainst` — Count of negative votes
- `consensusReached` — Whether threshold was met

### Discussion State Flow

1. **Topic dispatched** — Junction sends topic to all participants
2. **Opinions collected** — Junction parses responses into `ParticipantOpinion` entries
3. **Votes tallied** — Junction produces `VotingResult`
4. **Moderator may intervene** — If intervention enabled, moderator can redirect
5. **Repeat or exit** — Continue until consensus or round limit

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

## DSL Builder

The type-safe Kotlin DSL enforces moderator and participant ordering at compile time through a state machine with four stages:

- `JunctionStage.Initial` — Nothing configured yet
- `JunctionStage.HasModerator` — `moderator { }` has been called
- `JunctionStage.HasParticipants` — At least one `participant { }` has been called
- `JunctionStage.Ready` — All required and optional configuration is complete

### Entry Point

```kotlin
import com.TTT.Pipeline.junction

val junction = junction {
    // moderator is required first
    moderator("moderator", moderatorPipeline)

    // participants follow
    participant("security", securityPipeline)
    participant("performance", performancePipeline)
    participant("ux", uxPipeline)

    // optional: configure workflow recipe
    workflowRecipe(JunctionWorkflowRecipe.VOTE_PLAN_OUTPUT_EXIT)

    // optional: builder methods for chaining
    concurrencyMode(P2PConcurrencyMode.ISOLATED)
    killSwitch(inputTokenLimit = 50000, outputTokenLimit = 5000)
    strategy(DiscussionStrategy.ROUND_ROBIN)
    rounds(4)
    threshold(0.75)
    intervention(true)
    tracing()
}
```

### DSL Methods

All builder methods return `JunctionBuilder<S>` for chaining:

| Method | Stage After Call | Description |
|--------|------------------|-------------|
| `moderator(roleName, component, ...)` | `HasModerator` | Sets the discussion moderator |
| `participant(roleName, component, ...)` | `HasParticipants` | Adds a participant; can be called multiple times |
| `moderator(component, ...)` | `HasModerator` | Sets moderator with auto-generated role name |
| `participant(component, ...)` | `HasParticipants` | Adds participant with auto-generated role name |
| `concurrencyMode(mode)` | any | Sets P2P concurrency mode (SHARED or ISOLATED) |
| `killSwitch(input, output, onTripped)` | any | Halts execution if token limits are exceeded |
| `workflowRecipe(recipe)` | any | Selects a built-in workflow recipe |
| `discussionOnly()` | any | Switches to discussion-only execution |
| `voteActVerifyRepeat()` | any | Shortcut for `VOTE_ACT_VERIFY_REPEAT` recipe |
| `actVoteVerifyRepeat()` | any | Shortcut for `ACT_VOTE_VERIFY_REPEAT` recipe |
| `votePlanActVerifyRepeat()` | any | Shortcut for `VOTE_PLAN_ACT_VERIFY_REPEAT` recipe |
| `planVoteActVerifyRepeat()` | any | Shortcut for `PLAN_VOTE_ACT_VERIFY_REPEAT` recipe |
| `votePlanOutputExit()` | any | Shortcut for `VOTE_PLAN_OUTPUT_EXIT` recipe |
| `planVoteAdjustOutputExit()` | any | Shortcut for `PLAN_VOTE_ADJUST_OUTPUT_EXIT` recipe |
| `planner(roleName, component, ...)` | any | Configures a planner role for workflows |
| `actor(roleName, component, ...)` | any | Configures an actor role for workflows |
| `verifier(roleName, component, ...)` | any | Configures a verifier role for workflows |
| `adjuster(roleName, component, ...)` | any | Configures an adjuster role for workflows |
| `outputHandler(roleName, component, ...)` | any | Configures an output handler role |
| `strategy(strategy)` | any | Sets the discussion strategy |
| `rounds(n)` | any | Sets maximum discussion rounds |
| `threshold(t)` | any | Sets consensus voting threshold (0.0–1.0) |
| `intervention(enabled)` | any | Enables or disables moderator intervention |
| `maxNestedDepth(depth)` | any | Sets maximum nested P2P dispatch depth |
| `tracing(config)` | any | Enables tracing |
| `descriptor(descriptor)` | any | Sets the P2P descriptor for this junction |
| `requirements(requirements)` | any | Sets the P2P requirements |
| `memoryPolicy { }` | any | Configures outbound memory policy |

### Manual Builder

For manual assembly and chaining, use `junctionBuilder()`:

```kotlin
import com.TTT.Pipeline.junctionBuilder
import com.TTT.Pipeline.build

val builder = junctionBuilder<JunctionStage.Initial>()
    .moderator("moderator", moderatorPipeline)
    .participant("security", securityPipeline)
    .participant("performance", performancePipeline)
    .concurrencyMode(P2PConcurrencyMode.ISOLATED)
    .rounds(3)

val junction = builder.build()  // only available on JunctionBuilder<Ready>
```

### Build Modes

- `junction { ... }` uses `build()` internally and returns an initialized junction
- `JunctionBuilder.build()` initializes synchronously with `runBlocking`
- `JunctionBuilder.buildSuspend()` initializes asynchronously in a coroutine context

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

## P2P Concurrency

Junction is stateful — it maintains discussion state, workflow state, and pause flags during execution. When exposed via P2P, register with `P2PConcurrencyMode.ISOLATED` so each inbound request gets a fresh clone. See [P2P Registry and Routing](../advanced-concepts/p2p/p2p-registry-and-routing.md#concurrency-modes) for details.

**Next:** [Container Overview →](container-overview.md)
## Next Steps

- [MultiConnector - Advanced Routing](multiconnector.md) - Continue to advanced routing patterns.
