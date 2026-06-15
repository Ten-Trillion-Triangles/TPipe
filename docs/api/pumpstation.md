
### P2P Interface

These are the public methods of the `P2PInterface` contract that `PumpStation` overrides:

| Function | Description |
|----------|-------------|
| `setParentInterface(parent: P2PInterface)` | Sets the parent container reference. |
| `getParentP2PInterface(): P2PInterface?` | Returns the parent container reference. |
| `getPaths(): String` | Serializes the visible path descriptor list. |
| `getContextWindowFromInterface(): ContextWindow?` | Returns the station's `contextWindow`. |
| `getMiniBankFromInterface(): MiniBank?` | Returns the station's `miniBank`. |
| `setTokenBudgetRecursive(budget: TokenBudgetSettings)` | Propagates the budget to all child agents. |
| `getTokenBudgetSettings(): TokenBudgetSettings?` | Returns the station's budget. |
| `setPipeSettingsRecursively(settings: PipeSettings)` | Propagates pipe settings. |
| `P2PInit()` (suspend) | Initializes the harness. |
| `executeLocal(content: MultimodalContent): MultimodalContent` (suspend) | Primary execution entry. |
| `executeP2PRequest(request: P2PRequest): P2PResponse?` (suspend) | P2P entry. |


## PathObject Class

`PathObject` lives in `Pipeline/PumpStation.kt:225`. It represents a single path the dispatch agent can invoke.

```kotlin
class PathObject(override var killSwitch: KillSwitch? = null) : P2PInterface
```

### PathObject Public Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `pathName` | `String` | `""` | Unique identifier. Required. The DSL throws `IllegalArgumentException` at build time if blank. |
| `pathDescription` | `String` | `""` | LLM-facing description. Injected into the dispatch prompt. |
| `pathSchema` | `String` | `""` | Free-form JSON schema for the path's input. |
| `pcpSchema` | `PcpContext?` | `null` | PCP context for PCP-bound paths. |
| `riskLevel` | `PathRiskLevel` | `Low` | Low, Medium, or High. Triggers path-safety gate at Medium+. |
| `dispatchHint` | `String` | `""` | Soft advisory surfaced in the dispatch prompt as `"Hint: ..."`. |
| `revealWhen` | `(taskState, externalContext) -> Boolean` | `{ _, _ -> false }` | Predicate for reserve path visibility. Sticky once revealed. |
| `pathMetadata` | `MutableMap<Any, Any>` | empty | Developer-supplied metadata. |
| `isInternalAgentSet` | `Boolean` (getter) | `false` | True if an internal agent is configured. |
| `isExecutionFunctionSet` | `Boolean` (getter) | `false` | True if an execution function is bound. |
| `isRunsInBackground` | `Boolean` (getter) | `false` | True if the path runs in background. |
| `killSwitch` | `KillSwitch?` (override) | `null` | Per-path token cap. Propagated by the station. |

### PathObject Public Functions

| Function | Description |
|----------|-------------|
| `setInternalAgent(agent: P2PInterface)` | Sets the internal agent. Overrides any agent builder. |
| `setExecutionFunction(function: (suspend (MultimodalContent, PumpStation, ConverseHistory?, String) -> MultimodalContent)?)` | Sets the execution function. |
| `setRunsInBackground(value: Boolean)` | Marks the path as background. |
| `P2PInit()` (suspend, override) | Delegates to `init()`. |
| `init(): PathDescriptionData` (suspend) | Validates configuration and returns the `PathDescriptionData` record. |
| `getPathTokenUsage(): com.TTT.Pipe.TokenUsage?` | Reads the path's token usage when the internal agent is a `Pipeline`. |
| `getPathLegacyTokenUsage(): Pair<Int, Int>` | Reads legacy `(input, output)` token counters. |
| `setParentInterface(parent: P2PInterface)` (override) | Sets the parent container reference. |
| `getParentP2PInterface(): P2PInterface?` (override) | Returns the parent container reference. |

### PathObject Extension Functions

In `Pipeline/PumpStationPathObjectExtensions.kt`:

```kotlin
fun PathObject.bindFunction(name: String, function: KFunction<*>): PathObject
fun PathObject.getStashContent(stashId: String, station: PumpStation?): ConverseData?
```

`bindFunction` registers a Kotlin function in the global `FunctionRegistry` and populates `pcpSchema` with the function's `TPipeContextOptions`. Throws `IllegalArgumentException` on blank name. Returns `this` for chaining.

`getStashContent` retrieves a stashed `ConverseData` by ID from the parent station's stash. Returns `null` if the station is null or no entry exists.


## TurnResult Sealed Class

`TurnResult` lives in `Pipeline/PumpStationModels.kt:844`:

```kotlin
sealed class TurnResult {
    object Continue : TurnResult()
    data class Halt(val reason: PumpStationExitReason) : TurnResult()
}
```

`runTurn` returns this to the outer `runHarnessLoop`. `Continue` re-enters the loop; `Halt(reason)` exits with the given reason. The outer loop also catches `KillSwitchException` at the loop boundary and transitions to a `Halt(KillSwitchTripped)` state.


## PumpStationBuilder Class

`PumpStationBuilder<S : PumpStationStage>` lives in `Pipeline/PumpStationDsl.kt:48`. The generic type parameter tracks the build stage:

```kotlin
sealed class PumpStationStage {
    object Initial   : PumpStationStage()
    object HasPaths : PumpStationStage()
    object Ready    : PumpStationStage()
}
```

`PumpStationBuilder<PumpStationStage.Initial>` is the entry type. After at least one `path { }` call the builder promotes to `HasPaths` stage. After the build block returns, the builder promotes to `Ready` and `build()` produces the `PumpStation`.

### Builder Block Methods

The `pumpStation { }` DSL surface is comprehensive. The most important blocks and properties:

```kotlin
pumpStation("name") {
    // Core configuration
    personality = "..."
    systemTask = "..."
    userGuidelines = "..."
    entryUserPrompt = "..."

    // Direct agent assignment
    judgeAgent = pipeline()
    dispatchAgent = pipeline()
    interventionAgent = pipeline()
    healthAgent = pipeline()
    lorebookAgent = pipeline()
    summaryAgent = pipeline()
    goalAgent = pipeline()
    preInitAgent = pipeline()
    pathSafetyAgent = pipeline()

    // Builder-function assignment
    judgeAgentBuilderFunction = { station -> pipeline() }
    // ... same for all agents

    // Path registration
    path("research") {
        description = "..."
        risk = PathRiskLevel.Low
        schema = "{}"
        pcpSchema = PcpContext()
        runsInBackground = false
        dispatchHint = "..."
        pathMetadata = mutableMapOf<Any, Any>()
        setInternalAgent(pipeline())
        setExecutionFunction { content, station, history, summary -> ... }
        bindFunction("fn", ::fn)
    }

    // Reserve path
    reservePath("sandboxed") {
        // same surface as path { }
        revealWhen { taskState, ctx -> ... }
    }

    // Memory and concurrency
    memory { mode = PumpStationMemoryManagementMode.Hybrid }
    setConcurrencyMode(PumpStationConcurrencyMode.Async)
    setMemoryManagementMode(PumpStationMemoryManagementMode.Compaction)
    setCompactionStrategy(PumpStationCompactionStrategy.Whole)
    setCompactionThreshold(0.8)
    setCompactionFanoutMode(ChunkFanoutMode.Sequential)
    setMaxCompactionAttempts(2)
    setChunkTokenBudget(2000)
    setMaxChunks(16)
    setMaxParallelChunks(4)
    setMaxCompactionBackups(3)
    setHybridWholeHeadroom(0.3)
    setPrePruneTransform { turns, station -> turns }
    appendPrePruneTransform { turns, station -> turns }
    setCompactionRolledBackFunction { backup, reason, station -> null }

    // Loop guards
    setMaxHarnessTurns(10)
    setMaxTurns(10)
    setMaxGoalFailAttempts(3)
    setMaxConsecutiveSamePath(3)
    setMaxTotalPathCallsPerPath(10)
    setPathLimitExceededPolicy(PathLimitExceededPolicy.Skip)
    setMaxRawTurnHistorySize(1000)
    setBlowoutThreshold(0.9)
    setMemoryUpdateTimeoutMs(30_000L)
    setMaxBlowoutRecoveries(3)
    setMaxRepairPromptTokens(500)
    setStopHarnessOnInvalidPathRequest(false)
    setMaxTurnHistorySize(50)
    setJudgeJsonContractEnabled(true)
    setPathSafetyJsonContractEnabled(true)
    setJudgeRunMode(PumpStationJudgeRunMode.Always)

    // Health
    setHealthAgentTurnInterval(10)
    setHealthAgentErrorRatioThreshold(0.2)
    setHealthAgentConcurrencyMode(PumpStationConcurrencyMode.Blocking)

    // DITL hooks
    setPreInitFunction { content, station -> content }
    setPreValidationJudgeFunction { content, miniBank, station -> miniBank }
    setPreInvokeFunction { ctx, miniBank, station -> true }
    setPreValidationDispatchFunction { content, ctx, miniBank, station -> miniBank }
    setPostGenerateFunction { content, station -> station }
    setPathValidationFunction { content, station -> true }
    setPathTransformationFunction { content, station -> content }
    setPostMemoryFunction { content, station -> content }
    setPreCompactionFunction { content, overflow, history, station -> content }
    setPostCompactionFunction { content, history, station -> content }
    setOnContextTruncated { wasTruncated, remaining -> }
    setPathSafetyFunction { path, schema, station -> true }
    setPathLimitExceededFunction { path, reason, station -> PathLimitExceededResult(...) }
    setCompactionRolledBackFunction { backup, reason, station -> null }
    setExternalContextProvider { taskState -> mutableMapOf() }

    // Custom prompts
    setCustomJudgeSystemPrompt("...")
    setCustomDispatchSystemPrompt("...")
    setCustomPathSafetySystemPrompt("...")
    setCustomHealthSystemPrompt("...")
    setCustomLorebookSystemPrompt("...")
    setCustomGoalSystemPrompt("...")

    // Additional harness agent slots
    harnessAgent {
        agent = pipeline()
        concurrency = PumpStationConcurrencyMode.Blocking
        interval = 5
    }
    harnessAgentBuilder {
        builderFunction = { station -> pipeline() }
        concurrency = PumpStationConcurrencyMode.Async
        interval = 5
    }

    // Kill switch
    killSwitch {
        inputTokenLimit = 50_000
        outputTokenLimit = 10_000
        onTripped = { ctx -> throw KillSwitchException(ctx) }
    }

    // Tracing
    tracing {
        enabled()
        maxHistory(1000)
        outputFormat(TraceFormat.HTML)
        detailLevel(TraceDetailLevel.STANDARD)
        autoExport(enabled = true, path = "~/.tpipe-traces/")
        includeContext(true)
        includeMetadata(true)
    }

    // Pause phases
    pause { phase(PumpStationPausePhase.BeforeJudge) }
    pause { phase(PumpStationPausePhase.BeforePathExecution) }

    // Pipeline names (reserved for future)
    pipelineNames { }

    // Dispatcher rules (reserved for future)
    dispatcherRules { }
}
```

The `pumpStation("name") { ... }` function returns a fully built `PumpStation`. There is also `pumpStationBuilder("name")` for callers who want to build the builder separately and call `build()` explicitly.


## Enums

The enums referenced by the API are documented in full in **[PumpStation Models API](pumpstation-models.md)**:

- `PumpStationConcurrencyMode` — Async, Blocking
- `PumpStationMemoryManagementMode` — Compaction, Truncation, Hybrid
- `PumpStationCompactionStrategy` — Whole, Chunked, Hybrid
- `PumpStationJudgeRunMode` — Always, FlagTriggered
- `PathRiskLevel` — Low, Medium, High
- `PumpStationStatus` — NotStarted, Running, WaitingOnBackground, Suspended, Completed, Failed, Terminated
- `PumpStationPhase` — PreInit, HealthCheck, Judge, Dispatch, PathSafety, PathExecution, PathValidation, Intervention, ForegroundAgents, MemoryUpdate, Compaction, GoalValidation, Exit
- `PumpStationError` — UnknownPath, InvalidPathRequest, DispatchJsonRepairFailed, PathExecutionException, TokenBudgetExceeded, MemoryBlowout, KillSwitchTripped, MaxTurnsExceeded, LoopGuardTriggered, P2PRequestInvalid, InitNotCalled, CompactionInflated, CompactionRolledBack
- `PumpStationExitReason` — JudgeComplete, PassSignal, TerminateSignal, MaxTurnsHit, KillSwitchTripped, GoalValidationFailed, InterventionTerminated, Error
- `PumpStationPausePhase` — BeforeJudge, AfterJudge, BeforeDispatch, AfterDispatch, BeforePathSafety, BeforePathExecution, AfterPathExecution, BeforeMemoryUpdate, BeforeCompaction, BeforeGoalValidation, BeforeExit
- `StashReason` — TokenOverflow, BinaryPayload, ErrorLog, UnsafeForPrompt, DeveloperRequested, BackgroundResult
- `PathLimitExceededPolicy` — Skip, Halt, Continue
- `HealthStatus` — Healthy, Degraded, Critical, Unknown
- `WarningCode` — NoExitSignalConfigured
- `ExitMechanism` — JudgeAlways, JudgeFlagTriggered, PathPassPipeline, PathTerminatePipeline
- `ChunkFanoutMode` — Sequential, Parallel
- `LorebookOperation` — Merge, Replace

Default prompts are documented in **[PumpStation Magic Contracts](../core-concepts/pumpstation-magic-contracts.md)**.


## Cross-References

- **[PumpStation Container Doc](../containers/pumpstation.md)** — Architecture, execution flow, design philosophy
- **[PumpStation Magic Contracts](../core-concepts/pumpstation-magic-contracts.md)** — JSON schemas, parsers, default prompts
- **[PumpStation Models API](pumpstation-models.md)** — Sealed events, enums, data classes
- **[TPipe-Defaults Package](tpipe-defaults-package.md#pumpstationdefaults)** — `PumpStationDefaults.withOpenRouter` factory
- **[P2P Interface](p2p-interface.md)** — P2P contract for TPipe containers
- **[KillSwitch](../core-concepts/killswitch.md)** — Token limit enforcement

---

**Next:** [PumpStation Models API →](pumpstation-models.md)
