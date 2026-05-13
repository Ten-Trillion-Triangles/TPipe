package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.TruncationSettings
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
 * Core object class that is embedded into the [PumpStation] class. A PathObject is a special container for harness
 * calls. It comprises execution functions, internal agents, memory management, and PCP tool calls. It effectively
 * encapsulates the concept of a turn in a traditional agent harness and fully encloses the complexities that would
 * otherwise make the harness pattern inefficient.
 */
class PathObject(override var killSwitch: KillSwitch? = null) : P2PInterface
{

//============================================== Properties ============================================================
    /**
     * Configurable var to define the max number of concurrent agents allowed to be spawned. Acts as a passthrough
     * and a hint. This allows someone building a path object to abide by constraints or user requests and config
     * settings.
     */
    private var maxConcurrentAgents = 3

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
    private var executionFunction: (suspend (content: MultimodalContent, stationRef: PumpStation,  turnHistory: ConverseHistory?, turnSummary: String) -> MultimodalContent)? = null




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
     * REQUIRED: This agent judges if the given harness task is considered complete or not. Once completed,
     * the judge agent can shut down the harness and return the result.
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
     * Optional background lorebook agent. Invoked as the first background agent in the harness if present.
     * Is used to update the lorebook of the [PumpStation] internal context window/minibank.
     */
    private var lorebookAgent: P2PInterface? = null

    /**
     * Bindable builder function. This will spawn a brand-new agent at every point of invocation and execution
     * to ensure a thread safe, and stateless implementation. If not assigned, the PumpStation will attempt to
     * duplicate the agent using reflection.
     */
    private var lorebookAgentBuilderFunction : (suspend () -> P2PInterface)? = null

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
    private var summaryAgentBuilderFunction: (suspend (content: MultimodalContent) -> P2PInterface)? = null

    /**
     * Allows the user to add additional required agents between the output of dispatch, and the return to the judge
     * agent. Each agent will be invoked in the order that they are assigned to this list.
     */
    private var additionalHarnessAgents: MutableList<P2PInterface> = mutableListOf()

    /**
     * Alternate set of bindable builder functions. When invoked each will be invoked in order.
     * If this is bound, it will override the [additionalHarnessAgents] variable.
     */
    private var additionalHarnessAgentBuilderFuncList: MutableList<(suspend () -> P2PInterface)>? = null

//--------------------------------------------------Config--------------------------------------------------------------

    /**
     * Exceeding this number will instantly end the harness. Acts as a safety limit to avoid llm loops and
     * exploding token costs.
     */
    private var maxHarnessTurns = 50

    /**
     * Defines the maximum number of concurrent background agents that can be spawned at any given time.
     * If a spawn request would exceed this number it will be queued and batched out at the maximun number
     * allowed at a given time.
     */
    private var maxConcurrentBackgroundAgents = 3

    /**
     * Defines the max number of foreground agents that can be spawned by path calls, or by the dispatch agent.
     * This is passed into the path object and acts as hint the coder can abide by to constrain max agent concurrency.
     */
    private var maxConcurrentForegroundAgents = 3

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

//--------------------------------------------------Internal------------------------------------------------------------

    /**
     * Stored turn history. The entire history is shown to the harness agent after the summary is provided if
     * the summary is present. The judge and dispatch agents will use this to determine task status, and which path
     * to traverse next in the harness loop.
     */
    val turnHistory = ConverseHistory()

    /**
     * Internal mechanism to safely save and store outputs that might cause errors, or blowout the context window
     * of an agent. When the stache is saved to, the turn is replaced by a customizable message that can instruct
     * the harness agents. The map consists of a string based Id that is definable, and can be retrieved automatically
     * by a path designed and equipped to handle a stache situation.
     */
    private val stache = mutableMapOf<String, ConverseData>()

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

    

}