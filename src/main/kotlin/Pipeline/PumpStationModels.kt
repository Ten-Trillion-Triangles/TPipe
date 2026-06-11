package com.TTT.Pipeline

import com.TTT.Context.ConverseHistory
import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.P2P.P2PInterface
import com.TTT.Pipe.MultimodalContent
import kotlinx.serialization.Contextual

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
    TokenBudgetExceeded,
    MemoryBlowout,
    KillSwitchTripped,
    MaxTurnsExceeded,
    LoopGuardTriggered,
    P2PRequestInvalid,
    InitNotCalled
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
    val newHistorySize: Int
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
    val detail: String
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
data class HarnessAgentSlot(
    val agent: P2PInterface?,
    val concurrency: PumpStationConcurrencyMode,
    val builderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null
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
    var stopHarnessOnInvalidPathRequest: Boolean = false
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
    var requestJudgeNextTurn: Boolean = false
)
