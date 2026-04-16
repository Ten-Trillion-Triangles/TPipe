package com.TTT.P2P

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.Pipeline

/**
 * Interface for P2P communication. Enables handling requests, connecting to the TPipe llm frameworks inside container
 * classes and pipelines. Activating pipelines and containers, and setting and getting p2p data.
 */
interface P2PInterface
{

    /** Getter and setter. Too many objects will have either different names, access levels, or other complications
     * which unfortunately forces us to deploy basic P2P actions through this interface.
     */
    fun setP2pDescription(description: P2PDescriptor) {}
    fun getP2pDescription(): P2PDescriptor? = null

    /**
     * Getter and setter for the transport.
     */
    fun setP2pTransport(transport: P2PTransport) {}
    fun getP2pTransport(): P2PTransport? = null

    /**
     * Getter and setter for the requirements.
     */
    fun setP2pRequirements(requirements: P2PRequirements) {}
    fun getP2pRequirements(): P2PRequirements? = null



    /**
     * If the user of this interface is inside a container like a connector, splitter, or manifold etc. This will
     * get the reference to that object.
     */
    fun getContainerObject(): Any? = null

  /**
   * Interface contract to set the container object. The container object is any object that holds a pipeline
   * such as a connector, splitter, or manifold.
   */
    fun setContainerObject(container: Any) {}

    /**
     * Getter function to pull the pipelines from the interface object if valid. Will return nothing if the interface
     * is a pipeline itself.
     */
    fun getPipelinesFromInterface(): List<Pipeline> = listOf()

    /**
     * Equivalent to execute() in the local scope of a pipe or pipeline. Many container objects manage pipes
     * in very complex ways, and they will have to compensate for some of the more complex features such as
     * duplication features and updating json schemas or context instructions that the P2P protocol supports.
     *
     * Other than that, this more or less will function in the same way as execute() does for containers
     * or pipelines.
     */
     suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null

    /**
     * Call the object's internal execute function without moving through the P2P system. This is useful for
     * embedding larger containers into pipes or pipelines where a direct reference would trigger a circular
     * reference issue.
     */
     suspend fun executeLocal(content: MultimodalContent) : MultimodalContent {return content}

    /**
     * Emergency kill switch for halting agent execution when token limits are exceeded.
     * When tripped, throws [KillSwitchException] — an uncaught exception that bypasses
     * all retry policies and generic exception handlers. Set to null to disable.
     */
    var killSwitch: KillSwitch?

}