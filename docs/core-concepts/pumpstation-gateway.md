# PumpStation Gateway

> 💡 **Tip:** A **gateway** is a named, LLM-driven gate that judges whether one step of a task has been completed. When the gate passes, the harness advances to the next **stage** and reconfigures itself — visible paths, agent roster, settings — for the work that comes next.


## Table of Contents
- [What a Gateway Is](#what-a-gateway-is)
- [When to Use One](#when-to-use-one)
- [The Three Triggers](#the-three-triggers)
- [The Gateway Phase](#the-gateway-phase)
- [The Verdict Contract](#the-verdict-contract)
- [What Happens When the Gate Fails](#what-happens-when-the-gate-fails)
- [The Stage Model](#the-stage-model)
- [Stage Reconfiguration Actions](#stage-reconfiguration-actions)
- [Configuring a Gateway](#configuring-a-gateway)
- [Prompt Assembly](#prompt-assembly)
- [Binding a Gateway to a Todo Task](#binding-a-gateway-to-a-todo-task)
- [Failure Semantics](#failure-semantics)
- [Events and Tracing](#events-and-tracing)
- [End-to-End Example](#end-to-end-example)
- [Cross-References](#cross-references)


## What a Gateway Is

A gateway is an agent with one job: answer a yes/no question about the state of the work and hand back a structured verdict.

It is not the worker. The normal judge → dispatch → path cycle keeps doing the work; the gateway only observes the result and decides whether the current step is finished. That separation is what makes it useful as a *stage gate*: the harness can withhold the next stage's capabilities until the gateway says the current step is done.

Concretely, a gateway is:

- a **name** — the handle used by triggers and stage definitions,
- an **agent** — a `Pipeline`, because the harness injects the gateway's system prompt into the agent's decision pipe. A builder may hand back any `P2PInterface`; a non-`Pipeline` agent is reported as a configuration error rather than silently run without a contract,
- **criteria** — a saved string that says what "done" means for this step,
- a **verdict** — a typed `PumpStationGatewayVerdict` with `complete`, `reason`, and `instruction`.

The harness owns the ordering, the prompt assembly, the verdict parsing, and the stage transitions. The developer supplies the name, the agent, the criteria, and the stage sequence.


## When to Use One

Use a gateway when:

- a task has **ordered steps** and the harness should not start step *n+1* until step *n* is verifiably done,
- "done" needs a **judgement**, not a flag — e.g. "the migration ran and the output was written", which no boolean expresses,
- the **next step needs different capabilities** — different paths, a different agent roster, different turn limits.

Do not use a gateway when:

- a single boolean suffices — use the `passPipeline` / `terminatePipeline` flags,
- you only want to *observe* — use an event observer or a background harness agent,
- the gate would be unsatisfiable — use `gateBlocking = false` to make it advisory, or do not gate at all.


## The Three Triggers

A gateway only runs when something asks for it. There are exactly three typed ways to ask, and no fuzzy text matching anywhere.

### 1. A flag on `MultimodalContent`

```kotlin
content.requestGateway("todo-check")
```

The request travels with the content. `MultimodalContent.gatewayRequests` is `@Transient`, so the request never crosses a serialization boundary — it is a developer channel, exactly like `force()`. Because it is a constructor property, `MultimodalContent.copy()` carries it forward.

This is how a path's execution function asks for a gate it cannot reach directly:

```kotlin
path("write-tests") {
    setExecutionFunction { content, station, _, _ ->
        val result = writeTests(content)
        result.requestGateway("todo-check")
        result
    }
}
```

### 2. A direct signal to the station

```kotlin
station.signalGateway("todo-check")
```

Use this from external code, a DITL hook, or a custom harness agent.

### 3. The dispatch magic contract

`PathRequest` carries an optional `gatewayRequest` field:

```kotlin
@Serializable
data class PathRequest(
    var pathName: String = "",
    var pathSchema: String = "",
    var pathSelectionRationale: String? = null,
    var gatewayRequest: String? = null
)
```

The dispatch agent can therefore ask for a gate in the same JSON it already emits. The field is advertised automatically: the dispatch JSON example is generated from `examplePromptFor(PathRequest::class)`, so a new field appears in the dispatch prompt without any extra wiring.

The gateway named by `gatewayRequest` is evaluated **in the same turn**, right after the dispatched path has run.

### Where requests are collected

The harness collects requests wherever content enters the loop — the turn's input, path results, judge results, memory-update results and compaction results. The registry is drained by the gateway phase, so each request produces at most one evaluation per turn: asking twice for the same gateway in one turn still evaluates it once.

The registry lives on the station, outside `PumpStationTaskState` and `PumpStationSnapshot`, so it survives `P2PInit` and is not part of a resume — exactly like the force-flag registry. A nested station keeps its own registry.


## The Gateway Phase

The gateway phase runs **after path execution and before the foreground harness agents**:

```
health → judge → dispatch → path → GATEWAY → foreground agents → background agents → memory → compaction
```

The position matters. A gateway judges the work that was *just* performed, so it has to run after the path. It runs before the harness agents so a verdict is available before the next agents fire, and so a stage advance can reconfigure the roster before those agents run.

The phase is a no-op when nothing was requested: the pending registry is drained, and an empty drain returns immediately.


## The Verdict Contract

The gateway agent answers with a `GatewayVerdict` JSON object:

```json
{
  "complete": false,
  "reason": "The unit test file exists but the test suite was not run.",
  "instruction": "Run the test suite and paste the result into the report."
}
```

| Field | Meaning |
|---|---|
| `complete` | `true` only when the step is verifiably finished against the criteria. |
| `reason` | A brief explanation of the verdict. |
| `instruction` | When `complete` is false, what the agent must do next. May be empty when `complete` is true. |

The default prompt (`DEFAULT_GATEWAY_PROMPT`) is always present, because it carries this contract. It is **never replaced** by the developer's criteria string — the criteria are **appended** to it. A gateway therefore cannot lose its output contract.

Parsing is tolerant, in three steps:

1. **Typed JSON** — `deserialize<PumpStationGatewayVerdict>(content.text)`. The repository's deserialize helper repairs common AI JSON defects.
2. **Flag fallback** — if the typed contract cannot be read, the standard loop-control flags are consulted (`passPipeline` / `terminatePipeline` via `checkMultimodalFlags`). A pass flag reads as "complete", a terminate flag reads as "not complete".
3. **Fail closed** — if neither works, the verdict is `complete = false` and the event carries `parseFailed = true`. The stage stays locked.


## What Happens When the Gate Fails

A "not complete" verdict does **not** end the turn.

The harness:

1. injects the verdict's `instruction` into `turnHistory` as a `ConverseRole.harness` entry, so both the judge and the dispatch agent see it on the next turn,
2. leaves the stage **locked** — the next stage's paths, agents and settings are not applied,
3. lets the turn finish normally through safe-prune → foreground agents → background agents → memory update → compaction.

That last point is deliberate. Ending the turn early on a failing gate would skip the memory update and compaction phases, starving the lorebook, summary and compaction managers on exactly the turns when the harness is struggling. The agent keeps its ability to dispatch and do the missing work; what it loses is access to the *next* stage's capabilities.

The ordering is enforced by the **stage lock**, not by blocking the loop. The harness never refuses to run a phase because a gate failed.


## The Stage Model

A station may declare an ordered sequence of stages. Each stage names the gateway that gates it and carries the reconfiguration to apply when the stage becomes active.

```kotlin
gatewayStage("implementation") {
    gateway = "todo-check"
    setVisiblePaths("implement")
    enableHarnessAgent("reviewer")
}
```

Stage 0 is active from the start: its `onEnter` actions are applied when the harness initializes (`P2PInitInternal`), and a `GatewayStageAdvanced` event with `fromStage = null` records the initial configuration in the trace.

A stage advances when its gate is met:

- `gateBlocking = true` (the default) — the stage advances only when the gating gateway returns `complete = true`.
- `gateBlocking = false` — the gate is **advisory**: it still evaluates and still emits a verdict, but the stage advances anyway.

`gateBlocking = false` is the escape hatch for a gate that cannot be satisfied or would otherwise deadlock the harness.

When the stage advances, the new stage's `onEnter` actions run in list order on the foreground harness loop. A failing action is recorded and skipped; the remaining actions still run.


## Stage Reconfiguration Actions

| Action | Effect |
|---|---|
| `RevealPath(name)` | Moves a reserve path into the visible path list, bypassing its `revealWhen` predicate. |
| `HidePath(name)` | Hides a path: a visible path moves back into the reserve list; a reserve path revealed by its `revealWhen` predicate has the predicate neutralised and the sticky reveal flag cleared, so it leaves the visible set. |
| `SetVisiblePaths(names...)` | Replaces the entire visible path set with exactly these names. |
| `SetHarnessAgentEnabled(name, enabled)` | Enables or disables one named harness agent slot. |
| `SetHarnessAgents(slots...)` | Replaces the entire harness agent roster. |
| `SetSetting(setting, value)` | Changes one station setting. |
| `Custom { station -> ... }` | Arbitrary developer-supplied reconfiguration. |

The settings a stage may change:

| Setting | Value type |
|---|---|
| `ForegroundTurnInterval` | Int |
| `BackgroundTurnInterval` | Int |
| `MaxConcurrentForegroundAgents` | Int |
| `MaxConcurrentBackgroundAgents` | Int |
| `CompactionThreshold` | Double |
| `MaxTurnHistorySize` | Int |
| `MaxTurns` | Int |

A value whose type does not match the target setting is **rejected, not coerced**. The action is recorded as failed and the rest of the stage still applies.


## Configuring a Gateway

```kotlin
pumpStation {
    gateway("todo-check") {
        agent = todoGatePipeline
        systemPrompt = "The step is done when the unit test file exists AND the suite passes."
        userPrompt = "Focus on task 3."
        userPromptPlacement = PumpStationGatewayPromptPlacement.BeforeHistory
        historySource = PumpStationGatewayHistorySource.Summarized
    }
}
```

| Property | Type | Default | Description |
|---|---|---|---|
| `agent` | `Pipeline?` | `null` | The gateway agent. Must be a `Pipeline` so the system prompt can be injected. |
| `builderFunction` | `(suspend (PumpStation) -> P2PInterface)?` | `null` | Per-turn agent factory, evaluated before each invocation. |
| `systemPrompt` | `String?` | `null` | Saved criteria. Appended to the default contract prompt. |
| `userPrompt` | `String?` | `null` | Injectable instruction, placed per `userPromptPlacement`. |
| `userPromptPlacement` | `PumpStationGatewayPromptPlacement` | `BeforeHistory` | Where the instruction lands. |
| `historySource` | `PumpStationGatewayHistorySource` | `Summarized` | Which history the gateway reads. |
| `todoPageKey` | `String?` | `null` | ContextBank page key of a todo list to bind to. |
| `todoTaskNumber` | `Int?` | `null` | Todo task number to bind to. |

The imperative equivalents are `setGateway(config)`, `removeGateway(name)`, `getGateways()`, `setGatewayStages(stages)`, `getGatewayStages()` and `getGatewayStageIndex()`.

### History source

| Value | What the gateway reads |
|---|---|
| `Summarized` | `turnHistory` — the pruned, curated view the judge and dispatch agents see. |
| `Full` | `rawTurnHistory` — the full retained event log, including entries the pruned view has dropped. |

`Summarized` is the default: it is the same context the rest of the harness reasons over. Choose `Full` when the gate needs evidence the curated view has discarded.

### Prompt placement

| Value | Where the instruction lands |
|---|---|
| `SystemPrompt` | Appended to the gateway's system prompt, after the contract prompt and the saved criteria. |
| `BeforeHistory` | In the gateway's user message, before the conversation-history block. |
| `AfterHistory` | In the gateway's user message, after the conversation-history block. |
| `Suffix` | At the very end of the gateway's user message. |


## Prompt Assembly

The gateway's system prompt is assembled at invocation time, not at configuration time — the gateway may be reconfigured between turns.

```
DEFAULT_GATEWAY_PROMPT
  + "

=== GATEWAY CRITERIA (<name>) ===
" + <systemPrompt>
  + "

[GATEWAY INSTRUCTION]
" + <userPrompt> + "
[/GATEWAY INSTRUCTION]"   // only when placement == SystemPrompt
```

The gateway's user message is the normal turn content, with the injectable instruction inserted at the configured position and, when bound to a todo task, a `[GATEWAY TODO TASK]` block inserted before the history.


## Binding a Gateway to a Todo Task

A gateway can be bound to one task of a `ContextBank` todo list. When both `todoPageKey` and `todoTaskNumber` are set, the harness resolves the task and injects it as a `[GATEWAY TODO TASK]` block ahead of the history:

```text
[GATEWAY TODO TASK]
task: Write the failing test for the parser
completion requirements: the test file exists and fails on the current implementation
[/GATEWAY TODO TASK]
```

The bound task's `completionRequirements` are the natural source for the gateway's criteria, so a todo-driven task list can drive the gate without the criteria being restated.


## Failure Semantics

| Situation | Result |
|---|---|
| Gateway requested but not configured | `GatewaySkipped` event; the turn continues; the stage stays locked. |
| Gateway configured but has no agent | `GatewaySkipped` event; the stage stays locked. |
| Gateway agent is not a `Pipeline` | `GatewaySkipped` event naming the cause; the stage stays locked. The verdict contract can only be injected into a `Pipeline`, so a non-`Pipeline` agent would otherwise never satisfy the gate. |
| The gateway agent throws | `GatewayEvaluated` with `complete = false`, `parseFailed = true`; the failure count increments; the stage stays locked. |
| The output cannot be parsed | `GatewayEvaluated` with `complete = false`, `parseFailed = true`; the stage stays locked. |
| A stage action fails | Recorded in `GatewayStageAdvanced.failed`; the remaining actions still run. |
| A setting value has the wrong type | Recorded in `GatewayStageAdvanced.failed`; the setting is unchanged. |

Every failure is **fail-closed**: the stage does not advance on a failure it could not evaluate.


## Events and Tracing

| Event | Trace type | When |
|---|---|---|
| `GatewayEvaluated` | `PUMP_STATION_GATEWAY_EVALUATED` | A gateway produced a verdict. Carries `gatewayName`, `stageName`, `complete`, `reason`, `instruction`, `parseFailed`. |
| `GatewayStageAdvanced` | `PUMP_STATION_GATEWAY_STAGE_ADVANCED` | The active stage changed. Carries `fromStage`, `toStage`, `applied`, `failed`. |
| `GatewaySkipped` | `PUMP_STATION_GATEWAY_SKIPPED` | A requested gateway could not be evaluated. |

The stage index, stage name and failure count live in `PumpStationTaskState` and are therefore snapshotted.


## End-to-End Example

A two-stage task: write the tests, then implement. The `todo-check` gateway gates both stages.

```kotlin
val station = pumpStation("feature") {
    path("write-tests") {
        description = "Write the failing unit tests."
        setExecutionFunction { content, station, _, _ ->
            val result = writeTests(content)
            result.requestGateway("todo-check")
            result
        }
    }
    reservePath("implement") {
        description = "Implement the feature."
    }

    gateway("todo-check") {
        agent = gatePipeline
        systemPrompt = "The step is done when the test file exists and the suite passes."
        historySource = PumpStationGatewayHistorySource.Summarized
    }

    gatewayStage("tests") {
        gateway = "todo-check"
        setVisiblePaths("write-tests")
    }

    gatewayStage("implementation") {
        gateway = "todo-check"
        setVisiblePaths("implement")
        enableHarnessAgent("reviewer")
    }

    harnessAgent(reviewerPipeline, PumpStationConcurrencyMode.Blocking, name = "reviewer")
}
```

Turn 1: the dispatch agent picks `write-tests`. The path runs and its result requests the `todo-check` gateway. The gateway phase evaluates it, the verdict comes back `complete = false` with an instruction, and the instruction is injected into `turnHistory` as a harness-role entry. The stage stays on `tests`. The turn continues through the foreground agents, the memory update and the compaction.

Turn 2: the judge and dispatch agents both see the gateway's instruction, so the dispatch agent picks `write-tests` again — or a repair path. When the work is genuinely done, the gateway returns `complete = true` and the stage advances to `implementation`: `implement` is revealed, `write-tests` is hidden, and the reviewer agent is enabled.


## Cross-References

- **[PumpStation Container](../containers/pumpstation.md#gateway-system)** — The gateway phase in the turn order, the DSL blocks, stage configuration
- **[PumpStation API](../api/pumpstation.md#gateway-system)** — The public gateway API surface
- **[PumpStation Models API](../api/pumpstation-models.md#gateway-models)** — Enums, models and events
- **[PumpStation Magic Contracts](../core-concepts/pumpstation-magic-contracts.md#gateway-contract-inbound-verdict)** — The verdict JSON, parser and failure semantics
