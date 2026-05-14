package com.TTT.P2P

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipeline.Pipeline
import com.TTT.Structs.PipeSettings

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
     * Implementation contract: Drills through all agents inside a p2p interface recursively until we reach actual
     * pipelines. Then for each pipe keep drilling. If the pipe does not have a container pointer, we set the token
     * budget settings on that pipe using the param. If it does, we keep drilling inwards from the container pointer
     * until we do reach a pipe.
     *
     * This function will fan out in any container, to ensure all pipes at the end of the drilling will be applied
     * across the board. This allows us to set and assign a uniform set of token budget settings across the board.
     * This function is also expected to assign this value to any internally stored instances of [TokenBudgetSettings]
     * that reside inside a given container.
     */
    fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}

    /**
     * Gets the token budget settings for a given interface object. This would be the saved config inside a container
     * or the applied settings inside a pipe. Some containers do not store this data, and so this would return
     * null like in the case of pipelines, splitters, or connectors.
     */
    fun getTokenBudgetSettings(): TokenBudgetSettings? = null

    /**
     * Recursive contract to seek out all pipes at the end of ann agent tree, and apply uniform settings updates
     * to them using the [PipeSettings]. Drills through a container class and all agents inside that container class.
     * This continues until we reach an actual pipeline. And then we proceed forward into each pipe. If the pipe has a
     * container pointer, we must proceed to drill forward into whatever agent is housed in said ptr. Otherwise we're
     * at the end of the node tree for this particular path, and we can call applyPipeSettings(). This continues
     * until the entire agent node tree has been traversed and all acutal pipes have had their settings updated
     * in this manner.
     *
     * @see [com.TTT.Pipe.Pipe.applyPipeSettings]
     */
    fun setPipeSettingsRecursively(settings: PipeSettings) {}

    /**
     * Sets the parent interface to any child P2PInterface object. This useful for generic pass of this data
     * during complex container classes.
     */
    fun setParentInterface(parent: P2PInterface) {}

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