package com.TTT.Pipeline

import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.Pipe.MultimodalContent
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
    private var containerObject: Any? = null

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
        return coroutineScope {
            // Map each content to a connector using round-robin distribution
            contentList.mapIndexed { i, content ->
                async { connectors[i % connectors.size].execute(paths[i % paths.size], content) }
            }.map { it.await() } // Wait for all async operations to complete
        }
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
                result = connectors[i].execute(paths[i], result)
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
                val result = connectors[i].execute(paths[i], content)
                // Return first successful result (pipeline not terminated)
                if(!result.terminatePipeline) return result
            }
        }
        // All connectors failed, terminate pipeline
        return content.apply { terminatePipeline = true }
    }
}