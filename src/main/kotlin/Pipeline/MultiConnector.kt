package com.TTT.Pipeline

import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.RuntimeState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The MultiConnector is a class that is able to house multiple connectors for more complex routing logic where
 * there could be reasons to branch multiple times to different connectors based on a series of given connector paths.
 * The MultiConnector will route a single content object through each set of connectors, or duplicate it in paralell
 * and return each diverging result if executing the connectors in paralell.
 * 
 * @see Connector
 * @see MultimodalContent
 */
class MultiConnector : P2PInterface
{
//============================================== P2PInterface ==========================================================

    /**
     * P2P Agent descriptor for this MultiConnector. Used if this MultiConnector is being registered as an addressable agent
     * in the P2P system.
     */
    private var p2pDescriptor: P2PDescriptor? = null

    /**
     * Advertised transport method to connect to this MultiConnector if it's registered as a P2P agent.
     * Required by the P2PInterface standard.
     */
    private var p2pTransport: P2PTransport? = null

    /**
     * Internal P2P requirements for this MultiConnector. Used by the P2P system to determine if an agent can connect
     * to this MultiConnector or not based on compatibility and security standards.
     */
    private var p2PRequirements: P2PRequirements? = null

    /**
     * Reference to any containers that are holding this MultiConnector. Will be required for more advanced tracing patterns
     * such as Splitters, Manifolds, and DistributionGrids.
     */
    @RuntimeState
    private var containerObject: Any? = null
    @kotlinx.serialization.Transient
    private var _killSwitch: com.TTT.P2P.KillSwitch? = null
    override var killSwitch: com.TTT.P2P.KillSwitch?
        get() = _killSwitch
        set(value) {
            _killSwitch = value
            // Propagate kill switch to all connectors
            connectors.forEach { connector ->
                connector.killSwitch = value
            }
        }

    override fun setP2pDescription(description: P2PDescriptor)
    {
        p2pDescriptor = description
    }

    override fun getP2pDescription(): P2PDescriptor?
    {
        return p2pDescriptor
    }

    override fun setP2pTransport(transport: P2PTransport)
    {
        p2pTransport = transport
    }

    override fun getP2pTransport(): P2PTransport?
    {
        return p2pTransport
    }

    override fun setP2pRequirements(requirements: P2PRequirements)
    {
        p2PRequirements = requirements
    }

    override fun getP2pRequirements(): P2PRequirements?
    {
        return p2PRequirements
    }

    override fun getContainerObject(): Any?
    {
        return containerObject
    }

    override fun setContainerObject(container: Any)
    {
        containerObject = container
    }

    override fun getPipelinesFromInterface(): List<Pipeline>
    {
        return connectors.flatMap { it.getPipelinesFromInterface() }
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
    {
        // Execute P2P request using the first available connector in sequential mode
        if(connectors.isNotEmpty())
        {
            return connectors.first().executeP2PRequest(request)
        }
        return null
    }

//============================================== MultiConnector ========================================================

    /** List of connectors managed by this MultiConnector */
    private var connectors = mutableListOf<Connector>()
    
    /** Current execution mode determining how connectors are used */
    private var executionMode = ExecutionMode.SEQUENTIAL

//==========================================Kill Switch Accumulator=================================================

    /** Accumulates input tokens from all connector executions */
    @Transient
    private var killSwitchInputAccumulator = 0

    /** Accumulates output tokens from all connector executions */
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
        val reason = when
        {
            ks.inputTokenLimit != null && inputTokens > ks.inputTokenLimit -> "input_exceeded"
            ks.outputTokenLimit != null && outputTokens > ks.outputTokenLimit -> "output_exceeded"
            else -> null
        }
        if(reason != null)
        {
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
     * Execution modes for MultiConnector operations.
     *
     * SEQUENTIAL: Execute connectors one after another in order
     * PARALLEL: Distribute content across connectors using load balancing
     * FALLBACK: Try connectors in order until one succeeds
     */
    enum class ExecutionMode { SEQUENTIAL, PARALLEL, FALLBACK }
    
    /**
     * Adds a connector to this MultiConnector.
     * 
     * @param connector The connector to add
     * @return This MultiConnector for method chaining
     */
    fun add(connector: Connector): MultiConnector
    {
        connectors.add(connector)
        return this
    }
    
    /**
     * Sets the execution mode for this MultiConnector.
     * 
     * @param mode The execution mode to use
     * @return This MultiConnector for method chaining
     */
    fun setMode(mode: ExecutionMode): MultiConnector
    {
        executionMode = mode
        return this
    }
    
    /**
     * Executes a single content object through the configured connectors.
     * 
     * @param paths List of path keys for connector routing
     * @param content The content to process
     * @return List containing the processed content (single item)
     */
    suspend fun execute(paths: List<Any>, content: MultimodalContent): List<MultimodalContent>
    {
        // Initialize kill switch tracking for this execution
        if(killSwitch != null)
        {
            killSwitchExecutionStartTime = System.currentTimeMillis()
            killSwitchInputAccumulator = 0
            killSwitchOutputAccumulator = 0
        }

        return when(executionMode)
        {
            ExecutionMode.SEQUENTIAL -> listOf(executeSequential(paths, content))
            ExecutionMode.PARALLEL -> executeParallel(listOf(content), paths)
            ExecutionMode.FALLBACK -> listOf(executeFallback(paths, content))
        }
    }
    
    /**
     * Executes multiple content objects in parallel across connectors using load balancing.
     * Content is distributed round-robin across available connectors.
     * 
     * @param contentList List of content objects to process
     * @param paths List of path keys for connector routing
     * @return List of processed content objects
     */
    suspend fun executeParallel(contentList: List<MultimodalContent>, paths: List<Any>): List<MultimodalContent>
    {
        // Initialize kill switch tracking for this execution
        if(killSwitch != null)
        {
            killSwitchExecutionStartTime = System.currentTimeMillis()
            killSwitchInputAccumulator = 0
            killSwitchOutputAccumulator = 0
        }

        val results = coroutineScope {
            // Map each content to a connector using round-robin distribution
            contentList.mapIndexed { i, content ->
                async {
                    val result = connectors[i % connectors.size].execute(paths[i % paths.size], content)

                    // Check kill switch after each branch completes for early detection
                    // Note: In parallel execution, we check each branch's tokens individually
                    // This provides earlier detection than waiting for all branches to complete
                    if(killSwitch != null)
                    {
                        val connector = connectors[i % connectors.size]
                        connector.getPipelinesFromInterface().forEach { pipeline ->
                            val elapsedMs = System.currentTimeMillis() - killSwitchExecutionStartTime
                            checkKillSwitch(pipeline.inputTokensSpent, pipeline.outputTokensSpent, elapsedMs)
                        }
                    }

                    result
                }
            }.map { it.await() } // Wait for all async operations to complete
        }

        return results
    }
    
    /**
     * Executes multiple content objects through the configured connectors.
     * 
     * @param paths List of path keys for connector routing
     * @param contentList List of content objects to process
     * @return List of processed content objects
     */
    suspend fun execute(paths: List<Any>, contentList: List<MultimodalContent>): List<MultimodalContent>
    {
        // Initialize kill switch tracking for this execution
        if(killSwitch != null)
        {
            killSwitchExecutionStartTime = System.currentTimeMillis()
            killSwitchInputAccumulator = 0
            killSwitchOutputAccumulator = 0
        }

        return when(executionMode)
        {
            ExecutionMode.SEQUENTIAL -> contentList.map { executeSequential(paths, it) }
            ExecutionMode.PARALLEL -> executeParallel(contentList, paths)
            ExecutionMode.FALLBACK -> contentList.map { executeFallback(paths, it) }
        }
    }
    
    /**
     * Executes content through connectors sequentially, passing result from one to the next.
     * 
     * @param paths List of path keys for connector routing
     * @param content The content to process
     * @return The final processed content
     */
    private suspend fun executeSequential(paths: List<Any>, content: MultimodalContent): MultimodalContent
    {
        var result = content

        // Process through each connector in sequence
        for(i in connectors.indices)
        {
            // Only continue if we have a path and pipeline hasn't been terminated
            if(i < paths.size && !result.terminatePipeline)
            {
                // Kill switch check before connector execution
                if(killSwitch != null)
                {
                    val elapsedMs = System.currentTimeMillis() - killSwitchExecutionStartTime
                    checkKillSwitch(killSwitchInputAccumulator, killSwitchOutputAccumulator, elapsedMs)
                }

                result = connectors[i].execute(paths[i], result)

                // Accumulate tokens after connector execution
                if(killSwitch != null)
                {
                    connectors[i].getPipelinesFromInterface().forEach { pipeline ->
                        killSwitchInputAccumulator += pipeline.inputTokensSpent
                        killSwitchOutputAccumulator += pipeline.outputTokensSpent
                    }
                    val elapsedMs = System.currentTimeMillis() - killSwitchExecutionStartTime
                    checkKillSwitch(killSwitchInputAccumulator, killSwitchOutputAccumulator, elapsedMs)
                }
            }
        }
        return result
    }
    
    /**
     * Executes content through connectors in fallback mode, trying each until one succeeds.
     * 
     * @param paths List of path keys for connector routing
     * @param content The content to process
     * @return The first successful result, or terminated content if all fail
     */
    private suspend fun executeFallback(paths: List<Any>, content: MultimodalContent): MultimodalContent
    {
        // Try each connector until one succeeds
        for(i in connectors.indices)
        {
            if(i < paths.size)
            {
                // Kill switch check before connector execution
                if(killSwitch != null)
                {
                    val elapsedMs = System.currentTimeMillis() - killSwitchExecutionStartTime
                    checkKillSwitch(killSwitchInputAccumulator, killSwitchOutputAccumulator, elapsedMs)
                }

                val result = connectors[i].execute(paths[i], content)

                // Accumulate tokens after connector execution
                if(killSwitch != null)
                {
                    connectors[i].getPipelinesFromInterface().forEach { pipeline ->
                        killSwitchInputAccumulator += pipeline.inputTokensSpent
                        killSwitchOutputAccumulator += pipeline.outputTokensSpent
                    }
                    val elapsedMs = System.currentTimeMillis() - killSwitchExecutionStartTime
                    checkKillSwitch(killSwitchInputAccumulator, killSwitchOutputAccumulator, elapsedMs)
                }

                // Return first successful result (pipeline not terminated)
                if(!result.terminatePipeline) return result
            }
        }
        // All connectors failed, terminate pipeline
        return content.apply { terminatePipeline = true }
    }
}