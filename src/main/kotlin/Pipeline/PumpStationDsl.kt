package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.Context.MiniBank
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

//=========================================PathObject Extension Properties============================================
// These extension properties will be backed by internal state in PathObject once phase 3 adds them.
// For now, we store them in a companion object map on PathObject and the actual wiring
// to the real properties happens in phase 3 when PumpStation infrastructure is complete.

/**
 * Extension property to set dispatchHint on PathObject.
 * Stored in a thread-safe map until PathObject.dispatchHint is added in phase 3.
 */
var PathObject.dispatchHint: String
    get() = _dispatchHintState.getOrDefault(this, "")
    set(value) { _dispatchHintState[this] = value }

/**
 * Extension property to set revealWhen predicate on PathObject.
 * Stored in a thread-safe map until PathObject.revealWhen is added in phase 3.
 */
var PathObject.revealWhen: ReservePathRevealPredicate?
    get() = _revealWhenState[this]
    set(value) { _revealWhenState[this] = value }

// Internal state storage for extension properties
private val _dispatchHintState = mutableMapOf<PathObject, String>()
private val _revealWhenState = mutableMapOf<PathObject, ReservePathRevealPredicate?>()

//=========================================PumpStation Deferred Wiring===============================================
// In phase 3, PumpStation will have setters for all properties and addPath/addReservePath methods.
// For now, we store the configuration in a separate object and the build() method creates
// a placeholder that will be wired up properly in phase 3.

/**
 * Internal configuration holder for PumpStation properties that are private.
 * This is used by the DSL to capture configuration and then transfer it to
 * PumpStation in phase 3 when the proper setters are added.
 */
class PumpStationDslConfig
{
    var judgeAgent: P2PInterface? = null
    var dispatchAgent: Pipeline? = null
    var interventionAgent: P2PInterface? = null
    var healthAgent: P2PInterface? = null
    var healthAgentBuilderFunction: (suspend (com.TTT.Pipeline.PumpStation) -> P2PInterface)? = null
    var healthAgentTurnInterval: Int? = null
    var healthAgentErrorRatioThreshold: Double? = null
    var healthAgentConcurrencyMode: PumpStationConcurrencyMode? = null
    var lorebookAgent: P2PInterface? = null
    var summaryAgent: P2PInterface? = null
    var goalAgent: P2PInterface? = null
    var preInitAgent: P2PInterface? = null
    var pathSafetyAgent: P2PInterface? = null
    val additionalHarnessAgents: MutableList<P2PInterface> = mutableListOf()

    var systemTask: String = ""
    var userGuidelines: String = ""
    var entryUserPrompt: String = ""
    var maxHarnessTurns: Int = 50
    var maxConcurrentBackgroundAgents: Int = 3
    var maxConcurrentForegroundAgents: Int = 3
    var foregroundTurnInterval: Int = 0
    var backgroundTurnInterval: Int = 5
    var memoryManagementMode: PumpStationMemoryManagementMode = PumpStationMemoryManagementMode.Compaction
    var compactionThreshold: Double = 0.8
    var compactionStrategy: PumpStationCompactionStrategy = PumpStationCompactionStrategy.Whole
    var maxTurnHistorySize: Int = 50
    var stopHarnessOnInvalidPathRequest: Boolean = false
    var failurePolicy: PumpStationFailurePolicy = PumpStationFailurePolicy()

    var maxConsecutiveSamePath: Int = 3
    var maxTotalPathCallsPerPath: Int? = null
    var pathLimitExceededPolicy: PathLimitExceededPolicy = PathLimitExceededPolicy.Skip
    var pathLimitExceededFunction: (suspend (PathObject, String, PumpStation) -> PathLimitExceededResult)? = null

    var externalContextProvider: ((PumpStationTaskState) -> MutableMap<String, Any>)? = null

    val pauseAtPhases: MutableSet<PumpStationPausePhase> = mutableSetOf()

    var preInitFunction: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)? = null
    var preValidationJudgeFunction: (suspend (MultimodalContent, MiniBank, PumpStation) -> MiniBank)? = null
    var preValidationDispatchFunction: (suspend (MultimodalContent, MiniBank, PumpStation) -> MiniBank)? = null
    var preInvokeFunction: (suspend (MiniBank, PumpStation) -> Boolean)? = null
    var pathSafetyFunction: (suspend (PathObject, String, PumpStation) -> Boolean)? = null
    var postGenerateFunction: (suspend (MultimodalContent, PumpStation) -> P2PInterface)? = null
    var pathValidationFunction: (suspend (MultimodalContent, PumpStation) -> Boolean)? = null
    var pathTransformationFunction: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)? = null
    var postMemoryFunction: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)? = null
    var preCompactionFunction: (suspend (MultimodalContent, ConverseData, ConverseHistory, PumpStation) -> MultimodalContent)? = null
    var postCompactionFunction: (suspend (MultimodalContent, ConverseHistory, PumpStation) -> MultimodalContent)? = null

    val pathObjects: MutableMap<String, PathObject> = mutableMapOf()
    val reservePaths: MutableMap<String, PathObject> = mutableMapOf()
    val dispatcherRules: MutableList<DispatcherRule> = mutableListOf()

    /**
     * Transfer all configuration to a PumpStation instance.
     * This method uses reflection or direct access depending on what PumpStation exposes in phase 3.
     * For now, it stores the config in a transient property on PumpStation.
     */
    fun wireTo(station: PumpStation) {
        // Store config in a transient holder on station for phase 3 wiring
        station._pendingDslConfig = this
    }
}

// Add transient property to PumpStation for DSL configuration storage
var PumpStation._pendingDslConfig: PumpStationDslConfig?
    get() = null
    set(value) { }

//=========================================PumpStationBuilder========================================================

/**
 * Root PumpStation DSL builder.
 *
 * @param name Unique name for this PumpStation instance.
 */
@PumpStationDslMarker
class PumpStationBuilder(val name: String)
{
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
     * Each agent is invoked in the order assigned.
     */
    val additionalHarnessAgents: MutableList<P2PInterface> = mutableListOf()

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
    var preValidationDispatchFunction: (suspend (MultimodalContent, MiniBank, PumpStation) -> MiniBank)? = null

    /**
     * DITL function invoked just prior to the judge agent.
     * Allows developer to decide to shut down and end the harness loop based on logic.
     */
    var preInvokeFunction: (suspend (MiniBank, PumpStation) -> Boolean)? = null

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
    fun memory(block: MemoryBlock.() -> Unit) {
        val mb = MemoryBlock(this)
        mb.block()
    }

    /**
     * Declare a path that the dispatch agent can select.
     *
     * @param pathName Unique name for this path.
     * @param block Builder block that configures the path.
     */
    fun path(pathName: String, block: PathBlock.() -> Unit) {
        val pb = PathBlock(pathName, this)
        pb.block()
    }

    /**
     * Declare a reserve path that can be dynamically revealed.
     *
     * @param pathName Unique name for this reserve path.
     * @param block Builder block that configures the reserve path.
     */
    fun reservePath(pathName: String, block: ReservePathBlock.() -> Unit) {
        val rpb = ReservePathBlock(pathName, this)
        rpb.block()
    }

    /**
     * Configure dispatcher rules that govern path selection constraints.
     *
     * @param block Builder block that configures dispatcher rules.
     */
    fun dispatcherRules(block: DispatcherRulesBlock.() -> Unit) {
        val drb = DispatcherRulesBlock(this)
        drb.block()
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

        // Create configuration holder and defer wiring to phase 3
        val config = PumpStationDslConfig()

        // Wire up all agent assignments
        config.judgeAgent = judgeAgent
        config.dispatchAgent = dispatchAgent as Pipeline
        config.interventionAgent = interventionAgent
        config.healthAgent = healthAgent
        config.healthAgentBuilderFunction = healthAgentBuilderFunction
        config.healthAgentTurnInterval = healthAgentTurnInterval
        config.healthAgentErrorRatioThreshold = healthAgentErrorRatioThreshold
        config.healthAgentConcurrencyMode = healthAgentConcurrencyMode
        config.lorebookAgent = lorebookAgent
        config.summaryAgent = summaryAgent
        config.goalAgent = goalAgent
        config.preInitAgent = preInitAgent
        config.pathSafetyAgent = pathSafetyAgent
        config.additionalHarnessAgents.addAll(additionalHarnessAgents)

        // Wire up configuration
        config.systemTask = systemTask
        config.userGuidelines = userGuidelines
        config.entryUserPrompt = entryUserPrompt
        config.maxHarnessTurns = maxHarnessTurns
        config.maxConcurrentBackgroundAgents = maxConcurrentBackgroundAgents
        config.maxConcurrentForegroundAgents = maxConcurrentForegroundAgents
        config.foregroundTurnInterval = foregroundTurnInterval
        config.backgroundTurnInterval = backgroundTurnInterval
        config.memoryManagementMode = memoryManagementMode
        config.compactionThreshold = compactionThreshold
        config.compactionStrategy = compactionStrategy
        config.maxTurnHistorySize = maxTurnHistorySize
        config.stopHarnessOnInvalidPathRequest = stopHarnessOnInvalidPathRequest
        config.failurePolicy = failurePolicy

        // Wire up loop guards
        config.maxConsecutiveSamePath = maxConsecutiveSamePath
        config.maxTotalPathCallsPerPath = maxTotalPathCallsPerPath
        config.pathLimitExceededPolicy = pathLimitExceededPolicy
        config.pathLimitExceededFunction = pathLimitExceededFunction

        // Wire up external context provider
        config.externalContextProvider = externalContextProvider

        // Wire up pause phases
        config.pauseAtPhases.addAll(pauseAtPhases)

        // Wire up DITL hooks
        config.preInitFunction = preInitFunction
        config.preValidationJudgeFunction = preValidationJudgeFunction
        config.preValidationDispatchFunction = preValidationDispatchFunction
        config.preInvokeFunction = preInvokeFunction
        config.pathSafetyFunction = pathSafetyFunction
        config.postGenerateFunction = postGenerateFunction
        config.pathValidationFunction = pathValidationFunction
        config.pathTransformationFunction = pathTransformationFunction
        config.postMemoryFunction = postMemoryFunction
        config.preCompactionFunction = preCompactionFunction
        config.postCompactionFunction = postCompactionFunction

        // Add paths and reserve paths to config
        config.pathObjects.putAll(pathObjects)
        config.reservePaths.putAll(reservePaths)
        config.dispatcherRules.addAll(dispatcherRules)

        // Defer wiring to station - phase 3 will wire this properly
        config.wireTo(station)

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
        internal fun pushBuilder(builder: PumpStationBuilder) {
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
     * Bind a Kotlin function to this path, registering it in [FunctionRegistry]
     * and populating the PCP schema.
     *
     * @param function The KFunction to bind.
     */
    fun bindFunction(function: KFunction<*>) {
        pathObject.bindFunction(function.name, function)
    }

    /**
     * Set an internal agent to execute this path.
     * When assigned, the agent builder function is skipped at execution time.
     *
     * @param agent The P2PInterface agent to set as the internal agent.
     */
    fun setInternalAgent(agent: P2PInterface) {
        pathObject.setInternalAgent(agent)
    }

    /**
     * Set the raw execution function for this path.
     * Used as fallback when no internal agent or agent builder is present.
     *
     * @param function The suspend function to invoke when this path is called.
     */
    fun setExecutionFunction(function: (suspend (MultimodalContent, PumpStation, ConverseHistory?, String) -> MultimodalContent)?) {
        pathObject.setExecutionFunction(function)
    }

    /**
     * Configure the path schema for non-PCP path invocation.
     */
    fun schema(schema: String) {
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
    fun bindFunction(function: KFunction<*>) {
        pathObject.bindFunction(function.name, function)
    }

    /**
     * Set an internal agent to execute this reserve path.
     *
     * @param agent The P2PInterface agent to set.
     */
    fun setInternalAgent(agent: P2PInterface) {
        pathObject.setInternalAgent(agent)
    }

    /**
     * Set the predicate that determines when this reserve path should be revealed.
     *
     * @param predicate Function that receives task state and external context,
     *                  returns true if path should be revealed.
     */
    fun revealWhen(predicate: (PumpStationTaskState, MutableMap<String, Any>) -> Boolean) {
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
    fun maxConsecutive(pathName: String, count: Int) {
        builder.dispatcherRules.add(MaxConsecutiveRule(pathName, count))
    }

    /**
     * Require that any of the specified paths have been executed before
     * the given path can be selected.
     *
     * @param pathName Name of the path that has prerequisites.
     * @param requireAny List of path names that must have been executed first.
     */
    fun before(pathName: String, requireAny: List<String>) {
        builder.dispatcherRules.add(BeforeRule(pathName, requireAny))
    }

    /**
     * Suggest a path to fire after the given path completes.
     *
     * @param pathName Name of the path that triggers the suggestion.
     * @param suggest Name of the path to suggest after completion.
     */
    fun after(pathName: String, suggest: String) {
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