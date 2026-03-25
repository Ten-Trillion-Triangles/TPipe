package com.TTT.Pipeline

import com.TTT.Debug.EventPriorityMapper
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TracePhase
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRejection
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PSkills
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.PipeError
import com.TTT.Pipe.hasError
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deepCopy
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
    private companion object
    {
        private const val ENVELOPE_METADATA_KEY = "distributionGridEnvelope"
        private const val DIRECTIVE_METADATA_KEY = "distributionGridDirective"
        private const val OUTCOME_METADATA_KEY = "distributionGridOutcome"
        private const val FAILURE_METADATA_KEY = "distributionGridFailure"
    }

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

    private var beforeRouteHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var beforeLocalWorkerHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var afterLocalWorkerHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var beforePeerDispatchHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var afterPeerResponseHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var outboundMemoryHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var failureHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var outcomeTransformationHook: (suspend (MultimodalContent, DistributionGridEnvelope) -> MultimodalContent)? = null

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

    /**
     * Execute the local DistributionGrid runtime path directly.
     *
     * Phase 4 introduces the first real local-only runtime behavior. This entrypoint normalizes the request into a
     * grid envelope, executes the router-to-worker path, and returns terminal content without any remote dispatch.
     *
     * @param content Content to execute through the local grid harness.
     * @return Terminal local execution result.
     */
    suspend fun execute(content: MultimodalContent): MultimodalContent
    {
        val readinessFailure = validateExecutionReadiness()
        if(readinessFailure != null)
        {
            return buildBoundaryFailureContent(content, readinessFailure)
        }

        val workingContent = content.deepCopy()
        val envelope = normalizeEnvelopeFromDirectInput(workingContent)
        return executeEnvelopeLocally(envelope)
    }

    /**
     * Execute the local DistributionGrid runtime path without going through P2P transport.
     *
     * @param content Content to execute through the local grid harness.
     * @return Terminal local execution result.
     */
    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
    {
        return execute(content)
    }

    /**
     * Normalize an inbound P2P request into the same local runtime path used by direct execution.
     *
     * @param request Inbound P2P request to execute locally.
     * @return P2P-wrapped local execution result or a boundary rejection when execution cannot begin.
     */
    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
    {
        val readinessFailure = validateExecutionReadiness()
        if(readinessFailure != null)
        {
            return mapFailureToP2PResponse(readinessFailure)
        }

        val inputContent = request.prompt.deepCopy()
        inputContent.context = request.context?.deepCopy() ?: inputContent.context
        val envelope = normalizeEnvelopeFromP2PRequest(request, inputContent)
        val result = executeEnvelopeLocally(envelope)

        val failure = result.metadata[FAILURE_METADATA_KEY] as? DistributionGridFailure
        if(failure != null && failure.kind == DistributionGridFailureKind.VALIDATION_FAILURE &&
            result.metadata[OUTCOME_METADATA_KEY] == null
        )
        {
            return mapFailureToP2PResponse(failure)
        }

        return P2PResponse(output = result)
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

    /**
     * Register a hook that runs after envelope normalization but before router dispatch.
     *
     * @param hook Hook to assign, or `null` to clear it.
     * @return This grid for method chaining.
     */
    fun setBeforeRouteHook(
        hook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)?
    ): DistributionGrid
    {
        beforeRouteHook = hook
        return this
    }

    /**
     * Register a hook that runs immediately before local worker dispatch.
     *
     * @param hook Hook to assign, or `null` to clear it.
     * @return This grid for method chaining.
     */
    fun setBeforeLocalWorkerHook(
        hook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)?
    ): DistributionGrid
    {
        beforeLocalWorkerHook = hook
        return this
    }

    /**
     * Register a hook that runs immediately after local worker completion.
     *
     * @param hook Hook to assign, or `null` to clear it.
     * @return This grid for method chaining.
     */
    fun setAfterLocalWorkerHook(
        hook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)?
    ): DistributionGrid
    {
        afterLocalWorkerHook = hook
        return this
    }

    /**
     * Register a hook reserved for later remote peer dispatch phases.
     *
     * @param hook Hook to assign, or `null` to clear it.
     * @return This grid for method chaining.
     */
    fun setBeforePeerDispatchHook(
        hook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)?
    ): DistributionGrid
    {
        beforePeerDispatchHook = hook
        return this
    }

    /**
     * Register a hook reserved for later remote peer response phases.
     *
     * @param hook Hook to assign, or `null` to clear it.
     * @return This grid for method chaining.
     */
    fun setAfterPeerResponseHook(
        hook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)?
    ): DistributionGrid
    {
        afterPeerResponseHook = hook
        return this
    }

    /**
     * Register a hook reserved for later outbound memory shaping phases.
     *
     * @param hook Hook to assign, or `null` to clear it.
     * @return This grid for method chaining.
     */
    fun setOutboundMemoryHook(
        hook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)?
    ): DistributionGrid
    {
        outboundMemoryHook = hook
        return this
    }

    /**
     * Register a hook that runs on the failure path before terminal content is finalized.
     *
     * @param hook Hook to assign, or `null` to clear it.
     * @return This grid for method chaining.
     */
    fun setFailureHook(
        hook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)?
    ): DistributionGrid
    {
        failureHook = hook
        return this
    }

    /**
     * Register a hook that transforms the terminal output content before it is returned to the caller.
     *
     * @param hook Hook to assign, or `null` to clear it.
     * @return This grid for method chaining.
     */
    fun setOutcomeTransformationHook(
        hook: (suspend (MultimodalContent, DistributionGridEnvelope) -> MultimodalContent)?
    ): DistributionGrid
    {
        outcomeTransformationHook = hook
        return this
    }

//----------------------------------------------Helpers-----------------------------------------------------------------

    /**
     * Validate whether the Phase 4 runtime is ready to execute local work.
     *
     * @return Failure record when execution must be rejected before it starts, or `null` when the shell is ready.
     */
    private fun validateExecutionReadiness(): DistributionGridFailure?
    {
        if(!initialized)
        {
            return buildFailure(
                kind = DistributionGridFailureKind.VALIDATION_FAILURE,
                reason = "DistributionGrid must be initialized with init() before execution can begin.",
                retryable = true
            )
        }

        if(pauseRequested)
        {
            return buildFailure(
                kind = DistributionGridFailureKind.VALIDATION_FAILURE,
                reason = "DistributionGrid is paused and cannot begin a new execution.",
                retryable = true
            )
        }

        if(routerBinding == null || workerBinding == null)
        {
            return buildFailure(
                kind = DistributionGridFailureKind.VALIDATION_FAILURE,
                reason = "DistributionGrid requires both router and worker bindings before execution.",
                retryable = true
            )
        }

        return null
    }

    /**
     * Build the normalized envelope for direct local execution.
     *
     * @param content Deep-copied input content for this execution.
     * @return Fresh execution envelope.
     */
    private fun normalizeEnvelopeFromDirectInput(content: MultimodalContent): DistributionGridEnvelope
    {
        val currentNodeId = resolveCurrentNodeId()
        val currentTransport = resolveCurrentTransport()

        return DistributionGridEnvelope(
            taskId = UUID.randomUUID().toString(),
            originNodeId = currentNodeId,
            originTransport = currentTransport.deepCopy(),
            senderNodeId = currentNodeId,
            senderTransport = currentTransport.deepCopy(),
            currentNodeId = currentNodeId,
            currentTransport = currentTransport.deepCopy(),
            content = content,
            taskIntent = content.text,
            currentObjective = content.text,
            routingPolicy = routingPolicy.deepCopy(),
            tracePolicy = resolveDefaultTracePolicy(),
            credentialPolicy = DistributionGridCredentialPolicy(),
            executionNotes = mutableListOf(),
            hopHistory = mutableListOf(),
            completed = false,
            latestOutcome = null,
            latestFailure = null,
            durableStateKey = "",
            sessionRef = null,
            attributes = mutableMapOf()
        )
    }

    /**
     * Build the normalized envelope for inbound P2P execution.
     *
     * @param request Inbound request being normalized.
     * @param content Deep-copied prompt content from the request.
     * @return Fresh execution envelope.
     */
    private fun normalizeEnvelopeFromP2PRequest(
        request: P2PRequest,
        content: MultimodalContent
    ): DistributionGridEnvelope
    {
        val currentNodeId = resolveCurrentNodeId()
        val currentTransport = resolveCurrentTransport()
        val senderNodeId = request.transport.transportAddress.ifBlank { "external-sender" }
        val senderTransport = request.transport.copy()

        return DistributionGridEnvelope(
            taskId = UUID.randomUUID().toString(),
            originNodeId = senderNodeId,
            originTransport = senderTransport.deepCopy(),
            senderNodeId = senderNodeId,
            senderTransport = senderTransport.deepCopy(),
            currentNodeId = currentNodeId,
            currentTransport = currentTransport.deepCopy(),
            content = content,
            taskIntent = content.text,
            currentObjective = content.text,
            routingPolicy = routingPolicy.deepCopy(),
            tracePolicy = resolveDefaultTracePolicy(),
            credentialPolicy = DistributionGridCredentialPolicy(),
            executionNotes = mutableListOf(),
            hopHistory = mutableListOf(),
            completed = false,
            latestOutcome = null,
            latestFailure = null,
            durableStateKey = "",
            sessionRef = null,
            attributes = mutableMapOf()
        )
    }

    /**
     * Execute one normalized envelope through the Phase 4 local-only runtime path.
     *
     * @param envelope Fresh execution envelope to process.
     * @return Terminal local execution content.
     */
    private suspend fun executeEnvelopeLocally(envelope: DistributionGridEnvelope): MultimodalContent
    {
        val executionStartedAt = System.currentTimeMillis()
        trace(
            TraceEventType.DISTRIBUTION_GRID_START,
            TracePhase.ORCHESTRATION,
            metadata = mapOf(
                "taskId" to envelope.taskId,
                "currentNodeId" to envelope.currentNodeId
            )
        )

        return try
        {
            val preparedEnvelope = beforeRouteHook?.invoke(envelope) ?: envelope
            val routerResult = routerBinding!!.component.executeLocal(preparedEnvelope.content)
            preparedEnvelope.content = routerResult

            if(routerResult.hasError() || routerResult.terminatePipeline)
            {
                val failure = buildFailure(
                    kind = DistributionGridFailureKind.ROUTING_FAILURE,
                    reason = routerResult.pipeError?.message ?: "Router execution failed.",
                    retryable = false
                )
                preparedEnvelope.latestFailure = failure
                preparedEnvelope.executionNotes.add("Router execution failed before local worker dispatch.")
                return finalizeFailedEnvelope(
                    envelope = preparedEnvelope,
                    failure = failure,
                    startedAt = executionStartedAt,
                    completedAt = System.currentTimeMillis(),
                    directiveKind = DistributionGridDirectiveKind.TERMINATE
                )
            }

            val directive = resolveDirective(preparedEnvelope)
            trace(
                TraceEventType.DISTRIBUTION_GRID_ROUTER_DECISION,
                TracePhase.ORCHESTRATION,
                metadata = mapOf(
                    "taskId" to preparedEnvelope.taskId,
                    "directive" to directive.kind.name
                )
            )

            routeLocalDirective(preparedEnvelope, directive, executionStartedAt)
        }

        catch(error: Throwable)
        {
            val failure = buildFailure(
                kind = DistributionGridFailureKind.UNKNOWN,
                reason = error.message ?: "DistributionGrid local execution failed.",
                retryable = false
            )
            envelope.latestFailure = failure
            trace(
                TraceEventType.DISTRIBUTION_GRID_FAILURE,
                TracePhase.CLEANUP,
                metadata = mapOf(
                    "taskId" to envelope.taskId,
                    "directive" to "EXCEPTION"
                ),
                error = error
            )
            finalizeFailedEnvelope(
                envelope = envelope,
                failure = failure,
                startedAt = executionStartedAt,
                completedAt = System.currentTimeMillis(),
                directiveKind = DistributionGridDirectiveKind.TERMINATE,
                error = error
            )
        }
    }

    /**
     * Route one local directive without dispatching any remote peers.
     *
     * @param envelope Current execution envelope.
     * @param directive Resolved router directive.
     * @param startedAt Execution start time used for hop recording.
     * @return Terminal local execution content.
     */
    private suspend fun routeLocalDirective(
        envelope: DistributionGridEnvelope,
        directive: DistributionGridDirective,
        startedAt: Long
    ): MultimodalContent
    {
        return when(directive.kind)
        {
            DistributionGridDirectiveKind.RUN_LOCAL_WORKER ->
            {
                val preparedEnvelope = beforeLocalWorkerHook?.invoke(envelope) ?: envelope
                runLocalWorker(preparedEnvelope, startedAt)
            }

            DistributionGridDirectiveKind.RETURN_TO_SENDER,
            DistributionGridDirectiveKind.RETURN_TO_ORIGIN,
            DistributionGridDirectiveKind.RETURN_TO_TRANSPORT ->
            {
                envelope.executionNotes.add(
                    directive.notes.ifBlank { "Router returned locally without worker dispatch." }
                )
                finalizeSuccessfulEnvelope(
                    envelope = envelope,
                    returnMode = directive.toReturnMode(),
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    directiveKind = directive.kind
                )
            }

            DistributionGridDirectiveKind.REJECT,
            DistributionGridDirectiveKind.TERMINATE ->
            {
                val failure = buildFailure(
                    kind = DistributionGridFailureKind.POLICY_REJECTED,
                    reason = directive.rejectReason.ifBlank { "Router rejected the task locally." },
                    retryable = false
                )
                envelope.latestFailure = failure
                finalizeFailedEnvelope(
                    envelope = envelope,
                    failure = failure,
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    directiveKind = directive.kind
                )
            }

            DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
            DistributionGridDirectiveKind.RETRY_SAME_PEER,
            DistributionGridDirectiveKind.TRY_ALTERNATE_PEER ->
            {
                val failure = buildFailure(
                    kind = DistributionGridFailureKind.ROUTING_FAILURE,
                    reason = "Directive '${directive.kind.name}' is not supported until Phase 5 remote handoff.",
                    retryable = false
                )
                envelope.latestFailure = failure
                finalizeFailedEnvelope(
                    envelope = envelope,
                    failure = failure,
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    directiveKind = directive.kind
                )
            }
        }
    }

    /**
     * Execute the local worker binding and finalize the Phase 4 local worker path.
     *
     * @param envelope Current execution envelope.
     * @param startedAt Execution start time used for hop recording.
     * @return Terminal local execution content.
     */
    private suspend fun runLocalWorker(
        envelope: DistributionGridEnvelope,
        startedAt: Long
    ): MultimodalContent
    {
        trace(
            TraceEventType.DISTRIBUTION_GRID_LOCAL_WORKER_DISPATCH,
            TracePhase.EXECUTION,
            metadata = mapOf(
                "taskId" to envelope.taskId,
                "workerBindingKey" to workerBinding!!.bindingKey
            )
        )

        return try
        {
            val workerResult = workerBinding!!.component.executeLocal(envelope.content)
            envelope.content = workerResult

            trace(
                TraceEventType.DISTRIBUTION_GRID_LOCAL_WORKER_RESPONSE,
                TracePhase.EXECUTION,
                metadata = mapOf(
                    "taskId" to envelope.taskId,
                    "workerBindingKey" to workerBinding!!.bindingKey,
                    "terminated" to workerResult.terminatePipeline,
                    "hasError" to workerResult.hasError()
                )
            )

            val updatedEnvelope = afterLocalWorkerHook?.invoke(envelope) ?: envelope
            if(workerResult.hasError() || workerResult.terminatePipeline)
            {
                val failure = buildFailure(
                    kind = DistributionGridFailureKind.WORKER_FAILURE,
                    reason = workerResult.pipeError?.message ?: "Local worker execution failed.",
                    retryable = false
                )
                updatedEnvelope.latestFailure = failure
                return finalizeFailedEnvelope(
                    envelope = updatedEnvelope,
                    failure = failure,
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    directiveKind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER
                )
            }

            finalizeSuccessfulEnvelope(
                envelope = updatedEnvelope,
                returnMode = resolveReturnMode(updatedEnvelope.routingPolicy),
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                directiveKind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER
            )
        }

        catch(error: Throwable)
        {
            val failure = buildFailure(
                kind = DistributionGridFailureKind.WORKER_FAILURE,
                reason = error.message ?: "Local worker execution failed.",
                retryable = false
            )
            envelope.latestFailure = failure
            finalizeFailedEnvelope(
                envelope = envelope,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                directiveKind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER,
                error = error
            )
        }
    }

    /**
     * Finalize a successful local execution outcome.
     *
     * @param envelope Current execution envelope.
     * @param returnMode Terminal return mode to record.
     * @param startedAt Execution start time used for hop recording.
     * @param completedAt Completion time used for hop recording.
     * @param directiveKind Directive that produced the terminal state.
     * @return Final terminal content.
     */
    private suspend fun finalizeSuccessfulEnvelope(
        envelope: DistributionGridEnvelope,
        returnMode: DistributionGridReturnMode,
        startedAt: Long,
        completedAt: Long,
        directiveKind: DistributionGridDirectiveKind
    ): MultimodalContent
    {
        envelope.completed = true
        val outcome = DistributionGridOutcome(
            status = DistributionGridOutcomeStatus.SUCCESS,
            returnMode = returnMode,
            taskId = envelope.taskId,
            finalContent = envelope.content.deepCopy(),
            completionNotes = envelope.executionNotes.joinToString(separator = " | "),
            hopCount = envelope.hopHistory.size + 1,
            finalNodeId = envelope.currentNodeId,
            terminalFailure = null
        )
        envelope.latestOutcome = outcome
        envelope.latestFailure = null
        envelope.hopHistory.add(
            buildHopRecord(
                envelope = envelope,
                directiveKind = directiveKind,
                resultSummary = "Local execution completed successfully.",
                startedAt = startedAt,
                completedAt = completedAt
            )
        )

        val baseOutput = envelope.content
        baseOutput.passPipeline = true
        baseOutput.terminatePipeline = false
        baseOutput.metadata[ENVELOPE_METADATA_KEY] = envelope.deepCopy()
        baseOutput.metadata[OUTCOME_METADATA_KEY] = outcome.deepCopy()
        baseOutput.metadata.remove(FAILURE_METADATA_KEY)

        val finalOutput = outcomeTransformationHook?.invoke(baseOutput, envelope) ?: baseOutput

        trace(
            TraceEventType.DISTRIBUTION_GRID_SUCCESS,
            TracePhase.CLEANUP,
            metadata = mapOf(
                "taskId" to envelope.taskId,
                "directive" to directiveKind.name,
                "returnMode" to returnMode.name,
                "hopCount" to envelope.hopHistory.size
            )
        )
        trace(
            TraceEventType.DISTRIBUTION_GRID_END,
            TracePhase.CLEANUP,
            metadata = mapOf(
                "taskId" to envelope.taskId,
                "status" to DistributionGridOutcomeStatus.SUCCESS.name
            )
        )

        return finalOutput
    }

    /**
     * Finalize a failed local execution outcome.
     *
     * @param envelope Current execution envelope.
     * @param failure Failure record to attach.
     * @param startedAt Execution start time used for hop recording.
     * @param completedAt Completion time used for hop recording.
     * @param directiveKind Directive that produced the terminal state.
     * @param error Optional thrown exception associated with the failure.
     * @return Final terminal content.
     */
    private suspend fun finalizeFailedEnvelope(
        envelope: DistributionGridEnvelope,
        failure: DistributionGridFailure,
        startedAt: Long,
        completedAt: Long,
        directiveKind: DistributionGridDirectiveKind,
        error: Throwable? = null
    ): MultimodalContent
    {
        envelope.completed = true
        envelope.latestFailure = failure
        envelope.hopHistory.add(
            buildHopRecord(
                envelope = envelope,
                directiveKind = directiveKind,
                resultSummary = failure.reason.ifBlank { "Local execution failed." },
                startedAt = startedAt,
                completedAt = completedAt
            )
        )

        val preparedEnvelope = try
        {
            failureHook?.invoke(envelope) ?: envelope
        }
        catch(hookError: Throwable)
        {
            envelope.executionNotes.add(
                "Failure hook error: ${hookError.message ?: hookError::class.simpleName.orEmpty()}"
            )
            envelope
        }

        val failureOutput = preparedEnvelope.content
        failureOutput.passPipeline = false
        failureOutput.terminatePipeline = true
        if(failureOutput.pipeError == null)
        {
            failureOutput.pipeError = PipeError(
                exception = error ?: IllegalStateException(failure.reason.ifBlank { "DistributionGrid failure." }),
                eventType = TraceEventType.DISTRIBUTION_GRID_FAILURE,
                phase = TracePhase.CLEANUP,
                pipeName = "DistributionGrid-${p2pDescriptor?.agentName ?: gridId}",
                pipeId = gridId
            )
        }

        val outcome = DistributionGridOutcome(
            status = DistributionGridOutcomeStatus.FAILURE,
            returnMode = preparedEnvelope.routingPolicy.failureReturnMode,
            taskId = preparedEnvelope.taskId,
            finalContent = failureOutput.deepCopy(),
            completionNotes = preparedEnvelope.executionNotes.joinToString(separator = " | "),
            hopCount = preparedEnvelope.hopHistory.size,
            finalNodeId = preparedEnvelope.currentNodeId,
            terminalFailure = failure.deepCopy()
        )
        preparedEnvelope.latestOutcome = outcome

        failureOutput.metadata[ENVELOPE_METADATA_KEY] = preparedEnvelope.deepCopy()
        failureOutput.metadata[FAILURE_METADATA_KEY] = failure.deepCopy()
        failureOutput.metadata[OUTCOME_METADATA_KEY] = outcome.deepCopy()

        trace(
            TraceEventType.DISTRIBUTION_GRID_FAILURE,
            TracePhase.CLEANUP,
            metadata = mapOf(
                "taskId" to preparedEnvelope.taskId,
                "directive" to directiveKind.name,
                "failureKind" to failure.kind.name,
                "reason" to failure.reason
            ),
            error = error
        )
        trace(
            TraceEventType.DISTRIBUTION_GRID_END,
            TracePhase.CLEANUP,
            metadata = mapOf(
                "taskId" to preparedEnvelope.taskId,
                "status" to DistributionGridOutcomeStatus.FAILURE.name
            )
        )

        return failureOutput
    }

    /**
     * Convert a boundary-level execution failure into terminal local content.
     *
     * @param originalInput Original content that was supplied to the shell.
     * @param failure Failure explaining why execution could not begin.
     * @return Terminal failure content.
     */
    private fun buildBoundaryFailureContent(
        originalInput: MultimodalContent,
        failure: DistributionGridFailure
    ): MultimodalContent
    {
        val content = originalInput.deepCopy()
        content.terminatePipeline = true
        content.passPipeline = false
        content.pipeError = PipeError(
            exception = IllegalStateException(failure.reason),
            eventType = TraceEventType.DISTRIBUTION_GRID_FAILURE,
            phase = TracePhase.VALIDATION,
            pipeName = "DistributionGrid-${p2pDescriptor?.agentName ?: gridId}",
            pipeId = gridId
        )
        content.metadata[FAILURE_METADATA_KEY] = failure.deepCopy()

        trace(
            TraceEventType.DISTRIBUTION_GRID_FAILURE,
            TracePhase.VALIDATION,
            metadata = mapOf(
                "failureKind" to failure.kind.name,
                "reason" to failure.reason
            )
        )

        return content
    }

    /**
     * Convert a boundary-level grid failure into a P2P rejection response.
     *
     * @param failure Failure to map to the P2P boundary.
     * @return P2P rejection response.
     */
    private fun mapFailureToP2PResponse(failure: DistributionGridFailure): P2PResponse
    {
        return P2PResponse(
            rejection = P2PRejection(
                errorType = P2PError.configuration,
                reason = failure.reason
            )
        )
    }

    /**
     * Resolve the router directive recorded on the current content, or default to local worker execution.
     *
     * @param envelope Current execution envelope.
     * @return Resolved directive for the local execution step.
     */
    private fun resolveDirective(envelope: DistributionGridEnvelope): DistributionGridDirective
    {
        val contentDirective = envelope.content.metadata[DIRECTIVE_METADATA_KEY] as? DistributionGridDirective
        return contentDirective?.deepCopy() ?: DistributionGridDirective()
    }

    /**
     * Resolve the default return mode for a local worker completion.
     *
     * @param policy Routing policy stored on the envelope.
     * @return Effective return mode for the local completion path.
     */
    private fun resolveReturnMode(policy: DistributionGridRoutingPolicy): DistributionGridReturnMode
    {
        if(policy.returnAfterFirstLocalWork)
        {
            return DistributionGridReturnMode.RETURN_AFTER_LOCAL_WORK
        }

        return policy.completionReturnMode
    }

    /**
     * Build a compact grid failure record for the current shell.
     *
     * @param kind Failure category.
     * @param reason Human-readable reason.
     * @param retryable Whether the caller may retry.
     * @return Failure record.
     */
    private fun buildFailure(
        kind: DistributionGridFailureKind,
        reason: String,
        retryable: Boolean
    ): DistributionGridFailure
    {
        val transport = resolveCurrentTransport()
        return DistributionGridFailure(
            kind = kind,
            sourceNodeId = resolveCurrentNodeId(),
            targetNodeId = "",
            transportMethod = transport.transportMethod,
            transportAddress = transport.transportAddress,
            reason = reason,
            policyCause = "",
            retryable = retryable
        )
    }

    /**
     * Build one local hop record for the current execution.
     *
     * @param envelope Current execution envelope.
     * @param directiveKind Directive that was applied locally.
     * @param resultSummary Human-readable result summary.
     * @param startedAt Hop start timestamp.
     * @param completedAt Hop completion timestamp.
     * @return Hop record for the local action.
     */
    private fun buildHopRecord(
        envelope: DistributionGridEnvelope,
        directiveKind: DistributionGridDirectiveKind,
        resultSummary: String,
        startedAt: Long,
        completedAt: Long
    ): DistributionGridHopRecord
    {
        return DistributionGridHopRecord(
            sourceNodeId = envelope.senderNodeId,
            destinationNodeId = envelope.currentNodeId,
            transportMethod = envelope.currentTransport.transportMethod,
            transportAddress = envelope.currentTransport.transportAddress,
            routerAction = directiveKind,
            privacyDecision = if(envelope.tracePolicy.requireRedaction) "redacted" else "standard",
            resultSummary = resultSummary,
            startedAtEpochMillis = startedAt,
            completedAtEpochMillis = completedAt
        )
    }

    /**
     * Resolve the current node id from explicit grid metadata when available.
     *
     * @return Stable current node id.
     */
    private fun resolveCurrentNodeId(): String
    {
        return p2pDescriptor?.distributionGridMetadata?.nodeId
            ?.ifBlank { gridId }
            ?: gridId
    }

    /**
     * Resolve the current outward transport identity for this grid node.
     *
     * @return Current outward transport identity.
     */
    private fun resolveCurrentTransport(): P2PTransport
    {
        return p2pTransport?.deepCopy() ?: defaultGridTransport()
    }

    /**
     * Resolve the default trace policy snapshot to store on new execution envelopes.
     *
     * @return Trace policy snapshot for new executions.
     */
    private fun resolveDefaultTracePolicy(): DistributionGridTracePolicy
    {
        return p2pDescriptor?.distributionGridMetadata?.defaultTracePolicy?.deepCopy()
            ?: DistributionGridTracePolicy(
                allowTracing = tracingEnabled,
                allowTracePersistence = traceConfig.enabled
            )
    }

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

    /**
     * Convert a router directive into the corresponding return mode when the directive is terminal locally.
     *
     * @return Return mode implied by the directive kind.
     */
    private fun DistributionGridDirective.toReturnMode(): DistributionGridReturnMode
    {
        return when(kind)
        {
            DistributionGridDirectiveKind.RETURN_TO_SENDER -> DistributionGridReturnMode.RETURN_TO_SENDER
            DistributionGridDirectiveKind.RETURN_TO_ORIGIN -> DistributionGridReturnMode.RETURN_TO_ORIGIN
            DistributionGridDirectiveKind.RETURN_TO_TRANSPORT -> DistributionGridReturnMode.RETURN_TO_TRANSPORT
            else -> DistributionGridReturnMode.RETURN_TO_SENDER
        }
    }
}
