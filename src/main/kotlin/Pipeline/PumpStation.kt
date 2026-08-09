package com.TTT.Pipeline

import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.MiniBank
import com.TTT.Context.TodoList
import com.TTT.Context.TodoListTask
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.StallCallback
import com.TTT.Pipe.StreamingStallConfig
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.TruncationSettings
import com.TTT.Structs.PipeSettings
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.PcpExecutionDispatcher
import com.TTT.Debug.FailureAnalysis
import com.TTT.Debug.TraceAutoExporter
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceFormat
import com.TTT.Util.serialize
import com.TTT.Util.writeStringToFile
import com.TTT.Util.deepCopy
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Contextual
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Defines concurrency mode harness background tasks, and memory management.
 *
 * Async mode: In async mode background tasks will fire as soon as possible, and will queue up using a mutex.
 * They will continue to update various runtime state, and background memory state tasks as the queue unravels.
 * This is effective for constant non-blocking throughput but can potentially fall behind the judge and dispatch
 * agent depending on how quickly they judge the task, and dispatch path calls. If memory overflowed, and memory
 * management must be deployed, the harness will be blocked until all async tasks fully catch up.
 *
 * Blocking mode: Each background agent will block the harness until completion, and will be executed
 * in sequence of each other.
 *
 * Note: Only some harness agents can be assigned as async. Others that are set in the direct harness path
 * will always block by design.
 */
enum class PumpStationConcurrencyMode
{
    Async,
    Blocking
}

/**
 * Defines the memory management modes for [PumpStation]. Internally at init state, the PumpStation will scan
 * it's internal config and autoconfigure this enum if not manually configured. PumpStation can run in 3 modes:
 *
 * Compaction: Traditional agent harness compaction. Context and convo history is reduced to a summary by a compaction
 * agent once a threshold has been reached.
 *
 * Truncation: Leverages TPipe's memory management strategy. TokenBudgeting or auto-truncation will be deployed upon
 * reaching the threshold, Context will be truncated, and the lorebook selection algorithm will kick in to ensure
 * all required memory for the task survives.
 *
 * Hybrid: Deploys both TPipe truncation, and summarization intelligently to optimize memory preservation but
 * allow for preservation of context in an emergency state where the context size suddenly explodes before the
 * background agents updating the lorebook and summary have caught up.
 */
enum class PumpStationMemoryManagementMode
{
    Compaction,
    Truncation,
    Hybrid
}

/**
 * Determines the default approach to compacting context if compaction is enabled.
 *
 * @param Whole Entire turn history is passed to a compaction agent to summarize. Must fit entirely in the
 * context window of the agent. If any point the turn history exceeds the summary agent's context window size
 * the branch failure function will be invoked if valid. Otherwise, an exception will be thrown.
 *
 * @param Chunked Turn history will be converted to a string, and split into defined token chunks. Each chunk
 * can then be summarized in parallel, or in sequence until a complete compacted summary is produced. This will
 * survive even if the context window size has blown out the judge, and dispatchers window space, but may take
 * longer and require more tokens to compact.
 *
 * @param Hybrid Dynamically detects if there's enough tokens free to safely compact in whole, otherwise it defaults
 * back to chunked to perform the compaction.
 */
enum class PumpStationCompactionStrategy
{
    Whole,
    Chunked,
    Hybrid
}

/**
 * Controls when the judge agent runs inside the [PumpStation] harness loop.
 */
enum class PumpStationJudgeRunMode {
    /** Judge runs every turn (default; current behavior). */
    Always,

    /**
     * Judge runs only when [PumpStationTaskState.requestJudgeNextTurn] is true at the top of the judge phase.
     * The flag is one-shot: it is automatically cleared after the judge consumes it. The typical pattern is
     * a path the dispatch agent selects whose [PathObject.setExecutionFunction] calls
     * [PumpStation.requestJudgeNextTurn] when it believes the task is done.
     *
     * In this mode, [PumpStation.setMaxHarnessTurns] is the only safety net if the dispatch agent never
     * signals — set it conservatively.
     */
    FlagTriggered
}

/**
 * Defines risk level for path. This determines if an attempt to call path requires a validator agent to
 * kick on, or additional code to validate the safety of whatever the dispatcher agent is trying to call.
 * Breaks down to three levels:
 *
 * Low: Considered safe and does not require a validator agent to investigate the path call.
 *
 * Medium: Considered unsafe enough that a validator agent should examine what tthe path call is about to be,
 * what the dispatcher is trying to do, and weather it's about to defy orders, or do something stupid or destructive
 * and intervene.
 *
 * High: Always considered dangerous and should fire a handler function to allow the human programmer to intervene
 * as they see fit.
 */
enum class PathRiskLevel
{
    Low,
    Medium,
    High
}

/**
 * Defines the harness state of memory. Provided to the memory DITL function tto allow the coder
 * to quickly assess how memory was managed in a memory manage push.
 *
 * @param memoryMode Current mode this harness is set to.
 * @param memoryStrategy
 */
data class MemoryActionResult(
    var memoryMode: PumpStationMemoryManagementMode,
    var memoryStrategy: PumpStationCompactionStrategy,
    var loreBookActive: Boolean,
    var summaryActive: Boolean,
    var compactionPercent: Double,
    var budgetSettings: TokenBudgetSettings
)



/**
 * Immutable record produced by [PathObject.init]. Captures the fully initialized
 * configuration of a path — its name, description, invocation schema, and agent metadata.
 *
 * This is what the dispatch agent's prompt receives when it needs to understand
 * what a path is and how to call it. It is derived from  at init time
 * and augmented with runtime state (agent alive status, PCP schema availability).
 *
 * @property name The path's unique identifier.
 * @property description Human-readable description of what the path does.
 * @property inputSchema JSON schema string for non-PCP path invocation.
 * @property pcpSchema The fully populated [PcpContext] if PCP tools are bound to this path.
 * @property hasInternalAgent True if the path has an internal agent ready to execute.
 * @property hasExecutionFunction True if the path has a raw execution function bound.
 * @property agentTypeName Simple class name of the internal agent, if present.
 */
@kotlinx.serialization.Serializable
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

/**
 * List of all path descriptors which can be used to serialize and pass inward to agents.
 */
@kotlinx.serialization.Serializable
data class PathDescriptionList(
    var paths: MutableList<PathDescriptionData> = mutableListOf()
)

/**
 * Request object called by the llm to invoke a given path. Requires a path name to be passed, and the schema to be
 * supplied. This might be a custom JSON schema, a data class, or [PcpContext]. If PcpContext is supplied, then
 * the instructions on how to supply pcp will be auto-injected into the agent as well.
 *
 * The optional [pathSelectionRationale] field captures the LLM's free-text reasoning for why it picked
 * this specific path from the available list. The rationale rides into the trace and is consumed by the
 * judge phase for grading decision quality. When null on the wire, the dispatch output is still
 * schema-valid (back-compat with old LLM checkpoints that don't emit the field). The harness nudges the
 * LLM to commit a value when [PumpStationFailurePolicy.requirePathSelectionRationale] is true.
 */
@kotlinx.serialization.Serializable
data class PathRequest(
    var pathName: String = "",
    var pathSchema: String = "",
    var pathSelectionRationale: String? = null
)

/**
 * Core object class that is embedded into the [PumpStation] class. A PathObject is a special container for harness
 * calls. It comprises execution functions, internal agents, memory management, and PCP tool calls. It effectively
 * encapsulates the concept of a turn in a traditional agent harness and fully encloses the complexities that would
 * otherwise make the harness pattern inefficient.
 */
/**
 * Internal mirror of the [agentTokenUsage] helper from PumpStationLoop — duplicated here
 * because PumpStation.kt does not have visibility into the loop module. Returns null when
 * the agent is not a [Pipeline] or its token usage is zero.
 */
private fun agentTokenUsageInternal(agent: P2PInterface?): Pair<Int, Pair<Int, Int>>?
{
    val pipeline = agent as? Pipeline ?: return null
    val usage = pipeline.getTokenUsage()
    val input = usage.totalInputTokens.takeIf { it > 0 } ?: return null
    val output = usage.totalOutputTokens.takeIf { it > 0 } ?: 0
    val total = if (input > 0 || output > 0) input + output else 0
    return input to (output to total)
}

class PathObject(override var killSwitch: KillSwitch? = null) : P2PInterface
{

//============================================== Properties ============================================================

//---------------------------------------------------Core---------------------------------------------------------------
    /**
     * Reference to the parent P2PInterface when this [PathObject] is nested inside a complex container.
     */
    private var parentInterface: P2PInterface? = null

    override fun setParentInterface(parent: P2PInterface)
    {
        parentInterface = parent
    }

    override fun getParentP2PInterface(): P2PInterface? = parentInterface

    /**
     * Name of the path. Used by the [PumpStation] harnesses to locate this path when signals are sent to it
     * from the dispatcher agent.
     *
     * WARNING: This is a required value. If not assigned, the DSL will throw an [IllegalArgumentException] at
     * build time, and the path object will throw it at init() time.
     */
    var pathName = ""

    /**
     * Description of the path. Optional, bug strongly recommended. This explains to the dispatch agent what this
     * path does, how to use it, and helps improve accurate turn routing.
     */
    var pathDescription = ""

    /**
     * Schema required to invoke the path. Can either be json, or a data class.
     */
    var pathSchema = ""

    /**
     * Alternative pcp schema. If present, A pcp function will be invoked by the executor function, and treated
     * as the action taken by the path. Results will be collected and converted back to a string automatically,
     * then passed out as the [MultimodalContent] object.
     */
    var pcpSchema: PcpContext? = null

    /**
     * Defines risk level for the path. Allows for the [PumpStation] to automatically intervene with
     * validation agents, or human written DITL interrupts.
     */
    var riskLevel: PathRiskLevel = PathRiskLevel.Low

    /**
     * Configurable var to define the max number of concurrent agents allowed to be spawned. Acts as a passthrough
     * and a hint. This allows someone building a path object to abide by constraints or user requests and config
     * settings.
     */
    private var maxConcurrentAgents = 3

    /**
     * If true, the path will kick off and not block the harness. It will then send an interrupt signal to the harness to
     * interject its results upon completion into latest turn history event.
     */
    private var _runsInBackground = false

    /**
     * When true, an async path will NOT append its result to the harness
     * [turnHistory] on completion. The path still fires the [PathCompleted]
     * event so observers can see the result, but the foreground drain will
     * skip the merge. Useful for fire-and-forget paths that emit side-effect
     * events without producing textual content the LLM should see in the
     * conversation (e.g. a ping path that triggers a webhook).
     */
    private var _suppressHistoryEmit = false

    /**
     * Must be set, or pulled from the parent [PumpStation]. This required for us to calculate if we're about to
     * blow out a context window.
     */
    private var parentTokenBudgetSettings: TokenBudgetSettings? = null

    /**
     * Optional metadata storage dump to allow the developer to store read arbitrary values as needed from the path
     * object.
     */
    val pathMetadata: MutableMap<Any, Any> = mutableMapOf()

    /**
     * DITL hook that fires on the actual [MultimodalContent] about to exit the path, immediately before it
     * returns to the caller. Useful for outer-scaffolding UI/UX sinks that want to mirror path output
     * without altering the dispatch flow. Suspends; the dispatch awaits the capture inline so consumers
     * observe content in deterministic order. Fires on every successful return from [execute]
     * (PCP, executionFunction, internalAgent, agentBuilderFunction) and from [executeLocal].
     */
    @kotlinx.serialization.Transient
    var outputCaptureFunction: (suspend (content: MultimodalContent) -> Unit)? = null

    /**
     * Optional internal agent. Stored as a P2P interface to allow any possible TPipe agent type to be stored internally
     * this includes embedding another [PumpStation] inside the path object that can be called by an outer PumpStation.
     * When assigned, the agent builder function will be skipped over.
     */
    private var internalAgent : P2PInterface? = null

    /**
     * Returns true if an internal agent has been set on this path.
     */
    val isInternalAgentSet: Boolean get() = internalAgent != null

    /**
     * Returns true if an execution function has been set on this path.
     */
    val isExecutionFunctionSet: Boolean get() = executionFunction != null

    /**
     * Read the path's [com.TTT.Pipe.TokenUsage] when the [internalAgent] is a [Pipeline]. Returns
     * null for paths backed by an opaque [executionFunction] or a [P2PInterface] we do not know
     * how to inspect. Used by the harness to record per-path token usage in
     * [com.TTT.Pipeline.PumpStationEvent.PathCompleted] events.
     */
    fun getPathTokenUsage(): com.TTT.Pipe.TokenUsage?
    {
        val agent = internalAgent ?: return null
        return when (agent)
        {
            is Pipeline -> agent.getTokenUsage()
            else -> null
        }
    }

    /**
     * Read the path's [Pipeline.inputTokensSpent] / [Pipeline.outputTokensSpent] (the legacy
     * fields) when the [internalAgent] is a [Pipeline]. Returns a (0, 0) pair for paths backed
     * by an opaque [P2PInterface] or for paths without an internal agent. Unlike
     * [getPathTokenUsage] this does not require comprehensive token tracking to be enabled on
     * the path's pipe; the legacy fields are populated by the pipe's [countTokens] call during
     * normal execution. Used by the per-path kill switch enforcement to compare against the
     * path's own [PathObject.killSwitch] limits.
     */
    fun getPathLegacyTokenUsage(): Pair<Int, Int>
    {
        val agent = internalAgent ?: return 0 to 0
        return when (agent)
        {
            is Pipeline -> agent.inputTokensSpent to agent.outputTokensSpent
            else -> 0 to 0
        }
    }

    /**
     * Returns true if this path is configured to run in background.
     */
    val isRunsInBackground: Boolean get() = _runsInBackground

    /**
     * Required for memory management, and calculating if a functions output, or tool call output would blow out
     * the judge agent, and dispatch agent's context window.
     */
    private var truncationSettings: TruncationSettings? = null

    /**
     * Bindable agent builder function. This allows for a fresh copy of the agent to be generated at runtime. Extremely
     * useful for custom configs, settings or build-time state that changes prior to execution. Or to ensure a fully
     * clean slate and stateless agent at runtime. This will be checked for first at path execution, and will be
     * skipped over if [internalAgent] is not null. If neither this, nor internalAgent is assigned an exception
     * will be thrown at runtime, and at DSL build-time unless an execution function is present, or there is
     * a bound PCP function on this path.
     *
     * @param paramBundle Definable value that can be used for anything required. Can also be left null
     * if desired.
     */
     private var agentBuilderFunction : (suspend (paramBundle: MutableList<Any>?) -> P2PInterface)? = null

    /**
     * Bindable function to be invoked when the [PumpStation] dispatcher agent calls this path object.
     * This must be valid OR an internal agent or agent builder function must be valid, or a bound PCP function
     * must be present.
     *
     * @param content [MultimodalContent] object. May be supplied due to [P2PInterface] executeLocal(), or may be
     * supplied due to the presence of a supplied prompt in the path. Can be passed directly to an internal agent.
     *
     * @param stationRef [PumpStation] Reference to the PumpStation that owns this path. Useful for querying state,
     * and interacting with the inner components of the agent harness.
     *
     * @param turnHistory Reference to the active turn history [ConverseHistory] in the [PumpStation]. Presented
     * exactly as the state of context currently is at the time of path invocation. Can be optionally used, or
     * supplied to an internal agent or whatever form of work is desired.
     *
     * @param turnSummary Reference to the turn summary if present and enabled in [PumpStation]. May be desirable to
     * pass onward to an internal agent.
     */
    private var executionFunction: (suspend (content: MultimodalContent, stationRef: PumpStation, turnHistory: ConverseHistory?, turnSummary: String) -> MultimodalContent)? = null

    /**
     * True when this path has a developer-supplied [executionFunction]. Used by
     * [com.TTT.Pipeline.runPreInitPhase] to detect a path-bound exit signal — paths
     * with a custom function may return [MultimodalContent.passPipeline] = true or
     * [MultimodalContent.terminatePipeline] = true to exit the harness, even without
     * a judge agent. Without this signal the harness would emit a false-positive
     * [WarningCode.NoExitSignalConfigured] advisory.
     */
    internal val hasExecutionFunction: Boolean
        get() = executionFunction != null


//-----------------------------------------------------init--------------------------------------------------------

    /**
     * Optional dispatch hint advisory to the LLM. Injected into path description when
     * visible to the dispatch agent. Not enforced by the harness — soft guidance only.
     */
    var dispatchHint: String = ""

    /**
     * Predicate evaluated each dispatch turn to determine if this reserve path
     * should be revealed. Evaluated with the current task state and developer-provided
     * external context. Sticky once revealed — stays visible until explicitly hidden.
     * Not suspend — predicates should be simple synchronous checks.
     */
    var revealWhen: (taskState: PumpStationTaskState, externalContext: MutableMap<String, Any>) -> Boolean = { _, _ -> false }

    /**
     * Initializes this [PathObject], transitioning it from build-time configuration to
     * runtime-ready state. Must be called once before the path is used in a [PumpStation] harness.
     *
     * Performs the following in order:
     * 1. Validates that [pathName] is set (required for dispatch routing)
     * 2. Validates that at least one execution mechanism is configured
     * 3. Calls [P2PInit] on the internal agent if one is present
     * 4. Builds and returns a [PathDescriptionData] record capturing the fully initialized path state
     *
     * @return [PathDescriptionData] containing the path's name, description, schema, and agent metadata
     * @throws IllegalArgumentException if [pathName] is blank
     * @throws IllegalStateException if neither [executionFunction], [internalAgent], [agentBuilderFunction],
     *         nor a bound PCP function is configured (path has no means of execution)
     */
    suspend fun init(): PathDescriptionData
    {
        // Step 1: Validate required configuration
        require(pathName.isNotBlank()) {
            "PathObject.init() failed: pathName is required and cannot be blank. " +
            "Set pathName before calling init()."
        }

        // Check that at least one execution path is available
        val hasExecution = executionFunction != null
        val hasAgent = internalAgent != null
        val hasAgentBuilder = agentBuilderFunction != null
        val hasPcpFunction = pcpSchema != null && pcpSchema!!.tpipeOptions.isNotEmpty()

        require(hasExecution || hasAgent || hasAgentBuilder || hasPcpFunction) {
            "PathObject.init() failed for path '${pathName}': no execution mechanism configured. " +
            "At least one of executionFunction, internalAgent, agentBuilderFunction, or a bound PCP function is required."
        }

        // Step 2: Invoke P2PInit on internal agent if present
        val agentTypeName = internalAgent?.let { agent ->
            agent.P2PInit()
            agent::class.simpleName
        }

        // Step 3: Build and return PathDescriptionData
        return PathDescriptionData(
            name = pathName,
            description = pathDescription,
            inputSchema = pathSchema,
            pcpSchema = pcpSchema,
            hasInternalAgent = hasAgent,
            hasExecutionFunction = hasExecution,
            agentTypeName = agentTypeName,
            isRunsInBackground = isRunsInBackground,
        )
    }

    /**
     * Sets the internal agent for this path. When assigned, the agent builder function
     * is skipped at execution time.
     *
     * @param agent The P2PInterface agent to set as the internal agent.
     */
    fun setInternalAgent(agent: P2PInterface)
    {
        this.internalAgent = agent
    }

    /**
     * Sets the execution function for this path. This is the fallback when no internal agent
     * or agent builder is present.
     *
     * @param function The suspend function to invoke when this path is called.
     */
    fun setExecutionFunction(function: (suspend (content: MultimodalContent, stationRef: PumpStation, turnHistory: ConverseHistory?, turnSummary: String) -> MultimodalContent)?)
    {
        this.executionFunction = function
    }

    /**
     * Marks this path as one that runs in the background when invoked by the harness.
     * When true, the harness is expected to launch the path on its background scheduler
     * rather than awaiting the result inline.
     *
     * @param value true to mark this path as background; false to mark it as foreground.
     */
    fun setRunsInBackground(value: Boolean)
    {
        this._runsInBackground = value
    }

    /**
     * Controls whether an async path's completion result is appended to the
     * harness [turnHistory]. When true, [PathCompleted] is still emitted for
     * observers, but the foreground drain skips the history merge. Only takes
     * effect when [isRunsInBackground] is also true.
     *
     * @param value true to suppress history emission, false to allow it.
     */
    fun setSuppressHistoryEmit(value: Boolean)
    {
        this._suppressHistoryEmit = value
    }

    /**
     * Sets the output capture function that observes the final [MultimodalContent] just before it returns from
     * the path to the caller. Fires on every successful return from [execute] (PCP, executionFunction,
     * internalAgent, agentBuilderFunction) and from [executeLocal]. The dispatch awaits the capture inline
     * so consumers observe content in deterministic order. Intended for routing path output to UI/UX sinks
     * in parallel with the normal dispatch return path.
     *
     * @see [outputCaptureFunction]
     */
    fun setOutputCaptureFunction(func: suspend (content: MultimodalContent) -> Unit): PathObject
    {
        outputCaptureFunction = func
        return this
    }

    /**
     * True if this path suppresses its async result from being appended to
     * the harness [turnHistory]. Mirrors the mutable [setSuppressHistoryEmit]
     * setting.
     */
    val isSuppressHistoryEmit: Boolean get() = _suppressHistoryEmit

    /**
     * P2PInterface required init function. Delegates to [init] for path initialization.
     * Present to satisfy the [P2PInterface] contract.
     */
    override suspend fun P2PInit()
    {
        init()
    }

    /**
     * Executes this path with the given input.
     *
     * Execution priority (first to last):
     * 1. PCP schema / bound PCP function — if [pcpSchema] is present with tpipe options,
     *    the PCP function is invoked and its result is returned directly
     * 2. [executionFunction] — if present, called with the input, station, turn history,
     *    and turn summary
     * 3. [internalAgent] — if present, called via [P2PInterface.executeLocal]
     * 4. [agentBuilderFunction] — if present, a fresh agent is created, initialized via
     *    [P2PInterface.P2PInit], then called via [P2PInterface.executeLocal]
     *
     * @param content The [MultimodalContent] input to this path
     * @param station Reference to the parent [PumpStation]
     * @param turnHistory Current turn history at invocation time (may be null)
     * @param turnSummary Current turn summary at invocation time
     * @return [MultimodalContent] result from the path execution
     * @throws IllegalStateException if no execution mechanism is configured
     */
    internal suspend fun execute(
        content: MultimodalContent,
        station: PumpStation,
        turnHistory: ConverseHistory?,
        turnSummary: String
    ): MultimodalContent
    {
        // Priority 1: PCP function — dispatch to PcpExecutionDispatcher if a function is named
        if (pcpSchema != null && pcpSchema!!.tpipeOptions.isNotEmpty())
        {
            val functionName = content.tools.tPipeContextOptions.functionName
            if (functionName.isNotBlank())
            {
                val dispatcher = PcpExecutionDispatcher()
                val result = dispatcher.executeRequest(content.tools, pcpSchema!!)
                if (result.success)
                {
                    val pcpResult = MultimodalContent(text = result.output)
                    pcpResult.metadata["pcpOutput"] = result.output
                    outputCaptureFunction?.invoke(pcpResult)
                    return pcpResult
                }
                else
                {
                    // PCP execution failed — set lastError and fall through to next priority
                    station.getTaskState().lastError = PumpStationError.PathExecutionException
                    // Fall through to executionFunction or other priorities
                }
            }
        }

        // Priority 2: execution function
        if (executionFunction != null)
        {
            val execResult = executionFunction!!.invoke(content, station, turnHistory, turnSummary)
            outputCaptureFunction?.invoke(execResult)
            return execResult
        }

        // Priority 3: internal agent
        if (internalAgent != null)
        {
            internalAgent!!.setParentInterface(station)
            val internalResult = internalAgent!!.executeLocal(content)
            outputCaptureFunction?.invoke(internalResult)
            return internalResult
        }

        // Priority 4: agent builder function
        if (agentBuilderFunction != null)
        {
            val agent = agentBuilderFunction!!.invoke(null)
            agent.setParentInterface(station)
            agent.P2PInit()
            val builderResult = agent.executeLocal(content)
            outputCaptureFunction?.invoke(builderResult)
            return builderResult
        }

        // No execution mechanism available
        throw IllegalStateException(
            "PathObject.execute() failed for path '${pathName}': no execution mechanism configured. " +
            "At least one of executionFunction, internalAgent, agentBuilderFunction, or a bound PCP function is required."
        )
    }

//-----------------------------------------------------DITL-------------------------------------------------------------

}

/**
 * Agentic harness class for TPipe. Consists of a judge agent that determines task status and completion, a dispatch
 * agent that handles path control, and "paths" which are objects that contain code, tools, and agents that the dispatch
 * agent can invoke.
 *
 * Applies all of TPipe's powers such as lorebook, ditl, and vastly superior control and efficiency.
 *
 * Supports additional helper agents like a judge for task validation, turn limits, lorebook agents in blocking and asynchronous patterns, summary agents, and injectable harness agents that can be invoked at each step of the harness.
 *
 * Supports multiple memory management tactics like truncation, compaction, amnesia, and hybrid models.
 *
 * Includes a [KillSwitch] for cost control. The switch can be manually tripped via
 * [tripKillSwitch] (soft halt that lets the current turn finish) or auto-enforced: when an
 * [KillSwitch.inputTokenLimit] or [KillSwitch.outputTokenLimit] is configured, the harness loop
 * checks the running token total after each judge, dispatch, and path phase, and trips when the
 * limit is exceeded. The default [KillSwitch.onTripped] callback throws [KillSwitchException],
 * which the loop catches at the [runHarnessLoop] boundary so [runFinalizationPhase] can emit
 * the standard [HarnessFailed] event. The switch propagates to every [PathObject] registered
 * with the station so per-path limits are honored independently.
 *
 * Is able to automate its own config and apply core defaults internally.
 *
 * Includes full dsl support.
 *
 * Is also a p2p interface so a harness can be part of the path of another harness.
 *
 * ## Minimal viable station
 *
 * The smallest harness that runs a single-turn task end-to-end. This example uses the
 * Always-on judge (exit mechanism 1 of 3):
 *
 * ```kotlin
 * val station = pumpStation("hello") {
 *     setJudgeAgent(Pipeline().apply { add(OpenRouterPipe().apply {
 *         setModel("openai/gpt-4o-mini")
 *         setApiKey(System.getenv("OPENROUTER_API_KEY") ?: "")
 *     }}) })
 *
 *     setDispatchAgent(Pipeline().apply { add(OpenRouterPipe().apply {
 *         setModel("openai/gpt-4o-mini")
 *         setApiKey(System.getenv("OPENROUTER_API_KEY") ?: "")
 *     }}) })
 *
 *     path("answer") {
 *         pathDescription = "Responds to the user with a one-sentence answer."
 *         setExecutionFunction { content, _, _, _ ->
 *             MultimodalContent(text = "hello, world", passPipeline = true)
 *         }
 *     }
 *
 *     setKillSwitch(KillSwitch().apply { inputTokenLimit = 50_000 })
 * }
 *
 * val result = station.executeLocal(MultimodalContent(text = "say hello"))
 * // result.text == "hello, world"
 * ```
 *
 * Three exit mechanisms are supported (use the one that fits your domain):
 *
 * - **Always-on judge** (default): judge evaluates `isComplete` every turn.
 * - **FlagTriggered judge**: paths opt-in via `pumpStation.requestJudgeNextTurn()`;
 *   judge skips other turns. Configure with `setJudgeRunMode(PumpStationJudgeRunMode.FlagTriggered)`.
 * - **Path-terminated**: paths return `MultimodalContent.terminatePipeline = true` (failure)
 *   or `passPipeline = true` (success) on their result.
 *
 * A `HarnessWarning` event is emitted in `runPreInitPhase` when NONE of these mechanisms
 * are configured — see `HarnessWarning.code == WarningCode.NoExitSignalConfigured`. The
 * advisory is non-blocking; the harness continues and the developer sees the message in
 * the event log + trace.
 *
 * For a copy-paste-runnable example covering all three mechanisms, see
 * `TPipe-Defaults/src/main/kotlin/examples/pumpstation/PumpStationOpenRouterExample.kt`.
 * For a one-call factory that wires judge + dispatch + killSwitch + memory defaults,
 * see `Defaults.PumpStationDefaults.withOpenRouter(config)`.
 */
class PumpStation(
    killSwitch: KillSwitch? = null,
    steeringService: PumpStationSteeringService = PumpStationSteeringService()
) : P2PInterface
{
    //=====================================KillSwitch (Group O)========================================================
    /**
     * Backing field for the [P2PInterface.killSwitch] override. The custom setter propagates the
     * switch to every [PathObject] in [pathList] and [reservePaths] so per-path enforcement and
     * per-path DSL settings stay in sync with the station's switch. This mirrors the propagation
     * pattern already used by [Manifold], [Splitter], [Junction], [Connector], [MultiConnector],
     * and [DistributionGrid].
     */
    private var _killSwitch: KillSwitch? = killSwitch

    private val _steeringService: PumpStationSteeringService = steeringService

    /**
     * Kill switch attached to this PumpStation. The default [KillSwitch.onTripped] callback throws
     * [KillSwitchException]; the harness loop catches it at the [runHarnessLoop] boundary so
     * [runFinalizationPhase] can emit the standard failure event.
     *
     * Assigning a value propagates the switch to every registered [PathObject] (both
     * [pathList] and [reservePaths]). New paths added via [addPath] / [addReservePath] after the
     * switch is set will also receive the current switch automatically.
     */
    override var killSwitch: KillSwitch?
        get() = _killSwitch
        set(value)
        {
            _killSwitch = value
            pathList.values.forEach { it.killSwitch = value }
            reservePaths.values.forEach { it.killSwitch = value }
        }

    /**
     * The steering service instance used by the harness loop to inject [MultimodalContent]
     * into turnHistory at [PumpStationPausePhase] boundaries. Always non-null — defaults
     * to an empty [PumpStationSteeringService] when no `steeringPolicy { }` is configured.
     *
     * Accessed by the harness loop's phase-boundary injection points and by external
     * observability surfaces.
     */
    val steeringService: PumpStationSteeringService get() = _steeringService

    /**
     * The interrupt service instance used by the harness loop to receive
     * out-of-band messages that stop the active turn and re-enter from
     * BeforeJudge. Always non-null — defaults to an empty
     * [PumpStationInterruptService] when no `interruptPolicy { }` is configured.
     *
     * Accessed by the harness loop's phase-boundary interrupt poll points and
     * by external producer code that wants to send an interrupt.
     */
    val interruptService: PumpStationInterruptService = PumpStationInterruptService()

//=====================================Steering Runtime API (Group S)=================================================

/**
 * Enqueue a one-shot steering instruction. Fires at the next occurrence of
 * [phase] in the harness loop, then is automatically discarded.
 *
 * Thread-safe: may be called from any thread or coroutine context concurrently
 * with the running loop. The instruction is queued asynchronously and will not
 * block the caller.
 *
 * @param phase The PumpStationPausePhase boundary to inject at
 * @param content The MultimodalContent to append to turnHistory
 */
suspend fun steer(phase: PumpStationPausePhase, content: MultimodalContent)
{
    steeringService.enqueueOneShot(phase, content)
}

/**
 * Convenience overload accepting a plain text string. Constructs a MultimodalContent
 * with the given text and enqueues it as a one-shot.
 */
suspend fun steer(phase: PumpStationPausePhase, text: String)
{
    steer(phase, MultimodalContent(text = text))
}

/**
 * Set or replace the persistent overlay for [phase]. Fires on every occurrence of
 * [phase] until replaced by another `steerPersistent` call or cleared via `clearSteering`.
 *
 * Thread-safe: may be called concurrently with the running loop.
 *
 * @param phase The PumpStationPausePhase boundary to inject at
 * @param content The MultimodalContent to append to turnHistory on every match
 */
suspend fun steerPersistent(phase: PumpStationPausePhase, content: MultimodalContent)
{
    steeringService.setPersistent(phase, content)
}

/**
 * Convenience overload accepting a plain text string. Constructs a MultimodalContent
 * with the given text and registers it as a persistent overlay.
 */
suspend fun steerPersistent(phase: PumpStationPausePhase, text: String)
{
    steerPersistent(phase, MultimodalContent(text = text))
}

/**
 * Clear the persistent overlay for [phase]. Subsequent occurrences of [phase]
 * will not be steered unless a new overlay is set.
 *
 * Thread-safe: may be called concurrently with the running loop.
 *
 * @param phase The PumpStationPausePhase boundary to clear
 */
suspend fun clearSteering(phase: PumpStationPausePhase)
{
    steeringService.clearPersistent(phase)
}

//=====================================Steering Drain Helper (Group S)=================================================

/**
 * Drain all pending steering instructions for [phase] and prepare them for
 * insertion into turnHistory. Returns a list of MultimodalContent with the
 * canonical `metadata["steering"]` envelope stamped on each entry.
 *
 * Combination semantics at drain time:
 *   1. Persistent overlay (if set) is emitted first, with `metadata["steering"].persistent = true`
 *   2. One-shot instructions are emitted in FIFO order, each with `metadata["steering"].persistent = false`
 *
 * After the drain, the persistent overlay remains in place (fires again on next
 * phase match); the one-shot queue is empty for [phase].
 *
 * Returns an empty list if no overlay is set and no one-shots are queued.
 *
 * Thread-safe: the underlying service uses a Mutex to coordinate concurrent
 * producer-side calls.
 *
 * @param phase The PumpStationPausePhase to drain
 * @return List of MultimodalContent with steering metadata applied (empty list if nothing pending)
 */
suspend fun drainSteeringForPhase(phase: PumpStationPausePhase): List<MultimodalContent>
{
    val drained = steeringService.drainForPhase(phase)
    val now = System.currentTimeMillis()
    return drained.mapIndexed { index, content ->
        val isPersistent = if (index == 0 && steeringService.hasPersistentOverlay(phase)) {
            // First entry is the persistent overlay if it was set
            true
        } else {
            false
        }
        val steeringMetadata: Map<String, Any> = mapOf(
            "phase" to phase.name,
            "persistent" to isPersistent,
            "injectionId" to UUID.randomUUID().toString(),
            "timestamp" to now
        )
        // Build a fresh MutableMap<Any, Any> that merges existing metadata with the steering envelope.
        // Use deepCopy() (com.TTT.Util.deepCopy) instead of data-class .copy() so body-level
        // var current values (passPipeline, currentPipe, modelReasoning, pipeError, etc.) are
        // preserved on the returned content. A shallow data-class .copy() re-runs the body
        // initializer and substitutes the defaults, silently dropping the source's body state.
        val mergedMetadata: MutableMap<Any, Any> = mutableMapOf()
        content.metadata.forEach { (k, v) -> mergedMetadata[k] = v }
        mergedMetadata["steering"] = steeringMetadata
        val updated = content.deepCopy()
        updated.metadata = mergedMetadata
        updated
    }
}

//=====================================Interrupt Runtime API (Group I)=================================================

/**
 * Enqueue an interrupt. Fires at the next occurrence of [phase] in the
 * harness loop. Unlike [steer], an interrupt stops the active turn, rewinds
 * the harness state to the BeforeJudge of the current turn, and re-enters
 * the turn loop from the top with the interrupt message appended to
 * turnHistory (with the canonical `metadata["interrupt"]` envelope).
 *
 * Combination semantics when multiple [interrupt] calls queue for the same
 * phase before the next poll:
 *   - The first entry becomes the active interrupt (the rewind target).
 *   - Subsequent entries are forwarded to [steer] for the same phase as
 *     one-shot steering instructions. If [steer] is not configured for
 *     the phase, the overflow is silently dropped AND an
 *     [InterruptOverflowDropped] event is emitted for observability.
 *
 * Thread-safe: may be called from any thread or coroutine context.
 *
 * @param phase The PumpStationPausePhase boundary at which to interrupt
 * @param content The MultimodalContent to inject into turnHistory on rewind
 */
suspend fun interrupt(phase: PumpStationPausePhase, content: MultimodalContent)
{
    interruptService.enqueue(phase, content)
}

/**
 * Convenience overload accepting a plain text string. Constructs a
 * MultimodalContent with the given text and enqueues it.
 */
suspend fun interrupt(phase: PumpStationPausePhase, text: String)
{
    interrupt(phase, MultimodalContent(text = text))
}

//======================================Properties======================================================================

//---------------------------------------------Core Agents--------------------------------------------------------------

    /**
     * Reference to the parent P2PInterface when this PumpStation is nested inside a complex container.
     */
    private var parentInterface: P2PInterface? = null

    override fun setParentInterface(parent: P2PInterface)
    {
        parentInterface = parent
    }

    override fun getParentP2PInterface(): P2PInterface? = parentInterface

    /**
     * OPTIONAL: This agent judges if the given harness task is considered complete or not. Once completed,
     * the judge agent can shut down the harness and return the result. If not present, a stop signal
     * via [MultimodalContent.passPipeline], [MultimodalContent.terminatePipeline] or explicit pass signal
     * from [PumpStationTaskState] must be passed to exit the PumpStation. Otherwise, the loop will run until
     * the killswitch triggers or max turn count is hit.
     *
     * WARNING: [Splitter] may not be assigned as a judge agent. If assigned, an illegal argument exception will
     * be thrown.
     *
     * WARNING: If a pipeline is used as the agent, all pipes in the pipeline must use the same llm model. An illegal
     * argument exception will be thrown at runtime if this is not met.
     *
     * WARNING: TokenBudget settings must be assigned to all pipes, or to the [P2PInterface] agent. If this cannot
     * be resolved, OR token budget settings are not manually set in the PumpStation itself, an exception will be
     * thrown.
     */
    internal var judgeAgent: Pipeline? = null

    /**
     * Optional builder function to generate the [judgeAgent] on the fly. When non-null this will completely
     * override whatever is set for judgeAgent.
     */
    internal var judgeAgentBuilderFunction: (suspend (harness: PumpStation) -> Pipeline)? = null

    /**
     * Whether the judge agent's output is expected to follow the JSON contract
     * documented in [DEFAULT_JUDGE_PROMPT] (a JSON object with `isComplete` and
     * `shouldTerminate` fields). When true (the default), the harness parses the
     * agent's text via [parseJudgeVerdict] and the flag-based [withFlagCheck] runs
     * after. When false, the JSON parser is skipped entirely and the verdict
     * comes solely from MultimodalContent flags (terminatePipeline, passPipeline).
     *
     * Set to false via [setJudgeJsonContractEnabled] when wiring in a custom judge
     * agent that drives the loop with flags rather than JSON.
     */
    internal var judgeExpectsJsonContract: Boolean = true

    /**
     * Optional custom system prompt for the judge agent. When non-null, the
     * pump station injects this prompt into the decision pipe of the agent's
     * pipeline instead of the default [DEFAULT_JUDGE_PROMPT]. Setting a custom
     * prompt also disables the JSON contract (the agent drives the verdict via
     * MultimodalContent flags only). Set back to null to re-enable the default
     * contract.
     */
    internal var customJudgeSystemPrompt: String? = null

    /**
     * Optional custom system prompt for the dispatch agent. When non-null, the
     * pump station injects this prompt into the decision pipe of the agent's
     * pipeline instead of the default [DEFAULT_DISPATCH_PROMPT].
     */
    internal var customDispatchSystemPrompt: String? = null

    /**
     * Optional custom system prompt for the path-safety agent. When non-null,
     * the pump station injects this prompt into the decision pipe of the
     * agent's pipeline instead of the default [DEFAULT_PATH_SAFETY_PROMPT].
     * Setting a custom prompt also disables the JSON contract.
     */
    internal var customPathSafetySystemPrompt: String? = null

    /**
     * Optional custom system prompt for the health agent. When non-null, the
     * pump station injects this prompt into the decision pipe of the agent's
     * pipeline instead of the default [DEFAULT_HEALTH_PROMPT].
     */
    internal var customHealthSystemPrompt: String? = null

    /**
     * Optional custom system prompt for the lorebook agent. When non-null, the
     * pump station injects this prompt into the decision pipe of the agent's
     * pipeline instead of the default [DEFAULT_LOREBOOK_PROMPT].
     */
    internal var customLorebookSystemPrompt: String? = null

    /**
     * Optional custom system prompt for the goal agent. When non-null, the
     * pump station injects this prompt into the decision pipe of the agent's
     * pipeline instead of the default [DEFAULT_GOAL_PROMPT].
     */
    internal var customGoalSystemPrompt: String? = null

    /**
     * REQUIRED: This agent evaluates what the next steps in the harness needs to be, and dispatches the to the
     * next path. (Equal to a tool call, or turn in traditional agent harnesses.) If null, or if a [Splitter] has
     * been assigned to this an illegal argument exception will be thrown.
     *
     * WARNING: If a pipeline is used as the agent, all pipes in the pipeline must use the same llm model. An illegal
     * argument exception will be thrown at runtime if this is not met.
     *
     * WARNING: TokenBudget settings must be assigned to all pipes, or to the [P2PInterface] agent. If this cannot
     * be resolved, OR token budget settings are not manually set in the PumpStation itself, an exception will be
     * thrown.
     */
    internal var dispatchAgent: Pipeline? = null

    /**
     * Optional builder function to generate [dispatchAgent] on the fly, overrides any value set to dispatch agent
     * when not null.
     */
    internal var dispatchAgentBuilderFunction: (suspend (harness: PumpStation) -> Pipeline)? = null


    /**
     * Optional agent that is able to intervene with path calls. Can be enabled for things like enforcing specific
     * retries when a path has an error, requring an agent to call a specific path under a specific condtition etc.
     * When this fires it will investigate its assignment, and help reinforce correct agent behavior in responce to
     * various path output calls.
     *
     * intervetionAgent is invoked post path execution after DITL validator and branch invocations, and is able to
     * hook it's output automatically into the turn history system. This allows it to provide nudges, hints, and
     * agressive suggestions to the agent about how it should handle the output of a given path when it detects
     * intervenion and further guidance to steer the main dispatch and judge agents are required.
     */
    private var interventionAgent: P2PInterface? = null

    /**
     * Optional builder function for the intervention agent that overrides [interventionAgent] at runtime each
     * time it would be called.
     */
    private var interventionAgentBuilderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null

    /**
     * Proactive health monitoring agent. Fires based on [healthAgentTurnInterval]
     * or [healthAgentErrorRatioThreshold] to detect harness degradation,
     * context drift, looping, or struggle patterns.
     *
     * Unlike [interventionAgent] which is REACTIVE (fires after failure), healthAgent
     * is PROACTIVE and fires conditionally before the judge agent.
     */
    private var healthAgent: P2PInterface? = null

    /**
     * Builder function — creates fresh thread-safe agent instance each invocation.
     */
    private var healthAgentBuilderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null

    /**
     * Fire healthAgent after this many turns since last health check.
     * null = disabled.
     */
    private var healthAgentTurnInterval: Int? = null

    /**
     * Fire healthAgent when errors/turns ratio exceeds this threshold (0.0–1.0).
     * null = disabled.
     */
    private var healthAgentErrorRatioThreshold: Double? = null

    /**
     * Concurrency mode for healthAgent execution.
     * Blocking: judge waits for healthAgent to complete.
     * Async: judge fires immediately; healthAgent runs in background.
     */
    private var healthAgentConcurrencyMode: PumpStationConcurrencyMode? = null

    /**
     * Tracks turn index of last health check for interval evaluation.
     */
    private var lastHealthCheckTurn: Int = 0

    /**
     * Optional background lorebook agent. Invoked as the first background agent in the harness if present.
     * Is used to update the lorebook of the [PumpStation] internal context window/minibank.
     */
    private var lorebookAgent: P2PInterface? = null

    /**
     * Bindable builder function. This will spawn a brand-new agent at every point of invocation and execution
     * to ensure a thread safe, and stateless implementation. If not assigned, the PumpStation will attempt to
     * duplicate the agent using reflection.
     */
    private var lorebookAgentBuilderFunction : (suspend (harness: PumpStation) -> P2PInterface)? = null

    /**
     * Optional background agent to generate summaries of the events occurring in the harness for compaction, and
     * turn history drop-off.
     */
    private var summaryAgent: P2PInterface? = null

    /**
     * Bindable builder function. This will spawn a brand-new agent at every point of invocation and execution
     * to ensure a thread safe, and stateless implementation. If not assigned, the PumpStation will attempt to
     * duplicate the agent using reflection.
     */
    private var summaryAgentBuilderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null

    /**
     * Allows the user to add additional required agents between the output of dispatch, and the return to the judge
     * agent. Each slot stores either a direct agent reference or a builder function, along with the concurrency
     * mode that controls whether the agent runs synchronously (Blocking) or fires asynchronously (Async).
     */
    private var additionalHarnessAgentSlots: MutableList<HarnessAgentSlot> = mutableListOf()

    /**
     * Optional goal agent. This agent can be used to scan the work done by the harness once the harness is in an
     * exit state. If the agent fires [MultimodalContent.terminatePipeline] this will be treated as a failure state
     * and can be used to return back to the judge, and the dispatcher agent to force work to resume.
     *
     * This can be seen as effectively the same as a ralph loop in terms of enforcement.
     */
    internal var goalAgent: P2PInterface? = null


    /**
     * Optional bindable builder function. Allows for a dynamically generated agent at runtime. If non-null
     * [goalAgent] will be ignored and this will be invoked to generate the valid agent object at runtime.
     */
    internal var goalAgentBuilderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null

    /**
     * Optional post-success agent. Fires inside [runExitFlow] after the goal agent passes,
     * or on the no-goal-agent / passPipeline-routed exit paths. Receives the goal agent's
     * output (or the harness's exit-flow content when no goal agent is configured). Output
     * becomes the harness's final deliverable on pass; a [MultimodalContent.terminatePipeline]
     * result halts the harness with [PumpStationExitReason.JudgeComplete] without re-loop.
     *
     * @see [postGoalAgentBuilderFunction] for runtime override
     * @see [postGoalFunction] for the synchronous transform that precedes this agent
     */
    internal var postGoalAgent: P2PInterface? = null

    /**
     * Optional bindable builder function for [postGoalAgent]. When non-null this is invoked
     * to generate the agent per harness invocation and [postGoalAgent] is ignored.
     */
    internal var postGoalAgentBuilderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null

    /**
     * Optional DITL function fired inside [runExitFlow] after the goal agent passes (or
     * when no goal agent is configured and the harness is exiting through [runExitFlow]).
     * Synchronous transform: receives the goal agent's output and returns a possibly-modified
     * [MultimodalContent]. Precedes [postGoalAgent] when both are configured.
     */
    internal var postGoalFunction: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)? = null

    /**
     * Stored paths on this harness. Each path is mapped by its name from inside the path object, and the
     * reference to the object. Names are normalized to be case-insensitive, and all path calls will normalize
     * to lowercase when calling a path.
     */
    internal val pathList: MutableMap<String, PathObject> = mutableMapOf()

    /**
     * Paths stored here are placed in "reserve". This allows them to be loaded into the system prompt of the
     * dispatch agent dynamically which is useful for keeping token costs and usage under control. A path cannot
     * exist both in reserve, and in the main [pathList] at the same time.
     */
    internal val reservePaths: MutableMap<String, PathObject> = mutableMapOf()

/**
 * Normalize a path-name key for the [PumpStation.pathList] and
 * [PumpStation.reservePaths] maps. Path lookup is case-insensitive
 * per the contract documented on [PumpStation.pathList].
 */
private fun pathKey(name: String): String = name.lowercase()



//--------------------------------------------------Config--------------------------------------------------------------

    /**
     * Top level variable that allows the injection of a persona, or personality. This value can also be automatically
     * applied to any role-play reasoning pipes deployed into pipes and pipelines. Forces the agent to take on the persona
     * and prioritize the persona above every other instruction.
     */
    internal var personality = ""

    /**
     * Treated as the "system prompt" for the harness. Is injected into the harness after initial backed in harness
     * system instructions that are used to teach the agents how to drive the harness. Treated as highest priority
     * after the harness core guidelines of driving and always present regardless of user instructions.
     */
    internal var systemTask = ""

    /**
     * Secondary after [systemTask] these are user guidelines the judge and dispatch agents should follow as long as they
     * are able to still fully follow their system task. This is where traditional "skills" in other harnesses would
     * be injected.
     */
    internal var userGuidelines = ""

    /**
     * Third tier down. This is the initial user prompt sent to this harness via the [MultimodalContent] input or
     * P2P [P2PInterface.executeLocal] invocation. This is the core task of work to be done within the constraints
     * of both the core system prompts of the agents, the system task which is injected second, and the userGuidelines
     * aka: "skills" third. This will always be held at the top of the history and made clear to the agent that this
     * is the core task it must complete within its boundaries.
     */
    internal var entryUserPrompt = ""

    /**
     * Maximum number of harness turns before forced exit. Acts as a safety limit to avoid
     * llm loops and exploding token costs. The harness loop in
     * [com.TTT.Pipeline.PumpStationLoop.runHarnessLoop] reads this field via
     * [maxTurnsInternal] and terminates with [PumpStationError.MaxTurnsExceeded] /
     * [PumpStationExitReason.MaxTurnsHit] when [com.TTT.Pipeline.PumpStationTaskState.turnIndex]
     * reaches the cap.
     */
    private var maxTurns = 50

    /**
     * Controls when the judge agent runs inside the harness loop. See [PumpStationJudgeRunMode]
     * for the semantics. Defaults to [PumpStationJudgeRunMode.Always] (legacy behavior).
     * Mutation goes through [setJudgeRunMode] so the fluent API is preserved.
     */
    internal var judgeRunModeInternal: PumpStationJudgeRunMode = PumpStationJudgeRunMode.Always

    /**
     * When true (default), the judge phase is skipped on turn 0 and a [JudgeSkipped] event with
     * `reason = "first_turn"` is emitted in its place. The harness then runs dispatch and at least
     * one path before the judge gets a verdict vote.
     *
     * Why this exists: a live judge LLM can see the pre-dispatch state (just the system task and
     * user prompt with no paths fired yet) and return `isComplete = true` based on a hallucinated
     * brief. The harness then short-circuits via `runExitFlow` and the loop is permanently broken
     * before any path ever runs. Skipping the judge on turn 0 forces the pipeline to make at least
     * one real attempt before judging completion.
     *
     * Does NOT interact with [PumpStationJudgeRunMode.FlagTriggered] — that mode's `no_flag_set`
     * skip takes precedence and continues to use its canonical reason.
     *
     * Set to false to restore the legacy "judge fires on every turn including turn 0" behavior.
     */
    internal var skipJudgeOnFirstTurnInternal: Boolean = true

    /**
     * Dispatch contract shape for this PumpStation. Default is [PathExecutionShape.SinglePath]
     * which preserves the pre-existing dispatch JSON contract. Setting this to
     * [PathExecutionShape.MultiPath] injects the multi-path dispatch prompt and routes the
     * dispatch output through [com.TTT.Pipeline.parseDispatchOutputMulti].
     *
     * Read via [getPathExecutionShape]. Internal accessor for the loop file is
     * [pathExecutionShapeInternal].
     */
    internal var pathExecutionShape: PathExecutionShape = PathExecutionShape.SinglePath

    /**
     * Defines the maximum number of concurrent background agents that can be spawned at any given time.
     * If a spawn request would exceed this number it will be queued and batched out at the maximum number
     * allowed at a given time.
     */
    private var maxConcurrentBackgroundAgents = 3

    /**
     * Defines the max number of foreground agents that can be spawned by path calls, or by the dispatch agent.
     * This is passed into the path object and acts as hint the coder can abide by to constrain max agent concurrency.
     */
    private var maxConcurrentForegroundAgents = 3

    /**
     * Defines how many turns to wait per firing of foreground agents. Allows for customizing how speed, time,
     * and token costs by limiting how frequently foreground agents can run.
     *
     * @see foregroundAgents
     */
    private var foregroundTurnInterval = 0

    /**
     * Defines how many turns to wait before firing the background agents. Allows developers to customize the
     * frequency of background agents and better control token costs and usage.
     */
    private var backgroundTurnInterval = 5

    /**
     * Defines the default concurrency mode. This affects how background tasks impact the harness loop.
     *
     * @see PumpStationConcurrencyMode
     */
    private var concurrencyMode: PumpStationConcurrencyMode = PumpStationConcurrencyMode.Async

    /**
     * Defines the default memory management mode. Defaults to compaction in the event this is not defined,
     * or we can't infer the correct mode based on background agent and other defined settings.
     */
    private var memoryManagementMode: PumpStationMemoryManagementMode = PumpStationMemoryManagementMode.Compaction

    /**
     * Defines the % filled ratio of the available context window space that can be used up before triggering compaction.
     */
    private var compactionThreshold = .8

    /**
     * Defines the default strategy for compaction if compaction is enabled.
     *
     * @see PumpStationCompactionStrategy
     */
    private var compactionStrategy = PumpStationCompactionStrategy.Whole


    /**
     * Maximum number of [ConverseHistory] elements allowed in the turn history. If this size would be exceeded.
     * The top most element of the turn history will be popped off the stack.
     */
    private var maxTurnHistorySize = 50

    /**
     * Generated summary for the harness. This compacts older turn history events with a summary either blocking,
     * or async as turns are stored. Is injected if present, prior to the turn history in the agent's context.
     */
    internal var turnSummary = ""

    /**
     * If true, and the dispatch agent generates invalid json for a path request, throw an error, and
     * exit the PumpStation harness on the spot.
     */
    private var stopHarnessOnInvalidPathRequest = false

    /**
     * Mirror of [PumpStationFailurePolicy.requirePathSelectionRationale].
     * Cached at build/init time and re-read on every dispatch turn.
     * If true, the dispatch LLM is required to commit a non-null
     * [PathRequest.pathSelectionRationale] on every turn; empty emissions
     * cause a Hint to be appended to the next-turn dispatch history.
     */
    private var requirePathSelectionRationale = true

//--------------------------------------------------Internal------------------------------------------------------------

    /**
     * Used to track init state and auto-init if the user forgot to invoke init at execution time.
     */
    private var harnessIsReady = false

    //=====================================Group O: Token Accumulator (Kill Switch Input)==============================
    /**
     * Tracks total input/output tokens consumed by agents across the harness run, so the
     * [checkKillSwitch] enforcement can compare against [KillSwitch.inputTokenLimit] and
     * [KillSwitch.outputTokenLimit]. Reset in [P2PInitInternal]. Updated by
     * [addTokenUsage] after each agent call so the check sees a running total.
     *
     * @property runStartElapsedMs Wall-clock millis when the current harness run started, used to
     *     populate [KillSwitchContext.elapsedMs]. Zero before [P2PInitInternal] has run.
     */
    private var runStartElapsedMs: Long = 0

    /**
     * Total input tokens consumed by all agents in this run. Monotonically increasing.
     * Reset to 0 at the start of each [executeLocal] call.
     */
    private var accumulatedInputTokens: Int = 0

    /**
     * Total output tokens produced by all agents in this run. Monotonically increasing.
     * Reset to 0 at the start of each [executeLocal] call.
     */
    private var accumulatedOutputTokens: Int = 0

    /**
     * Accumulate token usage from a single agent call into the station-level running total.
     * Called from the harness loop after each [agentTokenUsage] read so [checkKillSwitch] can
     * compare the running total against the kill switch limits.
     *
     * @param input  Number of input tokens the agent consumed on this call.
     * @param output Number of output tokens the agent produced on this call.
     */
    internal fun addTokenUsage(input: Int, output: Int)
    {
        if (input > 0) accumulatedInputTokens += input
        if (output > 0) accumulatedOutputTokens += output
    }

    /**
     * Descriptors produced from initializing each path. This is required to pass onward to the dispatch
     * agent's internal systems to allow it to call a given path. The general expectation is that the dispatch agent
     * will eventually bubble down to a child agent which will be a pipeline, and that pipeline will embed
     * a pipe that contains this and has the ability to call a path as JSON.
     */
    private val pathDescriptors = PathDescriptionList()

    /**
     * Stored turn history. The entire history is shown to the harness agent after the summary is provided if
     * the summary is present. The judge and dispatch agents will use this to determine task status, and which path
     * to traverse next in the harness loop.
     *
     * turnHistory is updated after the completion of the path that was called by the dispatcher agent, and before
     * any foreground agents are invoked. Optionally, a foreground agent can update turn history again after it runs but
     * this is an action performed by a transformation function rather than the harness itself.
     */
    val turnHistory = ConverseHistory()

    /**
     * This is the complete set of events + path call outcomes generated over the entire runtime of this harness.
     * This is never shown to agents, but can be optionally used in DITL functions, sent to external systems like
     * command line interfaces, Or used by the goal agent to validate the entire work done. This is updated
     * at the same time [turnHistory] is.
     */
    val rawTurnHistory = ConverseHistory()

    /**
     * Internal mechanism to safely save and store outputs that might cause errors, or blowout the context window
     * of an agent. When the stache is saved to, the turn is replaced by a customizable message that can instruct
     * the harness agents. The map consists of a string based Id that is definable, and can be retrieved automatically
     * by a path designed and equipped to handle a stache situation.
     */
    private val stash = mutableMapOf<String, ConverseData>()

    /**
     * Optional metadata that can be used for anything the developer wants in the harnness. Useful for storing
     * whatever arbitrary data might need to be shared between functions across agents or other sub-systems.
     */
    val metadata = mutableMapOf<Any?, Any?>()

    /**
     * Internal context window addressable by this harness, and able to be passed into the various agents
     * that are deployed here.
     */
     val contextWindow = ContextWindow()

    /**
     * Internal miniBank serves the same purpose as [contextWindow]
     */
    val miniBank = MiniBank()

    /**
     * Mutex lock used for async lorebook agents. This allows us to queue up and safely ensure that the lorebook agents
     * are able to update the lorebook in sequence even if the turn harness moves fast enough to cause a backlog of
     * lorebook updates.
     */
    val lorebookMutex = Mutex()

    /**
     * Mutex lock used for async summary generation. Ensures that if summary agents gets backlogged, summaries will
     * be generated in chronological order and remain accurate to events.
     */
    val summaryMutex = Mutex()

//-----------------------------------------------NEW: Infrastructure---------------------------------------------------

    /**
     * Internal task state — single source of truth for harness inspection, replay, and resume.
     * Not exposed to developers directly — accessible via public inspection APIs.
     */
    internal val taskState = PumpStationTaskState(
        runId = "",
        status = PumpStationStatus.NotStarted,
        phase = PumpStationPhase.PreInit,
        turnIndex = 0,
        originalInput = null,
        latestContent = null,
        selectedPathName = null,
        lastPathResult = null,
        lastError = null,
        exitReason = null,
        memoryActionResult = null
    )

    //=====================================v3: Compaction state and backups==========================================

    /**
     * Per-PumpStation compaction cursor. Read/written by the compaction orchestrator. The
     * cursor lives both on the [taskState] (for snapshot/replay) and on this backing field
     * (for fast internal access in the orchestrator hot path). They are kept in sync by the
     * [compactionCursorInternal] getter/setter below.
     */
    internal var compactionCursor: CompactionCursor = CompactionCursor()

    /**
     * Per-PumpStation lorebook cursor. Same dual-location pattern as [compactionCursor].
     */
    internal var lorebookCursor: LorebookCursor = LorebookCursor()

    /**
     * In-memory ring buffer of [CompactionBackup] snapshots. Captured before every
     * compaction attempt; used by [restoreFromBackup] to roll back inflated or pre-empted
     * attempts. Bounded by [maxCompactionBackups] (default 3, configurable).
     */
    private val compactionBackups: ArrayDeque<CompactionBackup> = ArrayDeque()

    /**
     * Developer-supplied pre-prune transform. Applied to the raw [turnHistory] before it
     * reaches the summary agent. When null, the default pruner (drop blank, drop stash
     * placeholder, collapse duplicate system messages, drop pure echoes, collapse
     * tool-call/result pairs, strip excess metadata, normalize whitespace, drop turns
     * already in [turnSummary]) runs instead.
     */
    private var prePruneTransform: (suspend (List<ConverseData>, PumpStation) -> List<ConverseData>)? = null

    /**
     * Developer-supplied extra pruner that wraps the default. Applied after the default
     * pruner. Multiple extra pruners can be registered; they run in registration order.
     */
    private val extraPrePruneTransforms: MutableList<suspend (List<ConverseData>, PumpStation) -> List<ConverseData>> = mutableListOf()

    /**
     * DITL hook that fires when a [CompactionBackup] is restored. Receives the backup
     * being restored, a human-readable reason, and the harness. May return a replacement
     * backup (e.g. with patched state) or null to use the restored one as-is. Throwing
     * from this hook converts the orchestrator's retry to a handoff-to-truncation.
     */
    private var compactionRolledBackFunction: (suspend (CompactionBackup, String, PumpStation) -> CompactionBackup?)? = null

    /**
     * Number of pre-prune + summarize + fold iterations the orchestrator will attempt
     * before handing off to truncation. Configurable via [setMaxCompactionAttempts].
     */
    internal var maxCompactionAttempts: Int = 2

    /**
     * Token budget per chunk when the Chunked strategy is used. Each chunk's estimated
     * input token count must fit under this budget; the orchestrator partitions
     * [turnHistory] into `max(1, tokens / chunkTokenBudget)` chunks.
     */
    internal var chunkTokenBudget: Int = 2000

    /**
     * Hard cap on the number of chunks produced by a single Chunked strategy attempt.
     * Prevents pathological partitioning of very large turn histories.
     */
    internal var maxChunks: Int = 16

    /**
     * Semaphore permit count for the [ChunkFanoutMode.Parallel] strategy. Bounds the
     * number of concurrent chunk-summarize calls to the summary agent.
     */
    internal var maxParallelChunks: Int = 4

    /**
     * Maximum number of [CompactionBackup] snapshots retained in the ring buffer.
     */
    internal var maxCompactionBackups: Int = 3

    /**
     * Chunk fan-out mode for the [PumpStationCompactionStrategy.Chunked] strategy.
     * Sequential is the default (causal ordering preserved, single-mutex contract);
     * Parallel requires an explicit opt-in via [setCompactionFanoutMode] or the
     * `compaction { fanout = Parallel }` DSL block.
     */
    internal var compactionFanoutMode: ChunkFanoutMode = ChunkFanoutMode.Sequential

    /**
     * Headroom threshold (0.0-1.0) below which the [PumpStationCompactionStrategy.Hybrid]
     * strategy downgrades to Chunked. Above this headroom, Hybrid delegates to Whole.
     */
    internal var hybridWholeHeadroom: Double = 0.3

    /**
     * Token budget settings for this PumpStation. Stored so it can be propagated
     * to child agents and retrieved via [getTokenBudgetSettings].
     */
    internal var tokenBudgetSettings: TokenBudgetSettings? = null

    /**
     * Pipe settings for this PumpStation. Stored so it can be propagated to child agents.
     */
    private var pipeSettings: PipeSettings? = null

    /**
     * Failure recovery policy with sensible defaults. Controls dispatch JSON repair,
     * stash behavior, and intervention triggers.
     */
    val failurePolicy = PumpStationFailurePolicy()

    /**
     * Background event queue for async events from background paths, lorebook, and summary agents.
     * The foreground loop drains this queue at safe phase boundaries.
     */
    private val backgroundEventQueue = Channel<PumpStationEvent>(Channel.UNLIMITED)

    /**
     * Monotonic sequence counter assigned to every [PendingTurnEntry] at enqueue time.
     * Async paths and async harness agents pull a [seq] from this counter, so the
     * foreground drain can sort pending entries by [seq] even when out-of-order
     * completions arrive. The counter is a [java.util.concurrent.atomic.AtomicLong]
     * because the async fire site (which is a coroutine) is the only writer.
     */
    private val asyncSeqCounter = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Queue of pending turn entries produced by async paths and async harness agents.
     * Each entry carries the [PendingTurnEntry.seq] assigned at enqueue. The foreground
     * drain pulls entries from this channel in batches and merges them into
     * [turnHistory] in [seq] order. Backed by [Channel.UNLIMITED] so async producers
     * never block on a full queue; the harness's [maxConcurrentBackgroundAgents]
     * [Semaphore] is the real flow-control surface.
     */
    private val pendingAsyncResults = Channel<PendingTurnEntry>(Channel.UNLIMITED)

    /**
     * Mutex held by all async-origin writes to harness state. The [ConverseHistory]
     * data class does not have its own internal lock, so async coroutines
     * ([appendTurnEntryAsync], [appendTurnEntriesAsync]) must acquire this mutex
     * before touching [turnHistory], [rawTurnHistory], [turnSummary],
     * [contextWindow], or [taskState]. Foreground code paths retain the existing
     * single-coroutine funnel and do not need to take this mutex.
     */
    private val historyMutex = Mutex()

    /**
     * Station-scoped [CoroutineScope] backing every async coroutine launched by the
     * harness. Replaces the previous  so async work cannot
     * outlive [executeLocal] (closed by [cancelAsyncJobs] in [runFinalizationPhase])
     * and so the harness can enforce a single cancellation boundary.
     */
    val asyncScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.Dispatchers.Default
        )

    /**
     * When true, async paths are appended to [turnHistory] by the foreground drain
     * on completion. The default is true because the typical use case for an async
     * path is to land its result back into the conversation. Per-path opt-out is
     * available via [PathObject.setSuppressHistoryEmit].
     */
    private var asyncPathsAppendToTurnHistory: Boolean = true

    /**
     * When true, async harness agents (slots added with [PumpStationConcurrencyMode.Async])
     * are appended to [turnHistory] by the foreground drain on completion. The default
     * is false to preserve the historical fire-and-forget semantics of async harness
     * agents. Per-slot opt-in is available via [HarnessAgentSlot.appendsToTurnHistory]
     * and the matching DSL knob.
     */
    private var asyncAgentsAppendToTurnHistory: Boolean = false

    /**
     * Optional grace period (milliseconds) given to in-flight async coroutines
     * after [runFinalizationPhase] before [cancelAsyncJobs] cancels the
     * [asyncScope]. When null (the default), the cancel is off — [cancelAsyncJobs]
     * does not enforce a timeout and instead yields once before the cancel, so
     * long-running async paths (e.g. an async path that wraps a multi-minute LLM
     * call) are not artificially timeboxed. When set, coroutines that do not
     * finish within the window are cancelled; their partial results are NOT
     * merged into [turnHistory].
     *
     * The default is null because TPipe intentionally does not impose arbitrary
     * timeouts on user work. Developers who need a hard upper bound should set
     * this to a value that matches their worst-case LLM round-trip plus safety
     * margin (e.g. 30 minutes for production agent harnesses).
     */
    private var asyncJobGracePeriodMs: Long? = null

    /**
     * When true, async work is launched on [asyncScope]; when false, async
     * work is launched on [GlobalScope] (the pre-substrate behavior). Defaults
     * to true so async coroutines cannot outlive [executeLocal].
     */
    private var asyncJobsScopedToStation: Boolean = true

    /**
     * Optional test observability hook. When set, every [PumpStationEvent] emitted via [emitEvent]
     * is also dispatched synchronously to this observer. Used by tests to assert on event flow
     * without having to drain the [backgroundEventQueue] channel.
     */
    @kotlinx.serialization.Transient
    private var eventObserver: ((PumpStationEvent) -> Unit)? = null

    /**
     * When true, the harness emits events to the global [PipeTracer] (in addition to the in-process
     * event queue and observer hook) so a trace can be exported via [getTraceReport] and visualized
     * by [com.TTT.Debug.TraceVisualizer].
     */
    private var tracingEnabled: Boolean = false

    /**
     * Tracing configuration in effect when [tracingEnabled] is true.
     */
    private var traceConfig: TraceConfig = TraceConfig(enabled = true)

    /**
     * Manifest of stashed content entries. Mirrors the [stash] map with richer metadata
     * so agents and DITL tooling can reason about what was stashed without loading content.
     */
    private val stashManifest = mutableListOf<StashEntry>()

    /**
     * Developer-provided external context supplier. Called each turn to populate context
     * available to reserve path [revealWhen] predicates.
     */
    var externalContextProvider: (() -> MutableMap<String, Any>)? = null



    /**
     * Maximum number of consecutive goal-evaluation failures before giving up
     * on the current task. Defaults to 3.
     */
    private var maxGoalFailAttempts: Int = 3

    /**
     * Maximum number of raw turn history entries to retain, or null to disable
     * the cap. Distinct from the ConverseHistory turn history cap.
     */
    private var maxRawTurnHistorySize: Int? = null

    /**
     * Threshold (0.0-1.0) of context window utilization that triggers blowout
     * detection. Defaults to 0.9 (90%).
     */
    private var blowoutThreshold: Double = 0.9

    /**
     * Timeout in milliseconds for memory update operations. Defaults to 30s.
     */
    private var memoryUpdateTimeoutMs: Long = 30_000L

    /**
     * Maximum number of blowout recovery attempts before forced halt.
     * Defaults to 3.
     */
    private var maxBlowoutRecoveries: Int = 3

    /**
     * Maximum number of tokens allowed in a repair/regeneration prompt.
     * Defaults to 500.
     */
    private var maxRepairPromptTokens: Int = 500

    /**
     * Loop guard: maximum consecutive turns on the same path before triggering response.
     */
    private var maxConsecutiveSamePath: Int? = null

    /**
     * Loop guard: maximum total invocations allowed per path name.
     */
    private var maxTotalPathCallsPerPath: Int? = null

    /**
     * Loop guard: maximum consecutive dispatches of unregistered path names
     * before halting with [PumpStationExitReason.LoopGuardTripped]. Null (the
     * default) preserves today's unbounded behavior — the harness will keep
     * retrying a non-existent path until the turn budget exhausts.
     */
    private var maxConsecutiveUnknownPaths: Int? = null

//=====================================Group Q: SafePrune (optional deterministic cleanup)==========================

    /**
     * Master switch for the SafePrune phase. When false (the default) the phase is a
     * no-op and emits no events. When true, fires every turn after the existing prunes
     * if turnHistory.size exceeds [safePruneSizeThreshold].
     *
     * @see SafePruneStrategy
     */
    private var safePruneEnabled: Boolean = false

    /**
     * Minimum turnHistory size required for SafePrune to fire on a given turn. Defaults
     * to 30 — below that the pass is not worth its CPU cost. Each strategy's own gating
     * (protectRecentN, hashWindow, maxToolArgLength) applies independently.
     */
    private var safePruneSizeThreshold: Int = 30

    /**
     * Number of most-recent entries that SafePrune strategies must NOT mutate. Protects
     * the just-produced path output and the immediately-prior context from being rewritten
     * by cleanup strategies that could drop or replace them.
     */
    private var safePruneProtectRecentN: Int = 3

    /**
     * Window size for the [SafePruneStrategy.DeduplicateByHash] strategy. Only entries
     * within this many positions of an earlier entry are eligible for hash-based dedup.
     * Conservative default of 10 keeps repeated-question drops rare.
     */
    private var safePruneHashWindow: Int = 10

    /**
     * Maximum tool-response text length before [SafePruneStrategy.StripLongToolArguments]
     * replaces it with a truncated stub. Conservative default of 2000 covers typical
     * tool outputs without truncating load-bearing arguments.
     */
    private var safePruneMaxToolArgLength: Int = 2000

    /**
     * Strategies currently enabled. Empty by default. Add via [enableSafePruneStrategy],
     * remove via [disableSafePruneStrategy], or replace wholesale via [setSafePruneStrategies].
     */
    private val safePruneEnabledStrategies: MutableSet<SafePruneStrategy> = mutableSetOf()

    /**
     * Per-strategy policy overrides. Empty by default; each strategy uses the
     * PumpStation-global [safePruneSizeThreshold] / [safePruneProtectRecentN].
     * Add via [setSafePruneStrategyPolicy]; remove by clearing the map.
     */
    private val safePruneStrategyPolicies: MutableMap<SafePruneStrategy, SafePrunePolicy> = mutableMapOf()

    /**
     * Per-strategy dry-run flags. Empty by default; all strategies mutate normally.
     * When a strategy is in this set, its mutation is skipped and a
     * [SafePruneDryRunCompleted] event is emitted instead of [SafePruneApplied].
     */
    private val safePruneStrategyDryRun: MutableSet<SafePruneStrategy> = mutableSetOf()

    /**
     * Policy for how the harness responds when [maxTotalPathCallsPerPath] is exceeded.
     * Default is [PathLimitExceededPolicy.Skip] — path is moved to reserve.
     */
    var pathLimitExceededPolicy: PathLimitExceededPolicy = PathLimitExceededPolicy.Skip

    /**
     * Optional DITL function invoked when [maxTotalPathCallsPerPath] is exceeded.
     * Allows dynamic runtime policy instead of static [PathLimitExceededPolicy].
     * If null, static pathLimitExceededPolicy value is used.
     */
    private var pathLimitExceededFunction: (suspend (
        path: PathObject,
        reason: String,
        harness: PumpStation
    ) -> PathLimitExceededResult)? = null

    /**
     * Tracks invocation counts per path name for loop guard enforcement.
     */
    internal val pathCallCounts = mutableMapOf<String, Int>()

    /**
     * Counts consecutive turns on the same path for [maxConsecutiveSamePath] enforcement.
     */
    private var consecutivePathCount = 0

    /**
     * Counts consecutive [PumpStationError.UnknownPath] outcomes for
     * [maxConsecutiveUnknownPaths] enforcement. Reset to 0 on any successful
     * path resolution or on a guard trip. Internal so [runPathFlow] in
     * [PumpStationLoop.kt] can mutate the counter without a setter helper;
     * the public API for the guard is the DSL `maxConsecutiveUnknownPaths` field.
     */
    internal var consecutiveUnknownPathCount: Int = 0

    /**
     * Name of the last selected path, used to detect same-path repetition.
     */
    private var lastSelectedPathName: String? = null

    /**
     * Reserve paths that have been revealed (sticky — stay revealed once revealed).
     */
    private val revealedReservePaths = mutableSetOf<String>()

    /**
     * Phase boundaries at which the harness will pause for external inspection/intervention.
     * Empty set means no pause (run continuously).
     */
    private var pausePhases = emptySet<PumpStationPausePhase>()

    /**
     * Dispatcher rules for advisory routing hints and hard loop guard enforcement.
     */
    private val dispatcherRules = mutableListOf<DispatcherRule>()

    /**
     * Cached reference to the dispatch agent's pipeline for P2P hook injection.
     */
    private var dispatcherPipeline: Pipeline? = null



//---------------------------------------------------DITL---------------------------------------------------------------

    /**
     * Optional agent that fies prior to starting the harness. This agent can be used for any initial setup
     * or states that need to be handled prior to giving the task to the judge, and dispatch agents.
     */
    private var preInitAgent: P2PInterface? = null

    /**
     * DITL function invoked at the very beginning of harness runtime. Activates prior to any action or state.
     * Triggered only once, at the very startup of the harness. Allows for inspection, and formatting of
     * the input content object.
     */
    private var preInitFunction: (suspend (content: MultimodalContent, harness: PumpStation) -> MultimodalContent)? = null

    /**
     * Pre-validation DITL call. Follow TPipe standard pre-validation pattern. Allows context to be adjusted
     * prior to the llm call. This will be called prior to the judge agent.
     */
    private var preValidationJudgeFunction: (suspend (content: MultimodalContent, miniBank: MiniBank, harness: PumpStation) -> MiniBank)? = null

    /**
     * Execution function ran immediately after the judge agent exits. Allows the developer to intercept and fetch output,
     * or other data to control, update context and history, or alter the behavior of the harness in response to
     * the output of the judge agent.
     */
    private var postJudgeFunction: (suspend (content: MultimodalContent, harness: PumpStation) -> MultimodalContent)? = null

    /**
     * Pre-validation DITL call for the dispatch agent. Invoked prior to running the dispatch agent. Works the same way
     * as the other pre-validation function in PumpStation.
     */
    private var preValidationDispatchFunction: (suspend (content: MultimodalContent, context: ContextWindow, miniBank: MiniBank, harness: PumpStation) -> MiniBank)? = null

    /**
     * DITL function invoked just prior to the judge agent. Allows the developer to decide to shut down and end the
     * PumpStation harness loop based on logic.
     */
    private var preInvokeFunction: (suspend (turnState: ContextWindow, miniBank: MiniBank, harness: PumpStation) -> Boolean)? = null

    /**
     * DITL agent that is invoked to check path safety. When a path call is registered as medium risk or above. This agent
     * will be called if valid to check if the path is valid or needs intervention.
     */
    private var pathSafetyAgent: P2PInterface? = null

    /**
     * DITL function invoked when a path request is made to a path listed as high risk [PathRiskLevel] or if a
     * DITL agent that fired at medium risk found issue with the path request attempt, and fired an interrupt or
     * terminate pipeline flag inside the [MultimodalContent] object.
     *
     * Allows the developer to gracefully handle seeking human input, or any other intervention step required to
     * keep the task safe, following governance rules, and on track.
     */
    private var pathSafetyFunction: (suspend (targetPath: PathObject, schemaIn: String, harness: PumpStation) -> Boolean)? = null

    /**
     * Whether the path-safety agent's output is expected to follow the JSON
     * contract documented in [DEFAULT_JUDGE_PROMPT] style (a JSON object with a
     * `safe` boolean field). When true (the default), the harness parses the
     * agent's text via [parsePathSafetyVerdict] and falls back to a
     * MultimodalContent flag check if parsing returns null. When false, the
     * JSON parser is skipped entirely and the safety verdict comes solely from
     * the flag check on the agent's MultimodalContent.
     *
     * Set to false via [setPathSafetyJsonContractEnabled] when wiring in a custom
     * safety agent that drives the verdict with flags rather than JSON.
     */
    internal var pathSafetyExpectsJsonContract: Boolean = true

    /**
     * DITL function invoked after the dispatch agent has generated its path output.
     */
    private var postGenerateFunction: (suspend (content: MultimodalContent, harness: PumpStation) -> P2PInterface)? = null

    /**
     * DITL function invoked after the path has fully executed. Result is stored in the content object.
     * If false, an error will occur, and we'll try to recover with a branch failure. Otherwise, stop the harness
     * in an error state.
     */
    private var pathValidationFunction: (suspend (content: MultimodalContent, harness: PumpStation) -> Boolean)? = null

    /**
     * DITL function to allow for content transformation after a path has executed, and just before the results of the path
     * are injected into the harness history.
     */
    private var pathTransformationFunction: (suspend (content: MultimodalContent, harness: PumpStation) -> MultimodalContent)? = null

    /**
     * DITL function that executes after memory agents complete a memory update task.
     */
    private var postMemoryFunction: (suspend (content: MultimodalContent, harness: PumpStation) -> MultimodalContent)? = null

    /**
     * DITL function that fires when a memory blowout has been detected. Commonly caused by an unmanaged path that did not
     * catch this internally. Allows the developer to intervene before compaction triggers.
     */
    private var preCompactionFunction: (suspend (content: MultimodalContent, overflowTurn: ConverseData, currentHistory: ConverseHistory, harness: PumpStation) -> MultimodalContent)? = null

    /**
     * DITL function that fires after a TPipe emergency compaction/memory event happens.
     */
    private var postCompactionFunction: (suspend (content: MultimodalContent, newHistory: ConverseHistory, harness: PumpStation) -> MultimodalContent)? = null

    /**
     * Fires anytime an agent has an internal context truncation due to token budgeting. Allows for direct intervention
     * to repair things like the conversation history, and handle possible loss of user instructions or other required
     * data in the history itself. If not bound, a default function will be at harness startup time.
     */
    private var onContextTruncated: (suspend (wasTruncated: Boolean, remainingFreeSpace: Int) -> Unit)? = null

    /**
     * P2PInterface required init function. Initializes the PumpStation harness.
     * Present to satisfy the [P2PInterface] contract.
     * Actual initialization is performed by the full [P2PInitInternal] method.
     */
    override suspend fun P2PInit()
    {
        P2PInitInternal()
    }

    /**
     * Internal P2PInit that does the full initialization. Named distinctly to avoid
     * shadowing the P2PInterface method.
     */
    private suspend fun P2PInitInternal()
    {
        if (harnessIsReady) return

        // Generate run ID
        val runId = generateRunId()
        taskState.runId = runId
        taskState.status = PumpStationStatus.Running

        // Reset kill-switch token accumulator. The enforcement call needs a clean baseline so
        // the checkKillSwitch running total reflects only this run's token usage.
        runStartElapsedMs = System.currentTimeMillis()
        accumulatedInputTokens = 0
        accumulatedOutputTokens = 0

        // Validate dispatch agent is a Pipeline (hard constraint per design)
        require(dispatchAgent is Pipeline) {
            "PumpStation.init() failed: dispatchAgent must be a Pipeline. " +
            "Agents requiring PathRequest schema output must use Pipeline."
        }

        // Store reference to dispatcher pipeline for P2P hook injection
        dispatcherPipeline = dispatchAgent

        // Auto-configure memory mode if not explicitly set
        if (memoryManagementMode == PumpStationMemoryManagementMode.Compaction &&
            (lorebookAgent != null || summaryAgent != null)) {
            memoryManagementMode = PumpStationMemoryManagementMode.Hybrid
        }

        // Initialize all paths and build path descriptors
        pathDescriptors.paths.clear()
        for ((_, path) in pathList)
        {
            val desc = path.init()
            pathDescriptors.paths.add(desc)
        }
        for ((_, path) in reservePaths)
        {
            val desc = path.init()
            pathDescriptors.paths.add(desc)
        }

        // Bind parent interface to all agents
        judgeAgent?.setParentInterface(this)
        dispatchAgent?.setParentInterface(this)
        interventionAgent?.setParentInterface(this)
        lorebookAgent?.setParentInterface(this)
        summaryAgent?.setParentInterface(this)
        goalAgent?.setParentInterface(this)
        preInitAgent?.setParentInterface(this)
        pathSafetyAgent?.setParentInterface(this)
        healthAgent?.setParentInterface(this)
        for (slot in additionalHarnessAgentSlots)
        {
            slot.agent?.setParentInterface(this)
            slot.builderFunction?.let { fn ->
                val agent = fn(this)
                agent.setParentInterface(this)
                additionalHarnessAgentSlots[additionalHarnessAgentSlots.indexOf(slot)] = slot.copy(agent = agent)
            }
        }

        // Assign per-agent converse roles. Authoritative agents (judge,
        // dispatch, intervention, goal, path-safety, health, preInit)
        // gate the harness flow and get [ConverseRole.supervisor] so the
        // LLM API and downstream tooling can distinguish their turns
        // from worker-pipe turns. Memory workers (lorebook, summary)
        // keep the default [ConverseRole.agent] — they maintain state
        // but do not gate flow.
        judgeAgent?.setConverseRoleRecursive(ConverseRole.supervisor)
        dispatchAgent?.setConverseRoleRecursive(ConverseRole.supervisor)
        interventionAgent?.setConverseRoleRecursive(ConverseRole.supervisor)
        goalAgent?.setConverseRoleRecursive(ConverseRole.supervisor)
        pathSafetyAgent?.setConverseRoleRecursive(ConverseRole.supervisor)
        healthAgent?.setConverseRoleRecursive(ConverseRole.supervisor)
        preInitAgent?.setConverseRoleRecursive(ConverseRole.supervisor)

        // Initialize all agents
        judgeAgent?.P2PInit()
        dispatchAgent?.P2PInit()
        interventionAgent?.P2PInit()
        healthAgent?.P2PInit()
        lorebookAgent?.P2PInit()
        summaryAgent?.P2PInit()
        goalAgent?.P2PInit()
        preInitAgent?.P2PInit()
        pathSafetyAgent?.P2PInit()
        for (slot in additionalHarnessAgentSlots)
        {
            slot.agent?.P2PInit()
        }

        // Propagate settings to all agents
        propagateSettingsToAllAgents()

        harnessIsReady = true

        // Emit HarnessStarted event
        val event = HarnessStarted(
            runId = runId,
            turnIndex = 0,
            phase = PumpStationPhase.PreInit,
            originalInput = taskState.originalInput
        )
        backgroundEventQueue.trySend(event)
    }

    /**
     * Per-turn settings refresh (R.3). Re-propagates token budget and pipe
     * settings to all configured agents so any mid-loop configuration change
     * (e.g. via setTokenBudgetRecursive) is picked up before the next turn.
     */
    internal fun refreshSettingsPropagation()
    {
        propagateSettingsToAllAgents()
    }

    /**
     * Propagates token budget and pipe settings to all agents recursively.
     */
    private fun propagateSettingsToAllAgents()
    {
        val allAgents = buildList {
            judgeAgent?.let { add(it) }
            dispatchAgent?.let { add(it) }
            interventionAgent?.let { add(it) }
            healthAgent?.let { add(it) }
            lorebookAgent?.let { add(it) }
            summaryAgent?.let { add(it) }
            goalAgent?.let { add(it) }
            preInitAgent?.let { add(it) }
            pathSafetyAgent?.let { add(it) }
            for (slot in additionalHarnessAgentSlots)
            {
                slot.agent?.let { add(it) }
            }
        }

        val budget = tokenBudgetSettings
        val settings = pipeSettings
        val traceCfg = traceConfig
        for (agent in allAgents)
        {
            budget?.let { agent.setTokenBudgetRecursive(it) }
            settings?.let { agent.setPipeSettingsRecursively(it) }
            // Propagate tracing to child agent pipelines so each one records its own LLM
            // IO into the global PipeTracer under its own pipelineId. Without this, only
            // the pump station's own runId stream is populated and the per-agent HTML
            // exports come out empty (verified: agent-*.html was 0 bytes until this).
            if (tracingEnabled)
            {
                (agent as? Pipeline)?.enableTracing(traceCfg)
            }
        }
    }

    /**
     * Fetch all paths in this harness, serialize them to be ready for injection into a pipe, and return
     * them as a string.
     */
    override fun getPaths(): String
    {
        val descriptors = getVisiblePathDescriptorsInternal()
        return serialize(descriptors, false)
    }

    override fun getContextWindowFromInterface(): ContextWindow?
    {
        return contextWindow
    }

    override fun getMiniBankFromInterface(): MiniBank?
    {
        return miniBank
    }

//===========================================Infrastructure Methods====================================================

    /**
     * Generates a unique run identifier for this harness execution.
     */
    private fun generateRunId(): String = "ps-${System.currentTimeMillis()}-${(0..9999).random()}"

    /**
     * Returns path descriptors for the dispatch agent. Visible paths = normal paths plus
     * reserve paths whose [revealWhen] predicate returns true (sticky once revealed).
     *
     * This is the internal method called by [getVisiblePathDescriptorsForDispatch] and
     * is used to inject path data into the dispatch agent's pipe when
     * [Pipe.autoInjectPathDataFromPumpStation] is enabled.
     */
    private fun getVisiblePathDescriptorsInternal(): PathDescriptionList
    {
        val result = PathDescriptionList()
        val externalContext = externalContextProvider?.invoke() ?: mutableMapOf()

        // Add normal paths
        for ((_, path) in pathList)
        {
            val desc = PathDescriptionData(
                name = path.pathName,
                description = if (path.dispatchHint.isNotBlank())
                {
                    "${path.pathDescription}\n\nHint: ${path.dispatchHint}"
                }
                else
                {
                    path.pathDescription
                },
                inputSchema = path.pathSchema,
                pcpSchema = path.pcpSchema,
                hasInternalAgent = path.isInternalAgentSet,
                hasExecutionFunction = path.isExecutionFunctionSet,
                isRunsInBackground = path.isRunsInBackground,
                agentTypeName = null
            )
            result.paths.add(desc)
        }

        // Add revealed reserve paths
        for ((_, path) in reservePaths)
        {
            val shouldReveal = path.revealWhen.invoke(taskState, externalContext)
            if (shouldReveal)
            {
                val firstReveal = pathKey(path.pathName) !in revealedReservePaths
                revealedReservePaths.add(pathKey(path.pathName))
                if(firstReveal)
                {
                    emitEvent(ReservePathRevealed(
                        runId = taskState.runId,
                        turnIndex = taskState.turnIndex,
                        pathName = path.pathName,
                        reservePathNames = reservePaths.values.map { it.pathName }
                    ))
                }
            }
            if (revealedReservePaths.contains(pathKey(path.pathName)))
            {
                val desc = PathDescriptionData(
                    name = path.pathName,
                    description = if (path.dispatchHint.isNotBlank())
                    {
                        "${path.pathDescription}\n\nHint: ${path.dispatchHint}"
                    }
                    else
                    {
                        path.pathDescription
                    },
                    inputSchema = path.pathSchema,
                    pcpSchema = path.pcpSchema,
                    hasInternalAgent = path.isInternalAgentSet,
                    hasExecutionFunction = path.isExecutionFunctionSet,
                    isRunsInBackground = path.isRunsInBackground,
                    agentTypeName = null
                )
                result.paths.add(desc)
            }
        }

        return result
    }

    /**
     * Returns path descriptors for dispatch agent injection. Use this from the dispatch
     * agent's pipe to get the current list of visible paths with their descriptions and schemas.
     *
     * When the dispatcher's pipe calls this method, it should inject the resulting
     * [PathDescriptionList] into the system prompt and bind output to [PathRequest].
     */
    fun getVisiblePathDescriptorsForDispatch(): PathDescriptionList = getVisiblePathDescriptorsInternal()

    /**
     * Primary developer entry point. Executes the PumpStation harness on the given input.
     *
     * If the kill switch's auto-enforcement path trips on token limits, the loop catches the
     * [com.TTT.P2P.KillSwitchException], sets the harness state to the
     * [PumpStationError.KillSwitchTripped] failure class, and [runFinalizationPhase] still
     * runs to emit the standard [HarnessFailed] event for downstream observers. After
     * finalization the exception is re-thrown to the caller so the [com.TTT.Pipeline.Manifold]
     * throw semantics are preserved end-to-end.
     */
    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
    {
        if (!harnessIsReady) P2PInit()
        runPreInitPhase(content)
        val trip = runHarnessLoop()
        val result = runFinalizationPhase()
        if (trip != null) throw trip
        return result
    }

    /**
     * Executes a P2P request by wrapping the harness loop with P2P requirements validation.
     * Delegates to [executeLocal] with the request's prompt and emits a [NestedP2PCompleted]
     * event so the visualizer can render the nested call as a sub-entry under the parent
     * path's content panel.
     */
    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
    {
        // Extract input content from request
        val content = request.prompt
        val parentPathName = taskState.currentPathName

        // Execute via harness
        val result = executeLocal(content)

        // Emit a nested P2P completion event so the visualizer can render this call as a
        // sub-entry under the parent path's content panel. The token usage is read from the
        // result's metadata where the harness records it.
        val nestedUsage = agentTokenUsageInternal(this)
        val nestedInput = nestedUsage?.first
        val nestedOutput = nestedUsage?.second?.first
        val nestedTotal = nestedUsage?.second?.second
        backgroundEventQueue.trySend(NestedP2PCompleted(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            pathName = parentPathName,
            agentName = "(nested-p2p)",  // P2PRequest does not carry an agent name
            response = result,
            inputTokens = nestedInput,
            outputTokens = nestedOutput,
            totalTokens = nestedTotal
        ))

        return P2PResponse(
            output = result,
            rejection = null
        )
    }

    /**
     * Sets token budget on this PumpStation, stores it locally, and propagates it
     * recursively to all child agents.
     */
    override fun setTokenBudgetRecursive(budget: TokenBudgetSettings)
    {
        tokenBudgetSettings = budget
        propagateSettingsToAllAgents()
    }

    /**
     * Returns the token budget settings for this PumpStation.
     */
    override fun getTokenBudgetSettings(): TokenBudgetSettings? = tokenBudgetSettings

    /**
     * Sets pipe settings on this PumpStation, stores it locally, and propagates them
     * recursively to all child agents.
     */
    override fun setPipeSettingsRecursively(settings: PipeSettings)
    {
        pipeSettings = settings
        propagateSettingsToAllAgents()
    }

    override fun setStreamingCallbackRecursive(callback: suspend (String) -> Unit)
    {
        judgeAgent?.setStreamingCallbackRecursive(callback)
        dispatchAgent?.setStreamingCallbackRecursive(callback)
        interventionAgent?.setStreamingCallbackRecursive(callback)
        healthAgent?.setStreamingCallbackRecursive(callback)
        lorebookAgent?.setStreamingCallbackRecursive(callback)
        summaryAgent?.setStreamingCallbackRecursive(callback)
        goalAgent?.setStreamingCallbackRecursive(callback)
        preInitAgent?.setStreamingCallbackRecursive(callback)
        pathSafetyAgent?.setStreamingCallbackRecursive(callback)
        for (slot in additionalHarnessAgentSlots)
        {
            slot.agent?.setStreamingCallbackRecursive(callback)
        }
    }

    override fun enableStallDetectorRecursive(
        config: StreamingStallConfig,
        callback: StallCallback?
    )
    {
        judgeAgent?.enableStallDetectorRecursive(config, callback)
        dispatchAgent?.enableStallDetectorRecursive(config, callback)
        interventionAgent?.enableStallDetectorRecursive(config, callback)
        healthAgent?.enableStallDetectorRecursive(config, callback)
        lorebookAgent?.enableStallDetectorRecursive(config, callback)
        summaryAgent?.enableStallDetectorRecursive(config, callback)
        goalAgent?.enableStallDetectorRecursive(config, callback)
        preInitAgent?.enableStallDetectorRecursive(config, callback)
        pathSafetyAgent?.enableStallDetectorRecursive(config, callback)
        for (slot in additionalHarnessAgentSlots)
        {
            slot.agent?.enableStallDetectorRecursive(config, callback)
        }
    }

    /**
     * Returns the current task state for inspection.
     */
    fun getTaskState(): PumpStationTaskState = taskState

    /**
     * Test seam: sets the runId that [getTraceReport] uses to look up the trace stream.
     * Production code derives runId from `generateRunId()` inside `executeLocal()`; tests
     * need to inject a deterministic id so they can pre-populate the trace stream and
     * then assert on the auto-exported file. Internal per the same visibility pattern
     * as `getMaxHistoryForTest` on PipeTracer.
     */
    internal fun setRunIdForTest(runId: String)
    {
        this.taskState.runId = runId
    }

    /**
     * Flag the judge agent to run on the next turn. One-shot: cleared automatically after the
     * judge consumes it. No-op when [getJudgeRunMode] is [PumpStationJudgeRunMode.Always] (the
     * judge already runs every turn).
     *
     * The typical usage pattern is a path the dispatch agent selects whose
     * [PathObject.setExecutionFunction] calls this method when it believes the task is complete,
     * for example:
     *
     *     path("signalDone") {
     *         setExecutionFunction { _, pumpStation, _, _ ->
     *             pumpStation.requestJudgeNextTurn()
     *             MultimodalContent(text = "done")
     *         }
     *     }
     *
     * @return This PumpStation instance for method chaining.
     */
    fun requestJudgeNextTurn(): PumpStation
    {
        taskState.requestJudgeNextTurn = true
        return this
    }

    /**
     * Emits a PumpStation event. Called by inner PathObject to emit events
     * from within path execution without needing backgroundEventQueue visibility.
     */
    private fun emitEvent(event: PumpStationEvent)
    {
        backgroundEventQueue.trySend(event)
        eventObserver?.invoke(event)
        // Mirror to the global PipeTracer when tracing is enabled. The funnel is implemented
        // in PumpStationHelpers.kt and is no-op when tracingEnabled is false, so the cost is a
        // single null check on the hot path.
        tracePumpStationEvent(event)
    }

    /**
     * Internal-facing event emit. Used by sibling loop files
     * (PumpStationLoop.kt) to emit harness events from extension functions.
     */
    internal fun emitEventInternal(event: PumpStationEvent)
    {
        emitEvent(event)
    }

    //=====================================Internal DITL accessors==================================================
    // Internal accessors so PumpStationLoop.kt extension functions can read the
    // private DITL fields. Each accessor is read-only — mutation must go through
    // the public setters to preserve the existing fluent API.

    internal val preInvokeFunctionInternal get() = preInvokeFunction
    internal val preInitFunctionInternal get() = preInitFunction
    internal val preValidationJudgeFunctionInternal get() = preValidationJudgeFunction
    internal val preValidationDispatchFunctionInternal get() = preValidationDispatchFunction
    internal val postJudgeFunctionInternal get() = postJudgeFunction
    internal val postGenerateFunctionInternal get() = postGenerateFunction
    internal val pathValidationFunctionInternal get() = pathValidationFunction
    internal val pathTransformationFunctionInternal get() = pathTransformationFunction
    internal val postMemoryFunctionInternal get() = postMemoryFunction
    internal val preCompactionFunctionInternal get() = preCompactionFunction
    internal val postCompactionFunctionInternal get() = postCompactionFunction
    internal val pathSafetyFunctionInternal get() = pathSafetyFunction
    internal val onContextTruncatedInternal get() = onContextTruncated
    internal val maxRepairPromptTokensInternal get() = maxRepairPromptTokens
    internal val maxBlowoutRecoveriesInternal get() = maxBlowoutRecoveries
    internal val blowoutThresholdInternal get() = blowoutThreshold

    //=====================================Group Q accessors (SafePrune)============================================
    // Internal read-only accessors so PumpStationLoop.kt extension functions (Group Q:
    // safe-prune phase) can read the SafePrune configuration. Mutation goes through
    // the public fluent setters to preserve the existing builder pattern.

    internal val safePruneEnabledInternal get() = safePruneEnabled
    internal val safePruneSizeThresholdInternal get() = safePruneSizeThreshold
    internal val safePruneProtectRecentNInternal get() = safePruneProtectRecentN
    internal val safePruneHashWindowInternal get() = safePruneHashWindow
    internal val safePruneMaxToolArgLengthInternal get() = safePruneMaxToolArgLength
    internal val safePruneEnabledStrategiesInternal get() = safePruneEnabledStrategies
    internal val safePruneStrategyPoliciesInternal get() = safePruneStrategyPolicies
    internal val safePruneStrategyDryRunInternal get() = safePruneStrategyDryRun

    //=====================================Group K accessors========================================================
    // Internal accessors so PumpStationLoop.kt extension functions (Group K: context
    // blowout detection) can read and mutate the private [stash] and [stashManifest]
    // collections. Read access to manifest is also exposed via [getStashManifest].

    internal val stashInternal: MutableMap<String, ConverseData> get() = stash
    internal val stashManifestInternal: MutableList<StashEntry> get() = stashManifest

    //=====================================Group I accessors======================================================
    // Internal accessors so PumpStationLoop.kt extension functions (Group I: health,
    // memory update, compaction phases) can read the private backing fields. Each
    // accessor is read-only — mutation must go through the public setters to
    // preserve the existing fluent API.

    internal val healthAgentInternal get() = healthAgent
    internal val healthAgentBuilderFunctionInternal get() = healthAgentBuilderFunction
    internal val healthAgentTurnIntervalInternal get() = healthAgentTurnInterval
    internal val healthAgentErrorRatioThresholdInternal get() = healthAgentErrorRatioThreshold
    internal val judgeAgentInternal get() = judgeAgent
    internal val dispatchAgentInternal get() = dispatchAgent
    internal val interventionAgentInternal get() = interventionAgent
    internal val goalAgentInternal get() = goalAgent
    internal val pathSafetyAgentInternal get() = pathSafetyAgent
    internal var lastHealthCheckTurnInternal: Int
        get() = lastHealthCheckTurn
        set(value) { lastHealthCheckTurn = value }
    internal val lorebookAgentInternal get() = lorebookAgent
    internal val summaryAgentInternal get() = summaryAgent
    internal val backgroundTurnIntervalInternal get() = backgroundTurnInterval
    internal val maxConcurrentBackgroundAgentsInternal get() = maxConcurrentBackgroundAgents
    internal val asyncJobsScopedToStationInternal get() = asyncJobsScopedToStation
    internal val asyncAgentsAppendToTurnHistoryInternal get() = asyncAgentsAppendToTurnHistory
    internal val asyncPathsAppendToTurnHistoryInternal get() = asyncPathsAppendToTurnHistory
    internal val foregroundTurnIntervalInternal get() = foregroundTurnInterval
    internal val additionalHarnessAgentSlotsInternal get() = additionalHarnessAgentSlots
    internal val compactionThresholdInternal get() = compactionThreshold
    internal val compactionStrategyInternal get() = compactionStrategy
    internal val memoryManagementModeInternal get() = memoryManagementMode

    //=====================================v3: compaction / lorebook accessors====================================
    // Mirrors the existing Group I accessor pattern. Reads and writes to the v3 state
    // live alongside the existing per-turn fields so the orchestrator in PumpStationLoop.kt
    // can keep its call sites short and consistent.

    /** Internal read of the v3 compaction cursor. */
    internal val compactionCursorInternal: CompactionCursor
        get() = compactionCursor

    /**
     * Internal write of the v3 compaction cursor. The setter also propagates the new
     * value into [taskState.compactionCursor] so the snapshot and replay path sees the
     * same state the orchestrator is working with.
     */
    internal var compactionCursorWrite: CompactionCursor
        get() = compactionCursor
        set(value)
        {
            compactionCursor = value
            taskState.compactionCursor = value
        }

    /** Internal read of the v3 lorebook cursor. */
    internal val lorebookCursorInternal: LorebookCursor
        get() = lorebookCursor

    /**
     * Internal write of the v3 lorebook cursor. Mirrors the [compactionCursorWrite] pattern.
     */
    internal var lorebookCursorWrite: LorebookCursor
        get() = lorebookCursor
        set(value)
        {
            lorebookCursor = value
            taskState.lorebookCursor = value
        }

    /** Ring buffer of compaction backups (read-only for orchestrator). */
    internal val compactionBackupsInternal: ArrayDeque<CompactionBackup>
        get() = compactionBackups

    internal val prePruneTransformInternal get() = prePruneTransform
    internal val extraPrePruneTransformsInternal: List<suspend (List<ConverseData>, PumpStation) -> List<ConverseData>>
        get() = extraPrePruneTransforms

    internal val compactionRolledBackFunctionInternal get() = compactionRolledBackFunction
    internal val maxCompactionAttemptsInternal get() = maxCompactionAttempts
    internal val chunkTokenBudgetInternal get() = chunkTokenBudget
    internal val maxChunksInternal get() = maxChunks
    internal val maxParallelChunksInternal get() = maxParallelChunks
    internal val maxCompactionBackupsInternal get() = maxCompactionBackups
    internal val compactionFanoutModeInternal get() = compactionFanoutMode
    internal val hybridWholeHeadroomInternal get() = hybridWholeHeadroom
    internal val memoryUpdateTimeoutMsInternal get() = memoryUpdateTimeoutMs
    internal val consecutivePathCountInternal: Int
        get() = consecutivePathCount
    internal val lastSelectedPathNameInternal get() = lastSelectedPathName
    internal val maxConsecutiveUnknownPathsInternal: Int?
        get() = maxConsecutiveUnknownPaths

    //=====================================Tracing Accessors================================================
    // Internal accessors so PumpStationHelpers.kt and PumpStationLoop.kt extension functions can read
    // the private [tracingEnabled] field when deciding whether to mirror PumpStationEvents into the
    // global PipeTracer. The helper does its own null checks; we only need to expose the flag.

    internal val tracingEnabledInternal get() = tracingEnabled

    //=====================================Group L accessors======================================================
    // Internal accessor for the private [maxGoalFailAttempts] field so that
    // PumpStationLoop.kt extension functions (Group L: exit flow with goal
    // recursion) can compare it against [PumpStationTaskState.goalFailCount].

    internal val maxGoalFailAttemptsInternal get() = maxGoalFailAttempts

    //=====================================Group M accessors========================================================
    // Internal accessors so PumpStationLoop.kt extension functions (Group M: main
    // loop wiring) can read the private [preInitAgent], [eventObserver],
    // [backgroundEventQueue], and [maxTurns] fields. These fields are read
    // by runPreInitPhase/runFinalizationPhase/runHarnessLoop/runTurn and
    // drainBackgroundEventQueue.

    internal val preInitAgentInternal get() = preInitAgent
    internal val eventObserverInternal get() = eventObserver
    internal val backgroundEventQueueInternal get() = backgroundEventQueue
    internal val maxTurnsInternal get() = maxTurns

    //=====================================Group O accessors========================================================
    // Internal accessors so PumpStationLoop.kt extension functions (Group O: prune
    // history + emergency halt) can read the private [maxTurnHistorySize] and
    // [maxRawTurnHistorySize] fields. These are read by pruneTurnHistory and
    // pruneRawTurnHistory.

    internal val maxTurnHistorySizeInternal get() = maxTurnHistorySize
    internal val maxRawTurnHistorySizeInternal get() = maxRawTurnHistorySize

    //=====================================Group O: KillSwitch Accessors==============================================
    /**
     * Internal accessors so [PumpStationLoop.kt] extension functions (Group O: kill switch
     * auto-enforcement) can read the private token-accumulator fields. [addTokenUsage] is
     * also exposed as an internal function so the loop can update the running total.
     *
     * @property runStartElapsedMsInternal Exposes [runStartElapsedMs] to the loop.
     * @property accumulatedInputTokensInternal Exposes [accumulatedInputTokens] to the loop.
     * @property accumulatedOutputTokensInternal Exposes [accumulatedOutputTokens] to the loop.
     */
    internal val runStartElapsedMsInternal get() = runStartElapsedMs
    internal val accumulatedInputTokensInternal get() = accumulatedInputTokens
    internal val accumulatedOutputTokensInternal get() = accumulatedOutputTokens

    //=====================================Group O: Emergency Halt====================================================
    // Trip/force-halt methods so the PumpStationLoop can be safely interrupted
    // by external observers. tripKillSwitch() marks the harness as tripped and
    // also sets taskState so the loop exits on the next checkPauseGuards() call.
    // forceHalt() additionally wakes any suspended loop via notifyResume() and
    // emits a HarnessFailed event on the background event queue.

    /**
     * Trip the kill switch. The harness will halt on the next checkPauseGuards() call.
     * If a [KillSwitch] is attached, invokes its trip callback. Always also sets
     * [PumpStationTaskState.exitReason] and [PumpStationTaskState.lastError] so the
     * loop exits deterministically even when no KillSwitch is configured.
     */
    fun tripKillSwitch()
    {
        killSwitch?.let { ks ->
            // Invoking the onTripped callback would normally throw, but it has type
            // (KillSwitchContext) -> Nothing, so we don't invoke it here. We just
            // record the trip and let the checkPauseGuards halt the loop.
            ks.toString()
        }
        taskState.exitReason = PumpStationExitReason.KillSwitchTripped
        taskState.lastError = PumpStationError.KillSwitchTripped
    }

    /**
     * Force the harness to halt. Use this as an emergency exit from a paused state.
     * Sets [PumpStationTaskState.exitReason], marks the task as [PumpStationStatus.Failed],
     * notifies the suspended loop to wake up and exit, and emits a [HarnessFailed] event
     * on the [backgroundEventQueue].
     */
    suspend fun forceHalt(reason: PumpStationExitReason)
    {
        taskState.exitReason = reason
        taskState.status = PumpStationStatus.Failed
        notifyResume()
        backgroundEventQueueInternal.trySend(HarnessFailed(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            error = PumpStationError.KillSwitchTripped,
            errorMessage = reason.name,
            exitReason = reason
        ))
    }

    /**
     * Internal accessor for the private [invokePath] funnel so that
     * PumpStationLoop.kt extension functions can call it from the runPathFlow
     * phase helper. Preserves the existing private visibility for outside callers.
     */
    internal suspend fun invokePathInternal(path: PathObject, input: MultimodalContent): MultimodalContent
    {
        return invokePath(path, input)
    }

    /**
     * Registers a synchronous observer for every [PumpStationEvent] emitted by the harness.
     * Intended for test observability; the observer is invoked on whichever thread/coroutine
     * called [emitEvent]. Pass null to clear.
     *
     * @param observer Callback invoked with each event, or null to clear the observer.
     * @return This [PumpStation] for method chaining.
     */
    fun setEventObserver(observer: ((PumpStationEvent) -> Unit)?): PumpStation
    {
        this.eventObserver = observer
        return this
    }
    /**
     * Registers a richer [externalContextProvider] that receives the current
     * [PumpStationTaskState] when invoked. The runtime stores it as a no-arg
     * supplier; the wrapper captures [taskState] at call time.
     *
     * Use this when you need access to the harness task state to make
     * context decisions (e.g. reading path call counts, the active reserve
     * path set, or the latest turn index). The simpler no-arg
     * [externalContextProvider] setter is preserved for direct callers.
     *
     * @param provider The external context provider, or null to clear.
     * @return This [PumpStation] for method chaining.
     */
    fun setExternalContextProvider(provider: ((PumpStationTaskState) -> MutableMap<String, Any>)?): PumpStation
    {
        this.externalContextProvider = if(provider == null) null else { -> provider.invoke(this.getTaskState()) }
        return this
    }


    /**
     * Enables tracing for this PumpStation with the specified configuration. When enabled, every
     * [PumpStationEvent] emitted by the loop is mirrored into the global [PipeTracer] so the trace
     * can be exported via [getTraceReport] and visualized by [com.TTT.Debug.TraceVisualizer].
     *
     * The PumpStation trace is keyed by [taskState.runId] (generated in [P2PInit]). Calling
     * [enableTracing] before [executeLocal] ensures the trace stream is created at the start of
     * the first run.
     *
     * @param config The tracing configuration to use.
     * @return This PumpStation for method chaining.
     */
    fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)): PumpStation
    {
        this.tracingEnabled = true
        this.traceConfig = config
        PipeTracer.enable()
        PipeTracer.setMaxHistory(config.maxHistory)
        return this
    }

    /**
     * Returns the trace report for this PumpStation in the specified format. The report is rendered
     * by [com.TTT.Debug.TraceVisualizer] with the custom turn-centric layout when the trace contains
     * PUMP_STATION_* events.
     *
     * @param format The output format. Defaults to [traceConfig.outputFormat].
     * @return The formatted trace report as a string, or an explanatory message if tracing was not
     *         enabled before the run.
     */
    fun getTraceReport(format: TraceFormat = traceConfig.outputFormat): String
    {
        val traceId = taskState.runId.takeIf { it.isNotBlank() }
            ?: return "(PumpStation trace unavailable: harness has not been started. Call executeLocal() first.)"

        return try
        {
            val report = PipeTracer.exportTrace(traceId, format)

            if(traceConfig.autoExport)
            {
                val extension = when(format)
                {
                    TraceFormat.HTML -> "html"
                    TraceFormat.JSON -> "json"
                    TraceFormat.MARKDOWN -> "md"
                    TraceFormat.CONSOLE -> "txt"
                }
                val filename = "pumpstation-${traceId.take(12)}.$extension"
                val exportPath = traceConfig.exportPath.trimEnd('/') + "/" + filename
                TraceAutoExporter.default.export(exportPath, report) {
                    writeStringToFile(exportPath, report)
                }
            }

            report
        }
        catch (e: Exception)
        {
            "(PumpStation trace export failed: ${e.message})"
        }
    }

    /**
     * Returns a [FailureAnalysis] for this PumpStation if tracing is enabled.
     *
     * @return A FailureAnalysis object or null if tracing is disabled or the harness has not run.
     */
    fun getFailureAnalysis(): FailureAnalysis?
    {
        if(!tracingEnabled) return null
        val traceId = taskState.runId.takeIf { it.isNotBlank() } ?: return null
        return PipeTracer.getFailureAnalysis(traceId)
    }

    /**
     * Returns the trace ID for this PumpStation, or null if the harness has not been started.
     * The trace ID is the [taskState.runId] generated during [P2PInit].
     */
    fun getTraceId(): String? = taskState.runId.takeIf { it.isNotBlank() }

    /**
     * Returns the current stash manifest (rich metadata about stashed content)
     * without loading the full content.
     */
    fun getStashManifest(): List<StashEntry> = stashManifest.toList()

    /**
     * Retrieves a stashed ConverseData entry by its stash ID.
     * Returns null if no entry exists with that ID.
     */
    fun retrieveStash(stashId: String): ConverseData? = stash[stashId]

    /**
     * Returns a path by name, searching both normal and reserve paths.
     * Lookup is case-insensitive per the contract documented on [pathList].
     */
    fun getPath(name: String): PathObject? = pathList[pathKey(name)] ?: reservePaths[pathKey(name)]

    /**
     * Adds a path to the normal path list (not reserve). The path's parent is set to this station,
     * and the station's current [killSwitch] (if any) is propagated to the new path so per-path
     * enforcement stays in sync without requiring the caller to re-assign the switch.
     */
    fun addPath(path: PathObject)
    {
        path.setParentInterface(this)
        path.killSwitch = _killSwitch
        pathList[pathKey(path.pathName)] = path
    }

    /**
     * Removes a path from the normal path list by name. Lookup is case-insensitive.
     */
    fun removePath(name: String)
    {
        pathList.remove(pathKey(name))
    }

    /**
     * Moves a path from the normal path list to reserve, making it invisible to dispatch
     * until explicitly revealed or the harness resets. Lookup is case-insensitive.
     */
    private fun movePathToReserve(name: String)
    {
        val path = pathList.remove(pathKey(name)) ?: return
        path.revealWhen = { _, _ -> false }
        reservePaths[pathKey(name)] = path
    }

    /**
     * Returns names of all currently visible paths (normal paths + revealed reserve paths).
     * Names preserve the original casing of [PathObject.pathName] for each path so the
     * LLM-facing menu matches the casing shown in the path descriptors block.
     */
    fun getVisiblePathNames(): List<String>
    {
        val names = pathList.values.map { it.pathName }.toMutableList()
        for (key in revealedReservePaths)
        {
            reservePaths[key]?.pathName?.let { names.add(it) }
        }
        return names
    }

    /**
     * Returns names of all reserve paths (whether revealed or not).
     */
    fun getReservePathNames(): List<String> = reservePaths.values.map { it.pathName }

    /**
     * Saves a snapshot of the current harness state at a high-risk boundary
     * for rollback, resume, fork, or debugging.
     */
    fun saveSnapshot(): PumpStationSnapshot
    {
        return PumpStationSnapshot(
            taskState = taskState,
            turnHistory = turnHistory,
            rawTurnHistory = rawTurnHistory,
            turnSummary = turnSummary,
            contextWindow = contextWindow,
            miniBank = miniBank,
            stashManifest = stashManifest.toList(),
            visiblePathNames = getVisiblePathNames(),
            reservePathNames = getReservePathNames()
        )
    }

    /**
     * Restores the harness to a previously captured snapshot state (in-place).
     * After restore, [harnessIsReady] is set to true.
     */
    suspend fun restoreSnapshot(snapshot: PumpStationSnapshot)
    {
        taskState.status = snapshot.taskState.status
        taskState.phase = snapshot.taskState.phase
        taskState.turnIndex = snapshot.taskState.turnIndex
        taskState.originalInput = snapshot.taskState.originalInput
        taskState.latestContent = snapshot.taskState.latestContent
        taskState.selectedPathName = snapshot.taskState.selectedPathName
        taskState.lastPathResult = snapshot.taskState.lastPathResult
        taskState.lastError = snapshot.taskState.lastError
        taskState.exitReason = snapshot.taskState.exitReason
        taskState.memoryActionResult = snapshot.taskState.memoryActionResult
        taskState.isPaused = snapshot.taskState.isPaused
        taskState.pausedAt = snapshot.taskState.pausedAt
        taskState.pauseReason = snapshot.taskState.pauseReason
        harnessIsReady = true

        // Emit HarnessResumed event
        backgroundEventQueue.trySend(HarnessResumed(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            phase = taskState.phase
        ))
    }

    //=====================================Interrupt Snapshot Helpers==================================================

    /**
     * Capture a snapshot of the harness state for later rewind on interrupt.
     * Called at the top of every [runTurn] and stashed for the duration of the
     * turn. The snapshot covers the fields that an in-flight turn's work can
     * mutate — turnHistory and the four [taskState] fields listed below. Other
     * [taskState] fields (status, phase, lastError, exitReason, etc.) are not
     * affected by a turn's in-flight work and are NOT in the snapshot; the
     * rewind preserves them.
     */
    internal fun takeInterruptSnapshot(): PumpStationInterruptSnapshot
    {
        return PumpStationInterruptSnapshot(
            turnIndex = taskState.turnIndex,
            latestContent = taskState.latestContent,
            lastPathResult = taskState.lastPathResult,
            selectedPathName = taskState.selectedPathName,
            originalInput = taskState.originalInput,
            turnHistory = turnHistory.history
        )
    }

    /**
     * Restore the harness state from [snapshot]. Called from the
     * [PumpStationInterruptException] catch handler at the top of [runTurn].
     * Replaces the four [taskState] fields and turnHistory contents with the
     * snapshot's stored values. Does NOT touch other [taskState] fields.
     */
    internal fun restoreFromInterruptSnapshot(snapshot: PumpStationInterruptSnapshot)
    {
        taskState.turnIndex = snapshot.turnIndex
        taskState.latestContent = snapshot.latestContent
        taskState.lastPathResult = snapshot.lastPathResult
        taskState.selectedPathName = snapshot.selectedPathName
        taskState.originalInput = snapshot.originalInput
        turnHistory.history.clear()
        turnHistory.history.addAll(snapshot.turnHistoryCopy)
    }

    /**
     * Pauses the harness at the specified phase boundaries for external inspection
     * or intervention. Calling this replaces any previously set pause phases.
     */
    fun pauseAt(vararg phases: PumpStationPausePhase)
    {
        pausePhases = phases.toSet()
        taskState.isPaused = true
        taskState.pausedAt = pausePhases
    }

    /**
     * Resumes the harness from a paused state, clearing all pause phases.
     */
    suspend fun resume()
    {
        taskState.isPaused = false
        taskState.pausedAt = emptySet()
        taskState.pauseReason = null
        pausePhases = emptySet()

        // Emit HarnessResumed event
        backgroundEventQueue.trySend(HarnessResumed(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            phase = taskState.phase
        ))

        // Wake up any suspended checkPauseGuards call in the loop.
        notifyResume()
    }

    /**
     * Invokes a path through the internal funnel. This is the single entry point for
     * all path execution, handling risk checks, DITL hooks, schema validation, PCP,
     * execution functions, agents, background dispatch, loop guards, stash, validation,
     * transformation, history updates, and event emission.
     *
     * @param path The [PathObject] to invoke
     * @param input The [MultimodalContent] input to the path
     * @return [MultimodalContent] result from path execution
     */
    /**
     * Path-safety gate. Returns true if the path is approved to run, false if
     * it should be rejected. The result is computed from (in priority order):
     *   1. The custom [pathSafetyFunction] if set, OR
     *   2. The [pathSafetyAgent] if set, with the verdict extracted via
     *      [parsePathSafetyVerdict] when [pathSafetyExpectsJsonContract] is true,
     *      and from MultimodalContent flags (terminatePipeline / passPipeline)
     *      when it is false, OR
     *   3. true (default approve) when neither function nor agent is set.
     *
     * The safety gate runs for any path with [PathRiskLevel] greater than Low
     * (i.e. Medium or High). Extracted from [invokePathInternal] as a separate
     * method so the gating behavior is testable in isolation.
     */
    suspend fun checkPathSafety(path: PathObject, input: MultimodalContent): Boolean
    {
        pathSafetyFunction?.let { fn ->
            // Custom function users don't carry a reason string — the hint will
            // use the fallback wording. Clear any stale value.
            pathSafetyLastVerdict = null
            return fn(path, path.pathSchema, this)
        }
        val agent = pathSafetyAgent ?: return true
        val result = agent.executeLocal(input)
        val parsed = if (pathSafetyExpectsJsonContract) parsePathSafetyVerdict(result.text) else null
        // Capture the parsed verdict (including reason) so the hint code at the
        // call site can surface the actual rejection reason to the dispatch LLM.
        pathSafetyLastVerdict = parsed
        return when
        {
            parsed != null                    -> parsed.approved
            pathSafetyExpectsJsonContract     -> path.riskLevel == PathRiskLevel.Low
            else                              -> !(result.terminatePipeline || result.passPipeline)
        }
    }

    /**
     * The verdict returned by the most recent [checkPathSafety] call when the
     * path-safety agent was used. Null when (a) the custom [pathSafetyFunction]
     * was used (no reason string is available), or (b) the JSON contract parse
     * returned null and the legacy flag check was the source of the verdict, or
     * (c) the path was approved (no rejection to report).
     *
     * Consumed by [com.TTT.Pipeline.invokePath] at the rejection site to build
     * the [Path Safety] hint appended to turnHistory.
     */
    internal var pathSafetyLastVerdict: PathSafetyVerdict? = null

    private suspend fun invokePath(path: PathObject, input: MultimodalContent): MultimodalContent
    {
        val pathName = path.pathName
        val riskLevel = path.riskLevel

        // Emit PathSelected event
        emitEventInternal(PathSelected(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            phase = PumpStationPhase.Dispatch,
            pathName = pathName,
            riskLevel = riskLevel
        ))

        // --- Risk check (runs FIRST so safety-rejected paths return input
        // before the loop guards increment their counters) ---
        if (riskLevel != PathRiskLevel.Low)
        {
            // Emit PathSafetyStarted event
            emitEventInternal(PathSafetyStarted(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                pathName = pathName,
                riskLevel = riskLevel
            ))

            // Call path safety function or agent — function OR agent, first to return true approves.
            //
            // The agent's verdict is parsed as a structured `{"safe": bool, ...}` JSON object
            // (see [parsePathSafetyVerdict]). The legacy flag-based check
            // (`!result.terminatePipeline && !result.passPipeline`) is kept as a fallback
            // so custom agents that don't follow the JSON convention still work — but a
            // real path-safety LLM that returns `{"safe": false}` is now actually consulted.
            // Previously the flag check was the only gate, which made the safety check a
            // degenerate always-approve (LLMs don't normally set terminatePipeline on a
            // safety verdict response).
            // Phase boundary: BeforePathSafety — drain steering before the path-safety
            // gate runs. Persistent overlays / one-shot instructions for this phase are
            // appended to turnHistory so they are visible to the safety LLM (when one is
            // configured) and to downstream observers. The drain is non-blocking and
            // does not influence the safety verdict.
            injectSteeringForPhase(PumpStationPausePhase.BeforePathSafety)
            val approved = checkPathSafety(path, input)
            // Capture the rejection reason from the safety verdict (when the agent
            // path was used). pathSafetyFunction users don't carry a reason —
            // the hint will fall back to "Rejected by path safety check".
            val safetyReason: String? = if (!approved) pathSafetyLastVerdict?.reason else null

            emitEventInternal(PathSafetyCompleted(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                pathName = pathName,
                riskLevel = riskLevel,
                approved = approved,
                reason = if (!approved) (safetyReason ?: "Rejected by path safety check") else null
            ))

            if (!approved)
            {
                /*
                 * Surface the rejection in the next dispatch LLM's user prompt so
                 * dispatch can pick a different path. Symmetric with the
                 * empty-pathName hint at PumpStationLoop.kt:378-389 and the
                 * empty-rationale hint at PumpStationLoop.kt:2848-2854.
                 *
                 * `return input` here short-circuits the path before the
                 * loop-guard counters below increment, so the safety-rejected
                 * path does NOT trip maxConsecutiveSamePath or
                 * maxTotalPathCallsPerPath. See
                 * [com.TTT.Pipeline.PumpStationLoopGuardSafetyOrderingTest].
                 *
                 * Dedup the hint by pathName so a path that the safety gate
                 * rejects every turn doesn't accumulate one [Path Safety]
                 * entry per turn. We only append when no earlier turnHistory
                 * entry has already mentioned the same pathName.
                 */
                val hintMarker = "[Path Safety] Path '$pathName'"
                val alreadyNudged = turnHistory.history.any { turn ->
                    turn.content.text?.contains(hintMarker) == true
                }
                if (!alreadyNudged)
                {
                    val hintMessage = hintMarker + " was rejected by the path-safety gate" +
                        (if (safetyReason.isNullOrBlank()) "." else " for: $safetyReason.") +
                        " Select a different path from the visible list on your next dispatch."
                    turnHistory.add(
                        ConverseData(
                            role = ConverseRole.harness,
                            content = MultimodalContent(text = hintMessage)
                        )
                    )
                }
                return input
            }
        }

        // --- Loop guard checks (run AFTER the risk/safety gate so the
        // return-input short-circuit at the rejection site prevents these
        // counters from incrementing on safety-rejected paths) ---
        if (maxConsecutiveSamePath != null)
        {
            if (pathName == lastSelectedPathName)
            {
                consecutivePathCount++
                if (consecutivePathCount >= maxConsecutiveSamePath!!)
                {
                    /*
                     * Loop-guard trips halt the harness. Set the exit reason,
                     * mark the path output terminatePipeline, and surface the
                     * failure to the trace — repeated dispatch of the same
                     * path is a hard ceiling that the harness must not paper
                     * over by re-running the intervention agent.
                     */
                    emitEventInternal(LoopGuardTripped(
                        runId = taskState.runId,
                        turnIndex = taskState.turnIndex,
                        guard = "maxConsecutiveSamePath",
                        pathName = pathName,
                        detail = "consecutive=$consecutivePathCount, limit=${maxConsecutiveSamePath!!}",
                        metric = "consecutive",
                        observed = consecutivePathCount,
                        limit = maxConsecutiveSamePath!!
                    ))
                    emitEventInternal(PathFailed(
                        runId = taskState.runId,
                        turnIndex = taskState.turnIndex,
                        phase = PumpStationPhase.PathExecution,
                        pathName = pathName,
                        riskLevel = riskLevel,
                        error = PumpStationError.LoopGuardTriggered,
                        errorMessage = "maxConsecutiveSamePath exceeded for path '${pathName}'"
                    ))
                    taskState.latestContent = (taskState.latestContent ?: MultimodalContent())
                        .also { it.terminatePipeline = true }
                    taskState.lastError = PumpStationError.LoopGuardTriggered
                    taskState.exitReason = PumpStationExitReason.LoopGuardTripped
                    consecutivePathCount = 0
                    return input
                }
            }
            else
            {
                consecutivePathCount = 1
                lastSelectedPathName = pathName
            }
        }

        // Increment path call count for per-path limit
        val callCount = pathCallCounts.getOrDefault(pathName, 0) + 1
        pathCallCounts[pathName] = callCount
        if (maxTotalPathCallsPerPath != null && callCount > maxTotalPathCallsPerPath!!)
        {
            emitEventInternal(LoopGuardTripped(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                guard = "maxTotalPathCallsPerPath",
                pathName = pathName,
                detail = "count=$callCount, limit=${maxTotalPathCallsPerPath!!}",
                metric = "totalCount",
                observed = callCount,
                limit = maxTotalPathCallsPerPath!!
            ))
            val limitResult = pathLimitExceededFunction?.invoke(
                path,
                "maxTotalPathCallsPerPath exceeded",
                this
            ) ?: PathLimitExceededResult(
                action = pathLimitExceededPolicy,
                reason = "Using static policy"
            )

            when (limitResult.action)
            {
                PathLimitExceededPolicy.Skip ->
                {
                    emitEventInternal(PathHidden(
                        runId = taskState.runId,
                        turnIndex = taskState.turnIndex,
                        pathName = pathName,
                        reason = limitResult.reason.ifEmpty { "maxTotalPathCallsPerPath exceeded" }
                    ))
                    movePathToReserve(pathName)
                }
                PathLimitExceededPolicy.Halt ->
                {
                    taskState.latestContent?.terminatePipeline = true
                    taskState.lastError = PumpStationError.MaxTurnsExceeded
                    emitEventInternal(PathFailed(
                        runId = taskState.runId,
                        turnIndex = taskState.turnIndex,
                        phase = PumpStationPhase.PathExecution,
                        pathName = pathName,
                        riskLevel = riskLevel,
                        error = PumpStationError.MaxTurnsExceeded,
                        errorMessage = limitResult.reason.ifEmpty { "maxTotalPathCallsPerPath exceeded, harness halting" }
                    ))
                    return input
                }
                PathLimitExceededPolicy.Continue ->
                {
                    emitEventInternal(PathFailed(
                        runId = taskState.runId,
                        turnIndex = taskState.turnIndex,
                        phase = PumpStationPhase.PathExecution,
                        pathName = pathName,
                        riskLevel = riskLevel,
                        error = PumpStationError.MaxTurnsExceeded,
                        errorMessage = limitResult.reason.ifEmpty { "maxTotalPathCallsPerPath exceeded but continuing" }
                    ))
                }
            }
        }

        // --- Emit PathStarted event ---
        emitEventInternal(PathStarted(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            pathName = pathName,
            riskLevel = riskLevel
        ))

        // --- Execute the path ---
        //
        // Set taskState.currentPathName so nested P2P calls inside the path (via executeP2PRequest)
        // can annotate themselves with the parent path. Cleared in the finally block to avoid
        // leaking the path name into subsequent operations.
        val priorPathName = taskState.currentPathName
        taskState.currentPathName = pathName
        val result = try
        {
            path.execute(input, this, turnHistory, turnSummary)
        }
        catch(e: Exception)
        {
            // Timeouts are path-level, not harness-level, failures: skip
            // lastError so the loop continues instead of breaking into
            // runFinalizationPhase on the first transport timeout.
            val errorCode = classifyPathException(e)
            emitEventInternal(PathFailed(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                pathName = pathName,
                riskLevel = riskLevel,
                error = errorCode,
                errorMessage = e.message
            ))
            if (errorCode != PumpStationError.PathTimeout)
            {
                taskState.lastError = PumpStationError.PathExecutionException
            }
            taskState.currentPathName = priorPathName
            return input
        }
        finally
        {
            // Restore the prior path name even on success — the next operation should not see
            // this path's name as the ambient context.
            taskState.currentPathName = priorPathName
        }

        // --- Emit PathCompleted event ---
        // Read the path's token usage. The legacy-fields helper is preferred over the
        // comprehensive-tracking TokenUsage because the base Pipe.countTokens call always
        // populates the legacy fields on every execution, whereas the comprehensive totals
        // require the pipe to opt in. See PathObject.getPathLegacyTokenUsage for the full
        // rationale. The PathCompleted event mirrors the same numbers back to listeners.
        val (pathInputTokens, pathOutputTokens) = path.getPathLegacyTokenUsage()
        val pathTotalTokens = if (pathInputTokens > 0 || pathOutputTokens > 0)
            pathInputTokens + pathOutputTokens else 0
        emitEventInternal(PathCompleted(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            phase = PumpStationPhase.PathExecution,
            pathName = pathName,
            riskLevel = riskLevel,
            result = result,
            inputTokens = pathInputTokens.takeIf { it > 0 },
            outputTokens = pathOutputTokens.takeIf { it > 0 },
            totalTokens = pathTotalTokens.takeIf { it > 0 }
        ))

        // Per-path kill switch enforcement. The path's own killSwitch is checked against the
        // path's own token usage (not the station's accumulated total). A path can carry a
        // stricter limit than the station, and the propagation from PumpStation.addPath /
        // PumpStation.killSwitch setter ensures the slot is populated. The default
        // KillSwitch.onTripped throws KillSwitchException which propagates up through
        // runHarnessLoop's catch and transitions the run to a PumpStationError.KillSwitchTripped
        // failure state.
        if (pathInputTokens > 0 || pathOutputTokens > 0)
        {
            // Station accumulator: per-path tokens also count toward the station total so the
            // station-level check can trip on cumulative usage as well.
            addTokenUsage(pathInputTokens, pathOutputTokens)
            path.checkKillSwitch(pathInputTokens, pathOutputTokens, runStartElapsedMs)
        }

        taskState.lastPathResult = result
        taskState.latestContent = result

        // --- Path validation ---
        if (pathValidationFunction != null)
        {
            val validated = pathValidationFunction!!.invoke(result, this)
            emitEventInternal(PathValidationCompleted(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                pathName = pathName,
                approved = validated,
                reason = if (!validated) "Rejected by pathValidationFunction" else null
            ))
            if (!validated) return input
        }

        // --- Path transformation ---
        val transformed = pathTransformationFunction?.invoke(result, this) ?: result

        // --- Update turn history ---
        val resultContent = MultimodalContent()
        resultContent.addText(transformed.toString())
        val turnEntry = ConverseData(role = ConverseRole.assistant, content = resultContent)
        turnHistory.add(turnEntry)
        rawTurnHistory.add(turnEntry)

        return transformed
    }

    /**
     * Returns the list of dispatcher rules configured on this station.
     */
    fun getDispatcherRules(): List<DispatcherRule> = dispatcherRules.toList()

    /**
     * Adds a dispatcher rule to this station.
     */
    fun addDispatcherRule(rule: DispatcherRule)
    {
        dispatcherRules.add(rule)
    }


//=====================================Fluent Setters================================================================

//---------------------------------------------Agent Setters--------------------------------------------------------

    /**
     * Sets the judge agent for this PumpStation. The judge agent evaluates whether
     * the harness task is complete and can terminate the loop.
     *
     * @param agent The judge pipeline, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setJudgeAgent(agent: Pipeline?): PumpStation
    {
        this.judgeAgent = agent
        if(agent != null) autoInjectDefaultPrompt(agent, customJudgeSystemPrompt, DEFAULT_JUDGE_PROMPT)
        return this
    }

    /**
     * Sets the dispatch agent for this PumpStation. The dispatch agent evaluates
     * what the next step in the harness needs to be and dispatches to the next path.
     *
     * @param agent The dispatch pipeline, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setDispatchAgent(agent: Pipeline?): PumpStation
    {
        this.dispatchAgent = agent
        if(agent != null) autoInjectDefaultPrompt(agent, customDispatchSystemPrompt, DEFAULT_DISPATCH_PROMPT)
        return this
    }

    /**
     * Sets the intervention agent for this PumpStation. Invoked post path execution
     * to provide nudges, hints, and aggressive suggestions to steer dispatch/judge.
     *
     * @param agent The intervention agent, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setInterventionAgent(agent: P2PInterface?): PumpStation
    {
        this.interventionAgent = agent
        return this
    }

    /**
     * Sets the health agent for this PumpStation. The health agent is a proactive
     * monitor that fires before the judge based on interval/error-ratio thresholds.
     *
     * @param agent The health agent, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setHealthAgent(agent: P2PInterface?): PumpStation
    {
        this.healthAgent = agent
        return this
    }

    /**
     * Sets the lorebook agent for this PumpStation. The lorebook agent updates the
     * lorebook of the internal context window/minibank in the background.
     *
     * @param agent The lorebook agent, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setLorebookAgent(agent: P2PInterface?): PumpStation
    {
        this.lorebookAgent = agent
        return this
    }

    /**
     * Sets the summary agent for this PumpStation. The summary agent generates
     * summaries of harness events for compaction and turn history drop-off.
     *
     * @param agent The summary agent, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setSummaryAgent(agent: P2PInterface?): PumpStation
    {
        this.summaryAgent = agent
        return this
    }

    /**
     * Sets the goal agent for this PumpStation. The goal agent scans the work done
     * once the harness is in an exit state and can force work to resume.
     *
     * @param agent The goal agent, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setGoalAgent(agent: P2PInterface?): PumpStation
    {
        this.goalAgent = agent
        return this
    }

    /**
     * Sets the post-success agent. Fires inside [runExitFlow] on every successful exit
     * (broad coverage including the no-goal-agent path). Output becomes the harness's
     * final deliverable on pass; [MultimodalContent.terminatePipeline] halts with
     * [PumpStationExitReason.JudgeComplete] without re-loop.
     *
     * @param agent The post-success agent, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPostGoalAgent(agent: P2PInterface?): PumpStation
    {
        this.postGoalAgent = agent
        return this
    }

    /**
     * Sets the pre-init agent for this PumpStation. The pre-init agent fires prior
     * to starting the harness for any initial setup or state handling.
     *
     * @param agent The pre-init agent, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPreInitAgent(agent: P2PInterface?): PumpStation
    {
        this.preInitAgent = agent
        return this
    }

    /**
     * Sets the path-safety agent for this PumpStation. Invoked to check path safety
     * when a path call is medium risk or above.
     *
     * @param agent The path-safety agent, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPathSafetyAgent(agent: P2PInterface?): PumpStation
    {
        this.pathSafetyAgent = agent
        return this
    }

//---------------------------------------Agent Builder Setters------------------------------------------------------

    /**
     * Sets the judge agent builder function. When non-null, this overrides
     * any value set via [setJudgeAgent].
     *
     * @param fn The builder function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setJudgeAgentBuilderFunction(fn: (suspend (harness: PumpStation) -> Pipeline)?): PumpStation
    {
        this.judgeAgentBuilderFunction = fn
        return this
    }

    /**
     * Sets the dispatch agent builder function. When non-null, this overrides
     * any value set via [setDispatchAgent].
     *
     * @param fn The builder function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setDispatchAgentBuilderFunction(fn: (suspend (harness: PumpStation) -> Pipeline)?): PumpStation
    {
        this.dispatchAgentBuilderFunction = fn
        return this
    }

    /**
     * Sets the intervention agent builder function. When non-null, this overrides
     * any value set via [setInterventionAgent].
     *
     * @param fn The builder function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setInterventionAgentBuilderFunction(fn: (suspend (harness: PumpStation) -> P2PInterface)?): PumpStation
    {
        this.interventionAgentBuilderFunction = fn
        return this
    }

    /**
     * Sets the health agent builder function. When non-null, this overrides
     * any value set via [setHealthAgent].
     *
     * @param fn The builder function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setHealthAgentBuilderFunction(fn: (suspend (harness: PumpStation) -> P2PInterface)?): PumpStation
    {
        this.healthAgentBuilderFunction = fn
        return this
    }

    /**
     * Sets the lorebook agent builder function. When non-null, this overrides
     * any value set via [setLorebookAgent].
     *
     * @param fn The builder function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setLorebookAgentBuilderFunction(fn: (suspend (harness: PumpStation) -> P2PInterface)?): PumpStation
    {
        this.lorebookAgentBuilderFunction = fn
        return this
    }

    /**
     * Sets the summary agent builder function. When non-null, this overrides
     * any value set via [setSummaryAgent].
     *
     * @param fn The builder function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setSummaryAgentBuilderFunction(fn: (suspend (harness: PumpStation) -> P2PInterface)?): PumpStation
    {
        this.summaryAgentBuilderFunction = fn
        return this
    }

    /**
     * Sets the goal agent builder function. When non-null, this overrides
     * any value set via [setGoalAgent].
     *
     * @param fn The builder function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setGoalAgentBuilderFunction(fn: (suspend (harness: PumpStation) -> P2PInterface)?): PumpStation
    {
        this.goalAgentBuilderFunction = fn
        return this
    }

    /**
     * Sets the post-success agent builder function. When non-null, this overrides
     * any value set via [setPostGoalAgent].
     *
     * @param fn The builder function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPostGoalAgentBuilderFunction(fn: (suspend (harness: PumpStation) -> P2PInterface)?): PumpStation
    {
        this.postGoalAgentBuilderFunction = fn
        return this
    }

    /**
     * Sets the post-success DITL function. Fires inside [runExitFlow] after the goal
     * agent passes (or on the no-goal-agent path). Synchronous transformation of the
     * goal output that precedes [postGoalAgent] when both are configured.
     *
     * @param fn The transformation function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPostGoalFunction(fn: (suspend (content: MultimodalContent, harness: PumpStation) -> MultimodalContent)?): PumpStation
    {
        this.postGoalFunction = fn
        return this
    }

//---------------------------------------Concurrency / Loop / Memory------------------------------------------------

    /**
     * Sets the concurrency mode for background tasks. Async fires as soon as
     * possible and queues; Blocking runs each background agent to completion in order.
     *
     * @param mode The concurrency mode.
     * @return This PumpStation instance for method chaining.
     */
    fun setConcurrencyMode(mode: PumpStationConcurrencyMode): PumpStation
    {
        this.concurrencyMode = mode
        return this
    }

    /**
     * Sets the memory management mode. Compaction, Truncation, or Hybrid.
     *
     * @param mode The memory management mode.
     * @return This PumpStation instance for method chaining.
     */
    fun setMemoryManagementMode(mode: PumpStationMemoryManagementMode): PumpStation
    {
        this.memoryManagementMode = mode
        return this
    }

    /**
     * Sets the default compaction strategy.
     *
     * @param strategy The compaction strategy.
     * @return This PumpStation instance for method chaining.
     */
    fun setCompactionStrategy(strategy: PumpStationCompactionStrategy): PumpStation
    {
        this.compactionStrategy = strategy
        return this
    }

    /**
     * Sets the % filled ratio of the available context window space that triggers compaction.
     *
     * @param threshold The compaction threshold (0.0-1.0).
     * @return This PumpStation instance for method chaining.
     */
    fun setCompactionThreshold(threshold: Double): PumpStation
    {
        this.compactionThreshold = threshold
        return this
    }

    //=====================================v3: Compaction setters=================================================

    /**
     * Sets the chunk fan-out mode for the [PumpStationCompactionStrategy.Chunked] strategy.
     * Default is [ChunkFanoutMode.Sequential]. Switching to [ChunkFanoutMode.Parallel]
     * requires a maxParallelChunks value (configurable via [setMaxParallelChunks]).
     *
     * @param mode The fan-out mode.
     * @return This PumpStation instance for method chaining.
     */
    fun setCompactionFanoutMode(mode: ChunkFanoutMode): PumpStation
    {
        this.compactionFanoutMode = mode
        return this
    }

    /**
     * Sets the maximum number of compaction attempts (pre-prune + summarize + fold) the
     * orchestrator will run before handing off to truncation. Default is 2.
     *
     * @param value The maximum attempts; must be >= 1.
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxCompactionAttempts(value: Int): PumpStation
    {
        require(value >= 1) { "maxCompactionAttempts must be >= 1, got $value" }
        this.maxCompactionAttempts = value
        return this
    }

    /**
     * Sets the token budget per chunk for the Chunked strategy. The orchestrator partitions
     * `turnHistory` into `max(1, tokens / chunkTokenBudget)` chunks (capped by
     * [setMaxChunks]). Default 2000.
     */
    fun setChunkTokenBudget(value: Int): PumpStation
    {
        require(value >= 1) { "chunkTokenBudget must be >= 1, got $value" }
        this.chunkTokenBudget = value
        return this
    }

    /**
     * Sets the hard cap on the number of chunks the Chunked strategy produces from a
     * single attempt. Default 16. Prevents pathological partitioning of very large
     * histories.
     */
    fun setMaxChunks(value: Int): PumpStation
    {
        require(value >= 1) { "maxChunks must be >= 1, got $value" }
        this.maxChunks = value
        return this
    }

    /**
     * Sets the semaphore permit count for the [ChunkFanoutMode.Parallel] strategy. Default
     * 4. Bounds the number of concurrent chunk-summarize calls to the summary agent.
     */
    fun setMaxParallelChunks(value: Int): PumpStation
    {
        require(value >= 1) { "maxParallelChunks must be >= 1, got $value" }
        this.maxParallelChunks = value
        return this
    }

    /**
     * Sets the maximum number of [CompactionBackup] snapshots retained in the ring buffer.
     * Default 3. Older backups are dropped when the buffer overflows.
     */
    fun setMaxCompactionBackups(value: Int): PumpStation
    {
        require(value >= 1) { "maxCompactionBackups must be >= 1, got $value" }
        this.maxCompactionBackups = value
        return this
    }

    /**
     * Sets the headroom threshold (0.0-1.0) below which the
     * [PumpStationCompactionStrategy.Hybrid] strategy downgrades to Chunked. Above this
     * headroom, Hybrid delegates to Whole. Default 0.3.
     */
    fun setHybridWholeHeadroom(value: Double): PumpStation
    {
        require(value in 0.0..1.0) { "hybridWholeHeadroom must be in [0,1], got $value" }
        this.hybridWholeHeadroom = value
        return this
    }

    /**
     * Replaces the default pre-prune transform entirely. The transform is applied to
     * the raw [turnHistory] before it reaches the summary agent, allowing the developer
     * to drop application-specific noise (e.g. their own marker tokens) without paying
     * LLM cost. Pass null to restore the default pruner.
     */
    fun setPrePruneTransform(
        transform: (suspend (List<ConverseData>, PumpStation) -> List<ConverseData>)?
    ): PumpStation
    {
        this.prePruneTransform = transform
        return this
    }

    /**
     * Adds an extra pre-prune transform that runs *after* the default pruner. Multiple
     * extra pruners can be registered; they run in registration order. Returns the
     * registration index so the developer can later remove the transform if needed.
     */
    fun appendPrePruneTransform(
        transform: suspend (List<ConverseData>, PumpStation) -> List<ConverseData>
    ): Int
    {
        extraPrePruneTransforms.add(transform)
        return extraPrePruneTransforms.size - 1
    }

    /**
     * Sets the DITL function that fires when a [CompactionBackup] is restored. The function
     * receives the backup, a human-readable reason, and the harness. It may return a
     * replacement backup (which will be applied) or null to use the restored one as-is.
     * Throwing from this hook converts the orchestrator's retry to a handoff-to-truncation.
     * Pass null to clear the binding.
     */
    fun setCompactionRolledBackFunction(
        func: (suspend (CompactionBackup, String, PumpStation) -> CompactionBackup?)?
    ): PumpStation
    {
        this.compactionRolledBackFunction = func
        return this
    }

    /**
     * Sets the maximum number of harness turns before forced exit. Delegating alias
     * for [setMaxTurns]; both setters write the same [maxTurns] backing field that
     * the harness loop reads. Kept as a top-level setter so existing callers using
     * `station.setMaxHarnessTurns(N)` continue to work without modification.
     *
     * @param max The maximum harness turns.
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxHarnessTurns(max: Int): PumpStation
    {
        this.maxTurns = max
        return this
    }

    /**
     * Sets the judge run mode for this PumpStation. See [PumpStationJudgeRunMode] for semantics.
     * Default is [PumpStationJudgeRunMode.Always] (judge fires every turn).
     *
     * @param mode The judge run mode.
     * @return This PumpStation instance for method chaining.
     */
    fun setJudgeRunMode(mode: PumpStationJudgeRunMode): PumpStation
    {
        this.judgeRunModeInternal = mode
        return this
    }

    /**
     * Sets the dispatch contract shape for this PumpStation.
     *
     * [PathExecutionShape.SinglePath] (default) preserves the existing dispatch JSON
     * contract: dispatch LLM returns one [com.TTT.Pipeline.PathRequest], harness
     * invokes one path.
     *
     * [PathExecutionShape.MultiPath] injects a new dispatch prompt asking the
     * LLM to return a [com.TTT.Pipeline.PathRequestList]. The harness fans the
     * list out via the existing async substrate and merges results into turn
     * history on the next judge. New [PathBatchStarted] / [PathBatchCompleted] /
     * [PathBatchFailed] events carry the batch metadata.
     *
     * @param shape The dispatch contract shape to use.
     * @return This [PumpStation] for method chaining.
     */
    fun setPathExecutionShape(shape: PathExecutionShape): PumpStation
    {
        this.pathExecutionShape = shape
        return this
    }

    /**
     * Returns the active judge run mode. See [PumpStationJudgeRunMode] for semantics.
     */
    fun getJudgeRunMode(): PumpStationJudgeRunMode = judgeRunModeInternal

    /**
     * Returns the configured dispatch contract shape for this PumpStation.
     * Defaults to [PathExecutionShape.SinglePath] for backward compatibility.
     *
     * @return The current [PathExecutionShape] for this station.
     */
    fun getPathExecutionShape(): PathExecutionShape = pathExecutionShape

    /**
     * Internal accessor so [com.TTT.Pipeline.PumpStationLoop.kt] extension functions
     * can read the dispatch contract shape without exposing the mutable backing field.
     */
    internal val pathExecutionShapeInternal get() = pathExecutionShape

    /**
     * When `enabled` is true (default), the judge phase is skipped on turn 0 and a
     * [JudgeSkipped] event with `reason = "first_turn"` is emitted in its place. The harness
     * then runs dispatch and at least one path before the judge gets a verdict vote.
     *
     * This prevents the live-judge failure mode where a judge LLM sees the pre-dispatch
     * state (system task + user prompt with no paths yet), hallucinates a completed brief,
     * and returns `isComplete = true`. Without this guard the harness short-circuits via
     * [runExitFlow] before any path runs.
     *
     * Does NOT interact with [PumpStationJudgeRunMode.FlagTriggered] — that mode's
     * `no_flag_set` skip takes precedence.
     *
     * @param enabled When true, skip the judge on turn 0. Default true.
     * @return This PumpStation instance for method chaining.
     */
    fun setSkipJudgeOnFirstTurn(enabled: Boolean): PumpStation
    {
        this.skipJudgeOnFirstTurnInternal = enabled
        return this
    }

    /**
     * Returns whether the judge phase is skipped on turn 0. See [setSkipJudgeOnFirstTurn]
     * for semantics. Default is true.
     */
    fun getSkipJudgeOnFirstTurn(): Boolean = skipJudgeOnFirstTurnInternal

    /**
     * Returns the configured judge agent pipeline, or `null` if no judge has been wired.
     * Public accessor for the [judgeAgent] field; useful in defaults factories and integration
     * tests that need to verify the slot was filled.
     */
    fun getJudgeAgent(): Pipeline? = judgeAgent

    /**
     * Returns the configured dispatch agent pipeline, or `null` if no dispatch agent has been wired.
     * Public accessor for the [dispatchAgent] field; useful in defaults factories and integration
     * tests that need to verify the slot was filled.
     */
    fun getDispatchAgent(): Pipeline? = dispatchAgent

    /**
     * Returns the configured kill switch instance, or `null` if no kill switch has been wired.
     * Public accessor with a non-conflicting name (the [killSwitch] property auto-generates
     * `getKillSwitch()` on the JVM, so this is named to disambiguate); useful in defaults
     * factories and integration tests that need to verify the slot was filled.
     */
    fun getConfiguredKillSwitch(): com.TTT.P2P.KillSwitch? = killSwitch

    /**
     * Sets the maximum number of harness turns before forced exit. This is the
     * canonical loop-guard setter; the [com.TTT.Pipeline.PumpStationLoop.runHarnessLoop]
     * extension reads the value via [maxTurnsInternal] and terminates with
     * [PumpStationError.MaxTurnsExceeded] / [PumpStationExitReason.MaxTurnsHit]
     * when [com.TTT.Pipeline.PumpStationTaskState.turnIndex] reaches the cap.
     *
     * [setMaxHarnessTurns] is a delegating alias that writes the same field.
     *
     * @param max The maximum number of harness turns.
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxTurns(max: Int): PumpStation
    {
        this.maxTurns = max
        return this
    }

    /**
     * Sets the maximum number of consecutive goal-evaluation failures before
     * the harness gives up on the current task.
     *
     * @param value The maximum goal-fail attempts.
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxGoalFailAttempts(value: Int): PumpStation
    {
        this.maxGoalFailAttempts = value
        return this
    }

    /**
     * Returns the maximum number of consecutive goal-evaluation failures
     * before the harness gives up on the current task.
     */
    fun getMaxGoalFailAttempts(): Int = maxGoalFailAttempts

    /**
     * Returns the maximum number of harness turns. Public mirror of the
     * private [maxTurns] field; the harness loop enforces this limit via
     * [com.TTT.Pipeline.PumpStationLoop]. Companion to [getMaxTurns] and
     * [setMaxHarnessTurns]; both names read the same backing field.
     */
    fun getMaxHarnessTurns(): Int = maxTurns

    /**
     * Returns the maximum number of harness turns configured via [setMaxTurns]
     * (or its delegating alias [setMaxHarnessTurns]). This is the canonical
     * loop-guard budget; the harness loop enforces it via
     * [com.TTT.Pipeline.PumpStationLoop].
     */
    fun getMaxTurns(): Int = maxTurns

    /**
     * Returns whether the judge agent is expected to emit a JSON contract
     * verdict. Mirrors the [setJudgeJsonContractEnabled] setter.
     */
    fun getJudgeJsonContractEnabled(): Boolean = judgeExpectsJsonContract

    /**
     * Returns whether the path-safety agent is expected to emit a JSON
     * contract verdict. Mirrors the [setPathSafetyJsonContractEnabled]
     * setter.
     */
    fun getPathSafetyJsonContractEnabled(): Boolean = pathSafetyExpectsJsonContract

    /**
     * Sets the maximum number of raw turn history entries to retain.
     *
     * @param value The maximum raw turn history size, or null to disable the cap.
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxRawTurnHistorySize(value: Int?): PumpStation
    {
        this.maxRawTurnHistorySize = value
        return this
    }

    /**
     * Returns the maximum number of raw turn history entries to retain, or
     * null if no cap is enforced.
     */
    fun getMaxRawTurnHistorySize(): Int? = maxRawTurnHistorySize

    /**
     * Sets the context-blowout threshold (0.0-1.0). When the context window
     * utilization exceeds this fraction, blowout detection fires.
     *
     * @param value The blowout threshold fraction.
     * @return This PumpStation instance for method chaining.
     */
    fun setBlowoutThreshold(value: Double): PumpStation
    {
        this.blowoutThreshold = value
        return this
    }

    /**
     * Returns the context-blowout threshold (0.0-1.0).
     */
    fun getBlowoutThreshold(): Double = blowoutThreshold

    /**
     * Sets the timeout in milliseconds for memory update operations.
     *
     * @param value The memory-update timeout in milliseconds.
     * @return This PumpStation instance for method chaining.
     */
    fun setMemoryUpdateTimeoutMs(value: Long): PumpStation
    {
        this.memoryUpdateTimeoutMs = value
        return this
    }

    /**
     * Returns the memory-update timeout in milliseconds.
     */
    fun getMemoryUpdateTimeoutMs(): Long = memoryUpdateTimeoutMs

    /**
     * Sets the maximum number of blowout recovery attempts before forced halt.
     *
     * @param value The maximum blowout recoveries.
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxBlowoutRecoveries(value: Int): PumpStation
    {
        this.maxBlowoutRecoveries = value
        return this
    }

    /**
     * Returns the maximum number of blowout recovery attempts.
     */
    fun getMaxBlowoutRecoveries(): Int = maxBlowoutRecoveries

    /**
     * Sets the maximum number of tokens allowed in a repair/regeneration prompt.
     *
     * @param value The maximum repair-prompt tokens.
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxRepairPromptTokens(value: Int): PumpStation
    {
        this.maxRepairPromptTokens = value
        return this
    }

    //=====================================SafePrune fluent setters====================================================

    /**
     * Master switch for the SafePrune phase. When false (the default), the phase is a no-op.
     *
     * @param enabled true to enable, false to disable.
     * @return This PumpStation instance for method chaining.
     * @see SafePruneStrategy
     */
    fun setSafePruneEnabled(enabled: Boolean): PumpStation
    {
        this.safePruneEnabled = enabled
        return this
    }

    /**
     * Minimum turnHistory size required for SafePrune to fire on a given turn.
     *
     * @param threshold Minimum entry count; pass <= 0 to disable the size gate.
     * @return This PumpStation instance for method chaining.
     */
    fun setSafePruneSizeThreshold(threshold: Int): PumpStation
    {
        this.safePruneSizeThreshold = threshold
        return this
    }

    /**
     * Number of most-recent entries that SafePrune strategies must NOT mutate.
     *
     * @param count Number of recent entries to protect (>= 0).
     * @return This PumpStation instance for method chaining.
     */
    fun setSafePruneProtectRecentN(count: Int): PumpStation
    {
        this.safePruneProtectRecentN = count
        return this
    }

    /**
     * Window size for the [SafePruneStrategy.DeduplicateByHash] strategy.
     *
     * @param window Window size in entries (>= 1).
     * @return This PumpStation instance for method chaining.
     */
    fun setSafePruneHashWindow(window: Int): PumpStation
    {
        this.safePruneHashWindow = window
        return this
    }

    /**
     * Maximum tool-response text length before [SafePruneStrategy.StripLongToolArguments]
     * replaces it with a truncated stub.
     *
     * @param length Maximum length in characters (>= 1).
     * @return This PumpStation instance for method chaining.
     */
    fun setSafePruneMaxToolArgLength(length: Int): PumpStation
    {
        this.safePruneMaxToolArgLength = length
        return this
    }

    /**
     * Enable a single SafePrune strategy. Idempotent — enabling twice has no effect.
     *
     * @param strategy Strategy to enable.
     * @return This PumpStation instance for method chaining.
     */
    fun enableSafePruneStrategy(strategy: SafePruneStrategy): PumpStation
    {
        this.safePruneEnabledStrategies.add(strategy)
        return this
    }

    /**
     * Disable a single SafePrune strategy. Idempotent — disabling an already-disabled
     * strategy has no effect.
     *
     * @param strategy Strategy to disable.
     * @return This PumpStation instance for method chaining.
     */
    fun disableSafePruneStrategy(strategy: SafePruneStrategy): PumpStation
    {
        this.safePruneEnabledStrategies.remove(strategy)
        return this
    }

    /**
     * Replace the entire enabled-strategy set. Pass an empty set to disable all
     * strategies without turning the master switch off.
     *
     * @param strategies Strategies to enable (others are disabled).
     * @return This PumpStation instance for method chaining.
     */
    fun setSafePruneStrategies(strategies: Set<SafePruneStrategy>): PumpStation
    {
        this.safePruneEnabledStrategies.clear()
        this.safePruneEnabledStrategies.addAll(strategies)
        return this
    }

    /**
     * Set a per-strategy policy override for a single strategy. Null policy
     * clears any existing override (strategy falls back to global knobs).
     *
     * @param strategy Strategy to override.
     * @param policy Override policy, or null to clear.
     * @return This PumpStation instance for method chaining.
     */
    fun setSafePruneStrategyPolicy(strategy: SafePruneStrategy, policy: SafePrunePolicy?): PumpStation
    {
        if (policy == null) safePruneStrategyPolicies.remove(strategy)
        else safePruneStrategyPolicies[strategy] = policy
        return this
    }

    /**
     * Replace the entire per-strategy policy map.
     *
     * @param policies New policy map; strategies not in the map fall back to global.
     * @return This PumpStation instance for method chaining.
     */
    fun setSafePruneStrategyPolicies(policies: Map<SafePruneStrategy, SafePrunePolicy>): PumpStation
    {
        safePruneStrategyPolicies.clear()
        safePruneStrategyPolicies.putAll(policies)
        return this
    }

    /**
     * Set or clear dry-run mode for a single strategy. When true, the strategy
     * computes its mutation but does NOT apply it; instead a SafePruneDryRunCompleted
     * event fires with the hypothetical report.
     *
     * @param strategy Strategy to toggle.
     * @param dryRun True to enable dry-run, false to disable (default mutation).
     * @return This PumpStation instance for method chaining.
     */
    fun setSafePruneStrategyDryRun(strategy: SafePruneStrategy, dryRun: Boolean): PumpStation
    {
        if (dryRun) safePruneStrategyDryRun.add(strategy)
        else safePruneStrategyDryRun.remove(strategy)
        return this
    }

    /**
     * Enable or disable dry-run mode for every strategy at once.
     *
     * @param dryRun True to enable dry-run for all strategies, false to clear all.
     * @return This PumpStation instance for method chaining.
     */
    fun setSafePruneStrategyDryRunAll(dryRun: Boolean): PumpStation
    {
        if (dryRun) safePruneStrategyDryRun.addAll(SafePruneStrategy.entries)
        else safePruneStrategyDryRun.clear()
        return this
    }

    /**
     * Returns the maximum number of tokens allowed in a repair/regeneration
     * prompt.
     */
    fun getMaxRepairPromptTokens(): Int = maxRepairPromptTokens

    /**
     * Sets the maximum number of consecutive turns on the same path before the
     * loop guard fires.
     *
     * @param max The maximum consecutive turns, or null to disable the guard.
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxConsecutiveSamePath(max: Int?): PumpStation
    {
        this.maxConsecutiveSamePath = max
        return this
    }

    /**
     * Sets the maximum total invocations allowed per path name.
     *
     * @param max The maximum total calls per path, or null to disable the guard.
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxTotalPathCallsPerPath(max: Int?): PumpStation
    {
        this.maxTotalPathCallsPerPath = max
        return this
    }

    /**
     * Sets the maximum number of consecutive dispatches of unregistered path
     * names before the loop guard fires with
     * [PumpStationExitReason.LoopGuardTripped].
     *
     * @param max The maximum consecutive UnknownPath dispatches, or null to
     *   disable the guard (preserves today's unbounded behavior).
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxConsecutiveUnknownPaths(max: Int?): PumpStation
    {
        this.maxConsecutiveUnknownPaths = max
        return this
    }

    /**
     * Sets the maximum number of [ConverseHistory] elements allowed in the turn history.
     *
     * @param max The maximum turn history size.
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxTurnHistorySize(max: Int): PumpStation
    {
        this.maxTurnHistorySize = max
        return this
    }

    /**
     * Sets whether the harness should immediately stop when the dispatch agent
     * generates invalid JSON for a path request.
     *
     * @param stop true to stop on invalid JSON; false to attempt recovery.
     * @return This PumpStation instance for method chaining.
     */
    fun setStopHarnessOnInvalidPathRequest(stop: Boolean): PumpStation
    {
        this.stopHarnessOnInvalidPathRequest = stop
        return this
    }

    /**
     * Sets the [requirePathSelectionRationale] flag on the failure policy,
     * controlling whether the dispatch LLM is required to commit a
     * [PathRequest.pathSelectionRationale] on every turn.
     *
     * @param require true to require a rationale; false to silence the
     *                prompt directive and skip the nudge-on-empty check.
     * @return This PumpStation instance for method chaining.
     */
    fun setRequirePathSelectionRationale(require: Boolean): PumpStation
    {
        this.failurePolicy.requirePathSelectionRationale = require
        this.requirePathSelectionRationale = require
        return this
    }

    /**
     * Sets whether the judge phase parses the agent's text output as a JSON
     * JudgeVerdict. Default is true (the contract documented in
     * [DEFAULT_JUDGE_PROMPT] is honored). Set to false to drive the judge
     * verdict purely via MultimodalContent flags (terminatePipeline /
     * passPipeline), which is the canonical loop-control pattern documented on
     * [checkMultimodalFlags] and matches the harness's design philosophy of
     * "agents signal via flags, not via magic contracts."
     *
     * @param enabled true to parse the JSON contract, false to skip it.
     * @return This PumpStation instance for method chaining.
     */
    fun setJudgeJsonContractEnabled(enabled: Boolean): PumpStation
    {
        this.judgeExpectsJsonContract = enabled
        return this
    }

    /**
     * Sets whether the path-safety phase parses the agent's text output as a
     * JSON verdict. Default is true (the contract documented in
     * [parsePathSafetyVerdict] is honored). Set to false to drive the safety
     * verdict purely via MultimodalContent flags (terminatePipeline means
     * reject, no flag means approve), matching the canonical flag-driven
     * pattern.
     *
     * @param enabled true to parse the JSON contract, false to skip it.
     * @return This PumpStation instance for method chaining.
     */
    fun setPathSafetyJsonContractEnabled(enabled: Boolean): PumpStation
    {
        this.pathSafetyExpectsJsonContract = enabled
        return this
    }

    //==================================================================
    //  setXxxSystemPrompt — the single developer-facing API for opting
    //  in or out of the auto-injected contract prompts. Setting a
    //  non-null custom prompt switches the agent to flag-driven control
    //  (the JSON contract is skipped). Setting null re-enables the
    //  default prompt + contract.
    //==================================================================

    /**
     * Sets a custom system prompt for the judge agent. When non-null, the
     * pump station injects this prompt into the decision pipe of the judge's
     * pipeline and disables the JSON contract (the agent drives the verdict
     * via MultimodalContent flags only).
     *
     * Setting null re-enables the default [DEFAULT_JUDGE_PROMPT] and the
     * JSON contract.
     */
    fun setJudgeSystemPrompt(prompt: String?): PumpStation
    {
        this.customJudgeSystemPrompt = prompt
        this.judgeExpectsJsonContract = (prompt == null)
        applyCustomPromptToAgent(this.judgeAgent, prompt)
        return this
    }

    /**
     * Sets a custom system prompt for the dispatch agent.
     */
    fun setDispatchSystemPrompt(prompt: String?): PumpStation
    {
        this.customDispatchSystemPrompt = prompt
        applyCustomPromptToAgent(this.dispatchAgent, prompt)
        return this
    }

    /**
     * Sets a custom system prompt for the path-safety agent.
     */
    fun setPathSafetySystemPrompt(prompt: String?): PumpStation
    {
        this.customPathSafetySystemPrompt = prompt
        this.pathSafetyExpectsJsonContract = (prompt == null)
        applyCustomPromptToAgent(this.pathSafetyAgent, prompt)
        return this
    }

    /**
     * Sets a custom system prompt for the health agent.
     */
    fun setHealthSystemPrompt(prompt: String?): PumpStation
    {
        this.customHealthSystemPrompt = prompt
        applyCustomPromptToAgent(this.healthAgent, prompt)
        return this
    }

    /**
     * Sets a custom system prompt for the lorebook agent.
     */
    fun setLorebookSystemPrompt(prompt: String?): PumpStation
    {
        this.customLorebookSystemPrompt = prompt
        applyCustomPromptToAgent(this.lorebookAgent, prompt)
        return this
    }

    /**
     * Sets a custom system prompt for the goal agent.
     */
    fun setGoalSystemPrompt(prompt: String?): PumpStation
    {
        this.customGoalSystemPrompt = prompt
        applyCustomPromptToAgent(this.goalAgent, prompt)
        return this
    }

    /**
     * Apply a custom system prompt to the decision pipe of an agent's
     * pipeline. If [prompt] is null, the call is a no-op (the default
     * prompt is injected at [setXxxAgent] time, not here).
     */
    /**
     * Apply a custom system prompt to the decision pipe of an agent's
     * pipeline. If [prompt] is null, the call is a no-op (the default
     * prompt is injected at [setXxxAgent] time, not here).
     */
    private fun applyCustomPromptToAgent(agent: P2PInterface?, prompt: String?)
    {
        if(prompt == null) return
        val pipeline = agent as? Pipeline ?: return
        val decisionPipe = resolveDecisionPipeForInjection(pipeline) ?: return
        decisionPipe.setSystemPrompt(prompt)
    }

    /**
     * Resolve the pipe in [pipeline] that should receive the contract prompt.
     * Uses the same layered resolution as [Pipeline.execute]: manual
     * `decisionPipeName` first, then [com.TTT.Pipe.Pipe.isDecisionPipe], then
     * [com.TTT.Enums.PipeRole.Decision], then heuristic scoring. Returns null
     * if no decision pipe can be resolved.
     */
    private fun resolveDecisionPipeForInjection(pipeline: Pipeline): Pipe?
    {
        // 1. Manual override
        val manual = pipeline.decisionPipeName
        if(manual != null)
        {
            val (idx, pipe) = pipeline.getPipeByName(manual)
            if(idx >= 0 && pipe != null) return pipe
        }
        // 2. isDecisionPipe flag
        for(pipe in pipeline.getPipes())
        {
            if(pipe.isDecisionPipe) return pipe
        }
        // 3. pipeRole == Decision
        for(pipe in pipeline.getPipes())
        {
            if(pipe.pipeRole == com.TTT.Enums.PipeRole.Decision) return pipe
        }
        // 4. Heuristic scoring fallback (only returns a pipe if it has
        // a strong LLM signal — see [Pipeline.scoreDecisionPipeCandidates])
        val pipes = pipeline.getPipes()
        if(pipes.isNotEmpty())
        {
            var bestPipe: Pipe? = null
            var bestScore = 0
            val namePattern = Regex("(?i)(decision|judge|dispatch|output|final)")
            for(pipe in pipes)
            {
                val s = pipe.toPipeSettings()
                var score = 0
                if(s.provider != null && !s.model.isNullOrEmpty()) score += 10
                if(!s.jsonOutput.isNullOrEmpty()) score += 5
                if(!s.systemPrompt.isNullOrEmpty()) score += 3
                if(namePattern.containsMatchIn(pipe.pipeName)) score += 1
                if(score > bestScore || (score == bestScore && score > 0 && bestPipe != null))
                {
                    bestPipe = pipe
                    bestScore = score
                }
            }
            if(bestScore >= 10) return bestPipe
        }
        // 5. Last-pipe fallback: in a single-pipe pipeline, the only pipe
        // IS the decision pipe by definition. For multi-pipe pipelines
        // without signals, we use the last pipe as a best-effort default.
        return pipes.lastOrNull()
    }

    /**
     * Auto-injection helper. Called at [setXxxAgent] time: if the developer
     * has not supplied a custom prompt, inject the default into the decision
     * pipe of the agent's pipeline. If the developer HAS supplied a custom
     * prompt (via [setXxxSystemPrompt]), inject THAT instead.
     *
     * If the pipe already has a system prompt set, the auto-injection is
     * skipped — the developer's manual configuration takes precedence.
     */
    private fun autoInjectDefaultPrompt(agent: Pipeline, customPrompt: String?, defaultPrompt: String)
    {
        val decisionPipe = resolveDecisionPipeForInjection(agent) ?: return
        // Read the existing system prompt via toPipeSettings() to avoid
        // touching the protected `systemPrompt` field directly.
        val existing = decisionPipe.toPipeSettings().systemPrompt
        if(existing != null && existing.isNotEmpty())
        {
            // The developer already configured a prompt on this pipe.
            // Respect their choice and don't overwrite.
            return
        }
        val promptToInject = customPrompt ?: defaultPrompt
        decisionPipe.setSystemPrompt(promptToInject)
    }

    /**
     * Sets the maximum number of concurrent background agents.
     *
     * @param max The maximum concurrent background agents.
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxConcurrentBackgroundAgents(max: Int): PumpStation
    {
        this.maxConcurrentBackgroundAgents = max
        return this
    }

    /**
     * Sets the maximum number of concurrent foreground agents.
     *
     * @param max The maximum concurrent foreground agents.
     * @return This PumpStation instance for method chaining.
     */
    fun setMaxConcurrentForegroundAgents(max: Int): PumpStation
    {
        this.maxConcurrentForegroundAgents = max
        return this
    }

    /**
     * Sets the number of turns to wait before firing foreground agents.
     *
     * @param interval The foreground turn interval.
     * @return This PumpStation instance for method chaining.
     */
    fun setForegroundTurnInterval(interval: Int): PumpStation
    {
        this.foregroundTurnInterval = interval
        return this
    }

    /**
     * Sets the number of turns to wait before firing background agents.
     *
     * @param interval The background turn interval.
     * @return This PumpStation instance for method chaining.
     */
    fun setBackgroundTurnInterval(interval: Int): PumpStation
    {
        this.backgroundTurnInterval = interval
        return this
    }

    //---------------------------------------Async Substrate Setters----------------------------------------------------

    /**
     * Sets the station-wide default for whether async paths are appended to
     * [turnHistory] on completion. The default is true. Per-path opt-out is
     * available via [PathObject.setSuppressHistoryEmit].
     *
     * @param value true to auto-append async path results, false to suppress.
     * @return This PumpStation instance for method chaining.
     */
    fun setAsyncPathsAppendToTurnHistory(value: Boolean): PumpStation
    {
        this.asyncPathsAppendToTurnHistory = value
        return this
    }

    /**
     * Returns the current default for async-path history append behavior.
     */
    fun isAsyncPathsAppendToTurnHistory(): Boolean = asyncPathsAppendToTurnHistory

    /**
     * Sets the station-wide default for whether async harness agents are appended
     * to [turnHistory] on completion. The default is false (fire-and-forget).
     * Per-slot opt-in is available via the [HarnessAgentSlot.appendsToTurnHistory]
     * field and the matching DSL knob.
     *
     * @param value true to auto-append async agent results, false to suppress.
     * @return This PumpStation instance for method chaining.
     */
    fun setAsyncAgentsAppendToTurnHistory(value: Boolean): PumpStation
    {
        this.asyncAgentsAppendToTurnHistory = value
        return this
    }

    /**
     * Returns the current default for async-agent history append behavior.
     */
    fun isAsyncAgentsAppendToTurnHistory(): Boolean = asyncAgentsAppendToTurnHistory

    /**
     * Sets the grace period (milliseconds) given to in-flight async coroutines
     * after [runFinalizationPhase] before [cancelAsyncJobs] cancels the
     * [asyncScope]. Pass null to disable the timeout (the default) and let
     * in-flight work run until the scope is cancelled. When set, coroutines
     * that do not finish within the window are cancelled; their partial
     * results are NOT merged into [turnHistory].
     *
     * @param ms Grace period in milliseconds, or null to disable.
     * @return This PumpStation instance for method chaining.
     */
    fun setAsyncJobGracePeriodMs(ms: Long?): PumpStation
    {
        this.asyncJobGracePeriodMs = ms
        return this
    }

    /**
     * Returns the current async job grace period (milliseconds), or null if
     * the cancel is unbounded.
     */
    fun getAsyncJobGracePeriodMs(): Long? = asyncJobGracePeriodMs

    /**
     * Toggles whether async work runs on a station-scoped [CoroutineScope]
     * (the default) or on [GlobalScope]. The station-scoped behavior is the
     * recommended path because [cancelAsyncJobs] can guarantee that no async
     * coroutine outlives [executeLocal]. Setting this to false restores the
     * pre-substrate fire-and-forget behavior.
     *
     * @param value true to use [asyncScope], false to use [GlobalScope].
     * @return This PumpStation instance for method chaining.
     */
    fun setAsyncJobsScopedToStation(value: Boolean): PumpStation
    {
        this.asyncJobsScopedToStation = value
        return this
    }

    /**
     * Returns whether async work runs on the station-scoped [CoroutineScope].
     */
    fun isAsyncJobsScopedToStation(): Boolean = asyncJobsScopedToStation

    //---------------------------------------Async Substrate Public API-----------------------------------------------

    /**
     * Append a single [ConverseData] entry to [turnHistory] from a coroutine
     * running on [asyncScope] (or anywhere outside the foreground path). This
     * is the single thread-safe access point for async producers that want to
     * push into the harness conversation.
     *
     * Direct mutation of [turnHistory] from async code is NOT safe because
     * [ConverseHistory] is a plain data class with no internal lock. Always
     * call this method instead of  from async code.
     *
     * @param entry The turn entry to append.
     * @param source A short identifier for the producer (e.g. agent class name).
     */
    suspend fun appendTurnEntryAsync(entry: ConverseData, source: String = "agent")
    {
        historyMutex.withLock {
            turnHistory.add(entry)
            rawTurnHistory.add(entry)
        }
        emitEventInternal(AsyncTurnAppended(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            phase = PumpStationPhase.PathExecution,
            source = source,
            pathName = null,
            agentName = source,
            seq = asyncSeqCounter.get(),
            content = entry.content
        ))
    }

    /**
     * Batch version of [appendTurnEntryAsync]. Acquires [historyMutex] once
     * and emits a single trailing [AsyncTurnAppended] event for the last
     * entry, so observers can correlate.
     */
    suspend fun appendTurnEntriesAsync(entries: List<ConverseData>, source: String = "agent")
    {
        if (entries.isEmpty()) return
        historyMutex.withLock {
            for (entry in entries)
            {
                turnHistory.add(entry)
                rawTurnHistory.add(entry)
            }
        }
        val last = entries.last()
        emitEventInternal(AsyncTurnAppended(
            runId = taskState.runId,
            turnIndex = taskState.turnIndex,
            phase = PumpStationPhase.PathExecution,
            source = source,
            pathName = null,
            agentName = source,
            seq = asyncSeqCounter.get(),
            content = last.content
        ))
    }

    /**
     * Drain all entries currently buffered in [pendingAsyncResults] and merge
     * them into [turnHistory] in [PendingTurnEntry.seq] order. Returns the
     * number of entries merged. This is called by the foreground at safe phase
     * boundaries (start of judge, start of finalization).
     *
     * The drain is best-effort and lock-free relative to the producer channel:
     * it batches entries until the channel reports empty. Producers may
     * continue to enqueue after the drain returns; the next drain will pick
     * them up.
     */
    internal fun drainPendingAsyncResults(): Int
    {
        if (!asyncPathsAppendToTurnHistory) return 0
        // Batch-pull everything currently buffered. We tolerate the channel
        // being concurrently appended to by async producers — anything that
        // arrives after this loop runs is picked up by the next drain.
        val drained = mutableListOf<PendingTurnEntry>()
        while (true)
        {
            // channelResult is the ChannelResult<PendingTurnEntry> returned by
            // tryReceive(); isSuccess distinguishes a buffered entry from an
            // empty channel. We break on the first empty channel to keep the
            // drain bounded by what is currently buffered.
            val channelResult = pendingAsyncResults.tryReceive()
            if (channelResult.isSuccess) drained.add(channelResult.getOrThrow()) else break
        }
        if (drained.isEmpty()) return 0
        // Stable sort by seq. Sequential ids under contention; the
        // AtomicLong counter guarantees no two entries share a seq.
        drained.sortBy { it.seq }
        var merged = 0
        kotlinx.coroutines.runBlocking {
            historyMutex.withLock {
                for (entry in drained)
                {
                    // Per-path opt-out check: skip if the originating path
                    // is configured to suppress history emission. The flag
                    // is set on the path, not the entry, so we resolve
                    // pathName -> path object here.
                    val path = entry.pathName?.let { name -> pathList[pathKey(name)] ?: reservePaths[pathKey(name)] }
                    val suppressed = path?.isSuppressHistoryEmit == true
                    if (suppressed) continue
                    val turnEntry = ConverseData(
                        role = ConverseRole.assistant,
                        content = entry.result
                    )
                    turnHistory.add(turnEntry)
                    rawTurnHistory.add(turnEntry)
                    merged++
                }
            }
        }
        // Emit one AsyncTurnAppended per merged entry so observers see the
        // merge at the granularity of a single path / agent completion.
        for (entry in drained)
        {
            val path = entry.pathName?.let { name -> pathList[pathKey(name)] ?: reservePaths[pathKey(name)] }
            if (path?.isSuppressHistoryEmit == true) continue
            emitEventInternal(AsyncTurnAppended(
                runId = taskState.runId,
                turnIndex = taskState.turnIndex,
                phase = PumpStationPhase.PathExecution,
                source = entry.source,
                pathName = entry.pathName,
                agentName = entry.agentName,
                seq = entry.seq,
                content = entry.result
            ))
        }
        return merged
    }

    /**
     * Cancel in-flight async coroutines launched on [asyncScope]. The grace
     * period is bounded by [asyncJobGracePeriodMs] when set. When null
     * (the default), the cancel is unbounded — [cancelAsyncJobs] yields once
     * so any in-flight suspend point can progress, then cancels the scope
     * without imposing a hard timeout. This is the recommended default
     * because TPipe intentionally does not impose arbitrary timeouts on
     * user work; long-running async paths (e.g. an async path that wraps
     * a multi-minute LLM call) should not be killed by an arbitrary
     * timebox. Developers who need a hard upper bound should set
     * [asyncJobGracePeriodMs] to a value that matches their worst-case
     * LLM round-trip plus safety margin.
     *
     * After this call returns, [asyncScope] is in a cancelled state and any
     * further [appendTurnEntryAsync] calls will still succeed (they write to
     * the foreground-owned history lists) but the launched coroutines that
     * produced them have been stopped.
     *
     * Called automatically by [runFinalizationPhase] before [executeLocal]
     * returns. Safe to call multiple times.
     *
     * @param gracePeriodMs Optional timeout for the polite-wait phase. When
     *  null, the wait is unbounded (a single [yield] is performed so any
     *  in-flight suspend can make progress before the scope is cancelled).
     */
    fun cancelAsyncJobs(gracePeriodMs: Long? = asyncJobGracePeriodMs)
    {
        val scope = if (asyncJobsScopedToStation) asyncScope else null
        // Polite-wait phase: give in-flight work a chance to drain history
        // writes. When gracePeriodMs is null we just yield once and proceed
        // to cancel; long-running work will be cancelled but its cancel
        // handlers (the catch(CancellationException) { throw it } in the
        // launch sites) will be honoured.
        kotlinx.coroutines.runBlocking {
            if (gracePeriodMs == null)
            {
                kotlinx.coroutines.yield()
            }
            else
            {
                kotlinx.coroutines.withTimeoutOrNull(gracePeriodMs) {
                    kotlinx.coroutines.yield()
                }
            }
        }
        scope?.cancel()
    }

    /**
     * Returns true if the [asyncScope] is still active (not yet cancelled).
     * Useful for tests and DITL tooling that needs to check whether a station
     * has been torn down.
     */
    fun isAsyncScopeActive(): Boolean = asyncScope.isActive

    /**
     * Internal accessor for [pendingAsyncResults]. Used by the async dispatch
     * site in [runPathFlow] and by the async harness agent launcher in
     * [runBackgroundAgentsPhase] to enqueue a [PendingTurnEntry].
     */
    internal val pendingAsyncResultsInternal get() = pendingAsyncResults

    /**
     * Internal accessor for [historyMutex]. Used by the async producer helpers
     * in this class and exposed to test fixtures.
     */
    internal val historyMutexInternal get() = historyMutex

    /**
     * Internal accessor for the [asyncSeqCounter]. Producers call
     * [asyncSeqCounterInternal].incrementAndGet() to claim a [seq] for a
     * pending entry.
     */
    internal val asyncSeqCounterInternal get() = asyncSeqCounter

//---------------------------------------Health Probe Setters-------------------------------------------------------

    /**
     * Sets the number of turns between health-agent firings. null disables interval-based firing.
     *
     * @param interval The turn interval, or null to disable.
     * @return This PumpStation instance for method chaining.
     */
    fun setHealthAgentTurnInterval(interval: Int?): PumpStation
    {
        this.healthAgentTurnInterval = interval
        return this
    }

    /**
     * Sets the error-ratio threshold at which the health agent fires. null disables ratio-based firing.
     *
     * @param threshold The error ratio (0.0-1.0), or null to disable.
     * @return This PumpStation instance for method chaining.
     */
    fun setHealthAgentErrorRatioThreshold(threshold: Double?): PumpStation
    {
        this.healthAgentErrorRatioThreshold = threshold
        return this
    }

    /**
     * Sets the concurrency mode for health-agent execution. null lets the harness default behavior run.
     *
     * @param mode The health agent concurrency mode, or null to disable.
     * @return This PumpStation instance for method chaining.
     */
    fun setHealthAgentConcurrencyMode(mode: PumpStationConcurrencyMode?): PumpStation
    {
        this.healthAgentConcurrencyMode = mode
        return this
    }

//---------------------------------------Prompts and Metadata------------------------------------------------------

    /**
     * Sets the personality / persona string. Forces agents to take on the persona
     * and prioritize it above every other instruction.
     *
     * @param personality The personality text.
     * @return This PumpStation instance for method chaining.
     */
    fun setPersonality(personality: String): PumpStation
    {
        this.personality = personality
        return this
    }

    /**
     * Returns the personality / persona string. Mirrors the
     * [setPersonality] setter; primarily intended for tests and
     * external inspection.
     */
    fun getPersonality(): String = personality

    /**
     * Sets the system task string - the harness "system prompt" injected after the
     * built-in harness system instructions.
     *
     * @param task The system task text.
     * @return This PumpStation instance for method chaining.
     */
    fun setSystemTask(task: String): PumpStation
    {
        this.systemTask = task
        return this
    }

    /**
     * Sets the user guidelines - secondary after [systemTask]. Traditional
     * "skills" would be injected here.
     *
     * @param guidelines The user guidelines text.
     * @return This PumpStation instance for method chaining.
     */
    fun setUserGuidelines(guidelines: String): PumpStation
    {
        this.userGuidelines = guidelines
        return this
    }

    /**
     * Sets the entry user prompt - the third-tier initial user prompt sent to the harness.
     *
     * @param prompt The entry user prompt text.
     * @return This PumpStation instance for method chaining.
     */
    fun setEntryUserPrompt(prompt: String): PumpStation
    {
        this.entryUserPrompt = prompt
        return this
    }

//---------------------------------------DITL Function Setters-----------------------------------------------------

    /**
     * Sets the DITL function invoked at the very beginning of harness runtime.
     *
     * @param func The pre-init function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPreInitFunction(func: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)?): PumpStation
    {
        this.preInitFunction = func
        return this
    }

    /**
     * Sets the pre-validation DITL function for the judge agent.
     *
     * @param func The pre-validation judge function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPreValidationJudgeFunction(func: (suspend (MultimodalContent, MiniBank, PumpStation) -> MiniBank)?): PumpStation
    {
        this.preValidationJudgeFunction = func
        return this
    }

    /**
     * Sets the post-judge DITL function. Runs immediately after the judge agent exits.
     *
     * @param func The post-judge function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPostJudgeFunction(func: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)?): PumpStation
    {
        this.postJudgeFunction = func
        return this
    }

    /**
     * Sets the pre-validation DITL function for the dispatch agent.
     *
     * @param func The pre-validation dispatch function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPreValidationDispatchFunction(func: (suspend (MultimodalContent, ContextWindow, MiniBank, PumpStation) -> MiniBank)?): PumpStation
    {
        this.preValidationDispatchFunction = func
        return this
    }

    /**
     * Sets the DITL function invoked just prior to the judge agent. Returning false
     * can shut down the harness loop.
     *
     * @param func The pre-invoke function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPreInvokeFunction(func: (suspend (ContextWindow, MiniBank, PumpStation) -> Boolean)?): PumpStation
    {
        this.preInvokeFunction = func
        return this
    }

    /**
     * Sets the DITL function invoked to check path safety on a high-risk path call.
     *
     * @param func The path-safety function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPathSafetyFunction(func: (suspend (PathObject, String, PumpStation) -> Boolean)?): PumpStation
    {
        this.pathSafetyFunction = func
        return this
    }

    /**
     * Sets the DITL function invoked after the dispatch agent has generated its path output.
     *
     * @param func The post-generate function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPostGenerateFunction(func: (suspend (MultimodalContent, PumpStation) -> P2PInterface)?): PumpStation
    {
        this.postGenerateFunction = func
        return this
    }

    /**
     * Sets the DITL function invoked after the path has fully executed. If false,
     * an error is raised and the branch failure recovery is attempted.
     *
     * @param func The path-validation function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPathValidationFunction(func: (suspend (MultimodalContent, PumpStation) -> Boolean)?): PumpStation
    {
        this.pathValidationFunction = func
        return this
    }

    /**
     * Sets the DITL function for content transformation after a path executes
     * and just before results are injected into the harness history.
     *
     * @param func The path-transformation function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPathTransformationFunction(func: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)?): PumpStation
    {
        this.pathTransformationFunction = func
        return this
    }

    /**
     * Sets the DITL function that executes after memory agents complete a memory update task.
     *
     * @param func The post-memory function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPostMemoryFunction(func: (suspend (MultimodalContent, PumpStation) -> MultimodalContent)?): PumpStation
    {
        this.postMemoryFunction = func
        return this
    }

    /**
     * Sets the DITL function that fires when a memory blowout has been detected.
     *
     * @param func The pre-compaction function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPreCompactionFunction(func: (suspend (MultimodalContent, ConverseData, ConverseHistory, PumpStation) -> MultimodalContent)?): PumpStation
    {
        this.preCompactionFunction = func
        return this
    }

    /**
     * Sets the DITL function that fires after a TPipe emergency compaction/memory event happens.
     *
     * @param func The post-compaction function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPostCompactionFunction(func: (suspend (MultimodalContent, ConverseHistory, PumpStation) -> MultimodalContent)?): PumpStation
    {
        this.postCompactionFunction = func
        return this
    }

    /**
     * Sets the function that fires any time an agent has an internal context truncation
     * due to token budgeting.
     *
     * @param func The on-context-truncated function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setOnContextTruncated(func: (suspend (wasTruncated: Boolean, remainingFreeSpace: Int) -> Unit)?): PumpStation
    {
        this.onContextTruncated = func
        return this
    }

//---------------------------------------Misc Setters--------------------------------------------------------------

    /**
     * Sets the DITL function invoked when [maxTotalPathCallsPerPath] is exceeded.
     * Allows dynamic runtime policy instead of static [PathLimitExceededPolicy].
     *
     * @param func The path-limit-exceeded function, or null to clear the binding.
     * @return This PumpStation instance for method chaining.
     */
    fun setPathLimitExceededFunction(func: (suspend (PathObject, String, PumpStation) -> PathLimitExceededResult)?): PumpStation
    {
        this.pathLimitExceededFunction = func
        return this
    }

    /**
     * Sets the failure recovery policy controlling dispatch JSON repair, stash behavior,
     * and intervention triggers.
     *
     * @param policy The failure policy.
     * @return This PumpStation instance for method chaining.
     */
    fun setFailurePolicy(policy: PumpStationFailurePolicy): PumpStation
    {
        // failurePolicy is a public val on this class, so we copy the fields of the
        // incoming policy into the existing instance rather than reassigning.
        this.failurePolicy.repairInvalidDispatchJson = policy.repairInvalidDispatchJson
        this.failurePolicy.maxDispatchRepairAttempts = policy.maxDispatchRepairAttempts
        this.failurePolicy.stashOversizedOutputs = policy.stashOversizedOutputs
        this.failurePolicy.callInterventionOnPathFailure = policy.callInterventionOnPathFailure
        this.failurePolicy.stopHarnessOnInvalidPathRequest = policy.stopHarnessOnInvalidPathRequest
        this.failurePolicy.requirePathSelectionRationale = policy.requirePathSelectionRationale
        this.requirePathSelectionRationale = policy.requirePathSelectionRationale
        return this
    }

//---------------------------------------Harness Agent List Setters------------------------------------------------

    /**
     * Appends an additional harness agent. Each agent is invoked between the dispatch
     * output and the return to the judge agent, in the order added.
     *
     * @param agent The harness agent to add.
     * @param concurrency The concurrency mode (Blocking by default).
     * @return This PumpStation instance for method chaining.
     */
    fun addHarnessAgent(agent: P2PInterface, concurrency: PumpStationConcurrencyMode = PumpStationConcurrencyMode.Blocking): PumpStation
    {
        this.additionalHarnessAgentSlots.add(HarnessAgentSlot(agent = agent, concurrency = concurrency))
        return this
    }

    /**
     * Appends an additional harness agent builder function. Each builder is invoked
     * between the dispatch output and the return to the judge agent, in the order added.
     * When invoked at runtime, the produced agent is stored in the slot and then initialized.
     *
     * @param fn The builder function to add.
     * @param concurrency The concurrency mode (Async by default).
     * @return This PumpStation instance for method chaining.
     */
    fun addHarnessAgentBuilder(
        fn: (suspend (harness: PumpStation) -> P2PInterface),
        concurrency: PumpStationConcurrencyMode = PumpStationConcurrencyMode.Async
    ): PumpStation
    {
        this.additionalHarnessAgentSlots.add(
            HarnessAgentSlot(agent = null, concurrency = concurrency, builderFunction = fn)
        )
        return this
    }

    /**
     * Clears all additional harness agent slots (both direct agents and builder slots).
     *
     * @return This PumpStation instance for method chaining.
     */
    fun clearHarnessAgents(): PumpStation
    {
        this.additionalHarnessAgentSlots.clear()
        return this
    }

    /**
     * Removes only those additional harness agent slots that contain a builder function,
     * leaving slots that were added via [addHarnessAgent] intact.
     *
     * @return This PumpStation instance for method chaining.
     */
    fun clearHarnessAgentBuilders(): PumpStation
    {
        this.additionalHarnessAgentSlots.removeAll { it.builderFunction != null }
        return this
    }

    /**
     * Returns an immutable snapshot of the additional harness agent slots.
     */
    fun getAdditionalHarnessAgentSlots(): List<HarnessAgentSlot> = additionalHarnessAgentSlots.toList()

//---------------------------------------Reserve Path Mutator-------------------------------------------------------

    /**
     * Adds a path to the reserve list. The path's parent is set to this station.
     * Reserve paths are only visible to the dispatch agent when their [PathObject.revealWhen]
     * predicate returns true.
     *
     * @param path The PathObject to add to reserve.
     * @return This PumpStation instance for method chaining.
     */
    fun addReservePath(path: PathObject): PumpStation
    {
        path.setParentInterface(this)
        path.killSwitch = _killSwitch
        reservePaths[pathKey(path.pathName)] = path
        return this
    }

}
