# PumpStation

> 💡 **Tip:** **PumpStation** is TPipe's runtime agentic harness. It drives a judge → dispatch → path → memory cycle over multiple turns, routes through named `PathObject` units, and applies memory management, lorebook, DITL hooks, and a per-turn `KillSwitch` to keep the loop bounded and observable.


## Table of Contents
- [What PumpStation Is](#what-pumpstation-is)
- [When to Use It](#when-to-use-it)
- [Architecture at a Glance](#architecture-at-a-glance)
- [Agent Contracts](#agent-contracts)
  - [The Judge Agent Contract](#the-judge-agent-contract)
  - [The Dispatch Agent Contract](#the-dispatch-agent-contract)
  - [The Path Execution Contract](#the-path-execution-contract)
  - [The Goal Agent Contract](#the-goal-agent-contract)
  - [The Health Agent Contract](#the-health-agent-contract)
  - [The Path-Safety Agent Contract](#the-path-safety-agent-contract)
  - [The Lorebook Agent Contract](#the-lorebook-agent-contract)
  - [The Summary Agent Contract](#the-summary-agent-contract)
- [PathObject and PathRequest](#pathobject-and-pathrequest)
- [DSL Builder](#dsl-builder)
- [DSL Block Reference](#dsl-block-reference)
- [Execution Flow: The Two-Scope Loop](#execution-flow-the-two-scope-loop)
- [Phase Reference](#phase-reference)
- [Memory Management](#memory-management)
- [Concurrency Modes](#concurrency-modes)
- [DITL Hooks](#ditl-hooks)
- [Path Risk Levels](#path-risk-levels)
- [Loop Guards](#loop-guards)
- [Reserve Paths](#reserve-paths)
- [Stash, Snapshots, and Pause/Resume](#stash-snapshots-and-pauseresume)
- [Tracing Support](#tracing-support)
- [Kill Switch Integration](#kill-switch-integration)
- [P2P Integration](#p2p-integration)
- [Common Startup Failures](#common-startup-failures)
- [Best Practices](#best-practices)
- [Cross-References](#cross-references)


## What PumpStation Is

`PumpStation` is a runtime agentic harness — a class that drives an LLM-powered loop over multiple turns. Each turn runs a judge, a dispatch, and a path; between turns the harness updates memory, fires background agents, and (optionally) compacts the conversation history.

The contract surface is wide but the build surface is narrow. PumpStation auto-injects system prompts when the developer does not supply them, reads `MultimodalContent` flags for loop control, and treats `PathObject` as the only thing the dispatch agent can call. There are no mandatory JSON contracts the developer must remember to inject — the harness surfaces the prompts for the agents it owns (judge, dispatch, goal, path-safety, health, lorebook, summary) and auto-injects the path descriptor protocol into the dispatch pipe.

PumpStation implements `P2PInterface`. A pump station can sit inside another pump station as a path's internal agent, and a path can be exposed to the wider P2P registry for distributed dispatch.


## When to Use It

Use `PumpStation` when:

- The task is non-trivial and may require multiple LLM calls to complete
- You want an LLM to decide which step to take next, rather than a fixed state machine
- You want the harness to manage context overflow, memory compaction, and lorebook selection for you
- You want a unified kill switch and tracing surface across the agent loop
- You need path-safety validation or goal-validation (Ralph-loop) semantics

**Don't use it** when:

- A single LLM call is enough — use a plain `Pipeline` or `Pipe`
- The control flow is fully deterministic — use a `Manifold` (state machine) or `Junction` (workflow recipes)
- The agents live on different machines and need registry discovery — use `DistributionGrid`


## Architecture at a Glance

The runtime has two scopes. The outer loop (`runHarnessLoop`) drives turns via `while (turnIndex < maxTurns)`, calling the inner cycle (`runTurn`) each iteration, then runs finalization once. The inner cycle runs the per-turn phases (health, judge, dispatch, path, foreground agents, background agents, memory update, compaction) and returns `Continue` or `Halt(reason)`. A separate transition phase, `runExitFlow`, runs goal validation when the judge says the task is complete or a path signals pass.

```
┌────────────────────────────────────────────────────────────────────────┐
│  runHarnessLoop (outer)                                                │
│                                                                        │
│  runPreInitPhase(content)                  ← ONCE                      │
│  while (turnIndex < maxTurns) {                                       │
│      runTurn()                            ← inner cycle               │
│          healthCheck → judge → dispatch → path → fg agents             │
│            → bg agents → memory update → compaction                    │
│          return TurnResult.Continue | Halt(reason)                     │
│  }                                                                     │
│  runFinalizationPhase()                    ← ONCE, returns content    │
└────────────────────────────────────────────────────────────────────────┘
```

The path flow itself:

```
input → preInit → JUDGE → DISPATCH → PathObject → postGenerate → pathValidation
         ↑                                                              ↓
         ←←←←←←←←←←←←←←←← loop ←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←
                       background:
                       lorebookAgent (mutex-queued)
                       summaryAgent  (mutex-queued)
```

Per-turn, the harness (in order):

1. Refreshes agent instances, system prompts, and propagated settings
2. Runs the optional `healthAgent` if interval or error-ratio is exceeded
3. Runs the `judgeAgent` (or skips it in FlagTriggered mode without a flag)
4. If the judge says complete or terminates, transitions to `runExitFlow`
5. Runs the `dispatchAgent` and parses a `PathRequest`
6. Repairs malformed dispatch output up to `failurePolicy.maxDispatchRepairAttempts` times
7. Resolves the path by name (case-insensitive across `pathList` and `reservePaths`)
8. Enforces loop guards (consecutive same path, total calls per path)
9. For medium/high risk paths, runs the path-safety gate
10. Emits `PathSelected`/`PathSafetyStarted`/`PathStarted` events
11. Calls `PathObject.execute` (PCP function, execution function, internal agent, or agent builder — in priority order)
12. Reads the path's token usage; folds it into the station accumulator; runs the per-path kill switch
13. Runs the `pathValidationFunction` DITL hook
14. Runs the `pathTransformationFunction` DITL hook
15. Appends the transformed result to `turnHistory` and `rawTurnHistory`
16. Records the result in `taskState.lastPathResult` and `taskState.latestContent`
17. Checks `passPipeline` / `terminatePipeline` flags on the path's result
18. Prunes turn history if it exceeds `maxTurnHistorySize`
19. Runs the foreground (Blocking) harness agent slots
20. Queues background (Async) harness agent slots via `GlobalScope.launch`
21. Runs the memory update phase (queues lorebook + summary if interval hit or fill pressure)
22. Runs the compaction phase
23. Returns `Continue`

Context blowout detection runs at every phase boundary. The kill switch check runs after each judge, dispatch, and path phase.


## Agent Contracts

PumpStation owns up to seven distinct agents. Each one has a contract — what it receives as input, what it must output, and how the harness interprets its output. The contracts are documented individually below; the consolidated reference (with field-level JSON schemas) is in **[PumpStation Magic Contracts](../core-concepts/pumpstation-magic-contracts.md)**.

All seven agent slots are optional. A pump station with no judge, no path-safety agent, and no goal agent still runs — it relies on `passPipeline` / `terminatePipeline` flags on path results to exit. The harness emits a `HarnessWarning` event with `code = NoExitSignalConfigured` when **none** of the legitimate exit mechanisms are wired (see [Pre-Init Advisory](#pre-init-advisory) below).

### The Judge Agent Contract

**Type:** `Pipeline?`
**Location:** `PumpStation.judgeAgent` / `setJudgeAgent(...)` / `PumpStationBuilder.judgeAgent`
**Default prompt:** `DEFAULT_JUDGE_PROMPT` in `Pipeline/PumpStationDefaults.kt`

**What the judge receives:**

A `MultimodalContent` built by `buildTurnContent()` (see `Pipeline/PumpStationHelpers.kt:677`). The text carries `turnSummary` (if non-blank) prefixed to a phase-specific question, currently `"Is the task complete? Decide based on the conversation history."` for the Judge phase. The `context.converseHistory` is the curated `turnHistory`. The `miniBankContext` is the current `miniBank`. The `metadata` map contains `taskState`, `phase`, `turnIndex`, `runId`, `isInitialTurn`, and `visiblePaths`.

**What the judge must output:**

By default, JSON matching the schema documented in `DEFAULT_JUDGE_PROMPT`:

```json
{
  "isComplete": boolean,
  "shouldTerminate": boolean,
  "reason": string
}
```

`isComplete: true` transitions the harness into the goal-validation exit flow. `shouldTerminate: true` halts the harness immediately with `PumpStationExitReason.TerminateSignal` — there is no goal gate on this path.

The harness parses the JSON via `parseJudgeVerdict` (`Pipeline/PumpStationHelpers.kt:476`). On parse failure, an empty verdict is returned (treated as `isComplete: false`).

**Flag-based override (no JSON):**

The judge can drive the loop entirely via `MultimodalContent` flags. Set `setJudgeJsonContractEnabled(false)` (or `judgeJsonContractEnabled = false` in the DSL). With the JSON contract disabled, the parser is skipped and the verdict comes solely from the flag check:

- `terminatePipeline = true` → `shouldHalt = true`, exit with `TerminateSignal`
- `passPipeline = true` → `isComplete = true`, enter goal validation
- neither set → continue

`JudgeVerdict` is a `data class` defined in `Pipeline/PumpStationModels.kt`:

```kotlin
data class JudgeVerdict(
    val isComplete: Boolean = false,
    val shouldTerminate: Boolean = false,
    val shouldHalt: Boolean = false,
    val reason: PumpStationExitReason? = null
) {
    companion object {
        fun empty(): JudgeVerdict = JudgeVerdict()
    }
}
```

**Judge run mode (FlagTriggered):**

Set `setJudgeRunMode(PumpStationJudgeRunMode.FlagTriggered)`. The judge is skipped on every turn except turns where `requestJudgeNextTurn()` was called. The flag is one-shot — the judge clears it after consuming. A path's `setExecutionFunction` typically calls `station.requestJudgeNextTurn()` when it believes the task is done, deferring the cost of judge LLM calls to the turns that actually need them.

In `FlagTriggered` mode, `maxTurns` is the only safety net if the dispatch agent never signals. Set it conservatively.

### The Dispatch Agent Contract

**Type:** `Pipeline?` (required — `P2PInitInternal` throws if `dispatchAgent` is not a `Pipeline`)
**Location:** `PumpStation.dispatchAgent` / `setDispatchAgent(...)` / `PumpStationBuilder.dispatchAgent`
**Default prompt:** `DEFAULT_DISPATCH_PROMPT` in `Pipeline/PumpStationDefaults.kt`

**What the dispatch agent receives:**

A `MultimodalContent` built by `buildTurnContent()` (same builder as the judge). The text is `turnSummary + "Select the next path to invoke."`. The dispatch pipe additionally has `enableHarnessMode()` called on it, which auto-injects the path descriptor protocol into its system prompt so the visible paths and their schemas are presented to the model.

**What the dispatch agent must output:**

A `PathRequest` JSON object:

```json
{
  "pathName": "the exact path name from the visible list",
  "pathSchema": "free-form input schema string for the path"
}
```

`pathName` is matched case-insensitively against `pathList` and `reservePaths`. An empty or blank `pathName` ends the turn without calling a path (the loop continues). `pathSchema` is passed to the path as the input — see [The Path Execution Contract](#the-path-execution-contract).

`PathRequest` is a `data class` in `Pipeline/PumpStationModels.kt`:

```kotlin
@Serializable
data class PathRequest(
    var pathName: String = "",
    var pathSchema: String = ""
)
```

**Repair on parse failure:**

If the harness cannot parse the dispatch output as `PathRequest`, it invokes `buildRepairPrompt` (up to `failurePolicy.maxDispatchRepairAttempts` times) to ask the model to fix its malformed JSON. After the repair budget is exhausted:

- If `failurePolicy.stopHarnessOnInvalidPathRequest` is `true`, the harness records `lastError = DispatchJsonRepairFailed` and the finalization phase emits a `HarnessFailed` event.
- Otherwise the turn continues without a path call.

The error message the harness injects for an unknown path or invalid path request is built by `buildLlmErrorMessage` (`Pipeline/PumpStationHelpers.kt:743`). It is natural-language, names the available paths, and explains what a valid `PathRequest` looks like — the dispatch agent sees this on the next turn as part of `turnHistory`.

### The Path Execution Contract

**Type:** `PathObject` — see [PathObject and PathRequest](#pathobject-and-pathrequest)
**Location:** `PumpStation.pathList` / `addPath(...)` / `PumpStationBuilder.path("name") { }`

A path receives a `MultimodalContent` and returns a `MultimodalContent`. There is no required JSON contract on the result. The path's content goes through the dispatch path and the loop sees it as the path's output. The harness reads the result's flags:

- `passPipeline = true` → enter goal validation (or exit with `PassSignal` if no goal agent is configured)
- `terminatePipeline = true` → halt with `TerminateSignal` directly, no goal gate
- neither set → result is appended to `turnHistory`, loop continues

`PathObject` is defined in `Pipeline/PumpStation.kt:225`. The path must have at least one execution mechanism configured (see [PathObject and PathRequest](#pathobject-and-pathrequest)).

### The Goal Agent Contract

**Type:** `P2PInterface?`
**Location:** `PumpStation.goalAgent` / `setGoalAgent(...)` / `PumpStationBuilder.goalAgent`
**Default prompt:** `DEFAULT_GOAL_PROMPT` in `Pipeline/PumpStationDefaults.kt`

**What the goal agent receives:**

A `MultimodalContent` built by `buildGoalContent()`. This is the same shape as the judge/dispatch content but the `context.converseHistory` is overridden with `rawTurnHistory` (the full event log, not the curated turn history), and `metadata` includes `judgeVerdict` and `rawHistorySize`. The text is `turnSummary + "Verify the work was done."`. The full event log is included so the goal agent can do thorough deep verification.

**What the goal agent must output:**

By default, prose text. The goal agent's pass/fail signal is read from the `terminatePipeline` flag on the result:

- `terminatePipeline = false` (default) → goal passed → exit with `JudgeComplete`
- `terminatePipeline = true` → goal failed → append the result to `turnHistory`, increment `taskState.goalFailCount`, and continue the loop (subject to `maxGoalFailAttempts`)

There is no JSON contract on the goal agent. The goal's text is treated as critique feedback when the goal fails; the next turn's judge and dispatch see it in the history.

If `maxGoalFailAttempts` is exceeded, the harness halts with `PumpStationExitReason.GoalValidationFailed`.

### The Health Agent Contract

**Type:** `P2PInterface?`
**Location:** `PumpStation.healthAgent` / `setHealthAgent(...)` / `PumpStationBuilder.healthAgent`
**Default prompt:** `DEFAULT_HEALTH_PROMPT` in `Pipeline/PumpStationDefaults.kt`

The health agent fires **before** the judge on a turn if either `healthAgentTurnInterval` turns have passed since the last health check, or the running error ratio exceeds `healthAgentErrorRatioThreshold`. It is proactive (not reactive like `interventionAgent`).

**What the health agent receives:**

A `MultimodalContent` whose `text` is the JSON-serialized `HealthContext`:

```kotlin
@Serializable
data class HealthContext(
    val runId: String,
    val turnIndex: Int,
    val harnessStatus: PumpStationStatus,
    val lastError: String?,
    val consecutivePathCount: Int,
    val lastSelectedPathName: String?,
    val pathCallCounts: Map<String, Int>,
    val visiblePathNames: List<String>,
    val reservePathNames: List<String>,
    val contextFillPercent: Double,
    val turnHistorySummary: List<String>,
    val recentErrors: List<String>
)
```

**What the health agent must output:**

A `HealthReport` JSON object:

```kotlin
@Serializable
data class HealthReport(
    val status: HealthStatus = HealthStatus.Unknown,
    val warnings: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val metrics: Map<String, Double> = emptyMap(),
    val suggestedNextPath: String? = null,
    val terminateHarness: Boolean = false
)
```

`HealthStatus` is the enum `Healthy`, `Degraded`, `Critical`, or `Unknown`. `terminateHarness: true` causes the harness to set `taskState.latestContent.terminatePipeline = true`, which propagates to the next phase boundary and halts the loop.

On parse failure, the harness falls back to `HealthReport()` (all defaults, `Unknown`).

### The Path-Safety Agent Contract

**Type:** `P2PInterface?`
**Location:** `PumpStation.pathSafetyAgent` / `setPathSafetyAgent(...)` / `PumpStationBuilder.pathSafetyAgent`
**Default prompt:** `DEFAULT_PATH_SAFETY_PROMPT` in `Pipeline/PumpStationDefaults.kt`

The path-safety agent runs only for paths with `PathRiskLevel.Medium` or `PathRiskLevel.High`. It sits between `PathSelected` and `PathStarted` and gates whether the path actually runs.

**What the path-safety agent receives:**

The path's input `MultimodalContent`. The path's name, description, schema, and risk level are surfaced in the conversation history.

**What the path-safety agent must output:**

A `PathSafetyVerdict` JSON object:

```json
{
  "safe": boolean,
  "reason": string
}
```

The parser is **strict** on the `safe` field — it must be a JSON boolean literal (`true` or `false`). Strings like `"true"`, numbers, and `null` are rejected; the harness falls back to the flag-based check on the result. To use the strict JSON contract, do nothing; to disable it and rely on `MultimodalContent` flags only, set `pathSafetyJsonContractEnabled = false` (or `setPathSafetyJsonContractEnabled(false)`).

**Flag-based fallback (when JSON parse fails or the contract is disabled):**

- `terminatePipeline = true` → reject the path, return the original input unchanged
- `passPipeline = true` → approve the path, run it normally
- neither set → reject the path (defensive default)

When a path is rejected by safety, the harness does **not** call the path. The original input flows through as the turn's result so the loop can continue.

### The Lorebook Agent Contract

**Type:** `P2PInterface?`
**Location:** `PumpStation.lorebookAgent` / `setLorebookAgent(...)` / `PumpStationBuilder.lorebookAgent`
**Default prompt:** `DEFAULT_LOREBOOK_PROMPT` in `Pipeline/PumpStationDefaults.kt`

The lorebook agent updates `contextWindow.loreBookKeys` with new entries, updates, and removals based on recent turns.

**What the lorebook agent receives:**

A `MultimodalContent` whose `text` is the JSON-serialized `LorebookAgentInput`:

```kotlin
@Serializable
data class LorebookAgentInput(
    val turnsSinceLastUpdate: List<ConverseData>,
    val lastLorebookUpdateTurnIndex: Int,
    val currentLorebook: List<LoreBook>,
    val taskContext: LorebookTaskContext,
    val harnessGeneration: Long
)
```

**What the lorebook agent must output:**

A `LorebookAgentOutput` JSON object:

```kotlin
@Serializable
data class LorebookAgentOutput(
    val compactedThroughTurn: Int,
    val updates: List<LorebookUpdate>,
    val deletions: List<String>
)
```

Each `LorebookUpdate` carries `key`, `value`, `weight`, `linkedKeys`, `aliasKeys`, `requiredKeys`, and an `operation` (either `LorebookOperation.Merge` or `LorebookOperation.Replace`). The cursor is advanced to `compactedThroughTurn` on success; updates whose `compactedThroughTurn` is not strictly greater than the current cursor are discarded silently (pre-emption detection).

If the agent returns a `LoreBook` JSON object (or an array of them) without the typed envelope, the harness falls back to the legacy free-form path (`applyLorebookUpdates`).

The lorebook agent runs under `lorebookMutex` so concurrent turns queue their updates in chronological order.

### The Summary Agent Contract

**Type:** `P2PInterface?`
**Location:** `PumpStation.summaryAgent` / `setSummaryAgent(...)` / `PumpStationBuilder.summaryAgent`

The summary agent produces the `turnSummary` string injected at the top of the judge and dispatch user messages on subsequent turns.

**What the summary agent receives:**

`taskState.latestContent` (the most recent content object, typically the path's output).

**What the summary agent must output:**

A `MultimodalContent` whose `text` becomes the new `turnSummary`. The harness replaces the previous summary wholesale. There is no JSON contract — the agent is expected to produce prose.

The summary agent runs under `summaryMutex` so concurrent turns queue their updates in chronological order.

If the agent signals `terminatePipeline = true`, the harness records `lastError = P2PRequestInvalid` and skips the summary update. If the agent signals `passPipeline = true`, the harness skips the update but does not error.


## PathObject and PathRequest

`PathObject` is the harness's atom. A pump station owns a set of `PathObject` instances (in `pathList` and `reservePaths`), and the dispatch agent chooses one to invoke by name. The class lives in `Pipeline/PumpStation.kt:225`.

### PathObject Construction

```kotlin
val path = PathObject().apply {
    pathName = "researcher"
    pathDescription = "Performs a single research query and returns the result."
    pathSchema = "{\"query\": \"the question to research\"}"
    riskLevel = PathRiskLevel.Medium
    pcpSchema = PcpContext()  // optional
    setExecutionFunction { content, station, turnHistory, turnSummary ->
        // ... do work ...
        MultimodalContent(text = "research result", passPipeline = false)
    }
}
```

A path is only valid when it has at least one execution mechanism. The priorities at execution time are:

1. **PCP function** — if `pcpSchema.tpipeOptions` is non-empty and the dispatch content carries a `functionName`, the path delegates to `PcpExecutionDispatcher`
2. **Execution function** — `setExecutionFunction { ... }` is called directly with `(content, station, turnHistory, turnSummary)`
3. **Internal agent** — `setInternalAgent(agent)` is called via `agent.executeLocal(content)`. Can be any `P2PInterface` including another `PumpStation`
4. **Agent builder function** — `setAgentBuilderFunction { _ -> ... }` is called per invocation, producing a fresh agent. Useful for clean-slate stateless runs

If none of these are configured, `PathObject.init()` throws `IllegalStateException` at harness startup.

### PathDescriptionData

When the harness boots, every path runs `PathObject.init()`. The result is captured in a `PathDescriptionData` and stored in the path descriptor list. This is what the dispatch agent's system prompt sees:

```kotlin
@Serializable
data class PathDescriptionData(
    val name: String,
    val description: String,
    val inputSchema: String,
    val pcpSchema: PcpContext?,
    val hasInternalAgent: Boolean,
    val hasExecutionFunction: Boolean,
    val isRunsInBackground: Boolean,
    val agentTypeName: String? = null
)
```

`PathDescriptionList` is a wrapper that serializes the full set of visible paths to JSON for prompt injection:

```kotlin
@Serializable
data class PathDescriptionList(
    var paths: MutableList<PathDescriptionData> = mutableListOf()
)
```

### Binding a Function as a PCP Tool

`PathObject` can bind a Kotlin function so the dispatch agent sees it as a callable PCP tool. The pattern mirrors `Pipe.bindNativeFunction` from `PipeContextProtocol/PcpFunctionExtensions.kt`:

```kotlin
import com.TTT.Pipeline.bindFunction

val path = PathObject().apply {
    pathName = "code_review"
    pathDescription = "Process a code review for a given repository."
}
path.bindFunction("processCodeReview", ::processCodeReview)
```

The extension function `PathObject.bindFunction(name, function)`:

1. Calls `FunctionRegistry.registerFunction(name, function)` to create a `FunctionSignature` via reflection
2. Builds `TPipeContextOptions` from the signature (LLM-readable parameter schema: type, description, enumValues, isRequired)
3. Initializes `pcpSchema` if null and appends the new `tpipeOption` to it

The result is additive — multiple `bindFunction` calls accumulate options in `pcpSchema.tpipeOptions`. The schema is serialized into the dispatch agent's prompt context at injection time.

The path is then resolved at execution time by `PathObject.execute(content, ...)` which inspects `content.tools.tPipeContextOptions.functionName` and dispatches via `PcpExecutionDispatcher`.


## DSL Builder

If you want the pump station assembled, validated, and initialized in one place, prefer the Kotlin DSL:

```kotlin
import com.TTT.Pipeline.PumpStation
import com.TTT.Pipeline.pumpStation
import com.TTT.Pipeline.PumpStationMemoryManagementMode
import com.TTT.Pipe.MultimodalContent

val station = pumpStation("research-station") {
    personality = "You are a meticulous research analyst."
    systemTask = "Complete the user's research request to the highest standard."
    userGuidelines = "Cite every claim. Prefer primary sources."
    entryUserPrompt = "Research the history of distributed consensus algorithms."

    judgeAgent = Pipeline().apply {
        pipelineName = "judge"
        add(OpenRouterPipe().apply {
            setModel("openai/gpt-4o-mini")
            setApiKey(System.getenv("OPENROUTER_API_KEY") ?: "")
        })
    }

    dispatchAgent = Pipeline().apply {
        pipelineName = "dispatcher"
        add(OpenRouterPipe().apply {
            setModel("openai/gpt-4o-mini")
            setApiKey(System.getenv("OPENROUTER_API_KEY") ?: "")
        })
    }

    path("research") {
        description = "Performs a single research query."
        risk = PathRiskLevel.Low
        schema = "{\"query\": \"the question to research\"}"
        setExecutionFunction { content, station, history, summary ->
            // The harness fills content.text with the request schema
            // ... do work ...
            MultimodalContent(text = "research result").apply {
                passPipeline = true
            }
        }
    }

    setMaxHarnessTurns(10)
    setConcurrencyMode(PumpStationConcurrencyMode.Async)
    setMemoryManagementMode(PumpStationMemoryManagementMode.Compaction)
    setCompactionThreshold(0.8)
    setCompactionStrategy(PumpStationCompactionStrategy.Whole)
    setKillSwitch(KillSwitch(inputTokenLimit = 50_000, outputTokenLimit = 10_000))
}
```

The DSL returns a fully initialized `PumpStation` ready for `executeLocal`. The DSL stages are:

| Stage      | Description                                                                 |
|------------|-----------------------------------------------------------------------------|
| `Initial`  | Nothing configured yet. `pumpStation("name") { ... }` enters this stage.    |
| `HasPaths` | At least one `path { }` has been called. Required before `build()`.        |
| `Ready`    | All required and optional configuration is complete. `build()` is called.   |

The stage transitions are tracked by the `PumpStationStage` sealed class in `Pipeline/PumpStationDsl.kt:31`. The compiler enforces the "at least one path" requirement via the builder's generic type parameter — `build()` is only available on `PumpStationBuilder<PumpStationStage.HasPaths>`.


## DSL Block Reference

The `pumpStation { }` builder supports these top-level blocks and setters.

### Top-Level Configuration

| Property / Block         | Type                                       | Default | Description |
|--------------------------|--------------------------------------------|---------|-------------|
| `name`                   | `String`                                   | —       | Unique name for the station. Required. |
| `personality`            | `String`                                   | `""`    | Injected into judge and dispatch prompts ahead of all other instructions. |
| `systemTask`             | `String`                                   | `""`    | "System prompt" layer, second-priority. |
| `userGuidelines`         | `String`                                   | `""`    | Third-priority guidelines. Equivalent to "skills" in other harnesses. |
| `entryUserPrompt`        | `String`                                   | `""`    | Core task, fourth-priority. Set programmatically or via the input content. |
| `judgeRunMode`           | `PumpStationJudgeRunMode`                  | `Always`| `Always` (judge every turn) or `FlagTriggered` (judge only when `requestJudgeNextTurn()` is set). |
| `maxHarnessTurns`        | `Int`                                      | `50`    | Safety cap on loop iterations. |
| `maxTurns`               | `Int`                                      | `50`    | Canonical setter. `maxHarnessTurns` is a delegating alias. |
| `maxGoalFailAttempts`    | `Int`                                      | `3`     | Max consecutive goal-validation failures before `GoalValidationFailed` exit. |
| `maxRawTurnHistorySize`  | `Int?`                                     | `null`  | Cap on `rawTurnHistory.history`; null disables. |
| `blowoutThreshold`       | `Double`                                   | `0.9`   | Context-window fill ratio that triggers blowout detection. |
| `memoryUpdateTimeoutMs`  | `Long`                                     | `30_000L` | Timeout in ms for memory phase to await in-flight background jobs. |
| `maxBlowoutRecoveries`   | `Int`                                      | `3`     | Max blowout recovery attempts before forced halt. |
| `maxRepairPromptTokens`  | `Int`                                      | `500`   | Token cap for repair/regeneration prompts. |
| `stopHarnessOnInvalidPathRequest` | `Boolean`                         | `false` | Throw on parse failure instead of skipping the turn. |
| `failurePolicy`          | `PumpStationFailurePolicy`                 | defaults | See [Failure Policy](#failure-policy). |
| `concurrencyMode`        | `PumpStationConcurrencyMode?`              | `null`  | Default for harness agents; falls back to `Async`. |
| `maxConcurrentBackgroundAgents` | `Int`                               | `3`     | Cap on concurrent background agents. |
| `maxConcurrentForegroundAgents` | `Int`                               | `3`     | Cap on concurrent foreground agents (hint to paths). |
| `foregroundTurnInterval` | `Int`                                      | `0`     | Turns between foreground agent firings; 0 disables. |
| `backgroundTurnInterval` | `Int`                                      | `5`     | Turns between background agent firings. |
| `memoryManagementMode`   | `PumpStationMemoryManagementMode`          | `Compaction` | `Compaction`, `Truncation`, or `Hybrid`. |
| `compactionThreshold`    | `Double`                                   | `0.8`   | Context-window fill ratio that triggers compaction. |
| `compactionStrategy`     | `PumpStationCompactionStrategy`             | `Whole` | `Whole`, `Chunked`, or `Hybrid`. |
| `maxTurnHistorySize`     | `Int`                                      | `50`    | Cap on `turnHistory.history`; excess entries are summarized. |
| `judgeJsonContractEnabled` | `Boolean`                                | `true`  | When false, judge verdict comes from flags only. |
| `pathSafetyJsonContractEnabled` | `Boolean`                            | `true`  | When false, path-safety verdict comes from flags only. |
| `maxConsecutiveSamePath` | `Int`                                      | `3`     | Loop guard on consecutive same-path dispatch. |
| `maxTotalPathCallsPerPath` | `Int?`                                   | `null`  | Loop guard on total calls per path; null disables. |
| `pathLimitExceededPolicy` | `PathLimitExceededPolicy`                 | `Skip`  | `Skip`, `Halt`, or `Continue` when the per-path limit is hit. |

### Agent Setters

| Property | Type | Description |
|----------|------|-------------|
| `judgeAgent` | `P2PInterface?` | The judge. Should be a `Pipeline`. |
| `dispatchAgent` | `P2PInterface?` | **Required.** Must be a `Pipeline` (P2PInit throws otherwise). |
| `interventionAgent` | `P2PInterface?` | Reactive — fires after a path failure or loop guard trip. |
| `healthAgent` | `P2PInterface?` | Proactive — fires before the judge based on interval or error ratio. |
| `healthAgentTurnInterval` | `Int?` | Turns between health checks. |
| `healthAgentErrorRatioThreshold` | `Double?` | Error-ratio trigger. |
| `healthAgentConcurrencyMode` | `PumpStationConcurrencyMode?` | `Blocking` (judge waits) or `Async`. |
| `lorebookAgent` | `P2PInterface?` | Updates lorebook entries from recent turns. |
| `summaryAgent` | `P2PInterface?` | Generates `turnSummary`. |
| `goalAgent` | `P2PInterface?` | Validates work when judge says complete or path says pass. |
| `preInitAgent` | `P2PInterface?` | Runs once at startup before the main loop. |
| `pathSafetyAgent` | `P2PInterface?` | Gates medium/high risk path calls. |

Each agent has a matching `*BuilderFunction` setter for `(suspend (PumpStation) -> Pipeline)` (or `P2PInterface`) that produces a fresh agent per turn. When set, the builder function overrides the static agent reference.

### `path("name") { }` Block

Registers a `PathObject` in `pathList`. The block exposes:

| Member                       | Type                    | Description |
|------------------------------|-------------------------|-------------|
| `description`                | `String`                | Human-readable description; injected into the dispatch prompt. |
| `risk`                       | `PathRiskLevel`         | `Low`, `Medium`, or `High`. Triggers path-safety gate at Medium+. |
| `dispatchHint`               | `String`                | Soft guidance surfaced in the dispatch prompt as `"Hint: ..."`. |
| `runsInBackground`           | `Boolean`               | When true, the path runs in the background scheduler. |
| `schema`                     | `String`                | Free-form JSON schema for the path's input. |
| `pcpSchema`                  | `PcpContext?`           | Pre-built PCP context for PCP-bound paths. |
| `pathMetadata`               | `MutableMap<Any, Any>`  | Advisory metadata; travels with the path object. |
| `setInternalAgent(agent)`    | `(P2PInterface) -> Unit` | Set the path's internal agent (any P2PInterface, including another PumpStation). |
| `setExecutionFunction(fn)`  | `(... ) -> Unit`         | Set the path's execution function. |
| `bindFunction(function)`     | `(KFunction<*>) -> Unit` | Register a Kotlin function as a PCP tool under the function's own name. |
| `bindFunction(name, function)` | `(String, KFunction<*>) -> Unit` | Register a Kotlin function under a custom name. |
| `build()`                    | `() -> PathObject`      | Build and add the path. Called automatically at end of block. |

### `reservePath("name") { }` Block

Same surface as `path { }` but stores the `PathObject` in `reservePaths` instead of `pathList`. The reserve path is **not** visible to the dispatch agent until its `revealWhen` predicate evaluates to true. Sticky once revealed.

| Member | Type | Description |
|--------|------|-------------|
| `revealWhen(predicate)` | `(taskState, externalContext) -> Boolean` | Predicate evaluated each dispatch turn. |

### `tracing { }` Block

Configures `TraceConfig` for the station:

| Method | Description |
|--------|-------------|
| `enabled(enabled = true)` | Enable or disable tracing. Default enables. |
| `maxHistory(count)` | Max trace events retained per trace. |
| `outputFormat(format)` | `TraceFormat.HTML`, `JSON`, `MARKDOWN`, or `CONSOLE`. |
| `detailLevel(level)` | `TraceDetailLevel.VERBOSE`, `DETAILED`, `STANDARD`, etc. |
| `autoExport(enabled, path)` | Auto-export after each run to a path. |
| `includeContext(include)` | Include context snapshot on every event. |
| `includeMetadata(include)` | Include event metadata. |
| `config(configuration)` | Replace the entire configuration. |

### `killSwitch { }` Block

Configures the `KillSwitch`:

| Field | Type | Description |
|-------|------|-------------|
| `inputTokenLimit` | `Int?` | Max input tokens; null disables. |
| `outputTokenLimit` | `Int?` | Max output tokens; null disables. |
| `onTripped` | `(KillSwitchContext) -> Unit` | Callback on trip; default throws `KillSwitchException`. |

### `compaction { }` Block

A thin alias for the v3 compaction configuration:

| Field | Type | Description |
|-------|------|-------------|
| `mode` | `PumpStationMemoryManagementMode` | Read/write accessor for the builder's `memoryManagementMode`. |
| `compactionThreshold` | `Double` | Read/write accessor for the builder's `compactionThreshold`. |
| `strategy` | `PumpStationCompactionStrategy` | Read/write accessor for the builder's `compactionStrategy`. |

### `pause { }` Block

Configures pause phases for external inspection/intervention:

```kotlin
pumpStation("name") {
    pause { phase(PumpStationPausePhase.BeforeJudge) }
    pause { phase(PumpStationPausePhase.BeforeGoalValidation) }
}
```

The harness suspends at the named phase boundaries until `resume()` is called.

### `harnessAgent { }` and `harnessAgentBuilder { }` Blocks

Register additional agents that fire during the foreground or background phase. Each block produces a `HarnessAgentSlot`:

| Member | Type | Description |
|--------|------|-------------|
| `agent` | `P2PInterface?` | The agent instance. |
| `builderFunction` | `(suspend (PumpStation) -> P2PInterface)?` | Builder producing a fresh agent per turn. |
| `concurrency` | `PumpStationConcurrencyMode` | `Blocking` (foreground) or `Async` (background). |
| `interval` | `Int` | Turns between firings. |

The blocking slots fire synchronously during the foreground phase. The async slots are queued and run as coroutines during the background phase.

### `memory { }` Block

Read/write accessors for memory-management fields. See `compaction { }` — same shape.

### `pipelineNames { }` Block

A scope for declaring named pipe references that can be re-bound in the path block.

### `dispatcherRules { }` Block

Reserved for future use. Currently a placeholder. Path selection today is fully driven by the dispatch agent's `PathRequest` output.

### Failure Policy

```kotlin
@Serializable
data class PumpStationFailurePolicy(
    var repairInvalidDispatchJson: Boolean = true,
    var maxDispatchRepairAttempts: Int = 1,
    var stashOversizedOutputs: Boolean = true,
    var callInterventionOnPathFailure: Boolean = true,
    var stopHarnessOnInvalidPathRequest: Boolean = false
)
```

| Field | Default | Description |
|-------|---------|-------------|
| `repairInvalidDispatchJson` | `true` | Repair malformed dispatch output up to `maxDispatchRepairAttempts` times. |
| `maxDispatchRepairAttempts` | `1` | Max repair attempts. |
| `stashOversizedOutputs` | `true` | Stash oversized path outputs to keep prompt budgets bounded. |
| `callInterventionOnPathFailure` | `true` | Invoke `interventionAgent` after a path failure. |
| `stopHarnessOnInvalidPathRequest` | `false` | When true, set `lastError = DispatchJsonRepairFailed` after repair budget is exhausted. |


## Execution Flow: The Two-Scope Loop

PumpStation's loop has two scopes. Confusing them is the most common documentation error.

### Outer loop: `runHarnessLoop`

`runHarnessLoop` drives turns via `while (turnIndex < maxTurns && status == Running)`. Each iteration calls `runTurn()`. After the while loop exits, it runs `runFinalizationPhase()` once.

```kotlin
internal suspend fun PumpStation.runHarnessLoop(): KillSwitchException? {
    var tripException: KillSwitchException? = null
    while (taskState.turnIndex < maxTurns && taskState.status == PumpStationStatus.Running) {
        if (!checkPauseGuards(PumpStationPausePhase.BeforeJudge)) break
        val result = try { runTurn() }
        catch (e: KillSwitchException) {
            taskState.lastError = PumpStationError.KillSwitchTripped
            taskState.exitReason = PumpStationExitReason.KillSwitchTripped
            tripException = e
            break
        }
        if (result is TurnResult.Halt) {
            taskState.exitReason = result.reason
            break
        }
        taskState.turnIndex++
    }
    if (taskState.turnIndex >= maxTurns && taskState.lastError == null) {
        taskState.lastError = PumpStationError.MaxTurnsExceeded
        taskState.exitReason = PumpStationExitReason.MaxTurnsHit
    }
    return tripException
}
```

`TurnResult` is a sealed class in `Pipeline/PumpStationModels.kt:844`:

```kotlin
sealed class TurnResult {
    object Continue : TurnResult()
    data class Halt(val reason: PumpStationExitReason) : TurnResult()
}
```

### Inner cycle: `runTurn`

`runTurn` runs the per-turn phases and returns `Continue` or `Halt(reason)`. The function lives at `Pipeline/PumpStationLoop.kt:1898`.

```kotlin
internal suspend fun PumpStation.runTurn(): TurnResult {
    refreshAgentInstances()
    refreshPipelinesPrompts()
    refreshSettingsPropagation()

    runHealthCheckPhase()
    detectAndHandleContextBlowout(PumpStationPhase.HealthCheck)

    val judgeVerdict = runJudgePhase()
    detectAndHandleContextBlowout(PumpStationPhase.Judge)
    if (judgeVerdict.shouldHalt) {
        return TurnResult.Halt(judgeVerdict.reason ?: PumpStationExitReason.TerminateSignal)
    }
    if (judgeVerdict.isComplete) return runExitFlow()

    val pathRequest = runDispatchPhase() ?: return TurnResult.Continue
    detectAndHandleContextBlowout(PumpStationPhase.Dispatch)
    if (pathRequest.pathName.isBlank()) return TurnResult.Continue

    if (!checkPauseGuards(PumpStationPausePhase.BeforePathExecution)) {
        return TurnResult.Halt(PumpStationExitReason.KillSwitchTripped)
    }
    val pathResult = runPathFlow(pathRequest)
    detectAndHandleContextBlowout(PumpStationPhase.PathExecution)

    if (pathResult != null) {
        taskState.latestContent = pathResult
        if (pathResult.passPipeline) {
            return if (goalAgent == null) {
                TurnResult.Halt(PumpStationExitReason.PassSignal)
            } else {
                runExitFlow()
            }
        }
        if (pathResult.terminatePipeline) {
            return TurnResult.Halt(PumpStationExitReason.TerminateSignal)
        }
    }
    pruneTurnHistory()
    pruneRawTurnHistory()

    runForegroundAgentsPhase()
    detectAndHandleContextBlowout(PumpStationPhase.ForegroundAgents)

    runBackgroundAgentsPhase()
    runMemoryUpdatePhase()
    runCompactionPhase()

    return TurnResult.Continue
}
```

### Goal-validation transition: `runExitFlow`

`runExitFlow` is the goal-validation phase. It is called by `runTurn` when the judge says complete OR when a path returns `passPipeline: true` and a goal agent is configured. With no goal agent, the loop exits with `JudgeComplete` or `PassSignal` and the goal gate is skipped.

```kotlin
internal suspend fun PumpStation.runExitFlow(): TurnResult {
    if (!checkPauseGuards(PumpStationPausePhase.BeforeGoalValidation)) {
        return TurnResult.Halt(PumpStationExitReason.KillSwitchTripped)
    }
    if (goalAgent == null) {
        return TurnResult.Halt(PumpStationExitReason.JudgeComplete)
    }

    val agent = goalAgentBuilderFunction?.invoke(this) ?: goalAgent!!
    agent.setParentInterface(this)
    agent.P2PInit()
    refreshPipelinesPrompts()

    emitEventInternal(GoalValidationStarted(...))
    val goalContent = buildGoalContent()
    val result = agent.executeLocal(goalContent)
    val passed = !result.terminatePipeline
    emitEventInternal(GoalValidationCompleted(..., passed = passed, ...))

    if (!passed) {
        turnHistory.add(ConverseData(role = ConverseRole.assistant, content = result))
        taskState.goalFailCount++
        if (taskState.goalFailCount > maxGoalFailAttempts) {
            return TurnResult.Halt(PumpStationExitReason.GoalValidationFailed)
        }
        return TurnResult.Continue
    }

    return TurnResult.Halt(PumpStationExitReason.JudgeComplete)
}
```

The judge saying `isComplete: true` is **not** a terminal signal. It is a transition into goal validation. With no goal agent, the harness exits. With a goal agent, the goal validates — pass means deliver, fail means re-loop with the goal's critique appended to history (up to `maxGoalFailAttempts`).

### Finalization: `runFinalizationPhase`

After the while loop exits, `runFinalizationPhase` runs once. It drains background events, awaits in-flight background jobs (bounded by `memoryUpdateTimeoutMs`), runs a final compaction if the fill ratio is still above `compactionThreshold`, emits either `HarnessCompleted` or `HarnessFailed`, and returns the harness's deliverable.

```kotlin
internal suspend fun PumpStation.runFinalizationPhase(): MultimodalContent {
    drainBackgroundEventQueue()
    withTimeoutOrNull(memoryUpdateTimeoutMs) { backgroundJobs.forEach { it.join() } }
    backgroundJobs.clear()
    if (contextFillRatio() > compactionThreshold) runCompactionPhase()

    val isFailure = taskState.lastError in listOf(
        PumpStationError.MaxTurnsExceeded,
        PumpStationError.KillSwitchTripped,
        PumpStationError.P2PRequestInvalid,
        PumpStationError.InitNotCalled,
        PumpStationError.CompactionInflated
    )
    if (isFailure) {
        emitEventInternal(HarnessFailed(...))
        taskState.status = PumpStationStatus.Failed
    } else {
        emitEventInternal(HarnessCompleted(...))
        taskState.status = PumpStationStatus.Completed
    }

    if (tracingEnabled && RemoteTraceConfig.dispatchAutomatically) {
        PipeTracer.exportTrace(taskState.runId, TraceFormat.HTML)
    }

    return taskState.lastPathResult ?: (taskState.latestContent ?: MultimodalContent())
}
```

`executeLocal` then re-throws the `KillSwitchException` (if any) so the caller sees the trip, and otherwise returns the finalization's content.

### Pre-Init Advisory

`runPreInitPhase` emits a `HarnessWarning` event with `code = NoExitSignalConfigured` when **all** of these are true:

- `judgeAgent == null` AND `judgeAgentBuilderFunction == null`
- `judgeRunModeInternal != PumpStationJudgeRunMode.FlagTriggered`
- `maxTurnsInternal > 1`

The advisory is non-blocking — the harness continues. The `mechanisms` field of the `HarnessWarning` lists the four legitimate exit mechanisms (`JudgeAlways`, `JudgeFlagTriggered`, `PathPassPipeline`, `PathTerminatePipeline`).


## Phase Reference

| `PumpStationPhase`   | When                                                  | What runs |
|----------------------|-------------------------------------------------------|-----------|
| `PreInit`            | Once at start                                         | `preInitAgent`, `preInitFunction`, then `HarnessStarted` event |
| `HealthCheck`        | Top of each turn (if interval or error ratio hit)      | `healthAgent` |
| `Judge`              | Top of each turn (or skipped in FlagTriggered)        | `judgeAgent` |
| `Dispatch`           | After judge, if not complete and not halt             | `dispatchAgent` |
| `PathSafety`         | For medium/high risk paths                            | `pathSafetyAgent` (or `pathSafetyFunction`) |
| `PathExecution`      | After dispatch + safety                               | `PathObject.execute` |
| `PathValidation`     | After path returns                                    | `pathValidationFunction` |
| `Intervention`       | After path failure or loop guard trip                 | `interventionAgent` |
| `ForegroundAgents`   | After path execution (if interval hit)                | Blocking harness agent slots |
| `MemoryUpdate`       | After foreground (if interval hit or pressure)        | `lorebookAgent`, `summaryAgent` |
| `Compaction`         | After memory update (if fill ratio hit)               | `summaryAgent` (compaction strategy) |
| `GoalValidation`     | Inside `runExitFlow`                                  | `goalAgent` |
| `Exit`               | Final event (`HarnessCompleted` or `HarnessFailed`)   | none |

`PumpStationPhase` is an enum in `Pipeline/PumpStationModels.kt:37`. Each `PumpStationEvent` carries the phase that produced it; the trace funnel maps it to a more general `TracePhase` (see `Pipeline/PumpStationHelpers.kt:435`).


## Memory Management

PumpStation supports three memory management modes:

| Mode | Behavior |
|------|----------|
| `Compaction` | Default. When `contextFillRatio() > compactionThreshold`, the summary agent condenses `turnHistory` into a new summary. |
| `Truncation` | Leverages TPipe's `TokenBudget` and lorebook selection. Truncates context and selects lorebook entries to fit. |
| `Hybrid` | Both — deployed intelligently when context suddenly explodes before background agents can catch up. |

`PumpStationMemoryManagementMode` is in `Pipeline/PumpStation.kt:70`. When the harness is configured with a lorebook or summary agent, `P2PInitInternal` auto-promotes `Compaction` → `Hybrid` if the developer did not explicitly set the mode.

### Compaction Strategies

`PumpStationCompactionStrategy` (`Pipeline/PumpStation.kt:92`):

| Strategy | Behavior |
|----------|----------|
| `Whole` | Entire `turnHistory` is passed to the summary agent. Requires it to fit in the agent's context window. On overflow, `branchFailureFunction` is called. |
| `Chunked` | History is split into token-bounded chunks. Each chunk is summarized (sequentially with running summary, or in parallel with bounded concurrency), then folded. Survives very large contexts. |
| `Hybrid` | Dynamically chooses between `Whole` and `Chunked` based on headroom. |

`ChunkFanoutMode` (`Pipeline/PumpStationV3Models.kt:45`):

| Mode | Behavior |
|------|----------|
| `Sequential` | Default. Each chunk's summary carries forward the previous chunk's running summary. Causal order preserved. |
| `Parallel` | Chunks summarized concurrently with bounded concurrency (`maxParallelChunks`). All chunks folded by a second summary call. Cancellation through `coroutineScope` propagates on kill-switch trip. |

### Compaction Result Sealed Type

`CompactionResult` (`Pipeline/PumpStationV3Models.kt:117`) communicates the outcome of every compaction attempt:

```kotlin
sealed class CompactionResult {
    object SkippedBelowThreshold : CompactionResult()
    object SkippedNoAgent : CompactionResult()
    object SkippedCursorAlreadyAdvanced : CompactionResult()
    data class Applied(val inputTokens: Int, val outputTokens: Int, val generation: Long, val fanout: ChunkFanoutMode? = null) : CompactionResult()
    data class Inflated(val inputTokens: Int, val outputTokens: Int, val attempt: Int) : CompactionResult()
    data class DiscardedPreEmpted(val observedGeneration: Long, val currentGeneration: Long) : CompactionResult()
    data class RolledBack(val backupGeneration: Long, val reason: String) : CompactionResult()
    data class HandedOffToTruncation(val contextWindowBefore: Int, val contextWindowAfter: Int) : CompactionResult()
}
```

- `SkippedBelowThreshold` — fill ratio did not exceed `compactionThreshold`
- `SkippedNoAgent` — no summary agent bound
- `SkippedCursorAlreadyAdvanced` — another compaction already covered this range
- `Applied` — `turnHistory` was replaced with the summary
- `Inflated` — LLM summary was larger than input; orchestrator restores backup and retries with smaller scope
- `DiscardedPreEmpted` — cursor moved while LLM was in flight; result dropped without mutation
- `RolledBack` — DITL hook or orchestrator restored a backup
- `HandedOffToTruncation` — retry budget exhausted; failure policy's truncation path takes over

### Cursors

Pre-emption detection relies on two cursors:

```kotlin
@Serializable
data class CompactionCursor(
    val generation: Long = 0L,
    val lastCompactedTurnIndex: Int = -1,
    val lastCompactionStrategy: PumpStationCompactionStrategy? = null,
    val lastCompactionInputTokens: Int = 0,
    val lastCompactionOutputTokens: Int = 0,
    val lastCompactionTimestamp: Long = 0L,
    val lastFanoutMode: ChunkFanoutMode? = null
)

@Serializable
data class LorebookCursor(
    val lastUpdatedTurnIndex: Int = -1,
    val lastUpdateTimestamp: Long = 0L,
    val lastUpdateGeneration: Long = 0L
)
```

A strategy function captures the current `generation` on entry, performs the LLM work, and CAS-applies the result only if the generation is still unchanged when the LLM returns. If a concurrent compaction has advanced the generation, the first caller's work is stale and is returned as `DiscardedPreEmpted` without mutating `turnHistory`.

### Backups

```kotlin
@Serializable
data class CompactionBackup(
    val generation: Long,
    val turnIndex: Int,
    val turnHistory: List<ConverseData>,
    val latestContent: MultimodalContent?,
    val contextWindow: ContextWindow,
    val miniBank: MiniBank,
    val createdAt: Long = System.currentTimeMillis()
)
```

Captured before every compaction attempt; pushed to a ring buffer bounded by `maxCompactionBackups` (default 3). Used by `restoreFromBackup` to roll back `Inflated` or pre-empted attempts.


## Concurrency Modes

`PumpStationConcurrencyMode` (`Pipeline/PumpStation.kt:49`):

| Mode | Behavior |
|------|----------|
| `Async` | Background agents fire as soon as possible; queued via `backgroundMutex`. Harness advances without waiting. |
| `Blocking` | Each background agent blocks the harness until completion; agents run in sequence. |

Only some harness agents can run as `Async`. The path-safety, judge, dispatch, goal, and intervention agents are always `Blocking` by design.

`concurrencyMode` is the default for harness agent slots. Individual slots can override via `setConcurrencyMode(...)` on the slot.

### Foreground vs Background Agent Slots

`HarnessAgentSlot` (`Pipeline/PumpStationModels.kt:855`):

```kotlin
data class HarnessAgentSlot(
    val agent: P2PInterface?,
    val concurrency: PumpStationConcurrencyMode,
    val builderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null
)
```

- **Blocking slots** fire synchronously during the foreground phase, in registration order. The harness awaits each result.
- **Async slots** are queued via `backgroundMutex` and launched on `GlobalScope`. The harness continues to the next phase. Failures are isolated.

Both slot types respect `interval` — a slot with `interval = 5` only fires every 5 turns (modulo). The `foregroundTurnInterval` and `backgroundTurnInterval` builder fields are shorthand for setting intervals on the default slot list.

### Background Jobs and Mutexes

The file-scope `backgroundJobs`, `backgroundMutex`, `lorebookMutex`, and `summaryMutex` are all declared in `Pipeline/PumpStationLoop.kt:464`. The lorebook and summary mutexes serialize their respective agent invocations to keep updates chronological; the background mutex serializes enqueueing (so concurrent turns do not race on the jobs list).

The lorebook agent is invoked under `lorebookMutex.withLock` in `updateLorebook` (`PumpStationLoop.kt:1336`). The summary agent under `summaryMutex.withLock` in `updateSummary` (`PumpStationLoop.kt:1504`).


## DITL Hooks

PumpStation exposes six Developer-in-the-Loop hooks plus three agent-level functions. The full lifecycle:

| Hook | Type | When it fires | Receives | Return |
|------|------|---------------|----------|--------|
| `preInitFunction` | `(suspend (MultimodalContent, PumpStation) -> MultimodalContent)?` | Once at startup, after `preInitAgent` | Original content | (Optional) transformed content |
| `preValidationJudgeFunction` | `(suspend (MultimodalContent, MiniBank, PumpStation) -> MiniBank)?` | Before judge fires | Base input + current MiniBank | Replacement MiniBank |
| `preInvokeFunction` | `(suspend (ContextWindow, MiniBank, PumpStation) -> Boolean)?` | Before judge fires | Context + MiniBank | `false` to abort the run with `InterventionTerminated` |
| `preValidationDispatchFunction` | `(suspend (MultimodalContent, ContextWindow, MiniBank, PumpStation) -> MiniBank)?` | Before dispatch fires | Base input + ContextWindow + MiniBank | Replacement MiniBank |
| `postGenerateFunction` | `(suspend (MultimodalContent, PumpStation) -> P2PInterface)?` | After dispatch produces a path request | Dispatch result | Optional `P2PInterface` surfaced in metadata |
| `pathValidationFunction` | `(suspend (MultimodalContent, PumpStation) -> Boolean)?` | After path executes | Path result | `false` to reject the result; original input flows through |
| `pathTransformationFunction` | `(suspend (MultimodalContent, PumpStation) -> MultimodalContent)?` | After path validation passes | Path result | Replacement content for `turnHistory` |
| `postMemoryFunction` | `(suspend (MultimodalContent, PumpStation) -> MultimodalContent)?` | After lorebook/summary agents | Latest content | Replacement content |
| `preCompactionFunction` | `(suspend (MultimodalContent, ConverseData, ConverseHistory, PumpStation) -> MultimodalContent)?` | Before compaction | Latest content + overflow turn + history | Replacement content (advisory) |
| `postCompactionFunction` | `(suspend (MultimodalContent, ConverseHistory, PumpStation) -> MultimodalContent)?` | After compaction | Latest content + new history | Replacement content (advisory) |
| `onContextTruncated` | `(suspend (Boolean, Int) -> Unit)?` | When an agent truncates due to token budgeting | Whether truncated + free space | none |
| `pathSafetyFunction` | `(suspend (PathObject, String, PumpStation) -> Boolean)?` | For medium/high risk paths (alternative to `pathSafetyAgent`) | Path + schema | `true` to approve, `false` to reject |
| `pathLimitExceededFunction` | `(suspend (PathObject, String, PumpStation) -> PathLimitExceededResult)?` | When `maxTotalPathCallsPerPath` is exceeded | Path + reason | Dynamic policy result |
| `compactionRolledBackFunction` | `(suspend (CompactionBackup, String, PumpStation) -> CompactionBackup?)?` | When a backup is restored | Backup + reason | Replacement backup (or null to use as-is) |
| `externalContextProvider` | `((PumpStationTaskState) -> MutableMap<String, Any>)?` | Per dispatch turn (passed to reserve path `revealWhen`) | Task state | External context map |

The DITL hook order per turn:

1. `preInvokeFunction` (gates the turn)
2. `preValidationJudgeFunction`
3. `judgeAgent` runs
4. `preValidationDispatchFunction`
5. `dispatchAgent` runs
6. `postGenerateFunction` (surfaces optional agent in metadata)
7. (if path selected) `pathSafetyFunction` / `pathSafetyAgent` for medium/high risk
8. `PathObject.execute`
9. `pathValidationFunction` (gates result)
10. `pathTransformationFunction`
11. result appended to `turnHistory` and `rawTurnHistory`
12. (foreground phase) blocking harness agent slots
13. (memory phase) `postMemoryFunction`
14. (compaction phase) `preCompactionFunction`, then `summaryAgent`, then `postCompactionFunction`

Setters on `PumpStation`:

```kotlin
fun setPreInitFunction(fn: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)?): PumpStation
fun setPreValidationJudgeFunction(fn: (suspend (MultimodalContent, MiniBank, PumpStation) -> MiniBank)?): PumpStation
fun setPreInvokeFunction(fn: (suspend (ContextWindow, MiniBank, PumpStation) -> Boolean)?): PumpStation
fun setPreValidationDispatchFunction(fn: (suspend (MultimodalContent, ContextWindow, MiniBank, PumpStation) -> MiniBank)?): PumpStation
fun setPostGenerateFunction(fn: (suspend (MultimodalContent, PumpStation) -> P2PInterface)?): PumpStation
fun setPathValidationFunction(fn: (suspend (MultimodalContent, PumpStation) -> Boolean)?): PumpStation
fun setPathTransformationFunction(fn: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)?): PumpStation
fun setPostMemoryFunction(fn: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)?): PumpStation
fun setPreCompactionFunction(fn: (suspend (MultimodalContent, ConverseData, ConverseHistory, PumpStation) -> MultimodalContent)?): PumpStation
fun setPostCompactionFunction(fn: (suspend (MultimodalContent, ConverseHistory, PumpStation) -> MultimodalContent)?): PumpStation
fun setOnContextTruncated(fn: (suspend (Boolean, Int) -> Unit)?): PumpStation
fun setPathSafetyFunction(fn: (suspend (PathObject, String, PumpStation) -> Boolean)?): PumpStation
fun setPathLimitExceededFunction(fn: (suspend (PathObject, String, PumpStation) -> PathLimitExceededResult)?): PumpStation
fun setCompactionRolledBackFunction(fn: (suspend (CompactionBackup, String, PumpStation) -> CompactionBackup?)?): PumpStation
fun setExternalContextProvider(provider: ((PumpStationTaskState) -> MutableMap<String, Any>)?): PumpStation
```

All setters return `this` for chaining. The DSL exposes these as `setXyz { ... }` builder setters via the top-level `PumpStationBuilder`.


## Path Risk Levels

`PathRiskLevel` (`Pipeline/PumpStation.kt:132`):

| Level | Behavior |
|-------|----------|
| `Low` | Path runs without safety gate. Default for every path. |
| `Medium` | Path-safety agent / function fires before the path runs. |
| `High` | Path-safety gate fires. The path is rejected on `safe: false`. |

The safety gate calls `pathSafetyFunction` if set, then falls back to `pathSafetyAgent`. If neither is set, paths above `Low` risk are approved automatically. To force a manual review for medium/high risk paths, configure at least one of them.

Risk level is set on the path object:

```kotlin
path("dangerous") {
    risk = PathRiskLevel.High
    // ...
}
```


## Loop Guards

Two guards run before each path call:

### `maxConsecutiveSamePath`

Default `3`. When the dispatch agent selects the same path `N` consecutive turns, the harness emits a `LoopGuardTripped` event, optionally invokes `interventionAgent` (if set), and proceeds with the call. The developer can detect this in DITL hooks or in their intervention agent.

### `maxTotalPathCallsPerPath`

Default `null` (disabled). When set, after the call count for a path exceeds the cap, the harness emits `LoopGuardTripped` and consults the policy:

| `PathLimitExceededPolicy` | Behavior |
|---------------------------|----------|
| `Skip` | Move the path to `reservePaths` (effectively hiding it from dispatch). The path is no longer visible until revealed. |
| `Halt` | Set `lastError = MaxTurnsExceeded` and return the input unchanged. The finalization phase emits a `HarnessFailed` event. |
| `Continue` | Log the violation via a `PathFailed` event and proceed with the call anyway. |

The `pathLimitExceededFunction` DITL hook can override the static policy with a dynamic result.

Both loop guard events are emitted on `PumpStationPhase.PathExecution`.


## Reserve Paths

Reserve paths are stored in `reservePaths` and are not visible to the dispatch agent until their `revealWhen` predicate evaluates to true. This is useful for:

- Token-cost control: a rarely-needed path is hidden from the dispatch prompt until the situation warrants it
- Capability gating: a path only becomes available after a specific event in the harness
- Progressive disclosure: a path's description can be revealed based on the current turn index, error ratio, or task state

```kotlin
reservePath("sandboxed-shell") {
    description = "Runs a shell command in a sandbox."
    risk = PathRiskLevel.High
    revealWhen { taskState, _ ->
        taskState.turnIndex > 3 && taskState.lastSelectedPathName != "sandboxed-shell"
    }
    setExecutionFunction { content, station, _, _ ->
        // ... run shell command ...
        MultimodalContent(text = "shell output")
    }
}
```

Once a reserve path is revealed, it stays visible for the remainder of the run (sticky). The `getVisiblePathDescriptorsInternal` method (`Pipeline/PumpStation.kt:1739`) is responsible for filtering reserve paths based on `revealWhen`. The set of revealed reserve paths is tracked in the internal `revealedReservePaths` set.

A `ReservePathRevealed` event is emitted on the first turn a reserve path becomes visible, with `pathName` and the full list of reserve paths.


## Stash, Snapshots, and Pause/Resume

### Stash

`PumpStation.stash: MutableMap<String, ConverseData>` is an in-memory escape hatch for content that would blow out the prompt budget. DITL hooks (typically `pathTransformationFunction`) can move oversized content into the stash and replace the `turnHistory` entry with a placeholder. A path designed to handle the stashed data can call `station.retrieveStash(stashId)` to pull it back.

The stash manifest (`station.getStashManifest(): List<StashEntry>`) surfaces rich metadata without loading the full content:

```kotlin
@Serializable
data class StashEntry(
    val id: String,
    val sourcePath: String?,
    val createdTurn: Int,
    val reason: StashReason,
    val tokenEstimate: Int?,
    val byteSize: Long?,
    val preview: String
)
```

`StashReason` (`Pipeline/PumpStationModels.kt:115`):

```kotlin
enum class StashReason {
    TokenOverflow,
    BinaryPayload,
    ErrorLog,
    UnsafeForPrompt,
    DeveloperRequested,
    BackgroundResult
}
```

A path can reach back into the stash via the extension `PathObject.getStashContent(stashId, station)` (`Pipeline/PumpStationPathObjectExtensions.kt:55`).

### Snapshots

`saveSnapshot()` and `restoreSnapshot(snapshot)` are the high-risk-boundary save/restore. The snapshot captures the full task state plus the curated and raw history, context window, MiniBank, and stash manifest.

```kotlin
@Serializable
data class PumpStationSnapshot(
    val taskState: PumpStationTaskState,
    val turnHistory: ConverseHistory,
    val rawTurnHistory: ConverseHistory,
    val turnSummary: String,
    val contextWindow: ContextWindow,
    val miniBank: MiniBank,
    val stashManifest: List<StashEntry>,
    val visiblePathNames: List<String>,
    val reservePathNames: List<String>
)
```

After `restoreSnapshot`, `harnessIsReady` is set to `true` and a `HarnessResumed` event is emitted.

### Pause and Resume

`pauseAt(vararg phases)` registers pause phases. The harness calls `checkPauseGuards(phase)` at the named phase boundaries; if the current phase is in the pause set, the harness emits `HarnessSuspended` and suspends on a rendezvous channel.

`resume()` clears the pause phases, emits `HarnessResumed`, and sends a signal to the suspended `checkPauseGuards` call.

Pause phases: `BeforeJudge`, `AfterJudge`, `BeforeDispatch`, `AfterDispatch`, `BeforePathSafety`, `BeforePathExecution`, `AfterPathExecution`, `BeforeMemoryUpdate`, `BeforeCompaction`, `BeforeGoalValidation`, `BeforeExit`.


## Tracing Support

PumpStation tracing is keyed by `taskState.runId` (e.g. `ps-1718371200000-1234`). Every `PumpStationEvent` is mirrored into the global `PipeTracer` when tracing is enabled. The trace funnel (`Pipeline/PumpStationHelpers.kt:75`) maps each sealed event subtype to a `PUMP_STATION_*` `TraceEventType`.

The visualizer reads the `phase` and `turnIndex` from the trace event metadata, so each event carries a consistent `turnIndex` regardless of where it was emitted. The original `PumpStationPhase` is preserved in `metadata["phase"]` even when the `TracePhase` is a more generic bucket.

Trace events emitted by PumpStation:

| `TraceEventType` | Emitted on | Metadata fields |
|------------------|------------|-----------------|
| `PUMP_STATION_STARTED` | `HarnessStarted` and `PreInitCompleted` | runId, turnIndex, phase |
| `PUMP_STATION_HARNESS_WARNING` | `HarnessWarning` | warningCode, mechanisms |
| `PUMP_STATION_COMPLETED` | `HarnessCompleted` | runId, turnIndex, exitReason, finalOutput |
| `PUMP_STATION_FAILED` | `HarnessFailed` | error, errorMessage, exitReason |
| `PUMP_STATION_SUSPENDED` | `HarnessSuspended` | pausedAt, reason |
| `PUMP_STATION_RESUMED` | `HarnessResumed` | runId, turnIndex, phase |
| `PUMP_STATION_HEALTH_CHECK_STARTED` | `HealthCheckStarted` | runId, turnIndex |
| `PUMP_STATION_HEALTH_CHECK_COMPLETED` | `HealthCheckCompleted` | status, warnings, terminateHarness |
| `PUMP_STATION_JUDGE_STARTED` | `JudgeStarted` | runId, turnIndex |
| `PUMP_STATION_JUDGE_SKIPPED` | `JudgeSkipped` | reason, judgeRunMode |
| `PUMP_STATION_JUDGE_COMPLETED` | `JudgeCompleted` | isComplete, shouldTerminate, contentPreview, token usage |
| `PUMP_STATION_DISPATCH_STARTED` | `DispatchStarted` | runId, turnIndex |
| `PUMP_STATION_DISPATCH_COMPLETED` | `DispatchCompleted` | selectedPathName, pathRequest, contentPreview, token usage |
| `PUMP_STATION_PATH_SELECTED` | `PathSelected` | pathName, riskLevel |
| `PUMP_STATION_PATH_SAFETY_STARTED` | `PathSafetyStarted` | pathName, riskLevel |
| `PUMP_STATION_PATH_SAFETY_COMPLETED` | `PathSafetyCompleted` | pathName, riskLevel, approved, reason |
| `PUMP_STATION_PATH_STARTED` | `PathStarted` | pathName, riskLevel |
| `PUMP_STATION_PATH_COMPLETED` | `PathCompleted` | pathName, riskLevel, contentPreview, token usage |
| `PUMP_STATION_PATH_FAILED` | `PathFailed` | pathName, riskLevel, error, errorMessage |
| `PUMP_STATION_PATH_HIDDEN` | `PathHidden` | pathName, reason |
| `PUMP_STATION_PATH_VALIDATION_COMPLETED` | `PathValidationCompleted` | pathName, approved, reason |
| `PUMP_STATION_INTERVENTION_STARTED` | `InterventionStarted` | pathName, trigger |
| `PUMP_STATION_INTERVENTION_COMPLETED` | `InterventionCompleted` | nudges, shouldContinue, contentPreview, token usage |
| `PUMP_STATION_FOREGROUND_AGENT_COMPLETED` | `ForegroundAgentCompleted` | agentName, contentPreview, token usage |
| `PUMP_STATION_MEMORY_UPDATE_STARTED` | `MemoryUpdateStarted` | memoryMode |
| `PUMP_STATION_MEMORY_UPDATE_COMPLETED` | `MemoryUpdateCompleted` | memoryMode, compactionPercent, loreBookActive, summaryActive |
| `PUMP_STATION_STASH_CREATED` | `StashCreated` | stashId, sourcePath, reason, tokenEstimate |
| `PUMP_STATION_COMPACTION_STARTED` | `CompactionStarted` | strategy, memoryMode |
| `PUMP_STATION_COMPACTION_COMPLETED` | `CompactionCompleted` and `CompactionAttemptCompleted` | strategy, memoryMode, attempt, fanout, result |
| `PUMP_STATION_COMPACTION_INFLATED` | `CompactionInflated` | inputTokens, outputTokens, attempt, willRetry |
| `PUMP_STATION_COMPACTION_ROLLED_BACK` | `CompactionRolledBack` | backupGeneration, reason |
| `PUMP_STATION_COMPACTION_HANDED_OFF` | `CompactionHandedOffToTruncation` | contextWindowBefore, contextWindowAfter |
| `PUMP_STATION_GOAL_VALIDATION_STARTED` | `GoalValidationStarted` | runId, turnIndex |
| `PUMP_STATION_GOAL_VALIDATION_COMPLETED` | `GoalValidationCompleted` | passed, reason |
| `PUMP_STATION_RESERVE_PATH_REVEALED` | `ReservePathRevealed` | pathName, reservePathNames |
| `PUMP_STATION_LOOP_GUARD_TRIPPED` | `LoopGuardTripped` | guard, pathName, detail |
| `PUMP_STATION_CONTEXT_BLOWOUT_DETECTED` | `ContextBlowoutDetected` | fillRatio, threshold, afterPhase |
| `PUMP_STATION_BACKGROUND_AGENT_QUEUED` | `BackgroundAgentQueued` | agentName |
| `PUMP_STATION_NESTED_P2P_COMPLETED` | `NestedP2PCompleted` | pathName, agentName, contentPreview, token usage |

Enable tracing:

```kotlin
val station = pumpStation("name") {
    // ...
    tracing {
        enabled()
        outputFormat(TraceFormat.HTML)
        autoExport(enabled = true, path = "~/.tpipe-traces/")
    }
}
```

Or programmatically: `station.enableTracing(TraceConfig(enabled = true))`. `getTraceReport(format)` returns the rendered trace as a string; `getTraceId()` returns the run ID.


## Kill Switch Integration

`PumpStation` carries a `KillSwitch` (`Pipeline/PumpStation.kt:677`) that propagates to every `PathObject` in `pathList` and `reservePaths`. The harness checks the kill switch at every phase boundary via `recordAndCheckKillSwitch(agent)` (after judge, dispatch, and path execution).

The kill switch enforces `inputTokenLimit` and `outputTokenLimit`. When tripped, the default `KillSwitch.onTripped` callback throws `KillSwitchException`. The harness loop catches the exception at the `runHarnessLoop` boundary and sets `taskState.lastError = KillSwitchTripped` / `exitReason = KillSwitchTripped` before `runFinalizationPhase` emits a `HarnessFailed` event.

Each `PathObject` also has its own kill switch slot, populated by the station's `addPath` and `killSwitch` setter. Per-path limits are checked independently against the path's own token usage.

`KillSwitch` is documented in full in **[KillSwitch - Token Limit Enforcement](../core-concepts/killswitch.md)**. The propagation pattern in PumpStation mirrors the one used by `Manifold`, `Splitter`, `Junction`, `Connector`, `MultiConnector`, and `DistributionGrid`.


## P2P Integration

`PumpStation : P2PInterface` (`Pipeline/PumpStation.kt:667`). The station exposes its child agents and context through the P2P surface:

```kotlin
override fun getContextWindowFromInterface(): ContextWindow? = contextWindow
override fun getMiniBankFromInterface(): MiniBank? = miniBank
override fun getPaths(): String = serialize(getVisiblePathDescriptorsInternal(), false)
```

`executeP2PRequest(request: P2PRequest): P2PResponse?` delegates to `executeLocal(content)` and emits a `NestedP2PCompleted` event annotated with the parent path name. This lets a path issue a nested P2P call (e.g. dispatching to a child agent) and have the visualizer render the nested call as a sub-entry under the parent path's content panel.

`setParentInterface(parent: P2PInterface)` and `getParentP2PInterface(): P2PInterface?` track the parent container when a pump station is nested inside another. The `PathObject.setParentInterface(this)` call is made automatically at path execution time so nested calls are tagged with the station as their parent.

**P2P Concurrency:** PumpStation is stateful — turn index, history, and task state are mutable during a run. When exposed via P2P, register with `P2PConcurrencyMode.ISOLATED` so each inbound request gets a fresh clone. See [P2P Registry and Routing](../advanced-concepts/p2p/p2p-registry-and-routing.md#concurrency-modes) for details.


## Common Startup Failures

### `dispatchAgent must be a Pipeline`

```
PumpStation.init() failed: dispatchAgent must be a Pipeline.
Agents requiring PathRequest schema output must use Pipeline.
```

Cause: `dispatchAgent` is null or not a `Pipeline`.

Fix: assign a `Pipeline` to `dispatchAgent` (or `pumpStationBuilder.dispatchAgent`).

### `PathObject.init() failed: pathName is required`

Cause: a `PathObject` was added without a `pathName`.

Fix: set `pathName` on every `PathObject` before adding to the station.

### `PathObject.init() failed for path 'X': no execution mechanism configured`

Cause: the path has none of `executionFunction`, `internalAgent`, `agentBuilderFunction`, or a bound PCP function.

Fix: wire at least one execution mechanism on the path.

### `MaxTurnsExceeded`

The loop hit `maxTurns` (default 50) before any of the legitimate exit signals fired. Inspect `taskState.lastError` and `taskState.exitReason` to confirm. Common causes:

- Judge never says `isComplete: true` (in `Always` mode) or `requestJudgeNextTurn` is never set (in `FlagTriggered` mode)
- No path returns `passPipeline = true`
- No `terminatePipeline` either
- A `HarnessWarning` with `code = NoExitSignalConfigured` was emitted at startup if none of the above are configured

Fix: increase `maxTurns` conservatively, wire an exit signal, or supply a goal agent if you want the loop to keep verifying until the goal accepts.

### `KillSwitchTripped`

Token usage exceeded the configured input or output cap. The loop boundary catches the `KillSwitchException` and transitions to failure. Increase the cap on the `KillSwitch` (e.g. `KillSwitch(inputTokenLimit = 100_000, outputTokenLimit = 20_000)`) or optimize the agents to use fewer tokens.

### `CompactionInflated`

The summary agent returned a summary whose estimated token count exceeded the input. The orchestrator restored the most recent `CompactionBackup` and retried with a smaller scope until the retry budget was exhausted. The harness then handed off to the truncation path. Increase `maxCompactionAttempts` (default 2) or switch to `Truncation` mode for cost-controlled runs.

### `ContextBlowoutDetected`

The context-window fill ratio exceeded `blowoutThreshold` (default 0.9) at a phase boundary. The harness's recovery policy kicks in (up to `maxBlowoutRecoveries` attempts); after that the run halts.

### Dispatch agent emits invalid JSON for `PathRequest`

By default, the harness attempts up to `maxDispatchRepairAttempts` (default 1) repair round. After the budget is exhausted, the turn continues without a path call. To halt on parse failure, set `stopHarnessOnInvalidPathRequest = true` (or `failurePolicy.stopHarnessOnInvalidPathRequest = true`).

### Path-safety agent rejects a path

The path was not called. The original input flows through as the turn's result so the loop can continue. Inspect `PathSafetyCompleted.approved` in the trace to see the reason.


## Best Practices

- **Start with `PumpStationDefaults.withOpenRouter(config) { ... }`** from `TPipe-Defaults` for a pre-wired station. Override slots only when you need custom behavior.
- **Wire at least one exit signal** before running. Judge with default prompt, or a path that returns `passPipeline = true`, or `FlagTriggered` mode with a path-bound `requestJudgeNextTurn()`.
- **Set `maxTurns` conservatively in `FlagTriggered` mode.** The judge's absence means a runaway dispatch agent can loop forever otherwise.
- **Use a `goalAgent` for Ralph-loop semantics.** The goal agent forces a re-loop with critique if the work isn't acceptable, up to `maxGoalFailAttempts`.
- **Use reserve paths for expensive capabilities.** Hide them from the dispatch prompt until needed; their `revealWhen` predicate gates visibility.
- **Bind functions as PCP tools** rather than writing full `setExecutionFunction` closures. The dispatch agent gets typed parameter schema and the path stays lightweight.
- **Set a `KillSwitch` with a non-zero token cap** on every station. The default `recommendedKillSwitchConfig()` (50K input / 10K output) is a safe starting point.
- **Configure tracing during development.** The visualizer's turn-centric layout is the fastest way to understand what the harness is doing.
- **Use `saveSnapshot()` / `restoreSnapshot()` for rollback** at high-risk boundaries (e.g. before a destructive path call).
- **Don't share a single `PumpStation` instance across concurrent `executeLocal` calls.** Build a fresh station per concurrent run.
- **Tune compaction strategy per workload.** `Whole` is the cheapest when history fits; `Chunked` is the only option when history has blown past the agent's context window.
- **Test the magic contracts.** The judge, dispatch, and path-safety agents have structured JSON outputs. Validate them in your test suite — `parseJudgeVerdict`, `parseDispatchOutput`, and `parsePathSafetyVerdict` are accessible for direct invocation.


## Cross-References

- **[PumpStation Magic Contracts](../core-concepts/pumpstation-magic-contracts.md)** — Field-level JSON schemas and parser locations
- **[PumpStation API Reference](../api/pumpstation.md)** — Public properties, methods, and setters
- **[PumpStation Models API](../api/pumpstation-models.md)** — Enums, sealed events, data classes
- **[Container Overview](container-overview.md)** — Comparison with Manifold, Junction, DistributionGrid, Splitter
- **[Manifold](manifold.md)** — State-machine alternative
- **[DistributionGrid](distributiongrid.md)** — Distributed harness alternative
- **[Junction](junction.md)** — Discussion and workflow harness alternative
- **[Cross-Cutting Topics](cross-cutting-topics.md)** — Tracing events, KillSwitch propagation, P2P integration patterns
- **[KillSwitch](../core-concepts/killswitch.md)** — Token limit enforcement
- **[Developer-in-the-Loop Functions](../core-concepts/developer-in-the-loop.md)** — Hook system overview
- **[Tracing and Debugging](../core-concepts/tracing-and-debugging.md)** — Global tracing system
- **[P2P Interface](../api/p2p-interface.md)** — P2P contract for TPipe containers
- **[Pipe Context Protocol](pipe-context-protocol.md)** — PCP for bound functions on paths
- **[TPipe-Defaults Package](../api/tpipe-defaults-package.md)** — `PumpStationDefaults` factory reference

---
