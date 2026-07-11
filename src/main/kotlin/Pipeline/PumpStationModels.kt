package com.TTT.Pipeline

import com.TTT.Context.ConverseHistory
import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.P2P.P2PInterface
import com.TTT.Pipe.MultimodalContent
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Cleanup strategies that the SafePrune phase can apply to [com.TTT.Context.ConverseHistory]
 * before the LLM-requiring phases of [PumpStation] run. Each strategy is a deterministic,
 * LLM-free transform — the user opts in per strategy. Off by default; when none are enabled
 * the SafePrune phase is a no-op.
 *
 * Strategies are applied in declared order during a single pass; later strategies see the
 * output of earlier ones.
 */
enum class SafePruneStrategy
{
    /** Rewrite older entries whose text already appears in [turnSummary] to a `[See turnSummary]` marker. */
    ReplaceWithSummaryRef,

    /** Drop entries whose text is byte-identical to the immediately-preceding entry. */
    DropPureEchoes,

    /** Collapse adjacent tool_call/tool_response pairs into a single `[tool-call: {name}]` marker. */
    CollapseToolCallResults,

    /** Drop entries whose text hash matches an earlier entry within the last [safePruneHashWindow] entries. */
    DeduplicateByHash,

    /** Replace tool_response entries whose text exceeds [safePruneMaxToolArgLength] with a truncated stub. */
    StripLongToolArguments,

    /** Drop system-role entries whose text is empty and which carry only metadata. */
    MetadataOnlyCompression
}

/**
 * Summary payload emitted by the SafePrune phase on a single turn. Captures the count of
 * enabled strategies, the history size before and after the pass, the rough token delta,
 * and the turn index at which the pass ran. Carried on the [SafePruneApplied] event so
 * observers (tracing, tests, observability) can reconstruct what happened.
 *
 * @property enabledFlags Strategies that were active during this pass.
 * @property originalCount Entries in turnHistory before the pass.
 * @property finalCount Entries in turnHistory after the pass.
 * @property tokensRemoved Estimated tokens saved by the pass (sum of removed text lengths / 4).
 * @property firedAtTurnIndex Turn index at which the pass executed.
 */
@Serializable
data class SafePruneReport(
    val enabledFlags: Set<SafePruneStrategy>,
    val originalCount: Int,
    val finalCount: Int,
    val tokensRemoved: Int,
    val firedAtTurnIndex: Int
)

/**
 * Emitted at the end of every SafePrune phase run (only when at least one strategy fired).
 * Carries the [SafePruneReport] so downstream tracing can show per-turn savings.
 */
@Serializable
data class SafePruneApplied(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.SafePrune,
    val report: SafePruneReport
) : PumpStationEvent

/**
 * Per-strategy policy override. When a strategy has a non-null policy in
 * [PumpStation.safePruneStrategyPolicies], the policy's `sizeThreshold` and
 * `protectRecentN` override the corresponding PumpStation-global values for
 * that strategy only. Null values mean "fall back to the global knob".
 *
 * Use this when one strategy needs a tighter or looser threshold than the
 * PumpStation-wide default — e.g., StripLongToolArguments can be set to a
 * higher threshold than DeduplicateByHash without changing the global cap.
 *
 * @property sizeThreshold Optional per-strategy size threshold; null = use global.
 * @property protectRecentN Optional per-strategy protected-recent-N; null = use global.
 * @property customParams Free-form per-strategy parameters; reserved for
 *   strategies that need extra knobs beyond size + protection.
 */
@Serializable
data class SafePrunePolicy(
    val sizeThreshold: Int? = null,
    val protectRecentN: Int? = null,
    val customParams: Map<String, String> = emptyMap()
)

/**
 * Emitted at the end of every SafePrune phase run when at least one enabled
 * strategy is in dry-run mode and produced a mutation (a hypothetical rewrite).
 * The [SafePruneReport] describes what WOULD have been changed; the actual
 * turnHistory is unchanged. SafePruneDryRunCompleted and [SafePruneApplied]
 * are mutually exclusive on a given turn — dry-run replaces apply.
 */
@Serializable
data class SafePruneDryRunCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.SafePruneDryRun,
    val report: SafePruneReport
) : PumpStationEvent

/**
 * Models for the PumpStation scaffolding system.
 *
 * Contains enums, sealed interfaces, data classes, and type aliases used by the
 * PumpStation harness for lifecycle management, event tracking, failure recovery,
 * state inspection, and memory management.
 */

//=========================================Enums====================================================================

/**
 * Current status of the PumpStation harness.
 */
enum class PumpStationStatus
{
    NotStarted,
    Running,
    WaitingOnBackground,
    Suspended,
    Completed,
    Failed,
    Terminated
}

/**
 * The current phase of the harness execution loop.
 */
enum class PumpStationPhase
{
    PreInit,
    HealthCheck,
    Judge,
    Dispatch,
    PathSafety,
    PathExecution,
    PathValidation,
    Intervention,
    ForegroundAgents,
    MemoryUpdate,
    Compaction,
    SafePrune,
    SafePruneDryRun,
    GoalValidation,
    Exit
}

/**
 * Errors that can occur during PumpStation execution.
 */
enum class PumpStationError
{
    UnknownPath,
    InvalidPathRequest,
    DispatchJsonRepairFailed,
    PathExecutionException,
    // Path call timed out (transport-layer). Distinct from
    // PathExecutionException so the harness and operators can tell
    // timeouts apart from malformed-response or code exceptions.
    PathTimeout,
    TokenBudgetExceeded,
    MemoryBlowout,
    KillSwitchTripped,
    MaxTurnsExceeded,
    LoopGuardTriggered,
    P2PRequestInvalid,
    InitNotCalled,
    // v3: compaction produced more tokens than it consumed; orchestrator handed off to
    // truncation. Distinct from MemoryBlowout (which is a hard context-overflow signal).
    CompactionInflated,
    // v3: rollback DITL hook elected to halt the harness on rollback. Default behavior
    // is to retry with smaller scope; this variant only appears when the developer chose
    // to surface a halt via taskState.lastError.
    CompactionRolledBack
}

/**
 * Reason the harness exited (used in task state and events).
 */
enum class PumpStationExitReason
{
    JudgeComplete,
    PassSignal,
    TerminateSignal,
    MaxTurnsHit,
    KillSwitchTripped,
    LoopGuardTripped,
    GoalValidationFailed,
    InterventionTerminated,
    Error
}

/**
 * Phase boundaries at which the harness can pause for external inspection/intervention.
 */
enum class PumpStationPausePhase
{
    BeforeJudge,
    AfterJudge,
    BeforeDispatch,
    AfterDispatch,
    BeforePathSafety,
    BeforePathExecution,
    AfterPathExecution,
    BeforeMemoryUpdate,
    BeforeCompaction,
    BeforeGoalValidation,
    BeforeExit
}

/**
 * Reason an output was stashed rather than kept in the turn history directly.
 */
enum class StashReason
{
    TokenOverflow,
    BinaryPayload,
    ErrorLog,
    UnsafeForPrompt,
    DeveloperRequested,
    BackgroundResult
}

/**
 * Policy for how the harness responds when [maxTotalPathCallsPerPath] is exceeded.
 *
 * [Skip] removes the path from dispatch visibility by moving it to reserve — the dispatch
 * agent will no longer see it until the harness resets or the path is explicitly revealed.
 *
 * [Halt] terminates the harness immediately with [PumpStationError.MaxTurnsExceeded].
 *
 * [Continue] logs the violation and proceeds with path execution anyway.
 */
enum class PathLimitExceededPolicy
{
    Skip,
    Halt,
    Continue
}

/**
 * Health status levels that healthAgent can report.
 */
enum class HealthStatus
{
    /** Harness is operating normally with no detected issues. */
    Healthy,
    /** Harness is functioning but some degradation or concern detected. */
    Degraded,
    /** Harness is showing serious signs of going off-rails or context drift. */
    Critical,
    /** Agent unable to determine health status (insufficient data). */
    Unknown
}

/**
 * Structured context passed to healthAgent as JSON in MultimodalContent.text.
 * Contains all state healthAgent needs to assess harness wellness.
 */
@kotlinx.serialization.Serializable
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

/**
 * Result returned by healthAgent. Contains the agent's assessment of harness wellness.
 */
@kotlinx.serialization.Serializable
data class HealthReport(
    val status: HealthStatus = HealthStatus.Unknown,
    val warnings: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val metrics: Map<String, Double> = emptyMap(),
    val suggestedNextPath: String? = null,
    val terminateHarness: Boolean = false
)

/**
 * Result returned by pathLimitExceededFunction when maxTotalPathCallsPerPath is exceeded.
 * Allows dynamic runtime policy instead of static PathLimitExceededPolicy.
 */
@kotlinx.serialization.Serializable
data class PathLimitExceededResult(
    val action: PathLimitExceededPolicy,
    val reason: String = "",
    val nextPathOverride: String? = null
)

/**
 * Multi-path dispatch output. Wraps a list of [PathRequest]s plus an optional
 * rationale explaining why the dispatcher chose to fan out instead of picking
 * a single path.
 *
 * This shape is only produced when the [com.TTT.Pipeline.PumpStation] is
 * configured with [PathExecutionShape.MultiPath]. SinglePath mode never
 * produces or consumes this type.
 *
 * @property paths Ordered list of path requests to fan out. Each path
 *   follows the same [PathRequest] contract as single-path dispatch.
 * @property batchRationale Optional human-readable explanation of why the
 *   fan-out was chosen. Surfaced in the [PathBatchStarted] event so the
 *   trace visualizer can render it.
 */
@kotlinx.serialization.Serializable
data class PathRequestList(
    val paths: List<PathRequest> = emptyList(),
    val batchRationale: String? = null
)

//=========================================Dispatch Contract Shape============================================

/**
 * Dispatch contract shape for the harness.
 *
 * SinglePath (default) preserves the pre-existing dispatch JSON contract —
 * dispatch LLM returns one [com.TTT.Pipeline.PathRequest] per turn, harness
 * invokes one path.
 *
 * MultiPath injects the multi-path dispatch prompt and parses a
 * [PathRequestList] containing one or more [com.TTT.Pipeline.PathRequest]s.
 * The harness fans the parsed list out via the existing async substrate
 * (see [com.TTT.Pipeline.PumpStation.launchAsyncPath]) and merges results
 * into turn history on the next judge.
 *
 * MultiPath does NOT introduce halt-flag aggregation in this phase — the
 * fan-out is fire-and-collect-next-turn. Each path still emits its own
 * path events; new batch-boundary events ([PathBatchStarted] /
 * [PathBatchCompleted] / [PathBatchFailed]) carry the fan-out metadata.
 *
 * @property SinglePath Default. Today's dispatch contract, today's prompt,
 *   today's parse, today's single-path execution. Never emits batch events.
 * @property MultiPath New contract. The dispatch prompt asks for a list of
 *   paths, the parser extracts [PathRequestList], the harness launches each
 *   path as an async coroutine via the existing async substrate.
 */
enum class PathExecutionShape
{
    SinglePath,
    MultiPath
}

//=========================================Sealed Interface & Events==============================================

/**
 * Sealed interface for all PumpStation lifecycle events.
 * Used internally for event log, trace emission, and DITL inspection.
 * Projected into visible history, raw history, and trace output.
 */
@kotlinx.serialization.Serializable
sealed interface PumpStationEvent
{
    val runId: String
    val turnIndex: Int
    val timestamp: Long
    val phase: PumpStationPhase
}

/**
 * Harness has started execution with the given original input.
 */
@kotlinx.serialization.Serializable
data class HarnessStarted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PreInit,
    val originalInput: MultimodalContent?
) : PumpStationEvent

/**
 * Pre-initialization phase completed.
 */
@kotlinx.serialization.Serializable
data class PreInitCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PreInit
) : PumpStationEvent

/**
 * Judge phase started.
 */
@kotlinx.serialization.Serializable
data class JudgeStarted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Judge
) : PumpStationEvent

/**
 * Judge phase completed with completion and termination signals.
 *
 * [result] carries the judge's [MultimodalContent] response so the visualizer can render the
 * judge's reasoning and full text in the turn-detail view. The [inputTokens] / [outputTokens] /
 * [totalTokens] fields expose the judge's token usage; any of them may be null when the agent
 * does not track usage (e.g. a non-Pipe P2PInterface implementation).
 */
@kotlinx.serialization.Serializable
data class JudgeCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Judge,
    val isComplete: Boolean,
    val shouldTerminate: Boolean,
    val result: MultimodalContent? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null
) : PumpStationEvent

/**
 * Judge phase was skipped because the harness was in [PumpStationJudgeRunMode.FlagTriggered] mode
 * and the [PumpStationTaskState.requestJudgeNextTurn] flag was not set at the top of the judge
 * phase. Emitted in place of [JudgeStarted] / [JudgeCompleted] so the trace / visualizer can
 * show when the judge was bypassed to save tokens.
 *
 * [reason] is a short code describing why the judge was skipped (currently always `"no_flag_set"`).
 * [judgeRunMode] carries the active mode so the visualizer can render the skip-row with the
 * correct context.
 */
@kotlinx.serialization.Serializable
data class JudgeSkipped(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Judge,
    val reason: String,
    val judgeRunMode: PumpStationJudgeRunMode
) : PumpStationEvent

/**
 * Typed parser output for the judge agent's response. Encodes the LLM's
 * verdict on task completion plus the MultimodalContent flags for loop control.
 */
@kotlinx.serialization.Serializable
data class JudgeVerdict(
    val isComplete: Boolean = false,
    val shouldTerminate: Boolean = false,
    val shouldHalt: Boolean = false,
    val reason: PumpStationExitReason? = null
)
{
    companion object
    {
        fun empty() = JudgeVerdict()
    }
}

/**
 * Dispatch phase started.
 */
@kotlinx.serialization.Serializable
data class DispatchStarted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Dispatch
) : PumpStationEvent

/**
 * Dispatch phase completed with selected path information.
 *
 * [result] carries the dispatch agent's raw [MultimodalContent] response; the visualizer uses
 * this to render the dispatcher's reasoning. The [inputTokens] / [outputTokens] / [totalTokens]
 * fields expose the dispatcher's token usage; any of them may be null when the agent does not
 * track usage.
 */
@kotlinx.serialization.Serializable
data class DispatchCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Dispatch,
    val selectedPathName: String?,
    val pathRequest: PathRequest?,
    val result: MultimodalContent? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null
) : PumpStationEvent

/**
 * A path was selected for execution.
 */
@kotlinx.serialization.Serializable
data class PathSelected(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Dispatch,
    val pathName: String,
    val riskLevel: PathRiskLevel
) : PumpStationEvent

/**
 * Path safety check started.
 */
@kotlinx.serialization.Serializable
data class PathSafetyStarted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PathSafety,
    val pathName: String,
    val riskLevel: PathRiskLevel
) : PumpStationEvent

/**
 * Path safety check completed with approval decision.
 */
@kotlinx.serialization.Serializable
data class PathSafetyCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PathSafety,
    val pathName: String,
    val riskLevel: PathRiskLevel,
    val approved: Boolean,
    val reason: String?
) : PumpStationEvent

/**
 * Path execution started.
 */
@kotlinx.serialization.Serializable
data class PathStarted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PathExecution,
    val pathName: String,
    val riskLevel: PathRiskLevel
) : PumpStationEvent

/**
 * Path execution completed successfully.
 *
 * [result] carries the path's [MultimodalContent] output; the visualizer renders this in a
 * collapsible panel so developers can see what the path produced. The [inputTokens] /
 * [outputTokens] / [totalTokens] fields expose the path's token usage; any of them may be
 * null when the path does not track usage (e.g. a non-Pipe P2PInterface implementation).
 */
@kotlinx.serialization.Serializable
data class PathCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PathExecution,
    val pathName: String,
    val riskLevel: PathRiskLevel,
    val result: MultimodalContent?,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null
) : PumpStationEvent

/**
 * Intervention phase started — emitted before calling the intervention agent.
 */
@kotlinx.serialization.Serializable
data class InterventionStarted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Intervention,
    val trigger: PumpStationError,
    val pathName: String
) : PumpStationEvent

/**
 * Path execution failed.
 */
@kotlinx.serialization.Serializable
data class PathFailed(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PathExecution,
    val pathName: String,
    val riskLevel: PathRiskLevel,
    val error: PumpStationError,
    val errorMessage: String?
) : PumpStationEvent

/**
 * A batch of paths was selected for fan-out execution. Emitted at the start
 * of the multi-path dispatch phase when [com.TTT.Pipeline.PumpStation] is
 * configured with [PathExecutionShape.MultiPath].
 *
 * SinglePath mode never emits this event.
 *
 * @property pathNames Ordered list of path names in the batch.
 * @property batchRationale Optional rationale copied from
 *   [PathRequestList.batchRationale].
 */
@kotlinx.serialization.Serializable
data class PathBatchStarted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Dispatch,
    val pathNames: List<String>,
    val batchRationale: String?
) : PumpStationEvent

/**
 * All paths in a multi-path batch have completed (or failed). Emitted at the
 * end of the dispatch phase after the harness has launched every path in
 * [PathBatchStarted.pathNames]. The per-path [PathStarted] / [PathCompleted]
 * / [PathFailed] events carry the per-path detail; this event marks the
 * batch boundary.
 *
 * SinglePath mode never emits this event.
 *
 * @property totalPaths Number of paths in the batch.
 * @property succeededPaths Number of paths that completed without an error.
 * @property failedPaths Number of paths that emitted [PathFailed].
 */
@kotlinx.serialization.Serializable
data class PathBatchCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Dispatch,
    val totalPaths: Int,
    val succeededPaths: Int,
    val failedPaths: Int
) : PumpStationEvent

/**
 * Multi-path dispatch itself failed (the dispatch LLM output could not be
 * parsed as a [PathRequestList] even after repair). Emitted when the
 * repair loop is exhausted; the harness continues to the next turn. This
 * is distinct from per-path [PathFailed] — batch-level failures mean
 * zero paths in the batch were launched.
 *
 * SinglePath mode never emits this event.
 *
 * @property errorMessage Human-readable description of the failure.
 * @property repairAttempts Number of repair iterations attempted.
 */
@kotlinx.serialization.Serializable
data class PathBatchFailed(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Dispatch,
    val errorMessage: String,
    val repairAttempts: Int
) : PumpStationEvent

/**
 * Path was hidden from dispatch due to exceeding call limits.
 */
@kotlinx.serialization.Serializable
data class PathHidden(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PathExecution,
    val pathName: String,
    val reason: String
) : PumpStationEvent

/**
 * Path validation completed.
 */
@kotlinx.serialization.Serializable
data class PathValidationCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PathValidation,
    val pathName: String,
    val approved: Boolean,
    val reason: String?
) : PumpStationEvent

/**
 * Intervention phase completed.
 *
 * [result] carries the intervention agent's [MultimodalContent] response. The
 * [inputTokens] / [outputTokens] / [totalTokens] fields expose the intervention agent's
 * token usage; any of them may be null when the agent does not track usage.
 */
@kotlinx.serialization.Serializable
data class InterventionCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Intervention,
    val nudges: Int,
    val shouldContinue: Boolean,
    val result: MultimodalContent? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null
) : PumpStationEvent

/**
 * Health check phase completed — emitted after healthAgent finishes its assessment.
 */
@kotlinx.serialization.Serializable
data class HealthCheckCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.HealthCheck,
    val status: HealthStatus,
    val warnings: Int,
    val terminateHarness: Boolean
) : PumpStationEvent

/**
 * Health check phase started — emitted before calling the healthAgent.
 */
@kotlinx.serialization.Serializable
data class HealthCheckStarted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.HealthCheck
) : PumpStationEvent

/**
 * Foreground agent completed execution.
 *
 * [result] carries the foreground agent's [MultimodalContent] response; the visualizer renders
 * this in a collapsible panel. The [inputTokens] / [outputTokens] / [totalTokens] fields expose
 * the agent's token usage; any of them may be null when the agent does not track usage.
 */
@kotlinx.serialization.Serializable
data class ForegroundAgentCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.ForegroundAgents,
    val agentName: String,
    val result: MultimodalContent?,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null
) : PumpStationEvent

/**
 * Memory update phase started.
 */
@kotlinx.serialization.Serializable
data class MemoryUpdateStarted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.MemoryUpdate,
    val memoryMode: PumpStationMemoryManagementMode
) : PumpStationEvent

/**
 * Memory update phase completed.
 */
@kotlinx.serialization.Serializable
data class MemoryUpdateCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.MemoryUpdate,
    val memoryMode: PumpStationMemoryManagementMode,
    @Contextual val result: MemoryActionResult
) : PumpStationEvent

/**
 * Content was stashed for later retrieval.
 */
@kotlinx.serialization.Serializable
data class StashCreated(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.MemoryUpdate,
    val stashId: String,
    val sourcePath: String?,
    val reason: StashReason,
    val tokenEstimate: Int?
) : PumpStationEvent

/**
 * Compaction phase started.
 */
@kotlinx.serialization.Serializable
data class CompactionStarted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Compaction,
    val strategy: PumpStationCompactionStrategy,
    val memoryMode: PumpStationMemoryManagementMode
) : PumpStationEvent

/**
 * Compaction phase completed.
 */
@kotlinx.serialization.Serializable
data class CompactionCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Compaction,
    val strategy: PumpStationCompactionStrategy,
    val memoryMode: PumpStationMemoryManagementMode,
    val previousHistorySize: Int,
    val newHistorySize: Int,
    // v3: the final CompactionResult of the attempt loop. Optional for backward
    // compat with serialized traces from v2.
    val result: CompactionResult? = null
) : PumpStationEvent

/**
 * v3: emitted after a single compaction attempt, regardless of outcome. Lets the
 * visualizer show the per-attempt journey (e.g. "attempt 1: Inflated, attempt 2:
 * Chunked-Parallel, Applied"). Distinct from [CompactionCompleted] which marks the end of
 * the whole [runCompactionPhase] attempt loop.
 */
@kotlinx.serialization.Serializable
data class CompactionAttemptCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Compaction,
    val attempt: Int,
    val strategy: PumpStationCompactionStrategy,
    val fanout: ChunkFanoutMode?,
    val result: CompactionResult
) : PumpStationEvent

/**
 * v3: emitted when a compaction attempt produced a summary whose estimated token count
 * was greater than the input's. The orchestrator will retry with smaller scope unless
 * the retry budget is exhausted, in which case the run is handed off to truncation.
 */
@kotlinx.serialization.Serializable
data class CompactionInflated(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Compaction,
    val inputTokens: Int,
    val outputTokens: Int,
    val attempt: Int,
    val willRetry: Boolean
) : PumpStationEvent

/**
 * v3: emitted when a [CompactionBackup] is restored to the [PumpStation]. Either the
 * DITL hook returned a replacement backup, or the orchestrator restored the most-recent
 * one as part of an [CompactionResult.Inflated] retry.
 */
@kotlinx.serialization.Serializable
data class CompactionRolledBack(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Compaction,
    val backupGeneration: Long,
    val reason: String
) : PumpStationEvent

/**
 * v3: emitted when the compaction retry budget is exhausted and the orchestrator has
 * handed off to the existing `failurePolicy`-driven truncation path. The harness
 * continues; the kill switch is NOT tripped (kill switch is an independent cost-control
 * system).
 */
@kotlinx.serialization.Serializable
data class CompactionHandedOffToTruncation(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Compaction,
    val contextWindowBefore: Int,
    val contextWindowAfter: Int
) : PumpStationEvent

/**
 * Goal validation phase started.
 */
@kotlinx.serialization.Serializable
data class GoalValidationStarted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.GoalValidation
) : PumpStationEvent

/**
 * Goal validation phase completed.
 */
@kotlinx.serialization.Serializable
data class GoalValidationCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.GoalValidation,
    val passed: Boolean,
    val reason: String?
) : PumpStationEvent

/**
 * Post-success hook completed inside [runExitFlow]. Fires on every successful exit
 * through the exit flow — including the no-goal-agent and passPipeline-routed paths.
 * Does NOT fire on [com.TTT.Pipeline.PumpStationError.GoalValidationFailed] failure
 * exhaustion halts.
 *
 * [passed] indicates whether the optional [com.TTT.Pipeline.PumpStation.postGoalAgent]
 * signaled failure via [com.TTT.Pipe.MultimodalContent.terminatePipeline] on its result.
 * [transformedContent] indicates whether [com.TTT.Pipeline.PumpStation.postGoalFunction]
 * modified its input. When both function and agent are absent, the harness emits this
 * event with [passed]=true and [transformedContent]=false as a default-pass marker so
 * observers can still correlate every `runExitFlow` invocation.
 *
 * [reason] is populated with the post-goal agent's result text when [passed]=false;
 * null otherwise.
 */
@kotlinx.serialization.Serializable
data class PostGoalCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Exit,
    val passed: Boolean,
    val reason: String?,
    val transformedContent: Boolean
) : PumpStationEvent

/**
 * Harness completed execution successfully.
 */
@kotlinx.serialization.Serializable
data class HarnessCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Exit,
    val exitReason: PumpStationExitReason,
    val finalOutput: MultimodalContent?
) : PumpStationEvent

/**
 * Harness failed with an error.
 */
@kotlinx.serialization.Serializable
data class HarnessFailed(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Exit,
    val error: PumpStationError,
    val errorMessage: String?,
    val exitReason: PumpStationExitReason
) : PumpStationEvent

/**
 * Harness was suspended at specified pause phases.
 */
@kotlinx.serialization.Serializable
data class HarnessSuspended(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Exit,
    val pausedAt: Set<PumpStationPausePhase>,
    val reason: String?
) : PumpStationEvent

/**
 * Harness was resumed from a suspended state.
 */
@kotlinx.serialization.Serializable
data class HarnessResumed(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Exit
) : PumpStationEvent

/**
 * A reserve path's reveal predicate evaluated to true, making the path visible to the
 * dispatch agent. Sticky — once revealed, the path stays visible until the harness resets.
 */
@kotlinx.serialization.Serializable
data class ReservePathRevealed(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.Dispatch,
    val pathName: String,
    val reservePathNames: List<String>
) : PumpStationEvent

/**
 * A loop guard fired and altered dispatch behavior. Examples: max consecutive same path,
 * max total calls per path, or the per-path limit exceeded policy (Skip / Halt / Continue).
 */
@kotlinx.serialization.Serializable
data class LoopGuardTripped(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PathExecution,
    val guard: String,
    val pathName: String,
    val detail: String,
    val metric: String,
    val observed: Int,
    val limit: Int
) : PumpStationEvent

/**
 * Context window fill ratio exceeded the configured [com.TTT.Pipeline.PumpStation.blowoutThreshold]
 * during the harness loop. The harness responds by stashing oversized content and triggering
 * compaction per the failure recovery policy.
 */
@kotlinx.serialization.Serializable
data class ContextBlowoutDetected(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.MemoryUpdate,
    val fillRatio: Double,
    val threshold: Double,
    val afterPhase: PumpStationPhase
) : PumpStationEvent

/**
 * A nested P2P request — `executeP2PRequest(...)` was invoked from within a path — completed.
 *
 * Nested P2P calls happen when a path internally dispatches to a child agent (e.g. a research
 * sub-path or a helper LLM) via the P2P surface. The harness records the child call's
 * [MultimodalContent] response and token usage here so the visualizer can render the nested
 * call as a sub-entry under the parent path's content panel. [pathName] is the name of the
 * path that issued the nested call, sourced from [PumpStationTaskState.currentPathName];
 * null when the call did not originate from a path.
 *
 * The [inputTokens] / [outputTokens] / [totalTokens] fields expose the nested agent's token
 * usage; any of them may be null when the agent does not track usage.
 */
@kotlinx.serialization.Serializable
data class NestedP2PCompleted(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PathExecution,
    val pathName: String?,
    val agentName: String,
    val response: MultimodalContent?,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null
) : PumpStationEvent

/**
 * A background (Async concurrency) harness agent was queued for asynchronous execution. The
 * actual completion will be reported by a separate event when the agent finishes.
 */
@kotlinx.serialization.Serializable
data class BackgroundAgentQueued(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.ForegroundAgents,
    val agentName: String
) : PumpStationEvent

//=========================================Turn Control============================================================

/**
 * Result of a single turn iteration. Continue means the loop should re-enter;
 * Halt means the loop should exit with the given reason.
 */
sealed class TurnResult
{
    object Continue : TurnResult()
    data class Halt(val reason: PumpStationExitReason) : TurnResult()
}

/**
 * Wrapper for an additional harness agent. Each slot has a concurrency mode
 * (Blocking = foreground, runs synchronously; Async = background, runs queued).
 * The agent can be supplied directly or via a builder function for per-turn freshness.
 */

/**
 * A turn entry produced by an async path or async harness agent. Held in a
 * station-scoped queue and merged into [com.TTT.Context.ConverseHistory] in
 * monotonic [seq] order during a foreground drain. The [seq] is assigned at
 * enqueue time so that out-of-order completions still produce a deterministic
 * merge order from the LLM's perspective.
 */
//=========================================Async Turn Queue=======================================================

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

/**
 * An async path or async harness agent result was merged into the harness
 * [com.TTT.Context.ConverseHistory] by the foreground drain. Observers can
 * correlate the merge back to the dispatch via [seq] and [pathName] / [agentName].
 */
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

//=========================================Turn Control============================================================

/**
 * Wrapper for an additional harness agent. Each slot has a concurrency mode
 * (Blocking = foreground, runs synchronously; Async = background, runs queued).
 * The agent can be supplied directly or via a builder function for per-turn freshness.
 */
data class HarnessAgentSlot(
    val agent: P2PInterface?,
    val concurrency: PumpStationConcurrencyMode,
    val builderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null,
    val appendsToTurnHistory: Boolean = false
)

//=========================================Loop Control Flags======================================================

/**
 * Standardized result of checking MultimodalContent control flags.
 * Used to drive loop control without magic contracts.
 */
@kotlinx.serialization.Serializable
data class FlagCheckResult(
    val shouldHalt: Boolean = false,
    val shouldPass: Boolean = false,
    val shouldInterrupt: Boolean = false,
    val haltReason: String? = null
)

//=========================================Memory & Dispatch=======================================================

/**
 * Captured in-progress state of memory agents. Used by saveSnapshot() to record
 * lorebook and summary mid-flight values, so a rollback can restore without losing work.
 */
@kotlinx.serialization.Serializable
data class MemorySnapshot(
    val lorebookKeysSnapshot: Map<String, String> = emptyMap(),
    val summarySnapshot: String = "",
    val snapshotAt: Int = 0
)

/**
 * Result of parsing the dispatch agent's output. Carries the parsed PathRequest
 * (if successful), the number of repair attempts made, and any parse error message.
 */
@kotlinx.serialization.Serializable
data class DispatchOutput(
    val pathRequest: PathRequest? = null,
    val repairAttempts: Int = 0,
    val parseError: String? = null
)

//=========================================Stash Models============================================================

/**
 * Manifest entry for stashed content. Allows agents and DITL tooling to reason about
 * what was stashed without loading the full content.
 */
@kotlinx.serialization.Serializable
data class StashEntry(
    val id: String,
    val sourcePath: String?,
    val createdTurn: Int,
    val reason: StashReason,
    val tokenEstimate: Int?,
    val byteSize: Long?,
    val preview: String
)



//=========================================Failure Policy===========================================================

/**
 * Default failure recovery policy for the PumpStation harness.
 * Configures how the harness responds to common failure modes.
 */
@kotlinx.serialization.Serializable
data class PumpStationFailurePolicy(
    var repairInvalidDispatchJson: Boolean = true,
    var maxDispatchRepairAttempts: Int = 1,
    var stashOversizedOutputs: Boolean = true,
    var callInterventionOnPathFailure: Boolean = true,
    var stopHarnessOnInvalidPathRequest: Boolean = false,
    /**
     * If true, the dispatch LLM is REQUIRED to commit a non-null
     * [PathRequest.pathSelectionRationale] each turn. When the LLM emits
     * null/blank, the harness appends a Hint to turn history on the next
     * dispatch rather than failing the dispatch outright. If false, the
     * rationale field is not surfaced in the path-injection prompt and no
     * nudge is appended on empty.
     */
    var requirePathSelectionRationale: Boolean = true
)

//=========================================Snapshot===================================================================

/**
 * Snapshot of the PumpStation state at a high-risk boundary.
 * Used for rollback, resume, fork, and debugging.
 */
@kotlinx.serialization.Serializable
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

//=========================================Type Aliases=============================================================

/**
 * Predicate evaluated each dispatch turn to determine if a reserve path should become visible.
 * Receives the current task state and developer-provided external context.
 * Returns true if the path should be revealed (sticky — stays revealed until explicitly hidden).
 */
typealias ReservePathRevealPredicate = (PumpStationTaskState, MutableMap<String, Any>) -> Boolean

//=========================================Task State==============================================================

/**
 * Internal harness state object. Single source of truth for runtime inspection,
 * replay, and resume. Not exposed to developers directly — accessible via
 * public inspection APIs.
 */
@kotlinx.serialization.Serializable
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
    @Contextual var memoryActionResult: MemoryActionResult?,
    // Path execution context — set while a path is being executed, used to annotate
    // nested P2P events that originate from inside the path with the path's name.
    var currentPathName: String? = null,
    // Pause state
    var isPaused: Boolean = false,
    var pausedAt: Set<PumpStationPausePhase> = emptySet(),
    var pauseReason: String? = null
    ,
    // Judge-trigger flag (PumpStationJudgeRunMode.FlagTriggered only). When true, the next turn's judge
    // phase runs as normal and then the flag is cleared. Set by [PumpStation.requestJudgeNextTurn] - typically
    // from a path's setExecutionFunction when the dispatch agent believes the task is done. Default false
    // preserves legacy behavior (judge runs every turn).
    var requestJudgeNextTurn: Boolean = false,
    // v3: generation + turn-index cursor for the compaction pipeline. Lets an arriving
    // compaction detect that an ahead compaction already covered the work and discard its
    // own attempt. See [CompactionCursor].
    var compactionCursor: CompactionCursor? = null,
    // v3: turn-index cursor for the lorebook pipeline. Lets the lorebook agent detect
    // that an ahead update already covered the work and discard its own output. See
    // [LorebookCursor].
    var lorebookCursor: LorebookCursor? = null
)

/**
 * Warning category for a [HarnessWarning] event. The v3 advisory only emits
 * [NoExitSignalConfigured]; future advisory codes slot in here.
 */
@kotlinx.serialization.Serializable
enum class WarningCode
{
    /**
     * The harness has been configured with no exit signal. Specifically, no judge agent
     * is wired AND [PumpStationJudgeRunMode] is not [PumpStationJudgeRunMode.FlagTriggered]
     * AND no path is expected to return [MultimodalContent.passPipeline] or
     * [MultimodalContent.terminatePipeline]. The harness will run until
     * [PumpStation.maxTurns] is exhausted and fail with
     * [PumpStationError.MaxTurnsExceeded].
     *
     * Advisory only — not a `require()`. The developer may have intentionally configured
     * a no-judge station that relies on paths calling `pumpStation.requestJudgeNextTurn()`
     * or returning `passPipeline = true`; in that case the advisory is harmless.
     */
    NoExitSignalConfigured
}

/**
 * The three legitimate exit mechanisms a PumpStation can be configured with.
 * Listed in a [HarnessWarning] payload so a visualizer can render the advisory with
 * concrete next-step hints.
 */
@kotlinx.serialization.Serializable
enum class ExitMechanism
{
    /** Judge agent evaluates `isComplete` or `shouldTerminate` every turn. */
    JudgeAlways,

    /** Path-bound `requestJudgeNextTurn()` plus [PumpStationJudgeRunMode.FlagTriggered]. */
    JudgeFlagTriggered,

    /** Path returns [MultimodalContent.passPipeline] = true to signal success. */
    PathPassPipeline,

    /** Path returns [MultimodalContent.terminatePipeline] = true to signal failure. */
    PathTerminatePipeline
}

/**
 * Advisory event emitted by the harness when it detects a configuration that the
 * developer is likely to want to know about. Carries a [code] and a list of
 * [mechanisms] the developer can use to resolve the advisory.
 *
 * Currently the only advisory is [WarningCode.NoExitSignalConfigured] — the harness
 * has no judge, no FlagTriggered path, and no path-bound exit signal. The harness
 * will run to [PumpStation.maxTurns] and fail with
 * [PumpStationError.MaxTurnsExceeded] in that case.
 *
 * Advisory events are non-blocking. The harness continues normally. A visualizer or
 * DITL hook may choose to surface the advisory to the developer.
 */
@kotlinx.serialization.Serializable
data class HarnessWarning(
    override val runId: String,
    override val turnIndex: Int,
    override val timestamp: Long = System.currentTimeMillis(),
    override val phase: PumpStationPhase = PumpStationPhase.PreInit,
    val code: WarningCode,
    val message: String,
    val mechanisms: List<ExitMechanism> = emptyList()
) : PumpStationEvent
