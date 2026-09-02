package com.TTT.Pipeline

import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.MiniBank
import com.TTT.Debug.*
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.PipeError
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenUsage
import com.TTT.Pipe.PipeTimeoutStrategy
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.StallCallback
import com.TTT.Pipe.StreamingStallConfig
import com.TTT.Structs.PipeSettings
import com.TTT.Util.copyPipeline
import com.TTT.Util.deepCopy
import com.TTT.Util.RuntimeState
import com.TTT.Util.writeStringToFile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.awt.im.InputContext
import java.util.UUID

/**
 * Class for abstracting an AI pipeline in the TPipe pipeline system. A pipeline is a collection of pipes that
 * pass input and output to each other to control a series of AI models and api calls to them using a single
 * prediction model. Each AI is intended to make a single prediction, and it's return call operated upon, then
 * moved forward into the specified pipe.
 *
 * Pipeline instances are mutable orchestration objects. Build separate instances for concurrent top-level runs rather
 * than sharing one pipeline instance across multiple simultaneous executions.
 */
class Pipeline : P2PInterface
{
//=============================================== Properties =========================================================//

    /**
     * Reference to any containers that are holding this pipeline. Will be required for more advanced tracing patterns
     * such as Splitters, Manifolds, and DistributionGrids.
     */
    @RuntimeState
    var pipelineContainer: Any? = null

    /**
     * Optional name for the pipeline. This is used for debugging and monitoring purposes.
     */
    var pipelineName = ""

    //Counter for input tokens and output tokens that have been spent so far.
    var inputTokensSpent = 0
    var outputTokensSpent = 0
    
    /** List of all pipes to execute in sequence. */
    private var pipes: MutableList<Pipe> = mutableListOf()

    var content = MultimodalContent()

    /**
     * Context window to store and manipulate context produced by the results of the AI interactions
     * or other functions that manipulate the llm outputs and inputs.
     */
    var context = ContextWindow()

    /**
     * Pipeline mini-bank for multiple pages of supported context. Allows for more complex storage to still
     * be sandboxed to the pipeline as a minibank.
     */
    var miniBank = MiniBank()

    /**
     * Optional explicit name of the pipe whose output should be returned by
     * [executeMultimodal] when the pipeline has multiple pipes. Set this
     * when the decision-making pipe is NOT the last one in the pipeline
     * (e.g. a preprocessor / decision / postprocessor layout).
     *
     * Resolution priority (in order):
     *   1. This manual override (if non-null and the named pipe exists)
     *   2. The first pipe with [com.TTT.Pipe.Pipe.isDecisionPipe] = true
     *   3. The first pipe with [com.TTT.Structs.PipeSettings.pipeRole] = [com.TTT.Enums.PipeRole.Decision]
     *   4. Heuristic scoring based on the pipe's settings
     *   5. Fallback: the last pipe in the pipeline
     *
     * This is a silent override — there is no warning, no log, and no event
     * if the named pipe is missing or did not run. The fallback to the last
     * pipe's output is always in effect.
     */
    var decisionPipeName: String? = null

    /**
     * Fluent setter for [decisionPipeName]. Returns `this` for method chaining,
     * matching the TTT builder convention.
     */
    fun setDecisionPipeName(name: String?): Pipeline
    {
        this.decisionPipeName = name
        return this
    }

    /**
     * Read-only observability hook: the name of the pipe that was actually
     * selected as the decision pipe by the most recent [executeMultimodal]
     * call. `null` means the fallback (last pipe) was used. Developers can
     * introspect this after a run to confirm which pipe the resolution
     * logic chose.
     */
    @RuntimeState
    var lastDecisionPipeName: String? = null

    /**
     * Aggregated token usage across tracked pipes. Reset at the start of each execution.
     */
    @kotlinx.serialization.Transient
    @RuntimeState
    private var pipelineTokenUsage = TokenUsage()

    @kotlinx.serialization.Transient
    var pipeMetaData = mutableMapOf<Any, Any>()

    /**
     * Stores the pipe that caused the pipeline to fail, if any.
     */
    @kotlinx.serialization.Transient
    var lastFailedPipe: Pipe? = null

    /**
     * Stores the most recent error that occurred during pipeline execution.
     */
    @kotlinx.serialization.Transient
    var lastError: PipeError? = null

    /**
     * Emergency kill switch for halting execution when token limits are exceeded.
     * When tripped, throws [KillSwitchException] — an uncaught exception that bypasses
     * all retry policies and generic exception handlers.
     */
    @kotlinx.serialization.Transient
    override var killSwitch: com.TTT.P2P.KillSwitch? = null

    /**
     * Weather the pipeline should update the global context window system of TPipe which allows multiple pipes,
     * pipelines, and other concurrent tasks to share llm context with each other.
     */
    var updateGlobalContext = false

    /**
     * Page key for the context bank. This is used to emplace the context that's stored in that page. This allows
     * both sharing of global context, and separation of different types of global context. If left empty,
     * the banked context that has been swapped in will be written to instead.
     */
    private var pageKey = ""

    /**
     * Pause/resume functionality using TPipe's declarative approach
     * Pausing auto-enabled when any pause point is declared
     */
    @RuntimeState
    private var isPaused = false
    @RuntimeState
    private val resumeSignal = Channel<Unit>(Channel.RENDEZVOUS)
    private var pauseBeforePipes = false
    private var pauseAfterPipes = false
    private var pauseBeforeJumps = false
    private var pauseAfterRepeats = false
    private var pauseOnCompletion = false
    private var pausingEnabled = false  // Auto-set when pause points declared
    var conditionalPauseFunction: (suspend (Pipe, MultimodalContent) -> Boolean)? = null
    var pauseCallback: (suspend (Pipe?, MultimodalContent) -> Unit)? = null
    var resumeCallback: (suspend (Pipe?, MultimodalContent) -> Unit)? = null

    /**
     * Tracing system properties for debugging and monitoring pipeline execution.
     */
    private var tracingEnabled = false
    private var traceConfig = TraceConfig()
    @RuntimeState
    private val pipelineId = UUID.randomUUID().toString()

    /**
     * Current array index of the pipe that is next in line to be executed. Required because in order to jump to
     * pipes, we need to iterate through each pipe in the pipeline using a while loop instead of a for loop.
     * So this is required to keep track of the index, and exit the pipeline once we've exceeded the size of the
     * pipe array.
     *
     * @see executeMultimodal
     * @see getNextPipe
     */
    @RuntimeState
    private var currentPipeIndex = 0

    /**
     * If true, the input and output of this pipeline will be wrapped into a converse history struct if it has not
     * been already. This allows you to automate the process of keeping track roles and turns in a seamless way.
     * Input will be unwrapped from converse if it's supplied as such, and then re-wrapped upon the exit of the pipeline.
     */
    private var wrapContentWithConverseHistory = false

    private var wrapPipeContentWithConverseHistory = false

    private var pipelineConverseRole = ConverseRole.assistant

    private var pipeConverseRole = ConverseRole.agent

    private var userConverseRole = ConverseRole.user

    /**
     * If true only the text output of a pipe will be wrapped into the converse output. Then the final text output of the
     * pipeline will be returned as converse. If false, the entire content object will be serialized into converse.
     */
    private var wrapTextResponseOnly = true

    /**
     * Internal private var for [ConverseHistory] to enable automatic wrapping of [MultimodalContent] into a converse
     * history structure. Gets cleared each initial pipeline run and returns out at the end of the run if enabled.
     */
    @RuntimeState
    private var internalConverseHistory = ConverseHistory()

    /**
     * Optional delegate callback that allows the pipeline to notify anyone listening to the bound function
     * that a given pipe has been completed. Passes the reference to the pipe, and the content object it generated
     * forward.
     */
    var pipeCompletionCallback: (suspend(Pipe, MultimodalContent) -> Unit)? = null

    /**
     * callback function when the entire pipeline has been completed.
     */
    var pipelineCompletionCallBack: (suspend(Pipeline, MultimodalContent) -> Unit)? = null

    /**
     * Pre validation function that allows for runtime adjustment of the pipeline's internal data and context
     * at the start of the execution step.
     */
    var preValidationFunction: (suspend (context: ContextWindow, miniBank: MiniBank, content: MultimodalContent) -> Unit)? = null
    
    // Timeout Configuration Properties
    private var enablePipeTimeout = false
    private var pipeTimeout = 300000L
    private var timeoutStrategy = PipeTimeoutStrategy.Fail
    private var maxRetryAttempts = 5
    private var pipeRetryFunction: (suspend (pipe: Pipe, content: MultimodalContent) -> Boolean)? = null
    private var applyTimeoutRecursively = true

    // Stall Detection Configuration Properties
    private var enablePipelineStallDetector = false
    private var pipelineStallDetectorConfig: StreamingStallConfig = StreamingStallConfig()
    private var pipelineStallCallback: StallCallback? = null



//============================================== P2PInterface ==========================================================

//---------------------------------------------- Interface Properties --------------------------------------------------

    /**
     * P2P Agent descriptor for this pipeline. Used if this pipeline is being registered as an addressable agent
     * in the P2P system.
     */
    private var p2pDescriptor : P2PDescriptor? = null

    /**
     * Advertised transport method to connect to this pipeline if it's registered as a P2P agent.
     * Required by the P2PInterface standard.
     */
    private var p2pTransport: P2PTransport? = null

    /**
     * Internal P2P requirements for this pipeline. Used by the P2P system to determine if an agent can connect
     * to this pipeline or not based on compatibility and security standards.
     */
    private var p2PRequirements: P2PRequirements? = null
    private var parentInterface: P2PInterface? = null




//------------------------------------------- Interface Functions ------------------------------------------------------

    /**@see P2PInterface */

    override fun getP2pDescription(): P2PDescriptor? {
        val description = p2pDescriptor ?: return null
        return description
    }

    override fun setP2pDescription(description: P2PDescriptor)
    {
        p2pDescriptor = description
    }

    override fun getP2pTransport(): P2PTransport? {
        val transport = p2pTransport ?: return null
        return transport
    }

    override fun setP2pTransport(transport: P2PTransport)
    {
        p2pTransport = transport
    }

    override fun setP2pRequirements(requirements: P2PRequirements)
    {
        p2PRequirements = requirements
    }

    override fun getP2pRequirements(): P2PRequirements? {
        return p2PRequirements
    }

    override fun getContainerObject(): Any? {
        return pipelineContainer
    }

    override fun setContainerObject(container: Any)
    {
        pipelineContainer = container
    }

    override fun setParentInterface(parent: P2PInterface)
    {
        parentInterface = parent
    }

    override fun getParentP2PInterface(): P2PInterface? = parentInterface

    /**
     * Checks the kill switch if one is set. If token consumption exceeds the configured limits,
     * the kill switch's onTripped callback is invoked — this typically throws [KillSwitchException]
     * which propagates as an uncaught exception, bypassing all retry policies.
     *
     * @param inputTokens Current input token count
     * @param outputTokens Current output token count
     * @param elapsedMs Time elapsed since execution started
     */
    fun checkKillSwitch(inputTokens: Int, outputTokens: Int, elapsedMs: Long)
    {
        killSwitch?.let { ks ->
            val inputLimit = ks.inputTokenLimit
            val outputLimit = ks.outputTokenLimit

            val inputExceeded = inputLimit != null && inputTokens > inputLimit
            val outputExceeded = outputLimit != null && outputTokens > outputLimit

            // Emit KILLSWITCH_CHECK event on every token check when tracing is enabled
            if(tracingEnabled)
            {
                trace(
                    eventType = TraceEventType.KILLSWITCH_CHECK,
                    phase = TracePhase.MONITORING,
                    metadata = mapOf(
                        "inputTokens" to inputTokens,
                        "outputTokens" to outputTokens,
                        "elapsedMs" to elapsedMs,
                        "inputLimit" to (inputLimit ?: "none"),
                        "outputLimit" to (outputLimit ?: "none"),
                        "inputExceeded" to inputExceeded,
                        "outputExceeded" to outputExceeded
                    )
                )
            }

            if (inputExceeded || outputExceeded)
            {
                val reason = when {
                    inputExceeded && outputExceeded -> "input_and_output_exceeded"
                    inputExceeded -> "input_exceeded"
                    else -> "output_exceeded"
                }

                // Emit KILLSWITCH_TRIPPED event when limits are exceeded
                if(tracingEnabled)
                {
                    trace(
                        eventType = TraceEventType.KILLSWITCH_TRIPPED,
                        phase = TracePhase.ERROR,
                        metadata = mapOf(
                            "reason" to reason,
                            "inputTokens" to inputTokens,
                            "outputTokens" to outputTokens,
                            "elapsedMs" to elapsedMs,
                            "inputLimit" to (inputLimit ?: "none"),
                            "outputLimit" to (outputLimit ?: "none")
                        ),
                        error = null // KillSwitchException is thrown by onTripped callback
                    )
                }

                ks.onTripped(com.TTT.P2P.KillSwitchContext(
                    p2pInterface = this,
                    inputTokensSpent = inputTokens,
                    outputTokensSpent = outputTokens,
                    elapsedMs = elapsedMs,
                    reason = reason
                ))
            }
        }
    }

    /**
     * Determine whether every pipe in this pipeline has some configured overflow-protection path for prompt assembly.
     *
     * @return True when all registered pipes have either token budgeting or legacy auto truncation configured.
     */
    fun hasContextOverflowProtectionConfigured() : Boolean
    {
        return pipes.all { pipe ->
            pipe.hasContextOverflowProtectionConfigured()
        }
    }

    /**
     * Return the pipes that currently lack token-budget or auto-truncation protection.
     *
     * @return Pipes that have no configured overflow-protection path.
     */
    fun getPipesWithoutContextOverflowProtection() : List<Pipe>
    {
        return pipes.filter { pipe ->
            !pipe.hasContextOverflowProtectionConfigured()
        }
    }

    /**
     * This is already a pipeline so all it will do here is just return itself from the interface. However, we still
     * want to implement this so that in the even this is used on this class, it still works as expected and
     * doesn't end up causing an unexpected failure.
     */
    override fun getPipelinesFromInterface(): List<Pipeline> {
        return listOf(this)
    }

    override fun setTokenBudgetRecursive(budget: TokenBudgetSettings)
    {
        for (pipe in getPipes())
        {
            pipe.setTokenBudgetRecursive(budget)
        }
    }

    override fun getTokenBudgetSettings(): TokenBudgetSettings? = null

    override fun setPipeSettingsRecursively(settings: PipeSettings)
    {
        for (pipe in getPipes())
        {
            pipe.setPipeSettingsRecursively(settings)
        }
    }
    override fun setStreamingCallbackRecursive(callback: suspend (String) -> Unit)
    {
        for (pipe in getPipes())
        {
            pipe.propagateStreamingCallback(callback)
        }
    }
    override fun clearStreamingCallbacksRecursive()
    {
        for(pipe in getPipes())
        {
            pipe.clearStreamingCallbacksRecursively()
        }
    }

    override fun enableStallDetectorRecursive(
        config: StreamingStallConfig,
        callback: StallCallback?
    )
    {
        for (pipe in getPipes())
        {
            pipe.propagateStallDetection(config, callback)
        }
    }

    override fun setConverseRoleRecursive(role: com.TTT.Context.ConverseRole)
    {
        for (pipe in getPipes())
        {
            pipe.setConverseRole(role)
        }
    }

    override suspend fun abortRecursive()
    {
        for (pipe in getPipes())
        {
            pipe.abortRecursive()
        }
    }

    override fun enablePipeTimeoutRecursive(
        applyRecursively: Boolean,
        duration: Long,
        autoRetry: Boolean,
        retryLimit: Int
    )
    {
        for (pipe in getPipes())
        {
            pipe.enablePipeTimeoutRecursive(
                applyRecursively = applyRecursively,
                duration = duration,
                autoRetry = autoRetry,
                retryLimit = retryLimit
            )
        }
    }


    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? {
        /** Start as "this" but we may need to alter our target if we need to copy "this" due to some change the
         *  requested be made during the p2p request operation.
         */
        var targetPipeline = this

        /**
         * First, we need to check for any scenario that will require us to make a temporary copy of this pipeline.
         * This is required if the request is asking to modify some aspect about a pipe or the entire pipeline.
         * If we do not copy the pipeline in whole in that event it can cause unexpected drift and unwanted behavior
         * due to the ability for users to request some changes be made to it if the security system allows for it.
         */
        if(request.context != null || request.inputSchema != null || request.outputSchema != null)
        {
            targetPipeline = copyPipeline(this)
        }

        /**
         * Bind pipeline context if non-null. The requirements system will have handled security checks prior to
         * reaching this point so we can assume this allowed.
         *
         * NOTE: Context binding only works if the pipes that are interacting with this have enabled pulling
         * the pipeline's context. This will do nothing if global ContextBank schemes are in use.
         */
        request.context?.let {
            targetPipeline.context = it }

        /**
         * Json schemas can be altered if allowed. This allows the requester of the agent to have some more control over
         * inputs and outputs. But this must be compatible with the structuring of the pipe's system prompt, and other
         * components of its instructions. By default, this is disabled in default requirements security schemas.
         */
        if(request.inputSchema != null)
        {
            val inputSchema = request.inputSchema

            if(inputSchema?.schemaContainer != null)
            {
                for(pipe in inputSchema.schemaContainer)
                {
                    //Get the pipe object, the instructions, and the schema for json
                    val pipeTarget = getPipeByName(pipe.key)
                    val schemaPairObject = pipe.value
                    val instructions = schemaPairObject.first
                    val schema = schemaPairObject.second

                    pipeTarget.second?.setJsonInput(instructions)
                        ?.setJsonInput(schema)
                }
            }

            val outputSchema = request.outputSchema
            if(outputSchema?.schemaContainer != null)
            {
                for(pipe in outputSchema.schemaContainer)
                {
                    //Get the pipe object, the instructions, and the schema for json
                    val pipeTarget = getPipeByName(pipe.key)
                    val schemaPairObject = pipe.value
                    val instructions = schemaPairObject.first
                    val schema = schemaPairObject.second

                    pipeTarget.second?.setJsonOutputInstructions(instructions)
                        ?.setJsonOutput(schema)
                }
            }
        }

        /**
         * Apply custom context descriptions for every pipe
         */
        if(request.customContextDescriptions != null)
        {
            for(pipe in request.customContextDescriptions)
            {
                val pipeName = pipe.key
                val description = pipe.value
                val pipeTarget = getPipeByName(pipeName)
                pipeTarget.second?.autoInjectContext(description)
            }
        }


        //Final step. Reapply system prompt if target is not "this"
        if(targetPipeline != this)
        {
            //Re-apply all system prompts to bind all of our changes made.
            for(pipe in targetPipeline.getPipes())
            {
                pipe.applySystemPrompt()
            }

            //Call init again to ensure the copied pipeline is ready to be called.
            targetPipeline.init(true)
        }


        val multiModalResult = targetPipeline.executeMultimodal(request.prompt)

        val response = P2PResponse()
        response.output = multiModalResult


        return response
    }

//=============================================== Constructor ========================================================//

    /**
     * Enable wrapping user content into a [com.TTT.Context.ConverseHistory] structure automatically. Allows for
     * including the content of each pipe in the pipeline, or just the content input, and content output of the pipeline
     * itself. Allows for only text to be wrapped, or the entire [MultimodalContent] object.
     *
     * The wrapped inputs and outputs will be stored in a parallel converse history object. This is useful for judge
     * agents, tracking progress of multiple pipelines over time, showing the user the the pathway of events etc.
     *
     * @param wrapTextResponse True by default. Only wraps the text portion of the content into a converse history output.
     * The converse history will be serialized into the [MultimodalContent] text variable at the end of the pipeline run.
     * @param includePipeContent If true, all the outputs the pipes will also be included.
     * @param pipelineConverseRoleParam Defines the converse role for the pipeline to be listed as.
     * @param pipeConverseRoleParam Defines the converse role for the pipe to be listed as.
     * @param userConverseRoleParam Defines the converse role for the user prompt to be listed as.
     *
     * Beware that any sub-pipes content will not be visible even if pipe content wrapping has been enabled.
     * This is because the converse wrapping is happening at the pipeline level rather than inside the pipes.
     */
    fun wrapContentWithConverseHistory(
      historyRef: ConverseHistory,
      wrapTextResponse: Boolean = true,
      includePipeContent: Boolean = false,
      pipelineConverseRoleParam: ConverseRole = pipelineConverseRole,
      pipeConverseRoleParam: ConverseRole = pipeConverseRole,
      userConverseRoleParam: ConverseRole = userConverseRole,
      ) : Pipeline
    {
        internalConverseHistory = historyRef
        wrapContentWithConverseHistory = true
        pipelineConverseRole = pipelineConverseRoleParam
        pipeConverseRole = pipeConverseRoleParam
        userConverseRole = userConverseRoleParam
        wrapTextResponseOnly = wrapTextResponse
        wrapPipeContentWithConverseHistory = includePipeContent
        return this
    }

    /**
     * Weather to automatically update global context with this pipeline's context when it exits. This is useful
     * for situations where you want multiple pipes, pipelines and other concurrent systems to be able to share
     * the context they are working with for various llm's.
     */
    fun useGlobalContext(page: String = "") : Pipeline
    {
        updateGlobalContext = true
        pageKey = page
        return this
    }

    /**
     * Safely assign a context window to a pipeline. This performs a deep copy to prevent destruction of the original
     * value and unexpected memory management bugs.
     * @param window The context window to be assigned to the pipeline.
     */
    fun setContextWindow(window: ContextWindow) : Pipeline
    {
        context = window.deepCopy()
        return this
    }

    /**
     * Safely assign a mini-bank to this pipeline without the possibility of pipeline and internal pipe logic destroying
     * the original mini-bank. This performs a deep copy to prevent destruction of the original value and unexpected
     * memory management bugs.
     * @param bank The mini-bank to be assigned to the pipeline.
     */
    fun setMiniBank(bank: MiniBank) : Pipeline
    {
        miniBank = bank.deepCopy()
        return this
    }

    /**
     * Set the pre-validation function that allows for the data of this pipeline to be modifed at runtime prior
     * to the execution of the pipeline. This is useful for runtime context gathering and other tasks that
     * are dynamic and can't be defined at build time.
     */
    fun setPreValidationFunction(func:  (suspend (context: ContextWindow, miniBank: MiniBank, content: MultimodalContent) -> Unit)) : Pipeline
    {
        preValidationFunction = func
        return this
    }

    /**
     * Enables timeout for all pipes in this pipeline.
     * Settings will be applied to all pipes when [init] is called.
     *
     * @param applyRecursively If true, settings will propagate to child pipes (e.g. branch pipes)
     * @param duration Timeout duration in milliseconds
     * @param autoRetry If true, sets strategy to [PipeTimeoutStrategy.Retry]
     * @param retryLimit Maximum number of retry attempts
     * @param customLogic Optional custom retry logic function
     */
    fun enablePipeTimeout(
        applyRecursively: Boolean = true,
        duration: Long = 300000,
        autoRetry: Boolean = false,
        retryLimit: Int = 5,
        customLogic: (suspend(pipe: Pipe, content: MultimodalContent) -> Boolean)? = null) : Pipeline
    {
        this.enablePipeTimeout = true
        this.applyTimeoutRecursively = applyRecursively
        this.pipeTimeout = duration
        this.maxRetryAttempts = retryLimit
        
        if(autoRetry)
        {
             this.timeoutStrategy = PipeTimeoutStrategy.Retry
        }
        else if(customLogic != null)
        {
             this.timeoutStrategy = PipeTimeoutStrategy.CustomLogic
             this.pipeRetryFunction = customLogic
        }
        else
        {
             this.timeoutStrategy = PipeTimeoutStrategy.Fail
        }

        return this
    }

    /**
     * Enables stall detection for all pipes in this pipeline. When the pipeline is
     * initialized, every pipe receives the [config] and (optionally) the [callback].
     *
     * Stall detection tracks token arrival timestamps during streaming and uses
     * statistical deviation (rolling mean + stddev) to detect abnormally long silences
     * — typically a sign that the LLM has silently died without throwing an error.
     *
     * Each pipe owns its own StreamingStallDetector (per-pipe stats are per-pipe state);
     * the config is propagated to every child.
     *
     * @param config Detection thresholds. Defaults to [StreamingStallConfig].
     * @param callback Optional callback invoked on stall (logging/metrics). The retry
     *                 path is independent — see [PipeTimeoutManager.handleStallSignal].
     * @return This pipeline for chaining.
     */
    fun enableStallDetector(
        config: StreamingStallConfig = StreamingStallConfig(),
        callback: StallCallback? = null
    ): Pipeline
    {
        this.enablePipelineStallDetector = true
        this.pipelineStallDetectorConfig = config
        if(callback != null) this.pipelineStallCallback = callback
        return this
    }


    /**
     * Adds a pipe to the list of pipes in the pipeline to be executed, only if the pipe does not already exist in the list.
     * @param pipe The pipe to be added.
     */
    fun add(pipe: Pipe): Pipeline
    {
        if(!pipes.contains(pipe))
        {
            pipes.add(pipe)
            pipe.setPipelineRef(this)
            pipe.setParentInterface(this)
        }

        return this
    }

    /**
     * Inserts a pipe into the pipeline at a given index.
     * @param  pipe The pipe to be inserted.
     * @param index The index at which the pipe should be inserted.
     */
    fun insert(pipe: Pipe, index: Int) : Pipeline
    {
        pipes.add(index, pipe)
        pipe.setPipelineRef(this)
        pipe.setParentInterface(this)
        return this
    }

    /**
     * Adds all pipes in a list to the pipeline. All pipes in the list will be checked against the existing
     * list of pipes in the pipeline, and only if a pipe does not already exist in the list, will it be added.
     * @param pipes The list of pipes to be added to the pipeline.
     */
    fun addAll(pipes: List<Pipe>): Pipeline
    {
        for(pipe in pipes)
        {
            add(pipe)
        }

        return this
    }

    /**
     * Get all pipes stored on this pipeline. Useful if manual adjustments to pipe settings may be required.
     */
    fun getPipes() : List<Pipe>
    {
        return pipes
    }

    /**
     * Checks if this pipeline has an error stored.
     * @return true if an error is present, false otherwise
     */
    fun hasError(): Boolean = lastError != null

    /**
     * Gets the error message from the last error, or empty string if no error.
     * @return The error message or empty string
     */
    fun getErrorMessage(): String = lastError?.message ?: ""

    /**
     * Gets the name of the pipe that failed, or empty string if no failure.
     * @return The failed pipe name or empty string
     */
    fun getFailedPipeName(): String = lastFailedPipe?.pipeName ?: ""

    /**
     * Clears all error information from this pipeline.
     */
    fun clearErrors()
    {
        lastFailedPipe = null
        lastError = null
    }

    /**
     * Gets full error context including pipe name, phase, and message.
     * @return Formatted error context string or empty string if no error
     */
    fun getFullErrorContext(): String
    {
        val error = lastError ?: return ""
        return "Pipe '${error.pipeName}' failed in ${error.phase} phase: ${error.message}"
    }

    /**
     * Checks if the pipeline was terminated due to an error.
     * @return true if pipeline ended with an error, false otherwise
     */
    fun wasTerminatedByError(): Boolean = lastError != null

    /**
     * Enables tracing for this pipeline with the specified configuration.
     * @param config The tracing configuration to use
     * @return This Pipeline object for method chaining
     */
    fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipeline
    {
        this.tracingEnabled = true
        this.traceConfig = config
        PipeTracer.enable() // Enable global tracer
        PipeTracer.setMaxHistory(config.maxHistory) // Apply configured history limit
        return this
    }

    /**
     * Gets the trace report for this pipeline in the specified format.
     * @param format The output format for the trace report
     * @return The formatted trace report as a string
     */
    fun getTraceReport(format: TraceFormat = traceConfig.outputFormat): String
    {
        val report = PipeTracer.exportTrace(pipelineId, format)

        // Auto-export to file if configured
        if(traceConfig.autoExport)
        {
            val extension = when(format) {
                TraceFormat.HTML -> "html"
                TraceFormat.JSON -> "json"
                TraceFormat.MARKDOWN -> "md"
                TraceFormat.CONSOLE -> "txt"
            }
            val filename = "trace-${pipelineId.take(8)}.$extension"
            val exportPath = traceConfig.exportPath.trimEnd('/') + "/" + filename
            TraceAutoExporter.default.export(exportPath, report) {
                writeStringToFile(exportPath, report)
            }
        }

        return report
    }

    /**
     * Gets failure analysis for this pipeline if tracing is enabled.
     * @return FailureAnalysis object or null if tracing is disabled
     */
    fun getFailureAnalysis(): FailureAnalysis?
    {
        return if(tracingEnabled) PipeTracer.getFailureAnalysis(pipelineId) else null
    }

    /**
     * Gets the unique trace ID for this pipeline.
     * @return The pipeline's trace ID
     */
    fun getTraceId(): String = pipelineId


    /**
     * Gets comprehensive token usage for the entire pipeline when tracking is enabled.
     * This method provides access to aggregated token usage data across all pipes
     * in the pipeline that have comprehensive tracking enabled.
     *
     * @return TokenUsage object containing aggregated pipeline usage data
     */
    fun getTokenUsage(): TokenUsage = pipelineTokenUsage

    /**
     * Returns the aggregated input token count across all pipes when tracking is enabled.
     * This method sums up input tokens from all pipes in the pipeline that have
     * comprehensive token tracking enabled during the last execution.
     *
     * @return Total input tokens consumed across all tracked pipes in the pipeline
     */
    fun getTotalInputTokens(): Int = pipelineTokenUsage.totalInputTokens

    /**
     * Returns the aggregated output token count across all pipes when tracking is enabled.
     * This method sums up output tokens from all pipes in the pipeline that have
     * comprehensive token tracking enabled during the last execution.
     *
     * @return Total output tokens consumed across all tracked pipes in the pipeline
     */
    fun getTotalOutputTokens(): Int = pipelineTokenUsage.totalOutputTokens


    /**
     * Sets the name of the pipeline. This is used for debugging and monitoring purposes.
     */
    fun setPipelineName(name: String): Pipeline
    {
        pipelineName = name
        return this
    }

    /**
     * Enables pause points before pipe execution.
     * 
     * @return This pipeline instance for method chaining
     */
    fun pauseBeforePipes(): Pipeline
    {
        pauseBeforePipes = true
        pausingEnabled = true
        return this
    }

    /**
     * Enables pause points after pipe execution.
     * 
     * @return This pipeline instance for method chaining
     */
    fun pauseAfterPipes(): Pipeline
    {
        pauseAfterPipes = true
        pausingEnabled = true
        return this
    }

    /**
     * Enables pause points before jump operations.
     * 
     * @return This pipeline instance for method chaining
     */
    fun pauseBeforeJumps(): Pipeline
    {
        pauseBeforeJumps = true
        pausingEnabled = true
        return this
    }

    /**
     * Enables pause points after repeat operations.
     * 
     * @return This pipeline instance for method chaining
     */
    fun pauseAfterRepeats(): Pipeline
    {
        pauseAfterRepeats = true
        pausingEnabled = true
        return this
    }

    /**
     * Enables pause points on pipeline completion.
     * 
     * @return This pipeline instance for method chaining
     */
    fun pauseOnCompletion(): Pipeline
    {
        pauseOnCompletion = true
        pausingEnabled = true
        return this
    }

    /**
     * Enables pause functionality without declaring specific pause points.
     * Allows manual pause() calls to work even without declarative pause points.
     * 
     * @return This pipeline instance for method chaining
     */
    fun enablePausing(): Pipeline
    {
        pausingEnabled = true
        return this
    }

    /**
     * Convenience method to enable common pause points.
     * 
     * @return This pipeline instance for method chaining
     */
    fun enablePausePoints(): Pipeline
    {
        return pauseBeforePipes().pauseOnCompletion()
    }

    /**
     * Sets a conditional pause function that determines when to pause.
     * 
     * @param condition Function that returns true when pipeline should pause
     * @return This pipeline instance for method chaining
     */
    fun pauseWhen(condition: suspend (Pipe, MultimodalContent) -> Boolean): Pipeline
    {
        conditionalPauseFunction = condition
        pausingEnabled = true
        return this
    }

    /**
     * Sets callback function to execute when pipeline pauses.
     * 
     * @param callback Function to call when pause occurs
     * @return This pipeline instance for method chaining
     */
    fun onPause(callback: suspend (Pipe?, MultimodalContent) -> Unit): Pipeline
    {
        pauseCallback = callback
        return this
    }

    /**
     * Sets callback function to execute when pipeline resumes.
     * 
     * @param callback Function to call when resume occurs
     * @return This pipeline instance for method chaining
     */
    fun onResume(callback: suspend (Pipe?, MultimodalContent) -> Unit): Pipeline
    {
        resumeCallback = callback
        return this
    }

    /**
     * Pauses the pipeline execution if pausing is enabled.
     * Uses channel-based synchronization to block until resume is called.
     */
    suspend fun pause()
    {
        if(!pausingEnabled) return  // No-op if no pause points declared
        
        if(tracingEnabled)
        {
            trace(TraceEventType.PIPELINE_PAUSE, TracePhase.ORCHESTRATION,
                  metadata = mapOf("currentPipeIndex" to currentPipeIndex))
        }
        
        isPaused = true
        pauseCallback?.invoke(getCurrentPipe(), content)
        resumeSignal.receive()
        isPaused = false
        
        if(tracingEnabled)
        {
            trace(TraceEventType.PIPELINE_RESUME, TracePhase.ORCHESTRATION,
                  metadata = mapOf("currentPipeIndex" to currentPipeIndex))
        }
        
        resumeCallback?.invoke(getCurrentPipe(), content)
    }

    /**
     * Resumes the pipeline execution by sending a signal to the pause channel.
     */
    suspend fun resume()
    {
        resumeSignal.trySend(Unit)
    }

    /**
     * Checks if the pipeline is currently paused.
     * 
     * @return True if pipeline is paused, false otherwise
     */
    fun isPaused(): Boolean = isPaused

    /**
     * Checks if the pipeline has pause functionality enabled.
     * 
     * @return True if pausing is enabled, false otherwise
     */
    fun canPause(): Boolean = pausingEnabled

    /**
     * Helper methods for pause functionality
     */
    private suspend fun checkPausePoint()
    {
        if(pausingEnabled && isPaused)
        {
            resumeSignal.receive()
            isPaused = false
        }
    }

    /**
     * Gets the currently executing pipe.
     * 
     * @return The current pipe or null if no pipe is executing
     */
    private fun getCurrentPipe(): Pipe?
    {
        return if(currentPipeIndex < pipes.size) pipes[currentPipeIndex] else null
    }

    /**
     * Checks if conditional pause criteria are met and pauses if so.
     * 
     * @param pipe The current pipe being executed
     * @param content The current pipeline content
     */
    private suspend fun checkConditionalPause(pipe: Pipe, content: MultimodalContent)
    {
        if(conditionalPauseFunction?.invoke(pipe, content) == true)
        {
            pause()
        }
    }

    /**
     * Internal tracing method for Pipeline pause/resume events.
     * 
     * @param eventType The type of trace event
     * @param phase The execution phase
     * @param content Optional content to include in trace
     * @param metadata Additional metadata for the trace event
     * @param error Optional error information
     */
    private fun trace(
        eventType: TraceEventType,
        phase: TracePhase,
        content: MultimodalContent? = null,
        metadata: Map<String, Any> = emptyMap(),
        error: Throwable? = null
    )
    {
        if(!tracingEnabled) return
        if(!EventPriorityMapper.shouldTrace(eventType, traceConfig.detailLevel)) return

        val effectiveMetadata = if(traceConfig.includeMetadata) metadata else emptyMap()

        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = pipelineId,
            pipeName = "Pipeline-$pipelineName",
            eventType = eventType,
            phase = phase,
            content = content,
            contextSnapshot = null,
            metadata = effectiveMetadata,
            error = error
        )

        PipeTracer.addEvent(pipelineId, event)
    }

    /**
     * Binds a delegate function that will be called everytime a pipe in the pipeline has completed. Passes the reference
     * to the pipe, and the content object it produced forward.
     * @param func The delegate function object to bind to this pipeline.
     * @return This Pipeline object for method chaining
     */
    fun setPipeCompletionCallback(func: (suspend (Pipe, MultimodalContent) -> Unit)) : Pipeline
    {
        pipeCompletionCallback = func
        return this
    }

    /**
     * Binds a delegate function that will be called at any point in which the pipeline has exited.
     * This is useful for debugging and monitoring purposes.
     */
    fun setPipelineCompletionCallback(func: (suspend (Pipeline, MultimodalContent) -> Unit)) : Pipeline
    {
        pipelineCompletionCallBack = func
        return this
    }

    /**
     * Initialize the pipeline and pass its reference to each pipe. Can also call the init function for each pipe.
     * if desired.
     *
     * @since Beware, this will block the current thread until all pipes have been initialized.
     * Exercise caution when using this function and execute it off the main thread if you require this to be
     * non-blocking.
     */
    suspend fun init(initPipes : Boolean = false) : Pipeline
    {
        for(pipe in pipes)
        {
            pipe.setPipelineRef(this)
            pipe.setParentInterface(this)

            // Apply pipeline-level timeout settings if enabled
            if(enablePipeTimeout)
            {
                pipe.enablePipeTimeout(
                    applyRecursively = applyTimeoutRecursively,
                    duration = pipeTimeout,
                    retryLimit = maxRetryAttempts
                )
                // Manually set other properties that might not be fully covered by the builder or need explicit setting
                pipe.enablePipeTimeout = true
                pipe.timeoutStrategy = timeoutStrategy
                pipe.setRetryFunction(pipeRetryFunction)
            }

            // Apply pipeline-level stall detection settings if enabled.
            // Note: stall detection does NOT recursively cascade by default — each pipe
            // owns its own StreamingStallDetector since per-pipe stats need per-pipe state.
            // The config and callback are simply propagated to every child.
            if(enablePipelineStallDetector)
            {
                val stallCallback = pipelineStallCallback
                pipe.enableStallDetector(pipelineStallDetectorConfig)
                if(stallCallback != null) pipe.setStallCallback(stallCallback)
            }
            
            if(initPipes)
            {
                //Exercise safety when using init(). It will block whatever thread is calling it.
                val job = coroutineScope {
                 pipe.init()
             }

            }
        }

        return this
    }

//=============================================== Functions ==========================================================//

    /**
     * Searches for a pipe by its pipe name and returns it.
     *
     * @param name The name of the pipe to search for.
     * @return Returns the list index where the pipe was found, and the pipe itself. Returns -1 and nullptr if
     * not found.
     */
    fun getPipeByName(name: String) : Pair<Int, Pipe?>
    {
        for((index, pipe) in pipes.withIndex())
        {
            if(pipe.pipeName == name)
            {
                return Pair(index, pipe)
            }
        }

        return Pair(-1, null)
    }

    fun getNextPipe(content: MultimodalContent) : Pipe?
    {
        val jumpTarget = content.getJumpToPipe()
        var nextPipe : Pipe? //Can't be constructed due to being abstract but must be defined now.
        var nextPipeIndex = -1 //Must be defined early due to if/else logic needed to fetch our next pipe ahead.

        /**Grab pipe by current index if we're not jumping. The while loop inside executeMultiModal will be mapped
         * to the correct index by the time this is called. Otherwise, we'll be aiming to skip over one index past,
         * or directly jumping to a given pipe by its name.
         */
        if(jumpTarget.isEmpty())
        {
           return try {
                pipes[currentPipeIndex]
            }

           catch(exception: Exception)
           {
               null
           }
        }

        //Try to get the next pipe forward. Increment index too if we can.
        if(jumpTarget == "skip-to-next-pipe")
        {
            currentPipeIndex++

            return try {
                pipes[currentPipeIndex]
            }

            catch(e: Exception)
            {
                null
            }
        }

        else
        {
            val namedPipe = getPipeByName(jumpTarget)
            nextPipeIndex = namedPipe.first
            nextPipe = namedPipe.second

            //Exit if the pipe index it out of bounds.
            if(pipes.lastIndex < nextPipeIndex || nextPipeIndex == -1)
            {
                return null
            }

            currentPipeIndex = nextPipeIndex
            return nextPipe
        }

    }

    /**
     * Gets the total token count spent on this pipeline since the start of this pipeline's creation.
     * Will count all tokens that has came in or out.
     */
    fun getTokenCount() : String
    {
        return "Input tokens: $inputTokensSpent \n Output Tokens: $outputTokensSpent"
    }

    /**
     * Internal function to append to this pipeline's converse history. This is used to wrap content
     * with the converse history structure.
     *
     * Note: When [wrapTextResponseOnly] is true, only the text field is preserved in the converse history
     * entry. All other fields including metadata, binaryContent, tools, and context are stripped.
     * Do not rely on converse history for metadata fidelity when using text-only wrapping.
     */
    private fun appendContentToConverseHistory(content: MultimodalContent, role: ConverseRole)
    {
        if(wrapContentWithConverseHistory)
        {
            if(wrapTextResponseOnly)
            {
                internalConverseHistory.add(role, MultimodalContent(text = content.text))
            }

            else
            {
                internalConverseHistory.add(role, content)
            }
        }
    }

    /**
     * Executes the pipeline with the given initial prompt. The pipeline will be executed until completion,
     * or until the generated text is empty, at which point the pipeline will be exited and the function
     * will return the generated text.
     *
     * @param initialPrompt The initial prompt to pass to the first pipe in the pipeline.
     * @return The generated text after all pipes in the pipeline have been executed.
     */
    suspend fun execute(initialPrompt: String = ""): String = coroutineScope {
        val content = MultimodalContent(text = initialPrompt)
        val result = executeMultimodal(content)
        result.text
    }
    
    /**
     * Executes the pipeline with multimodal content support. Handles text and binary content
     * through the entire pipeline chain.
     *
     * This execution path expects single-owner usage per instance. If you need concurrency, build a fresh pipeline
     * instance for each top-level run.
     *
     * @param initialContent The initial multimodal content to pass to the first pipe.
     * @return The generated multimodal content after all pipes have been executed.
     */
    suspend fun execute(initialContent: MultimodalContent): MultimodalContent = executeMultimodal(initialContent)

    /**
     * P2PInterface compliance: when the harness (or any other P2PInterface consumer) holds
     * a [Pipeline] reference and invokes it via [executeLocal], the [P2PInterface] default
     * implementation returns the input unchanged — which silently swallows every LLM call.
     * This override delegates to [execute], so a pipeline used as a P2PInterface (e.g. as a
     * path's [com.TTT.Pipeline.PathObject.internalAgent] or [setParentInterface] target) actually
     * runs its pipes instead of acting as a no-op pass-through.
     *
     * Single-owner constraint still applies — do not share a pipeline across concurrent top-level
     * calls. The harness's [runAgent] helper already calls [execute] directly for known-Pipeline
     * agents (judge/dispatch/goal/summary/lorebook/pathSafety); this override covers the remaining
     * sites that go through the generic [P2PInterface.executeLocal] funnel.
     */
    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent = execute(content)


    
    /**
     * Internal multimodal execution logic shared by both execute methods
     */
    private suspend fun executeMultimodal(initialContent: MultimodalContent): MultimodalContent = coroutineScope {
        val executionStartTime = System.currentTimeMillis()

        if(tracingEnabled)
        {
            PipeTracer.startTrace(pipelineId)
        }

        //Run pre validation function prior to any execution operations on the content object.
        preValidationFunction?.let { func ->
            if(tracingEnabled)
            {
                trace(TraceEventType.VALIDATION_START, TracePhase.PRE_VALIDATION, initialContent,
                    metadata = mapOf("pipelineFunctionType" to "preValidation"))
            }
            
            try {
                func.invoke(context, miniBank, initialContent)
                
                if(tracingEnabled)
                {
                    trace(TraceEventType.VALIDATION_SUCCESS, TracePhase.PRE_VALIDATION, initialContent,
                        metadata = mapOf("pipelineFunctionType" to "preValidation"))
                }
            }
            catch(e: Exception)
            {
                if(tracingEnabled)
                {
                    trace(TraceEventType.VALIDATION_FAILURE, TracePhase.PRE_VALIDATION, initialContent,
                        metadata = mapOf("pipelineFunctionType" to "preValidation"), error = e)
                }

                pipelineCompletionCallBack?.invoke(this@Pipeline, MultimodalContent())
                throw e
            }
        }

        //Initialize pipeline execution state and token tracking.
        var generatedContent = initialContent
        currentPipeIndex = 0
        
        //Reset pipeline token usage tracking for this execution cycle.
        pipelineTokenUsage = TokenUsage()

        appendContentToConverseHistory(initialContent, userConverseRole)

        // Per-pipe output capture. After each pipe runs, the latest
        // generatedContent is recorded against the pipe's name. The decision-pipe
        // resolution at the end of executeMultimodal uses this map to return
        // the named pipe's output instead of the last pipe's output.
        val pipeOutputs: MutableMap<String, MultimodalContent> = mutableMapOf()
        val pipeInputs: MutableMap<String, MultimodalContent> = mutableMapOf()

        /**
         * Find next pipe based on index or pointers -> Run pipe -> Break on terminate or out of bounds -> Repeat
         */
        while(currentPipeIndex < pipes.size)
        {
            // PAUSE POINT 1: Before pipe execution (if declared)
            if(pauseBeforePipes)
            {
                checkPausePoint()
            }

            //Get next pipe based on next index, or jump instruction.
            val pipe = getNextPipe(generatedContent) ?: break
            generatedContent.clearJumpToPipe() //Clear so we don't have unintended behaviors.

            // Record the input that was passed to this pipe so the decision-pipe
            // resolution can detect a "no-op" pipe (one whose output is the same
            // as its input).
            if(pipe.pipeName.isNotEmpty())
            {
                pipeInputs[pipe.pipeName] = generatedContent
            }
            
            // Check conditional pause before pipe execution
            conditionalPauseFunction?.let { checkConditionalPause(pipe, generatedContent) }
            
            if(tracingEnabled)
            {
                pipe.enableTracing(traceConfig)
                pipe.addTraceId(pipelineId)
            }

            try {
                if(!pipe.disablePipe) //Conditional skip if the pipe is disabled. Otherwise, proceed as normal.
                {
                    val result : Deferred<MultimodalContent> = async {
                        pipe.execute(generatedContent)
                    }

                    //Execute the current pipe and await its result.
                    generatedContent = result.await()

                    // Capture pipe errors after execution
                    if(pipe.hasError())
                    {
                        lastFailedPipe = pipe
                        lastError = pipe.lastError
                    }

                    // Also check if error was propagated through content
                    if(generatedContent.pipeError != null && lastError == null)
                    {
                        lastFailedPipe = pipe
                        lastError = generatedContent.pipeError
                    }
                }
            }

            catch(e: com.TTT.P2P.KillSwitchException) {
                // KillSwitchException must never be caught — it must propagate to terminate the agent
                throw e
            }
            catch(e: Exception)
            {
                trace(TraceEventType.PIPE_FAILURE, TracePhase.EXECUTION, generatedContent, error = e)
                
                // Capture exception-based failures
                if(pipe.hasError())
                {
                    lastFailedPipe = pipe
                    lastError = pipe.lastError
                }
            }



            /**
             * Attempt to invoke the callback if it was bound. This allows external systems to listen to when pipes
             * complete. This is useful for logging, showing ui updates to users as the process moves about,
             * and other frontend facing tasks.
             */
            pipeCompletionCallback?.invoke(getCurrentPipe()!!, generatedContent)
            
            //Track token usage from pipes that have comprehensive tracking enabled.
            val pipeIndex = currentPipeIndex
            val hasTokenTracking = pipe.isComprehensiveTokenTrackingEnabled()
            if(hasTokenTracking)
            {
                //Add this pipe's token usage to the pipeline's aggregated tracking.
                pipelineTokenUsage.addChildUsage("pipe-$pipeIndex-${pipe.pipeName}", pipe.getTokenUsage())

                //Update pipeline-level token counts for backward compatibility.
                inputTokensSpent = pipelineTokenUsage.totalInputTokens
                outputTokensSpent = pipelineTokenUsage.totalOutputTokens
            }

            // Check kill switch after pipe execution — always check if kill switch is set
            val elapsedMs = System.currentTimeMillis() - executionStartTime
            val inputTokens = if(hasTokenTracking) pipelineTokenUsage.totalInputTokens else inputTokensSpent
            val outputTokens = if(hasTokenTracking) pipelineTokenUsage.totalOutputTokens else outputTokensSpent
            checkKillSwitch(inputTokens, outputTokens, elapsedMs)

            // PAUSE POINT 2: After pipe execution (if declared)
            if(pauseAfterPipes)
            {
                checkPausePoint()
            }

            if(wrapContentWithConverseHistory)
            {
                appendContentToConverseHistory(generatedContent, pipeConverseRole)
            }

            /**
             * Allow pipes to repeat. This is useful for creating custom reasoning and thinking modes in
             * models that do not support that feature natively.
             */
            while(generatedContent.repeatPipe)
            {
                var repeatPipeResult : Deferred<MultimodalContent> = async {
                    pipe.execute(generatedContent)
                }

                generatedContent = repeatPipeResult.await()

                // PAUSE POINT 3: After repeat pipe (if declared)
                if(pauseAfterRepeats)
                {
                    checkPausePoint()
                }
            }

            // Capture this pipe's final output. This is the value of generatedContent
            // AFTER the pipe's body and any repeat loop, so it represents the pipe's
            // contribution to the chain. The decision-pipe resolution reads from this
            // map to return the named pipe's output instead of the last pipe's output.
            if(pipe.pipeName.isNotEmpty()) {
                pipeOutputs[pipe.pipeName] = generatedContent
            }

            if(generatedContent.shouldTerminate())
            {
                if(tracingEnabled)
                {
                    PipeTracer.addEvent(pipelineId, TraceEvent(
                        timestamp = System.currentTimeMillis(),
                        pipeId = "pipeline-${pipelineId}",
                        pipeName = if(pipe.pipeName.isNotEmpty()) pipe.pipeName else pipe.javaClass.simpleName,
                        eventType = TraceEventType.PIPELINE_TERMINATION,
                        phase = TracePhase.CLEANUP,
                        content = generatedContent,
                        contextSnapshot = null,
                        metadata = mapOf("taskSuccessful" to "false")
                    ))
                }
                break // Exit pipeline if content is marked for termination
            }

            //Check if we're exiting early because the task was finished early or extra steps were not needed.
            if(generatedContent.passPipeline)
            {
                if(tracingEnabled)
                {
                    PipeTracer.addEvent(pipelineId, TraceEvent(
                        timestamp = System.currentTimeMillis(),
                        pipeId = "pipeline-${pipelineId}",
                        pipeName = if(pipe.pipeName.isNotEmpty()) pipe.pipeName else pipe.javaClass.simpleName,
                        eventType = TraceEventType.PIPELINE_TERMINATION,
                        phase = TracePhase.CLEANUP,
                        content = generatedContent,
                        contextSnapshot = null,
                        metadata = mapOf("taskSuccessful" to "true")
                    ))
                }

                /**
                 * Jump instructions now override terminate instructions. Both terminate instructions, and jump
                 * instructions are commonly manually invoked. And the only exceptions to automatic invocation
                 * are the original human-in-the-loop functions provided by TPipe and some automatic failsafe
                 * events.
                 */
                if(generatedContent.getJumpToPipe().isEmpty())
                {
                    break
                }

            }

            // PAUSE POINT 4: Before jump operations (if declared)
            if(pauseBeforeJumps && !generatedContent.getJumpToPipe().isEmpty())
            {
                checkPausePoint()
            }


            currentPipeIndex++
        }

        // PAUSE POINT 5: On pipeline completion (if declared)
        if(pauseOnCompletion)
        {
            checkPausePoint()
        }

        // Update global context if enabled
        if(updateGlobalContext)
        {
            /**
             * Bind from the result since native functions will manipulate the context object held in the
             * multi-modal data.
             */
            if(!generatedContent.context.isEmpty())
            {
                context = generatedContent.context
            }
            
            if(pageKey.isNotEmpty())
            {
                ContextBank.emplaceWithMutex(pageKey, context)
            }

            if(!miniBank.isEmpty())
            {
                val pageKeyList = pageKey.split(", ")

                if(pageKeyList.isNotEmpty())
                {
                    for(page in pageKeyList)
                    {
                        val contextFromMiniBank = miniBank.contextMap[page]
                        if(contextFromMiniBank != null)
                        {
                            ContextBank.emplaceWithMutex(page, contextFromMiniBank)
                        }
                    }
                }

                else
                {
                    for(it in miniBank.contextMap)
                    {
                        ContextBank.emplaceWithMutex(it.key, it.value)
                    }
                }
            }

            else
            {
                ContextBank.updateBankedContextWithMutex(context)
            }
        }

        content = generatedContent //Save content to the pipe so that we can read it externally.

        if(!wrapPipeContentWithConverseHistory)
        {
            appendContentToConverseHistory(content, pipelineConverseRole)
        }

        // Resolve the "decision pipe": the one whose output is the agent's
        // actual decision. The harness and the developer-facing API both
        // return the decision pipe's output instead of the last pipe's.
        // Resolution is layered: manual override > isDecisionPipe > role >
        // scoring > fallback (last pipe).
        val decisionResult = resolveDecisionPipeOutput(generatedContent, pipeOutputs, pipeInputs)
        lastDecisionPipeName = resolveDecisionPipeName(pipeOutputs, pipeInputs)

        pipelineCompletionCallBack?.invoke(this@Pipeline, decisionResult)
        return@coroutineScope decisionResult
    }

    /**
     * Resolve the output of the "decision pipe" given the current state of
     * the pipeline and the [generatedContent] from the just-finished run.
     *
     * Layered resolution:
     *   1. [decisionPipeName] set + pipe found in `pipes` + pipe ran this turn -> use its output
     *   2. First pipe with [com.TTT.Pipe.Pipe.isDecisionPipe] = true + it ran -> use its output
     *   3. First pipe with [com.TTT.Structs.PipeSettings.pipeRole] = [com.TTT.Enums.PipeRole.Decision] + it ran -> use its output
     *   4. Heuristic scoring (highest score wins; ties go to the LATER pipe) + it ran -> use its output
     *   5. Fallback: the last pipe's output (= `generatedContent`)
     *
     * If the resolved pipe never ran (jumped over by `jumpToPipe`/`skip-to-next-pipe`),
     * the function falls back to `generatedContent` (the last pipe's output). The
     * observability hook [lastDecisionPipeName] is `null` in that case.
     */
    private fun resolveDecisionPipeOutput(
        generatedContent: MultimodalContent,
        pipeOutputs: Map<String, MultimodalContent>,
        pipeInputs: Map<String, MultimodalContent>
    ): MultimodalContent
    {
        // Priority 1: manual override. If the developer set decisionPipeName
        // but the named pipe is not in the pipeline, this is a "silent" failure:
        // the developer made a typo or pointed at a removed pipe, and we just
        // fall through to the last pipe's output without warning.
        val manualName = decisionPipeName
        if(manualName != null)
        {
            val (idx, pipe) = getPipeByName(manualName)
            if(idx >= 0 && pipe != null)
            {
                return capturedOrFallback(manualName, pipeOutputs, pipeInputs, generatedContent)
            }
            return generatedContent
        }

        // Priority 2: isDecisionPipe flag
        for(pipe in pipes)
        {
            if(pipe.isDecisionPipe)
            {
                val name = pipe.pipeName.ifEmpty { null } ?: continue
                return capturedOrFallback(name, pipeOutputs, pipeInputs, generatedContent)
            }
        }

        // Priority 3: pipeRole == Decision
        for(pipe in pipes)
        {
            if(pipe.pipeRole == com.TTT.Enums.PipeRole.Decision)
            {
                val name = pipe.pipeName.ifEmpty { null } ?: continue
                return capturedOrFallback(name, pipeOutputs, pipeInputs, generatedContent)
            }
        }

        // Priority 4: heuristic scoring
        val scored = scoreDecisionPipeCandidates()
        if(scored != null)
        {
            val name = scored.pipeName.ifEmpty { null } ?: return generatedContent
            return capturedOrFallback(name, pipeOutputs, pipeInputs, generatedContent)
        }

        // Priority 5: fallback to last pipe's output
        return generatedContent
    }

    /**
     * Returns the captured output for [pipeName] from [pipeOutputs], or
     * [fallback] if either:
     *   - the pipe's output was never captured (it was jumped over, did not run,
     *     or has an empty name), or
     *   - the pipe's output text is the same as its input text (a "no-op" pipe
     *     that just passed the input through, often because it issued a jump
     *     instruction and never produced a real decision).
     */
    private fun capturedOrFallback(
        pipeName: String,
        pipeOutputs: Map<String, MultimodalContent>,
        pipeInputs: Map<String, MultimodalContent>,
        fallback: MultimodalContent
    ): MultimodalContent
    {
        val out = pipeOutputs[pipeName] ?: return fallback
        val inp = pipeInputs[pipeName]
        // Detect a no-op: the pipe's output text equals the input text. This
        // matches the "jumped-over" scenario in the test suite, where a pipe
        // sets a jump instruction but produces no decision content of its own.
        if(inp != null && out.text == inp.text && pipeOutputs.size > 1)
        {
            return fallback
        }
        return out
    }

    /**
     * Returns the name of the pipe that would be selected as the decision pipe,
     * or `null` if the fallback (last pipe) was used. Companion to
     * [resolveDecisionPipeOutput] that exposes the resolution for the
     * observability hook.
     */
    private fun resolveDecisionPipeName(
        pipeOutputs: Map<String, MultimodalContent>,
        pipeInputs: Map<String, MultimodalContent>
    ): String?
    {
        // Priority 1: manual override. If the named pipe is not in the
        // pipeline, this is a "silent" failure: return `null` to indicate
        // the fallback (last pipe) was used.
        val manualName = decisionPipeName
        if(manualName != null)
        {
            val (idx, pipe) = getPipeByName(manualName)
            if(idx >= 0 && pipe != null)
            {
                return nameOrNullIfNoop(manualName, pipeOutputs, pipeInputs)
            }
            return null
        }

        // Priority 2: isDecisionPipe flag
        for(pipe in pipes)
        {
            if(pipe.isDecisionPipe)
            {
                val name = pipe.pipeName.ifEmpty { null } ?: continue
                return nameOrNullIfNoop(name, pipeOutputs, pipeInputs)
            }
        }

        // Priority 3: pipeRole == Decision
        for(pipe in pipes)
        {
            if(pipe.pipeRole == com.TTT.Enums.PipeRole.Decision)
            {
                val name = pipe.pipeName.ifEmpty { null } ?: continue
                return nameOrNullIfNoop(name, pipeOutputs, pipeInputs)
            }
        }

        // Priority 4: heuristic scoring
        val scored = scoreDecisionPipeCandidates()
        if(scored != null)
        {
            val name = scored.pipeName.ifEmpty { null } ?: return null
            return nameOrNullIfNoop(name, pipeOutputs, pipeInputs)
        }

        // Priority 5: fallback
        return null
    }

    /**
     * Returns [pipeName] unless its captured output is a no-op (same text as
     * its input). In the no-op case, returns `null` to signal the fallback
     * was used.
     */
    private fun nameOrNullIfNoop(
        pipeName: String,
        pipeOutputs: Map<String, MultimodalContent>,
        pipeInputs: Map<String, MultimodalContent>
    ): String?
    {
        val out = pipeOutputs[pipeName] ?: return null
        val inp = pipeInputs[pipeName]
        if(inp != null && out.text == inp.text && pipeOutputs.size > 1)
        {
            return null
        }
        return pipeName
    }

    /**
     * Score each pipe in the pipeline for decision-pipe candidacy. Returns the
     * highest-scoring pipe, or `null` if no pipe has a strong LLM signal
     * (provider+model). The name-match signal (+1) is a weak tiebreaker that
     * only applies when at least one pipe has a strong signal — it does not
     * by itself elect a decision pipe. Ties go to the LATER pipe.
     *
     * Returns `null` (and the caller falls back to the last pipe) when:
     *   - the pipeline is empty, OR
     *   - no pipe has a provider+model set (no LLM signal at all)
     */
    private fun scoreDecisionPipeCandidates(): Pipe?
    {
        if(pipes.isEmpty()) return null
        var bestPipe: Pipe? = null
        var bestScore = 0
        val namePattern = Regex("(?i)(decision|judge|dispatch|output|final)")
        for(pipe in pipes)
        {
            val s = pipe.toPipeSettings()
            var score = 0
            // Note: `provider` defaults to `Aws` and `model` defaults to ""
            // (empty string, not null) on the base Pipe class, so the check
            // requires a NON-EMPTY model string to count as a real LLM signal.
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
        // Only elect a decision pipe via scoring if at least one pipe has a
        // strong LLM signal (provider+model = 10 points). Pipelines with no
        // LLM signals (e.g. plain data processing pipelines) fall back to
        // the last pipe's output, matching the "no signal = last pipe"
        // default. The name-match (+1) signal is a weak tiebreaker only.
        if(bestScore < 10) return null
        return bestPipe
    }
}
