package com.TTT.Pipeline

import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.Context.MiniBank
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.TruncationSettings
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.Util.serialize
import kotlinx.coroutines.sync.Mutex

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
    val runsInBackground: Boolean,
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
 */
@kotlinx.serialization.Serializable
data class PathRequest(
    var pathName: String = "",
    var pathSchema: String = ""
)

/**
 * Core object class that is embedded into the [PumpStation] class. A PathObject is a special container for harness
 * calls. It comprises execution functions, internal agents, memory management, and PCP tool calls. It effectively
 * encapsulates the concept of a turn in a traditional agent harness and fully encloses the complexities that would
 * otherwise make the harness pattern inefficient.
 */
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
    private var runsInBackground = false

    /**
     * Must be set, or pulled from the parent [PumpStation]. This required for us to calculate if we're about to
     * blow out a context window.
     */
    private var parentTokenBudgetSettings: TokenBudgetSettings? = null

    /**
     * Optional internal agent. Stored as a P2P interface to allow any possible TPipe agent type to be stored internally
     * this includes embedding another [PumpStation] inside the path object that can be called by an outer PumpStation.
     * When assigned, the agent builder function will be skipped over.
     */
    private var internalAgent : P2PInterface? = null

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


//-----------------------------------------------------init--------------------------------------------------------

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
            runsInBackground = runsInBackground,
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
     * P2PInterface required init function. Delegates to [init] for path initialization.
     * Present to satisfy the [P2PInterface] contract.
     */
    override suspend fun P2PInit()
    {
        init()
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
 * Includes killswitch, turn limits, and token budgeting for cost control.
 *
 * Is able to automate its own config and apply core defaults internally.
 *
 * Includes full dsl support.
 *
 * Is also a p2p interface so a harness can be part of the path of another harness.
 */
class PumpStation(override var killSwitch: KillSwitch? = null) : P2PInterface
{
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
    private var judgeAgent: P2PInterface? = null

    /**
     * Optional builder function to generate the [judgeAgent] on the fly. When non-null this will completely
     * override whatever is set for judgeAgent.
     */
    private var judgeAgentBuilderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null

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
    private var dispatchAgent: P2PInterface? = null

    /**
     * Optional builder function to generate [dispatchAgent] on the fly, overrides any value set to dispatch agent
     * when not null.
     */
    private var dispatchAgentBuilderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null


    /**
     * Optional agent that is able to intervene with path calls. Can be enabled for things like enforcing specific
     * retries when a path has an error, requring an agent to call a specific path under a specific condtition etc.
     * When this fires it will investigate its assignment, and
     */
    private var interventionAgent: P2PInterface? = null

    /**
     * Optional builder function for the intervention agent that overrides [interventionAgent] at runtime each
     * time it would be called.
     */
    private var interventionAgentBuilderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null

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
     * agent. Each agent will be invoked in the order that they are assigned to this list.
     */
    private var additionalHarnessAgents: MutableList<P2PInterface> = mutableListOf()

    /**
     * Alternate set of bindable builder functions. When invoked each will be invoked in order.
     * If this is bound, it will override the [additionalHarnessAgents] variable.
     */
    private var additionalHarnessAgentBuilderFuncList: MutableList<(suspend (harness: PumpStation) -> P2PInterface)>? = null

    /**
     * Optional goal agent. This agent can be used to scan the work done by the harness once the harness is in an
     * exit state. If the agent fires [MultimodalContent.terminatePipeline] this will be treated as a failure state
     * and can be used to return back to the judge, and the dispatcher agent to force work to resume.
     *
     * This can be seen as effectively the same as a ralph loop in terms of enforcement.
     */
    private var goalAgent: P2PInterface? = null


    /**
     * Optional bindable builder function. Allows for a dynamically generated agent at runtime. If non-null
     * [goalAgent] will be ignored and this will be invoked to generate the valid agent object at runtime.
     */
    private var goalAgentBuilderFunction: (suspend (harness: PumpStation) -> P2PInterface)? = null

    /**
     * Stored paths on this harness. Each path is mapped by its name from inside the path object, and the
     * reference to the object. Names are normalized to be case-insensitive, and all path calls will normalize
     * to lowercase when calling a path.
     */
    private val pathList: MutableMap<String, PathObject> = mutableMapOf()

    /**
     * Paths stored here are placed in "reserve". This allows them to be loaded into the system prompt of the
     * dispatch agent dynamically which is useful for keeping token costs and usage under control. A path cannot
     * exist both in reserve, and in the main [pathList] at the same time.
     */
    private val reservePaths: MutableMap<String, PathObject> = mutableMapOf()



//--------------------------------------------------Config--------------------------------------------------------------

    /**
     * Treated as the "system prompt" for the harness. Is injected into the harness after initial backed in harness
     * system instructions that are used to teach the agents how to drive the harness. Treated as highest priority
     * after the harness core guidelines of driving and always present regardless of user instructions.
     */
    private var systemTask = ""

    /**
     * Secondary after [systemTask] these are user guidelines the judge and dispatch agents should follow as long as they
     * are able to still fully follow their system task. This is where traditional "skills" in other harnesses would
     * be injected.
     */
    private var userGuidelines = ""

    /**
     * Third tier down. This is the initial user prompt sent to this harness via the [MultimodalContent] input or
     * P2P [P2PInterface.executeLocal] invocation. This is the core task of work to be done within the constraints
     * of both the core system prompts of the agents, the system task which is injected second, and the userGuidelines
     * aka: "skills" third. This will always be held at the top of the history and made clear to the agent that this
     * is the core task it must complete within its boundaries.
     */
    private var entryUserPrompt = ""

    /**
     * Exceeding this number will instantly end the harness. Acts as a safety limit to avoid llm loops and
     * exploding token costs.
     */
    private var maxHarnessTurns = 50

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
    private var turnSummary = ""

    /**
     * If true, and the dispatch agent generates invalid json for a path request, throw an error, and
     * exit the PumpStation harness on the spot.
     */
    private var stopHarnessOnInvalidPathRequest = false

//--------------------------------------------------Internal------------------------------------------------------------

    /**
     * Used to track init state and auto-init if the user forgot to invoke init at execution time.
     */
    private var harnessIsReady = false

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
     * Internal context window addressable by this harness, and able to be passed into the various agents
     * that are deployed here.
     */
    private val contextWindow = ContextWindow()

    /**
     * Internal miniBank serves the same purpose as [contextWindow]
     */
    private val miniBank = MiniBank()

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



//---------------------------------------------------DITL---------------------------------------------------------------

    /**
     * Optional agent that fies prior to starting the harness. This agent can be used for any initial setup
     * or states that need to be handled prior to giving the task to the judge, and dispatch agents.
     */
    private var preInitAgent: P2PInterface? = null

    /**
     * If bound, the preInit agent will be spawned by this function, and executed as a fresh copy. This avoids
     * stale states, and stateful agents if desired.
     */
    private var preInitAgentBuilder: (suspend () -> P2PInterface)? = null

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
     * Pre-validation DITL call for the dispatch agent. Invoked prior to running the dispatch agent. Works the same way
     * as the other pre-validation function in PumpStation.
     */
    private var preValidationDispatchFunction: (suspend (content: MultimodalContent, context: MiniBank, harness: PumpStation) -> MiniBank)? = null

    /**
     * DITL function invoked just prior to the judge agent. Allows the developer to decide to shut down and end the
     * PumpStation harness loop based on logic.
     */
    private var preInvokeFunction: (suspend (turnState: ContextWindow, harness: PumpStation) -> Boolean)? = null

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
//===========================================P2PInterface Implementation==============================================

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
    {
        // PumpStation does not support direct P2P execution yet, but will upon true completion.
        return null
    }

    /**
     * P2PInterface required init function. Initializes the PumpStation harness.
     * Present to satisfy the [P2PInterface] contract.
     */
    override suspend fun P2PInit()
    {
        // TODO: PumpStation init — wire in judge/dispatch agents initialization when needed
    }

    /**
     * Fetch all paths in this harness, serialize them to be ready for injection into a pipe, and return
     * them as a string.
     */
    override fun getPaths(): String
    {
        val schema = serialize(pathList)
        return schema
    }

    override fun getContextWindowFromInterface(): ContextWindow?
    {
        return contextWindow
    }

    override fun getMiniBankFromInterface(): MiniBank?
    {
        return miniBank
    }

}