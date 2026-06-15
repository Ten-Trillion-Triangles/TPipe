
## Path Description Models

### PathRequest

`Pipeline/PumpStation.kt:198` — Emitted by the dispatch agent. The class itself is the magic contract.

```kotlin
@Serializable
data class PathRequest(
    var pathName: String = "",
    var pathSchema: String = ""
)
```

`pathName` is matched case-insensitively against `pathList` and `reservePaths`. `pathSchema` is passed to the path as the input.

### PathDescriptionData

`Pipeline/PumpStation.kt:173` — Immutable record produced by `PathObject.init()`.

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

Captures the fully initialized configuration of a path. This is what the dispatch agent's prompt sees when the harness builds the visible path descriptor list.

### PathDescriptionList

`Pipeline/PumpStation.kt:188` — Wrapper for the full set of visible paths.

```kotlin
@Serializable
data class PathDescriptionList(
    var paths: MutableList<PathDescriptionData> = mutableListOf()
)
```

Serialized to JSON and injected into the dispatch agent's system prompt at prompt-build time.


## Memory and Action Models

### MemoryActionResult

`Pipeline/PumpStation.kt:146` — Result of a memory update push. Surfaced in `MemoryUpdateCompleted` events.

```kotlin
data class MemoryActionResult(
    var memoryMode: PumpStationMemoryManagementMode,
    var memoryStrategy: PumpStationCompactionStrategy,
    var loreBookActive: Boolean,
    var summaryActive: Boolean,
    var compactionPercent: Double,
    var budgetSettings: TokenBudgetSettings
)
```

### LorebookAgentInput

`Pipeline/LorebookAgentModels.kt:48` — Input envelope for the lorebook agent. Built by `buildLorebookAgentInput` in `Pipeline/PumpStationLoop.kt:1372`.

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

`turnsSinceLastUpdate` is the slice of `turnHistory.history` whose `turnIndex` is greater than `lorebookCursor.lastUpdatedTurnIndex`. The pre-prune step is applied before this list is built.

### LorebookAgentOutput

`Pipeline/LorebookAgentModels.kt:85` — Output envelope from the lorebook agent.

```kotlin
@Serializable
data class LorebookAgentOutput(
    val updates: List<LorebookUpdate>,
    val deletions: List<String> = emptyList(),
    val compactedThroughTurn: Int
)
```

`compactedThroughTurn` is the cursor advance. Outputs whose `compactedThroughTurn <= lorebookCursor.lastUpdatedTurnIndex` are discarded silently (pre-emption).

### LorebookUpdate

`Pipeline/LorebookAgentModels.kt:110` — One update entry in a `LorebookAgentOutput`.

```kotlin
@Serializable
data class LorebookUpdate(
    val key: String,
    val value: String,
    val weight: Int = 0,
    val linkedKeys: List<String> = emptyList(),
    val aliasKeys: List<String> = emptyList(),
    val requiredKeys: List<String> = emptyList(),
    val operation: LorebookOperation = LorebookOperation.Merge
)
```

### LorebookOperation

`Pipeline/LorebookAgentModels.kt:124` — Whether a `LorebookUpdate` merges or replaces.

```kotlin
@Serializable
enum class LorebookOperation {
    Merge,    // Combine the new value with the existing entry's value
    Replace   // Overwrite the existing entry wholesale
}
```

### LorebookTaskContext

`Pipeline/LorebookAgentModels.kt:62` — Static task framing passed to the lorebook agent.

```kotlin
@Serializable
data class LorebookTaskContext(
    val task: String,
    val persona: String,
    val systemTask: String,
    val userGuidelines: String
)
```


## Health Models

### HealthContext

`Pipeline/PumpStationModels.kt:162` — Structured context passed to the health agent as JSON in `MultimodalContent.text`.

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

Built by `buildHealthContext` in `Pipeline/PumpStationHelpers.kt:530`.

### HealthReport

`Pipeline/PumpStationModels.kt:181` — Result returned by the health agent.

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

Parsed by `parseHealthReport` in `Pipeline/PumpStationHelpers.kt:1554`. On any exception or null result, the harness returns `HealthReport()` (all defaults, `Unknown`).


## Failure Policy and Snapshot Models

### PumpStationFailurePolicy

`Pipeline/PumpStationModels.kt:925` — Default failure recovery policy.

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
| `stashOversizedOutputs` | `true` | Stash oversized path outputs. |
| `callInterventionOnPathFailure` | `true` | Invoke `interventionAgent` after a path failure. |
| `stopHarnessOnInvalidPathRequest` | `false` | When true, set `lastError = DispatchJsonRepairFailed` after repair budget is exhausted. |

### PumpStationSnapshot

`Pipeline/PumpStationModels.kt:940` — Snapshot of the harness state.

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

Used for rollback, resume, fork, and debugging. Captured by `saveSnapshot()` and restored by `restoreSnapshot(snapshot)`.

### StashEntry

`Pipeline/PumpStationModels.kt:906` — Manifest entry for stashed content.

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

Allows agents and DITL tooling to reason about what was stashed without loading the full content. `getStashManifest()` returns the list.

### PathLimitExceededResult

`Pipeline/PumpStationModels.kt:195` — Result returned by `pathLimitExceededFunction` when the per-path limit is exceeded.

```kotlin
@Serializable
data class PathLimitExceededResult(
    val action: PathLimitExceededPolicy,
    val reason: String = "",
    val nextPathOverride: String? = null
)
```

Allows dynamic runtime policy instead of the static `PathLimitExceededPolicy`.


## Task State and Sealed Events

### PumpStationTaskState

`Pipeline/PumpStationModels.kt:969` — Single source of truth for runtime inspection, replay, and resume.

```kotlin
@Serializable
data class PumpStationTaskState(
    var runId: String,
    var status: PumpStationStatus,
    var phase: PumpStationPhase,
    var turnIndex: Int,
    var goalFailCount: Int = 0,
    var originalInput: MultimodalContent?,
    var latestContent: MultimodalContent?,
    var selectedPathName: String?,
    var lastPathResult: MultimodalContent?,
    var lastError: PumpStationError?,
    var exitReason: PumpStationExitReason?,
    var memoryActionResult: MemoryActionResult?,
    var currentPathName: String? = null,
    var isPaused: Boolean = false,
    var pausedAt: Set<PumpStationPausePhase> = emptySet(),
    var pauseReason: String? = null,
    var requestJudgeNextTurn: Boolean = false,
    var compactionCursor: CompactionCursor? = null,
    var lorebookCursor: LorebookCursor? = null
)
```

`currentPathName` is set while a path is being executed and is used to annotate nested P2P events with the parent path's name. `requestJudgeNextTurn` is the one-shot flag consumed by `runJudgePhase` in `FlagTriggered` mode.

### PumpStationEvent Sealed Interface

`Pipeline/PumpStationModels.kt:209` — Every event the harness emits implements this interface.

```kotlin
@Serializable
sealed interface PumpStationEvent {
    val runId: String
    val turnIndex: Int
    val timestamp: Long
    val phase: PumpStationPhase
}
```

The full event taxonomy is documented below. Each event has a corresponding `TraceEventType` mapping (see the PumpStation container doc for the full table).

### Harness Lifecycle Events

`Pipeline/PumpStationModels.kt:221-247, 705-740`

| Event | Data | Phase | Description |
|-------|------|-------|-------------|
| `HarnessStarted` | `originalInput: MultimodalContent?` | `PreInit` | Emitted at the start of `runPreInitPhase`. |
| `PreInitCompleted` | (no extra fields) | `PreInit` | Emitted at the end of `runPreInitPhase`. |
| `HarnessCompleted` | `exitReason: PumpStationExitReason`, `finalOutput: MultimodalContent?` | `Exit` | Emitted on successful finalization. |
| `HarnessFailed` | `error: PumpStationError`, `errorMessage: String?`, `exitReason: PumpStationExitReason` | `Exit` | Emitted when `lastError` is set. |
| `HarnessSuspended` | `pausedAt: Set<PumpStationPausePhase>`, `reason: String?` | `Exit` | Emitted when the harness pauses at a phase boundary. |
| `HarnessResumed` | (no extra fields) | `Exit` | Emitted when the harness resumes from a pause. |
| `HarnessWarning` | `code: WarningCode`, `message: String`, `mechanisms: List<ExitMechanism>` | `PreInit` | Advisory. Currently only `NoExitSignalConfigured`. |

### Judge Phase Events

`Pipeline/PumpStationModels.kt:244-291`

| Event | Data | Phase | Description |
|-------|------|-------|-------------|
| `JudgeStarted` | (no extra fields) | `Judge` | Emitted at the start of `runJudgePhase`. |
| `JudgeSkipped` | `reason: String`, `judgeRunMode: PumpStationJudgeRunMode` | `Judge` | Emitted in `FlagTriggered` mode when `requestJudgeNextTurn` is false. |
| `JudgeCompleted` | `isComplete: Boolean`, `shouldTerminate: Boolean`, `result: MultimodalContent?`, `inputTokens: Int?`, `outputTokens: Int?`, `totalTokens: Int?` | `Judge` | Emitted at the end of `runJudgePhase`. |

### Dispatch Phase Events

`Pipeline/PumpStationModels.kt:315-342`

| Event | Data | Phase | Description |
|-------|------|-------|-------------|
| `DispatchStarted` | (no extra fields) | `Dispatch` | Emitted at the start of `runDispatchPhase`. |
| `DispatchCompleted` | `selectedPathName: String?`, `pathRequest: PathRequest?`, `result: MultimodalContent?`, `inputTokens: Int?`, `outputTokens: Int?`, `totalTokens: Int?` | `Dispatch` | Emitted at the end of `runDispatchPhase`. |

### Path Execution Events

`Pipeline/PumpStationModels.kt:348-473`

| Event | Data | Phase | Description |
|-------|------|-------|-------------|
| `PathSelected` | `pathName: String`, `riskLevel: PathRiskLevel` | `Dispatch` | Emitted when a path is selected for execution. |
| `PathSafetyStarted` | `pathName: String`, `riskLevel: PathRiskLevel` | `PathSafety` | Emitted at the start of the safety check. |
| `PathSafetyCompleted` | `pathName: String`, `riskLevel: PathRiskLevel`, `approved: Boolean`, `reason: String?` | `PathSafety` | Emitted at the end of the safety check. |
| `PathStarted` | `pathName: String`, `riskLevel: PathRiskLevel` | `PathExecution` | Emitted before the path executes. |
| `PathCompleted` | `pathName: String`, `riskLevel: PathRiskLevel`, `result: MultimodalContent?`, `inputTokens: Int?`, `outputTokens: Int?`, `totalTokens: Int?` | `PathExecution` | Emitted after the path executes successfully. |
| `PathFailed` | `pathName: String`, `riskLevel: PathRiskLevel`, `error: PumpStationError`, `errorMessage: String?` | `PathExecution` | Emitted on path failure. |
| `PathHidden` | `pathName: String`, `reason: String` | `PathExecution` | Emitted when the per-path limit is exceeded and policy is `Skip`. |
| `PathValidationCompleted` | `pathName: String`, `approved: Boolean`, `reason: String?` | `PathValidation` | Emitted after the `pathValidationFunction` runs. |

### Intervention Events

`Pipeline/PumpStationModels.kt:424-494`

| Event | Data | Phase | Description |
|-------|------|-------|-------------|
| `InterventionStarted` | `trigger: PumpStationError`, `pathName: String` | `Intervention` | Emitted before calling the intervention agent. |
| `InterventionCompleted` | `nudges: Int`, `shouldContinue: Boolean`, `result: MultimodalContent?`, `inputTokens: Int?`, `outputTokens: Int?`, `totalTokens: Int?` | `Intervention` | Emitted after the intervention agent returns. |

### Health Check Events

`Pipeline/PumpStationModels.kt:500-519`

| Event | Data | Phase | Description |
|-------|------|-------|-------------|
| `HealthCheckStarted` | (no extra fields) | `HealthCheck` | Emitted at the start of `runHealthCheckPhase`. |
| `HealthCheckCompleted` | `status: HealthStatus`, `warnings: Int`, `terminateHarness: Boolean` | `HealthCheck` | Emitted at the end of `runHealthCheckPhase`. |

### Foreground and Background Events

`Pipeline/PumpStationModels.kt:529-836`

| Event | Data | Phase | Description |
|-------|------|-------|-------------|
| `ForegroundAgentCompleted` | `agentName: String`, `result: MultimodalContent?`, `inputTokens: Int?`, `outputTokens: Int?`, `totalTokens: Int?` | `ForegroundAgents` | Emitted when a foreground (Blocking) agent finishes. |
| `BackgroundAgentQueued` | `agentName: String` | `ForegroundAgents` | Emitted when a background (Async) agent is queued. |

### Memory and Compaction Events

`Pipeline/PumpStationModels.kt:545-676`

| Event | Data | Phase | Description |
|-------|------|-------|-------------|
| `MemoryUpdateStarted` | `memoryMode: PumpStationMemoryManagementMode` | `MemoryUpdate` | Emitted at the start of `runMemoryUpdatePhase`. |
| `MemoryUpdateCompleted` | `memoryMode: PumpStationMemoryManagementMode`, `result: MemoryActionResult` | `MemoryUpdate` | Emitted at the end of `runMemoryUpdatePhase`. |
| `StashCreated` | `stashId: String`, `sourcePath: String?`, `reason: StashReason`, `tokenEstimate: Int?` | `MemoryUpdate` | Emitted when content is stashed. |
| `CompactionStarted` | `strategy: PumpStationCompactionStrategy`, `memoryMode: PumpStationMemoryManagementMode` | `Compaction` | Emitted at the start of `runCompactionPhase`. |
| `CompactionCompleted` | `strategy: PumpStationCompactionStrategy`, `memoryMode: PumpStationMemoryManagementMode`, `previousHistorySize: Int`, `newHistorySize: Int`, `result: CompactionResult?` | `Compaction` | Emitted at the end of `runCompactionPhase`. |
| `CompactionAttemptCompleted` | `attempt: Int`, `strategy: PumpStationCompactionStrategy`, `fanout: ChunkFanoutMode?`, `result: CompactionResult` | `Compaction` | v3: emitted per attempt within the loop. |
| `CompactionInflated` | `inputTokens: Int`, `outputTokens: Int`, `attempt: Int`, `willRetry: Boolean` | `Compaction` | v3: emitted when an attempt's summary was larger than input. |
| `CompactionRolledBack` | `backupGeneration: Long`, `reason: String` | `Compaction` | v3: emitted when a `CompactionBackup` is restored. |
| `CompactionHandedOffToTruncation` | `contextWindowBefore: Int`, `contextWindowAfter: Int` | `Compaction` | v3: emitted when retry budget is exhausted. |

### Goal Validation Events

`Pipeline/PumpStationModels.kt:682-700`

| Event | Data | Phase | Description |
|-------|------|-------|-------------|
| `GoalValidationStarted` | (no extra fields) | `GoalValidation` | Emitted at the start of `runExitFlow` when a goal agent is configured. |
| `GoalValidationCompleted` | `passed: Boolean`, `reason: String?` | `GoalValidation` | Emitted at the end of `runExitFlow`. |

### Path and Loop Guard Events

`Pipeline/PumpStationModels.kt:758-796`

| Event | Data | Phase | Description |
|-------|------|-------|-------------|
| `ReservePathRevealed` | `pathName: String`, `reservePathNames: List<String>` | `Dispatch` | Emitted on the first turn a reserve path becomes visible. |
| `LoopGuardTripped` | `guard: String`, `pathName: String`, `detail: String` | `PathExecution` | Emitted when a loop guard fires (`maxConsecutiveSamePath` or `maxTotalPathCallsPerPath`). |
| `ContextBlowoutDetected` | `fillRatio: Double`, `threshold: Double`, `afterPhase: PumpStationPhase` | `MemoryUpdate` | Emitted when context-window fill ratio exceeds `blowoutThreshold` at a phase boundary. |

### Stash and Reserve Path Events

Documented under [Path and Loop Guard Events](#path-and-loop-guard-events) above.

### Harness Outcome Events

`HarnessCompleted` and `HarnessFailed` are documented under [Harness Lifecycle Events](#harness-lifecycle-events) above.

### Miscellaneous Events

`Pipeline/PumpStationModels.kt:812-836`

| Event | Data | Phase | Description |
|-------|------|-------|-------------|
| `NestedP2PCompleted` | `pathName: String?`, `agentName: String`, `response: MultimodalContent?`, `inputTokens: Int?`, `outputTokens: Int?`, `totalTokens: Int?` | `PathExecution` | Emitted when a nested P2P request completes inside a path. |


## Loop Control Models

### TurnResult

`Pipeline/PumpStationModels.kt:844` — Result of a single turn iteration.

```kotlin
sealed class TurnResult {
    object Continue : TurnResult()
    data class Halt(val reason: PumpStationExitReason) : TurnResult()
}
```

`runTurn` returns this to the outer `runHarnessLoop`. `Continue` re-enters the loop; `Halt(reason)` exits with the given reason. The outer loop also catches `KillSwitchException` at the loop boundary and transitions to a `Halt(KillSwitchTripped)` state.

### HarnessAgentSlot

`Pipeline/PumpStationModels.kt:855` — Wrapper for an additional harness agent.

```kotlin
data class HarnessAgentSlot(
    val agent: P2PInterface?,
    val concurrency: PumpStationConcurrencyMode,
    val builderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null
)
```

`concurrency = Blocking` fires synchronously during the foreground phase. `concurrency = Async` queues the agent as a coroutine during the background phase.

### FlagCheckResult

`Pipeline/PumpStationModels.kt:868` — Standardized result of checking `MultimodalContent` control flags.

```kotlin
@Serializable
data class FlagCheckResult(
    val shouldHalt: Boolean = false,
    val shouldPass: Boolean = false,
    val shouldInterrupt: Boolean = false,
    val haltReason: String? = null
)
```

Built by `checkMultimodalFlags(content, source)` in `Pipeline/PumpStationHelpers.kt:459`.

### MemorySnapshot

`Pipeline/PumpStationModels.kt:882` — Captured in-progress state of memory agents.

```kotlin
@Serializable
data class MemorySnapshot(
    val lorebookKeysSnapshot: Map<String, String> = emptyMap(),
    val summarySnapshot: String = "",
    val snapshotAt: Int = 0
)
```

Used by `saveSnapshot()` to record lorebook and summary mid-flight values, so a rollback can restore without losing work.

### DispatchOutput

`Pipeline/PumpStationModels.kt:893` — Result of parsing the dispatch agent's output.

```kotlin
@Serializable
data class DispatchOutput(
    val pathRequest: PathRequest? = null,
    val repairAttempts: Int = 0,
    val parseError: String? = null
)
```

The parser actually returns `PathRequest?` directly (via `parseDispatchOutput`), and the repair loop tracks attempts separately. `DispatchOutput` is the data carrier for callers that want the full result with parse metadata.

### JudgeVerdict

`Pipeline/PumpStationModels.kt:298` — Typed parser output for the judge agent's response.

```kotlin
@Serializable
data class JudgeVerdict(
    val isComplete: Boolean = false,
    val shouldTerminate: Boolean = false,
    val shouldHalt: Boolean = false,
    val reason: PumpStationExitReason? = null
) {
    companion object {
        fun empty() = JudgeVerdict()
    }
}
```

`isComplete` and `shouldTerminate` come from the JSON parser. `shouldHalt` and `reason` are set later by `withFlagCheck(content)` based on the source `MultimodalContent`'s flags.


## Type Aliases

`Pipeline/PumpStationModels.kt:959`:

```kotlin
typealias ReservePathRevealPredicate = (PumpStationTaskState, MutableMap<String, Any>) -> Boolean
```

The `revealWhen` predicate on a `PathObject` is typed as this alias. The receiver is the current task state; the argument is the developer-supplied external context. Returns `true` to reveal the path (sticky once revealed).


## Source File Locations

| Data class group | Source file |
|------------------|-------------|
| `PumpStationConcurrencyMode` | `Pipeline/PumpStation.kt` |
| `PumpStationMemoryManagementMode` | `Pipeline/PumpStation.kt` |
| `PumpStationCompactionStrategy` | `Pipeline/PumpStation.kt` |
| `PumpStationJudgeRunMode` | `Pipeline/PumpStation.kt` |
| `PathRiskLevel` | `Pipeline/PumpStation.kt` |
| `PathRequest` | `Pipeline/PumpStation.kt` |
| `PathDescriptionData` | `Pipeline/PumpStation.kt` |
| `PathDescriptionList` | `Pipeline/PumpStation.kt` |
| `MemoryActionResult` | `Pipeline/PumpStation.kt` |
| `PumpStationStatus`, `PumpStationPhase`, `PumpStationError`, `PumpStationExitReason`, `PumpStationPausePhase` | `Pipeline/PumpStationModels.kt` |
| `StashReason`, `PathLimitExceededPolicy`, `HealthStatus` | `Pipeline/PumpStationModels.kt` |
| `PumpStationEvent` (sealed interface) and all event subtypes | `Pipeline/PumpStationModels.kt` |
| `PumpStationTaskState` | `Pipeline/PumpStationModels.kt` |
| `PumpStationFailurePolicy`, `PumpStationSnapshot`, `StashEntry`, `PathLimitExceededResult` | `Pipeline/PumpStationModels.kt` |
| `TurnResult`, `HarnessAgentSlot`, `FlagCheckResult`, `MemorySnapshot`, `DispatchOutput`, `JudgeVerdict` | `Pipeline/PumpStationModels.kt` |
| `WarningCode`, `ExitMechanism`, `ReservePathRevealPredicate` | `Pipeline/PumpStationModels.kt` |
| `ChunkFanoutMode`, `CompactionCursor`, `LorebookCursor`, `CompactionBackup`, `CompactionResult` | `Pipeline/PumpStationV3Models.kt` |
| `LorebookAgentInput`, `LorebookTaskContext`, `LorebookAgentOutput`, `LorebookUpdate`, `LorebookOperation` | `Pipeline/LorebookAgentModels.kt` |
| `HealthContext`, `HealthReport` | `Pipeline/PumpStationModels.kt` |


## Cross-References

- **[PumpStation Container Doc](../containers/pumpstation.md)** — Architecture, execution flow
- **[PumpStation API Reference](pumpstation.md)** — Public properties, methods, and setters
- **[PumpStation Magic Contracts](../core-concepts/pumpstation-magic-contracts.md)** — JSON schemas and parsers
- **[TPipe-Defaults Package](tpipe-defaults-package.md#pumpstationdefaults)** — `PumpStationDefaults.withOpenRouter` factory
- **[Pipe Context Protocol](pipe-context-protocol.md)** — `PcpContext` referenced by `PathDescriptionData.pcpSchema`
- **[Lorebook API](lorebook.md)** — `LoreBook` referenced by `LorebookAgentInput.currentLorebook`

---
