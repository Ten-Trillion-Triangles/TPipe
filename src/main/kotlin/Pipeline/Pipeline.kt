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
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenUsage
import com.TTT.Pipe.PipeTimeoutStrategy
import com.TTT.Util.copyPipeline
import com.TTT.Util.deepCopy
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
 */
class Pipeline : P2PInterface {
//=============================================== Properties =========================================================//

    /**
     * Reference to any containers that are holding this pipeline. Will be required for more advanced tracing patterns
     * such as Splitters, Manifolds, and DistributionGrids.
     */
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
     * Aggregated token usage across tracked pipes. Reset at the start of each execution.
     */
    @kotlinx.serialization.Transient
    private var pipelineTokenUsage = TokenUsage()

    @kotlinx.serialization.Transient
    var pipeMetaData = mutableMapOf<Any, Any>()

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
    private var isPaused = false
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




//------------------------------------------- Interface Functions ------------------------------------------------------

    /**@see P2PInterface */

    override fun getP2pDescription(): P2PDescriptor? {
        val description = p2pDescriptor ?: return null
        return description
    }

    override fun setP2pDescription(description: P2PDescriptor) {
        p2pDescriptor = description
    }

    override fun getP2pTransport(): P2PTransport? {
        val transport = p2pTransport ?: return null
        return transport
    }

    override fun setP2pTransport(transport: P2PTransport) {
        p2pTransport = transport
    }

    override fun setP2pRequirements(requirements: P2PRequirements) {
        p2PRequirements = requirements
    }

    override fun getP2pRequirements(): P2PRequirements? {
        return p2PRequirements
    }

    override fun getContainerObject(): Any? {
        return pipelineContainer
    }

    override fun setContainerObject(container: Any) {
        pipelineContainer = container
    }

    /**
     * This is already a pipeline so all it will do here is just return itself from the interface. However, we still
     * want to implement this so that in the even this is used on this class, it still works as expected and
     * doesn't end up causing an unexpected failure.
     */
    override fun getPipelinesFromInterface(): List<Pipeline> {
        return listOf(this)
    }


    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
    {
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
        
        if(autoRetry) {
             this.timeoutStrategy = PipeTimeoutStrategy.Retry
        } else if(customLogic != null) {
             this.timeoutStrategy = PipeTimeoutStrategy.CustomLogic
             this.pipeRetryFunction = customLogic
        } else {
             this.timeoutStrategy = PipeTimeoutStrategy.Fail
        }
        
        return this
    }


    /**
     * Adds a pipe to the list of pipes in the pipeline to be executed, only if the pipe does not already exist in the list.
     * @param pipe The pipe to be added.
     */
    fun add(pipe: Pipe): Pipeline
    {
        if (!pipes.contains(pipe)) {
            pipes.add(pipe)
            pipe.setPipelineRef(this)
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
     * Enables tracing for this pipeline with the specified configuration.
     * @param config The tracing configuration to use
     * @return This Pipeline object for method chaining
     */
    fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipeline
    {
        this.tracingEnabled = true
        this.traceConfig = config
        PipeTracer.enable() // Enable global tracer
        return this
    }

    /**
     * Gets the trace report for this pipeline in the specified format.
     * @param format The output format for the trace report
     * @return The formatted trace report as a string
     */
    fun getTraceReport(format: TraceFormat = traceConfig.outputFormat): String
    {
        return PipeTracer.exportTrace(pipelineId, format)
    }

    /**
     * Gets failure analysis for this pipeline if tracing is enabled.
     * @return FailureAnalysis object or null if tracing is disabled
     */
    fun getFailureAnalysis(): FailureAnalysis?
    {
        return if (tracingEnabled) PipeTracer.getFailureAnalysis(pipelineId) else null
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
        if (!pausingEnabled) return  // No-op if no pause points declared
        
        if (tracingEnabled) {
            trace(TraceEventType.PIPELINE_PAUSE, TracePhase.ORCHESTRATION,
                  metadata = mapOf("currentPipeIndex" to currentPipeIndex))
        }
        
        isPaused = true
        pauseCallback?.invoke(getCurrentPipe(), content)
        resumeSignal.receive()
        isPaused = false
        
        if (tracingEnabled) {
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
        if (pausingEnabled && isPaused) {
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
        return if (currentPipeIndex < pipes.size) pipes[currentPipeIndex] else null
    }

    /**
     * Checks if conditional pause criteria are met and pauses if so.
     * 
     * @param pipe The current pipe being executed
     * @param content The current pipeline content
     */
    private suspend fun checkConditionalPause(pipe: Pipe, content: MultimodalContent)
    {
        if (conditionalPauseFunction?.invoke(pipe, content) == true) {
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
        if (!tracingEnabled) return
        
        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = pipelineId,
            pipeName = "Pipeline-$pipelineName",
            eventType = eventType,
            phase = phase,
            content = content,
            contextSnapshot = null,
            metadata = metadata,
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
     * @param initialContent The initial multimodal content to pass to the first pipe.
     * @return The generated multimodal content after all pipes have been executed.
     */
    suspend fun execute(initialContent: MultimodalContent): MultimodalContent = executeMultimodal(initialContent)


    
    /**
     * Internal multimodal execution logic shared by both execute methods
     */
    private suspend fun executeMultimodal(initialContent: MultimodalContent): MultimodalContent = coroutineScope {
        if(tracingEnabled)
        {
            PipeTracer.startTrace(pipelineId)
        }

        //Run pre validation function prior to any execution operations on the content object.
        preValidationFunction?.let { func ->
            if (tracingEnabled) {
                trace(TraceEventType.VALIDATION_START, TracePhase.PRE_VALIDATION, initialContent,
                    metadata = mapOf("pipelineFunctionType" to "preValidation"))
            }
            
            try {
                func.invoke(context, miniBank, initialContent)
                
                if (tracingEnabled) {
                    trace(TraceEventType.VALIDATION_SUCCESS, TracePhase.PRE_VALIDATION, initialContent,
                        metadata = mapOf("pipelineFunctionType" to "preValidation"))
                }
            } catch (e: Exception) {
                if (tracingEnabled) {
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

        /**
         * Find next pipe based on index or pointers -> Run pipe -> Break on terminate or out of bounds -> Repeat
         */
        while(currentPipeIndex < pipes.size)
        {
            // PAUSE POINT 1: Before pipe execution (if declared)
            if (pauseBeforePipes) {
                checkPausePoint()
            }

            //Get next pipe based on next index, or jump instruction.
            val pipe = getNextPipe(generatedContent) ?: break
            generatedContent.clearJumpToPipe() //Clear so we don't have unintended behaviors.
            
            // Check conditional pause before pipe execution
            conditionalPauseFunction?.let { checkConditionalPause(pipe, generatedContent) }
            
            if (tracingEnabled)
            {
                pipe.enableTracing(traceConfig)
                pipe.addTraceId(pipelineId)
            }

            try {
                val result : Deferred<MultimodalContent> = async {
                    pipe.execute(generatedContent)
                }

                //Execute the current pipe and await its result.
                generatedContent = result.await()
            }

            catch(e: Exception) {
                trace(TraceEventType.PIPE_FAILURE, TracePhase.EXECUTION, generatedContent, error = e)
            }



            /**
             * Attempt to invoke the callback if it was bound. This allows external systems to listen to when pipes
             * complete. This is useful for logging, showing ui updates to users as the process moves about,
             * and other frontend facing tasks.
             */
            pipeCompletionCallback?.invoke(getCurrentPipe()!!, generatedContent)
            
            //Track token usage from pipes that have comprehensive tracking enabled.
            val pipeIndex = currentPipeIndex
            if (pipe.isComprehensiveTokenTrackingEnabled())
            {
                //Add this pipe's token usage to the pipeline's aggregated tracking.
                pipelineTokenUsage.addChildUsage("pipe-$pipeIndex-${pipe.pipeName}", pipe.getTokenUsage())
                
                //Update pipeline-level token counts for backward compatibility.
                inputTokensSpent = pipelineTokenUsage.totalInputTokens
                outputTokensSpent = pipelineTokenUsage.totalOutputTokens
            }

            // PAUSE POINT 2: After pipe execution (if declared)
            if (pauseAfterPipes) {
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
                if (pauseAfterRepeats) {
                    checkPausePoint()
                }
            }

            if(generatedContent.shouldTerminate())
            {
                if (tracingEnabled)
                {
                    PipeTracer.addEvent(pipelineId, TraceEvent(
                        timestamp = System.currentTimeMillis(),
                        pipeId = "pipeline-${pipelineId}",
                        pipeName = if (pipe.pipeName.isNotEmpty()) pipe.pipeName else pipe.javaClass.simpleName,
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
                        pipeName = if (pipe.pipeName.isNotEmpty()) pipe.pipeName else pipe.javaClass.simpleName,
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
            if (pauseBeforeJumps && !generatedContent.getJumpToPipe().isEmpty()) {
                checkPausePoint()
            }


            currentPipeIndex++
        }

        // PAUSE POINT 5: On pipeline completion (if declared)
        if (pauseOnCompletion) {
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

        pipelineCompletionCallBack?.invoke(this@Pipeline, generatedContent)
        return@coroutineScope generatedContent
    }
}
