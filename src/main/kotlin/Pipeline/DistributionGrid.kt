package com.TTT.Pipeline

import com.TTT.Debug.EventPriorityMapper
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TracePhase
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PSkills
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Internal binding record for a router, worker, or attached local peer.
 *
 * @param bindingKey Stable internal key used by the shell to track the binding.
 * @param kind Runtime role the binding serves.
 * @param component P2P-capable component bound to the role.
 * @param descriptor Normalized descriptor assigned to the component.
 * @param transport Normalized transport identity assigned to the component.
 * @param requirements Normalized requirements assigned to the component.
 */
private data class DistributionGridBinding(
    val bindingKey: String,
    val kind: DistributionGridBindingKind,
    val component: P2PInterface,
    val descriptor: P2PDescriptor,
    val transport: P2PTransport,
    val requirements: P2PRequirements
)

/**
 * Declares the local role served by one `DistributionGrid` binding.
 */
private enum class DistributionGridBindingKind
{
    ROUTER,
    WORKER,
    PEER
}

/**
 * `DistributionGrid` is TPipe's remote node harness.
 *
 * Phase 3 still keeps the class non-executing, but it now validates the local graph, exposes child pipelines,
 * manages shell lifecycle state, and attaches explicit grid metadata to the outward node descriptor.
 */
class DistributionGrid : P2PInterface
{
    private var p2pDescriptor: P2PDescriptor? = null
    private var p2pTransport: P2PTransport? = null
    private var p2pRequirements: P2PRequirements? = null
    private var containerObject: Any? = null

    private var routerBinding: DistributionGridBinding? = null
    private var workerBinding: DistributionGridBinding? = null
    private val localPeerBindingsByKey = linkedMapOf<String, DistributionGridBinding>()
    private val externalPeerDescriptorsByKey = linkedMapOf<String, P2PDescriptor>()

    private var discoveryMode = DistributionGridPeerDiscoveryMode.HYBRID
    private var routingPolicy = DistributionGridRoutingPolicy()
    private var memoryPolicy = DistributionGridMemoryPolicy()
    private var durableStore: DistributionGridDurableStore? = null
    private var maxHops = 16
    private var tracingEnabled = false
    private var traceConfig = TraceConfig()
    private var initialized = false
    private var pauseRequested = false

    private val gridId = UUID.randomUUID().toString()
    private val defaultMaxNestedDepth = 8
    private var synthesizedPeerOrdinal = 0

//----------------------------------------------P2P Interface------------------------------------------------------------

    /**
     * Store the outward P2P descriptor for the grid node itself.
     *
     * @param description Public descriptor assigned to the grid node.
     */
    override fun setP2pDescription(description: P2PDescriptor)
    {
        p2pDescriptor = description
        markShellDirty()
    }

    /**
     * Read the outward P2P descriptor assigned to the grid node itself.
     *
     * @return Grid-level descriptor or `null` if one has not been assigned yet.
     */
    override fun getP2pDescription(): P2PDescriptor?
    {
        return p2pDescriptor
    }

    /**
     * Store the outward transport identity for the grid node itself.
     *
     * @param transport Public transport identity assigned to the grid node.
     */
    override fun setP2pTransport(transport: P2PTransport)
    {
        p2pTransport = transport
        markShellDirty()
    }

    /**
     * Read the outward transport identity assigned to the grid node itself.
     *
     * @return Grid-level transport identity or `null` if one has not been assigned yet.
     */
    override fun getP2pTransport(): P2PTransport?
    {
        return p2pTransport
    }

    /**
     * Store the outward P2P requirements for the grid node itself.
     *
     * @param requirements Public requirements assigned to the grid node.
     */
    override fun setP2pRequirements(requirements: P2PRequirements)
    {
        p2pRequirements = requirements
        markShellDirty()
    }

    /**
     * Read the outward P2P requirements assigned to the grid node itself.
     *
     * @return Grid-level P2P requirements or `null` if none have been assigned yet.
     */
    override fun getP2pRequirements(): P2PRequirements?
    {
        return p2pRequirements
    }

    /**
     * Read the containing object that holds this grid node.
     *
     * @return Parent container or `null` if the grid is not nested.
     */
    override fun getContainerObject(): Any?
    {
        return containerObject
    }

    /**
     * Assign the containing object that holds this grid node.
     *
     * @param container Parent container for this grid node.
     */
    override fun setContainerObject(container: Any)
    {
        containerObject = container
        markShellDirty()
    }

    /**
     * Expose the child pipelines registered through the router, worker, and local peer bindings.
     *
     * External peer descriptors are not included because they are not local components.
     *
     * @return Stable de-duplicated child pipeline list.
     */
    override fun getPipelinesFromInterface(): List<Pipeline>
    {
        val pipelineSet = linkedSetOf<Pipeline>()

        allBindings().forEach { binding ->
            binding.component.getPipelinesFromInterface().forEach { pipeline ->
                pipelineSet.add(pipeline)
            }
        }

        return pipelineSet.toList()
    }

//----------------------------------------------Configuration------------------------------------------------------------

    /**
     * Bind the local router harness for this node.
     *
     * Existing router bindings are replaced. Missing descriptors and requirements are synthesized using safe local
     * defaults and written back onto the component.
     *
     * @param component P2P-capable router component.
     * @param descriptor Optional explicit descriptor.
     * @param requirements Optional explicit requirements.
     * @return This grid for method chaining.
     */
    fun setRouter(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    ): DistributionGrid
    {
        require(component !== this) { "DistributionGrid cannot register itself as its own router." }

        routerBinding = buildBinding(
            kind = DistributionGridBindingKind.ROUTER,
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            forcedBindingKey = "router"
        )
        markShellDirty()
        return this
    }

    /**
     * Bind the local worker harness for this node.
     *
     * Existing worker bindings are replaced. Missing descriptors and requirements are synthesized using safe local
     * defaults and written back onto the component.
     *
     * @param component P2P-capable worker component.
     * @param descriptor Optional explicit descriptor.
     * @param requirements Optional explicit requirements.
     * @return This grid for method chaining.
     */
    fun setWorker(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    ): DistributionGrid
    {
        require(component !== this) { "DistributionGrid cannot register itself as its own worker." }

        workerBinding = buildBinding(
            kind = DistributionGridBindingKind.WORKER,
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            forcedBindingKey = "worker"
        )
        markShellDirty()
        return this
    }

    /**
     * Add a local peer component attached to this node.
     *
     * The peer receives synthesized local defaults when descriptor or requirements are omitted. Duplicate peer keys
     * are rejected.
     *
     * @param component P2P-capable peer component.
     * @param descriptor Optional explicit descriptor.
     * @param requirements Optional explicit requirements.
     * @return This grid for method chaining.
     */
    fun addPeer(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    ): DistributionGrid
    {
        require(component !== this) { "DistributionGrid cannot register itself as a peer." }

        val binding = buildBinding(
            kind = DistributionGridBindingKind.PEER,
            component = component,
            descriptor = descriptor,
            requirements = requirements
        )

        require(binding.bindingKey !in localPeerBindingsByKey) { "Peer '${binding.bindingKey}' is already registered locally." }
        require(binding.bindingKey !in externalPeerDescriptorsByKey) { "Peer '${binding.bindingKey}' is already registered as an external descriptor." }

        localPeerBindingsByKey[binding.bindingKey] = binding
        markShellDirty()
        return this
    }

    /**
     * Register an external peer descriptor without a local component binding.
     *
     * @param descriptor External peer descriptor to store.
     * @return This grid for method chaining.
     */
    fun addPeerDescriptor(descriptor: P2PDescriptor): DistributionGrid
    {
        val bindingKey = buildPeerKey(
            descriptor = descriptor,
            transport = descriptor.transport
        )

        require(bindingKey !in localPeerBindingsByKey) { "Peer '$bindingKey' is already registered locally." }
        require(bindingKey !in externalPeerDescriptorsByKey) { "Peer '$bindingKey' is already registered as an external descriptor." }

        externalPeerDescriptorsByKey[bindingKey] = descriptor.copy(
            transport = descriptor.transport
        )
        markShellDirty()
        return this
    }

    /**
     * Remove a registered local peer or external peer descriptor.
     *
     * @param peerKey Canonical peer key to remove.
     * @return This grid for method chaining.
     */
    fun removePeer(peerKey: String): DistributionGrid
    {
        require(peerKey.isNotBlank()) { "Peer key must not be blank." }

        val removedLocalPeer = localPeerBindingsByKey.remove(peerKey)
        val removedExternalPeer = externalPeerDescriptorsByKey.remove(peerKey)

        require(removedLocalPeer != null || removedExternalPeer != null) { "Peer '$peerKey' is not registered." }

        markShellDirty()
        return this
    }

    /**
     * Replace an existing local peer binding while preserving its canonical key slot.
     *
     * @param peerKey Canonical peer key to replace.
     * @param component New P2P-capable peer component.
     * @param descriptor Optional explicit descriptor.
     * @param requirements Optional explicit requirements.
     * @return This grid for method chaining.
     */
    fun replacePeer(
        peerKey: String,
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    ): DistributionGrid
    {
        require(peerKey.isNotBlank()) { "Peer key must not be blank." }
        require(component !== this) { "DistributionGrid cannot register itself as a peer." }

        val previousBinding = localPeerBindingsByKey[peerKey]
            ?: throw IllegalArgumentException("Peer '$peerKey' is not registered as a local peer.")

        val replacementBinding = buildBinding(
            kind = DistributionGridBindingKind.PEER,
            component = component,
            descriptor = descriptor,
            requirements = requirements,
            forcedBindingKey = peerKey,
            fallbackTransport = previousBinding.transport,
            fallbackDescriptorName = previousBinding.descriptor.agentName
        )

        localPeerBindingsByKey[peerKey] = replacementBinding
        markShellDirty()
        return this
    }

    /**
     * Set how this node should discover downstream peers once routing is implemented.
     *
     * @param mode Discovery mode to store on the shell.
     * @return This grid for method chaining.
     */
    fun setDiscoveryMode(mode: DistributionGridPeerDiscoveryMode): DistributionGrid
    {
        discoveryMode = mode
        markShellDirty()
        return this
    }

    /**
     * Store the routing policy for future execution phases.
     *
     * @param policy Routing policy to assign.
     * @return This grid for method chaining.
     */
    fun setRoutingPolicy(policy: DistributionGridRoutingPolicy): DistributionGrid
    {
        routingPolicy = policy
        markShellDirty()
        return this
    }

    /**
     * Store the memory policy for future execution phases.
     *
     * @param policy Memory policy to assign.
     * @return This grid for method chaining.
     */
    fun setMemoryPolicy(policy: DistributionGridMemoryPolicy): DistributionGrid
    {
        memoryPolicy = policy
        markShellDirty()
        return this
    }

    /**
     * Store the durable-store contract implementation for future execution phases.
     *
     * @param store Durable store implementation to assign.
     * @return This grid for method chaining.
     */
    fun setDurableStore(store: DistributionGridDurableStore?): DistributionGrid
    {
        durableStore = store
        markShellDirty()
        return this
    }

    /**
     * Store the maximum hop count for future execution phases.
     *
     * @param max Maximum hop count to store.
     * @return This grid for method chaining.
     */
    fun setMaxHops(max: Int): DistributionGrid
    {
        maxHops = max
        markShellDirty()
        return this
    }

    /**
     * Store tracing configuration for future runtime phases.
     *
     * @param config Trace configuration to store.
     * @return This grid for method chaining.
     */
    fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)): DistributionGrid
    {
        tracingEnabled = config.enabled
        traceConfig = config
        if(config.enabled)
        {
            PipeTracer.enable()
        }
        markShellDirty()
        return this
    }

    /**
     * Disable tracing on the shell.
     *
     * @return This grid for method chaining.
     */
    fun disableTracing(): DistributionGrid
    {
        tracingEnabled = false
        traceConfig = TraceConfig()
        markShellDirty()
        return this
    }

    /**
     * Validate and finalize the shell configuration for later runtime phases.
     *
     * Phase 3 keeps this method non-executing. It only synthesizes outward identity where needed, validates the
     * local graph, and marks the shell initialized.
     *
     * @return This grid for method chaining.
     */
    suspend fun init(): DistributionGrid
    {
        if(tracingEnabled)
        {
            PipeTracer.enable()
            PipeTracer.startTrace(gridId)
            trace(
                TraceEventType.DISTRIBUTION_GRID_INIT,
                TracePhase.INITIALIZATION,
                metadata = mapOf("gridId" to gridId)
            )
        }

        trace(
            TraceEventType.DISTRIBUTION_GRID_VALIDATION_START,
            TracePhase.VALIDATION,
            metadata = mapOf(
                "routerBound" to (routerBinding != null),
                "workerBound" to (workerBinding != null),
                "localPeerCount" to localPeerBindingsByKey.size,
                "externalPeerCount" to externalPeerDescriptorsByKey.size
            )
        )

        try
        {
            require(routerBinding != null) { "DistributionGrid requires a router before init()." }
            require(workerBinding != null) { "DistributionGrid requires a worker before init()." }
            require(maxHops > 0) { "DistributionGrid maxHops must be greater than zero." }

            ensureGridIdentity()
            validatePeerRegistrationState()
            validateLocalOwnership("router", routerBinding!!)
            validateLocalOwnership("worker", workerBinding!!)
            localPeerBindingsByKey.forEach { (peerKey, binding) ->
                validateLocalOwnership("local peer '$peerKey'", binding)
            }

            validateContainerAncestry("router", routerBinding!!.component)
            validateContainerAncestry("worker", workerBinding!!.component)
            localPeerBindingsByKey.forEach { (peerKey, binding) ->
                validateContainerAncestry("local peer '$peerKey'", binding.component)
            }

            initialized = true
            pauseRequested = false

            trace(
                TraceEventType.DISTRIBUTION_GRID_VALIDATION_SUCCESS,
                TracePhase.VALIDATION,
                metadata = mapOf(
                    "routerBindingKey" to routerBinding!!.bindingKey,
                    "workerBindingKey" to workerBinding!!.bindingKey,
                    "localPeerCount" to localPeerBindingsByKey.size
                )
            )

            return this
        }

        catch(error: Throwable)
        {
            initialized = false
            trace(
                TraceEventType.DISTRIBUTION_GRID_VALIDATION_FAILURE,
                TracePhase.VALIDATION,
                metadata = mapOf("gridId" to gridId),
                error = error
            )
            throw error
        }
    }

    /**
     * Clear transient runtime state while preserving configuration and bindings.
     */
    fun clearRuntimeState()
    {
        trace(
            TraceEventType.DISTRIBUTION_GRID_RUNTIME_RESET,
            TracePhase.CLEANUP,
            metadata = mapOf(
                "wasInitialized" to initialized,
                "wasPaused" to pauseRequested
            )
        )

        initialized = false
        pauseRequested = false
    }

    /**
     * Clear the accumulated trace history for this grid instance.
     */
    fun clearTrace()
    {
        PipeTracer.clearTrace(gridId)
    }

    /**
     * Request a pause at the next safe checkpoint.
     *
     * Phase 3 exposes only shell-level pause state. Execution checkpoints land later with the runtime path.
     */
    fun pause()
    {
        pauseRequested = true
        trace(
            TraceEventType.DISTRIBUTION_GRID_PAUSE,
            TracePhase.ORCHESTRATION,
            metadata = mapOf("requested" to true)
        )
    }

    /**
     * Clear a previously requested pause.
     */
    fun resume()
    {
        pauseRequested = false
        trace(
            TraceEventType.DISTRIBUTION_GRID_RESUME,
            TracePhase.ORCHESTRATION,
            metadata = mapOf("requested" to false)
        )
    }

    /**
     * Check whether the shell is currently paused.
     *
     * @return `true` when a pause has been requested.
     */
    fun isPaused(): Boolean
    {
        return pauseRequested
    }

    /**
     * Check whether the shell has enough bound structure to support pause checkpoints later.
     *
     * @return `true` when both router and worker bindings are present.
     */
    fun canPause(): Boolean
    {
        return routerBinding != null && workerBinding != null
    }

    /**
     * Read the internal router binding key.
     *
     * @return Router binding key or `null` if no router is bound.
     */
    fun getRouterBindingKey(): String?
    {
        return routerBinding?.bindingKey
    }

    /**
     * Read the internal worker binding key.
     *
     * @return Worker binding key or `null` if no worker is bound.
     */
    fun getWorkerBindingKey(): String?
    {
        return workerBinding?.bindingKey
    }

    /**
     * Read the currently registered local peer keys.
     *
     * @return Ordered local peer keys.
     */
    fun getLocalPeerKeys(): List<String>
    {
        return localPeerBindingsByKey.keys.toList()
    }

    /**
     * Read the currently registered external peer descriptor keys.
     *
     * @return Ordered external peer keys.
     */
    fun getExternalPeerKeys(): List<String>
    {
        return externalPeerDescriptorsByKey.keys.toList()
    }

    /**
     * Read the configured discovery mode.
     *
     * @return Stored discovery mode.
     */
    fun getDiscoveryMode(): DistributionGridPeerDiscoveryMode
    {
        return discoveryMode
    }

    /**
     * Read the configured routing policy.
     *
     * @return Stored routing policy.
     */
    fun getRoutingPolicy(): DistributionGridRoutingPolicy
    {
        return routingPolicy
    }

    /**
     * Read the configured memory policy.
     *
     * @return Stored memory policy.
     */
    fun getMemoryPolicy(): DistributionGridMemoryPolicy
    {
        return memoryPolicy
    }

    /**
     * Read the configured durable-store implementation.
     *
     * @return Stored durable store or `null` if none is configured yet.
     */
    fun getDurableStore(): DistributionGridDurableStore?
    {
        return durableStore
    }

    /**
     * Read the configured maximum hop count.
     *
     * @return Stored maximum hop count.
     */
    fun getMaxHops(): Int
    {
        return maxHops
    }

    /**
     * Check whether shell-level tracing is currently enabled.
     *
     * @return `true` when tracing has been enabled on the shell.
     */
    fun isTracingEnabled(): Boolean
    {
        return tracingEnabled
    }

//----------------------------------------------Helpers-----------------------------------------------------------------

    /**
     * Build and normalize a router, worker, or local peer binding.
     *
     * @param kind Role being normalized.
     * @param component P2P-capable component to normalize.
     * @param descriptor Optional explicit descriptor.
     * @param requirements Optional explicit requirements.
     * @param forcedBindingKey Optional explicit key to preserve when replacing a peer or assigning fixed router or worker slots.
     * @param fallbackTransport Optional fallback transport used when replacing an existing binding.
     * @param fallbackDescriptorName Optional fallback descriptor name used when replacing an existing binding.
     * @return Normalized binding record.
     */
    private fun buildBinding(
        kind: DistributionGridBindingKind,
        component: P2PInterface,
        descriptor: P2PDescriptor?,
        requirements: P2PRequirements?,
        forcedBindingKey: String? = null,
        fallbackTransport: P2PTransport? = null,
        fallbackDescriptorName: String = ""
    ): DistributionGridBinding
    {
        val resolvedTransport = descriptor?.transport
            ?: component.getP2pTransport()
            ?: fallbackTransport
            ?: defaultTransport(kind)

        val synthesizedDescriptorName = fallbackDescriptorName.ifBlank { defaultAgentName(kind) }

        val resolvedDescriptor = (descriptor
            ?: component.getP2pDescription()
            ?: synthesizeDescriptor(
                kind = kind,
                transport = resolvedTransport,
                agentName = synthesizedDescriptorName
            )).copy(
            transport = resolvedTransport
        )

        val resolvedRequirements = requirements
            ?: component.getP2pRequirements()
            ?: synthesizeRequirements()

        component.setP2pDescription(resolvedDescriptor)
        component.setP2pTransport(resolvedTransport)
        component.setP2pRequirements(resolvedRequirements)

        if(component.getContainerObject() == null)
        {
            component.setContainerObject(this)
        }

        val bindingKey = forcedBindingKey ?: buildPeerKey(
            descriptor = resolvedDescriptor,
            transport = resolvedTransport
        )

        return DistributionGridBinding(
            bindingKey = bindingKey,
            kind = kind,
            component = component,
            descriptor = resolvedDescriptor,
            transport = resolvedTransport,
            requirements = resolvedRequirements
        )
    }

    /**
     * Return the complete local binding list in routing-stable order.
     *
     * @return Router, worker, and local peer bindings in deterministic order.
     */
    private fun allBindings(): List<DistributionGridBinding>
    {
        val bindings = mutableListOf<DistributionGridBinding>()

        if(routerBinding != null)
        {
            bindings.add(routerBinding!!)
        }

        if(workerBinding != null)
        {
            bindings.add(workerBinding!!)
        }

        bindings.addAll(localPeerBindingsByKey.values)
        return bindings
    }

    /**
     * Mark the shell configuration dirty after a structural or policy mutation.
     */
    private fun markShellDirty()
    {
        initialized = false
    }

    /**
     * Ensure the grid itself has a safe outward node identity and attached grid metadata.
     */
    private fun ensureGridIdentity()
    {
        val resolvedTransport = p2pTransport ?: defaultGridTransport()
        val resolvedRequirements = p2pRequirements ?: synthesizeRequirements()
        val resolvedDescriptor = (p2pDescriptor ?: synthesizeGridDescriptor(
            transport = resolvedTransport,
            agentName = defaultGridAgentName()
        )).copy(
            transport = resolvedTransport,
            distributionGridMetadata = p2pDescriptor?.distributionGridMetadata ?: synthesizeGridMetadata(resolvedTransport)
        )

        p2pTransport = resolvedTransport
        p2pRequirements = resolvedRequirements
        p2pDescriptor = resolvedDescriptor
    }

    /**
     * Validate duplicate-peer and key-registration state before the runtime begins executing.
     */
    private fun validatePeerRegistrationState()
    {
        val localPeerKeys = localPeerBindingsByKey.keys.toList()
        val externalPeerKeys = externalPeerDescriptorsByKey.keys.toList()

        require(localPeerKeys.none { peerKey -> peerKey.isBlank() }) { "DistributionGrid local peer keys must not be blank." }
        require(externalPeerKeys.none { peerKey -> peerKey.isBlank() }) { "DistributionGrid external peer keys must not be blank." }

        val overlappingKeys = localPeerKeys.toSet().intersect(externalPeerKeys.toSet())
        require(overlappingKeys.isEmpty()) {
            "DistributionGrid local and external peers must not share keys: ${overlappingKeys.joinToString()}"
        }
    }

    /**
     * Ensure a local binding is still owned by this grid and has not been rebound elsewhere.
     *
     * @param label Friendly label used for diagnostics.
     * @param binding Binding being checked.
     */
    private fun validateLocalOwnership(
        label: String,
        binding: DistributionGridBinding
    )
    {
        val owner = binding.component.getContainerObject()
        require(owner === this) {
            "DistributionGrid requires $label to remain bound to this grid before init(). " +
                "Current owner: ${describeContainer(owner)}"
        }
    }

    /**
     * Validate a local binding ancestry chain with identity-based cycle detection.
     *
     * @param label Friendly label used in error messages.
     * @param component Starting component to validate.
     */
    private fun validateContainerAncestry(
        label: String,
        component: P2PInterface
    )
    {
        val ancestry = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var current: Any? = component
        var depth = 0

        while(current is P2PInterface)
        {
            if(!ancestry.add(current))
            {
                throw IllegalStateException(
                    "DistributionGrid detected a cycle while validating $label. " +
                        "Repeated container: ${describeContainer(current)}"
                )
            }

            val next = current.getContainerObject() ?: return
            depth++
            if(depth > defaultMaxNestedDepth)
            {
                throw IllegalStateException(
                    "DistributionGrid exceeded the nested depth limit of $defaultMaxNestedDepth while validating $label. " +
                        "Last container: ${describeContainer(next)}"
                )
            }

            current = next
        }
    }

    /**
     * Render a compact diagnostic description for a container or bound component.
     *
     * @param container Container or owner reference to describe.
     * @return Human-readable diagnostic label.
     */
    private fun describeContainer(container: Any?): String
    {
        if(container == null)
        {
            return "null"
        }

        if(container is P2PInterface)
        {
            val descriptor = container.getP2pDescription()
            if(descriptor?.agentName?.isNotBlank() == true)
            {
                return "${container::class.simpleName}:${descriptor.agentName}"
            }
        }

        return container::class.simpleName ?: container::class.qualifiedName ?: "Unknown"
    }

    /**
     * Build the canonical peer key used for local and external peer registration.
     *
     * Transport identity is preferred. Descriptor name is the fallback when no transport address is available.
     *
     * @param descriptor Descriptor associated with the peer.
     * @param transport Transport associated with the peer.
     * @return Canonical peer key.
     */
    private fun buildPeerKey(
        descriptor: P2PDescriptor,
        transport: P2PTransport
    ): String
    {
        if(transport.transportAddress.isNotBlank())
        {
            return "${transport.transportMethod}::${transport.transportAddress}"
        }

        if(descriptor.agentName.isNotBlank())
        {
            return "name::${descriptor.agentName}"
        }

        throw IllegalArgumentException("Peer bindings must provide either a transport address or an agent name.")
    }

    /**
     * Build the default transport identity for a synthesized local binding.
     *
     * @param kind Role receiving the synthesized transport.
     * @return Safe local transport identity.
     */
    private fun defaultTransport(kind: DistributionGridBindingKind): P2PTransport
    {
        return when(kind)
        {
            DistributionGridBindingKind.ROUTER ->
            {
                P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "distribution-grid-router-$gridId"
                )
            }

            DistributionGridBindingKind.WORKER ->
            {
                P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "distribution-grid-worker-$gridId"
                )
            }

            DistributionGridBindingKind.PEER ->
            {
                synthesizedPeerOrdinal += 1
                P2PTransport(
                    transportMethod = Transport.Tpipe,
                    transportAddress = "distribution-grid-peer-$gridId-$synthesizedPeerOrdinal"
                )
            }
        }
    }

    /**
     * Build the default transport identity for the outward grid node descriptor.
     *
     * @return Safe local transport identity for the node shell.
     */
    private fun defaultGridTransport(): P2PTransport
    {
        return P2PTransport(
            transportMethod = Transport.Tpipe,
            transportAddress = "distribution-grid-node-$gridId"
        )
    }

    /**
     * Build the default agent name for a synthesized local binding.
     *
     * @param kind Role receiving the synthesized name.
     * @return Safe local binding name.
     */
    private fun defaultAgentName(kind: DistributionGridBindingKind): String
    {
        return when(kind)
        {
            DistributionGridBindingKind.ROUTER -> "distribution-grid-router-$gridId"
            DistributionGridBindingKind.WORKER -> "distribution-grid-worker-$gridId"
            DistributionGridBindingKind.PEER -> "distribution-grid-peer-$gridId-$synthesizedPeerOrdinal"
        }
    }

    /**
     * Build the default public agent name for the grid node itself.
     *
     * @return Safe outward node name.
     */
    private fun defaultGridAgentName(): String
    {
        return "distribution-grid-node-$gridId"
    }

    /**
     * Build a safe local descriptor for a router, worker, or peer binding.
     *
     * @param kind Role receiving the synthesized descriptor.
     * @param transport Resolved transport to embed in the descriptor.
     * @param agentName Resolved agent name to embed in the descriptor.
     * @return Synthesized descriptor.
     */
    private fun synthesizeDescriptor(
        kind: DistributionGridBindingKind,
        transport: P2PTransport,
        agentName: String
    ): P2PDescriptor
    {
        val description = when(kind)
        {
            DistributionGridBindingKind.ROUTER -> "Local router binding for DistributionGrid node orchestration"
            DistributionGridBindingKind.WORKER -> "Local worker binding for DistributionGrid node execution"
            DistributionGridBindingKind.PEER -> "Local peer binding attached to the DistributionGrid node"
        }

        val skills = when(kind)
        {
            DistributionGridBindingKind.ROUTER ->
            {
                mutableListOf(
                    P2PSkills("Route", "Evaluates the grid envelope and chooses the next shell action"),
                    P2PSkills("Validate", "Checks the local node configuration and routing policy constraints")
                )
            }

            DistributionGridBindingKind.WORKER ->
            {
                mutableListOf(
                    P2PSkills("Execute", "Performs the local task work advertised by this DistributionGrid node")
                )
            }

            DistributionGridBindingKind.PEER ->
            {
                mutableListOf(
                    P2PSkills("PeerHandoff", "Acts as a local peer candidate attached to a DistributionGrid node")
                )
            }
        }

        return P2PDescriptor(
            agentName = agentName,
            agentDescription = description,
            transport = transport,
            requiresAuth = false,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none,
            supportedContentTypes = mutableListOf(SupportedContentTypes.text),
            agentSkills = skills
        )
    }

    /**
     * Build a safe outward descriptor for the grid node itself.
     *
     * @param transport Resolved outward transport.
     * @param agentName Resolved outward agent name.
     * @return Synthesized outward node descriptor.
     */
    private fun synthesizeGridDescriptor(
        transport: P2PTransport,
        agentName: String
    ): P2PDescriptor
    {
        return P2PDescriptor(
            agentName = agentName,
            agentDescription = "DistributionGrid node shell",
            transport = transport,
            requiresAuth = false,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none,
            supportedContentTypes = mutableListOf(SupportedContentTypes.text),
            agentSkills = mutableListOf(
                P2PSkills("Route", "Coordinates DistributionGrid node routing decisions"),
                P2PSkills("Execute", "Hosts a local worker binding for DistributionGrid node execution")
            )
        )
    }

    /**
     * Build safe grid metadata for the outward node descriptor.
     *
     * @param transport Resolved outward transport for the grid node.
     * @return Synthesized grid metadata block.
     */
    private fun synthesizeGridMetadata(transport: P2PTransport): DistributionGridNodeMetadata
    {
        return DistributionGridNodeMetadata(
            nodeId = gridId,
            supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
            roleCapabilities = mutableListOf("Router", "Worker"),
            supportedTransports = mutableListOf(transport.transportMethod),
            defaultTracePolicy = DistributionGridTracePolicy(
                allowTracing = tracingEnabled,
                allowTracePersistence = traceConfig.enabled
            ),
            defaultRoutingPolicy = routingPolicy,
            actsAsRegistry = false
        )
    }

    /**
     * Build safe local requirements for a router, worker, peer, or outward node binding.
     *
     * @return Synthesized local requirements.
     */
    private fun synthesizeRequirements(): P2PRequirements
    {
        return P2PRequirements(
            requireConverseInput = false,
            allowAgentDuplication = false,
            allowCustomContext = false,
            allowCustomJson = false,
            allowExternalConnections = false,
            acceptedContent = mutableListOf(SupportedContentTypes.text),
            maxTokens = 30000,
            allowMultiPageContext = true
        )
    }

    /**
     * Emit a Phase 3 trace event when tracing is enabled and the requested detail level allows it.
     *
     * @param eventType Event vocabulary entry to emit.
     * @param phase Trace phase for the event.
     * @param metadata Optional metadata payload.
     * @param error Optional exception snapshot for failures.
     */
    private fun trace(
        eventType: TraceEventType,
        phase: TracePhase,
        metadata: Map<String, Any> = emptyMap(),
        error: Throwable? = null
    )
    {
        if(!tracingEnabled) return
        if(!EventPriorityMapper.shouldTrace(eventType, traceConfig.detailLevel)) return

        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = gridId,
            pipeName = "DistributionGrid-${p2pDescriptor?.agentName ?: "unbound"}",
            eventType = eventType,
            phase = phase,
            content = null,
            contextSnapshot = null,
            metadata = if(traceConfig.includeMetadata) buildTraceMetadata(metadata, error) else emptyMap(),
            error = error
        )

        PipeTracer.addEvent(gridId, event)
    }

    /**
     * Build the compact metadata snapshot recorded for Phase 3 validation and lifecycle events.
     *
     * @param baseMetadata Event-specific metadata.
     * @param error Optional exception to serialize into metadata.
     * @return Trace-safe metadata map.
     */
    private fun buildTraceMetadata(
        baseMetadata: Map<String, Any>,
        error: Throwable?
    ): Map<String, Any>
    {
        val metadata = mutableMapOf<String, Any>(
            "gridId" to gridId,
            "initialized" to initialized,
            "paused" to pauseRequested,
            "routerBound" to (routerBinding != null),
            "workerBound" to (workerBinding != null),
            "localPeerCount" to localPeerBindingsByKey.size,
            "externalPeerCount" to externalPeerDescriptorsByKey.size,
            "maxHops" to maxHops
        )

        metadata.putAll(baseMetadata)

        if(error != null)
        {
            metadata["errorType"] = error::class.simpleName.orEmpty()
            metadata["errorMessage"] = error.message.orEmpty()
        }

        return metadata
    }
}
