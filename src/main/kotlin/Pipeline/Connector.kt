package com.TTT.Pipeline

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TracePhase
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import com.TTT.Util.RuntimeState
import kotlinx.coroutines.Job

/**
 * A Connector is a special class that allows multiple pipelines to be branched into by passing in a mapped value.
 * The Connector will be able to move a MultimodalContent object across each connection and seamlessly route the
 * content object across the given branching Pipeline
 *
 * @see com.TTT.Pipe.MultimodalContent
 * @see Pipeline
 */
class Connector : P2PInterface
{
    @RuntimeState
    private val pipelineId: String = java.util.UUID.randomUUID().toString()

//---------------------------------------------P2P Interface------------------------------------------------------------

    private var p2pDescriptor: P2PDescriptor? = null
    private var p2pTransport: P2PTransport? = null
    private var p2PRequirements: P2PRequirements? = null
    @kotlinx.serialization.Transient
    private var _killSwitch: com.TTT.P2P.KillSwitch? = null
    override var killSwitch: com.TTT.P2P.KillSwitch?
        get() = _killSwitch
        set(value) {
            _killSwitch = value
            // Propagate kill switch to all branches
            branches.values.forEach { pipeline ->
                pipeline.killSwitch = value
            }
        }

    override fun setP2pDescription(description: P2PDescriptor)
    {
        p2pDescriptor = description
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

    override fun getP2pDescription(): P2PDescriptor? {
        return p2pDescriptor
    }

    override fun getP2pTransport(): P2PTransport? {
        return p2pTransport
    }

    override fun getPipelinesFromInterface(): List<Pipeline> {
        return branches.values.toList()
    }

    override fun setTokenBudgetRecursive(budget: TokenBudgetSettings)
    {
        for (pipeline in branches.values)
        {
            pipeline.setTokenBudgetRecursive(budget)
        }
    }

    override fun getTokenBudgetSettings(): TokenBudgetSettings? = null

    override fun setPipeSettingsRecursively(settings: PipeSettings)
    {
        for (pipeline in branches.values)
        {
            pipeline.setPipeSettingsRecursively(settings)
        }
    }


    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
    {
        val pipeline = branches[lastConnection]
        val response = pipeline?.executeP2PRequest(request) ?: return null
        return response
    }


    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
    {
        if(content.getConnectorPath() == null || !branches.contains(content.getConnectorPath()))
        {
            throw IllegalArgumentException("Invalid connector path was supplied through P2PInterface.")
        }

       return execute(content.getConnectorPath() ?: {}, content)
    }




//--------------------------------------------Constructor---------------------------------------------------------------

    /**
     * Holds each pipe this connector can branch into. If the key of Any matches, that pipeline will be used to
     * execute passing the content object forward.
     */
    private var branches = mutableMapOf<Any, Pipeline>()

    @RuntimeState
    private var lastConnection: Any? = null

    /** Whether tracing is enabled for this connector */
    private var tracingEnabled = false

    /** Trace configuration when tracing is enabled */
    private var traceConfig = TraceConfig()

//==========================================Kill Switch Accumulator=================================================

    /** Accumulates input tokens from branch execution */
    @Transient
    private var killSwitchInputAccumulator = 0

    /** Accumulates output tokens from branch execution */
    @Transient
    private var killSwitchOutputAccumulator = 0

    /** Tracks execution start time for kill switch elapsed time calculation */
    @Transient
    private var killSwitchExecutionStartTime = 0L

    /**
     * Checks if the kill switch limits have been exceeded and triggers termination if so.
     * This method accumulates token counts and evaluates them against configured limits.
     *
     * @param inputTokens The current accumulated input token count
     * @param outputTokens The current accumulated output token count
     * @param elapsedMs Time elapsed since execution started
     * @throws KillSwitchException if any limit is exceeded
     */
    protected fun checkKillSwitch(inputTokens: Int, outputTokens: Int, elapsedMs: Long)
    {
        val ks = killSwitch ?: return

        val inputLimit = ks.inputTokenLimit
        val outputLimit = ks.outputTokenLimit
        val inputExceeded = inputLimit != null && inputTokens > inputLimit
        val outputExceeded = outputLimit != null && outputTokens > outputLimit

        // Emit KILLSWITCH_CHECK event on every token check when tracing is enabled
        if(tracingEnabled) {
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

        // Determine which limit was exceeded (input takes priority when both exceed)
        val reason = when
        {
            inputExceeded -> "input_exceeded"
            outputExceeded -> "output_exceeded"
            else -> null
        }

        if(reason != null)
        {
            // Emit KILLSWITCH_TRIPPED event when limits are exceeded
            if(tracingEnabled) {
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
                    )
                )
            }

            ks.onTripped(com.TTT.P2P.KillSwitchContext(
                p2pInterface = this,
                inputTokensSpent = inputTokens,
                outputTokensSpent = outputTokens,
                elapsedMs = elapsedMs,
                reason = reason,
                accumulatedInputTokens = inputTokens,
                accumulatedOutputTokens = outputTokens
            ))
        }
    }

    /**
     * Adds a new connection to the connector to branch into a given pipeline. Returns this object back to
     * allow method chaining.
     */
    fun add(key: Any, pipeline: Pipeline) : Connector
    {
        branches[key] = pipeline
        return this
    }

    /**
     * Get a pipeline stored in this connector by its key. Returns null if no pipeline is found.
     */
    fun get(key: Any) : Pipeline?
    {
        return branches[key]
    }

    fun enableTracing(config: TraceConfig = TraceConfig()) : Connector
    {
        tracingEnabled = true
        traceConfig = config
        // Enable tracing for this connector
        com.TTT.Debug.PipeTracer.startTrace(pipelineId)

        // Enable tracing for all branches
        branches.forEach {
            it.value.enableTracing(config)
        }
        return this
    }

    /**
     * Internal trace helper that emits events to the PipeTracer when tracing is enabled.
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

        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = pipelineId,
            pipeName = "Connector",
            eventType = eventType,
            phase = phase,
            content = content,
            contextSnapshot = null,
            metadata = if(traceConfig.includeMetadata) metadata else emptyMap(),
            error = error
        )

        PipeTracer.addEvent(pipelineId, event)
    }

    /**
     * Get the trace report of the last executed pipeline. If no pipeline has been executed, returns an empty string.
     * @param format The format of the trace report. Defaults to JSON.
     */
    fun getTrace(format: TraceFormat = TraceFormat.JSON) : String
    {
        val pipeline = branches[lastConnection]
        val trace = pipeline?.getTraceReport(format) ?: ""
        return trace
    }

    /**
     * Get the trace ID for this connector.
     */
    fun getTraceId(): String = pipelineId

    /**
     * Set the lastConnection internal variable. This is useful specifically for p2p request handling where we
     * can't define the path for the connector with the p2p descriptor limitations on serialization.
     */
    fun setDefaultPath(path: Any) : Connector
    {
        /**
         * Because Any is not a variable that can be serialized in a way that we can consider viable for the very
         * flexible needs of the p2p Descriptor type, we need to be able to assign a default path at times here.
         * This issue is unique to the Connector class which has multiple paths that can be chosen. whereas other
         * container classes only have a single given input path and don't need this parameter to be known in order
         * to execute the container's internal pipelines.
         */

        lastConnection = path
        return this
    }

//--------------------------------------------Functions-----------------------------------------------------------------

    /**
     * Use the key to connect up to the mapped pipeline and execute it passing through the content object.
     */
    suspend fun execute(path: Any, content: MultimodalContent) : MultimodalContent
    {
        // Initialize kill switch tracking for this execution
        if(killSwitch != null)
        {
            killSwitchExecutionStartTime = System.currentTimeMillis()
            killSwitchInputAccumulator = 0
            killSwitchOutputAccumulator = 0
        }

        try{
            val connection = branches[path] //Find the pipeline if it's valid.
            lastConnection = path

            if(connection != null)
            {
                // Kill switch check before branch execution
                if(killSwitch != null)
                {
                    val elapsedMs = System.currentTimeMillis() - killSwitchExecutionStartTime
                    checkKillSwitch(killSwitchInputAccumulator, killSwitchOutputAccumulator, elapsedMs)
                }

                val result = connection.execute(content) //Execute it and return our as it passes through.

                // Accumulate branch tokens after execution
                if(killSwitch != null)
                {
                    killSwitchInputAccumulator += connection.inputTokensSpent
                    killSwitchOutputAccumulator += connection.outputTokensSpent
                    val elapsedMs = System.currentTimeMillis() - killSwitchExecutionStartTime
                    checkKillSwitch(killSwitchInputAccumulator, killSwitchOutputAccumulator, elapsedMs)
                }

                return result
            }

            content.terminatePipeline = true
            return content
        }
        catch(e: com.TTT.P2P.KillSwitchException)
        {
            // KillSwitchException must never be caught — it must propagate to terminate the agent
            throw e
        }
        catch(e: Exception)
        {
            content.terminatePipeline = true
            return content
        }

    }


    companion object {
        /**
         * Set and assign a path for a connector. If this gets used by a connector it will route to this path.
         */
        fun MultimodalContent.setConnectorPath(path: Any)
        {
            metadata["connectorPath"] = path
        }

        /**
         * Extract the connector path if present. This is mainly useful for being called internally by the
         * [com.TTT.Pipeline.Connector] class.
         */
        fun MultimodalContent.getConnectorPath() : Any?
        {
            return metadata["connectorPath"]
        }
    }

}