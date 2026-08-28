
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
| `isSuppressHistoryEmit` | `Boolean` (getter) | `false` | True if the async path's completion is suppressed from being merged into `turnHistory`. Only takes effect when `isRunsInBackground` is also true. |
| `killSwitch` | `KillSwitch?` (override) | `null` | Per-path token cap. Propagated by the station. |

### PathObject Public Functions

| Function | Description |
|----------|-------------|
| `setInternalAgent(agent: P2PInterface)` | Sets the internal agent. Overrides any agent builder. |
| `setExecutionFunction(function: (suspend (MultimodalContent, PumpStation, ConverseHistory?, String) -> MultimodalContent)?)` | Sets the execution function. |
| `setRunsInBackground(value: Boolean)` | Marks the path as background. |
| `setSuppressHistoryEmit(value: Boolean)` | When `true`, an async path's `PathCompleted` event is still emitted, but the foreground drain skips merging the result into `turnHistory`. Only meaningful when the path is also `isRunsInBackground = true`. |
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
    implementationPlan("Follow the declared workflow and preserve the required output contract.")

    // Dispatch contract — SinglePath (default) emits one PathRequest per turn;
    // MultiPath emits a PathRequestList and fans out via the async substrate.
    pathExecutionShape = PathExecutionShape.MultiPath

    // Direct agent assignment
    judgeAgent = pipeline()
    dispatchAgent = pipeline()
    interventionAgent = pipeline()
    healthAgent = pipeline()
    lorebookAgent = pipeline()
    summaryAgent = pipeline()
    goalAgent = pipeline()
    postGoalAgent = pipeline()
    postGoalFunction = { content, station -> content }
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
        suppressHistoryEmit = false
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
    promptConfiguration {
        historyTransport = PumpStationHistoryTransport.TextOnly
        latestContentInjectionEnabled = true
        latestContentPosition = PumpStationLatestContentPosition.Suffix
        deduplicateLatestContentAgainstHistory = true
    }
    setBlowoutThreshold(0.9)
    setMemoryUpdateTimeoutMs(30_000L)
    setMaxBlowoutRecoveries(3)
    setMaxRepairPromptTokens(500)
    setStopHarnessOnInvalidPathRequest(false)
    setMaxTurnHistorySize(50)
    setJudgeJsonContractEnabled(true)
    setPathSafetyJsonContractEnabled(true)
    setRequirePathSelectionRationale(true)
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

    // Async substrate (paths that opt into runsInBackground; background harness agents)
    asyncPathsAppendToTurnHistory = true
    asyncAgentsAppendToTurnHistory = false
    asyncJobGracePeriodMs = null
    asyncJobsScopedToStation = true

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

### Implementation-Plan Functions

#### `setImplementationPlan(plan: String?): PumpStation`

Attaches stable implementation guidance to the judge, dispatch, and Pipeline-shaped goal agents. The value is trimmed before storage. `null` and blank values clear the plan.

The plan is appended to each selected pipe's raw system prompt as:

```text
Implementation plan:
<trimmed plan text>
```

The setter applies the plan immediately to configured control-agent pipes and returns the station for chaining. Calling it while `executeLocal(...)` is active throws `IllegalStateException`. If prompt application fails, the prior plan is restored and the error is rethrown. Source: `Pipeline/PumpStation.kt:3634`.

#### `getImplementationPlan(): String?`

Returns the normalized implementation plan, or `null` when the plan is disabled. Source: `Pipeline/PumpStation.kt:3665`.

#### `implementationPlan(plan: String?): PumpStationBuilder<S>`

Stores the normalized plan in the staged builder. The value is copied during builder promotion and applied to the built station. Source: `Pipeline/PumpStationDsl.kt:823`.

### Prompt Configuration

`PromptConfigurationBlock` lives in `Pipeline/PumpStationDsl.kt`. Its values are copied into the built `PumpStation`, including when a builder is promoted through `path { }`.

| Property | Type | Default | Description |
|---|---|---|---|
| `historyTransport` | `PumpStationHistoryTransport` | `TextOnly` | Selects text, structured-context, or dual history transport. |
| `latestContentInjectionEnabled` | `Boolean` | `true` | Enables latest prior agent output in dispatch text. |
| `latestContentPosition` | `PumpStationLatestContentPosition` | `Suffix` | Selects `Prefix`, `BeforeHistory`, `AfterHistory`, or `Suffix`. |
| `deduplicateLatestContentAgainstHistory` | `Boolean` | `true` | Suppresses exact latest-output duplicates already present in turn history. |

The equivalent `PumpStation` methods are `setHistoryTransport(...)`, `setLatestContentInjectionEnabled(...)`, `setLatestContentPosition(...)`, and `setDeduplicateLatestContentAgainstHistory(...)`.

### Prompt Transport Enums

`PumpStationHistoryTransport` and `PumpStationLatestContentPosition` live in `src/main/kotlin/Enums/`. `TextOnly` and `Suffix` are the defaults because they preserve one provider-facing history representation and a stable dispatch prefix.


## Async Substrate

`PumpStation` exposes a thread-safe substrate for code that runs outside the foreground turn loop. Async paths (`isRunsInBackground = true`) and async harness agents (`HarnessAgentSlot` with `concurrency = Async`) launch coroutines on a station-scoped `CoroutineScope` and need a way to land their results into the harness conversation history without corrupting it. The async substrate is that interface.

The full design — what the substrate guarantees, why the default grace period is `null`, and the rationale for monotonic `seq` ordering — is documented in **[PumpStation Container Doc](../containers/pumpstation.md#async-substrate)**. This section is the public API surface.

### Async Substrate Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `asyncScope` | `kotlinx.coroutines.CoroutineScope` | station-scoped `SupervisorJob() + Dispatchers.Default` | The scope backing every async coroutine launched by the harness. Cancelled by `cancelAsyncJobs()` at the end of `executeLocal`. |

### Async Substrate Public Functions

| Function | Description |
|----------|-------------|
| `appendTurnEntryAsync(entry: ConverseData, source: String = "agent")` (suspend) | The single thread-safe access point for async producers to push a `ConverseData` into `turnHistory`. Acquires the harness history mutex, appends to `turnHistory` and `rawTurnHistory`, and emits an `AsyncTurnAppended` event. Direct mutation of `turnHistory` from async code is NOT safe; always use this method. |
| `appendTurnEntriesAsync(entries: List<ConverseData>, source: String = "agent")` (suspend) | Batch version of `appendTurnEntryAsync`. Acquires the history mutex once and emits a single trailing `AsyncTurnAppended` event for the last entry. |
| `cancelAsyncJobs(gracePeriodMs: Long? = asyncJobGracePeriodMs)` | Cancel in-flight async coroutines. When `gracePeriodMs` is `null` (the default), the wait is unbounded — the harness yields once and then cancels. When set, coroutines that do not finish within the window are cancelled. Safe to call multiple times. Called automatically by `runFinalizationPhase` before `executeLocal` returns. |
| `isAsyncScopeActive(): Boolean` | Returns `true` if `asyncScope` is still active (not yet cancelled). Useful for tests and DITL tooling. |
| `setAsyncPathsAppendToTurnHistory(value: Boolean): PumpStation` | Station-wide default for whether async paths append their result to `turnHistory` on completion. Default `true`. Per-path opt-out via `PathObject.setSuppressHistoryEmit`. |
| `isAsyncPathsAppendToTurnHistory(): Boolean` | Returns the current default. |
| `setAsyncAgentsAppendToTurnHistory(value: Boolean): PumpStation` | Station-wide default for whether async harness agents append their result to `turnHistory` on completion. Default `false` (fire-and-forget). Per-slot opt-in via `HarnessAgentSlot.appendsToTurnHistory`. |
| `isAsyncAgentsAppendToTurnHistory(): Boolean` | Returns the current default. |
| `setAsyncJobGracePeriodMs(ms: Long?): PumpStation` | Optional millisecond grace period given to in-flight async coroutines after `runFinalizationPhase` before `cancelAsyncJobs` cancels `asyncScope`. When `null` (the default), the cancel is unbounded. TPipe intentionally does not impose arbitrary timeouts on user work; developers who need a hard upper bound should set this to a value that matches their worst-case LLM round-trip plus safety margin. |
| `getAsyncJobGracePeriodMs(): Long?` | Returns the current grace period, or `null` if the cancel is unbounded. |
| `setAsyncJobsScopedToStation(value: Boolean): PumpStation` | When `true` (the default), async work runs on the station-scoped `asyncScope` so `cancelAsyncJobs` can guarantee no coroutine outlives `executeLocal`. When `false`, async work runs on `GlobalScope` (the pre-substrate fire-and-forget behavior). |
| `isAsyncJobsScopedToStation(): Boolean` | Returns whether async work runs on the station-scoped `asyncScope`. |

### Drain Ordering

The foreground calls `drainPendingAsyncResults()` at safe phase boundaries (start of judge, start of finalization). The drain merges pending entries into `turnHistory` in monotonic `seq` order, where `seq` is assigned at enqueue time by an `AtomicLong` counter. Out-of-order async completions still produce a deterministic merge order from the LLM's perspective.

Per-path opt-out via `PathObject.setSuppressHistoryEmit` is honoured during the drain. Per-slot opt-in via `HarnessAgentSlot.appendsToTurnHistory` is honoured at enqueue time. The two station-wide defaults (`asyncPathsAppendToTurnHistory`, `asyncAgentsAppendToTurnHistory`) act as the umbrella switches; the per-path / per-slot flags override the station defaults.

### Pushing From Custom Async Agents

Custom async harness agents and DITL hooks running on `asyncScope` should use `appendTurnEntryAsync` (or `appendTurnEntriesAsync` for batches) to land results in the conversation. The corresponding `AsyncTurnAppended` event carries the `source` identifier and the `seq` so observers can correlate the merge back to the dispatch. Direct mutation of `turnHistory` from async code is unsafe because `ConverseHistory` is a plain data class with no internal lock.

```kotlin
pumpStation("research") {
    asyncAgentsAppendToTurnHistory = true
    asyncJobGracePeriodMs = 30 * 60 * 1000L  // 30 minutes

    harnessAgentBuilder({ station ->
        MyAsyncResearchAgent(station)
    }, concurrency = PumpStationConcurrencyMode.Async)
}
```


## Enums

The enums referenced by the API are documented in full in **[PumpStation Models API](pumpstation-models.md)**:

- `PumpStationConcurrencyMode` — Async, Blocking
- `PumpStationMemoryManagementMode` — Compaction, Truncation, Hybrid
- `PumpStationCompactionStrategy` — Whole, Chunked, Hybrid
- `PumpStationJudgeRunMode` — Always, FlagTriggered
- `PathRiskLevel` — Low, Medium, High
- `PathExecutionShape` — SinglePath, MultiPath. Controls whether the dispatch agent emits one `PathRequest` per turn (default) or a `PathRequestList` that the harness fans out across multiple paths.
- `PumpStationStatus` — NotStarted, Running, WaitingOnBackground, Suspended, Completed, Failed, Terminated
- `PumpStationPhase` — PreInit, HealthCheck, Judge, Dispatch, PathSafety, PathExecution, PathValidation, Intervention, ForegroundAgents, MemoryUpdate, Compaction, GoalValidation, Exit
- `PumpStationError` — UnknownPath, InvalidPathRequest, DispatchJsonRepairFailed, PathExecutionException, PathTimeout, TokenBudgetExceeded, MemoryBlowout, KillSwitchTripped, MaxTurnsExceeded, LoopGuardTriggered, P2PRequestInvalid, InitNotCalled, CompactionInflated, CompactionRolledBack
- `PumpStationExitReason` — JudgeComplete, PassSignal, TerminateSignal, MaxTurnsHit, KillSwitchTripped, GoalValidationFailed, InterventionTerminated, Error
- `PumpStationPausePhase` — BeforeJudge, AfterJudge, BeforeDispatch, AfterDispatch, BeforePathSafety, BeforePathExecution, AfterPathExecution, BeforeMemoryUpdate, BeforeCompaction, BeforeGoalValidation, BeforeExit
- `StashReason` — TokenOverflow, BinaryPayload, ErrorLog, UnsafeForPrompt, DeveloperRequested, BackgroundResult
- `PathLimitExceededPolicy` — Skip, Halt, Continue
- `PumpStationHistoryTransport` — TextOnly, ContextOnly, TextAndContext
- `PumpStationLatestContentPosition` — Prefix, BeforeHistory, AfterHistory, Suffix
- `HealthStatus` — Healthy, Degraded, Critical, Unknown
- `WarningCode` — NoExitSignalConfigured
- `ExitMechanism` — JudgeAlways, JudgeFlagTriggered, PathPassPipeline, PathTerminatePipeline
- `ChunkFanoutMode` — Sequential, Parallel
- `LorebookOperation` — Merge, Replace

Default prompts are documented in **[PumpStation Magic Contracts](../core-concepts/pumpstation-magic-contracts.md)**.


## Steering and Interrupt Runtime API

Two complementary runtime mechanisms for out-of-band producer calls into the running harness loop. Both are thread-safe and never block the caller; both fire at the same 11 `PumpStationPausePhase` boundaries. They differ in effect: **steering ADDS content** to the running turn, **interrupt REWINDS and RESTARTS** the turn from the top.

### Steering: add content to the running turn

Use `steer(...)` to inject a `MultimodalContent` into `turnHistory` at a future phase boundary without halting the loop. Persistent variants fire on every occurrence; one-shot variants fire once and are discarded.

```kotlin
// One-shot — fires at the next BeforeJudge, then discarded
station.steer(PumpStationPausePhase.BeforeJudge, "user just asked: focus on security")

// Persistent — fires on every BeforeJudge until cleared
station.steerPersistent(PumpStationPausePhase.BeforeJudge, "always check the user's last message")

// Clear the persistent overlay for a phase
station.clearSteering(PumpStationPausePhase.BeforeJudge)

// Drain helper — returns the entries that WOULD be injected at the next
// phase boundary, with the canonical metadata["steering"] envelope applied
val entries: List<MultimodalContent> = station.drainSteeringForPhase(PumpStationPausePhase.BeforeJudge)
```

All three are `suspend` extensions on `PumpStation`; the underlying `PumpStationSteeringService` uses a `Mutex` plus per-phase `Channel<MultimodalContent>` for thread safety. See [Steering: Mid-Loop Context Injection](../containers/pumpstation.md#steering-mid-loop-context-injection) in the container doc for the full design.

### Interrupt: rewind and restart the turn

Use `interrupt(...)` to stop the active turn mid-flight, inject the message into `turnHistory`, and re-enter `runTurn` from the BeforeJudge of the same turn slot. The in-flight turn's partial work is discarded.

```kotlin
// Standard: pass a MultimodalContent payload
station.interrupt(
    PumpStationPausePhase.BeforeJudge,
    MultimodalContent(text = "halt: switch to a different path")
)

// Convenience: pass a plain text string
station.interrupt(PumpStationPausePhase.AfterDispatch, "stop and pivot")
```

Both are `suspend` extensions on `PumpStation`. The underlying `PumpStationInterruptService` is a thread-safe per-phase FIFO queue (`Mutex` plus `Channel<MultimodalContent>` plus `ConcurrentHashMap`). The `interruptService` property exposes the service for inspection:

```kotlin
val pending = station.interruptService.queueDepth(PumpStationPausePhase.BeforeJudge)
```

The first interrupt queued for a phase becomes the active rewind target. Subsequent interrupts queued before the next poll are forwarded to the steering service as one-shot entries (or silently dropped with an `InterruptOverflowDropped` event if steering is also not configured for that phase). See [Interrupt: Hard Rewind-and-Restart](../containers/pumpstation.md#interrupt-hard-rewind-and-restart) in the container doc for the full design.

### When to use which

| Use case | Steering | Interrupt |
|----------|----------|-----------|
| Add a hint to the next judge call | yes | |
| Periodic nudge the agent should see on every turn | yes (`steerPersistent`) | |
| User pressed the stop button | | yes |
| Watchdog decided a path is looping | | yes |
| Framework sent a new instruction that must preempt the in-flight turn | | yes |


## Cross-References

- **[PumpStation Container Doc](../containers/pumpstation.md)** — Architecture, execution flow, design philosophy
- **[PumpStation Async Substrate](../containers/pumpstation.md#async-substrate)** — Thread-safety guarantees, drain ordering, cancellation lifecycle for async paths and agents
- **[PumpStation Magic Contracts](../core-concepts/pumpstation-magic-contracts.md)** — JSON schemas, parsers, default prompts
- **[PumpStation Models API](pumpstation-models.md)** — Sealed events, enums, data classes
- **[TPipe-Defaults Package](tpipe-defaults-package.md#pumpstationdefaults)** — `PumpStationDefaults.withOpenRouter` factory
- **[P2P Interface](p2p-interface.md)** — P2P contract for TPipe containers
- **[KillSwitch](../core-concepts/killswitch.md)** — Token limit enforcement

---

**Next:** [PumpStation Models API →](pumpstation-models.md)
