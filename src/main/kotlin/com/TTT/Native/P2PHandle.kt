package com.TTT.Native

import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PConcurrencyMode
import com.TTT.P2P.AgentRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PRejection
import kotlinx.coroutines.runBlocking

/**
 * Handle representing TPipe's P2P (peer-to-peer) distributed agent system.
 *
 * P2P allows multiple TPipe instances to communicate directly across process
 * boundaries via stdio or HTTP transport. This handle provides C ABI callers
 * access to agent registration, discovery, and message routing.
 *
 * NOTE: The internal P2PInterface (used between TPipe nodes for distributed
 * execution) is NOT exposed to C ABI callers. Only the host-facing P2PHandle
 * API is exposed. Internal P2P communication happens through P2PRegistry
 * which is accessed by hosted agents but not directly by external callers.
 *
 * The P2PHandle wraps:
 * - Agent registration via P2PRegistry.register()
 * - Agent discovery via P2PRegistry.listClientAgents()
 * - Request routing via P2PRegistry.sendP2pRequest()
 *
 * All P2P operations are thread-safe through P2PRegistry's internal locking.
 *
 * @param p2pRegistry The P2PRegistry instance for agent management
 * @param p2pAgentId Optional agent ID assigned during registration
 */
class P2PHandle(
    p2pRegistry: P2PRegistry = P2PRegistry,
    p2pAgentId: String? = null
) {
    // Using private backing fields to avoid JVM signature clash with methods
    private val registry: P2PRegistry = p2pRegistry
    private var agentId: String? = p2pAgentId

    //======================================================================
    // Agent Registration
    //======================================================================

    /**
     * Register a hosted agent with the P2P registry.
     *
     * Uses the P2PRegistry.register(agent, transport, descriptor, requirements, concurrencyMode)
     * signature. The agent must implement P2PInterface and have its transport, descriptor,
     * and requirements properly configured.
     *
     * @param agent The P2PInterface implementation (Manifold, Junction, etc.)
     * @param transport P2P transport configuration (address, method)
     * @param descriptor Agent descriptor with name, capabilities, request template
     * @param requirements Agent requirements (auth, content types, etc.)
     * @param concurrencyMode SHARED or ISOLATED instance handling (default: SHARED)
     * @return true if registration succeeded, false otherwise
     */
    fun registerAgent(
        agent: P2PInterface,
        transport: P2PTransport,
        descriptor: P2PDescriptor,
        requirements: P2PRequirements,
        concurrencyMode: P2PConcurrencyMode = P2PConcurrencyMode.SHARED
    ): Boolean {
        return try {
            registry.register(agent, transport, descriptor, requirements, concurrencyMode)
            agentId = descriptor.agentName
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Register a hosted agent with a factory for ISOLATED mode.
     *
     * Factory mode implies ISOLATED concurrency — every request calls the factory,
     * executes against the fresh instance, and discards it after completion.
     *
     * @param factory Suspend function that produces a fresh P2PInterface instance
     * @param transport P2P transport configuration (address, method)
     * @param descriptor Agent descriptor with name, capabilities, request template
     * @param requirements Agent requirements (auth, content types, etc.)
     * @return true if registration succeeded, false otherwise
     */
    fun registerAgentWithFactory(
        factory: suspend () -> P2PInterface,
        transport: P2PTransport,
        descriptor: P2PDescriptor,
        requirements: P2PRequirements
    ): Boolean {
        return try {
            registry.register(factory, transport, descriptor, requirements)
            agentId = descriptor.agentName
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Unregister an agent from the P2P registry.
     *
     * @param transport The transport address of the agent to unregister
     * @return true if unregistration succeeded, false otherwise
     */
    fun unregisterAgent(transport: P2PTransport): Boolean {
        return try {
            registry.remove(transport)
            agentId = null
            true
        } catch (e: Exception) {
            false
        }
    }

    //======================================================================
    // Agent Discovery
    //======================================================================

    /**
     * Query the registry for active hosted agents (local TPipe agents).
     *
     * @return List of agent IDs currently registered
     */
    fun getActiveAgents(): List<String> {
        return try {
            registry.listClientAgents().map { it.agentName }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get the count of registered agents (excluding self).
     *
     * @return Number of active peer agents
     */
    fun getPeerCount(): Int {
        return try {
            registry.listClientAgents().size
        } catch (e: Exception) {
            0
        }
    }

    /**
     * List all client-side (remote) agent descriptors known to the registry.
     *
     * @return List of P2PDescriptor for remote agents
     */
    fun listRemoteAgents(): List<P2PDescriptor> {
        return try {
            registry.listClientAgents()
        } catch (e: Exception) {
            emptyList()
        }
    }

    //======================================================================
    // Message Routing
    //======================================================================

    /**
     * Send a P2P request to a target agent.
     *
     * This is the primary method for C ABI callers to communicate with
     * remote agents. The request is built from the AgentRequest and routed
     * through the registry to the target agent.
     *
     * @param request The agent request with target agent name and prompt
     * @param httpAuthBody Optional HTTP auth header value
     * @param p2pAuthBody Optional P2P auth body
     * @param template Optional request template override
     * @return P2PResponse containing result or rejection
     */
    suspend fun sendMessage(
        request: AgentRequest,
        httpAuthBody: String = "",
        p2pAuthBody: String = "",
        template: com.TTT.P2P.P2PRequest? = null
    ): P2PResponse {
        return try {
            registry.sendP2pRequest(request, httpAuthBody, p2pAuthBody, template)
        } catch (e: Exception) {
            val response = P2PResponse()
            response.rejection = P2PRejection(
                errorType = P2PError.transport,
                reason = e.message ?: "Unknown error sending P2P request"
            )
            response
        }
    }

    /**
     * Send a P2P request synchronously (blocking).
     *
     * Convenience method for C ABI callers that cannot use coroutines.
     *
     * @param request The agent request
     * @param httpAuthBody Optional HTTP auth
     * @param p2pAuthBody Optional P2P auth
     * @return P2PResponse or null on exception
     */
    fun sendMessageSync(
        request: AgentRequest,
        httpAuthBody: String = "",
        p2pAuthBody: String = ""
    ): P2PResponse? {
        return try {
            runBlocking {
                sendMessage(request, httpAuthBody, p2pAuthBody)
            }
        } catch (e: Exception) {
            null
        }
    }

    //======================================================================
    // Registry Access
    //======================================================================

    /**
     * Get the underlying P2PRegistry instance.
     *
     * This allows direct access to registry features not exposed by
     * P2PHandle, such as trusted registry source management.
     *
     * @return The P2PRegistry singleton
     */
    fun getRegistryInstance(): P2PRegistry = registry

    /**
     * Load remote agent descriptors into the client-side catalog.
     *
     * @param agents List of P2PDescriptor for remote agents
     */
    fun loadRemoteAgents(agents: List<P2PDescriptor>) {
        registry.loadAgents(agents)
    }

    //======================================================================
    // State Management
    //======================================================================

    /**
     * Check if this handle has an assigned agent ID.
     *
     * @return true if registered with an agent ID
     */
    fun isRegistered(): Boolean = agentId != null

    /**
     * Get the registered agent ID.
     *
     * @return Agent ID or null if not registered
     */
    fun getAgentId(): String? = agentId

    override fun toString(): String {
        return "P2PHandle(agentId=$agentId, peerCount=${getPeerCount()})"
    }
}