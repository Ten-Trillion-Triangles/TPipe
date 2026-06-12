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
 * Root PumpStation DSL builder.
 *
 * @param name Unique name for this PumpStation instance.
 */
@PumpStationDslMarker
class PumpStationBuilder(val name: String)
{
//=========================================Tracing Configuration===================================================

    /**
     * Optional tracing configuration. Set via the `tracing { }` DSL block; applied to the built
     * station in [PumpStationBuilder.build]. Null when the user did not configure tracing.
     */
    var tracingConfiguration: TraceConfig? = null

    /**
     * Optional kill switch configuration. Set via the `killSwitch { }` DSL block; applied to
     * the built station in [PumpStationBuilder.build] (before path registration so propagation
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
     * Builder function for healthAgent — creates fresh instance each invocation.
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
     * Additional harness agents invoked between dispatch output and return to judge agent.
     * Each slot stores an agent (or builder function) with a concurrency mode, and is
     * invoked in the order assigned.
     */
    val additionalHarnessAgentSlots: MutableList<HarnessAgentSlot> = mutableListOf()

//=========================================Configuration============================================================

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
     * Maximum harness turns before forced exit. Safety limit to avoid loops.
     */
    var maxHarnessTurns: Int = 50

    /**
     * Controls when the judge agent runs. Defaults to [PumpStationJudgeRunMode.Always] (judge fires
     * every turn). Set to [PumpStationJudgeRunMode.FlagTriggered] to skip the judge except on turns
     * where the dispatch agent (or any code holding a [PumpStation] reference) has called
     * [PumpStation.requestJudgeNextTurn].
     *
     * Trade-off: in `FlagTriggered` mode, [maxHarnessTurns] is the only safety net if the dispatch
     * never signals — set it conservatively.
     */
    var judgeRunMode: PumpStationJudgeRunMode = PumpStationJudgeRunMode.Always

    /**
     * Maximum number of concurrent background agents.
     * Excess requests are queued and batched.
     */
    var maxConcurrentBackgroundAgents: Int = 3

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
     * Maximum number of ConverseHistory elements in turn history.
     * Excess elements are popped from the stack.
     */
    var maxTurnHistorySize: Int = 50

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
     * Failure recovery policy for common failure modes.
     */
    var failurePolicy: PumpStationFailurePolicy = PumpStationFailurePolicy()

//=========================================Loop Guards==============================================================

    /**
     * Maximum consecutive dispatch turns that can select the same path.
     * Prevents infinite loops on a single path.
     */
    var maxConsecutiveSamePath: Int = 3

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
     */
    val pauseAtPhases: MutableSet<PumpStationPausePhase> = mutableSetOf()

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
     */
    fun path(pathName: String, block: PathBlock.() -> Unit)
    {
        val pb = PathBlock(pathName, this)
        pb.block()
        pb.build()
    }

    /**
     * Declare a reserve path that can be dynamically revealed.
     *
     * @param pathName Unique name for this reserve path.
     * @param block Builder block that configures the reserve path.
     */
    fun reservePath(pathName: String, block: ReservePathBlock.() -> Unit)
    {
        val rpb = ReservePathBlock(pathName, this)
        rpb.block()
        rpb.build()
    }

    /**
     * Configure dispatcher rules that govern path selection constraints.
     *
     * @param block Builder block that configures dispatcher rules.
     */
    fun dispatcherRules(block: DispatcherRulesBlock.() -> Unit)
    {
        val drb = DispatcherRulesBlock(this)
        drb.block()
    }

    /**
     * Configure tracing for the harness. The captured configuration is applied to the built station
     * via [PumpStation.enableTracing] so every emitted [PumpStationEvent] is mirrored into the
     * global [com.TTT.Debug.PipeTracer] for export and visualization.
     *
     * @param block Builder block that enables tracing and configures its detail level, format, and
     *              auto-export behavior.
     * @return This builder for method chaining.
     */
    fun tracing(block: PumpStationTracingDsl.() -> Unit): PumpStationBuilder
    {
        require(tracingConfiguration == null) {
            "Tracing has already been configured for this PumpStation DSL."
        }
        val dsl = PumpStationTracingDsl()
        dsl.block()
        tracingConfiguration = dsl.build()
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
    fun killSwitch(block: KillSwitchBlock.() -> Unit): PumpStationBuilder
    {
        require(killSwitchConfiguration == null) {
            "KillSwitch has already been configured for this PumpStation DSL."
        }
        val dsl = KillSwitchBlock()
        dsl.block()
        killSwitchConfiguration = dsl.build()
        return this
    }

    /**
     * Configure compaction for the harness. The captured configuration is applied
     * to the built station via [build] so the per-attempt orchestrator picks up
     * the developer-chosen strategy, fan-out mode, retry budget, chunk budget, and
     * the pre-prune / rollback DITL hooks. Mirrors the [tracing] / [killSwitch]
     * block shape.
     */
    fun compaction(block: CompactionBlock.() -> Unit): PumpStationBuilder
    {
        require(compactionConfiguration == null) {
            "compaction { } block already configured on this builder"
        }
        val dsl = CompactionBlock()
        dsl.block()
        compactionConfiguration = dsl
        return this
    }

//=========================================Build====================================================================

    /**
     * Build and return the configured [PumpStation].
     *
     * Note: This method defers wiring to the PumpStation instance until phase 3
     * when PumpStation infrastructure is complete. The DSL structure is complete
     * and correct; actual property wiring happens in phase 3.
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
            .setPreInitAgent(preInitAgent)
            .setPathSafetyAgent(pathSafetyAgent)

        // Additional harness agents (append each entry directly)
        for (slot in additionalHarnessAgentSlots)
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
            .setMaxHarnessTurns(maxHarnessTurns)
            .setJudgeRunMode(judgeRunMode)
            .setMaxConcurrentBackgroundAgents(maxConcurrentBackgroundAgents)
            .setMaxConcurrentForegroundAgents(maxConcurrentForegroundAgents)
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
            .setFailurePolicy(failurePolicy)

        // Tracing
        tracingConfiguration?.let { station.enableTracing(it) }

        // Loop guards
        station
            .setMaxConsecutiveSamePath(maxConsecutiveSamePath)
            .setMaxTotalPathCallsPerPath(maxTotalPathCallsPerPath)
            .setPathLimitExceededFunction(pathLimitExceededFunction)

        // pathLimitExceededPolicy is a public var on PumpStation
        station.pathLimitExceededPolicy = pathLimitExceededPolicy

        // External context provider (signature on PumpStation takes no arguments)
        if (externalContextProvider != null)
        {
            station.externalContextProvider = { -> externalContextProvider!!.invoke(station.getTaskState()) }
        }

        // Pause phases - map onto the existing pauseAt(vararg) method
        if (pauseAtPhases.isNotEmpty())
        {
            station.pauseAt(*pauseAtPhases.toTypedArray())
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
         * When nested blocks are called, they push the builder onto the stack
         * so parent context can be accessed.
         */
        private val builderStack = ThreadLocal.withInitial { mutableListOf<PumpStationBuilder>() }

        /**
         * Push a builder onto the stack.
         */
        internal fun pushBuilder(builder: PumpStationBuilder)
        {
            builderStack.get().add(builder)
        }

        /**
         * Pop a builder from the stack.
         */
        internal fun popBuilder(): PumpStationBuilder? {
            return if(builderStack.get().isNotEmpty()) {
                builderStack.get().removeAt(builderStack.get().size - 1)
            }
            else null
        }

        /**
         * Peek at the top of the stack without removing it.
         */
        fun currentBuilder(): PumpStationBuilder? {
            return if(builderStack.get().isNotEmpty()) {
                builderStack.get().last()
            }
            else null
        }
    }
}

/**
 * Builder for memory management configuration.
 */
@PumpStationDslMarker
class MemoryBlock(private val builder: PumpStationBuilder)
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
}

/**
 * Builder for path configuration.
 */
@PumpStationDslMarker
class PathBlock(private val pathName: String, private val builder: PumpStationBuilder)
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
     * Bind a Kotlin function to this path, registering it in [FunctionRegistry]
     * and populating the PCP schema.
     *
     * @param function The KFunction to bind.
     */
    fun bindFunction(function: KFunction<*>)
    {
        pathObject.bindFunction(function.name, function)
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
     * Configure the path schema for non-PCP path invocation.
     */
    fun schema(schema: String)
    {
        pathObject.pathSchema = schema
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
class ReservePathBlock(private val pathName: String, private val builder: PumpStationBuilder)
{
    val pathObject = PathObject()

    init { pathObject.pathName = pathName }

    var description: String
        get() = pathObject.pathDescription
        set(value) { pathObject.pathDescription = value }

    var risk: PathRiskLevel
        get() = pathObject.riskLevel
        set(value) { pathObject.riskLevel = value }

    /**
     * Bind a Kotlin function to this reserve path.
     *
     * @param function The KFunction to bind.
     */
    fun bindFunction(function: KFunction<*>)
    {
        pathObject.bindFunction(function.name, function)
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
class DispatcherRulesBlock(private val builder: PumpStationBuilder)
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

//=========================================Entry Point=============================================================

/**
 * Entry point for the PumpStation DSL.
 *
 * @param name Unique name for this PumpStation instance.
 * @param block Builder block that configures the PumpStation.
 * @return Fully configured PumpStation ready for initialization.
 */
fun pumpStation(name: String, block: PumpStationBuilder.() -> Unit): PumpStation
{
    val builder = PumpStationBuilder(name)
    PumpStationBuilder.pushBuilder(builder)
    builder.block()
    PumpStationBuilder.popBuilder()
    return builder.build()
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
