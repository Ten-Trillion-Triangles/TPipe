package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.KillSwitchContext
import com.TTT.P2P.P2PInterface
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.PcpContext
import kotlin.reflect.KFunction

/**
 * DSL marker annotation to restrict nested builder scopes so memory, path, and
 * dispatcherRules blocks do not leak methods into one another.
 */
@DslMarker
annotation class PumpStationDslMarker

/**
 * Sealed class representing the state machine stages for type-safe PumpStation DSL building.
 *
 * - [Initial]   : Nothing configured yet
 * - [HasPaths]  : At least one path { } has been called
 * - [Ready]     : All required and optional configuration is complete (build() available)
 */
sealed class PumpStationStage
{
    object Initial   : PumpStationStage()
    object HasPaths : PumpStationStage()
    object Ready      : PumpStationStage()
}

//=========================================PumpStationBuilder========================================================

/**
 * Root PumpStation DSL builder. Generic over the current configuration stage so
 * [build] can only be called once at least one path has been declared. Mirrors
 * [com.TTT.Pipeline.ManifoldBuilder]'s pattern from [ManifoldDsl.kt].
 *
 * @param name Unique name for this PumpStation instance.
 */
@PumpStationDslMarker
class PumpStationBuilder<S : PumpStationStage> @PublishedApi internal constructor(val name: String)
{
//=========================================Tracing Configuration===================================================

    /**
     * Optional tracing configuration. Set via the `tracing { }` DSL block; applied to the built
     * station in [PumpStationBuilder.build]. Null when the user did not configure tracing.
     */
    var tracingConfiguration: TraceConfig? = null

    /**
     * Optional kill switch configuration. Set via the `killSwitch { }` DSL block; applied to
     * the built station in [build] (before path registration so propagation
     * to [com.TTT.Pipeline.PathObject] happens up front). Null when the user did not configure
     * a kill switch.
     */
    var killSwitchConfiguration: KillSwitch? = null

    /**
     * Optional compaction configuration. Set via the `compaction { }` DSL block;
     * applied to the built station in [build] (after the station is constructed
     * but before path registration, so per-path compaction settings see the
     * configured values). Null when the developer did not configure compaction.
     */
    var compactionConfiguration: CompactionBlock? = null

//=========================================Agent Assignments=========================================================

    /**
     * Optional judge agent that evaluates if the harness task is complete.
     * If not present, must receive explicit pass/terminate signals.
     */
    var judgeAgent: P2PInterface? = null

    /**
     * REQUIRED: Agent that evaluates what the next steps in the harness need to be
     * and dispatches calls to the appropriate paths.
     */
    var dispatchAgent: P2PInterface? = null

    /**
     * Dispatch contract shape for this station. Defaults to
     * [com.TTT.Pipeline.PathExecutionShape.SinglePath] (preserves the
     * pre-existing dispatch JSON contract). Set to
     * [com.TTT.Pipeline.PathExecutionShape.MultiPath] to switch to the
     * multi-path dispatch contract where the dispatch LLM emits a
     * [com.TTT.Pipeline.PathRequestList] and the harness fans the list out
     * via the existing async substrate.
     *
     * DSL usage follows the assignment pattern used elsewhere in this
     * builder (e.g. `judgeAgent = ...`, `dispatchAgent = ...`):
     *
     *     pumpStation("example") {
     *         judgeAgent = Pipeline()
     *         dispatchAgent = Pipeline()
     *         pathExecutionShape = PathExecutionShape.MultiPath
     *         path("noop") { ... }
     *     }
     *
     * The assignment must precede any `path(...)` call because `path()`
     * promotes the Initial-stage builder and the promoted builder copies
     * the value of `pathExecutionShape` at promotion time.
     */
    var pathExecutionShape: PathExecutionShape = PathExecutionShape.SinglePath

    /**
     * Optional agent that can intervene with path calls, enforcing correct behavior
     * and providing nudges/hints to steer the dispatch and judge agents.
     */
    var interventionAgent: P2PInterface? = null

    /**
     * Optional background lorebook agent invoked to update the lorebook
     * of the PumpStation internal context window/minibank.
     */
    var lorebookAgent: P2PInterface? = null

    /**
     * Optional background agent to generate summaries of events for compaction
     * and turn history drop-off.
     */
    var summaryAgent: P2PInterface? = null

    /**
     * Optional goal agent. Scans work done by the harness once in exit state.
     * Fires [MultimodalContent.terminatePipeline] to signal failure and force resume.
     */
    var goalAgent: P2PInterface? = null

    /**
     * Optional agent that fires after the goal agent passes (or when no goal agent is
     * configured and the harness is exiting through [runExitFlow]). Receives the
     * goal agent's output (or the harness's exit-flow content when no goal agent is
     * configured) as its input and may set [MultimodalContent.terminatePipeline] on
     * its result to signal failure. Fires on every successful exit through
     * [runExitFlow] — broad coverage including the no-goal-agent and passPipeline-
     * routed paths — but NOT on the [PumpStationExitReason.GoalValidationFailed]
     * failure-exhaustion halt path or the [MultimodalContent.terminatePipeline] direct
     * halt path. Output becomes the harness's final deliverable; a non-passing agent
     * halts the harness with [PumpStationExitReason.JudgeComplete] (does NOT re-loop
     * — post-success-only semantic).
     */
    var postGoalAgent: P2PInterface? = null

    /**
     * Optional agent that fires prior to starting the harness.
     * Used for any initial setup or state handling.
     */
    var preInitAgent: P2PInterface? = null

    /**
     * DITL agent invoked to check path safety when a path is medium or high risk.
     */
    var pathSafetyAgent: P2PInterface? = null

    /**
     * Proactive health monitoring agent. Fires before judge based on
     * [healthAgentTurnInterval] or [healthAgentErrorRatioThreshold].
     */
    var healthAgent: P2PInterface? = null

    /**
     * Builder function for [judgeAgent] - creates a fresh instance per harness invocation.
     */
    var judgeAgentBuilderFunction: (suspend (PumpStation) -> Pipeline)? = null

    /**
     * Builder function for [dispatchAgent] - creates a fresh instance per harness invocation.
     */
    var dispatchAgentBuilderFunction: (suspend (PumpStation) -> Pipeline)? = null

    /**
     * Builder function for [interventionAgent] - creates a fresh instance per harness invocation.
     */
    var interventionAgentBuilderFunction: (suspend (PumpStation) -> P2PInterface)? = null

    /**
     * Builder function for [lorebookAgent] - creates a fresh instance per harness invocation.
     */
    var lorebookAgentBuilderFunction: (suspend (PumpStation) -> P2PInterface)? = null

    /**
     * Builder function for [summaryAgent] - creates a fresh instance per harness invocation.
     */
    var summaryAgentBuilderFunction: (suspend (PumpStation) -> P2PInterface)? = null

    /**
     * Builder function for [goalAgent] - creates a fresh instance per harness invocation.
     */
    var goalAgentBuilderFunction: (suspend (PumpStation) -> P2PInterface)? = null

    /**
     * Builder function for [postGoalAgent] - creates a fresh instance per harness
     * invocation. When non-null, this overrides any value set via [setPostGoalAgent].
     *
     * @see [postGoalAgent]
     */
    var postGoalAgentBuilderFunction: (suspend (PumpStation) -> P2PInterface)? = null

    /**
     * Builder function for healthAgent - creates fresh instance each invocation.
     */
    var healthAgentBuilderFunction: (suspend (PumpStation) -> P2PInterface)? = null

    /**
     * Fire healthAgent every N turns. null = disabled.
     */
    var healthAgentTurnInterval: Int? = null

    /**
     * Fire healthAgent when error ratio exceeds threshold. null = disabled.
     */
    var healthAgentErrorRatioThreshold: Double? = null

    /**
     * Concurrency mode: Blocking (judge waits) or Async (judge fires immediately).
     */
    var healthAgentConcurrencyMode: PumpStationConcurrencyMode? = null

    /**
     * Harness agent slots. The DSL `harnessAgent { }` and `harnessAgentBuilder { }`
     * blocks append to this list. Captured here, applied to the station in [build].
     */
    internal val harnessAgentSlots: MutableList<HarnessAgentSlot> = mutableListOf()

//=========================================Configuration============================================================

    /**
     * Persona / personality string. Injected into the judge and dispatch system prompts
     * ahead of every other instruction so the agent prioritises the persona.
     */
    var personality: String = ""

    /**
     * Treated as the "system prompt" for the harness.
     * Injected after core harness system instructions.
     */
    var systemTask: String = ""

    /**
     * User guidelines the judge and dispatch agents should follow.
     * Where traditional "skills" in other harnesses would be injected.
     */
    var userGuidelines: String = ""

    /**
     * Initial user prompt sent to the harness via MultimodalContent input
     * or P2P executeLocal invocation.
     */
    var entryUserPrompt: String = ""

    /**
     * Maximum harness turns before forced exit. Delegating alias for
     * [maxTurns]; the harness loop reads the canonical [maxTurns] field,
     * so writing this var has the same effect as writing [maxTurns].
     * Kept as a top-level DSL var so existing pumpStation blocks using
     * `maxHarnessTurns = N` continue to work.
     */
    var maxHarnessTurns: Int
        get() = maxTurns
        set(value) { maxTurns = value }

    /**
     * Controls when the judge agent runs. Defaults to [PumpStationJudgeRunMode.Always] (judge fires
     * every turn). Set to [PumpStationJudgeRunMode.FlagTriggered] to skip the judge except on turns
     * where the dispatch agent (or any code holding a [PumpStation] reference) has called
     * [PumpStation.requestJudgeNextTurn].
     *
     * Trade-off: in `FlagTriggered` mode, [maxTurns] is the only safety net if the dispatch
     * never signals - set it conservatively.
     */
    var judgeRunMode: PumpStationJudgeRunMode = PumpStationJudgeRunMode.Always

    /**
     * When true (default), the judge phase is skipped on turn 0 and the harness proceeds
     * directly to dispatch. The judge LLM gets a verdict vote starting on turn 1, after at
     * least one path has run and produced real output.
     *
     * Prevents the live-judge failure mode where the judge LLM sees the pre-dispatch state
     * (system task + user prompt with no paths yet) and hallucinates `isComplete=true` based
     * on an imagined brief. Without this guard the harness short-circuits before any path
     * ever runs and the loop is permanently broken.
     *
     * Set to false to restore the legacy "judge fires on every turn including turn 0" behavior.
     * Has no effect when [judgeRunMode] is [PumpStationJudgeRunMode.FlagTriggered] — that
     * mode's `no_flag_set` skip takes precedence.
     */
    var skipJudgeOnFirstTurn: Boolean = true

    /**
     * Maximum number of concurrent background agents.
     * Excess requests are queued and batched.
     */
    var maxConcurrentBackgroundAgents: Int = 3

    /**
     * When true, async paths are appended to turnHistory on completion. The
     * default is true. Per-path opt-out is available via the path builder's
     *  property.
     */
    var asyncPathsAppendToTurnHistory: Boolean = true

    /**
     * When true, async harness agents are appended to turnHistory on
     * completion. The default is false (fire-and-forget). Per-slot opt-in is
     * available via the harness agent builder's  property.
     */
    var asyncAgentsAppendToTurnHistory: Boolean = false

    /**
     * Optional grace period (milliseconds) given to in-flight async coroutines
     * after runFinalizationPhase before the station cancels its async scope.
     * When null (the default), the cancel is unbounded and long-running async
     * work is not artificially timeboxed. Set this to a value that matches the
     * worst-case LLM round-trip plus safety margin when a hard upper bound is
     * required.
     */
    var asyncJobGracePeriodMs: Long? = null

    /**
     * When true, async work runs on a station-scoped CoroutineScope that is
     * cancelled at the end of executeLocal. The default is true. Set to false
     * to fall back to the pre-substrate GlobalScope fire-and-forget behavior.
     */
    var asyncJobsScopedToStation: Boolean = true

    /**
     * Maximum number of concurrent foreground agents spawned by path calls
     * or by the dispatch agent.
     */
    var maxConcurrentForegroundAgents: Int = 3

    /**
     * Number of turns to wait before firing foreground agents.
     * Allows customizing speed, time, and token costs.
     */
    var foregroundTurnInterval: Int = 0

    /**
     * Number of turns to wait before firing background agents.
     * Allows customizing frequency of background agents.
     */
    var backgroundTurnInterval: Int = 5

    /**
     * Default memory management mode. Defaults to Compaction.
     */
    var memoryManagementMode: PumpStationMemoryManagementMode = PumpStationMemoryManagementMode.Compaction

    /**
     * % filled ratio of available context window space before triggering compaction.
     */
    var compactionThreshold: Double = 0.8

    /**
     * Default strategy for compaction if compaction is enabled.
     */
    var compactionStrategy: PumpStationCompactionStrategy = PumpStationCompactionStrategy.Whole

    /**
     * Master switch for the optional SafePrune phase. Defaults to false — feature is
     * fully opt-in.
     */
    var safePruneEnabled: Boolean = false

    /**
     * Minimum turnHistory size required for SafePrune to fire on a given turn.
     */
    var safePruneSizeThreshold: Int = 30

    /**
     * Number of most-recent entries that SafePrune strategies must NOT mutate.
     */
    var safePruneProtectRecentN: Int = 3

    /**
     * Window size for the DeduplicateByHash strategy.
     */
    var safePruneHashWindow: Int = 10

    /**
     * Maximum tool-response text length before StripLongToolArguments replaces it.
     */
    var safePruneMaxToolArgLength: Int = 2000

    /**
     * Per-strategy enable flags. Edited by [enableSafePruneStrategy] / [disableSafePruneStrategy].
     */
    internal val safePruneEnabledStrategies: MutableSet<SafePruneStrategy> = mutableSetOf()

    /**
     * Per-strategy policy overrides at the builder level. Empty by default.
     */
    internal val safePruneStrategyPolicies: MutableMap<SafePruneStrategy, SafePrunePolicy> = mutableMapOf()

    /**
     * Per-strategy dry-run flags at the builder level. Empty by default.
     */
    internal val safePruneStrategyDryRun: MutableSet<SafePruneStrategy> = mutableSetOf()

    /**
     * Enable a SafePrune strategy at the builder level.
     */
    fun enableSafePruneStrategy(strategy: SafePruneStrategy)
    {
        safePruneEnabledStrategies.add(strategy)
    }

    /**
     * Disable a SafePrune strategy at the builder level.
     */
    fun disableSafePruneStrategy(strategy: SafePruneStrategy)
    {
        safePruneEnabledStrategies.remove(strategy)
    }

    /**
     * Replace the entire enabled-strategy set at the builder level.
     */
    fun setSafePruneStrategies(strategies: Set<SafePruneStrategy>)
    {
        safePruneEnabledStrategies.clear()
        safePruneEnabledStrategies.addAll(strategies)
    }

    /**
     * Set a per-strategy policy override at the builder level. Pass null to clear.
     */
    fun setSafePruneStrategyPolicy(strategy: SafePruneStrategy, policy: SafePrunePolicy?)
    {
        if (policy == null) safePruneStrategyPolicies.remove(strategy)
        else safePruneStrategyPolicies[strategy] = policy
    }

    /**
     * Enable or disable dry-run mode for a single strategy at the builder level.
     */
    fun setSafePruneStrategyDryRun(strategy: SafePruneStrategy, dryRun: Boolean)
    {
        if (dryRun) safePruneStrategyDryRun.add(strategy)
        else safePruneStrategyDryRun.remove(strategy)
    }

    /**
     * Enable or disable dry-run mode for every strategy at once.
     */
    fun setSafePruneStrategyDryRunAll(dryRun: Boolean)
    {
        if (dryRun) safePruneStrategyDryRun.addAll(SafePruneStrategy.entries)
        else safePruneStrategyDryRun.clear()
    }

    /**
     * Maximum number of ConverseHistory elements in turn history.
     * Excess elements are popped from the stack.
     */
    var maxTurnHistorySize: Int = 50

    /**
     * Maximum number of turns the harness loop will run end-to-end. This is
     * the canonical loop-guard setter; the harness loop in
     * [com.TTT.Pipeline.PumpStationLoop.runHarnessLoop] reads the corresponding
     * [PumpStation.maxTurnsInternal] and terminates with
     * [PumpStationError.MaxTurnsExceeded] / [PumpStationExitReason.MaxTurnsHit]
     * when the cap is hit. [maxHarnessTurns] is a delegating alias for symmetry.
     */
    var maxTurns: Int = 50

    /**
     * Overall concurrency mode applied across the harness's spawn decisions.
     * Mirrors [PumpStation.setConcurrencyMode].
     */
    var concurrencyMode: PumpStationConcurrencyMode? = null

    /**
     * Maximum number of consecutive goal-evaluation failures before the
     * harness gives up on the current task. Defaults to 3.
     */
    var maxGoalFailAttempts: Int = 3

    /**
     * Maximum number of raw turn history entries to retain, or null to
     * disable the cap. Distinct from the ConverseHistory turn history cap.
     */
    var maxRawTurnHistorySize: Int? = null

    /**
     * Threshold (0.0-1.0) of context window utilization that triggers
     * blowout detection. Defaults to 0.9 (90%).
     */
    var blowoutThreshold: Double = 0.9

    /**
     * Timeout in milliseconds for memory update operations. Defaults to 30s.
     */
    var memoryUpdateTimeoutMs: Long = 30_000L

    /**
     * Maximum number of blowout recovery attempts before forced halt.
     * Defaults to 3.
     */
    var maxBlowoutRecoveries: Int = 3

    /**
     * Maximum number of tokens allowed in a repair/regeneration prompt.
     * Defaults to 500.
     */
    var maxRepairPromptTokens: Int = 500

    /**
     * If true, throw error and exit PumpStation when dispatch agent generates invalid JSON
     * for a path request.
     */
    var stopHarnessOnInvalidPathRequest: Boolean = false

    /**
     * If true, the dispatch LLM must commit a non-null
     * [PathRequest.pathSelectionRationale] on every dispatch turn.
     * A blank/null rationale causes the harness to append a soft Hint
     * to the next-turn dispatch history (no hard dispatch failure).
     */
    var requirePathSelectionRationale: Boolean = true

    /**
     * Failure recovery policy for common failure modes.
     */
    var failurePolicy: PumpStationFailurePolicy = PumpStationFailurePolicy()

//=========================================Loop Guards==============================================================

    /**
     * Maximum consecutive dispatch turns that can select the same path.
     * Null disables the guard. The guard is opt-in: set this explicitly when a
     * station should police repeated path selection.
     */
    var maxConsecutiveSamePath: Int? = null

    /**
     * Maximum total dispatch calls allowed per specific path.
     * null means unlimited.
     */
    var maxTotalPathCallsPerPath: Int? = null

    /**
     * Policy for how the harness responds when [maxTotalPathCallsPerPath] is exceeded.
     */
    var pathLimitExceededPolicy: PathLimitExceededPolicy = PathLimitExceededPolicy.Skip

    /**
     * Optional DITL function invoked when [maxTotalPathCallsPerPath] is exceeded.
     * Allows dynamic runtime policy instead of static [PathLimitExceededPolicy].
     */
    var pathLimitExceededFunction: (suspend (PathObject, String, PumpStation) -> PathLimitExceededResult)? = null

    /**
     * When false, the harness will not auto-inject the JSON-contract wrapper around
     * the judge agent's system prompt. Defaults to true.
     */
    var judgeJsonContractEnabled: Boolean = true

    /**
     * When false, the harness will not require a JSON contract verdict from the
     * path safety agent. Defaults to true.
     */
    var pathSafetyJsonContractEnabled: Boolean = true

//=========================================Reserve Paths & External Context=========================================

    /**
     * Paths stored in reserve are loaded into dispatch agent's system prompt dynamically.
     * Useful for keeping token costs under control.
     */
    val reservePaths: MutableMap<String, PathObject> = mutableMapOf()

    /**
     * External context provider called each dispatch turn to supply additional
     * context to the reserve path reveal predicate.
     */
    var externalContextProvider: ((PumpStationTaskState) -> MutableMap<String, Any>)? = null

//=========================================Pause Phases=============================================================

    /**
     * Phase boundaries at which the harness can pause for external inspection/intervention.
     * Populated by the `pause { }` DSL block; applied in [build] via
     * [PumpStation.pauseAt].
     */
    internal val pausePhases: MutableSet<PumpStationPausePhase> = mutableSetOf()

//=========================================System Prompts==========================================================

    /**
     * Custom judge system prompt. When set, replaces the harness default
     * judge prompt and disables the auto-injected JSON contract wrapper.
     */
    var judgeSystemPrompt: String? = null

    /**
     * Custom dispatch system prompt. When set, replaces the harness default
     * dispatch prompt.
     */
    var dispatchSystemPrompt: String? = null

    /**
     * Custom path-safety system prompt. When set, replaces the harness default
     * path-safety prompt and disables the auto-injected JSON contract wrapper.
     */
    var pathSafetySystemPrompt: String? = null

    /**
     * Custom health-agent system prompt.
     */
    var healthSystemPrompt: String? = null

    /**
     * Custom lorebook-agent system prompt.
     */
    var lorebookSystemPrompt: String? = null

    /**
     * Custom goal-agent system prompt.
     */
    var goalSystemPrompt: String? = null

//=========================================Event Observers=========================================================

    /**
     * Synchronous observer for every [PumpStationEvent] emitted by the harness.
     * Primarily intended for test observability.
     */
    var eventObserver: ((PumpStationEvent) -> Unit)? = null

//=========================================DITL Hooks===============================================================

    /**
     * DITL function invoked at the very beginning of harness runtime.
     * Activates prior to any action or state. Allows inspection and formatting
     * of the input content object.
     */
    var preInitFunction: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)? = null

    /**
     * Pre-validation DITL call for the judge agent.
     * Allows context adjustment prior to the LLM call.
     */
    var preValidationJudgeFunction: (suspend (MultimodalContent, MiniBank, PumpStation) -> MiniBank)? = null

    /**
     * DITL function invoked just after the judge agent returns. Allows the
     * developer to transform the verdict (or replace it) before the harness loop
     * decides what to do next.
     */
    var postJudgeFunction: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)? = null

    /**
     * Pre-validation DITL call for the dispatch agent.
     * Invoked prior to running the dispatch agent.
     */
    var preValidationDispatchFunction: (suspend (MultimodalContent, ContextWindow, MiniBank, PumpStation) -> MiniBank)? = null

    /**
     * DITL function invoked just prior to the judge agent.
     * Allows developer to decide to shut down and end the harness loop based on logic.
     */
    var preInvokeFunction: (suspend (ContextWindow, MiniBank, PumpStation) -> Boolean)? = null

    /**
     * DITL function invoked when a path request is made to a high risk path
     * or if a DITL agent at medium risk found issues.
     * Allows graceful handling of human input or other intervention.
     */
    var pathSafetyFunction: (suspend (PathObject, String, PumpStation) -> Boolean)? = null

    /**
     * DITL function invoked after the dispatch agent has generated its path output.
     */
    var postGenerateFunction: (suspend (MultimodalContent, PumpStation) -> P2PInterface)? = null

    /**
     * DITL function invoked after the path has fully executed.
     * If false, an error occurs and recovery is attempted.
     */
    var pathValidationFunction: (suspend (MultimodalContent, PumpStation) -> Boolean)? = null

    /**
     * DITL function to allow content transformation after path execution
     * and just before results are injected into harness history.
     */
    var pathTransformationFunction: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)? = null

    /**
     * DITL function that executes after memory agents complete a memory update task.
     */
    var postMemoryFunction: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)? = null

    /**
     * DITL function that fires when a memory blowout has been detected.
     * Allows developer intervention before compaction triggers.
     */
    var preCompactionFunction: (suspend (MultimodalContent, ConverseData, ConverseHistory, PumpStation) -> MultimodalContent)? = null

    /**
     * DITL function that fires after a TPipe emergency compaction/memory event happens.
     */
    var postCompactionFunction: (suspend (MultimodalContent, ConverseHistory, PumpStation) -> MultimodalContent)? = null

    /**
     * DITL function that fires when the context window is truncated to make room.
     * Allows the developer to react before the harness loop continues.
     */
    var onContextTruncated: (suspend (wasTruncated: Boolean, remainingFreeSpace: Int) -> Unit)? = null

    /**
     * DITL function fired after the goal agent passes (or when no goal agent is
     * configured and the harness is exiting through [runExitFlow]). Synchronous
     * transformation: receives the goal agent's output (or the harness's exit-flow
     * content when no goal agent is configured) and returns a possibly-modified
     * [MultimodalContent]. Precedes [postGoalAgent] when both are configured —
     * the agent receives the function's return value. Fires on every successful
     * exit through [runExitFlow] (broad coverage); does NOT fire on the
     * [PumpStationExitReason.GoalValidationFailed] failure-exhaustion halt path or
     * the [MultimodalContent.terminatePipeline] direct halt path.
     */
    var postGoalFunction: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)? = null

//=========================================Nested Blocks Storage====================================================

    internal val pathObjects: MutableMap<String, PathObject> = mutableMapOf()
    internal val dispatcherRules: MutableList<DispatcherRule> = mutableListOf()

//=========================================Nested Block Methods=====================================================

    /**
     * Configure memory management settings.
     */
    fun memory(block: MemoryBlock.() -> Unit)
    {
        val mb = MemoryBlock(this)
        mb.block()
    }

    /**
     * Declare a path that the dispatch agent can select.
     *
     * @param pathName Unique name for this path.
     * @param block Builder block that configures the path.
     * @return A [PumpStationBuilder] in the [PumpStationStage.Ready] stage - the
     *         stage the [build] call is gated to.
     */
    fun path(pathName: String, block: PathBlock.() -> Unit): PumpStationBuilder<PumpStationStage.Ready>
    {
        val pb = PathBlock(pathName, this)
        pb.block()
        pb.build()
        return promote()
    }

    /**
     * Declare a reserve path that can be dynamically revealed.
     *
     * @param pathName Unique name for this reserve path.
     * @param block Builder block that configures the reserve path.
     * @return A [PumpStationBuilder] in the [PumpStationStage.Ready] stage - the
     *         stage the [build] call is gated to.
     */
    fun reservePath(pathName: String, block: ReservePathBlock.() -> Unit): PumpStationBuilder<PumpStationStage.Ready>
    {
        val rpb = ReservePathBlock(pathName, this)
        rpb.block()
        rpb.build()
        return promote()
    }

    /**
     * Configure dispatcher rules that govern path selection constraints.
     *
     * @param block Builder block that configures dispatcher rules.
     * @return This builder for method chaining.
     */
    fun dispatcherRules(block: DispatcherRulesBlock.() -> Unit): PumpStationBuilder<S>
    {
        val targetBuilder = resolveActiveBuilder()
        val drb = DispatcherRulesBlock(targetBuilder)
        drb.block()
        return this
    }

    /**
     * Configure tracing for the harness. The captured configuration is applied to the built station
     * via [build] so every emitted [PumpStationEvent] is mirrored into the
     * global [com.TTT.Debug.PipeTracer] for export and visualization.
     *
     * @param block Builder block that enables tracing and configures its detail level, format, and
     *              auto-export behavior.
     * @return This builder for method chaining.
     */
    fun tracing(block: PumpStationTracingDsl.() -> Unit): PumpStationBuilder<S>
    {
        val targetBuilder = resolveActiveBuilder()
        require(targetBuilder.tracingConfiguration == null) {
            "Tracing has already been configured for this PumpStation DSL."
        }
        val dsl = PumpStationTracingDsl()
        dsl.block()
        targetBuilder.tracingConfiguration = dsl.build()
        return this
    }

    /**
     * Configure a [KillSwitch] on the built [PumpStation]. The switch auto-enforces the
     * configured [KillSwitchBlock.inputTokenLimit] / [KillSwitchBlock.outputTokenLimit] in the
     * harness loop (mirrors [com.TTT.Pipeline.Manifold.checkKillSwitch]) and propagates to
     * every [com.TTT.Pipeline.PathObject] the station owns.
     *
     * @param block DSL block that configures limits and an optional trip callback.
     * @return This builder for method chaining.
     * @throws IllegalArgumentException if a kill switch has already been configured.
     */
    fun killSwitch(block: KillSwitchBlock.() -> Unit): PumpStationBuilder<S>
    {
        val targetBuilder = resolveActiveBuilder()
        require(targetBuilder.killSwitchConfiguration == null) {
            "KillSwitch has already been configured for this PumpStation DSL."
        }
        val dsl = KillSwitchBlock()
        dsl.block()
        targetBuilder.killSwitchConfiguration = dsl.build()
        return this
    }

    /**
     * Configure compaction for the harness. The captured configuration is applied
     * to the built station via [build] so the per-attempt orchestrator picks up
     * the developer-chosen strategy, fan-out mode, retry budget, chunk budget, and
     * the pre-prune / rollback DITL hooks. Mirrors the [tracing] / [killSwitch]
     * block shape.
     */
    fun compaction(block: CompactionBlock.() -> Unit): PumpStationBuilder<S>
    {
        val targetBuilder = resolveActiveBuilder()
        require(targetBuilder.compactionConfiguration == null) {
            "compaction { } block already configured on this builder"
        }
        val dsl = CompactionBlock()
        dsl.block()
        targetBuilder.compactionConfiguration = dsl
        return this
    }

    /**
     * Configure pause phases for the harness. Each `add(phase)` call appends
     * to the built station via [PumpStation.pauseAt].
     *
     * @param block Builder block that adds phases to the pause set.
     * @return This builder for method chaining.
     */
    fun pause(block: PauseBlock.() -> Unit): PumpStationBuilder<S>
    {
        val targetBuilder = resolveActiveBuilder()
        val pb = PauseBlock(targetBuilder)
        pb.block()
        return this
    }

    /**
     * Add a static additional harness agent. The block body is unused - the
     * slot is built from the agent / concurrency args. The block exists only
     * for shape symmetry with the rest of the DSL.
     */
    fun harnessAgent(
        agent: P2PInterface,
        concurrency: PumpStationConcurrencyMode = PumpStationConcurrencyMode.Blocking,
        block: (HarnessAgentSlotDsl.() -> Unit) = {}
    ): PumpStationBuilder<S>
    {
        val targetBuilder = resolveActiveBuilder()
        val dsl = HarnessAgentSlotDsl()
        dsl.block()
        targetBuilder.harnessAgentSlots.add(
            HarnessAgentSlot(agent = agent, concurrency = concurrency, builderFunction = null)
        )
        return this
    }

    /**
     * Add a builder-function additional harness agent. The block body is unused.
     */
    fun harnessAgentBuilder(
        fn: suspend (PumpStation) -> P2PInterface,
        concurrency: PumpStationConcurrencyMode = PumpStationConcurrencyMode.Async,
        block: (HarnessAgentSlotDsl.() -> Unit) = {}
    ): PumpStationBuilder<S>
    {
        val targetBuilder = resolveActiveBuilder()
        val dsl = HarnessAgentSlotDsl()
        dsl.block()
        targetBuilder.harnessAgentSlots.add(
            HarnessAgentSlot(agent = null, concurrency = concurrency, builderFunction = fn)
        )
        return this
    }

    /**
     * Resolve the builder that nested block methods should write their
     * configuration into. Mirrors [ManifoldDsl]'s `peekBuilder` pattern: if a
     * [path] or [reservePath] call has promoted the builder to
     * [PumpStationStage.Ready], the promoted builder is on the stack and
     * becomes the target. Otherwise the active `this` builder is the target.
     */
    @PublishedApi
    internal fun resolveActiveBuilder(): PumpStationBuilder<*>
    {
        val stackBuilder = PumpStationBuilder.peekBuilder()
        return if(stackBuilder != null && stackBuilder !== this)
        {
            @Suppress("UNCHECKED_CAST")
            stackBuilder as PumpStationBuilder<*>
        }
        else
        {
            this
        }
    }

    /**
     * Promote the active builder to [PumpStationStage.Ready] by creating a new
     * builder that copies the parent's configuration and pushing it onto the
     * stack. The original `this` builder is no longer the active one; the
     * top-level `pumpStation` entry function will pop the final ready-stage
     * builder and call [build] on it.
     */
    @PublishedApi
    internal fun promote(): PumpStationBuilder<PumpStationStage.Ready>
    {
        val promoted = PumpStationBuilder<PumpStationStage.Ready>(name).copyFrom(this)
        @Suppress("UNCHECKED_CAST")
        PumpStationBuilder.pushBuilder(promoted as PumpStationBuilder<*>)
        @Suppress("UNCHECKED_CAST")
        return promoted as PumpStationBuilder<PumpStationStage.Ready>
    }

    /**
     * Copy every captured configuration field from [source] into this builder.
     * Used by [promote] to carry over the Initial-stage configuration into the
     * Ready-stage builder.
     */
    @PublishedApi
    internal fun copyFrom(source: PumpStationBuilder<*>): PumpStationBuilder<*>
    {
        tracingConfiguration = source.tracingConfiguration
        killSwitchConfiguration = source.killSwitchConfiguration
        compactionConfiguration = source.compactionConfiguration
        judgeAgent = source.judgeAgent
        dispatchAgent = source.dispatchAgent
        interventionAgent = source.interventionAgent
        lorebookAgent = source.lorebookAgent
        summaryAgent = source.summaryAgent
        goalAgent = source.goalAgent
        postGoalAgent = source.postGoalAgent
        preInitAgent = source.preInitAgent
        pathSafetyAgent = source.pathSafetyAgent
        healthAgent = source.healthAgent
        judgeAgentBuilderFunction = source.judgeAgentBuilderFunction
        dispatchAgentBuilderFunction = source.dispatchAgentBuilderFunction
        interventionAgentBuilderFunction = source.interventionAgentBuilderFunction
        lorebookAgentBuilderFunction = source.lorebookAgentBuilderFunction
        summaryAgentBuilderFunction = source.summaryAgentBuilderFunction
        goalAgentBuilderFunction = source.goalAgentBuilderFunction
        postGoalAgentBuilderFunction = source.postGoalAgentBuilderFunction
        healthAgentBuilderFunction = source.healthAgentBuilderFunction
        healthAgentTurnInterval = source.healthAgentTurnInterval
        healthAgentErrorRatioThreshold = source.healthAgentErrorRatioThreshold
        healthAgentConcurrencyMode = source.healthAgentConcurrencyMode
        harnessAgentSlots.addAll(source.harnessAgentSlots)
        personality = source.personality
        systemTask = source.systemTask
        userGuidelines = source.userGuidelines
        entryUserPrompt = source.entryUserPrompt
        judgeRunMode = source.judgeRunMode
        skipJudgeOnFirstTurn = source.skipJudgeOnFirstTurn
        maxConcurrentBackgroundAgents = source.maxConcurrentBackgroundAgents
        maxConcurrentForegroundAgents = source.maxConcurrentForegroundAgents
        foregroundTurnInterval = source.foregroundTurnInterval
        backgroundTurnInterval = source.backgroundTurnInterval
        memoryManagementMode = source.memoryManagementMode
        compactionThreshold = source.compactionThreshold
        compactionStrategy = source.compactionStrategy
        maxTurnHistorySize = source.maxTurnHistorySize
        maxTurns = source.maxTurns
        concurrencyMode = source.concurrencyMode
        maxGoalFailAttempts = source.maxGoalFailAttempts
        maxRawTurnHistorySize = source.maxRawTurnHistorySize
        pathExecutionShape = source.pathExecutionShape
        blowoutThreshold = source.blowoutThreshold
        memoryUpdateTimeoutMs = source.memoryUpdateTimeoutMs
        maxBlowoutRecoveries = source.maxBlowoutRecoveries
        maxRepairPromptTokens = source.maxRepairPromptTokens
        stopHarnessOnInvalidPathRequest = source.stopHarnessOnInvalidPathRequest
        requirePathSelectionRationale = source.requirePathSelectionRationale
        failurePolicy = source.failurePolicy
        maxConsecutiveSamePath = source.maxConsecutiveSamePath
        maxTotalPathCallsPerPath = source.maxTotalPathCallsPerPath
        pathLimitExceededPolicy = source.pathLimitExceededPolicy
        pathLimitExceededFunction = source.pathLimitExceededFunction
        judgeJsonContractEnabled = source.judgeJsonContractEnabled
        pathSafetyJsonContractEnabled = source.pathSafetyJsonContractEnabled
        reservePaths.putAll(source.reservePaths)
        externalContextProvider = source.externalContextProvider
        pausePhases.addAll(source.pausePhases)
        judgeSystemPrompt = source.judgeSystemPrompt
        dispatchSystemPrompt = source.dispatchSystemPrompt
        pathSafetySystemPrompt = source.pathSafetySystemPrompt
        healthSystemPrompt = source.healthSystemPrompt
        lorebookSystemPrompt = source.lorebookSystemPrompt
        goalSystemPrompt = source.goalSystemPrompt

        // SafePrune configuration — must be carried forward when `path()` promotes an
        // Initial-stage builder into a Ready-stage builder via copyFrom. Otherwise
        // config set inside `pumpStation { memory { safePrune { ... } } path(...) { ... } }`
        // is silently dropped on the promoted builder and `runSafePrunePhase` never
        // fires even though the user enabled the phase. Fixes a real bug uncovered
        // during the 2026-07-04 live SafePrune verification run.
        safePruneEnabled = source.safePruneEnabled
        safePruneSizeThreshold = source.safePruneSizeThreshold
        safePruneProtectRecentN = source.safePruneProtectRecentN
        safePruneHashWindow = source.safePruneHashWindow
        safePruneMaxToolArgLength = source.safePruneMaxToolArgLength
        safePruneEnabledStrategies.addAll(source.safePruneEnabledStrategies)
        safePruneStrategyPolicies.putAll(source.safePruneStrategyPolicies)
        safePruneStrategyDryRun.addAll(source.safePruneStrategyDryRun)

        eventObserver = source.eventObserver
        preInitFunction = source.preInitFunction
        preValidationJudgeFunction = source.preValidationJudgeFunction
        postJudgeFunction = source.postJudgeFunction
        preValidationDispatchFunction = source.preValidationDispatchFunction
        preInvokeFunction = source.preInvokeFunction
        pathSafetyFunction = source.pathSafetyFunction
        postGenerateFunction = source.postGenerateFunction
        pathValidationFunction = source.pathValidationFunction
        pathTransformationFunction = source.pathTransformationFunction
        postMemoryFunction = source.postMemoryFunction
        preCompactionFunction = source.preCompactionFunction
        postCompactionFunction = source.postCompactionFunction
        onContextTruncated = source.onContextTruncated
        pathObjects.putAll(source.pathObjects)
        dispatcherRules.addAll(source.dispatcherRules)
        return this
    }

//=========================================Build====================================================================

    /**
     * Build and return the configured [PumpStation]. This call is only type-safe
     * on a [PumpStationBuilder] in the [PumpStationStage.Ready] stage; the entry
     * function [pumpStation] handles the promotion. The runtime checks below
     * are kept as a safety net in case a caller manually crafts a Ready-stage
     * builder.
     *
     * @return Fully configured PumpStation ready for initialization.
     * @throws IllegalArgumentException if required configuration is missing.
     */
    fun build(): PumpStation {
        // Validate required pieces
        require(dispatchAgent != null) { "dispatchAgent is required" }
        require(dispatchAgent is Pipeline) { "dispatchAgent must be a Pipeline" }
        require(pathObjects.isNotEmpty()) { "At least one path is required" }

        // Validate path names are unique (case-insensitive)
        val pathNames = pathObjects.keys.map { it.lowercase() }
        require(pathNames.size == pathNames.toSet().size) {
            "Path names must be unique (case-insensitive)"
        }

        val station = PumpStation()

        // Apply all configuration to the station using the public fluent setters.
        station
            .setJudgeAgent(judgeAgent as? Pipeline)
            .setDispatchAgent(dispatchAgent as? Pipeline)
            .setInterventionAgent(interventionAgent)
            .setHealthAgent(healthAgent)
            .setHealthAgentBuilderFunction(healthAgentBuilderFunction)
            .setHealthAgentTurnInterval(healthAgentTurnInterval)
            .setHealthAgentErrorRatioThreshold(healthAgentErrorRatioThreshold)
            .setHealthAgentConcurrencyMode(healthAgentConcurrencyMode)
            .setLorebookAgent(lorebookAgent)
            .setSummaryAgent(summaryAgent)
            .setGoalAgent(goalAgent)
            .setPostGoalAgent(postGoalAgent)
            .setPreInitAgent(preInitAgent)
            .setPathSafetyAgent(pathSafetyAgent)
            .setPathExecutionShape(pathExecutionShape)

        // Agent builder functions
        station
            .setJudgeAgentBuilderFunction(judgeAgentBuilderFunction)
            .setDispatchAgentBuilderFunction(dispatchAgentBuilderFunction)
            .setInterventionAgentBuilderFunction(interventionAgentBuilderFunction)
            .setLorebookAgentBuilderFunction(lorebookAgentBuilderFunction)
            .setSummaryAgentBuilderFunction(summaryAgentBuilderFunction)
            .setGoalAgentBuilderFunction(goalAgentBuilderFunction)
            .setPostGoalAgentBuilderFunction(postGoalAgentBuilderFunction)

        // Magic-contract toggles
        station
            .setJudgeJsonContractEnabled(judgeJsonContractEnabled)
            .setPathSafetyJsonContractEnabled(pathSafetyJsonContractEnabled)

        // Custom system prompts
        station
            .setJudgeSystemPrompt(judgeSystemPrompt)
            .setDispatchSystemPrompt(dispatchSystemPrompt)
            .setPathSafetySystemPrompt(pathSafetySystemPrompt)
            .setHealthSystemPrompt(healthSystemPrompt)
            .setLorebookSystemPrompt(lorebookSystemPrompt)
            .setGoalSystemPrompt(goalSystemPrompt)

        // Event observer
        eventObserver?.let { station.setEventObserver(it) }

        // Concurrency / max-turns parity
        concurrencyMode?.let { station.setConcurrencyMode(it) }

        // Personality
        station.setPersonality(personality)

        // Additional harness agents (append each entry directly)
        for (slot in harnessAgentSlots)
        {
            if (slot.builderFunction != null)
            {
                station.addHarnessAgentBuilder(slot.builderFunction!!, slot.concurrency)
            }
            else if (slot.agent != null)
            {
                station.addHarnessAgent(slot.agent!!, slot.concurrency)
            }
        }

        // Prompts and metadata
        station
            .setSystemTask(systemTask)
            .setUserGuidelines(userGuidelines)
            .setEntryUserPrompt(entryUserPrompt)

        // Loop / concurrency / memory knobs
        station
            .setMaxTurns(maxTurns)
            .setJudgeRunMode(judgeRunMode)
            .setSkipJudgeOnFirstTurn(skipJudgeOnFirstTurn)
            .setMaxConcurrentBackgroundAgents(maxConcurrentBackgroundAgents)
            .setMaxConcurrentForegroundAgents(maxConcurrentForegroundAgents)
            .setAsyncPathsAppendToTurnHistory(asyncPathsAppendToTurnHistory)
            .setAsyncAgentsAppendToTurnHistory(asyncAgentsAppendToTurnHistory)
            .setAsyncJobGracePeriodMs(asyncJobGracePeriodMs)
            .setAsyncJobsScopedToStation(asyncJobsScopedToStation)
            .setForegroundTurnInterval(foregroundTurnInterval)
            .setBackgroundTurnInterval(backgroundTurnInterval)
            .setMemoryManagementMode(memoryManagementMode)
            .setCompactionThreshold(compactionThreshold)
            .setCompactionStrategy(compactionStrategy)
            .setMaxTurnHistorySize(maxTurnHistorySize)
            .setMaxGoalFailAttempts(maxGoalFailAttempts)
            .setMaxRawTurnHistorySize(maxRawTurnHistorySize)
            .setBlowoutThreshold(blowoutThreshold)
            .setMemoryUpdateTimeoutMs(memoryUpdateTimeoutMs)
            .setMaxBlowoutRecoveries(maxBlowoutRecoveries)
            .setMaxRepairPromptTokens(maxRepairPromptTokens)
            .setStopHarnessOnInvalidPathRequest(stopHarnessOnInvalidPathRequest)
            .setRequirePathSelectionRationale(requirePathSelectionRationale)
            .setFailurePolicy(failurePolicy)

        // SafePrune configuration
        station
            .setSafePruneEnabled(safePruneEnabled)
            .setSafePruneSizeThreshold(safePruneSizeThreshold)
            .setSafePruneProtectRecentN(safePruneProtectRecentN)
            .setSafePruneHashWindow(safePruneHashWindow)
            .setSafePruneMaxToolArgLength(safePruneMaxToolArgLength)
            .setSafePruneStrategies(safePruneEnabledStrategies.toSet())
            .setSafePruneStrategyPolicies(safePruneStrategyPolicies.toMap())
            .setSafePruneStrategyDryRunAll(false)
        // Apply per-strategy dry-run flags individually to preserve which strategies
        // are marked, since the master toggle above clears all.
        for (strategy in safePruneStrategyDryRun)
        {
            station.setSafePruneStrategyDryRun(strategy, true)
        }

        // Tracing
        tracingConfiguration?.let { station.enableTracing(it) }

        // Loop guards
        station
            .setMaxConsecutiveSamePath(maxConsecutiveSamePath)
            .setMaxTotalPathCallsPerPath(maxTotalPathCallsPerPath)
            .setPathLimitExceededFunction(pathLimitExceededFunction)

        // pathLimitExceededPolicy is a public var on PumpStation
        station.pathLimitExceededPolicy = pathLimitExceededPolicy

        // External context provider
        externalContextProvider?.let { station.setExternalContextProvider(it) }

        // Pause phases
        if (pausePhases.isNotEmpty())
        {
            station.pauseAt(*pausePhases.toTypedArray())
        }

        // DITL hooks
        station
            .setPreInitFunction(preInitFunction)
            .setPreValidationJudgeFunction(preValidationJudgeFunction)
            .setPreValidationDispatchFunction(preValidationDispatchFunction)
            .setPreInvokeFunction(preInvokeFunction)
            .setPathSafetyFunction(pathSafetyFunction)
            .setPostGenerateFunction(postGenerateFunction)
            .setPathValidationFunction(pathValidationFunction)
            .setPathTransformationFunction(pathTransformationFunction)
            .setPostMemoryFunction(postMemoryFunction)
            .setPreCompactionFunction(preCompactionFunction)
            .setPostCompactionFunction(postCompactionFunction)
            .setPostJudgeFunction(postJudgeFunction)
            .setOnContextTruncated(onContextTruncated)

        // Compaction configuration - applied before path registration so the
        // per-path settings see the configured values on the first addPath call.
        compactionConfiguration?.let { cfg ->
            cfg.strategy?.let { station.setCompactionStrategy(it) }
            cfg.fanout?.let { station.setCompactionFanoutMode(it) }
            cfg.threshold?.let { station.setCompactionThreshold(it) }
            cfg.maxAttempts?.let { station.setMaxCompactionAttempts(it) }
            cfg.chunkTokenBudget?.let { station.setChunkTokenBudget(it) }
            cfg.maxChunks?.let { station.setMaxChunks(it) }
            cfg.maxParallelChunks?.let { station.setMaxParallelChunks(it) }
            cfg.maxBackups?.let { station.setMaxCompactionBackups(it) }
            cfg.hybridWholeHeadroom?.let { station.setHybridWholeHeadroom(it) }
            cfg.prePruneTransform?.let { station.setPrePruneTransform(it) }
            cfg.rolledBackFunction?.let { station.setCompactionRolledBackFunction(it) }
        }

        // Kill switch - assign before path registration so the propagation in
        // PumpStation.addPath picks up the configured switch on the first path added.
        killSwitchConfiguration?.let { station.killSwitch = it }

        // Paths - add each entry to the station directly
        for ((_, path) in pathObjects)
        {
            station.addPath(path)
        }
        for ((_, path) in reservePaths)
        {
            station.addReservePath(path)
        }

        // Dispatcher rules - add each entry directly
        for (rule in dispatcherRules)
        {
            station.addDispatcherRule(rule)
        }

        return station
    }

//=========================================Companion Object=========================================================

    companion object {
        /**
         * Thread-local stack to track builder instances during DSL execution.
         * When a `path { }` or `reservePath { }` call promotes the builder to
         * [PumpStationStage.Ready], the new builder is pushed onto the stack so
         * subsequent configuration methods (`tracing { }`, `killSwitch { }`,
         * `pause { }`, `harnessAgent { }`, etc.) can find the right builder to
         * attach their configuration to. The entry function [pumpStation] pops
         * the final builder and calls [build] on it.
         */
        private val builderStack = ThreadLocal.withInitial { mutableListOf<PumpStationBuilder<*>>() }

        /**
         * Push a builder onto the stack.
         */
        @PublishedApi
        internal fun pushBuilder(builder: PumpStationBuilder<*>)
        {
            builderStack.get().add(builder)
        }

        /**
         * Pop a builder from the stack.
         */
        @PublishedApi
        internal fun popBuilder(): PumpStationBuilder<*>? {
            return if(builderStack.get().isNotEmpty()) {
                builderStack.get().removeAt(builderStack.get().size - 1)
            }
            else null
        }

        /**
         * Peek at the top of the stack without removing it.
         */
        @PublishedApi
        internal fun peekBuilder(): PumpStationBuilder<*>? {
            return if(builderStack.get().isNotEmpty()) {
                builderStack.get().last()
            }
            else null
        }

        /**
         * Peek at the top of the stack from outside the package. Public for
         * advanced callers; the canonical use is internal configuration flow.
         */
        fun currentBuilder(): PumpStationBuilder<*>? {
            return peekBuilder()
        }
    }
}

/**
 * Builder for memory management configuration.
 */
@PumpStationDslMarker
class MemoryBlock(private val builder: PumpStationBuilder<*>)
{
    var mode: PumpStationMemoryManagementMode
        get() = builder.memoryManagementMode
        set(value) { builder.memoryManagementMode = value }

    var compactionThreshold: Double
        get() = builder.compactionThreshold
        set(value) { builder.compactionThreshold = value }

    var strategy: PumpStationCompactionStrategy
        get() = builder.compactionStrategy
        set(value) { builder.compactionStrategy = value }

    /**
     * Configure the optional SafePrune phase. Off by default; call inside the block
     * to enable individual strategies. Usage:
     * ```
     * pumpStation {
     *     memory {
     *         safePrune {
     *             enabled = true
     *             enable(SafePruneStrategy.DropPureEchoes)
     *             enable(SafePruneStrategy.ReplaceWithSummaryRef)
     *         }
     *     }
     * }
     * ```
     */
    fun safePrune(block: SafePruneBlock.() -> Unit)
    {
        SafePruneBlock(builder).block()
    }
}

/**
 * Builder for the optional SafePrune phase. All knobs have safe defaults — the master
 * switch is off until [enabled] is set to true and at least one strategy is enabled.
 */
@PumpStationDslMarker
class SafePruneBlock(private val builder: PumpStationBuilder<*>)
{
    var enabled: Boolean
        get() = builder.safePruneEnabled
        set(value) { builder.safePruneEnabled = value }

    var sizeThreshold: Int
        get() = builder.safePruneSizeThreshold
        set(value) { builder.safePruneSizeThreshold = value }

    var protectRecentN: Int
        get() = builder.safePruneProtectRecentN
        set(value) { builder.safePruneProtectRecentN = value }

    var hashWindow: Int
        get() = builder.safePruneHashWindow
        set(value) { builder.safePruneHashWindow = value }

    var maxToolArgLength: Int
        get() = builder.safePruneMaxToolArgLength
        set(value) { builder.safePruneMaxToolArgLength = value }

    /**
     * Enable a single SafePrune strategy.
     */
    fun enable(strategy: SafePruneStrategy)
    {
        builder.enableSafePruneStrategy(strategy)
    }

    /**
     * Disable a single SafePrune strategy.
     */
    fun disable(strategy: SafePruneStrategy)
    {
        builder.disableSafePruneStrategy(strategy)
    }

    /**
     * Enable every SafePrune strategy. Use with caution — strategy D (DeduplicateByHash)
     * and E (StripLongToolArguments) have riskier behavior profiles.
     */
    fun enableAll()
    {
        builder.setSafePruneStrategies(SafePruneStrategy.entries.toSet())
    }

    /**
     * Disable every SafePrune strategy without turning the master switch off.
     */
    fun disableAll()
    {
        builder.setSafePruneStrategies(emptySet())
    }

    /**
     * Set a per-strategy policy override.
     */
    fun policy(strategy: SafePruneStrategy, policy: SafePrunePolicy)
    {
        builder.setSafePruneStrategyPolicy(strategy, policy)
    }

    /**
     * Clear a per-strategy policy override (strategy falls back to global knobs).
     */
    fun clearPolicy(strategy: SafePruneStrategy)
    {
        builder.setSafePruneStrategyPolicy(strategy, null)
    }

    /**
     * Enable or disable dry-run mode for a single strategy.
     */
    fun dryRun(strategy: SafePruneStrategy, dryRun: Boolean)
    {
        builder.setSafePruneStrategyDryRun(strategy, dryRun)
    }

    /**
     * Enable or disable dry-run mode for every strategy at once.
     */
    fun dryRunAll(dryRun: Boolean)
    {
        builder.setSafePruneStrategyDryRunAll(dryRun)
    }
}

/**
 * Standalone DSL builder for [PathObject]. Mirrors [PathBlock]'s surface but is NOT parented to a
 * [PumpStationBuilder]. Use [pathObject] for the entry point or [pathObjectBuilder] for staged construction.
 *
 * The resulting [PathObject] can be attached to a [PumpStation] via [PumpStation.addPath] or constructed
 * once and reused across multiple harnesses.
 *
 * @param pathName Unique name for the path. Will be set on [PathObject.pathName].
 */
@PumpStationDslMarker
class PathBuilder(internal val pathName: String)
{
    val pathObject = PathObject()

    init { pathObject.pathName = pathName }

    var description: String
        get() = pathObject.pathDescription
        set(value) { pathObject.pathDescription = value }

    var risk: PathRiskLevel
        get() = pathObject.riskLevel
        set(value) { pathObject.riskLevel = value }

    var dispatchHint: String
        get() = pathObject.dispatchHint
        set(value) { pathObject.dispatchHint = value }

    /**
     * Mark this path as one that runs in the background. When true, the harness is expected to launch
     * the path on its background scheduler rather than awaiting the result inline.
     */
    var runsInBackground: Boolean
        get() = pathObject.isRunsInBackground
        set(value) { pathObject.setRunsInBackground(value) }

    /**
     * When true, an async path will NOT append its result to turnHistory on completion. The path still
     * fires the PathCompleted event so observers can see the result, but the foreground drain will skip
     * the history merge. Only takes effect when runsInBackground is also true.
     */
    var suppressHistoryEmit: Boolean
        get() = pathObject.isSuppressHistoryEmit
        set(value) { pathObject.setSuppressHistoryEmit(value) }

    /**
     * JSON schema used by the dispatch agent when this path is not bound to a PCP function. Mirrors
     * [PathObject.pathSchema].
     */
    var schema: String
        get() = pathObject.pathSchema
        set(value) { pathObject.pathSchema = value }

    /**
     * Optional pre-built PCP schema. If the developer wants full control over the [PcpContext] (e.g. to
     * merge external tools or pre-load options), set this directly. The [bindFunction] helper appends to
     * whatever schema is already set, so binding a function after this assignment is additive.
     */
    var pcpSchema: PcpContext?
        get() = pathObject.pcpSchema
        set(value) { pathObject.pcpSchema = value }

    /**
     * Developer-supplied metadata map. Travels with the [PathObject] into the built station and can be
     * read by the path's own execution closure or by DITL hooks.
     */
    var pathMetadata: MutableMap<Any, Any>
        get() = pathObject.pathMetadata
        set(value) { pathObject.pathMetadata.clear(); pathObject.pathMetadata.putAll(value) }

    /**
     * Bind a Kotlin function to this path, registering it in [FunctionRegistry] and populating the PCP
     * schema under the function's own name.
     */
    fun bindFunction(function: KFunction<*>)
    {
        pathObject.bindFunction(function.name, function)
    }

    /**
     * Bind a Kotlin function to this path under an explicit name. Use this overload when the registered
     * PCP function name should differ from [KFunction.name].
     */
    fun bindFunction(name: String, function: KFunction<*>)
    {
        pathObject.bindFunction(name, function)
    }

    /**
     * Set an internal agent to execute this path. When assigned, the agent builder function is skipped
     * at execution time.
     */
    fun setInternalAgent(agent: P2PInterface)
    {
        pathObject.setInternalAgent(agent)
    }

    /**
     * Set the raw execution function for this path. This is the fallback when no internal agent or
     * agent builder is present.
     */
    fun setExecutionFunction(function: (suspend (MultimodalContent, PumpStation, ConverseHistory?, String) -> MultimodalContent)?)
    {
        pathObject.setExecutionFunction(function)
    }

    /**
     * Sets the output capture function that observes the final [MultimodalContent] just before it returns
     * from the path to the caller. Fires on every successful return from [PathObject.execute] (PCP,
     * executionFunction, internalAgent, agentBuilderFunction) and from [PathObject.executeLocal]. Awaited
     * inline so consumers observe content in deterministic order.
     *
     * @see PathObject.outputCaptureFunction
     */
    fun setOutputCaptureFunction(func: suspend (content: MultimodalContent) -> Unit)
    {
        pathObject.setOutputCaptureFunction(func)
    }

    /**
     * Build and return the configured [PathObject]. Idempotent — safe to call multiple times.
     */
    fun build(): PathObject = pathObject
}

/**
 * Builder for path configuration.
 */
@PumpStationDslMarker
class PathBlock(private val pathName: String, private val builder: PumpStationBuilder<*>)
{
    val pathObject = PathObject()

    init { pathObject.pathName = pathName }

    var description: String
        get() = pathObject.pathDescription
        set(value) { pathObject.pathDescription = value }

    var risk: PathRiskLevel
        get() = pathObject.riskLevel
        set(value) { pathObject.riskLevel = value }

    var dispatchHint: String
        get() = pathObject.dispatchHint
        set(value) { pathObject.dispatchHint = value }

    /**
     * Mark this path as one that runs in the background. When true, the harness
     * is expected to launch the path on its background scheduler rather than
     * awaiting the result inline.
     */
    var runsInBackground: Boolean
        get() = pathObject.isRunsInBackground
        set(value) { pathObject.setRunsInBackground(value) }

    /**
     * When true, an async path will NOT append its result to turnHistory on
     * completion. The path still fires the PathCompleted event so observers
     * can see the result, but the foreground drain will skip the history
     * merge. Only takes effect when runsInBackground is also true.
     */
    var suppressHistoryEmit: Boolean
        get() = pathObject.isSuppressHistoryEmit
        set(value) { pathObject.setSuppressHistoryEmit(value) }

    /**
     * JSON schema used by the dispatch agent when this path is not bound to a
     * PCP function. Mirrors [PathObject.pathSchema].
     */
    var schema: String
        get() = pathObject.pathSchema
        set(value) { pathObject.pathSchema = value }

    /**
     * Optional pre-built PCP schema. If the developer wants full control over
     * the [PcpContext] (e.g. to merge external tools or pre-load options), set
     * this directly. The [bindFunction] helper appends to whatever schema is
     * already set, so binding a function after this assignment is additive.
     */
    var pcpSchema: PcpContext?
        get() = pathObject.pcpSchema
        set(value) { pathObject.pcpSchema = value }

    /**
     * Developer-supplied metadata map. Travels with the [PathObject] into the
     * built station and can be read by the path's own execution closure or by
     * DITL hooks. Currently advisory - there is no built-in runtime consumer
     * of the map - but it is a public field on [PathObject] and we surface it
     * here to keep the door open.
     */
    var pathMetadata: MutableMap<Any, Any>
        get() = pathObject.pathMetadata
        set(value) { pathObject.pathMetadata.clear(); pathObject.pathMetadata.putAll(value) }

    /**
     * Bind a Kotlin function to this path, registering it in [FunctionRegistry]
     * and populating the PCP schema under the function's own name.
     *
     * @param function The KFunction to bind.
     */
    fun bindFunction(function: KFunction<*>)
    {
        pathObject.bindFunction(function.name, function)
    }

    /**
     * Bind a Kotlin function to this path under an explicit name. Use this
     * overload when the registered PCP function name should differ from
     * [KFunction.name] (e.g. to namespace, or to support a developer-chosen
     * schema name).
     *
     * @param name The name to register the function under. Must match the
     *             functionName the dispatch agent will emit when requesting
     *             this path.
     * @param function The KFunction to bind.
     */
    fun bindFunction(name: String, function: KFunction<*>)
    {
        pathObject.bindFunction(name, function)
    }

    /**
     * Set an internal agent to execute this path.
     * When assigned, the agent builder function is skipped at execution time.
     *
     * @param agent The P2PInterface agent to set.
     */
    fun setInternalAgent(agent: P2PInterface)
    {
        pathObject.setInternalAgent(agent)
    }

    /**
     * Set the raw execution function for this path.
     * This is the fallback when no internal agent or agent builder is present.
     *
     * @param function The suspend function to invoke when this path is called.
     */
    fun setExecutionFunction(function: (suspend (MultimodalContent, PumpStation, ConverseHistory?, String) -> MultimodalContent)?)
    {
        pathObject.setExecutionFunction(function)
    }

    /**
     * Sets the output capture function that observes the final [MultimodalContent] just before it returns
     * from the path to the caller. Fires on every successful return from [PathObject.execute] (PCP,
     * executionFunction, internalAgent, agentBuilderFunction) and from [PathObject.executeLocal]. Awaited
     * inline so consumers observe content in deterministic order.
     *
     * @see PathObject.outputCaptureFunction
     */
    fun setOutputCaptureFunction(func: suspend (content: MultimodalContent) -> Unit)
    {
        pathObject.setOutputCaptureFunction(func)
    }

    /**
     * Build and add this path to the parent builder.
     */
    fun build(): PathObject {
        builder.pathObjects[pathName] = pathObject
        return pathObject
    }
}

/**
 * Builder for reserve path configuration.
 */
@PumpStationDslMarker
class ReservePathBlock(private val pathName: String, private val builder: PumpStationBuilder<*>)
{
    val pathObject = PathObject()

    init { pathObject.pathName = pathName }

    var description: String
        get() = pathObject.pathDescription
        set(value) { pathObject.pathDescription = value }

    var risk: PathRiskLevel
        get() = pathObject.riskLevel
        set(value) { pathObject.riskLevel = value }

    var pcpSchema: PcpContext?
        get() = pathObject.pcpSchema
        set(value) { pathObject.pcpSchema = value }

    var pathMetadata: MutableMap<Any, Any>
        get() = pathObject.pathMetadata
        set(value) { pathObject.pathMetadata.clear(); pathObject.pathMetadata.putAll(value) }

    /**
     * Bind a Kotlin function to this reserve path under its own name.
     */
    fun bindFunction(function: KFunction<*>)
    {
        pathObject.bindFunction(function.name, function)
    }

    /**
     * Bind a Kotlin function to this reserve path under an explicit name.
     */
    fun bindFunction(name: String, function: KFunction<*>)
    {
        pathObject.bindFunction(name, function)
    }

    /**
     * Set an internal agent to execute this reserve path.
     *
     * @param agent The P2PInterface agent to set.
     */
    fun setInternalAgent(agent: P2PInterface)
    {
        pathObject.setInternalAgent(agent)
    }

    /**
     * Set the predicate that determines when this reserve path should be revealed.
     *
     * @param predicate Function that receives task state and external context,
     *                  returns true if path should be revealed.
     */
    fun revealWhen(predicate: (PumpStationTaskState, MutableMap<String, Any>) -> Boolean)
    {
        pathObject.revealWhen = predicate
    }

    /**
     * Build and add this reserve path to the parent builder.
     */
    fun build(): PathObject {
        builder.reservePaths[pathName] = pathObject
        return pathObject
    }
}

/**
 * Builder for dispatcher rules configuration.
 */
@PumpStationDslMarker
class DispatcherRulesBlock(private val builder: PumpStationBuilder<*>)
{
    /**
     * Set maximum consecutive dispatch turns that can select a specific path.
     *
     * @param pathName Name of the path to limit.
     * @param count Maximum consecutive turns allowed.
     */
    fun maxConsecutive(pathName: String, count: Int)
    {
        builder.dispatcherRules.add(MaxConsecutiveRule(pathName, count))
    }

    /**
     * Require that any of the specified paths have been executed before
     * the given path can be selected.
     *
     * @param pathName Name of the path that has prerequisites.
     * @param requireAny List of path names that must have been executed first.
     */
    fun before(pathName: String, requireAny: List<String>)
    {
        builder.dispatcherRules.add(BeforeRule(pathName, requireAny))
    }

    /**
     * Suggest a path to fire after the given path completes.
     *
     * @param pathName Name of the path that triggers the suggestion.
     * @param suggest Name of the path to suggest after completion.
     */
    fun after(pathName: String, suggest: String)
    {
        builder.dispatcherRules.add(AfterRule(pathName, suggest))
    }

    /**
     * Get all configured dispatcher rules.
     */
    fun getRules(): List<DispatcherRule> = builder.dispatcherRules.toList()
}

//=========================================Dispatcher Rules Types====================================================

/**
 * Sealed class representing all dispatcher rule types.
 */
sealed class DispatcherRule

/**
 * Rule that limits maximum consecutive dispatch turns for a specific path.
 */
data class MaxConsecutiveRule(
    val pathName: String,
    val maxCount: Int
) : DispatcherRule()

/**
 * Rule that requires certain paths to have been executed before
 * a given path can be selected.
 */
data class BeforeRule(
    val pathName: String,
    val requireAny: List<String>
) : DispatcherRule()

/**
 * Rule that suggests a path to fire after a given path completes.
 */
data class AfterRule(
    val pathName: String,
    val suggest: String
) : DispatcherRule()

/**
 * Builder for pause phases. Each `add(phase)` call appends to the parent
 * builder's pause set; the build step forwards to
 * [PumpStation.pauseAt].
 */
@PumpStationDslMarker
class PauseBlock(private val builder: PumpStationBuilder<*>)
{
    /**
     * Add a single phase to the pause set.
     */
    fun add(phase: PumpStationPausePhase)
    {
        builder.pausePhases.add(phase)
    }

    /**
     * Add a vararg list of phases to the pause set.
     */
    fun addAll(phases: Iterable<PumpStationPausePhase>)
    {
        builder.pausePhases.addAll(phases)
    }

    /**
     * Read-only view of the captured phases. Mutate via [add] / [addAll].
     */
    val phases: Set<PumpStationPausePhase> get() = builder.pausePhases
}

/**
 * Optional body for `harnessAgent { }` / `harnessAgentBuilder { }` blocks.
 * Currently a no-op container - the harness agent slot is built from the
 * function args alone. Kept as a typed hook for future per-slot
 * configuration (priority, weight, conditional attachment, etc.).
 */
@PumpStationDslMarker
class HarnessAgentSlotDsl
{
    // Reserved for future per-slot configuration. Intentionally empty so the
    // call-site shape matches the rest of the DSL.
}

//=========================================Entry Point=============================================================

/**
 * Entry point for the PumpStation DSL.
 *
 * @param name Unique name for this PumpStation instance.
 * @param block Builder block that configures the PumpStation.
 * @return Fully configured PumpStation ready for initialization.
 */
fun pumpStation(name: String, block: PumpStationBuilder<PumpStationStage.Initial>.() -> Unit): PumpStation
{
    val initialBuilder = PumpStationBuilder<PumpStationStage.Initial>(name)
    PumpStationBuilder.pushBuilder(initialBuilder)
    initialBuilder.block()
    // After the block runs, the top-of-stack is either the initial builder
    // (no path/reservePath was called - but we have a runtime require() for
    // that) or the most-recently-promoted Ready-stage builder.
    val finalBuilder = PumpStationBuilder.popBuilder() ?: initialBuilder
    return finalBuilder.build()
}

/**
 * Factory function to create the initial [PumpStationBuilder] in the
 * [PumpStationStage.Initial] stage. Mirrors [manifoldBuilder] from
 * [ManifoldDsl].
 */
fun pumpStationBuilder(name: String): PumpStationBuilder<PumpStationStage.Initial>
{
    return PumpStationBuilder<PumpStationStage.Initial>(name)
}

/**
 * Standalone entry point for constructing a [PathObject] outside the [pumpStation] harness context.
 * Mirrors the [pumpStation] / [pumpStationBuilder] dual pattern in a single call. The returned [PathObject]
 * is fully configured and ready to attach to any harness via [PumpStation.addPath].
 *
 * @param pathName Unique name for the path.
 * @param block Builder block that configures the path.
 * @return Fully configured [PathObject].
 */
fun pathObject(pathName: String, block: PathBuilder.() -> Unit): PathObject
{
    val builder = PathBuilder(pathName)
    builder.block()
    return builder.build()
}

/**
 * Factory function to create a standalone [PathBuilder] for staged/manual construction. Mirrors
 * [pumpStationBuilder] for the path-level DSL.
 *
 * @param pathName Unique name for the path.
 */
fun pathObjectBuilder(pathName: String): PathBuilder
{
    return PathBuilder(pathName)
}

//=========================================Tracing DSL================================================================

/**
 * Nested DSL block for configuring [PumpStation] tracing. Mirrors the Manifold/Junction tracing
 * patterns. Usage:
 *
 * ```
 * pumpStation("MyAgent") {
 *     tracing {
 *         enabled()
 *         detailLevel(TraceDetailLevel.VERBOSE)
 *         outputFormat(TraceFormat.HTML)
 *         autoExport(enabled = true, path = "~/.my-traces/")
 *     }
 *     // ... agents, paths, etc.
 * }
 * ```
 */
@PumpStationDslMarker
class PumpStationTracingDsl
{
    private var config = TraceConfig(enabled = true)

    /**
     * Enable (or explicitly disable) tracing. Defaults to enabling.
     */
    fun enabled(enabled: Boolean = true): PumpStationTracingDsl
    {
        config = config.copy(enabled = enabled)
        return this
    }

    /**
     * Set the maximum number of trace events retained per trace.
     */
    fun maxHistory(count: Int): PumpStationTracingDsl
    {
        config = config.copy(maxHistory = count)
        return this
    }

    /**
     * Set the trace output format. Used by [PumpStation.getTraceReport] when no format is supplied.
     */
    fun outputFormat(format: TraceFormat): PumpStationTracingDsl
    {
        config = config.copy(outputFormat = format)
        return this
    }

    /**
     * Set the detail level. Lower levels gate out DETAILED/INTERNAL events.
     */
    fun detailLevel(level: TraceDetailLevel): PumpStationTracingDsl
    {
        config = config.copy(detailLevel = level)
        return this
    }

    /**
     * Enable automatic file export after each run.
     */
    fun autoExport(enabled: Boolean = true, path: String = "~/.TPipe-Debug/traces/"): PumpStationTracingDsl
    {
        config = config.copy(autoExport = enabled, exportPath = path)
        return this
    }

    /**
     * Include the context snapshot on every event (slightly larger trace, more replayable).
     */
    fun includeContext(include: Boolean = true): PumpStationTracingDsl
    {
        config = config.copy(includeContext = include)
        return this
    }

    /**
     * Include event metadata (path names, risk levels, fill ratios, etc.) in the trace.
     */
    fun includeMetadata(include: Boolean = true): PumpStationTracingDsl
    {
        config = config.copy(includeMetadata = include)
        return this
    }

    /**
     * Replace the entire configuration with a pre-built [TraceConfig].
     */
    fun config(configuration: TraceConfig): PumpStationTracingDsl
    {
        config = configuration
        return this
    }

    /**
     * Build the immutable [TraceConfig] captured by this DSL block.
     */
    fun build(): TraceConfig = config
}

//=========================================KillSwitch Block========================================================

/**
 * DSL block for configuring a [KillSwitch] on a [PumpStationBuilder]. Captures the
 * input/output token limits and an optional trip callback, then produces an immutable
 * [KillSwitch] via [build]. Mirrors the block style used by [PumpStationTracingDsl].
 *
 * Example:
 * ```
 * pumpStation("my-station") {
 *     killSwitch {
 *         inputTokenLimit = 100_000
 *         outputTokenLimit = 50_000
 *         onTripped = { ctx ->
 *             logger.error("Kill switch tripped: ${ctx.reason}")
 *             throw KillSwitchException(ctx)
 *         }
 *     }
 * }
 * ```
 */
@PumpStationDslMarker
class KillSwitchBlock
{
    /** Maximum tokens allowed for input (prompt + context). null = no limit. */
    var inputTokenLimit: Int? = null

    /** Maximum tokens allowed for output (response + reasoning). null = no limit. */
    var outputTokenLimit: Int? = null

    /**
     * Callback invoked when the kill switch trips. Default throws [com.TTT.P2P.KillSwitchException].
     * Typed as `(KillSwitchContext) -> Unit` in the DSL to allow custom handlers (e.g. logging
     * before re-throwing). When null, the built [KillSwitch] uses the package default
     * `onTripped` which throws [com.TTT.P2P.KillSwitchException].
     */
    var onTripped: ((KillSwitchContext) -> Unit)? = null

    /**
     * Build the immutable [KillSwitch] captured by this DSL block. When [onTripped] is null,
     * the default throwing callback is used so the harness loop's [com.TTT.P2P.KillSwitchException]
     * catch can transition the run to a [com.TTT.Pipeline.PumpStationError.KillSwitchTripped] state.
     */
    fun build(): KillSwitch
    {
        val handler = onTripped
        return if (handler == null)
        {
            KillSwitch(
                inputTokenLimit = inputTokenLimit,
                outputTokenLimit = outputTokenLimit
            )
        }
        else
        {
            KillSwitch(
                inputTokenLimit = inputTokenLimit,
                outputTokenLimit = outputTokenLimit,
                onTripped = { ctx ->
                    handler(ctx)
                    // If the user's callback returns without throwing, fall through to the
                    // default behavior so the harness still gets a deterministic termination
                    // signal. This matches the contract of (KillSwitchContext) -> Nothing but
                    // surfaces the user's handler result through the standard failure path.
                    throw com.TTT.P2P.KillSwitchException(ctx)
                }
            )
        }
    }
}

//=========================================Compaction Block=========================================================

/**
 * DSL block for configuring the PumpStation v3 compaction orchestrator. Captures the
 * strategy, threshold, fan-out mode, retry budget, chunk budgets, and the pre-prune /
 * rollback DITL hooks, then applies them to the built station via [PumpStationBuilder.build].
 *
 * Mirrors the block style used by [PumpStationTracingDsl] and [KillSwitchBlock]. Every
 * field is optional; unset fields fall back to the [PumpStation] defaults
 * (Whole strategy, 0.8 threshold, Sequential fan-out, 2 attempts, etc.).
 *
 * Example:
 * ```
 * pumpStation("my-station") {
 *     compaction {
 *         strategy = PumpStationCompactionStrategy.Hybrid
 *         fanout = ChunkFanoutMode.Parallel
 *         maxAttempts = 2
 *         chunkTokenBudget = 1500
 *         maxBackups = 3
 *         prePrune { turns, _ -> turns.filter { it.content.text.isNotBlank() } }
 *         onRolledBack { backup, reason, _ -> null }
 *     }
 * }
 * ```
 */
@PumpStationDslMarker
class CompactionBlock
{
    /** Compaction strategy. Null = leave the [PumpStation] default. */
    var strategy: PumpStationCompactionStrategy? = null

    /** Context fill ratio that triggers compaction. Null = leave the default. */
    var threshold: Double? = null

    /** Chunk fan-out mode for the [PumpStationCompactionStrategy.Chunked] strategy. */
    var fanout: ChunkFanoutMode? = null

    /** Maximum number of compaction attempts before handing off to truncation. */
    var maxAttempts: Int? = null

    /** Token budget per chunk for the Chunked strategy. */
    var chunkTokenBudget: Int? = null

    /** Hard cap on the number of chunks produced by a single attempt. */
    var maxChunks: Int? = null

    /** Semaphore permit count for the [ChunkFanoutMode.Parallel] strategy. */
    var maxParallelChunks: Int? = null

    /** Maximum number of [CompactionBackup] snapshots retained. */
    var maxBackups: Int? = null

    /** Headroom threshold for Hybrid -> Whole. */
    var hybridWholeHeadroom: Double? = null

    /** Replace the default pre-prune transform. */
    var prePruneTransform: (suspend (List<ConverseData>, PumpStation) -> List<ConverseData>)? = null

    /** DITL hook fired when a [CompactionBackup] is restored. */
    var rolledBackFunction: (suspend (CompactionBackup, String, PumpStation) -> CompactionBackup?)? = null

    /**
     * Register a pre-prune transform. Convenience for the most common use case.
     * Equivalent to setting [prePruneTransform] directly.
     */
    fun prePrune(transform: suspend (List<ConverseData>, PumpStation) -> List<ConverseData>)
    {
        prePruneTransform = transform
    }

    /**
     * Register a rollback DITL hook. Convenience for the most common use case.
     * Equivalent to setting [rolledBackFunction] directly.
     */
    fun onRolledBack(handler: suspend (CompactionBackup, String, PumpStation) -> CompactionBackup?)
    {
        rolledBackFunction = handler
    }
}
