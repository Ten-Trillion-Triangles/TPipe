
## Path Description Models

### PathRequest

`Pipeline/PumpStation.kt:198` — Emitted by the dispatch agent. The class itself is the magic contract.

```kotlin
@Serializable
data class PathRequest(
    var pathName: String = "",
    var pathSchema: String = "",
    var pathSelectionRationale: String? = null
)
```

`pathName` is matched case-insensitively against `pathList` and `reservePaths`. `pathSchema` is passed to the path as the input. `pathSelectionRationale` is an optional free-text reason the dispatch LLM writes for the path it picked. It rides into `DispatchCompleted.pathRequest` and into the `PUMP_STATION_DISPATCH_COMPLETED` trace event. The field is nullable, so old checkpoints that don't emit it still produce a schema-valid `PathRequest`. When `failurePolicy.requirePathSelectionRationale = true` (the default) and the LLM returns null, the harness appends a one-shot reminder to the next dispatch prompt. See [Dispatch Contract: `pathSelectionRationale`](../containers/pumpstation.md#dispatch-contract-pathselectionrationale).

### PathRequestList

`Pipeline/PumpStationModels.kt:327` — Multi-path counterpart to `PathRequest`. The dispatch LLM emits this when the station is configured with `PathExecutionShape.MultiPath`; the harness parses it and fans the list out via the existing async substrate.

```kotlin
@Serializable
data class PathRequestList(
    val paths: List<PathRequest> = emptyList(),
    val batchRationale: String? = null
)
```

`paths` is the ordered list of path requests to invoke. Each entry follows the same `PathRequest` contract as single-path dispatch — `pathName` must match a visible path, `pathSchema` is passed to the path as the input, `pathSelectionRationale` is captured per-path. `batchRationale` is an optional human-readable explanation of why fan-out was chosen over a single path. It rides into the `PathBatchStarted` event so the trace visualizer can render it as the rationale pill. `paths` must be non-empty after parsing — a `PathRequestList` with no entries is treated as a parse failure by `parseDispatchOutputMulti` and triggers the repair loop.

### PathExecutionShape

`Pipeline/PumpStationModels.kt:358` — Station-level dispatch contract selector. Controls which dispatch prompt template the harness injects, which parser it runs, and which event types it emits.

```kotlin
enum class PathExecutionShape {
    SinglePath,
    MultiPath
}
```

`SinglePath` is the default. The dispatch prompt asks for one `PathRequest`, the parser extracts it via `parseDispatchOutput`, and the harness invokes a single path. The batch-boundary events (`PathBatchStarted`, `PathBatchCompleted`, `PathBatchFailed`) are never emitted.

`MultiPath` switches the contract. The dispatch prompt asks for a `PathRequestList`, the parser extracts it via `parseDispatchOutputMulti`, and the harness launches each path as an async coroutine via the existing async substrate. Results merge into turn history on the next judge. The per-path `PathStarted` / `PathCompleted` / `PathFailed` events are still emitted as today; the new batch events carry the fan-out metadata.

Multi-path fan-out in this phase is fire-and-collect-next-turn: there is no halt-flag aggregation across the batch. Each path runs independently; the harness does not abort the remaining paths on a single-path failure.

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
    var stopHarnessOnInvalidPathRequest: Boolean = false,
    var requirePathSelectionRationale: Boolean = true
)
```

|| Field | Default | Description |
|-------|---------|-------------|
| `repairInvalidDispatchJson` | `true` | Repair malformed dispatch output up to `maxDispatchRepairAttempts` times. |
| `maxDispatchRepairAttempts` | `1` | Max repair attempts. |
| `stashOversizedOutputs` | `true` | Stash oversized path outputs. |
| `callInterventionOnPathFailure` | `true` | Invoke `interventionAgent` after a path failure. |
| `stopHarnessOnInvalidPathRequest` | `false` | When true, set `lastError = DispatchJsonRepairFailed` after repair budget is exhausted. |
| `requirePathSelectionRationale` | `true` | When true, the harness appends a one-shot reminder to the next dispatch prompt if the LLM returned a null `pathSelectionRationale`. Mirrored on `PumpStationBuilder` and on `PumpStation` itself; `setRequirePathSelectionRationale(Boolean)` keeps both sides in sync. See [Dispatch Contract: `pathSelectionRationale`](../containers/pumpstation.md#dispatch-contract-pathselectionrationale). |

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


## Interrupt Models

The interrupt feature (sibling of steering) uses a small set of supporting types. The exception carries the interrupt content + the rewind snapshot; the snapshot captures the harness state at the top of `runTurn` for rewind-on-receive; the configuration data class is the DSL block's serializable form; the policy builder is the user-facing DSL surface; the event surfaces silent overflow drops for observability.

### PumpStationInterruptException

`src/main/kotlin/Pipeline/PumpStationInterruptException.kt` — Runtime exception thrown by `PumpStation.injectInterruptForPhase` when the interrupt service has a queued entry for the polled phase. Caught at the top of `runHarnessLoop` around the `runTurn` invocation.

```kotlin
class PumpStationInterruptException(
    val content: MultimodalContent,           // stamped with the canonical metadata["interrupt"] envelope
    val snapshot: PumpStationInterruptSnapshot  // harness state at the most recent BeforeJudge
) : RuntimeException("PumpStation interrupt fired at turnIndex=${snapshot.turnIndex}")
```

The catch handler restores `snapshot` (turnHistory + four taskState fields), appends `content` to `turnHistory` with the `metadata["interrupt"]` envelope, and `continue`s the `while` loop without advancing `taskState.turnIndex`.

### PumpStationInterruptSnapshot

`src/main/kotlin/Pipeline/PumpStationInterruptSnapshot.kt` — Harness state captured at the top of every `runTurn` for rewind-on-receive. The constructor takes the source list for `turnHistory` and constructs a fresh `turnHistoryCopy` via `.toList()` so subsequent in-flight mutations to the live `ConverseHistory` do not bleed into the snapshot.

```kotlin
class PumpStationInterruptSnapshot(
    val turnIndex: Int,
    val latestContent: MultimodalContent?,
    val lastPathResult: MultimodalContent?,
    val selectedPathName: String?,
    val originalInput: MultimodalContent?,
    turnHistory: List<ConverseData>           // constructor parameter; produces fresh turnHistoryCopy
)
{
    val turnHistoryCopy: List<ConverseData> = turnHistory.toList()
}
```

Captured fields cover the four `taskState` fields that a turn's in-flight work can mutate. Fields NOT captured (status, phase, lastError, exitReason, etc.) are not affected by a turn's in-flight work and are preserved by the rewind unchanged. `rawTurnHistory`, `contextWindow`, `miniBank`, `visiblePathNames`, `reservePathNames` are also NOT captured — they don't change during a single turn and `rawTurnHistory` is intentionally the full event log.

### PumpStationInterruptConfiguration

`src/main/kotlin/Pipeline/PumpStationSteeringModels.kt` — Configuration block set via `pumpStation { interruptPolicy { ... } }`. Holds the initial interrupts queued at construction time. Unlike steering, there is no persistent overlay.

```kotlin
data class PumpStationInterruptConfiguration(
    val initialQueue: Map<PumpStationPausePhase, List<MultimodalContent>> = emptyMap()
)
```

### PumpStationInterruptPolicyBuilder

`src/main/kotlin/Pipeline/PumpStationDsl.kt` — DSL block for seeding `PumpStationInterruptService` at construction time. Mirrors the shape of `SteeringPolicyBuilder` but for interrupts.

```kotlin
@PumpStationDslMarker
class PumpStationInterruptPolicyBuilder
{
    val initialQueue: MutableMap<PumpStationPausePhase, List<MultimodalContent>> = mutableMapOf()

    fun initialQueue(phase: PumpStationPausePhase, contents: List<MultimodalContent>)
    {
        initialQueue[phase] = contents
    }

    internal fun build(): PumpStationInterruptConfiguration
    {
        return PumpStationInterruptConfiguration(initialQueue = initialQueue.toMap())
    }
}
```

Both function-call form `initialQueue(phase, contents)` and indexer form `initialQueue[BeforeJudge] = listOf(...)` are supported.

### InterruptOverflowDropped (event)

`src/main/kotlin/Pipeline/PumpStationModels.kt` — Emitted by `injectInterruptForPhase` when one or more queued interrupt entries were dropped because the steering service is not configured for the phase. The first queued entry was thrown as the active interrupt; the rest had no destination.

```kotlin
@Serializable
data class InterruptOverflowDropped(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Judge,
    val boundaryPhase: PumpStationPausePhase,
    val droppedCount: Int,
    val firstDroppedText: String?
) : PumpStationEvent
```

`firstDroppedText` is truncated to 200 characters to keep the event payload bounded. Operators use this event to detect when a caller is firing more interrupts than the harness can process and the overflow is being silently absorbed. See `TraceEventType.PUMP_STATION_INTERRUPT_OVERFLOW_DROPPED` for the trace-side mirror.


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
|| `HarnessResumed` | (no extra fields) | `Exit` | Emitted when the harness resumes from a pause. |
|| `HarnessWarning` | `code: WarningCode`, `message: String`, `mechanisms: List<ExitMechanism>` | `PreInit` | Advisory. Currently only `NoExitSignalConfigured`. |
|| `InterruptOverflowDropped` | `boundaryPhase: PumpStationPausePhase`, `droppedCount: Int`, `firstDroppedText: String?` | `Judge` | Emitted by `injectInterruptForPhase` when one or more queued interrupt entries were dropped because the steering service is not configured for the phase. The first queued entry was thrown as the active interrupt; the rest had no destination. Operators use this to detect overflow conditions. |

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
| `DispatchCompleted` | `selectedPathName: String?`, `pathRequest: PathRequest?`, `result: MultimodalContent?`, `inputTokens: Int?`, `outputTokens: Int?`, `totalTokens: Int?` | `Dispatch` | Emitted at the end of `runDispatchPhase`. In `PathExecutionShape.MultiPath` mode this event still fires and `pathRequest` carries the first entry of the parsed `PathRequestList` so existing single-path consumers continue to work; the batch detail rides in the separate `PathBatchStarted` / `PathBatchCompleted` events. |

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

### Multi-Path Batch Events

`Pipeline/PumpStationModels.kt:623-676` — Batch-boundary markers for `PathExecutionShape.MultiPath` dispatch. SinglePath mode never emits these events.

| Event | Data | Phase | Description |
|-------|------|-------|-------------|
| `PathBatchStarted` | `pathNames: List<String>`, `batchRationale: String?` | `Dispatch` | Emitted at the start of `runDispatchPhaseMulti` after the dispatch LLM's `PathRequestList` is parsed and before the per-path fan-out. `pathNames` is the ordered list of paths the harness is about to launch; `batchRationale` is the copy from `PathRequestList.batchRationale`. |
| `PathBatchCompleted` | `totalPaths: Int`, `succeededPaths: Int`, `failedPaths: Int` | `Dispatch` | Emitted at the end of `runDispatchPhaseMulti` after every path in the batch has either completed or failed. Per-path detail rides in the standard `PathCompleted` / `PathFailed` events; this event carries the batch totals. |
| `PathBatchFailed` | `errorMessage: String`, `repairAttempts: Int` | `Dispatch` | Emitted when the dispatch LLM output could not be parsed as a `PathRequestList` even after the repair loop exhausted `failurePolicy.maxDispatchRepairAttempts`. Distinct from per-path `PathFailed` — a batch failure means zero paths in the batch were launched. |

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
| `AsyncTurnAppended` | `source: String`, `pathName: String?`, `agentName: String?`, `seq: Long`, `content: MultimodalContent?` | `PathExecution` | Emitted when an async path or async harness agent result is merged into `turnHistory` by the foreground drain. Observers can correlate the merge back to the dispatch via `seq` and `pathName` / `agentName`. |

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
| `LoopGuardTripped` | `guard: String`, `pathName: String`, `detail: String`, `metric: String`, `observed: Int`, `limit: Int` | `PathExecution` | Emitted when a loop guard fires. The `guard` field discriminates which guard tripped: `maxConsecutiveSamePath` (same path dispatched N consecutive turns), `maxTotalPathCallsPerPath` (path call-count cap exceeded), or `maxConsecutiveUnknownPaths` (N consecutive dispatches of unregistered path names). |
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
    val builderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null,
    val appendsToTurnHistory: Boolean = false
)
```

`concurrency = Blocking` fires synchronously during the foreground phase. `concurrency = Async` queues the agent as a coroutine during the background phase.

`appendsToTurnHistory` (default `false`) is honoured only when `concurrency = Async`. When `true`, the agent's result is captured into a `PendingTurnEntry` and merged into `turnHistory` by the foreground drain at the next safe phase boundary. When `false`, the result is discarded, preserving the historical fire-and-forget semantics. The station-wide `asyncAgentsAppendToTurnHistory` flag can be used as an umbrella default; per-slot flags override the station default.

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


## Async Substrate Models

### PendingTurnEntry

`Pipeline/PumpStationModels.kt:863` — A turn entry produced by an async path or async harness agent. Held in the station's `pendingAsyncResults` channel and merged into `turnHistory` in monotonic `seq` order during a foreground drain.

```kotlin
data class PendingTurnEntry(
    val seq: Long,
    val turnIndex: Int,
    val pathName: String?,
    val agentName: String?,
    val source: String,
    val result: MultimodalContent,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val passPipeline: Boolean = false,
    val terminatePipeline: Boolean = false
)
```

| Field | Description |
|-------|-------------|
| `seq` | Monotonic id assigned at enqueue time by an `AtomicLong` counter. The foreground drain sorts pending entries by `seq` so out-of-order async completions still produce a deterministic merge order from the LLM's perspective. |
| `turnIndex` | Snapshot of `taskState.turnIndex` at the time the entry was enqueued. Used by observers to correlate the entry back to the originating turn. |
| `pathName` | Name of the path that produced the result, or `null` for harness-agent-originated entries. |
| `agentName` | Simple class name of the agent that produced the result, or `null` for path-originated entries. |
| `source` | Short producer identifier (e.g. `"asyncPath"`, `"asyncHarnessAgent"`). |
| `result` | The async producer's `MultimodalContent` output. |
| `inputTokens`, `outputTokens`, `totalTokens` | Optional token usage captured from the producer's response. |
| `passPipeline`, `terminatePipeline` | Control flags lifted from the result's `MultimodalContent`. The foreground drain carries them forward for the existing loop-control path. |

`PendingTurnEntry` is the queue payload. The merged-into-history event is `AsyncTurnAppended`.

### AsyncTurnAppended (event)

`Pipeline/PumpStationModels.kt:885` — An async path or async harness agent result was merged into `turnHistory` by the foreground drain.

```kotlin
@kotlinx.serialization.Serializable
data class AsyncTurnAppended(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PathExecution,
    val source: String,
    val pathName: String?,
    val agentName: String?,
    val seq: Long,
    val content: MultimodalContent?
) : PumpStationEvent
```

Observers can correlate the merge back to the dispatch via `seq` and `pathName` / `agentName`. The trace funnel maps this event to `TraceEventType.PUMP_STATION_ASYNC_TURN_APPENDED`.


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
| `PathRequestList`, `PathExecutionShape` | `Pipeline/PumpStationModels.kt` |
| `PathDescriptionData`, `PathDescriptionList` | `Pipeline/PumpStation.kt` |
| `MemoryActionResult` | `Pipeline/PumpStation.kt` |
| `PumpStationStatus`, `PumpStationPhase`, `PumpStationError`, `PumpStationExitReason`, `PumpStationPausePhase` | `Pipeline/PumpStationModels.kt` |
| `StashReason`, `PathLimitExceededPolicy`, `HealthStatus` | `Pipeline/PumpStationModels.kt` |
| `PumpStationEvent` (sealed interface) and all event subtypes | `Pipeline/PumpStationModels.kt` |
| `PumpStationTaskState` | `Pipeline/PumpStationModels.kt` |
| `PumpStationFailurePolicy`, `PumpStationSnapshot`, `StashEntry`, `PathLimitExceededResult` | `Pipeline/PumpStationModels.kt` |
| `TurnResult`, `HarnessAgentSlot`, `FlagCheckResult`, `MemorySnapshot`, `DispatchOutput`, `JudgeVerdict` | `Pipeline/PumpStationModels.kt` |
| `PendingTurnEntry`, `AsyncTurnAppended` | `Pipeline/PumpStationModels.kt` |
| `WarningCode`, `ExitMechanism`, `ReservePathRevealPredicate` | `Pipeline/PumpStationModels.kt` |
| `ChunkFanoutMode`, `CompactionCursor`, `LorebookCursor`, `CompactionBackup`, `CompactionResult` | `Pipeline/PumpStationV3Models.kt` |
| `LorebookAgentInput`, `LorebookTaskContext`, `LorebookAgentOutput`, `LorebookUpdate`, `LorebookOperation` | `Pipeline/LorebookAgentModels.kt` |
| `HealthContext`, `HealthReport` | `Pipeline/PumpStationModels.kt` |


## Cross-References

- **[PumpStation Container Doc](../containers/pumpstation.md)** — Architecture, execution flow
- **[PumpStation API Reference](pumpstation.md)** — Public properties, methods, and setters
- **[PumpStation Magic Contracts](../core-concepts/pumpstation-magic-contracts.md)** — JSON schemas and parsers, including `parseDispatchOutputMulti` for the multi-path contract
- **[TPipe-Defaults Package](tpipe-defaults-package.md#pumpstationdefaults)** — `PumpStationDefaults.withOpenRouter` factory
- **[Pipe Context Protocol](pipe-context-protocol.md)** — `PcpContext` referenced by `PathDescriptionData.pcpSchema`
- **[Lorebook API](lorebook.md)** — `LoreBook` referenced by `LorebookAgentInput.currentLorebook`

---
