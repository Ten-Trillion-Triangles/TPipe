package com.TTT.Pipeline

import com.TTT.Debug.EventPriorityMapper
import com.TTT.Debug.FailureAnalysis
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TracePhase
import com.TTT.Context.ContextWindow
import com.TTT.Context.Dictionary
import com.TTT.Context.MiniBank
import com.TTT.Config.AuthRegistry
import com.TTT.Enums.ContextWindowSettings
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PHostedListingKind
import com.TTT.P2P.P2PHostedListingMetadata
import com.TTT.P2P.P2PHostedRegistryClient
import com.TTT.P2P.P2PHostedRegistryListing
import com.TTT.P2P.P2PHostedRegistryMutationResult
import com.TTT.P2P.P2PHostedRegistryPublishRequest
import com.TTT.P2P.P2PHostedRegistryQuery
import com.TTT.P2P.P2PHostedRegistryRemoveRequest
import com.TTT.P2P.P2PHostedRegistryRenewRequest
import com.TTT.P2P.P2PHostedRegistryUpdateRequest
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRejection
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PSkills
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.PipeError
import com.TTT.Pipe.TruncationSettings
import com.TTT.Pipe.hasError
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.StdioContextOptions
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deserialize
import com.TTT.Util.deepCopy
import com.TTT.Util.serialize
import com.TTT.Util.RuntimeState
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        private const val MEMORY_ENVELOPE_METADATA_KEY = "distributionGridMemoryEnvelope"
        private const val PAUSED_METADATA_KEY = "distributionGridPaused"
        private const val PAUSED_TASK_ID_METADATA_KEY = "distributionGridPausedTaskId"
        private const val PAUSED_REASON_METADATA_KEY = "distributionGridPauseReason"
        private const val SINGLE_NODE_MODE_ATTRIBUTE = "distributionGridSingleNodeMode"
        private const val ALLOW_REMOTE_PCP_FORWARDING_ATTRIBUTE = "distributionGridAllowRemotePcpForwarding"
        private const val RETRY_COUNT_ATTRIBUTE_PREFIX = "distributionGridRetryCount::"
        private const val ALTERNATE_INDEX_ATTRIBUTE_PREFIX = "distributionGridAlternateIndex::"
        private const val GRID_RPC_FRAME_PREFIX = "TPipe-DistributionGrid-RPC::1"
        private const val REGISTRY_ADVERTISEMENT_PAYLOAD_TYPE = "DistributionGridRegistryAdvertisement"
        private const val REGISTRATION_REQUEST_PAYLOAD_TYPE = "DistributionGridRegistrationRequest"
        private const val REGISTRATION_LEASE_PAYLOAD_TYPE = "DistributionGridRegistrationLease"
        private const val REGISTRY_QUERY_PAYLOAD_TYPE = "DistributionGridRegistryQuery"
        private const val REGISTRY_QUERY_RESULT_PAYLOAD_TYPE = "DistributionGridRegistryQueryResult"
        private const val HANDSHAKE_PAYLOAD_TYPE = "DistributionGridHandshakeRequest"
        private const val HANDSHAKE_RESPONSE_PAYLOAD_TYPE = "DistributionGridHandshakeResponse"
        private const val ENVELOPE_PAYLOAD_TYPE = "DistributionGridEnvelope"
        private const val FAILURE_PAYLOAD_TYPE = "DistributionGridFailure"
    }

    private var p2pDescriptor: P2PDescriptor? = null
    private var p2pTransport: P2PTransport? = null
    private var p2pRequirements: P2PRequirements? = null
    @RuntimeState
    private var containerObject: Any? = null

    private var routerBinding: DistributionGridBinding? = null
    private var workerBinding: DistributionGridBinding? = null
    private val localPeerBindingsByKey = linkedMapOf<String, DistributionGridBinding>()
    private val externalPeerDescriptorsByKey = linkedMapOf<String, P2PDescriptor>()

    private var discoveryMode = DistributionGridPeerDiscoveryMode.HYBRID
    private var routingPolicy = DistributionGridRoutingPolicy()
    private var memoryPolicy = DistributionGridMemoryPolicy()
    private var durableStore: DistributionGridDurableStore? = null
    private var registryMetadata: DistributionGridRegistryMetadata? = null
    private var trustVerifier: DistributionGridTrustVerifier = DefaultDistributionGridTrustVerifier()
    private var maxHops = 16
    private var rpcTimeoutMillis: Long = 120_000L
    private var maxSessionDurationSeconds: Int = 3600
    private var tracingEnabled = false
    private var traceConfig = TraceConfig()
    @RuntimeState
    private var initialized = false
    @RuntimeState
    private var gridPauseRequested = false
    @RuntimeState
    private val pausedTaskIds = mutableSetOf<String>()

    private var beforeRouteHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var beforeLocalWorkerHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var afterLocalWorkerHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var beforePeerDispatchHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var afterPeerResponseHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var outboundMemoryHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var failureHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null
    private var outcomeTransformationHook: (suspend (MultimodalContent, DistributionGridEnvelope) -> MultimodalContent)? = null
    @RuntimeState
    private val sessionRecordsByPeerKey = linkedMapOf<DistributionGridPeerSessionKey, DistributionGridSessionRecord>()
    @RuntimeState
    private val sessionRecordsById = linkedMapOf<String, DistributionGridSessionRecord>()
    private val bootstrapRegistriesById = linkedMapOf<String, DistributionGridRegistryAdvertisement>()
    private val bootstrapCatalogSourcesById = linkedMapOf<String, DistributionGridBootstrapCatalogSource>()
    @RuntimeState
    private val bootstrapCatalogSourceStatusById = linkedMapOf<String, DistributionGridBootstrapCatalogSourceStatus>()
    @RuntimeState
    private val discoveredRegistriesById = linkedMapOf<String, DistributionGridRegistryAdvertisement>()
    @RuntimeState
    private val discoveredNodeAdvertisementsById = linkedMapOf<DistributionGridDiscoveredNodeKey, DistributionGridNodeAdvertisement>()
    @RuntimeState
    private val activeRegistryLeasesById = linkedMapOf<String, DistributionGridRegistrationLease>()
    @RuntimeState
    private val localRegisteredNodeAdvertisementsById = linkedMapOf<String, DistributionGridNodeAdvertisement>()
    @RuntimeState
    private val localRegistrationLeasesById = linkedMapOf<String, DistributionGridRegistrationLease>()
    @RuntimeState
    private val localRegistrationLeaseIdsByNodeId = linkedMapOf<String, String>()
    @RuntimeState
    private val publicListingRenewJobsById = linkedMapOf<String, Job>()
    @RuntimeState
    private val publicListingRenewStatusById = linkedMapOf<String, DistributionGridPublicListingAutoRenewStatus>()
    @RuntimeState
    private val publicListingRenewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @RuntimeState
    private val gridId = UUID.randomUUID().toString()
    private val defaultMaxNestedDepth = 8
    @RuntimeState
    private var synthesizedPeerOrdinal = 0

    /**
     * Canonical registry scope used for explicit-peer cache identity.
     *
     * @param memberships Sorted registry memberships for one node.
     */
    private data class DistributionGridRegistryScope(
        val memberships: List<String>
    )
    {
        /**
         * Encode the registry scope for wire-facing session metadata.
         *
         * @return Canonical registry token suitable for session records and RPC wrappers.
         */
        fun toRegistryToken(): String
        {
            val canonicalMemberships = memberships
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            if(canonicalMemberships.isEmpty())
            {
                return ""
            }

            return serialize(canonicalMemberships)
        }
    }

    /**
     * Structured cache key for one explicit-peer session.
     *
     * @param peerKey Canonical explicit-peer key.
     * @param registryScope Canonical registry scope for the session.
     */
    private data class DistributionGridPeerSessionKey(
        val peerKey: String,
        val registryScope: DistributionGridRegistryScope
    )

    /**
     * Canonical discovered-node cache identity scoped by registry relationship.
     *
     * @param nodeId Canonical discovered node id.
     * @param registryId Registry that surfaced the node.
     */
    private data class DistributionGridDiscoveredNodeKey(
        val nodeId: String,
        val registryId: String
    )

    /**
     * Resolved remote peer target used by the handoff runtime.
     *
     * @param peerKey Stable peer identity used for session caching.
     * @param descriptor Descriptor used for transport and handshake.
     * @param registryScope Registry scope used for discovered-peer session identity.
     */
    private data class DistributionGridResolvedPeerTarget(
        val peerKey: String,
        val descriptor: P2PDescriptor,
        val registryScope: DistributionGridRegistryScope? = null
    )

    /**
     * Conservative default trust verifier for Phase 6 discovery and registry membership.
     */
    private inner class DefaultDistributionGridTrustVerifier : DistributionGridTrustVerifier
    {
        override suspend fun verifyRegistryAdvertisement(
            candidate: DistributionGridRegistryAdvertisement,
            trustedParents: List<DistributionGridRegistryAdvertisement>
        ): DistributionGridFailure?
        {
            if(candidate.metadata.registryId.isBlank())
            {
                return buildFailure(
                    kind = DistributionGridFailureKind.TRUST_REJECTED,
                    reason = "DistributionGrid rejected a registry advertisement without a stable registry id.",
                    retryable = false
                )
            }

            if(candidate.transport.transportAddress.isBlank())
            {
                return buildFailure(
                    kind = DistributionGridFailureKind.TRUST_REJECTED,
                    reason = "DistributionGrid rejected registry '${candidate.metadata.registryId}' because it had no transport address.",
                    retryable = false
                )
            }

            if(candidate.expiresAtEpochMillis > 0L &&
                candidate.expiresAtEpochMillis <= System.currentTimeMillis()
            )
            {
                return buildFailure(
                    kind = DistributionGridFailureKind.TRUST_REJECTED,
                    reason = "DistributionGrid rejected stale registry advertisement '${candidate.metadata.registryId}'.",
                    retryable = true
                )
            }

            if(trustedParents.isEmpty())
            {
                if(!candidate.metadata.bootstrapTrusted)
                {
                    return buildFailure(
                        kind = DistributionGridFailureKind.TRUST_REJECTED,
                        reason = "DistributionGrid rejected registry '${candidate.metadata.registryId}' because it was not marked as a trusted bootstrap root.",
                        retryable = false
                    )
                }

                return null
            }

            val sameTrustedRegistry = trustedParents.any { parent ->
                parent.metadata.registryId == candidate.metadata.registryId
            }
            if(sameTrustedRegistry)
            {
                return null
            }

            if(candidate.attestationRef.isBlank())
            {
                return buildFailure(
                    kind = DistributionGridFailureKind.TRUST_REJECTED,
                    reason = "DistributionGrid rejected learned registry '${candidate.metadata.registryId}' because it had no attestation reference.",
                    retryable = false
                )
            }

            val trustDomainMatches = trustedParents.any { parent ->
                parent.metadata.trustDomainId.isNotBlank() &&
                    candidate.metadata.trustDomainId.isNotBlank() &&
                    parent.metadata.trustDomainId == candidate.metadata.trustDomainId
            }
            if(!trustDomainMatches)
            {
                return buildFailure(
                    kind = DistributionGridFailureKind.TRUST_REJECTED,
                    reason = "DistributionGrid rejected learned registry '${candidate.metadata.registryId}' because its trust domain did not chain to a trusted parent.",
                    retryable = false
                )
            }

            return null
        }

        override suspend fun verifyNodeAdvertisement(
            registryAdvertisement: DistributionGridRegistryAdvertisement,
            candidate: DistributionGridNodeAdvertisement
        ): DistributionGridFailure?
        {
            val nodeId = candidate.metadata.nodeId.ifBlank {
                candidate.descriptor?.distributionGridMetadata?.nodeId.orEmpty()
            }
            if(nodeId.isBlank())
            {
                return buildFailure(
                    kind = DistributionGridFailureKind.TRUST_REJECTED,
                    reason = "DistributionGrid rejected a node advertisement without an explicit node id.",
                    retryable = false
                )
            }

            if(candidate.registryId != registryAdvertisement.metadata.registryId)
            {
                return buildFailure(
                    kind = DistributionGridFailureKind.TRUST_REJECTED,
                    reason = "DistributionGrid rejected node '$nodeId' because its registry advertisement did not match the trusted registry.",
                    retryable = false
                )
            }

            if(candidate.descriptor == null || candidate.descriptor!!.distributionGridMetadata == null)
            {
                return buildFailure(
                    kind = DistributionGridFailureKind.TRUST_REJECTED,
                    reason = "DistributionGrid rejected node '$nodeId' because it was missing explicit grid metadata.",
                    retryable = false
                )
            }

            if(candidate.expiresAtEpochMillis > 0L &&
                candidate.expiresAtEpochMillis <= System.currentTimeMillis()
            )
            {
                return buildFailure(
                    kind = DistributionGridFailureKind.TRUST_REJECTED,
                    reason = "DistributionGrid rejected stale node advertisement '$nodeId'.",
                    retryable = true
                )
            }

            if(candidate.attestationRef.isBlank())
            {
                return buildFailure(
                    kind = DistributionGridFailureKind.TRUST_REJECTED,
                    reason = "DistributionGrid rejected node '$nodeId' because it had no attestation reference.",
                    retryable = false
                )
            }

            return null
        }
    }

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
        val promptText = request.prompt.text
        val hasGridRpcFramePrefix = hasGridRpcFrame(promptText)
        val gridRpcMessage = parseGridRpcMessage(promptText)
        if(gridRpcMessage != null)
        {
            return handleGridRpcRequest(request, gridRpcMessage)
        }

        if(hasGridRpcFramePrefix)
        {
            return P2PResponse(
                rejection = P2PRejection(
                    errorType = P2PError.json,
                    reason = "DistributionGrid framed RPC payload was malformed."
                )
            )
        }

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
        routingPolicy = policy.deepCopy()
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
        memoryPolicy = policy.copy()
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
     * Store explicit registry metadata when this grid node also exposes registry behavior.
     *
     * @param metadata Registry metadata to assign, or `null` to clear registry behavior.
     * @return This grid for method chaining.
     */
    fun setRegistryMetadata(metadata: DistributionGridRegistryMetadata?): DistributionGrid
    {
        registryMetadata = metadata?.deepCopy()
        markShellDirty()
        return this
    }

    /**
     * Read the configured registry metadata for this grid node.
     *
     * @return Registry metadata or `null` when the node is not acting as a registry.
     */
    fun getRegistryMetadata(): DistributionGridRegistryMetadata?
    {
        return registryMetadata?.deepCopy()
    }

    /**
     * Override the trust verifier used for registry and node advertisement admission.
     *
     * @param verifier Verifier to install.
     * @return This grid for method chaining.
     */
    fun setTrustVerifier(verifier: DistributionGridTrustVerifier): DistributionGrid
    {
        trustVerifier = verifier
        markShellDirty()
        return this
    }

    /**
     * Read the trust verifier currently used for discovery admission.
     *
     * @return Active trust verifier.
     */
    fun getTrustVerifier(): DistributionGridTrustVerifier
    {
        return trustVerifier
    }

    /**
     * Add one bootstrap registry advertisement as a discovery root.
     *
     * @param advertisement Bootstrap registry advertisement to trust as a root.
     * @return This grid for method chaining.
     */
    fun addBootstrapRegistry(advertisement: DistributionGridRegistryAdvertisement): DistributionGrid
    {
        val registryId = advertisement.metadata.registryId.ifBlank {
            advertisement.transport.transportAddress
        }
        require(registryId.isNotBlank()) { "Bootstrap registry id must not be blank." }

        bootstrapRegistriesById[registryId] = advertisement.deepCopy().apply {
            metadata.registryId = registryId
            metadata.bootstrapTrusted = true
            expiresAtEpochMillis = Long.MAX_VALUE
        }
        markShellDirty()
        return this
    }

    /**
     * Add one trusted hosted-registry bootstrap source used to pull public `GRID_REGISTRY` listings.
     *
     * @param source Hosted-registry source configuration.
     * @return This grid for method chaining.
     */
    fun addBootstrapCatalogSource(source: DistributionGridBootstrapCatalogSource): DistributionGrid
    {
        val sourceId = source.sourceId.ifBlank { source.transport.transportAddress }
        require(sourceId.isNotBlank()) { "Bootstrap catalog source id must not be blank." }

        val normalizedQuery = source.query.deepCopy<P2PHostedRegistryQuery>().apply {
            if(P2PHostedListingKind.GRID_REGISTRY !in listingKinds)
            {
                listingKinds = (listingKinds + P2PHostedListingKind.GRID_REGISTRY).distinct().toMutableList()
            }
        }

        bootstrapCatalogSourcesById[sourceId] = source.deepCopy<DistributionGridBootstrapCatalogSource>().apply {
            this.sourceId = sourceId
            this.query = normalizedQuery
        }
        bootstrapCatalogSourceStatusById.putIfAbsent(
            sourceId,
            DistributionGridBootstrapCatalogSourceStatus(sourceId = sourceId)
        )
        markShellDirty()
        return this
    }

    /**
     * Remove one configured hosted-registry bootstrap source.
     *
     * @param sourceId Source identifier to remove.
     * @return This grid for method chaining.
     */
    fun removeBootstrapCatalogSource(sourceId: String): DistributionGrid
    {
        require(sourceId.isNotBlank()) { "Bootstrap catalog source id must not be blank." }
        bootstrapCatalogSourcesById.remove(sourceId)
        bootstrapCatalogSourceStatusById.remove(sourceId)
        markShellDirty()
        return this
    }

    /**
     * Remove one bootstrap registry root.
     *
     * @param registryId Registry id to remove.
     * @return This grid for method chaining.
     */
    fun removeBootstrapRegistry(registryId: String): DistributionGrid
    {
        require(registryId.isNotBlank()) { "Registry id must not be blank." }
        bootstrapRegistriesById.remove(registryId)
        discoveredRegistriesById.remove(registryId)
        discoveredNodeAdvertisementsById.entries.removeIf { it.key.registryId == registryId }
        activeRegistryLeasesById.remove(registryId)
        syncRegistryMembershipsFromActiveLeases()
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
     * Store the RPC timeout applied to all outbound grid calls.
     *
     * @param millis Timeout in milliseconds. Must be positive.
     * @return This grid for method chaining.
     */
    fun setRpcTimeout(millis: Long): DistributionGrid
    {
        require(millis > 0) { "RPC timeout must be positive." }
        rpcTimeoutMillis = millis
        return this
    }

    /**
     * Store the maximum session duration cap for handshake negotiation.
     *
     * @param seconds Maximum session lifetime in seconds. Must be positive.
     * @return This grid for method chaining.
     */
    fun setMaxSessionDuration(seconds: Int): DistributionGrid
    {
        require(seconds > 0) { "Max session duration must be positive." }
        maxSessionDurationSeconds = seconds
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
     * Export the current grid trace using the same tracer path as the mature TPipe harnesses.
     *
     * @param format Output format to export.
     * @return Formatted trace report.
     */
    fun getTraceReport(format: TraceFormat = traceConfig.outputFormat): String
    {
        return PipeTracer.exportTrace(gridId, format)
    }

    /**
     * Read structured failure analysis for the current grid trace when tracing is enabled.
     *
     * @return Failure analysis or `null` when tracing is disabled.
     */
    fun getFailureAnalysis(): FailureAnalysis?
    {
        return if(tracingEnabled) PipeTracer.getFailureAnalysis(gridId) else null
    }

    /**
     * Resume one previously checkpointed task from the configured durable store.
     *
     * This is the explicit public resume path for Phase 7. Normal `execute(...)` behavior remains unchanged.
     *
     * @param taskId Stable task identifier to resume.
     * @return Resumed terminal content or boundary failure content when the checkpoint cannot be resumed.
     */
    suspend fun resumeTask(taskId: String): MultimodalContent
    {
        val readinessFailure = validateExecutionReadiness()
        if(readinessFailure != null)
        {
            return buildBoundaryFailureContent(MultimodalContent(), readinessFailure)
        }

        val store = durableStore
        if(store == null)
        {
            return buildBoundaryFailureContent(
                MultimodalContent(),
                buildFailure(
                    kind = DistributionGridFailureKind.DURABILITY_FAILURE,
                    reason = "DistributionGrid cannot resume task '$taskId' without a configured durable store.",
                    retryable = true
                )
            )
        }

        val durableState = try
        {
            store.resumeState(taskId)
        }
        catch(error: Throwable)
        {
            trace(
                TraceEventType.DISTRIBUTION_GRID_FAILURE,
                TracePhase.CONTEXT_PREPARATION,
                metadata = mapOf(
                    "taskId" to taskId,
                    "checkpointReason" to "resume-load"
                ),
                error = error
            )
            return buildBoundaryFailureContent(
                MultimodalContent(),
                buildFailure(
                    kind = DistributionGridFailureKind.DURABILITY_FAILURE,
                    reason = error.message ?: "DistributionGrid could not load durable state for task '$taskId'.",
                    retryable = true
                )
            )
        }

        if(durableState == null)
        {
            return buildBoundaryFailureContent(
                MultimodalContent(),
                buildFailure(
                    kind = DistributionGridFailureKind.DURABILITY_FAILURE,
                    reason = "DistributionGrid could not find resumable durable state for task '$taskId'.",
                    retryable = true
                )
            )
        }

        if(durableState.completed && durableState.checkpointReason != "after-peer-response")
        {
            val finalContent = durableState.envelope.content.deepCopy()
            finalContent.metadata[ENVELOPE_METADATA_KEY] = durableState.envelope.deepCopy()
            durableState.envelope.latestOutcome?.let { finalContent.metadata[OUTCOME_METADATA_KEY] = it.deepCopy() }
            durableState.envelope.latestFailure?.let { finalContent.metadata[FAILURE_METADATA_KEY] = it.deepCopy() }
            return finalContent
        }

        val checkpointOutcome = durableState.envelope.latestOutcome?.deepCopy()
        val resumedEnvelope = durableState.envelope.deepCopy().apply {
            durableStateKey = taskId
            completed = false
            latestOutcome = null
            attributes.remove(SINGLE_NODE_MODE_ATTRIBUTE)
            content = content.deepCopy().apply {
                metadata.remove(PAUSED_METADATA_KEY)
                metadata.remove(PAUSED_TASK_ID_METADATA_KEY)
                metadata.remove(PAUSED_REASON_METADATA_KEY)
            }
        }
        revalidateResumedSession(resumedEnvelope)

        trace(
            TraceEventType.DISTRIBUTION_GRID_RESUME,
            TracePhase.CONTEXT_PREPARATION,
            metadata = mapOf(
                "taskId" to taskId,
                "checkpointReason" to durableState.checkpointReason
            )
        )

        return when(durableState.checkpointReason)
        {
            "before-peer-dispatch" ->
            {
                val resumedLocalNodeId = resolveCurrentNodeId()
                val resumedLocalTransport = resolveCurrentTransport()
                val resumedPeerNodeId = resumedEnvelope.currentNodeId
                val resumedDirective = DistributionGridDirective(
                    kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                    targetNodeId = resumedPeerNodeId,
                    targetPeerId = "",
                    notes = "Resumed from durable checkpoint 'before-peer-dispatch'."
                )
                resumedEnvelope.currentNodeId = resumedLocalNodeId
                resumedEnvelope.currentTransport = resumedLocalTransport
                dispatchExplicitPeerHandoff(resumedEnvelope, resumedDirective, System.currentTimeMillis())
            }

            "after-local-worker",
            "after-peer-response" ->
            {
                if(resumedEnvelope.latestFailure != null)
                {
                    finalizeFailedEnvelope(
                        envelope = resumedEnvelope,
                        failure = resumedEnvelope.latestFailure!!,
                        startedAt = System.currentTimeMillis(),
                        completedAt = System.currentTimeMillis(),
                        directiveKind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER,
                        recordTerminalHop = false
                    )
                }
                else
                {
                    val returnMode = if(durableState.checkpointReason == "after-peer-response")
                    {
                        checkpointOutcome?.returnMode ?: resolveReturnMode(resumedEnvelope.routingPolicy)
                    }
                    else
                    {
                        resolveReturnMode(resumedEnvelope.routingPolicy)
                    }
                    finalizeSuccessfulEnvelope(
                        envelope = resumedEnvelope,
                        returnMode = returnMode,
                        startedAt = System.currentTimeMillis(),
                        completedAt = System.currentTimeMillis(),
                        directiveKind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER,
                        recordTerminalHop = durableState.checkpointReason == "after-local-worker"
                    )
                }
            }

            else ->
            {
                executeEnvelopeLocally(resumedEnvelope)
            }
        }
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
            gridPauseRequested = false
            pausedTaskIds.clear()

            val autoBootstrapSources = bootstrapCatalogSourcesById.values
                .filter { it.autoPullOnInit }
                .map { it.sourceId }
            if(autoBootstrapSources.isNotEmpty())
            {
                pullTrustedBootstrapCatalogs(autoBootstrapSources)
            }

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
                "wasPaused" to gridPauseRequested
            )
        )

        initialized = false
        gridPauseRequested = false
        pausedTaskIds.clear()
        synthesizedPeerOrdinal = 0
        invalidateAllSessions("Runtime state cleared.")
    }

    /**
     * Clear the accumulated trace history for this grid instance.
     */
    fun clearTrace()
    {
        PipeTracer.clearTrace(gridId)
    }

    /**
     * Request a grid-wide pause at the next safe checkpoint.
     */
    fun pause()
    {
        gridPauseRequested = true
        trace(
            TraceEventType.DISTRIBUTION_GRID_PAUSE,
            TracePhase.ORCHESTRATION,
            metadata = mapOf("requested" to true, "scope" to "grid")
        )
    }

    /**
     * Request a per-task pause at the next safe checkpoint.
     *
     * @param taskId Stable task identifier to pause.
     */
    fun pause(taskId: String)
    {
        pausedTaskIds.add(taskId)
        trace(
            TraceEventType.DISTRIBUTION_GRID_PAUSE,
            TracePhase.ORCHESTRATION,
            metadata = mapOf("requested" to true, "scope" to "task", "taskId" to taskId)
        )
    }

    /**
     * Clear a previously requested grid-wide pause.
     */
    fun resume()
    {
        gridPauseRequested = false
        trace(
            TraceEventType.DISTRIBUTION_GRID_RESUME,
            TracePhase.ORCHESTRATION,
            metadata = mapOf("requested" to false, "scope" to "grid")
        )
    }

    /**
     * Clear a previously requested per-task pause.
     *
     * @param taskId Stable task identifier to resume.
     */
    fun resume(taskId: String)
    {
        pausedTaskIds.remove(taskId)
        trace(
            TraceEventType.DISTRIBUTION_GRID_RESUME,
            TracePhase.ORCHESTRATION,
            metadata = mapOf("requested" to false, "scope" to "task", "taskId" to taskId)
        )
    }

    /**
     * Check whether the grid-wide pause has been requested.
     *
     * @return `true` when a grid-wide pause has been requested.
     */
    fun isPaused(): Boolean
    {
        return gridPauseRequested
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
     * Read the configured bootstrap registry ids.
     *
     * @return Ordered bootstrap registry ids.
     */
    fun getBootstrapRegistryIds(): List<String>
    {
        return bootstrapRegistriesById.keys.toList()
    }

    /**
     * Read the configured hosted-registry bootstrap source ids.
     *
     * @return Ordered hosted-registry bootstrap source ids.
     */
    fun getBootstrapCatalogSourceIds(): List<String>
    {
        return bootstrapCatalogSourcesById.keys.toList()
    }

    /**
     * Read the verified discovered registry ids.
     *
     * @return Ordered discovered registry ids.
     */
    fun getDiscoveredRegistryIds(): List<String>
    {
        return discoveredRegistriesById.keys.toList()
    }

    /**
     * Read the verified discovered node ids.
     *
     * @return Ordered discovered node ids.
     */
    fun getDiscoveredNodeIds(): List<String>
    {
        return discoveredNodeAdvertisementsById.keys
            .map { it.nodeId }
            .distinct()
            .toList()
    }

    /**
     * Read the currently active registry lease ids.
     *
     * @return Ordered active lease ids.
     */
    fun getActiveRegistryLeaseIds(): List<String>
    {
        return activeRegistryLeasesById.values.map { it.leaseId }
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
     * Read the configured RPC timeout applied to outbound grid calls.
     *
     * @return Stored RPC timeout in milliseconds.
     */
    fun getRpcTimeout(): Long
    {
        return rpcTimeoutMillis
    }

    /**
     * Read the configured session-duration cap used during handshake negotiation.
     *
     * @return Stored maximum session duration in seconds.
     */
    fun getMaxSessionDuration(): Int
    {
        return maxSessionDurationSeconds
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
     * Probe all configured bootstrap registries and cache the verified advertisements that respond.
     *
     * @return Verified registry advertisements discovered during the probe pass.
     */
    suspend fun probeTrustedRegistries(): List<DistributionGridRegistryAdvertisement>
    {
        validateRegistryClientReadiness()?.let { throw IllegalStateException(it.reason) }
        purgeExpiredDiscoveryState()

        val discoveredAdvertisements = mutableListOf<DistributionGridRegistryAdvertisement>()
        bootstrapRegistriesById.values.toList().forEach { bootstrapRegistry ->
            trace(
                TraceEventType.DISTRIBUTION_GRID_REGISTRY_PROBE,
                TracePhase.P2P_TRANSPORT,
                metadata = mapOf(
                    "registryId" to bootstrapRegistry.metadata.registryId,
                    "targetNodeId" to bootstrapRegistry.metadata.registryId,
                    "operation" to "probe"
                )
            )
            val response = sendRegistryRpcRequest(
                advertisement = bootstrapRegistry,
                rpcMessage = DistributionGridRpcMessage(
                    messageType = DistributionGridRpcMessageType.PROBE_REGISTRY,
                    senderNodeId = resolveCurrentNodeId(),
                    targetId = bootstrapRegistry.metadata.registryId,
                    protocolVersion = resolveRegistryProtocolVersion(bootstrapRegistry),
                    payloadType = REGISTRY_ADVERTISEMENT_PAYLOAD_TYPE,
                    payloadJson = ""
                )
            )

            if(response.rejection != null)
            {
                return@forEach
            }

            val rpcResponse = parseGridRpcMessage(response.output?.text.orEmpty()) ?: return@forEach
            if(rpcResponse.messageType != DistributionGridRpcMessageType.PROBE_REGISTRY ||
                rpcResponse.payloadType != REGISTRY_ADVERTISEMENT_PAYLOAD_TYPE
            )
            {
                return@forEach
            }
            val advertisement = deserialize<DistributionGridRegistryAdvertisement>(
                rpcResponse.payloadJson,
                useRepair = false
            ) ?: return@forEach

            val failure = trustVerifier.verifyRegistryAdvertisement(
                candidate = advertisement,
                trustedParents = listOf(bootstrapRegistry)
            )
            if(failure == null)
            {
                discoveredRegistriesById[advertisement.metadata.registryId] = advertisement.deepCopy()
                discoveredAdvertisements.add(advertisement.deepCopy())
                trace(
                    TraceEventType.DISTRIBUTION_GRID_DISCOVERY_ADMISSION,
                    TracePhase.VALIDATION,
                    metadata = mapOf(
                        "registryId" to advertisement.metadata.registryId,
                        "accepted" to true,
                        "sourceId" to bootstrapRegistry.metadata.registryId,
                        "reason" to "trusted-bootstrap-registry"
                    )
                )
            }
            else
            {
                trace(
                    TraceEventType.DISTRIBUTION_GRID_DISCOVERY_ADMISSION,
                    TracePhase.VALIDATION,
                    metadata = mapOf(
                        "registryId" to advertisement.metadata.registryId,
                        "accepted" to false,
                        "sourceId" to bootstrapRegistry.metadata.registryId,
                        "reason" to failure.reason
                    )
                )
            }
        }

        return discoveredAdvertisements
    }

    /**
     * Register this node with one trusted registry and store the returned lease.
     *
     * @param registryId Target registry id.
     * @param requestedLeaseSeconds Optional requested lease duration override.
     * @return Granted lease or `null` when the registry rejected the registration.
     */
    suspend fun registerWithRegistry(
        registryId: String,
        requestedLeaseSeconds: Int? = null
    ): DistributionGridRegistrationLease?
    {
        validateRegistryClientReadiness()?.let { throw IllegalStateException(it.reason) }

        val registryAdvertisement = resolveRegistryAdvertisement(registryId) ?: return null
        val request = DistributionGridRegistrationRequest(
            leaseId = "",
            descriptor = p2pDescriptor?.deepCopy(),
            metadata = requireNotNull(p2pDescriptor?.distributionGridMetadata).deepCopy(),
            requestedLeaseSeconds = requestedLeaseSeconds ?: registryAdvertisement.metadata.defaultLeaseSeconds,
            healthSummary = "healthy",
            restateCapabilities = true
        )

        val response = sendRegistryRpcRequest(
            advertisement = registryAdvertisement,
            rpcMessage = DistributionGridRpcMessage(
                messageType = DistributionGridRpcMessageType.REGISTER_NODE,
                senderNodeId = resolveCurrentNodeId(),
                targetId = registryId,
                protocolVersion = resolveRegistryProtocolVersion(registryAdvertisement),
                payloadType = REGISTRATION_REQUEST_PAYLOAD_TYPE,
                payloadJson = serialize(request)
            )
        )
        trace(
            TraceEventType.DISTRIBUTION_GRID_REGISTRY_REGISTRATION,
            TracePhase.P2P_TRANSPORT,
            metadata = mapOf(
                "registryId" to registryId,
                "nodeId" to request.metadata.nodeId.ifBlank { resolveCurrentNodeId() },
                "requestedLeaseSeconds" to request.requestedLeaseSeconds
            )
        )

        val lease = extractRegistryLeaseFromResponse(
            response = response,
            expectedMessageType = DistributionGridRpcMessageType.REGISTER_NODE,
            expectedRegistryId = registryId,
            expectedNodeId = request.metadata.nodeId.ifBlank { resolveCurrentNodeId() }
        ) ?: return null
        activeRegistryLeasesById[registryId] = lease.deepCopy()
        syncRegistryMembershipsFromActiveLeases()
        trace(
            TraceEventType.DISTRIBUTION_GRID_REGISTRY_REGISTRATION,
            TracePhase.CLEANUP,
            metadata = mapOf(
                "registryId" to registryId,
                "leaseId" to lease.leaseId,
                "grantedLeaseSeconds" to lease.grantedLeaseSeconds,
                "accepted" to true
            )
        )
        return lease.deepCopy()
    }

    /**
     * Renew one active registry lease explicitly.
     *
     * @param registryId Registry id whose lease should be renewed.
     * @return Refreshed lease or `null` when renewal failed.
     */
    suspend fun renewRegistryLease(registryId: String): DistributionGridRegistrationLease?
    {
        validateRegistryClientReadiness()?.let { throw IllegalStateException(it.reason) }

        val existingLease = activeRegistryLeasesById[registryId] ?: return null
        val registryAdvertisement = resolveRegistryAdvertisement(registryId) ?: return null
        val request = DistributionGridRegistrationRequest(
            leaseId = existingLease.leaseId,
            descriptor = p2pDescriptor?.deepCopy(),
            metadata = requireNotNull(p2pDescriptor?.distributionGridMetadata).deepCopy(),
            requestedLeaseSeconds = existingLease.grantedLeaseSeconds,
            healthSummary = "healthy",
            restateCapabilities = true
        )

        val response = sendRegistryRpcRequest(
            advertisement = registryAdvertisement,
            rpcMessage = DistributionGridRpcMessage(
                messageType = DistributionGridRpcMessageType.RENEW_LEASE,
                senderNodeId = resolveCurrentNodeId(),
                targetId = registryId,
                protocolVersion = resolveRegistryProtocolVersion(registryAdvertisement),
                payloadType = REGISTRATION_REQUEST_PAYLOAD_TYPE,
                payloadJson = serialize(request)
            )
        )
        trace(
            TraceEventType.DISTRIBUTION_GRID_REGISTRY_LEASE_RENEWAL,
            TracePhase.P2P_TRANSPORT,
            metadata = mapOf(
                "registryId" to registryId,
                "leaseId" to existingLease.leaseId,
                "requestedLeaseSeconds" to request.requestedLeaseSeconds
            )
        )

        val renewedLease = extractRegistryLeaseFromResponse(
            response = response,
            expectedMessageType = DistributionGridRpcMessageType.RENEW_LEASE,
            expectedRegistryId = registryId,
            expectedNodeId = request.metadata.nodeId.ifBlank { resolveCurrentNodeId() }
        ) ?: return null
        activeRegistryLeasesById[registryId] = renewedLease.deepCopy()
        syncRegistryMembershipsFromActiveLeases()
        trace(
            TraceEventType.DISTRIBUTION_GRID_REGISTRY_LEASE_RENEWAL,
            TracePhase.CLEANUP,
            metadata = mapOf(
                "registryId" to registryId,
                "leaseId" to renewedLease.leaseId,
                "grantedLeaseSeconds" to renewedLease.grantedLeaseSeconds,
                "accepted" to true
            )
        )
        return renewedLease.deepCopy()
    }

    /**
     * Renew near-expiry leases and drop expired memberships.
     *
     * @param nowEpochMillis Current time used for expiry checks.
     * @return Refreshed leases produced by this tick.
     */
    suspend fun tickRegistryMemberships(
        nowEpochMillis: Long = System.currentTimeMillis()
    ): List<DistributionGridRegistrationLease>
    {
        validateRegistryClientReadiness()?.let { throw IllegalStateException(it.reason) }
        purgeExpiredDiscoveryState()

        val renewedLeases = mutableListOf<DistributionGridRegistrationLease>()
        val expiredRegistryIds = activeRegistryLeasesById
            .filterValues { it.expiresAtEpochMillis <= nowEpochMillis }
            .keys
            .toList()

        expiredRegistryIds.forEach { activeRegistryLeasesById.remove(it) }
        if(expiredRegistryIds.isNotEmpty())
        {
            syncRegistryMembershipsFromActiveLeases()
        }

        activeRegistryLeasesById.toMap().forEach { (registryId, lease) ->
            val renewalWindowMillis = resolveLeaseRenewalWindowMillis(lease)
            if(lease.expiresAtEpochMillis - nowEpochMillis <= renewalWindowMillis)
            {
                val renewedLease = renewRegistryLease(registryId)
                if(renewedLease != null)
                {
                    renewedLeases.add(renewedLease)
                }
            }
        }

        return renewedLeases
    }

    /**
     * Query trusted registries for candidate downstream nodes and cache the verified advertisements.
     *
     * @param query Structured candidate query.
     * @param registryIds Optional explicit registry ids to query. Blank means all known registries.
     * @return Verified node advertisements returned by the queried registries.
     */
    suspend fun queryRegistries(
        query: DistributionGridRegistryQuery,
        registryIds: List<String> = emptyList()
    ): List<DistributionGridNodeAdvertisement>
    {
        validateRegistryClientReadiness()?.let { throw IllegalStateException(it.reason) }
        purgeExpiredDiscoveryState()

        val targetRegistryIds = if(registryIds.isNotEmpty())
        {
            registryIds
        }
        else
        {
            (discoveredRegistriesById.keys + bootstrapRegistriesById.keys).distinct()
        }

        val verifiedCandidates = mutableListOf<DistributionGridNodeAdvertisement>()
        targetRegistryIds.forEach { registryId ->
            val registryAdvertisement = resolveRegistryAdvertisement(registryId) ?: return@forEach
            trace(
                TraceEventType.DISTRIBUTION_GRID_REGISTRY_QUERY,
                TracePhase.P2P_TRANSPORT,
                metadata = mapOf(
                    "registryId" to registryId,
                    "queryKinds" to query.requiredCapabilities.joinToString(","),
                    "queryTransport" to query.acceptedTransports.joinToString(",") { transport -> transport.name }
                )
            )
            val response = sendRegistryRpcRequest(
                advertisement = registryAdvertisement,
                rpcMessage = DistributionGridRpcMessage(
                    messageType = DistributionGridRpcMessageType.QUERY_REGISTRY,
                    senderNodeId = resolveCurrentNodeId(),
                    targetId = registryId,
                    protocolVersion = resolveRegistryProtocolVersion(registryAdvertisement),
                    payloadType = REGISTRY_QUERY_PAYLOAD_TYPE,
                    payloadJson = serialize(query)
                )
            )

            if(response.rejection != null)
            {
                return@forEach
            }

            val rpcResponse = parseGridRpcMessage(response.output?.text.orEmpty()) ?: return@forEach
            if(rpcResponse.messageType != DistributionGridRpcMessageType.QUERY_REGISTRY ||
                rpcResponse.payloadType != REGISTRY_QUERY_RESULT_PAYLOAD_TYPE
            )
            {
                return@forEach
            }
            val result = deserialize<DistributionGridRegistryQueryResult>(
                rpcResponse.payloadJson,
                useRepair = false
            ) ?: return@forEach
            if(!result.accepted)
            {
                return@forEach
            }

            result.candidates.forEach { candidate ->
                val failure = trustVerifier.verifyNodeAdvertisement(registryAdvertisement, candidate)
                if(failure == null)
                {
                    val resolvedNodeId = resolveDiscoveredNodeId(candidate)
                    if(resolvedNodeId.isNotBlank())
                    {
                        val normalizedCandidate = canonicalizeDiscoveredNodeAdvertisement(
                            candidate = candidate,
                            resolvedNodeId = resolvedNodeId
                        )
                        discoveredNodeAdvertisementsById[buildDiscoveredNodeKey(
                            nodeId = resolvedNodeId,
                            registryId = normalizedCandidate.registryId
                        )] = normalizedCandidate.deepCopy()
                        verifiedCandidates.add(normalizedCandidate.deepCopy())
                        trace(
                            TraceEventType.DISTRIBUTION_GRID_DISCOVERY_ADMISSION,
                            TracePhase.VALIDATION,
                            metadata = mapOf(
                                "registryId" to registryId,
                                "targetNodeId" to resolvedNodeId,
                                "accepted" to true,
                                "reason" to "trusted-node-advertisement"
                            )
                        )
                    }
                }
                else
                {
                    trace(
                        TraceEventType.DISTRIBUTION_GRID_DISCOVERY_ADMISSION,
                        TracePhase.VALIDATION,
                        metadata = mapOf(
                            "registryId" to registryId,
                            "targetNodeId" to resolveDiscoveredNodeId(candidate),
                            "accepted" to false,
                            "reason" to failure.reason
                        )
                    )
                }
            }
        }

        return verifiedCandidates
    }

    /**
     * Pull trusted `GRID_REGISTRY` listings from configured hosted-registry bootstrap sources and admit verified
     * registry advertisements into live discovery state.
     *
     * @param sourceIds Optional explicit source ids. Blank means all configured sources.
     * @return Registry advertisements that were accepted into discovery state.
     */
    suspend fun pullTrustedBootstrapCatalogs(
        sourceIds: List<String> = emptyList()
    ): List<DistributionGridRegistryAdvertisement>
    {
        validateRegistryClientReadiness()?.let { throw IllegalStateException(it.reason) }
        purgeExpiredDiscoveryState()

        val targetSourceIds = if(sourceIds.isNotEmpty())
        {
            sourceIds
        }
        else
        {
            bootstrapCatalogSourcesById.keys.toList()
        }

        val acceptedAdvertisements = mutableListOf<DistributionGridRegistryAdvertisement>()
        targetSourceIds.forEach { sourceId ->
            val source = bootstrapCatalogSourcesById[sourceId] ?: return@forEach
            val pullStartedAt = System.currentTimeMillis()
            trace(
                TraceEventType.DISTRIBUTION_GRID_BOOTSTRAP_CATALOG_PULL,
                TracePhase.P2P_TRANSPORT,
                metadata = mapOf(
                    "sourceId" to sourceId,
                    "operation" to "start"
                )
            )
            bootstrapCatalogSourceStatusById[sourceId] =
                (bootstrapCatalogSourceStatusById[sourceId]
                    ?: DistributionGridBootstrapCatalogSourceStatus(sourceId = sourceId)).copy(
                    lastPullAttemptEpochMillis = pullStartedAt
                )
            val result = P2PHostedRegistryClient.searchListings(
                transport = source.transport,
                query = source.query,
                authBody = source.authBody,
                transportAuthBody = source.transportAuthBody
            )
            if(!result.accepted)
            {
                trace(
                    TraceEventType.DISTRIBUTION_GRID_BOOTSTRAP_CATALOG_PULL,
                    TracePhase.CLEANUP,
                    metadata = mapOf(
                        "sourceId" to sourceId,
                        "operation" to "rejected",
                        "acceptedCount" to 0,
                        "trustRejectedCount" to 0,
                        "reason" to result.rejectionReason.ifBlank {
                            "Hosted bootstrap catalog pull was rejected."
                        }
                    )
                )
                bootstrapCatalogSourceStatusById[sourceId] =
                    (bootstrapCatalogSourceStatusById[sourceId]
                        ?: DistributionGridBootstrapCatalogSourceStatus(sourceId = sourceId)).copy(
                        lastPullAttemptEpochMillis = pullStartedAt,
                        lastFailureReason = result.rejectionReason.ifBlank {
                            "Hosted bootstrap catalog pull was rejected."
                        }
                    )
                return@forEach
            }

            val trustedParents = (bootstrapRegistriesById.values + discoveredRegistriesById.values)
                .map { it.deepCopy<DistributionGridRegistryAdvertisement>() }
            var acceptedCount = 0
            var trustRejectedCount = 0

            result.results.forEach { listing ->
                val candidate = listing.gridRegistryAdvertisement?.deepCopy<DistributionGridRegistryAdvertisement>()
                    ?: return@forEach
                if(candidate.attestationRef.isBlank() && listing.attestationRef.isNotBlank())
                {
                    candidate.attestationRef = listing.attestationRef
                }
                val failure = trustVerifier.verifyRegistryAdvertisement(
                    candidate = candidate,
                    trustedParents = trustedParents
                )
                if(failure == null)
                {
                    discoveredRegistriesById[candidate.metadata.registryId] = candidate.deepCopy()
                    acceptedAdvertisements.add(candidate.deepCopy())
                    acceptedCount++
                    trace(
                        TraceEventType.DISTRIBUTION_GRID_DISCOVERY_ADMISSION,
                        TracePhase.VALIDATION,
                        metadata = mapOf(
                            "sourceId" to sourceId,
                            "registryId" to candidate.metadata.registryId,
                            "accepted" to true,
                            "reason" to "trusted-hosted-bootstrap-listing"
                        )
                    )
                }
                else
                {
                    trustRejectedCount++
                    trace(
                        TraceEventType.DISTRIBUTION_GRID_DISCOVERY_ADMISSION,
                        TracePhase.VALIDATION,
                        metadata = mapOf(
                            "sourceId" to sourceId,
                            "registryId" to candidate.metadata.registryId,
                            "accepted" to false,
                            "reason" to failure.reason
                        )
                    )
                }
            }

            trace(
                TraceEventType.DISTRIBUTION_GRID_BOOTSTRAP_CATALOG_PULL,
                TracePhase.CLEANUP,
                metadata = mapOf(
                    "sourceId" to sourceId,
                    "operation" to "complete",
                    "acceptedCount" to acceptedCount,
                    "trustRejectedCount" to trustRejectedCount
                )
            )

            bootstrapCatalogSourceStatusById[sourceId] =
                (bootstrapCatalogSourceStatusById[sourceId]
                    ?: DistributionGridBootstrapCatalogSourceStatus(sourceId = sourceId)).copy(
                    lastPullAttemptEpochMillis = pullStartedAt,
                    lastSuccessfulPullEpochMillis = System.currentTimeMillis(),
                    lastFailureReason = "",
                    acceptedRegistryCount = acceptedCount,
                    trustRejectedCount = trustRejectedCount
                )
        }

        return acceptedAdvertisements
    }

    /**
     * Publish the current node as a public `GRID_NODE` listing on one hosted registry.
     */
    suspend fun publishPublicNodeListing(
        transport: P2PTransport,
        options: DistributionGridPublicListingOptions = DistributionGridPublicListingOptions(),
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val listing = buildPublicNodeHostedListing(options)
            ?: return buildHostedRegistryMutationFailure(
                "DistributionGrid cannot publish a public node listing without a node descriptor and grid metadata."
            )

        val result = P2PHostedRegistryClient.publishListing(
            transport = transport,
            request = P2PHostedRegistryPublishRequest(
                listing = listing,
                requestedLeaseSeconds = options.requestedLeaseSeconds
            ),
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
        tracePublicListingOperation(
            operation = "publish",
            listingKind = P2PHostedListingKind.GRID_NODE,
            result = result,
            requestedLeaseSeconds = options.requestedLeaseSeconds,
            transport = transport
        )
        return result
    }

    /**
     * Update one previously published public node listing.
     */
    suspend fun updatePublicNodeListing(
        transport: P2PTransport,
        listingId: String,
        leaseId: String,
        options: DistributionGridPublicListingOptions = DistributionGridPublicListingOptions(),
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val listing = buildPublicNodeHostedListing(options)
            ?: return buildHostedRegistryMutationFailure(
                "DistributionGrid cannot update a public node listing without a node descriptor and grid metadata."
            )

        val result = P2PHostedRegistryClient.updateListing(
            transport = transport,
            request = P2PHostedRegistryUpdateRequest(
                listingId = listingId,
                leaseId = leaseId,
                listing = listing,
                requestedLeaseSeconds = options.requestedLeaseSeconds
            ),
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
        tracePublicListingOperation(
            operation = "update",
            listingKind = P2PHostedListingKind.GRID_NODE,
            result = result,
            requestedLeaseSeconds = options.requestedLeaseSeconds,
            transport = transport,
            listingId = listingId
        )
        return result
    }

    /**
     * Renew one previously published public node listing.
     */
    suspend fun renewPublicNodeListing(
        transport: P2PTransport,
        listingId: String,
        leaseId: String,
        requestedLeaseSeconds: Int = 3600,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val result = P2PHostedRegistryClient.renewListing(
            transport = transport,
            request = P2PHostedRegistryRenewRequest(
                listingId = listingId,
                leaseId = leaseId,
                requestedLeaseSeconds = requestedLeaseSeconds
            ),
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
        tracePublicListingOperation(
            operation = "renew",
            listingKind = P2PHostedListingKind.GRID_NODE,
            result = result,
            requestedLeaseSeconds = requestedLeaseSeconds,
            transport = transport,
            listingId = listingId
        )
        return result
    }

    /**
     * Remove one previously published public node listing.
     */
    suspend fun removePublicNodeListing(
        transport: P2PTransport,
        listingId: String,
        leaseId: String,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val result = P2PHostedRegistryClient.removeListing(
            transport = transport,
            request = P2PHostedRegistryRemoveRequest(
                listingId = listingId,
                leaseId = leaseId
            ),
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
        tracePublicListingOperation(
            operation = "remove",
            listingKind = P2PHostedListingKind.GRID_NODE,
            result = result,
            requestedLeaseSeconds = 0,
            transport = transport,
            listingId = listingId
        )
        return result
    }

    /**
     * Publish the current node's registry role as a public `GRID_REGISTRY` listing on one hosted registry.
     */
    suspend fun publishPublicRegistryListing(
        transport: P2PTransport,
        options: DistributionGridPublicListingOptions = DistributionGridPublicListingOptions(),
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val listing = buildPublicRegistryHostedListing(options)
            ?: return buildHostedRegistryMutationFailure(
                "DistributionGrid cannot publish a public registry listing without registry metadata and a node descriptor."
            )

        val result = P2PHostedRegistryClient.publishListing(
            transport = transport,
            request = P2PHostedRegistryPublishRequest(
                listing = listing,
                requestedLeaseSeconds = options.requestedLeaseSeconds
            ),
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
        tracePublicListingOperation(
            operation = "publish",
            listingKind = P2PHostedListingKind.GRID_REGISTRY,
            result = result,
            requestedLeaseSeconds = options.requestedLeaseSeconds,
            transport = transport
        )
        return result
    }

    /**
     * Update one previously published public registry listing.
     */
    suspend fun updatePublicRegistryListing(
        transport: P2PTransport,
        listingId: String,
        leaseId: String,
        options: DistributionGridPublicListingOptions = DistributionGridPublicListingOptions(),
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val listing = buildPublicRegistryHostedListing(options)
            ?: return buildHostedRegistryMutationFailure(
                "DistributionGrid cannot update a public registry listing without registry metadata and a node descriptor."
            )

        val result = P2PHostedRegistryClient.updateListing(
            transport = transport,
            request = P2PHostedRegistryUpdateRequest(
                listingId = listingId,
                leaseId = leaseId,
                listing = listing,
                requestedLeaseSeconds = options.requestedLeaseSeconds
            ),
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
        tracePublicListingOperation(
            operation = "update",
            listingKind = P2PHostedListingKind.GRID_REGISTRY,
            result = result,
            requestedLeaseSeconds = options.requestedLeaseSeconds,
            transport = transport,
            listingId = listingId
        )
        return result
    }

    /**
     * Renew one previously published public registry listing.
     */
    suspend fun renewPublicRegistryListing(
        transport: P2PTransport,
        listingId: String,
        leaseId: String,
        requestedLeaseSeconds: Int = 3600,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val result = P2PHostedRegistryClient.renewListing(
            transport = transport,
            request = P2PHostedRegistryRenewRequest(
                listingId = listingId,
                leaseId = leaseId,
                requestedLeaseSeconds = requestedLeaseSeconds
            ),
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
        tracePublicListingOperation(
            operation = "renew",
            listingKind = P2PHostedListingKind.GRID_REGISTRY,
            result = result,
            requestedLeaseSeconds = requestedLeaseSeconds,
            transport = transport,
            listingId = listingId
        )
        return result
    }

    /**
     * Remove one previously published public registry listing.
     */
    suspend fun removePublicRegistryListing(
        transport: P2PTransport,
        listingId: String,
        leaseId: String,
        authBody: String = "",
        transportAuthBody: String = ""
    ): P2PHostedRegistryMutationResult
    {
        val result = P2PHostedRegistryClient.removeListing(
            transport = transport,
            request = P2PHostedRegistryRemoveRequest(
                listingId = listingId,
                leaseId = leaseId
            ),
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
        tracePublicListingOperation(
            operation = "remove",
            listingKind = P2PHostedListingKind.GRID_REGISTRY,
            result = result,
            requestedLeaseSeconds = 0,
            transport = transport,
            listingId = listingId
        )
        return result
    }

    /**
     * Start an opt-in renewal loop for one hosted public node listing.
     */
    fun startPublicNodeListingAutoRenew(
        transport: P2PTransport,
        listingId: String,
        leaseId: String,
        requestedLeaseSeconds: Int = 3600,
        renewEveryMillis: Long = (requestedLeaseSeconds.coerceAtLeast(1) * 1000L / 2L).coerceAtLeast(1_000L),
        authBody: String = "",
        transportAuthBody: String = ""
    ): String
    {
        require(renewEveryMillis > 0L) { "Public listing auto-renew interval must be greater than zero." }
        val renewalId = UUID.randomUUID().toString()
        publicListingRenewStatusById[renewalId] = DistributionGridPublicListingAutoRenewStatus(
            renewalId = renewalId,
            listingId = listingId,
            listingKind = P2PHostedListingKind.GRID_NODE,
            renewEveryMillis = renewEveryMillis,
            requestedLeaseSeconds = requestedLeaseSeconds,
            active = true
        )
        publicListingRenewJobsById[renewalId] = publicListingRenewScope.launch {
            while(true)
            {
                delay(renewEveryMillis)
                val attemptTime = System.currentTimeMillis()
                publicListingRenewStatusById[renewalId] = publicListingRenewStatusById[renewalId]
                    ?.copy(lastRenewAttemptEpochMillis = attemptTime, active = true)
                    ?: DistributionGridPublicListingAutoRenewStatus(
                        renewalId = renewalId,
                        listingId = listingId,
                        listingKind = P2PHostedListingKind.GRID_NODE,
                        renewEveryMillis = renewEveryMillis,
                        requestedLeaseSeconds = requestedLeaseSeconds,
                        active = true,
                        lastRenewAttemptEpochMillis = attemptTime
                    )
                val renewResult = renewPublicNodeListing(
                    transport = transport,
                    listingId = listingId,
                    leaseId = leaseId,
                    requestedLeaseSeconds = requestedLeaseSeconds,
                    authBody = authBody,
                    transportAuthBody = transportAuthBody
                )
                val currentStatus = publicListingRenewStatusById[renewalId]
                publicListingRenewStatusById[renewalId] = currentStatus?.copy(
                    active = true,
                    lastRenewAttemptEpochMillis = attemptTime,
                    lastRenewSuccessEpochMillis = if(renewResult.accepted) System.currentTimeMillis()
                    else currentStatus.lastRenewSuccessEpochMillis,
                    lastFailureReason = if(renewResult.accepted) "" else renewResult.rejectionReason
                ) ?: DistributionGridPublicListingAutoRenewStatus(
                    renewalId = renewalId,
                    listingId = listingId,
                    listingKind = P2PHostedListingKind.GRID_NODE,
                    renewEveryMillis = renewEveryMillis,
                    requestedLeaseSeconds = requestedLeaseSeconds,
                    active = true,
                    lastRenewAttemptEpochMillis = attemptTime,
                    lastRenewSuccessEpochMillis = if(renewResult.accepted) System.currentTimeMillis() else 0L,
                    lastFailureReason = if(renewResult.accepted) "" else renewResult.rejectionReason
                )
                trace(
                    TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING_AUTO_RENEW,
                    TracePhase.CLEANUP,
                    metadata = mapOf(
                        "renewalId" to renewalId,
                        "listingId" to listingId,
                        "listingKind" to P2PHostedListingKind.GRID_NODE.name,
                        "accepted" to renewResult.accepted,
                        "reason" to renewResult.rejectionReason,
                        "transportAddress" to transport.transportAddress
                    )
                )
            }
        }
        return renewalId
    }

    /**
     * Start an opt-in renewal loop for one hosted public registry listing.
     */
    fun startPublicRegistryListingAutoRenew(
        transport: P2PTransport,
        listingId: String,
        leaseId: String,
        requestedLeaseSeconds: Int = 3600,
        renewEveryMillis: Long = (requestedLeaseSeconds.coerceAtLeast(1) * 1000L / 2L).coerceAtLeast(1_000L),
        authBody: String = "",
        transportAuthBody: String = ""
    ): String
    {
        val renewalId = startPublicNodeListingAutoRenew(
            transport = transport,
            listingId = listingId,
            leaseId = leaseId,
            requestedLeaseSeconds = requestedLeaseSeconds,
            renewEveryMillis = renewEveryMillis,
            authBody = authBody,
            transportAuthBody = transportAuthBody
        )
        publicListingRenewStatusById[renewalId] = publicListingRenewStatusById[renewalId]
            ?.copy(listingKind = P2PHostedListingKind.GRID_REGISTRY)
            ?: DistributionGridPublicListingAutoRenewStatus(
                renewalId = renewalId,
                listingId = listingId,
                listingKind = P2PHostedListingKind.GRID_REGISTRY,
                renewEveryMillis = renewEveryMillis,
                requestedLeaseSeconds = requestedLeaseSeconds,
                active = true
            )
        return renewalId
    }

    /**
     * Stop one opt-in public listing renewal loop.
     */
    fun stopPublicListingAutoRenew(renewalId: String)
    {
        publicListingRenewJobsById.remove(renewalId)?.cancel()
        publicListingRenewStatusById[renewalId] = publicListingRenewStatusById[renewalId]
            ?.copy(active = false)
            ?: return
        trace(
            TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING_AUTO_RENEW,
            TracePhase.CLEANUP,
            metadata = mapOf(
                "renewalId" to renewalId,
                "operation" to "stop",
                "active" to false
            )
        )
    }

    /**
     * Inspect active public listing renewal loop ids.
     */
    fun getPublicListingAutoRenewIds(): List<String>
    {
        return publicListingRenewJobsById.keys.toList()
    }

    /**
     * Inspect current hosted bootstrap source pull status.
     */
    fun getBootstrapCatalogSourceStatuses(): List<DistributionGridBootstrapCatalogSourceStatus>
    {
        return bootstrapCatalogSourceStatusById.values.map { it.deepCopy() }
    }

    /**
     * Inspect current public hosted-listing auto-renew loop status.
     */
    fun getPublicListingAutoRenewStatuses(): List<DistributionGridPublicListingAutoRenewStatus>
    {
        return publicListingRenewStatusById.values.map { it.deepCopy() }
    }

    /**
     * Clear discovered registries, discovered nodes, and active lease state without removing bootstrap roots.
     */
    fun clearDiscoveredRegistryState()
    {
        discoveredRegistriesById.clear()
        discoveredNodeAdvertisementsById.clear()
        activeRegistryLeasesById.clear()
        syncRegistryMembershipsFromActiveLeases()
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

        if(gridPauseRequested)
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
     * Validate whether the shell is ready to act as a registry client.
     *
     * @return Failure record when registry work must be rejected, or `null` when the shell is ready.
     */
    private fun validateRegistryClientReadiness(): DistributionGridFailure?
    {
        val readinessFailure = validateExecutionReadiness()
        if(readinessFailure != null)
        {
            return readinessFailure
        }

        if(p2pDescriptor?.distributionGridMetadata == null)
        {
            return buildFailure(
                kind = DistributionGridFailureKind.VALIDATION_FAILURE,
                reason = "DistributionGrid requires explicit node metadata before registry discovery can run.",
                retryable = false
            )
        }

        return null
    }

    /**
     * Validate whether the shell is ready to serve registry RPC requests.
     *
     * @return Failure record when registry behavior must be rejected, or `null` when registry behavior is active.
     */
    private fun validateRegistryServerReadiness(): DistributionGridFailure?
    {
        val readinessFailure = validateRegistryClientReadiness()
        if(readinessFailure != null)
        {
            return readinessFailure
        }

        val localRegistryMetadata = registryMetadata
        val actsAsRegistry = p2pDescriptor?.distributionGridMetadata?.actsAsRegistry == true
        if(localRegistryMetadata == null || !actsAsRegistry)
        {
            return buildFailure(
                kind = DistributionGridFailureKind.TRUST_REJECTED,
                reason = "DistributionGrid is not configured to expose registry behavior.",
                retryable = false
            )
        }

        return null
    }

    /**
     * Parse a grid RPC message only when the inbound prompt clearly looks like serialized grid JSON.
     *
     * @param text Prompt text to inspect.
     * @return Parsed grid RPC message or `null` when the prompt is ordinary content.
     */
    private fun parseGridRpcMessage(text: String): DistributionGridRpcMessage?
    {
        val trimmed = text.trim()
        if(trimmed.isBlank() || !trimmed.startsWith(GRID_RPC_FRAME_PREFIX))
        {
            return null
        }

        val payloadText = trimmed.removePrefix(GRID_RPC_FRAME_PREFIX)
        if(payloadText.isEmpty())
        {
            return null
        }

        val payload = when
        {
            payloadText.startsWith("\r\n") -> payloadText.removePrefix("\r\n")
            payloadText.startsWith('\n') -> payloadText.removePrefix("\n")
            else -> return null
        }.trim()
        if(payload.isBlank())
        {
            return null
        }

        return deserialize<DistributionGridRpcMessage>(payload, useRepair = false)
    }

    /**
     * Determine whether one prompt is framed as DistributionGrid RPC traffic.
     *
     * @param text Prompt text to inspect.
     * @return `true` when the prompt begins with the DistributionGrid RPC prefix.
     */
    private fun hasGridRpcFrame(text: String): Boolean
    {
        return text.trim().startsWith(GRID_RPC_FRAME_PREFIX)
    }

    /**
     * Handle one inbound grid RPC request over the normal P2P boundary.
     *
     * @param request Raw inbound P2P request.
     * @param rpcMessage Parsed grid RPC message.
     * @return P2P response carrying a grid RPC reply or a standard boundary rejection.
     */
    private suspend fun handleGridRpcRequest(
        request: P2PRequest,
        rpcMessage: DistributionGridRpcMessage
    ): P2PResponse
    {
        return when(rpcMessage.messageType)
        {
            DistributionGridRpcMessageType.PROBE_REGISTRY ->
            {
                handleProbeRegistryRequest(request, rpcMessage)
            }

            DistributionGridRpcMessageType.REGISTER_NODE ->
            {
                handleRegisterNodeRequest(request, rpcMessage)
            }

            DistributionGridRpcMessageType.RENEW_LEASE ->
            {
                handleRenewLeaseRequest(request, rpcMessage)
            }

            DistributionGridRpcMessageType.QUERY_REGISTRY ->
            {
                handleQueryRegistryRequest(request, rpcMessage)
            }

            DistributionGridRpcMessageType.HANDSHAKE_INIT ->
            {
                handleHandshakeInitRequest(request, rpcMessage)
            }

            DistributionGridRpcMessageType.TASK_HANDOFF ->
            {
                handleTaskHandoffRequest(request, rpcMessage)
            }

            else ->
            {
                P2PResponse(
                    rejection = P2PRejection(
                        errorType = P2PError.configuration,
                        reason = "DistributionGrid does not support inbound RPC '${rpcMessage.messageType.name}' in Phase 5."
                    )
                )
            }
        }
    }

    /**
     * Return the current registry advertisement when this node exposes registry behavior.
     *
     * @param request Raw inbound P2P request.
     * @param rpcMessage Parsed registry probe RPC.
     * @return P2P response carrying the local registry advertisement.
     */
    private suspend fun handleProbeRegistryRequest(
        request: P2PRequest,
        rpcMessage: DistributionGridRpcMessage
    ): P2PResponse
    {
        val registryFailure = validateRegistryServerReadiness()
        if(registryFailure != null)
        {
            return mapFailureToP2PResponse(registryFailure)
        }

        val advertisement = buildLocalRegistryAdvertisement()
        val protocolVersion = resolveInboundRegistryProtocolVersion(rpcMessage)

        return P2PResponse(
            output = buildGridRpcContent(
                DistributionGridRpcMessage(
                    messageType = DistributionGridRpcMessageType.PROBE_REGISTRY,
                    senderNodeId = resolveCurrentNodeId(),
                    targetId = rpcMessage.senderNodeId,
                    protocolVersion = protocolVersion,
                    payloadType = REGISTRY_ADVERTISEMENT_PAYLOAD_TYPE,
                    payloadJson = serialize(advertisement)
                )
            )
        )
    }

    /**
     * Register or refresh one node entry in the local registry directory.
     *
     * @param request Raw inbound P2P request.
     * @param rpcMessage Parsed registration RPC.
     * @return P2P response carrying the granted lease or a boundary rejection.
     */
    private suspend fun handleRegisterNodeRequest(
        request: P2PRequest,
        rpcMessage: DistributionGridRpcMessage
    ): P2PResponse
    {
        val registryFailure = validateRegistryServerReadiness()
        if(registryFailure != null)
        {
            return mapFailureToP2PResponse(registryFailure)
        }

        val registrationRequest = deserialize<DistributionGridRegistrationRequest>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(
                errorType = P2PError.json,
                reason = "DistributionGrid could not deserialize the node registration request."
            )
        )

        val nodeId = registrationRequest.metadata.nodeId.ifBlank {
            registrationRequest.descriptor?.distributionGridMetadata?.nodeId.orEmpty()
        }
        if(nodeId.isBlank())
        {
            return P2PResponse(
                rejection = P2PRejection(
                    errorType = P2PError.configuration,
                    reason = "DistributionGrid node registration requires explicit grid node metadata."
                )
            )
        }

        val lease = grantRegistrationLease(nodeId, registrationRequest, existingLeaseId = null)
        val descriptor = registrationRequest.descriptor ?: buildRemoteDescriptorFromRegistration(request, registrationRequest)
            ?: return P2PResponse(
                rejection = P2PRejection(
                    errorType = P2PError.configuration,
                    reason = "DistributionGrid node registration requires a caller return address when no descriptor is supplied."
                )
            )
        val advertisement = buildRegisteredNodeAdvertisement(
            descriptor = descriptor,
            metadata = registrationRequest.metadata,
            lease = lease
        )
        storeLocalRegistrationLeaseState(
            nodeId = nodeId,
            lease = lease,
            advertisement = advertisement
        )

        return P2PResponse(
            output = buildGridRpcContent(
                DistributionGridRpcMessage(
                    messageType = DistributionGridRpcMessageType.REGISTER_NODE,
                    senderNodeId = resolveCurrentNodeId(),
                    targetId = rpcMessage.senderNodeId,
                    protocolVersion = resolveInboundRegistryProtocolVersion(rpcMessage),
                    payloadType = REGISTRATION_LEASE_PAYLOAD_TYPE,
                    payloadJson = serialize(lease)
                )
            )
        )
    }

    /**
     * Renew one existing node lease in the local registry directory.
     *
     * @param request Raw inbound P2P request.
     * @param rpcMessage Parsed renewal RPC.
     * @return P2P response carrying the refreshed lease or a boundary rejection.
     */
    private suspend fun handleRenewLeaseRequest(
        request: P2PRequest,
        rpcMessage: DistributionGridRpcMessage
    ): P2PResponse
    {
        val registryFailure = validateRegistryServerReadiness()
        if(registryFailure != null)
        {
            return mapFailureToP2PResponse(registryFailure)
        }

        val registrationRequest = deserialize<DistributionGridRegistrationRequest>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(
                errorType = P2PError.json,
                reason = "DistributionGrid could not deserialize the lease-renewal request."
            )
        )

        val nodeId = registrationRequest.metadata.nodeId.ifBlank {
            registrationRequest.descriptor?.distributionGridMetadata?.nodeId.orEmpty()
        }
        val leaseId = registrationRequest.leaseId
        val existingLease = localRegistrationLeasesById[leaseId]
        if(nodeId.isBlank() || existingLease == null || existingLease.nodeId != nodeId)
        {
            return P2PResponse(
                rejection = P2PRejection(
                    errorType = P2PError.configuration,
                    reason = "DistributionGrid could not resolve the requested registry lease for renewal."
                )
            )
        }

        if(existingLease.expiresAtEpochMillis <= System.currentTimeMillis())
        {
            localRegistrationLeasesById.remove(existingLease.leaseId)
            localRegistrationLeaseIdsByNodeId.remove(nodeId)
            localRegisteredNodeAdvertisementsById.remove(nodeId)
            return P2PResponse(
                rejection = P2PRejection(
                    errorType = P2PError.configuration,
                    reason = "DistributionGrid registry lease '$leaseId' has already expired."
                )
            )
        }

        val lease = grantRegistrationLease(
            nodeId = nodeId,
            registrationRequest = registrationRequest,
            existingLeaseId = existingLease.leaseId
        )
        val descriptor = registrationRequest.descriptor
            ?: localRegisteredNodeAdvertisementsById[nodeId]?.descriptor
            ?: buildRemoteDescriptorFromRegistration(request, registrationRequest)
            ?: return P2PResponse(
                rejection = P2PRejection(
                    errorType = P2PError.configuration,
                    reason = "DistributionGrid lease renewal requires a caller return address when no descriptor is supplied."
                )
            )
        val advertisement = buildRegisteredNodeAdvertisement(
            descriptor = descriptor,
            metadata = registrationRequest.metadata,
            lease = lease
        )
        storeLocalRegistrationLeaseState(
            nodeId = nodeId,
            lease = lease,
            advertisement = advertisement
        )

        return P2PResponse(
            output = buildGridRpcContent(
                DistributionGridRpcMessage(
                    messageType = DistributionGridRpcMessageType.RENEW_LEASE,
                    senderNodeId = resolveCurrentNodeId(),
                    targetId = rpcMessage.senderNodeId,
                    protocolVersion = resolveInboundRegistryProtocolVersion(rpcMessage),
                    payloadType = REGISTRATION_LEASE_PAYLOAD_TYPE,
                    payloadJson = serialize(lease)
                )
            )
        )
    }

    /**
     * Evaluate one structured registry query against the local registry directory.
     *
     * @param request Raw inbound P2P request.
     * @param rpcMessage Parsed registry query RPC.
     * @return P2P response carrying the query result.
     */
    private suspend fun handleQueryRegistryRequest(
        request: P2PRequest,
        rpcMessage: DistributionGridRpcMessage
    ): P2PResponse
    {
        val registryFailure = validateRegistryServerReadiness()
        if(registryFailure != null)
        {
            return mapFailureToP2PResponse(registryFailure)
        }

        val query = deserialize<DistributionGridRegistryQuery>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            rejection = P2PRejection(
                errorType = P2PError.json,
                reason = "DistributionGrid could not deserialize the registry query."
            )
        )

        purgeExpiredLocalRegistrationState()

        val registryId = registryMetadata!!.registryId
        val result = DistributionGridRegistryQueryResult(
            registryId = registryId,
            accepted = true,
            rejectionReason = "",
            candidates = localRegisteredNodeAdvertisementsById.values
                .filter { advertisement ->
                    advertisement.lease?.expiresAtEpochMillis?.let { it > System.currentTimeMillis() } ?: false
                }
                .filter { advertisement ->
                    matchesRegistryQuery(advertisement, query)
                }
                .map { it.deepCopy() }
                .toMutableList()
        )

        return P2PResponse(
            output = buildGridRpcContent(
                DistributionGridRpcMessage(
                    messageType = DistributionGridRpcMessageType.QUERY_REGISTRY,
                    senderNodeId = resolveCurrentNodeId(),
                    targetId = rpcMessage.senderNodeId,
                    protocolVersion = resolveInboundRegistryProtocolVersion(rpcMessage),
                    payloadType = REGISTRY_QUERY_RESULT_PAYLOAD_TYPE,
                    payloadJson = serialize(result)
                )
            )
        )
    }

    /**
     * Handle an inbound first-contact handshake request from an explicit remote grid peer.
     *
     * @param request Raw inbound P2P request.
     * @param rpcMessage Parsed handshake RPC wrapper.
     * @return P2P response carrying handshake acknowledgment or session rejection.
     */
    private suspend fun handleHandshakeInitRequest(
        request: P2PRequest,
        rpcMessage: DistributionGridRpcMessage
    ): P2PResponse
    {
        val readinessFailure = validateExecutionReadiness()
        if(readinessFailure != null)
        {
            return mapFailureToP2PResponse(readinessFailure)
        }

        val handshakeRequest = deserialize<DistributionGridHandshakeRequest>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            output = buildGridRpcContent(
                buildFailureRpcMessage(
                    messageType = DistributionGridRpcMessageType.SESSION_REJECT,
                    targetId = rpcMessage.senderNodeId,
                    protocolVersion = rpcMessage.protocolVersion,
                    failure = buildFailure(
                        kind = DistributionGridFailureKind.HANDSHAKE_REJECTED,
                        reason = "DistributionGrid could not deserialize the handshake payload.",
                        retryable = true
                    )
                )
            )
        )

        trace(
            TraceEventType.DISTRIBUTION_GRID_SESSION_HANDSHAKE,
            TracePhase.AGENT_COMMUNICATION,
            metadata = mapOf(
                "senderNodeId" to handshakeRequest.requesterNodeId,
                "transport" to request.transport.transportMethod.name
            )
        )

        val negotiatedPolicy = negotiateHandshakePolicy(
            handshakeRequest = handshakeRequest,
            requestTransport = request.transport
        )

        if(negotiatedPolicy == null)
        {
            val failure = buildFailure(
                kind = DistributionGridFailureKind.HANDSHAKE_REJECTED,
                reason = "DistributionGrid handshake negotiation failed.",
                retryable = true
            )
            return P2PResponse(
                output = buildGridRpcContent(
                buildFailureRpcMessage(
                    messageType = DistributionGridRpcMessageType.SESSION_REJECT,
                    targetId = handshakeRequest.requesterNodeId,
                    protocolVersion = rpcMessage.protocolVersion,
                    failure = failure
                )
            )
            )
        }

        val requestedSessionSeconds = resolveRequestedSessionSeconds(
            handshakeRequest.requestedSessionDurationSeconds
        ) ?: return P2PResponse(
            output = buildGridRpcContent(
                buildFailureRpcMessage(
                    messageType = DistributionGridRpcMessageType.SESSION_REJECT,
                    targetId = handshakeRequest.requesterNodeId,
                    protocolVersion = rpcMessage.protocolVersion,
                    failure = buildFailure(
                        kind = DistributionGridFailureKind.HANDSHAKE_REJECTED,
                        reason = "DistributionGrid handshake requested an invalid session lifetime.",
                        retryable = false
                    )
                )
            )
        )

        val sessionRecord = DistributionGridSessionRecord(
            sessionId = UUID.randomUUID().toString(),
            requesterNodeId = handshakeRequest.requesterNodeId.ifBlank {
                handshakeRequest.requesterMetadata.nodeId
            },
            responderNodeId = resolveCurrentNodeId(),
            registryId = handshakeRequest.registryId,
            negotiatedProtocolVersion = negotiatedPolicy.protocolVersion.deepCopy(),
            negotiatedPolicy = negotiatedPolicy.deepCopy(),
            expiresAtEpochMillis = System.currentTimeMillis() + requestedSessionSeconds * 1000L,
            invalidated = false,
            invalidationReason = ""
        )
        cacheSession(sessionRecord)

        val responsePayload = DistributionGridHandshakeResponse(
            accepted = true,
            negotiatedProtocolVersion = negotiatedPolicy.protocolVersion.deepCopy(),
            negotiatedPolicy = negotiatedPolicy.deepCopy(),
            rejectionReason = "",
            sessionRecord = sessionRecord.deepCopy()
        )

        return P2PResponse(
            output = buildGridRpcContent(
                DistributionGridRpcMessage(
                    messageType = DistributionGridRpcMessageType.HANDSHAKE_ACK,
                    senderNodeId = resolveCurrentNodeId(),
                    targetId = sessionRecord.requesterNodeId,
                    protocolVersion = negotiatedPolicy.protocolVersion.deepCopy(),
                    sessionRef = sessionRecord.toSessionRef(),
                    payloadType = HANDSHAKE_RESPONSE_PAYLOAD_TYPE,
                    payloadJson = serialize(responsePayload)
                )
            )
        )
    }

    /**
     * Handle an inbound remote task handoff once a valid explicit-peer session already exists.
     *
     * @param request Raw inbound P2P request.
     * @param rpcMessage Parsed task handoff RPC wrapper.
     * @return P2P response carrying `TASK_RETURN`, `TASK_FAILURE`, or `SESSION_REJECT`.
     */
    private suspend fun handleTaskHandoffRequest(
        request: P2PRequest,
        rpcMessage: DistributionGridRpcMessage
    ): P2PResponse
    {
        val readinessFailure = validateExecutionReadiness()
        if(readinessFailure != null)
        {
            return mapFailureToP2PResponse(readinessFailure)
        }

        val sessionRef = rpcMessage.sessionRef ?: return P2PResponse(
            output = buildGridRpcContent(
                buildFailureRpcMessage(
                    messageType = DistributionGridRpcMessageType.SESSION_REJECT,
                    targetId = rpcMessage.senderNodeId,
                    protocolVersion = rpcMessage.protocolVersion,
                    failure = buildFailure(
                        kind = DistributionGridFailureKind.SESSION_REJECTED,
                        reason = "DistributionGrid task handoff requires a valid session reference.",
                        retryable = true
                    )
                )
            )
        )

        val sessionRecord = resolveInboundSession(
            sessionRef = sessionRef,
            rpcMessage = rpcMessage,
            senderNodeId = rpcMessage.senderNodeId.ifBlank { request.transport.transportAddress }
        ) ?: return P2PResponse(
            output = buildGridRpcContent(
                buildFailureRpcMessage(
                    messageType = DistributionGridRpcMessageType.SESSION_REJECT,
                    targetId = rpcMessage.senderNodeId,
                    sessionRef = sessionRef.deepCopy(),
                    protocolVersion = rpcMessage.protocolVersion,
                    failure = buildFailure(
                        kind = DistributionGridFailureKind.SESSION_REJECTED,
                        reason = "DistributionGrid could not validate the supplied session reference.",
                        retryable = true
                    )
                )
            )
        )

        val remoteEnvelope = deserialize<DistributionGridEnvelope>(
            rpcMessage.payloadJson,
            useRepair = false
        ) ?: return P2PResponse(
            output = buildGridRpcContent(
                buildFailureRpcMessage(
                    messageType = DistributionGridRpcMessageType.TASK_FAILURE,
                    targetId = rpcMessage.senderNodeId,
                    sessionRef = sessionRecord.toSessionRef(),
                    protocolVersion = sessionRecord.negotiatedProtocolVersion,
                    failure = buildFailure(
                        kind = DistributionGridFailureKind.ROUTING_FAILURE,
                        reason = "DistributionGrid could not deserialize the remote task envelope.",
                        retryable = false
                    )
                )
            )
        )

        val preparedEnvelope = remoteEnvelope.deepCopy().apply {
            senderNodeId = sessionRecord.requesterNodeId
            senderTransport = request.returnAddress.deepCopy().takeIf {
                it.transportAddress.isNotBlank() || it.transportAuthBody.isNotBlank()
            } ?: request.transport.deepCopy()
            currentNodeId = resolveCurrentNodeId()
            currentTransport = resolveCurrentTransport()
            this.sessionRef = sessionRecord.toSessionRef()
            content = content.deepCopy()
        }
        applyNegotiatedPolicyToEnvelope(preparedEnvelope, sessionRecord)

        val allowedHopCount = minOf(maxHops, preparedEnvelope.routingPolicy.maxHopCount.coerceAtLeast(1))
        if(preparedEnvelope.hopHistory.size >= allowedHopCount)
        {
            val failure = buildFailure(
                kind = DistributionGridFailureKind.ROUTING_FAILURE,
                reason = "DistributionGrid rejected inbound task handoff because the hop count (${preparedEnvelope.hopHistory.size}) reached the limit ($allowedHopCount).",
                retryable = false
            )
            return P2PResponse(
                output = buildGridRpcContent(
                    buildFailureRpcMessage(
                        messageType = DistributionGridRpcMessageType.TASK_FAILURE,
                        targetId = rpcMessage.senderNodeId,
                        sessionRef = sessionRecord.toSessionRef(),
                        protocolVersion = sessionRecord.negotiatedProtocolVersion,
                        failure = failure
                    )
                )
            )
        }

        val result = executeEnvelopeLocally(
            envelope = preparedEnvelope,
            inboundSingleNodeMode = true,
            negotiatedSessionRecord = sessionRecord
        )
        val terminalEnvelope = extractEnvelopeFromTerminalContent(result, preparedEnvelope)
        val responseType = when
        {
            result.metadata[FAILURE_METADATA_KEY] != null || result.terminatePipeline ->
            {
                DistributionGridRpcMessageType.TASK_FAILURE
            }

            else ->
            {
                DistributionGridRpcMessageType.TASK_RETURN
            }
        }

        return P2PResponse(
            output = buildGridRpcContent(
                DistributionGridRpcMessage(
                    messageType = responseType,
                    senderNodeId = resolveCurrentNodeId(),
                    targetId = sessionRecord.requesterNodeId,
                    protocolVersion = sessionRecord.negotiatedProtocolVersion.deepCopy(),
                    sessionRef = sessionRecord.toSessionRef(),
                    payloadType = ENVELOPE_PAYLOAD_TYPE,
                    payloadJson = serialize(terminalEnvelope)
                )
            )
        )
    }

    /**
     * Dispatch one remote handoff to an explicitly configured peer descriptor.
     *
     * @param envelope Current execution envelope.
     * @param directive Router directive requesting remote handoff.
     * @param startedAt Execution start time used for hop recording.
     * @return Terminal content finalized locally after the remote reply.
     */
    private suspend fun dispatchExplicitPeerHandoff(
        envelope: DistributionGridEnvelope,
        directive: DistributionGridDirective,
        startedAt: Long
    ): MultimodalContent
    {
        val resolvedPeer = resolveExplicitPeerDescriptor(directive)
        if(resolvedPeer == null)
        {
            val failure = buildFailure(
                kind = DistributionGridFailureKind.ROUTING_FAILURE,
                reason = "DistributionGrid could not resolve explicit peer '${directive.targetPeerId.ifBlank { directive.targetNodeId }}'.",
                retryable = false
            )
            envelope.latestFailure = failure
            return finalizeFailedEnvelope(
                envelope = envelope,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                directiveKind = directive.kind
            )
        }

        val peerKey = resolvedPeer.peerKey
        val peerDescriptor = resolvedPeer.descriptor
        val peerMetadata = peerDescriptor.distributionGridMetadata
        val preparedEnvelope = beforePeerDispatchHook?.invoke(envelope) ?: envelope

        val allowedHopCount = minOf(maxHops, preparedEnvelope.routingPolicy.maxHopCount.coerceAtLeast(1))
        if(preparedEnvelope.hopHistory.size >= allowedHopCount)
        {
            val failure = buildFailure(
                kind = DistributionGridFailureKind.ROUTING_FAILURE,
                reason = "DistributionGrid exhausted the maximum hop count of $allowedHopCount before peer handoff.",
                retryable = false
            )
            preparedEnvelope.latestFailure = failure
            trace(
                TraceEventType.DISTRIBUTION_GRID_LOOP_GUARD,
                TracePhase.ORCHESTRATION,
                metadata = mapOf(
                    "taskId" to preparedEnvelope.taskId,
                    "peerKey" to peerKey,
                    "hopHistorySize" to preparedEnvelope.hopHistory.size,
                    "allowedHopCount" to allowedHopCount
                )
            )
            return finalizeFailedEnvelope(
                envelope = preparedEnvelope,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                directiveKind = directive.kind
            )
        }

        if(peerMetadata == null || peerMetadata.nodeId.isBlank())
        {
            val failure = buildFailure(
                kind = DistributionGridFailureKind.TRUST_REJECTED,
                reason = "DistributionGrid explicit peer '$peerKey' is missing explicit grid metadata.",
                retryable = false
            )
            preparedEnvelope.latestFailure = failure
            return finalizeFailedEnvelope(
                envelope = preparedEnvelope,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                directiveKind = directive.kind
            )
        }

        if(!isDescriptorPrivacyCompatible(peerDescriptor, preparedEnvelope.tracePolicy))
        {
            val failure = buildFailure(
                kind = DistributionGridFailureKind.POLICY_REJECTED,
                reason = "DistributionGrid explicit peer '$peerKey' conflicts with the current trace or privacy policy.",
                retryable = false
            )
            preparedEnvelope.latestFailure = failure
            return finalizeFailedEnvelope(
                envelope = preparedEnvelope,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                directiveKind = directive.kind
            )
        }

        val handshakeOutcome = resolveOrCreatePeerSession(
            peerKey = peerKey,
            descriptor = peerDescriptor,
            envelope = preparedEnvelope,
            registryScopeOverride = resolvedPeer.registryScope
        )
        val sessionRecord = handshakeOutcome.sessionRecord
        if(sessionRecord == null)
        {
            val failure = handshakeOutcome.failure ?: buildFailure(
                kind = DistributionGridFailureKind.SESSION_REJECTED,
                reason = "DistributionGrid could not establish a valid explicit-peer session for '$peerKey'.",
                retryable = true
            )
            preparedEnvelope.latestFailure = failure
            val failedEnvelope = mergeRemoteFailure(
                envelope = preparedEnvelope,
                peerDescriptor = peerDescriptor,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                resultSummary = failure.reason
            )
            return finalizeFailedEnvelope(
                envelope = failedEnvelope,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                directiveKind = directive.kind,
                recordTerminalHop = false
            )
        }

        val outboundEnvelope = preparedEnvelope.deepCopy().apply {
            senderNodeId = resolveCurrentNodeId()
            senderTransport = resolveCurrentTransport()
            currentNodeId = peerMetadata.nodeId
            currentTransport = peerDescriptor.transport.deepCopy()
            sessionRef = sessionRecord.toSessionRef()
            attributes.remove(SINGLE_NODE_MODE_ATTRIBUTE)
            content = content.deepCopy()
        }
        applyNegotiatedPolicyToEnvelope(outboundEnvelope, sessionRecord)
        val shapedOutboundEnvelope = try
        {
            shapeOutboundEnvelopeForPeer(outboundEnvelope, peerDescriptor)
        }
        catch(error: Throwable)
        {
            val failure = buildFailure(
                kind = DistributionGridFailureKind.POLICY_REJECTED,
                reason = error.message ?: "DistributionGrid could not build a safe outbound memory envelope.",
                retryable = false
            )
            val failedEnvelope = mergeRemoteFailure(
                envelope = preparedEnvelope,
                peerDescriptor = peerDescriptor,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                resultSummary = failure.reason,
                resolvedSessionRef = sessionRecord.toSessionRef()
            )
            return finalizeFailedEnvelope(
                envelope = failedEnvelope,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                directiveKind = directive.kind,
                recordTerminalHop = false
            )
        }

        val pausedBeforeDispatch = maybePauseAtSafeBoundary(
            envelope = shapedOutboundEnvelope,
            checkpointReason = "before-peer-dispatch"
        )
        if(pausedBeforeDispatch != null)
        {
            return pausedBeforeDispatch
        }

        checkpointTaskState(shapedOutboundEnvelope, "before-peer-dispatch")

        trace(
            TraceEventType.DISTRIBUTION_GRID_PEER_HANDOFF,
            TracePhase.AGENT_COMMUNICATION,
            metadata = mapOf(
                "taskId" to shapedOutboundEnvelope.taskId,
                "peerKey" to peerKey,
                "targetNodeId" to peerMetadata.nodeId,
                "transport" to peerDescriptor.transport.transportMethod.name
            )
        )

        val handoffRpcMessage = DistributionGridRpcMessage(
            messageType = DistributionGridRpcMessageType.TASK_HANDOFF,
            senderNodeId = resolveCurrentNodeId(),
            targetId = peerMetadata.nodeId,
            protocolVersion = sessionRecord.negotiatedProtocolVersion.deepCopy(),
            sessionRef = sessionRecord.toSessionRef(),
            payloadType = ENVELOPE_PAYLOAD_TYPE,
            payloadJson = serialize(shapedOutboundEnvelope)
        )
        val outboundRequest = buildGridRpcRequest(peerDescriptor, handoffRpcMessage)
        injectOutboundCredentials(
            envelope = shapedOutboundEnvelope,
            peerKey = peerKey,
            descriptor = peerDescriptor,
            request = outboundRequest
        )
        val outboundPolicyFailure = validateOutboundPolicy(
            envelope = shapedOutboundEnvelope,
            peerKey = peerKey,
            descriptor = peerDescriptor,
            request = outboundRequest
        )
        if(outboundPolicyFailure != null)
        {
            val failedEnvelope = mergeRemoteFailure(
                envelope = preparedEnvelope,
                peerDescriptor = peerDescriptor,
                failure = outboundPolicyFailure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                resultSummary = outboundPolicyFailure.reason,
                resolvedSessionRef = sessionRecord.toSessionRef()
            )
            return finalizeFailedEnvelope(
                envelope = failedEnvelope,
                failure = outboundPolicyFailure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                directiveKind = directive.kind,
                recordTerminalHop = false
            )
        }

        val response = externalP2PCallWithTimeout(outboundRequest)

        if(response.rejection != null)
        {
            invalidateSession(
                sessionRecord = sessionRecord,
                reason = "Explicit peer rejected the handoff at the transport boundary."
            )
            val failure = mapRemoteP2PRejectionToFailure(
                rejection = response.rejection!!,
                peerDescriptor = peerDescriptor
            )
            preparedEnvelope.latestFailure = failure
            val failedEnvelope = mergeRemoteFailure(
                envelope = preparedEnvelope,
                peerDescriptor = peerDescriptor,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                resultSummary = failure.reason
            )
            return finalizeFailedEnvelope(
                envelope = failedEnvelope,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                directiveKind = directive.kind,
                recordTerminalHop = false
            )
        }

        val rpcResponse = parseGridRpcMessage(response.output?.text.orEmpty())
        if(rpcResponse == null)
        {
            invalidateSession(
                sessionRecord = sessionRecord,
                reason = "Explicit peer returned a non-grid response to task handoff."
            )
            val failure = buildFailure(
                kind = DistributionGridFailureKind.TRANSPORT_FAILURE,
                reason = "DistributionGrid explicit peer '$peerKey' returned a non-grid response to task handoff.",
                retryable = false
            )
            val failedEnvelope = mergeRemoteFailure(
                envelope = preparedEnvelope,
                peerDescriptor = peerDescriptor,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                resultSummary = failure.reason
            )
            return finalizeFailedEnvelope(
                envelope = failedEnvelope,
                failure = failure,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                directiveKind = directive.kind,
                recordTerminalHop = false
            )
        }

        trace(
            TraceEventType.DISTRIBUTION_GRID_PEER_RESPONSE,
            TracePhase.AGENT_COMMUNICATION,
            metadata = mapOf(
                "taskId" to preparedEnvelope.taskId,
                "peerKey" to peerKey,
                "messageType" to rpcResponse.messageType.name
            )
        )

        return when(rpcResponse.messageType)
        {
            DistributionGridRpcMessageType.TASK_RETURN ->
            {
                val remoteEnvelope = deserialize<DistributionGridEnvelope>(
                    rpcResponse.payloadJson,
                    useRepair = false
                )

                if(remoteEnvelope == null)
                {
                    val failure = buildFailure(
                        kind = DistributionGridFailureKind.TRANSPORT_FAILURE,
                        reason = "DistributionGrid explicit peer '$peerKey' returned an unreadable envelope.",
                        retryable = false
                    )
                    val failedEnvelope = mergeRemoteFailure(
                        envelope = preparedEnvelope,
                        peerDescriptor = peerDescriptor,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        resultSummary = failure.reason
                    )
                    return finalizeFailedEnvelope(
                        envelope = failedEnvelope,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        directiveKind = directive.kind,
                        recordTerminalHop = false
                    )
                }

                val expectedSessionRef = sessionRecord.toSessionRef()
                if(rpcResponse.sessionRef != expectedSessionRef ||
                    (remoteEnvelope.sessionRef != null && remoteEnvelope.sessionRef != expectedSessionRef)
                )
                {
                    invalidateSession(
                        sessionRecord = sessionRecord,
                        reason = "Explicit peer returned a task reply with a mismatched session reference."
                    )

                    val failure = buildFailure(
                        kind = DistributionGridFailureKind.SESSION_REJECTED,
                        reason = "DistributionGrid explicit peer '$peerKey' returned a task reply for the wrong session.",
                        retryable = true
                    )
                    val failedEnvelope = mergeRemoteFailure(
                        envelope = preparedEnvelope,
                        peerDescriptor = peerDescriptor,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        resultSummary = failure.reason
                    )
                    return finalizeFailedEnvelope(
                        envelope = failedEnvelope,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        directiveKind = directive.kind,
                        recordTerminalHop = false
                    )
                }

                var mergedEnvelope = mergeRemoteSuccess(
                    envelope = preparedEnvelope,
                    peerDescriptor = peerDescriptor,
                    remoteEnvelope = remoteEnvelope,
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    resolvedSessionRef = expectedSessionRef
                )
                mergedEnvelope = afterPeerResponseHook?.invoke(mergedEnvelope) ?: mergedEnvelope
                val pausedAfterPeer = maybePauseAtSafeBoundary(
                    envelope = mergedEnvelope,
                    checkpointReason = "after-peer-response"
                )
                if(pausedAfterPeer != null)
                {
                    return pausedAfterPeer
                }
                checkpointTaskState(mergedEnvelope, "after-peer-response")
                trace(
                    TraceEventType.DISTRIBUTION_GRID_RETURN_ROUTING,
                    TracePhase.CLEANUP,
                    metadata = mapOf(
                        "taskId" to mergedEnvelope.taskId,
                        "targetNodeId" to peerMetadata.nodeId
                    )
                )
                finalizeSuccessfulEnvelope(
                    envelope = mergedEnvelope,
                    returnMode = remoteEnvelope.latestOutcome?.returnMode ?: resolveReturnMode(mergedEnvelope.routingPolicy),
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    directiveKind = directive.kind,
                    recordTerminalHop = false
                )
            }

            DistributionGridRpcMessageType.TASK_FAILURE,
            DistributionGridRpcMessageType.SESSION_REJECT ->
            {
                if(rpcResponse.messageType == DistributionGridRpcMessageType.SESSION_REJECT)
                {
                    invalidateSession(sessionRecord, "Explicit peer session was rejected by the remote node.")
                }

                val remoteEnvelope = deserialize<DistributionGridEnvelope>(
                    rpcResponse.payloadJson,
                    useRepair = false
                )
                val remoteFailure = remoteEnvelope?.latestFailure
                    ?: deserialize<DistributionGridFailure>(rpcResponse.payloadJson, useRepair = false)
                    ?: buildFailure(
                        kind = if(rpcResponse.messageType == DistributionGridRpcMessageType.SESSION_REJECT)
                        {
                            DistributionGridFailureKind.SESSION_REJECTED
                        }
                        else
                        {
                            DistributionGridFailureKind.ROUTING_FAILURE
                        },
                        reason = "DistributionGrid explicit peer '$peerKey' returned a remote failure.",
                        retryable = rpcResponse.messageType == DistributionGridRpcMessageType.SESSION_REJECT
                    )

                val expectedSessionRef = sessionRecord.toSessionRef()
                val remoteSessionRef = remoteEnvelope?.sessionRef
                if(rpcResponse.sessionRef != expectedSessionRef ||
                    (remoteSessionRef != null && remoteSessionRef != expectedSessionRef)
                )
                {
                    invalidateSession(
                        sessionRecord = sessionRecord,
                        reason = "Explicit peer returned a failure reply with a mismatched session reference."
                    )

                    val failure = buildFailure(
                        kind = DistributionGridFailureKind.SESSION_REJECTED,
                        reason = "DistributionGrid explicit peer '$peerKey' returned a failure reply for the wrong session.",
                        retryable = true
                    )
                    val failedEnvelope = mergeRemoteFailure(
                        envelope = preparedEnvelope,
                        peerDescriptor = peerDescriptor,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        resultSummary = failure.reason
                    )
                    return finalizeFailedEnvelope(
                        envelope = failedEnvelope,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        directiveKind = directive.kind,
                        recordTerminalHop = false
                    )
                }

                var mergedEnvelope = if(remoteEnvelope != null)
                {
                    mergeRemoteFailure(
                        envelope = preparedEnvelope,
                        peerDescriptor = peerDescriptor,
                        failure = remoteFailure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        resultSummary = remoteFailure.reason,
                        remoteEnvelope = remoteEnvelope,
                        resolvedSessionRef = expectedSessionRef
                    )
                }

                else
                {
                    mergeRemoteFailure(
                        envelope = preparedEnvelope,
                        peerDescriptor = peerDescriptor,
                        failure = remoteFailure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        resultSummary = remoteFailure.reason,
                        resolvedSessionRef = expectedSessionRef
                    )
                }

                mergedEnvelope = afterPeerResponseHook?.invoke(mergedEnvelope) ?: mergedEnvelope
                val continuationDirective = resolveDirective(mergedEnvelope)
                if(continuationDirective.kind == DistributionGridDirectiveKind.RETRY_SAME_PEER ||
                    continuationDirective.kind == DistributionGridDirectiveKind.TRY_ALTERNATE_PEER
                )
                {
                    return continueWithRetryDirective(
                        envelope = mergedEnvelope,
                        directive = continuationDirective,
                        startedAt = startedAt,
                        redispatchEnvelope = preparedEnvelope.deepCopy(),
                        fallbackPeerKey = peerKey,
                        fallbackTargetNodeId = peerMetadata.nodeId
                    )
                }
                val pausedAfterPeer = maybePauseAtSafeBoundary(
                    envelope = mergedEnvelope,
                    checkpointReason = "after-peer-response"
                )
                if(pausedAfterPeer != null)
                {
                    return pausedAfterPeer
                }
                checkpointTaskState(mergedEnvelope, "after-peer-response")
                trace(
                    TraceEventType.DISTRIBUTION_GRID_RETURN_ROUTING,
                    TracePhase.CLEANUP,
                    metadata = mapOf(
                        "taskId" to mergedEnvelope.taskId,
                        "targetNodeId" to peerMetadata.nodeId,
                        "status" to "failure"
                    )
                )
                finalizeFailedEnvelope(
                    envelope = mergedEnvelope,
                    failure = remoteFailure,
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    directiveKind = directive.kind,
                    recordTerminalHop = false
                )
            }

            else ->
            {
                val failure = buildFailure(
                    kind = DistributionGridFailureKind.TRANSPORT_FAILURE,
                    reason = "DistributionGrid explicit peer '$peerKey' returned unsupported RPC '${rpcResponse.messageType.name}'.",
                    retryable = false
                )
                val failedEnvelope = mergeRemoteFailure(
                    envelope = preparedEnvelope,
                    peerDescriptor = peerDescriptor,
                    failure = failure,
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    resultSummary = failure.reason
                )
                finalizeFailedEnvelope(
                    envelope = failedEnvelope,
                    failure = failure,
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    directiveKind = directive.kind,
                    recordTerminalHop = false
                )
            }
        }
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
    private suspend fun executeEnvelopeLocally(
        envelope: DistributionGridEnvelope,
        inboundSingleNodeMode: Boolean = false,
        negotiatedSessionRecord: DistributionGridSessionRecord? = null
    ): MultimodalContent
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
            if(inboundSingleNodeMode && negotiatedSessionRecord != null)
            {
                applyNegotiatedPolicyToEnvelope(preparedEnvelope, negotiatedSessionRecord)
            }
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

            routeLocalDirective(
                envelope = preparedEnvelope,
                directive = directive,
                startedAt = executionStartedAt,
                inboundSingleNodeMode = inboundSingleNodeMode,
                negotiatedSessionRecord = negotiatedSessionRecord
            )
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
     * Compact result wrapper for explicit-peer handshake resolution.
     *
     * @param sessionRecord Negotiated session record when the handshake succeeded.
     * @param failure Failure record when the handshake failed or was rejected.
     */
    private data class DistributionGridPeerHandshakeOutcome(
        val sessionRecord: DistributionGridSessionRecord? = null,
        val failure: DistributionGridFailure? = null
    )

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
        startedAt: Long,
        inboundSingleNodeMode: Boolean = false,
        negotiatedSessionRecord: DistributionGridSessionRecord? = null
    ): MultimodalContent
    {
        return when(directive.kind)
        {
            DistributionGridDirectiveKind.RUN_LOCAL_WORKER ->
            {
                val preparedEnvelope = beforeLocalWorkerHook?.invoke(envelope) ?: envelope
                if(inboundSingleNodeMode && negotiatedSessionRecord != null)
                {
                    applyNegotiatedPolicyToEnvelope(preparedEnvelope, negotiatedSessionRecord)
                }
                runLocalWorker(
                    envelope = preparedEnvelope,
                    startedAt = startedAt,
                    inboundSingleNodeMode = inboundSingleNodeMode,
                    negotiatedSessionRecord = negotiatedSessionRecord
                )
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

            DistributionGridDirectiveKind.HAND_OFF_TO_PEER ->
            {
                if(inboundSingleNodeMode)
                {
                    val failure = buildFailure(
                        kind = DistributionGridFailureKind.ROUTING_FAILURE,
                        reason = "Nested remote handoff is not supported during inbound Phase 5 task execution.",
                        retryable = false
                    )
                    envelope.latestFailure = failure
                    return finalizeFailedEnvelope(
                        envelope = envelope,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        directiveKind = directive.kind
                    )
                }

                dispatchExplicitPeerHandoff(envelope, directive, startedAt)
            }

            DistributionGridDirectiveKind.RETRY_SAME_PEER,
            DistributionGridDirectiveKind.TRY_ALTERNATE_PEER ->
            {
                if(inboundSingleNodeMode)
                {
                    val failure = buildFailure(
                        kind = DistributionGridFailureKind.ROUTING_FAILURE,
                        reason = "Retry and alternate-peer directives are not supported during inbound single-node execution.",
                        retryable = false
                    )
                    envelope.latestFailure = failure
                    return finalizeFailedEnvelope(
                        envelope = envelope,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        directiveKind = directive.kind
                    )
                }

                continueWithRetryDirective(
                    envelope = envelope,
                    directive = directive,
                    startedAt = startedAt
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
        startedAt: Long,
        inboundSingleNodeMode: Boolean = false,
        negotiatedSessionRecord: DistributionGridSessionRecord? = null
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
            if(inboundSingleNodeMode && negotiatedSessionRecord != null)
            {
                applyNegotiatedPolicyToEnvelope(updatedEnvelope, negotiatedSessionRecord)
            }
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
            val pausedResult = maybePauseAtSafeBoundary(
                envelope = updatedEnvelope,
                checkpointReason = "after-local-worker"
            )
            if(pausedResult != null)
            {
                return pausedResult
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
        directiveKind: DistributionGridDirectiveKind,
        recordTerminalHop: Boolean = true
    ): MultimodalContent
    {
        envelope.completed = true
        if(recordTerminalHop)
        {
            envelope.hopHistory.add(
                buildHopRecord(
                    envelope = envelope,
                    directiveKind = directiveKind,
                    resultSummary = "Local execution completed successfully.",
                    startedAt = startedAt,
                    completedAt = completedAt
                )
            )
        }

        val baseOutput = envelope.content
        baseOutput.passPipeline = true
        baseOutput.terminatePipeline = false

        val finalOutput = outcomeTransformationHook?.invoke(baseOutput, envelope) ?: baseOutput
        envelope.content = finalOutput

        val outcome = DistributionGridOutcome(
            status = DistributionGridOutcomeStatus.SUCCESS,
            returnMode = returnMode,
            taskId = envelope.taskId,
            finalContent = finalOutput.deepCopy(),
            completionNotes = envelope.executionNotes.joinToString(separator = " | "),
            hopCount = envelope.hopHistory.size,
            finalNodeId = envelope.currentNodeId,
            terminalFailure = null
        )
        envelope.latestOutcome = outcome
        envelope.latestFailure = null

        finalOutput.metadata[ENVELOPE_METADATA_KEY] = envelope.deepCopy()
        finalOutput.metadata[OUTCOME_METADATA_KEY] = outcome.deepCopy()
        finalOutput.metadata.remove(FAILURE_METADATA_KEY)

        checkpointTaskState(envelope, "terminal-success")
        archiveTaskStateBestEffort(envelope, "terminal-success")
        pausedTaskIds.remove(envelope.taskId)
        gridPauseRequested = false

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
        error: Throwable? = null,
        recordTerminalHop: Boolean = true
    ): MultimodalContent
    {
        envelope.completed = true
        envelope.latestFailure = failure
        if(recordTerminalHop)
        {
            envelope.hopHistory.add(
                buildHopRecord(
                    envelope = envelope,
                    directiveKind = directiveKind,
                    resultSummary = failure.reason.ifBlank { "Local execution failed." },
                    startedAt = startedAt,
                    completedAt = completedAt
                )
            )
        }

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

        preparedEnvelope.content = failureOutput

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

        checkpointTaskState(preparedEnvelope, "terminal-failure")
        archiveTaskStateBestEffort(preparedEnvelope, "terminal-failure")
        pausedTaskIds.remove(preparedEnvelope.taskId)
        gridPauseRequested = false

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
        val errorType = when(failure.kind)
        {
            DistributionGridFailureKind.TRANSPORT_FAILURE -> P2PError.transport
            DistributionGridFailureKind.HANDSHAKE_REJECTED,
            DistributionGridFailureKind.SESSION_REJECTED,
            DistributionGridFailureKind.TRUST_REJECTED,
            DistributionGridFailureKind.POLICY_REJECTED,
            DistributionGridFailureKind.VALIDATION_FAILURE -> P2PError.configuration

            else -> P2PError.configuration
        }

        return P2PResponse(
            rejection = P2PRejection(
                errorType = errorType,
                reason = failure.reason
            )
        )
    }

    /**
     * Revalidate any stored session reference before resumed execution continues.
     *
     * Resumed work must not blindly trust an old session snapshot. If the session record is missing, invalidated,
     * expired, or no longer matches the cached in-memory record, the resumed envelope drops the session and forces
     * a later handshake to negotiate fresh state.
     *
     * @param envelope Envelope loaded from durable state.
     */
    private fun revalidateResumedSession(envelope: DistributionGridEnvelope)
    {
        val sessionRef = envelope.sessionRef ?: return
        val cachedSession = sessionRecordsById[sessionRef.sessionId]
        if(cachedSession == null ||
            !isSessionValid(
                sessionRecord = cachedSession,
                expectedRequesterNodeId = sessionRef.requesterNodeId,
                expectedResponderNodeId = sessionRef.responderNodeId
            ) ||
            cachedSession.toSessionRef() != sessionRef
        )
        {
            envelope.executionNotes.add(
                "Resumed task dropped stale session '${sessionRef.sessionId}' and will renegotiate if remote dispatch continues."
            )
            envelope.sessionRef = null
        }
    }

    /**
     * Persist a best-effort durable checkpoint for the current task state.
     *
     * Normal runtime execution continues when the save fails. The failure is recorded in execution notes and tracing
     * so callers can still diagnose durability drift without forcing the live task to abort.
     *
     * @param envelope Envelope snapshot to persist.
     * @param checkpointReason Human-readable reason for the checkpoint.
     * @return `true` when the checkpoint saved successfully.
     */
    private suspend fun checkpointTaskState(
        envelope: DistributionGridEnvelope,
        checkpointReason: String
    ): Boolean
    {
        val store = durableStore ?: return false
        if(envelope.durableStateKey.isBlank())
        {
            envelope.durableStateKey = envelope.taskId
        }

        val durableState = DistributionGridDurableState(
            taskId = envelope.taskId,
            envelope = envelope.deepCopy(),
            sessionRef = envelope.sessionRef?.deepCopy(),
            checkpointReason = checkpointReason,
            completed = envelope.completed,
            updatedAtEpochMillis = System.currentTimeMillis(),
            archived = false
        )

        return try
        {
            store.saveState(durableState)
            trace(
                TraceEventType.DISTRIBUTION_GRID_DURABILITY_CHECKPOINT,
                TracePhase.CONTEXT_PREPARATION,
                metadata = mapOf(
                    "taskId" to envelope.taskId,
                    "checkpointReason" to checkpointReason,
                    "durabilityAction" to "save",
                    "accepted" to true
                )
            )
            true
        }

        catch(error: Throwable)
        {
            envelope.executionNotes.add(
                "Durability checkpoint '$checkpointReason' failed: ${error.message ?: error::class.simpleName.orEmpty()}"
            )
            trace(
                TraceEventType.DISTRIBUTION_GRID_DURABILITY_CHECKPOINT,
                TracePhase.CONTEXT_PREPARATION,
                metadata = mapOf(
                    "taskId" to envelope.taskId,
                    "checkpointReason" to checkpointReason,
                    "durabilityAction" to "save",
                    "accepted" to false
                ),
                error = error
            )
            false
        }
    }

    /**
     * Attempt to archive one terminal durable state without rewriting the task outcome when archiving fails.
     *
     * @param envelope Terminal envelope being finalized.
     * @param checkpointReason Human-readable archive reason.
     */
    private suspend fun archiveTaskStateBestEffort(
        envelope: DistributionGridEnvelope,
        checkpointReason: String
    )
    {
        val store = durableStore ?: return
        val taskId = envelope.taskId.ifBlank { envelope.durableStateKey }
        if(taskId.isBlank())
        {
            return
        }

        try
        {
            store.archiveState(taskId)
            trace(
                TraceEventType.DISTRIBUTION_GRID_DURABILITY_CHECKPOINT,
                TracePhase.CLEANUP,
                metadata = mapOf(
                    "taskId" to taskId,
                    "checkpointReason" to checkpointReason,
                    "durabilityAction" to "archive",
                    "accepted" to true
                )
            )
        }

        catch(error: Throwable)
        {
            envelope.executionNotes.add(
                "Durability archive '$checkpointReason' failed: ${error.message ?: error::class.simpleName.orEmpty()}"
            )
            trace(
                TraceEventType.DISTRIBUTION_GRID_DURABILITY_CHECKPOINT,
                TracePhase.CLEANUP,
                metadata = mapOf(
                    "taskId" to taskId,
                    "checkpointReason" to checkpointReason,
                    "durabilityAction" to "archive",
                    "accepted" to false
                ),
                error = error
            )
        }
    }

    /**
     * Honor a requested safe pause only at an execution boundary where the current state can be checkpointed.
     *
     * If the checkpoint cannot be saved, the pause is canceled and execution continues, matching the best-effort
     * durability stance chosen for Phase 7.
     *
     * @param envelope Current envelope at a safe boundary.
     * @param checkpointReason Human-readable reason for the pause checkpoint.
     * @return Paused content when the checkpoint succeeded, or `null` when execution should continue.
     */
    private suspend fun maybePauseAtSafeBoundary(
        envelope: DistributionGridEnvelope,
        checkpointReason: String
    ): MultimodalContent?
    {
        if(!gridPauseRequested && envelope.taskId !in pausedTaskIds)
        {
            return null
        }

        val checkpointEnvelope = if(checkpointReason == "after-peer-response" && envelope.completed)
        {
            envelope.deepCopy().apply {
                completed = false
            }
        }
        else
        {
            envelope
        }

        val checkpointSaved = checkpointTaskState(checkpointEnvelope, checkpointReason)
        if(!checkpointSaved)
        {
            pausedTaskIds.remove(envelope.taskId)
            gridPauseRequested = false
            val cancellationNote = "Pause request was canceled because checkpoint '$checkpointReason' could not be saved."
            checkpointEnvelope.executionNotes.add(cancellationNote)
            if(checkpointEnvelope !== envelope)
            {
                envelope.executionNotes.add(cancellationNote)
            }
            trace(
                TraceEventType.DISTRIBUTION_GRID_RESUME,
                TracePhase.CLEANUP,
                metadata = mapOf(
                    "taskId" to envelope.taskId,
                    "checkpointReason" to checkpointReason,
                    "paused" to false
                )
            )
            return null
        }

        pausedTaskIds.remove(envelope.taskId)
        gridPauseRequested = false
        trace(
            TraceEventType.DISTRIBUTION_GRID_PAUSE,
            TracePhase.CLEANUP,
            metadata = mapOf(
                "taskId" to envelope.taskId,
                "checkpointReason" to checkpointReason
            )
        )
        return buildPausedContent(checkpointEnvelope, checkpointReason)
    }

    /**
     * Build the non-terminal content returned when a task pauses cleanly at a safe checkpoint.
     *
     * @param envelope Current envelope snapshot.
     * @param checkpointReason Human-readable reason for the pause.
     * @return Paused content ready to return to the caller.
     */
    private fun buildPausedContent(
        envelope: DistributionGridEnvelope,
        checkpointReason: String
    ): MultimodalContent
    {
        val pausedOutput = envelope.content.deepCopy().apply {
            passPipeline = false
            terminatePipeline = false
            metadata[PAUSED_METADATA_KEY] = true
            metadata[PAUSED_TASK_ID_METADATA_KEY] = envelope.taskId
            metadata[PAUSED_REASON_METADATA_KEY] = checkpointReason
            metadata[ENVELOPE_METADATA_KEY] = envelope.deepCopy().apply {
                durableStateKey = taskId
            }
            metadata.remove(OUTCOME_METADATA_KEY)
            metadata.remove(FAILURE_METADATA_KEY)
        }
        return pausedOutput
    }

    /**
     * Resolve the smallest safe outbound memory budget for a remote peer handoff.
     *
     * @param descriptor Remote peer descriptor being targeted.
     * @return Safe outbound token ceiling.
     */
    private fun resolveOutboundBudget(descriptor: P2PDescriptor): Int
    {
        val policyBudget = memoryPolicy.outboundTokenBudget.takeIf { it > 0 } ?: Int.MAX_VALUE
        val descriptorBudget = descriptor.contextWindowSize.takeIf { it > 0 } ?: Int.MAX_VALUE
        return minOf(policyBudget, descriptorBudget)
    }

    /**
     * Resolve the token-counting settings used for deterministic outbound compaction.
     *
     * @return Shared truncation settings.
     */
    private fun resolveMemoryTruncationSettings(): TruncationSettings
    {
        return memoryPolicy.truncationSettings
    }

    /**
     * Compact one text block to a hard token budget using dictionary-backed accounting.
     *
     * @param text Text to compact.
     * @param tokenBudget Hard token ceiling.
     * @param settings Shared truncation settings.
     * @param preserveStart Whether the front of the text should be preserved.
     * @return Budgeted text block.
     */
    private fun budgetText(
        text: String,
        tokenBudget: Int,
        settings: TruncationSettings,
        preserveStart: Boolean = true
    ): String
    {
        if(text.isBlank() || tokenBudget <= 0)
        {
            return ""
        }

        val method = if(preserveStart) ContextWindowSettings.TruncateBottom else ContextWindowSettings.TruncateTop
        return Dictionary.truncate(
            text,
            tokenBudget,
            1,
            method,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias
        )
    }

    /**
     * Estimate the token count of one compacted block.
     *
     * @param text Text to inspect.
     * @param settings Shared truncation settings.
     * @return Approximate token count.
     */
    private fun countBudgetedTokens(text: String, settings: TruncationSettings): Int
    {
        if(text.isBlank())
        {
            return 0
        }

        return Dictionary.countTokens(
            text,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias
        )
    }

    /**
     * Build one labeled compact memory section.
     *
     * @param title Human-readable section title.
     * @param lines Nonblank lines to include.
     * @return Section block or blank when no input lines remain.
     */
    private fun buildSectionText(title: String, lines: List<String>): String
    {
        val filtered = lines.filter { it.isNotBlank() }
        if(filtered.isEmpty())
        {
            return ""
        }

        return buildString {
            appendLine("$title:")
            filtered.forEach { line ->
                appendLine("- $line")
            }
        }.trim()
    }

    /**
     * Build the optional older-history summary block used only after hard budgets are allocated.
     *
     * @param summarySeed Older-history text seed.
     * @param summaryBudget Summary token budget.
     * @param settings Shared truncation settings.
     * @return Budgeted summary block.
     */
    private fun buildSummaryText(
        summarySeed: String,
        summaryBudget: Int,
        settings: TruncationSettings
    ): String
    {
        if(summarySeed.isBlank() || summaryBudget <= 0)
        {
            return ""
        }

        val trimmedSeed = summarySeed.take(memoryPolicy.maxSummaryCharacters)
        val summarized = if(memoryPolicy.enableSummarization && memoryPolicy.summarizer != null)
        {
            runCatching { memoryPolicy.summarizer?.invoke(trimmedSeed).orEmpty() }
                .getOrDefault("")
                .ifBlank { trimmedSeed }
        }
        else
        {
            trimmedSeed
        }

        val summarySection = buildSectionText("Summary", listOf(summarized))
        return budgetText(summarySection, summaryBudget, settings, preserveStart = true)
    }

    /**
     * Render compact memory sections into a [ContextWindow] for remote prompt injection.
     *
     * @param sections Ordered compact section text.
     * @param budget Available prompt budget.
     * @param settings Shared truncation settings.
     * @param inputText Original live content used to preserve text matches.
     * @return Compact prompt window.
     */
    private fun buildContextWindowForPrompt(
        sections: List<String>,
        budget: Int,
        settings: TruncationSettings,
        inputText: String
    ): ContextWindow
    {
        val promptWindow = ContextWindow()
        sections.filter { it.isNotBlank() }.forEach { section ->
            promptWindow.contextElements.add(section)
        }

        if(promptWindow.contextElements.isEmpty())
        {
            return promptWindow
        }

        promptWindow.truncateContextElements(
            budget,
            1,
            ContextWindowSettings.TruncateBottom,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias,
            inputText = inputText,
            preserveTextMatches = true
        )

        return promptWindow
    }

    /**
     * Mirror compact prompt sections into a [MiniBank] for receivers that expect page-based prompt state.
     *
     * @param sections Named prompt section text.
     * @return MiniBank copy of the compact prompt state.
     */
    private fun buildMiniBankFromSections(sections: Map<String, String>): MiniBank
    {
        val miniBank = MiniBank()
        sections.forEach { (key, text) ->
            if(text.isBlank())
            {
                return@forEach
            }

            miniBank.contextMap[key] = ContextWindow().apply {
                contextElements.add(text)
            }
        }

        return miniBank
    }

    /**
     * Produce a compact excerpt of the current live content for inclusion in critical outbound state.
     *
     * @param text Live content text.
     * @param settings Shared truncation settings.
     * @return Budgeted excerpt.
     */
    private fun compactContentExcerpt(
        text: String,
        settings: TruncationSettings
    ): String
    {
        val excerptBudget = maxOf(memoryPolicy.minimumCriticalBudget / 2, 128)
        return budgetText(text, excerptBudget, settings, preserveStart = true)
    }

    /**
     * Build the critical outbound memory lines that must survive every remote handoff.
     *
     * @param envelope Live envelope being prepared for handoff.
     * @param targetNodeId Remote node id.
     * @param settings Shared truncation settings.
     * @return Critical memory lines.
     */
    private fun buildOutboundCriticalLines(
        envelope: DistributionGridEnvelope,
        targetNodeId: String,
        settings: TruncationSettings
    ): List<String>
    {
        val liveExcerpt = if(memoryPolicy.redactContentSections || envelope.tracePolicy.requireRedaction)
        {
            "[REDACTED]"
        }
        else
        {
            compactContentExcerpt(envelope.content.text, settings)
        }

        return listOf(
            "Task: ${envelope.taskId}",
            "Origin: ${envelope.originNodeId.ifBlank { "<unknown>" }}",
            "Sender: ${envelope.senderNodeId.ifBlank { "<unknown>" }}",
            "Current objective: ${envelope.currentObjective.ifBlank { envelope.taskIntent.ifBlank { "<none>" } }}",
            "Target node: ${targetNodeId.ifBlank { "<unknown>" }}",
            "Trace redaction required: ${envelope.tracePolicy.requireRedaction}",
            "Hop count: ${envelope.hopHistory.size}",
            "Latest content excerpt: $liveExcerpt"
        )
    }

    /**
     * Build recent outbound memory lines from the bounded live execution notes and hop history.
     *
     * @param envelope Live envelope being prepared for handoff.
     * @return Recent memory lines.
     */
    private fun buildOutboundRecentLines(envelope: DistributionGridEnvelope): List<String>
    {
        val recentNotes = envelope.executionNotes
            .takeLast(memoryPolicy.maxExecutionNotes)
            .map { "Note: $it" }

        val recentHops = envelope.hopHistory
            .takeLast(memoryPolicy.maxHopRecords)
            .map { hop ->
                "Hop ${hop.routerAction.name}: ${hop.sourceNodeId} -> ${hop.destinationNodeId} (${hop.resultSummary})"
            }

        return recentNotes + recentHops
    }

    /**
     * Build the older-history seed that may be summarized when extra outbound budget remains.
     *
     * @param envelope Live envelope being prepared for handoff.
     * @return Summary seed text.
     */
    private fun buildOutboundSummarySeed(envelope: DistributionGridEnvelope): String
    {
        val omittedNotes = envelope.executionNotes.dropLast(memoryPolicy.maxExecutionNotes)
        val omittedHops = envelope.hopHistory.dropLast(memoryPolicy.maxHopRecords)

        return buildString {
            if(omittedNotes.isNotEmpty())
            {
                appendLine("Older execution notes omitted: ${omittedNotes.size}")
                omittedNotes.takeLast(5).forEach { appendLine(it) }
            }
            if(omittedHops.isNotEmpty())
            {
                appendLine("Older hops omitted: ${omittedHops.size}")
                omittedHops.takeLast(5).forEach { hop ->
                    appendLine("${hop.sourceNodeId} -> ${hop.destinationNodeId}: ${hop.resultSummary}")
                }
            }
        }.trim()
    }

    /**
     * Build one complete outbound memory envelope for a remote peer handoff.
     *
     * @param envelope Live envelope being prepared.
     * @param descriptor Remote peer descriptor.
     * @return Compact outbound memory envelope.
     */
    private fun buildOutboundMemoryEnvelope(
        envelope: DistributionGridEnvelope,
        descriptor: P2PDescriptor
    ): DistributionGridMemoryEnvelope
    {
        val settings = resolveMemoryTruncationSettings()
        val resolvedBudget = resolveOutboundBudget(descriptor)
        val availableBudget = maxOf(0, resolvedBudget - memoryPolicy.safetyReserveTokens)

        val summarySeed = buildOutboundSummarySeed(envelope)
        val configuredSummaryBudget = if(summarySeed.isBlank()) 0 else memoryPolicy.summaryBudget.coerceAtLeast(0)
        val maximumReservedSummaryBudget = maxOf(0, availableBudget - memoryPolicy.minimumCriticalBudget - memoryPolicy.minimumRecentBudget)
        val summaryBudget = minOf(configuredSummaryBudget, maximumReservedSummaryBudget)
        val remainingBudget = maxOf(0, availableBudget - summaryBudget)

        var criticalBudget = maxOf(0, maxOf(memoryPolicy.minimumCriticalBudget, remainingBudget / 2))
        criticalBudget = minOf(criticalBudget, remainingBudget)
        var recentBudget = maxOf(0, remainingBudget - criticalBudget)
        if(recentBudget > 0)
        {
            recentBudget = minOf(recentBudget, maxOf(memoryPolicy.minimumRecentBudget, remainingBudget / 3))
        }

        val criticalRaw = buildSectionText(
            "Critical state",
            buildOutboundCriticalLines(
                envelope = envelope,
                targetNodeId = descriptor.distributionGridMetadata?.nodeId.orEmpty(),
                settings = settings
            )
        )
        val recentRaw = buildSectionText("Recent history", buildOutboundRecentLines(envelope))
        val criticalText = budgetText(criticalRaw, criticalBudget, settings, preserveStart = true)
        val recentText = budgetText(recentRaw, recentBudget, settings, preserveStart = true)
        val summaryText = buildSummaryText(summarySeed, summaryBudget, settings)

        val sections = mutableListOf<DistributionGridMemorySection>()
        sections.add(
            DistributionGridMemorySection(
                name = "critical",
                tokenBudget = criticalBudget,
                text = criticalText,
                truncated = countBudgetedTokens(criticalText, settings) < countBudgetedTokens(criticalRaw, settings),
                tokenCount = countBudgetedTokens(criticalText, settings)
            )
        )

        if(recentText.isNotBlank())
        {
            sections.add(
                DistributionGridMemorySection(
                    name = "recent",
                    tokenBudget = recentBudget,
                    text = recentText,
                    truncated = countBudgetedTokens(recentText, settings) < countBudgetedTokens(recentRaw, settings),
                    tokenCount = countBudgetedTokens(recentText, settings)
                )
            )
        }

        if(summaryText.isNotBlank())
        {
            sections.add(
                DistributionGridMemorySection(
                    name = "summary",
                    tokenBudget = summaryBudget,
                    text = summaryText,
                    truncated = countBudgetedTokens(summaryText, settings) < countBudgetedTokens(summarySeed, settings),
                    tokenCount = countBudgetedTokens(summaryText, settings)
                )
            )
        }

        val promptWindow = buildContextWindowForPrompt(
            sections = listOf(criticalText, recentText, summaryText),
            budget = availableBudget,
            settings = settings,
            inputText = envelope.content.text
        )
        val miniBank = buildMiniBankFromSections(
            linkedMapOf(
                "critical" to criticalText,
                "recent" to recentText,
                "summary" to summaryText
            )
        )

        return DistributionGridMemoryEnvelope(
            targetNodeId = descriptor.distributionGridMetadata?.nodeId.orEmpty(),
            resolvedBudget = resolvedBudget,
            availableBudget = availableBudget,
            safetyReserveTokens = memoryPolicy.safetyReserveTokens,
            criticalBudget = criticalBudget,
            recentBudget = recentBudget,
            summaryBudget = summaryBudget,
            summarizationUsed = memoryPolicy.enableSummarization && memoryPolicy.summarizer != null && summarySeed.isNotBlank(),
            compacted = sections.any { it.truncated },
            failureReason = "",
            sections = sections,
            contextWindow = promptWindow,
            miniBank = miniBank
        )
    }

    /**
     * Strip grid-internal attribute prefixes from inbound remote attributes before merging.
     *
     * @param attributes Remote envelope attributes to sanitize.
     * @return Sanitized attribute map safe for local merge.
     */
    private fun sanitizeInboundAttributes(attributes: Map<String, String>): Map<String, String>
    {
        return attributes.filterKeys { key ->
            !key.startsWith(RETRY_COUNT_ATTRIBUTE_PREFIX) &&
            !key.startsWith(ALTERNATE_INDEX_ATTRIBUTE_PREFIX) &&
            key != SINGLE_NODE_MODE_ATTRIBUTE &&
            key != ALLOW_REMOTE_PCP_FORWARDING_ATTRIBUTE
        }
    }

    /**
     * Remove local-only or trace-sensitive metadata from outbound content before remote handoff.
     *
     * @param metadata Raw content metadata map.
     * @return Sanitized metadata copy.
     */
    private fun sanitizeOutboundContentMetadata(metadata: MutableMap<Any, Any>): MutableMap<Any, Any>
    {
        val sanitized = metadata.toMutableMap()
        sanitized.remove(ENVELOPE_METADATA_KEY)
        sanitized.remove(OUTCOME_METADATA_KEY)
        sanitized.remove(FAILURE_METADATA_KEY)
        sanitized.remove(DIRECTIVE_METADATA_KEY)
        sanitized.remove(PAUSED_METADATA_KEY)
        sanitized.remove(PAUSED_TASK_ID_METADATA_KEY)
        sanitized.remove(PAUSED_REASON_METADATA_KEY)

        if(memoryPolicy.redactTraceMetadata)
        {
            sanitized.keys
                .filterIsInstance<String>()
                .filter { key -> key.contains("trace", ignoreCase = true) }
                .forEach { key -> sanitized.remove(key) }
        }

        return sanitized
    }

    /**
     * Shape the outbound handoff envelope so remote peers receive bounded memory and redacted metadata.
     *
     * @param envelope Live envelope about to be handed off.
     * @param descriptor Remote peer descriptor being targeted.
     * @return Outbound envelope ready for RPC serialization.
     */
    private suspend fun shapeOutboundEnvelopeForPeer(
        envelope: DistributionGridEnvelope,
        descriptor: P2PDescriptor
    ): DistributionGridEnvelope
    {
        val memoryEnvelope = buildOutboundMemoryEnvelope(envelope, descriptor)

        val shapedEnvelope = envelope.deepCopy().apply {
            executionNotes = executionNotes.takeLast(memoryPolicy.maxExecutionNotes).toMutableList()
            hopHistory = hopHistory.takeLast(memoryPolicy.maxHopRecords).toMutableList()
            content = content.deepCopy().apply {
                context = memoryEnvelope.contextWindow.deepCopy()
                miniBankContext = memoryEnvelope.miniBank.deepCopy()
                metadata = sanitizeOutboundContentMetadata(metadata)
                metadata[MEMORY_ENVELOPE_METADATA_KEY] = memoryEnvelope.deepCopy()
                if(memoryPolicy.redactContentSections || envelope.tracePolicy.requireRedaction)
                {
                    text = "[REDACTED]"
                    binaryContent = mutableListOf()
                    tools = PcPRequest()
                }
            }
        }

        val hookEnvelope = outboundMemoryHook?.invoke(shapedEnvelope) ?: shapedEnvelope
        trace(
            TraceEventType.DISTRIBUTION_GRID_MEMORY_ENVELOPE,
            TracePhase.CONTEXT_PREPARATION,
            metadata = mapOf(
                "taskId" to hookEnvelope.taskId,
                "targetNodeId" to memoryEnvelope.targetNodeId,
                "resolvedBudget" to memoryEnvelope.resolvedBudget,
                "availableBudget" to memoryEnvelope.availableBudget,
                "compacted" to memoryEnvelope.compacted,
                "summarizationUsed" to memoryEnvelope.summarizationUsed
            )
        )
        return hookEnvelope
    }

    /**
     * Decide whether remote PCP forwarding is explicitly allowed for the current handoff.
     *
     * @param envelope Current execution envelope.
     * @return `true` when remote PCP forwarding has been explicitly enabled.
     */
    private fun isRemotePcpForwardAllowed(envelope: DistributionGridEnvelope): Boolean
    {
        val attributeFlag = envelope.attributes[ALLOW_REMOTE_PCP_FORWARDING_ATTRIBUTE]
        if(attributeFlag.equals("true", ignoreCase = true))
        {
            return true
        }

        val metadataFlag = envelope.content.metadata[ALLOW_REMOTE_PCP_FORWARDING_ATTRIBUTE]
        return metadataFlag == true || metadataFlag?.toString().equals("true", ignoreCase = true)
    }

    /**
     * Inject resolved credentials into an outbound request before policy validation.
     *
     * Credential resolution uses the peer's canonical node identity and falls back through the same identity
     * sources the P2P layer uses so auth lookup parity is preserved.
     *
     * @param envelope Current execution envelope.
     * @param peerKey Canonical peer key being targeted.
     * @param descriptor Remote peer descriptor.
     * @param request Outbound P2P request to populate.
     */
    private fun injectOutboundCredentials(
        envelope: DistributionGridEnvelope,
        peerKey: String,
        descriptor: P2PDescriptor,
        request: P2PRequest
    )
    {
        val resolvedPeerNodeId = descriptor.distributionGridMetadata?.nodeId.orEmpty()
        val requiredCredentialRef = sequenceOf(
            resolvedPeerNodeId.takeIf { it.isNotBlank() },
            peerKey.takeIf { it.isNotBlank() },
            descriptor.agentName.takeIf { it.isNotBlank() },
            request.transport.transportAddress.takeIf { it.isNotBlank() }
        ).mapNotNull { identity ->
            envelope.credentialPolicy.perHopCredentialRefs[identity]
        }.firstOrNull() ?: return

        val selectedAuthToken = when
        {
            request.authBody.isNotBlank() -> request.authBody
            else -> sequenceOf(
                request.transport.transportAddress.takeIf { it.isNotBlank() },
                resolvedPeerNodeId.takeIf { it.isNotBlank() },
                peerKey.takeIf { it.isNotBlank() },
                descriptor.agentName.takeIf { it.isNotBlank() }
            ).mapNotNull { identity ->
                identity?.let { AuthRegistry.getToken(it).takeIf { token -> token.isNotBlank() } }
            }.firstOrNull().orEmpty()
        }

        if(request.authBody.isBlank())
        {
            request.authBody = selectedAuthToken
        }
        if(request.transport.transportMethod != Transport.Tpipe && request.transport.transportAuthBody.isBlank())
        {
            request.transport.transportAuthBody = selectedAuthToken
        }
    }

    /**
     * Check whether an outbound request satisfies the current credential-routing policy for the target peer.
     *
     * This is a read-only check. Credential injection must be performed beforehand via [injectOutboundCredentials].
     *
     * @param envelope Current execution envelope.
     * @param peerKey Canonical peer key being targeted.
     * @param descriptor Remote peer descriptor.
     * @param request Outbound P2P request to validate.
     * @return Failure when policy denies the outbound request, or `null` when it is allowed.
     */
    private fun validateOutboundPolicy(
        envelope: DistributionGridEnvelope,
        peerKey: String,
        descriptor: P2PDescriptor,
        request: P2PRequest
    ): DistributionGridFailure?
    {
        val requiresRemotePcpForwarding = request.transport.transportMethod != Transport.Stdio &&
            hasForwardingRelevantPcpPayload(request.pcpRequest)
        if(requiresRemotePcpForwarding && !isRemotePcpForwardAllowed(envelope))
        {
            return buildFailure(
                kind = DistributionGridFailureKind.POLICY_REJECTED,
                reason = "DistributionGrid refused to forward PCP tooling to remote peer '$peerKey' without explicit allowance.",
                retryable = false
            )
        }

        val resolvedPeerNodeId = descriptor.distributionGridMetadata?.nodeId.orEmpty()
        val requiredCredentialRef = sequenceOf(
            resolvedPeerNodeId.takeIf { it.isNotBlank() },
            peerKey.takeIf { it.isNotBlank() },
            descriptor.agentName.takeIf { it.isNotBlank() },
            request.transport.transportAddress.takeIf { it.isNotBlank() }
        ).mapNotNull { identity ->
            envelope.credentialPolicy.perHopCredentialRefs[identity]
        }.firstOrNull()

        val hasAuthMaterial = request.authBody.isNotBlank() ||
            request.transport.transportAuthBody.isNotBlank()
        if(requiredCredentialRef != null && !hasAuthMaterial)
        {
            return buildFailure(
                kind = DistributionGridFailureKind.POLICY_REJECTED,
                reason = "DistributionGrid could not satisfy required credential reference '$requiredCredentialRef' for peer '$peerKey'.",
                retryable = false
            )
        }

        return null
    }

    /**
     * Determine whether a PCP request contains any real forwarding payload beyond stdio session settings.
     *
     * @param pcpRequest PCP request to inspect.
     * @return `true` when the request carries non-stdio PCP tooling that should be gated as forwarding.
     */
    private fun hasForwardingRelevantPcpPayload(pcpRequest: PcPRequest?): Boolean
    {
        if(pcpRequest == null)
        {
            return false
        }

        val sanitizedRequest = pcpRequest.copy(
            stdioContextOptions = StdioContextOptions()
        )
        return sanitizedRequest != PcPRequest()
    }

    /**
     * Continue execution with a retry or alternate-peer directive after a safe boundary.
     *
     * @param envelope Current envelope state.
     * @param directive Continuation directive to apply.
     * @param startedAt Original execution start time.
     * @param fallbackPeerKey Peer key from the prior handoff, used when the retry directive does not repeat it.
     * @param fallbackTargetNodeId Node id from the prior handoff, used when the retry directive does not repeat it.
     * @return Terminal content for the continuation path.
     */
    private suspend fun continueWithRetryDirective(
        envelope: DistributionGridEnvelope,
        directive: DistributionGridDirective,
        startedAt: Long,
        redispatchEnvelope: DistributionGridEnvelope? = null,
        fallbackPeerKey: String = "",
        fallbackTargetNodeId: String = ""
    ): MultimodalContent
    {
        val retryEnvelope = (redispatchEnvelope ?: envelope).deepCopy().apply {
            if(redispatchEnvelope != null)
            {
                executionNotes.addAll(envelope.executionNotes.deepCopy())
                attributes.putAll(envelope.attributes.deepCopy())
                hopHistory = envelope.hopHistory.deepCopy()
            }
            latestFailure = null
            latestOutcome = null
            completed = false
        }

        return when(directive.kind)
        {
            DistributionGridDirectiveKind.RETRY_SAME_PEER ->
            {
                if(!envelope.routingPolicy.allowRetrySamePeer)
                {
                    val failure = buildFailure(
                        kind = DistributionGridFailureKind.POLICY_REJECTED,
                        reason = "DistributionGrid retry-same-peer was requested even though the current routing policy disallows it.",
                        retryable = false
                    )
                    envelope.latestFailure = failure
                    return finalizeFailedEnvelope(
                        envelope = envelope,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        directiveKind = directive.kind,
                        recordTerminalHop = false
                    )
                }

                val retryPeerKey = directive.targetPeerId.ifBlank { fallbackPeerKey }
                if(retryPeerKey.isBlank())
                {
                    val failure = buildFailure(
                        kind = DistributionGridFailureKind.ROUTING_FAILURE,
                        reason = "DistributionGrid retry-same-peer requires a target peer identity.",
                        retryable = false
                    )
                    envelope.latestFailure = failure
                    return finalizeFailedEnvelope(
                        envelope = envelope,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        directiveKind = directive.kind,
                        recordTerminalHop = false
                    )
                }

                val retryCountKey = "$RETRY_COUNT_ATTRIBUTE_PREFIX$retryPeerKey"
                val retryCount = envelope.attributes[retryCountKey]?.toIntOrNull()?.plus(1) ?: 1
                if(retryCount > envelope.routingPolicy.maxRetryCount)
                {
                    val failure = buildFailure(
                        kind = DistributionGridFailureKind.ROUTING_FAILURE,
                        reason = "DistributionGrid exhausted same-peer retries for '$retryPeerKey'.",
                        retryable = false
                    )
                    envelope.latestFailure = failure
                    return finalizeFailedEnvelope(
                        envelope = envelope,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        directiveKind = directive.kind,
                        recordTerminalHop = false
                    )
                }

                envelope.attributes[retryCountKey] = retryCount.toString()
                retryEnvelope.attributes[retryCountKey] = retryCount.toString()
                val retryPeerDescriptor = resolveExplicitPeerDescriptor(
                    DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                        targetPeerId = retryPeerKey,
                        targetNodeId = fallbackTargetNodeId
                    )
                )?.descriptor
                trace(
                    TraceEventType.DISTRIBUTION_GRID_LOOP_GUARD,
                    TracePhase.ORCHESTRATION,
                    metadata = mapOf(
                        "taskId" to envelope.taskId,
                        "directive" to directive.kind.name,
                        "peerKey" to retryPeerKey,
                        "retryCount" to retryCount
                    )
                )
                retryEnvelope.currentNodeId = retryPeerDescriptor?.distributionGridMetadata?.nodeId
                    ?: fallbackTargetNodeId.ifBlank { retryEnvelope.currentNodeId }
                retryEnvelope.currentTransport = retryPeerDescriptor?.transport?.deepCopy()
                    ?: retryEnvelope.currentTransport.deepCopy()
                val pausedBeforeRetry = maybePauseAtSafeBoundary(
                    envelope = retryEnvelope,
                    checkpointReason = "before-peer-dispatch"
                )
                if(pausedBeforeRetry != null)
                {
                    return pausedBeforeRetry
                }
                checkpointTaskState(retryEnvelope, "before-peer-dispatch")
                dispatchExplicitPeerHandoff(
                    envelope = retryEnvelope,
                    directive = DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                        targetPeerId = retryPeerKey,
                        targetNodeId = directive.targetNodeId.ifBlank { fallbackTargetNodeId },
                        alternatePeerIds = directive.alternatePeerIds.deepCopy()
                    ),
                    startedAt = startedAt
                )
            }

            DistributionGridDirectiveKind.TRY_ALTERNATE_PEER ->
            {
                if(!envelope.routingPolicy.allowAlternatePeers)
                {
                    val failure = buildFailure(
                        kind = DistributionGridFailureKind.POLICY_REJECTED,
                        reason = "DistributionGrid alternate-peer routing was requested even though the current routing policy disallows it.",
                        retryable = false
                    )
                    envelope.latestFailure = failure
                    return finalizeFailedEnvelope(
                        envelope = envelope,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        directiveKind = directive.kind,
                        recordTerminalHop = false
                    )
                }

                if(directive.alternatePeerIds.isEmpty())
                {
                    val failure = buildFailure(
                        kind = DistributionGridFailureKind.ROUTING_FAILURE,
                        reason = "DistributionGrid alternate-peer routing requires explicit alternate peer ids.",
                        retryable = false
                    )
                    envelope.latestFailure = failure
                    return finalizeFailedEnvelope(
                        envelope = envelope,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        directiveKind = directive.kind,
                        recordTerminalHop = false
                    )
                }

                val alternateIndexKey = "$ALTERNATE_INDEX_ATTRIBUTE_PREFIX${directive.targetPeerId.ifBlank { fallbackPeerKey }}"
                val nextAlternateIndex = envelope.attributes[alternateIndexKey]?.toIntOrNull() ?: 0
                if(nextAlternateIndex >= directive.alternatePeerIds.size)
                {
                    val failure = buildFailure(
                        kind = DistributionGridFailureKind.ROUTING_FAILURE,
                        reason = "DistributionGrid exhausted alternate peers for '${directive.targetPeerId.ifBlank { fallbackPeerKey }}'.",
                        retryable = false
                    )
                    envelope.latestFailure = failure
                    return finalizeFailedEnvelope(
                        envelope = envelope,
                        failure = failure,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        directiveKind = directive.kind,
                        recordTerminalHop = false
                    )
                }

                val alternatePeerKey = directive.alternatePeerIds[nextAlternateIndex]
                envelope.attributes[alternateIndexKey] = (nextAlternateIndex + 1).toString()
                retryEnvelope.attributes[alternateIndexKey] = (nextAlternateIndex + 1).toString()
                val alternatePeerDescriptor = resolveExplicitPeerDescriptor(
                    DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                        targetPeerId = alternatePeerKey
                    )
                )?.descriptor
                trace(
                    TraceEventType.DISTRIBUTION_GRID_LOOP_GUARD,
                    TracePhase.ORCHESTRATION,
                    metadata = mapOf(
                        "taskId" to envelope.taskId,
                        "directive" to directive.kind.name,
                        "peerKey" to alternatePeerKey,
                        "alternateIndex" to nextAlternateIndex
                    )
                )
                retryEnvelope.currentNodeId = alternatePeerDescriptor?.distributionGridMetadata?.nodeId
                    ?: retryEnvelope.currentNodeId
                retryEnvelope.currentTransport = alternatePeerDescriptor?.transport?.deepCopy()
                    ?: retryEnvelope.currentTransport.deepCopy()
                val pausedBeforeRetry = maybePauseAtSafeBoundary(
                    envelope = retryEnvelope,
                    checkpointReason = "before-peer-dispatch"
                )
                if(pausedBeforeRetry != null)
                {
                    return pausedBeforeRetry
                }
                checkpointTaskState(retryEnvelope, "before-peer-dispatch")
                dispatchExplicitPeerHandoff(
                    envelope = retryEnvelope,
                    directive = DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                        targetPeerId = alternatePeerKey,
                        targetNodeId = fallbackTargetNodeId
                    ),
                    startedAt = startedAt
                )
            }

            else ->
            {
                val failure = buildFailure(
                    kind = DistributionGridFailureKind.ROUTING_FAILURE,
                    reason = "DistributionGrid cannot continue with unsupported directive '${directive.kind.name}'.",
                    retryable = false
                )
                envelope.latestFailure = failure
                finalizeFailedEnvelope(
                    envelope = envelope,
                    failure = failure,
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    directiveKind = directive.kind,
                    recordTerminalHop = false
                )
            }
        }
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
        retryable: Boolean,
        sourceNodeId: String = resolveCurrentNodeId()
    ): DistributionGridFailure
    {
        val transport = resolveCurrentTransport()
        return DistributionGridFailure(
            kind = kind,
            sourceNodeId = sourceNodeId,
            targetNodeId = "",
            transportMethod = transport.transportMethod,
            transportAddress = transport.transportAddress,
            reason = reason,
            policyCause = "",
            retryable = retryable
        )
    }

    /**
     * Resolve an explicit external peer descriptor for a remote handoff directive.
     *
     * @param directive Router directive requesting remote handoff.
     * @return Canonical peer key and descriptor pair, or `null` when no explicit peer matches.
     */
    private fun resolveExplicitPeerDescriptor(
        directive: DistributionGridDirective
    ): DistributionGridResolvedPeerTarget?
    {
        purgeExpiredDiscoveryState()
        val explicitAllowed = discoveryMode != DistributionGridPeerDiscoveryMode.REGISTRY_ONLY
        val registryAllowed = discoveryMode != DistributionGridPeerDiscoveryMode.EXPLICIT_ONLY

        val targetPeerId = directive.targetPeerId
        if(explicitAllowed && targetPeerId.isNotBlank())
        {
            val descriptor = externalPeerDescriptorsByKey[targetPeerId]
            if(descriptor != null)
            {
                return DistributionGridResolvedPeerTarget(
                    peerKey = targetPeerId,
                    descriptor = descriptor
                )
            }
        }

        val targetNodeId = directive.targetNodeId
        if(targetNodeId.isNotBlank())
        {
            if(explicitAllowed)
            {
                val resolvedExplicit = externalPeerDescriptorsByKey.entries.firstOrNull { entry ->
                    entry.value.distributionGridMetadata?.nodeId == targetNodeId
                }
                if(resolvedExplicit != null)
                {
                    return DistributionGridResolvedPeerTarget(
                        peerKey = resolvedExplicit.key,
                        descriptor = resolvedExplicit.value
                    )
                }
            }

            if(registryAllowed)
            {
                val discoveredAdvertisement = resolveFreshDiscoveredNodeAdvertisement(targetNodeId)
                val discoveredDescriptor = discoveredAdvertisement?.descriptor
                if(discoveredAdvertisement != null && discoveredDescriptor != null)
                {
                    return DistributionGridResolvedPeerTarget(
                        peerKey = "discovered::${discoveredAdvertisement.registryId}::$targetNodeId",
                        descriptor = discoveredDescriptor.deepCopy(),
                        registryScope = DistributionGridRegistryScope(
                            memberships = discoveredAdvertisement.registryId
                                .takeIf { it.isNotBlank() }
                                ?.let { listOf(it) }
                                ?: emptyList()
                        )
                    )
                }
            }
        }

        return null
    }

    /**
     * Resolve one discovered node advertisement only when it is still fresh and still tied to a trusted registry.
     *
     * @param nodeId Canonical discovered node id.
     * @return Fresh discovered advertisement, or `null` when the cached advertisement must not be reused.
     */
    private fun resolveFreshDiscoveredNodeAdvertisement(
        nodeId: String
    ): DistributionGridNodeAdvertisement?
    {
        val now = System.currentTimeMillis()
        val validCandidates = mutableListOf<Pair<DistributionGridDiscoveredNodeKey, DistributionGridNodeAdvertisement>>()

        discoveredNodeAdvertisementsById.entries
            .filter { it.key.nodeId == nodeId }
            .forEach { (_, discoveredAdvertisement) ->
                val registryId = discoveredAdvertisement.registryId
                if(registryId.isBlank() || resolveRegistryAdvertisement(registryId) == null)
                {
                    return@forEach
                }

                val leaseExpiry = discoveredAdvertisement.lease?.expiresAtEpochMillis
                    ?: discoveredAdvertisement.expiresAtEpochMillis
                if(leaseExpiry <= now || discoveredAdvertisement.expiresAtEpochMillis <= now)
                {
                    return@forEach
                }

                val resolvedNodeId = resolveDiscoveredNodeId(discoveredAdvertisement)
                if(resolvedNodeId.isBlank())
                {
                    return@forEach
                }

                validCandidates.add(
                    DistributionGridDiscoveredNodeKey(resolvedNodeId, registryId) to canonicalizeDiscoveredNodeAdvertisement(
                        candidate = discoveredAdvertisement,
                        resolvedNodeId = resolvedNodeId
                    )
                )
            }

        return validCandidates
            .sortedWith(
                compareByDescending<Pair<DistributionGridDiscoveredNodeKey, DistributionGridNodeAdvertisement>> {
                    it.second.lease?.expiresAtEpochMillis ?: it.second.expiresAtEpochMillis
                }.thenByDescending { it.second.discoveredAtEpochMillis }
                    .thenBy { it.first.registryId }
            )
            .firstOrNull()
            ?.second
    }

    /**
     * Resolve the canonical discovered node id from a verified advertisement.
     *
     * @param candidate Discovered node advertisement under consideration.
     * @return Canonical node id or blank when the advertisement still does not identify a stable node.
     */
    private fun resolveDiscoveredNodeId(
        candidate: DistributionGridNodeAdvertisement
    ): String
    {
        return candidate.metadata.nodeId.ifBlank {
            candidate.descriptor?.distributionGridMetadata?.nodeId.orEmpty()
        }
    }

    /**
     * Normalize one discovered node advertisement so all identity fields align with the resolved node id.
     *
     * @param candidate Discovered node advertisement to normalize.
     * @param resolvedNodeId Canonical node id resolved for the advertisement.
     * @return Normalized deep copy of the candidate.
     */
    private fun canonicalizeDiscoveredNodeAdvertisement(
        candidate: DistributionGridNodeAdvertisement,
        resolvedNodeId: String
    ): DistributionGridNodeAdvertisement
    {
        return candidate.deepCopy().apply {
            metadata.nodeId = resolvedNodeId
            descriptor?.distributionGridMetadata?.nodeId = resolvedNodeId
        }
    }

    /**
     * Build the canonical cache key for one discovered node advertisement.
     *
     * @param nodeId Canonical discovered node id.
     * @param registryId Registry that surfaced the node.
     * @return Structured discovered-node cache key.
     */
    private fun buildDiscoveredNodeKey(
        nodeId: String,
        registryId: String
    ): DistributionGridDiscoveredNodeKey
    {
        return DistributionGridDiscoveredNodeKey(
            nodeId = nodeId,
            registryId = registryId
        )
    }

    /**
     * Resolve an existing valid session or create a new one through the explicit-peer handshake path.
     *
     * @param peerKey Canonical explicit-peer key.
     * @param descriptor Explicit remote peer descriptor.
     * @param envelope Current execution envelope.
     * @return Valid session record or `null` when negotiation fails.
     */
    private suspend fun resolveOrCreatePeerSession(
        peerKey: String,
        descriptor: P2PDescriptor,
        envelope: DistributionGridEnvelope,
        registryScopeOverride: DistributionGridRegistryScope? = null
    ): DistributionGridPeerHandshakeOutcome
    {
        val registryScope = registryScopeOverride ?: resolveCurrentRegistryScope()
        val cachedSession = resolveValidCachedSession(peerKey, registryScope, descriptor, envelope)
        if(cachedSession != null)
        {
            return DistributionGridPeerHandshakeOutcome(sessionRecord = cachedSession)
        }

        return performPeerHandshake(peerKey, registryScope, descriptor, envelope)
    }

    /**
     * Reuse a cached explicit-peer session only when it still matches the current peer and protocol constraints.
     *
     * @param peerKey Canonical explicit-peer key.
     * @param descriptor Explicit peer descriptor.
     * @return Cached session record or `null` when a fresh handshake is required.
     */
    private fun resolveValidCachedSession(
        peerKey: String,
        registryScope: DistributionGridRegistryScope,
        descriptor: P2PDescriptor,
        envelope: DistributionGridEnvelope
    ): DistributionGridSessionRecord?
    {
        val sessionRecord = sessionRecordsByPeerKey[buildPeerSessionCacheKey(peerKey, registryScope)] ?: return null
        val peerNodeId = descriptor.distributionGridMetadata?.nodeId.orEmpty()
        if(!isSessionValid(
                sessionRecord = sessionRecord,
                expectedRequesterNodeId = resolveCurrentNodeId(),
                expectedResponderNodeId = peerNodeId
            )
        )
        {
            invalidateSession(sessionRecord, "Cached explicit-peer session is no longer valid.")
            return null
        }

        if(!sessionPolicySatisfiesEnvelope(sessionRecord, envelope))
        {
            invalidateSession(sessionRecord, "Cached explicit-peer session no longer satisfies the current envelope policy.")
            return null
        }

        return sessionRecord.deepCopy()
    }

    /**
     * Resolve a cached explicit-peer session using the legacy registry-token entrypoint.
     *
     * @param peerKey Canonical explicit-peer key.
     * @param registryId Registry relationship token to resolve.
     * @param descriptor Explicit peer descriptor.
     * @param envelope Current task envelope.
     * @return Cached session record or `null` when a fresh handshake is required.
     */
    private fun resolveValidCachedSession(
        peerKey: String,
        registryId: String,
        descriptor: P2PDescriptor,
        envelope: DistributionGridEnvelope
    ): DistributionGridSessionRecord?
    {
        return resolveValidCachedSession(
            peerKey = peerKey,
            registryScope = resolveRegistryScopeFromToken(registryId),
            descriptor = descriptor,
            envelope = envelope
        )
    }

    /**
     * Perform the explicit-peer handshake needed before the first remote task handoff.
     *
     * @param peerKey Canonical explicit-peer key.
     * @param descriptor Explicit peer descriptor.
     * @param envelope Current execution envelope.
     * @return Negotiated session record or failure details when the handshake fails.
     */
    private suspend fun performPeerHandshake(
        peerKey: String,
        registryScope: DistributionGridRegistryScope,
        descriptor: P2PDescriptor,
        envelope: DistributionGridEnvelope
    ): DistributionGridPeerHandshakeOutcome
    {
        val localMetadata = p2pDescriptor?.distributionGridMetadata ?: return DistributionGridPeerHandshakeOutcome(
            failure = buildFailure(
                kind = DistributionGridFailureKind.VALIDATION_FAILURE,
                reason = "DistributionGrid is missing local grid metadata for explicit-peer handshake.",
                retryable = false
            )
        )
        val peerMetadata = descriptor.distributionGridMetadata ?: return DistributionGridPeerHandshakeOutcome(
            failure = buildFailure(
                kind = DistributionGridFailureKind.TRUST_REJECTED,
                reason = "DistributionGrid explicit peer '$peerKey' is missing grid metadata.",
                retryable = false
            )
        )

        if(!isDescriptorPrivacyCompatible(descriptor, envelope.tracePolicy))
        {
            return DistributionGridPeerHandshakeOutcome(
                failure = buildFailure(
                    kind = DistributionGridFailureKind.POLICY_REJECTED,
                    reason = "DistributionGrid explicit peer '$peerKey' conflicts with the current trace or privacy policy.",
                    retryable = false
                )
            )
        }

        val handshakeRequest = DistributionGridHandshakeRequest(
            requesterNodeId = resolveCurrentNodeId(),
            requesterMetadata = localMetadata.deepCopy(),
            registryId = registryScope.toRegistryToken(),
            supportedProtocolVersions = localMetadata.supportedProtocolVersions.deepCopy<MutableList<DistributionGridProtocolVersion>>(),
            acceptedTransports = mutableListOf(descriptor.transport.transportMethod),
            tracePolicy = envelope.tracePolicy.deepCopy(),
            credentialPolicy = envelope.credentialPolicy.deepCopy(),
            acceptedRoutingPolicy = envelope.routingPolicy.deepCopy(),
            requestedSessionDurationSeconds = maxSessionDurationSeconds
        )

        trace(
            TraceEventType.DISTRIBUTION_GRID_SESSION_HANDSHAKE,
            TracePhase.AGENT_COMMUNICATION,
            metadata = mapOf(
                "peerKey" to peerKey,
                "targetNodeId" to peerMetadata.nodeId,
                "registryId" to registryScope.toRegistryToken()
            )
        )

        val handshakeRpcMessage = DistributionGridRpcMessage(
            messageType = DistributionGridRpcMessageType.HANDSHAKE_INIT,
            senderNodeId = resolveCurrentNodeId(),
            targetId = peerMetadata.nodeId,
            protocolVersion = localMetadata.supportedProtocolVersions.firstOrNull()?.deepCopy()
                ?: DistributionGridProtocolVersion(),
            sessionRef = null,
            payloadType = HANDSHAKE_PAYLOAD_TYPE,
            payloadJson = serialize(handshakeRequest)
        )
        val handshakeRequestP2P = buildGridRpcRequest(descriptor, handshakeRpcMessage)
        injectOutboundCredentials(
            envelope = envelope,
            peerKey = peerKey,
            descriptor = descriptor,
            request = handshakeRequestP2P
        )
        val outboundPolicyFailure = validateOutboundPolicy(
            envelope = envelope,
            peerKey = peerKey,
            descriptor = descriptor,
            request = handshakeRequestP2P
        )
        if(outboundPolicyFailure != null)
        {
            return DistributionGridPeerHandshakeOutcome(failure = outboundPolicyFailure)
        }

        val response = externalP2PCallWithTimeout(handshakeRequestP2P)

        if(response.rejection != null)
        {
            return DistributionGridPeerHandshakeOutcome(
                failure = mapRemoteP2PRejectionToFailure(
                    rejection = response.rejection!!,
                    peerDescriptor = descriptor
                )
            )
        }

        val rpcResponse = parseGridRpcMessage(response.output?.text.orEmpty()) ?: return DistributionGridPeerHandshakeOutcome(
            failure = buildFailure(
                kind = DistributionGridFailureKind.TRANSPORT_FAILURE,
                reason = "DistributionGrid explicit peer '$peerKey' returned a non-grid handshake response.",
                retryable = true
            )
        )
        return when(rpcResponse.messageType)
        {
            DistributionGridRpcMessageType.HANDSHAKE_ACK ->
            {
                val handshakeResponse = deserialize<DistributionGridHandshakeResponse>(
                    rpcResponse.payloadJson,
                    useRepair = false
                ) ?: return DistributionGridPeerHandshakeOutcome(
                    failure = buildFailure(
                        kind = DistributionGridFailureKind.TRANSPORT_FAILURE,
                        reason = "DistributionGrid explicit peer '$peerKey' returned an unreadable handshake acknowledgment.",
                        retryable = false
                    )
                )

                if(!handshakeResponse.accepted)
                {
                    val rejectionReason = handshakeResponse.rejectionReason.takeIf { it.isNotBlank() }
                        ?: "DistributionGrid explicit peer '$peerKey' rejected the handshake."
                    return DistributionGridPeerHandshakeOutcome(
                        failure = buildFailure(
                            kind = DistributionGridFailureKind.HANDSHAKE_REJECTED,
                            reason = rejectionReason,
                            retryable = false
                        )
                    )
                }

                val sessionRecord = handshakeResponse.sessionRecord ?: return DistributionGridPeerHandshakeOutcome(
                    failure = buildFailure(
                        kind = DistributionGridFailureKind.SESSION_REJECTED,
                        reason = "DistributionGrid explicit peer '$peerKey' omitted the negotiated session record.",
                        retryable = false
                    )
                )
                if(rpcResponse.sessionRef != sessionRecord.toSessionRef())
                {
                    return DistributionGridPeerHandshakeOutcome(
                        failure = buildFailure(
                            kind = DistributionGridFailureKind.HANDSHAKE_REJECTED,
                            reason = "DistributionGrid explicit peer '$peerKey' returned a handshake session reference that did not match the negotiated session record.",
                            retryable = false
                        )
                    )
                }
                val handshakeNegotiatedPolicy = handshakeResponse.negotiatedPolicy ?: return DistributionGridPeerHandshakeOutcome(
                    failure = buildFailure(
                        kind = DistributionGridFailureKind.HANDSHAKE_REJECTED,
                        reason = "DistributionGrid explicit peer '$peerKey' omitted the negotiated policy in its handshake acknowledgment.",
                        retryable = false
                    )
                )
                val canonicalSessionPolicy = canonicalizeNegotiatedPolicyForComparison(sessionRecord.negotiatedPolicy)
                val canonicalHandshakePolicy = canonicalizeNegotiatedPolicyForComparison(
                    handshakeNegotiatedPolicy
                )
                val requestedRegistryId = registryScope.toRegistryToken()
                if(!handshakeResponse.accepted ||
                    handshakeResponse.negotiatedProtocolVersion != sessionRecord.negotiatedProtocolVersion ||
                    sessionRecord.registryId != requestedRegistryId ||
                    canonicalHandshakePolicy != canonicalSessionPolicy ||
                    !isHandshakeSessionDurationWithinRequestedWindow(
                        sessionRecord = sessionRecord,
                        handshakeRequest = handshakeRequest
                    ) ||
                    !negotiatedPolicySatisfiesHandshakeRequest(
                        negotiatedPolicy = sessionRecord.negotiatedPolicy,
                        handshakeRequest = handshakeRequest
                    ) ||
                    !isSessionValid(
                        sessionRecord = sessionRecord,
                        expectedRequesterNodeId = resolveCurrentNodeId(),
                        expectedResponderNodeId = peerMetadata.nodeId
                    )
                )
                {
                    return DistributionGridPeerHandshakeOutcome(
                        failure = buildFailure(
                            kind = DistributionGridFailureKind.HANDSHAKE_REJECTED,
                            reason = "DistributionGrid explicit peer '$peerKey' returned an invalid or widened handshake acknowledgment.",
                            retryable = false
                        )
                    )
                }

        cacheSession(sessionRecord, peerKey, registryScope)
        DistributionGridPeerHandshakeOutcome(sessionRecord = sessionRecord.deepCopy())
    }

            DistributionGridRpcMessageType.SESSION_REJECT ->
            {
                val peerFailure = deserialize<DistributionGridFailure>(
                    rpcResponse.payloadJson,
                    useRepair = false
                ) ?: deserialize<DistributionGridEnvelope>(rpcResponse.payloadJson, useRepair = false)
                    ?.latestFailure
                    ?: buildFailure(
                        kind = DistributionGridFailureKind.SESSION_REJECTED,
                        reason = "DistributionGrid explicit peer '$peerKey' rejected the handshake.",
                        retryable = false
                    )

                DistributionGridPeerHandshakeOutcome(failure = peerFailure)
            }

            else ->
            {
                DistributionGridPeerHandshakeOutcome(
                    failure = buildFailure(
                        kind = DistributionGridFailureKind.SESSION_REJECTED,
                        reason = "DistributionGrid explicit peer '$peerKey' returned unsupported handshake RPC '${rpcResponse.messageType.name}'.",
                        retryable = false
                    )
                )
            }
        }
    }

    /**
     * Execute one outbound P2P call with the configured RPC timeout.
     *
     * @param request Outbound P2P request.
     * @return P2P response or a transport rejection on timeout.
     */
    private suspend fun externalP2PCallWithTimeout(request: P2PRequest): P2PResponse
    {
        return try
        {
            kotlinx.coroutines.withTimeout(rpcTimeoutMillis) {
                P2PRegistry.externalP2PCall(request)
            }
        }
        catch(_: kotlinx.coroutines.TimeoutCancellationException)
        {
            P2PResponse(
                rejection = P2PRejection(
                    errorType = P2PError.transport,
                    reason = "DistributionGrid RPC call timed out after ${rpcTimeoutMillis}ms."
                )
            )
        }
    }

    /**
     * Send one grid RPC request to an explicit remote peer using the normal TPipe transport layer.
     *
     * @param descriptor Explicit peer descriptor.
     * @param rpcMessage Grid RPC message to send.
     * @return P2P response from the remote peer.
     */
    private suspend fun sendGridRpcRequest(
        descriptor: P2PDescriptor,
        rpcMessage: DistributionGridRpcMessage
    ): P2PResponse
    {
        val request = buildGridRpcRequest(descriptor, rpcMessage)
        return externalP2PCallWithTimeout(request)
    }

    /**
     * Send one registry RPC request using a verified registry advertisement as the target.
     *
     * @param advertisement Verified registry advertisement to contact.
     * @param rpcMessage Registry RPC payload to send.
     * @return P2P response from the remote registry.
     */
    private suspend fun sendRegistryRpcRequest(
        advertisement: DistributionGridRegistryAdvertisement,
        rpcMessage: DistributionGridRpcMessage
    ): P2PResponse
    {
        val descriptor = P2PDescriptor(
            agentName = advertisement.metadata.registryId.ifBlank {
                advertisement.transport.transportAddress
            },
            agentDescription = "DistributionGrid registry endpoint",
            transport = advertisement.transport.deepCopy(),
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
            distributionGridMetadata = DistributionGridNodeMetadata(
                nodeId = advertisement.metadata.registryId.ifBlank {
                    advertisement.transport.transportAddress
                },
                supportedProtocolVersions = advertisement.metadata.supportedProtocolVersions.deepCopy(),
                supportedTransports = mutableListOf(advertisement.transport.transportMethod),
                actsAsRegistry = true
            )
        )

        return sendGridRpcRequest(descriptor, rpcMessage)
    }

    /**
     * Build one outbound P2P request for a grid RPC call using the normal request-template precedence.
     *
     * @param descriptor Explicit peer descriptor to target.
     * @param rpcMessage Grid RPC payload to send.
     * @return P2P request carrying the serialized RPC message.
     */
    private fun buildGridRpcRequest(
        descriptor: P2PDescriptor,
        rpcMessage: DistributionGridRpcMessage
    ): P2PRequest
    {
        val baseRequest = descriptor.requestTemplate?.deepCopy<P2PRequest>()
            ?: P2PRegistry.requestTemplates[descriptor.agentName]?.deepCopy<P2PRequest>()
            ?: P2PRequest()

        return baseRequest.apply {
            transport = descriptor.transport.copy()
            returnAddress = resolveCurrentTransport()
            prompt = buildGridRpcContent(rpcMessage)
            context = null
            customContextDescriptions = null
            inputSchema = null
            outputSchema = null
        }
    }

    /**
     * Build terminal content that carries one serialized grid RPC message.
     *
     * @param rpcMessage Grid RPC message to serialize.
     * @return P2P-safe multimodal content payload.
     */
    private fun buildGridRpcContent(rpcMessage: DistributionGridRpcMessage): MultimodalContent
    {
        return MultimodalContent(text = "$GRID_RPC_FRAME_PREFIX\n${serialize(rpcMessage)}")
    }

    /**
     * Resolve the protocol version used for one outbound registry RPC call.
     *
     * @param advertisement Registry advertisement being contacted.
     * @return Outbound protocol version.
     */
    private fun resolveRegistryProtocolVersion(
        advertisement: DistributionGridRegistryAdvertisement
    ): DistributionGridProtocolVersion
    {
        val localVersions = p2pDescriptor?.distributionGridMetadata?.supportedProtocolVersions.orEmpty()
        val remoteVersions = advertisement.metadata.supportedProtocolVersions
        return DistributionGridProtocolVersion.negotiateHighestShared(
            localVersions = localVersions,
            remoteVersions = remoteVersions
        ) ?: remoteVersions.firstOrNull()?.deepCopy()
            ?: localVersions.firstOrNull()?.deepCopy()
            ?: DistributionGridProtocolVersion()
    }

    /**
     * Resolve the protocol version used for one inbound registry RPC response.
     *
     * @param rpcMessage Inbound registry RPC wrapper.
     * @return Protocol version echoed on the response wrapper.
     */
    private fun resolveInboundRegistryProtocolVersion(
        rpcMessage: DistributionGridRpcMessage
    ): DistributionGridProtocolVersion
    {
        val localSupported = registryMetadata?.supportedProtocolVersions
            ?: p2pDescriptor?.distributionGridMetadata?.supportedProtocolVersions
            ?: mutableListOf(DistributionGridProtocolVersion())

        return DistributionGridProtocolVersion.negotiateHighestShared(
            localVersions = localSupported,
            remoteVersions = listOf(rpcMessage.protocolVersion)
        ) ?: rpcMessage.protocolVersion.deepCopy()
    }

    /**
     * Resolve one known registry advertisement by id from discovered or bootstrap state.
     *
     * @param registryId Registry id to resolve.
     * @return Matching registry advertisement or `null` when none is known.
     */
    private fun resolveRegistryAdvertisement(
        registryId: String
    ): DistributionGridRegistryAdvertisement?
    {
        val now = System.currentTimeMillis()
        val discoveredAdvertisement = discoveredRegistriesById[registryId]
        if(discoveredAdvertisement != null && discoveredAdvertisement.expiresAtEpochMillis > now)
        {
            return discoveredAdvertisement.deepCopy()
        }

        val bootstrapAdvertisement = bootstrapRegistriesById[registryId]
        if(bootstrapAdvertisement != null && bootstrapAdvertisement.expiresAtEpochMillis > now)
        {
            return bootstrapAdvertisement.deepCopy()
        }

        return null
    }

    /**
     * Parse one registry lease from a registry RPC response.
     *
     * @param response Raw P2P response returned by the registry.
     * @return Granted or renewed lease, or `null` when the registry rejected the operation.
     */
    private fun extractRegistryLeaseFromResponse(
        response: P2PResponse,
        expectedMessageType: DistributionGridRpcMessageType,
        expectedRegistryId: String,
        expectedNodeId: String
    ): DistributionGridRegistrationLease?
    {
        if(response.rejection != null)
        {
            return null
        }

        val rpcResponse = parseGridRpcMessage(response.output?.text.orEmpty()) ?: return null
        if(rpcResponse.messageType != expectedMessageType ||
            rpcResponse.payloadType != REGISTRATION_LEASE_PAYLOAD_TYPE
        )
        {
            return null
        }

        val lease = deserialize<DistributionGridRegistrationLease>(rpcResponse.payloadJson, useRepair = false)
            ?: return null

        if(lease.leaseId.isBlank() ||
            lease.nodeId != expectedNodeId ||
            lease.registryId != expectedRegistryId ||
            lease.expiresAtEpochMillis <= System.currentTimeMillis() ||
            lease.grantedLeaseSeconds <= 0
        )
        {
            return null
        }

        return lease
    }

    /**
     * Build the local registry advertisement published by this node.
     *
     * @return Current local registry advertisement.
     */
    private fun buildLocalRegistryAdvertisement(): DistributionGridRegistryAdvertisement
    {
        val localRegistryMetadata = requireNotNull(registryMetadata).deepCopy()
        val now = System.currentTimeMillis()
        return DistributionGridRegistryAdvertisement(
            transport = resolveCurrentTransport(),
            metadata = localRegistryMetadata,
            attestationRef = if(localRegistryMetadata.bootstrapTrusted)
            {
                "bootstrap:${localRegistryMetadata.registryId}"
            }
            else
            {
                "registry:${localRegistryMetadata.registryId}"
            },
            discoveredAtEpochMillis = now,
            expiresAtEpochMillis = now + localRegistryMetadata.defaultLeaseSeconds.coerceAtLeast(1) * 1000L
        )
    }

    /**
     * Grant one local registry lease.
     *
     * @param nodeId Node being registered.
     * @param registrationRequest Registration request supplied by the caller.
     * @param existingLeaseId Existing lease id when the request is a renewal.
     * @return Granted lease.
     */
    private fun grantRegistrationLease(
        nodeId: String,
        registrationRequest: DistributionGridRegistrationRequest,
        existingLeaseId: String?
    ): DistributionGridRegistrationLease
    {
        val localRegistryMetadata = requireNotNull(registryMetadata)
        val grantedLeaseSeconds = registrationRequest.requestedLeaseSeconds
            .coerceAtLeast(1)
            .coerceAtMost(localRegistryMetadata.defaultLeaseSeconds.coerceAtLeast(1))
        return DistributionGridRegistrationLease(
            leaseId = existingLeaseId ?: UUID.randomUUID().toString(),
            nodeId = nodeId,
            registryId = localRegistryMetadata.registryId,
            grantedLeaseSeconds = grantedLeaseSeconds,
            expiresAtEpochMillis = System.currentTimeMillis() + grantedLeaseSeconds * 1000L,
            renewalRequired = localRegistryMetadata.leaseRequired
        )
    }

    /**
     * Build one node advertisement stored by the local registry directory.
     *
     * @param descriptor Descriptor to advertise.
     * @param metadata Node metadata to advertise.
     * @param lease Active lease associated with the node.
     * @return Stored node advertisement.
     */
    private fun buildRegisteredNodeAdvertisement(
        descriptor: P2PDescriptor,
        metadata: DistributionGridNodeMetadata,
        lease: DistributionGridRegistrationLease
    ): DistributionGridNodeAdvertisement
    {
        val now = System.currentTimeMillis()
        return DistributionGridNodeAdvertisement(
            descriptor = descriptor.deepCopy(),
            metadata = metadata.deepCopy(),
            registryId = lease.registryId,
            lease = lease.deepCopy(),
            attestationRef = "registry:${lease.registryId}:node:${metadata.nodeId}",
            discoveredAtEpochMillis = now,
            expiresAtEpochMillis = lease.expiresAtEpochMillis
        )
    }

    /**
     * Replace any existing lease state for one node with the newly granted lease.
     *
     * @param nodeId Node whose lease state is being updated.
     * @param lease Newly granted lease.
     * @param advertisement Advertised node state associated with the lease.
     */
    private fun storeLocalRegistrationLeaseState(
        nodeId: String,
        lease: DistributionGridRegistrationLease,
        advertisement: DistributionGridNodeAdvertisement
    )
    {
        val priorLeaseId = localRegistrationLeaseIdsByNodeId[nodeId]
        if(priorLeaseId != null && priorLeaseId != lease.leaseId)
        {
            localRegistrationLeasesById.remove(priorLeaseId)
        }

        localRegistrationLeasesById.entries.removeIf { it.value.nodeId == nodeId && it.key != lease.leaseId }
        localRegisteredNodeAdvertisementsById[nodeId] = advertisement.deepCopy()
        localRegistrationLeasesById[lease.leaseId] = lease.deepCopy()
        localRegistrationLeaseIdsByNodeId[nodeId] = lease.leaseId
    }

    /**
     * Build a fallback descriptor for a remote registration request when the caller omitted one.
     *
     * @param request Raw inbound P2P request.
     * @param registrationRequest Registration request supplied by the caller.
     * @return Descriptor synthesized from the request transport and node metadata.
     */
    private fun buildRemoteDescriptorFromRegistration(
        request: P2PRequest,
        registrationRequest: DistributionGridRegistrationRequest
    ): P2PDescriptor?
    {
        val callerTransport = request.returnAddress.deepCopy().takeIf {
            it.transportAddress.isNotBlank() || it.transportAuthBody.isNotBlank()
        } ?: return null

        return P2PDescriptor(
            agentName = registrationRequest.metadata.nodeId,
            agentDescription = "DistributionGrid registered node",
            transport = callerTransport,
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
            distributionGridMetadata = registrationRequest.metadata.deepCopy()
        )
    }

    /**
     * Check whether one cached node advertisement matches a structured registry query.
     *
     * @param advertisement Cached node advertisement.
     * @param query Structured query to evaluate.
     * @return `true` when the advertisement matches the requested filters.
     */
    private fun matchesRegistryQuery(
        advertisement: DistributionGridNodeAdvertisement,
        query: DistributionGridRegistryQuery
    ): Boolean
    {
        val metadata = advertisement.metadata
        if(query.requiredCapabilities.isNotEmpty() &&
            !query.requiredCapabilities.all { capability -> capability in metadata.roleCapabilities }
        )
        {
            return false
        }

        if(query.acceptedTransports.isNotEmpty() &&
            metadata.supportedTransports.none { it in query.acceptedTransports }
        )
        {
            return false
        }

        if(query.registryIds.isNotEmpty() && advertisement.registryId !in query.registryIds)
        {
            return false
        }

        val localRegistryTrustDomain = registryMetadata?.trustDomainId.orEmpty()
        if(query.trustDomainIds.isNotEmpty() &&
            localRegistryTrustDomain !in query.trustDomainIds
        )
        {
            return false
        }

        if(query.requireHealthy && advertisement.lease == null)
        {
            return false
        }

        if(query.freshnessWindowSeconds > 0)
        {
            val now = System.currentTimeMillis()
            if(advertisement.expiresAtEpochMillis <= now ||
                advertisement.discoveredAtEpochMillis < now - query.freshnessWindowSeconds * 1000L
            )
            {
                return false
            }
        }

        return true
    }

    private fun buildHostedRegistryMutationFailure(reason: String): P2PHostedRegistryMutationResult
    {
        return P2PHostedRegistryMutationResult(
            accepted = false,
            rejectionReason = reason
        )
    }

    private fun buildPublicNodeHostedListing(
        options: DistributionGridPublicListingOptions
    ): P2PHostedRegistryListing?
    {
        val descriptor = p2pDescriptor?.deepCopy() ?: return null
        val metadata = descriptor.distributionGridMetadata?.deepCopy() ?: return null
        val now = System.currentTimeMillis()
        return P2PHostedRegistryListing(
            kind = P2PHostedListingKind.GRID_NODE,
            metadata = P2PHostedListingMetadata(
                title = options.title.ifBlank { descriptor.agentName.ifBlank { metadata.nodeId } },
                summary = options.summary.ifBlank { descriptor.agentDescription },
                categories = options.categories.toMutableList(),
                tags = options.tags.toMutableList()
            ),
            publicDescriptor = descriptor.deepCopy(),
            gridNodeAdvertisement = DistributionGridNodeAdvertisement(
                descriptor = descriptor.deepCopy(),
                metadata = metadata.deepCopy(),
                registryId = metadata.registryMemberships.firstOrNull().orEmpty(),
                discoveredAtEpochMillis = now,
                expiresAtEpochMillis = now + options.requestedLeaseSeconds.coerceAtLeast(1) * 1000L
            )
        )
    }

    private fun buildPublicRegistryHostedListing(
        options: DistributionGridPublicListingOptions
    ): P2PHostedRegistryListing?
    {
        val metadata = registryMetadata?.deepCopy() ?: return null
        val nodeDescriptor = p2pDescriptor?.deepCopy() ?: return null
        val now = System.currentTimeMillis()
        val advertisement = DistributionGridRegistryAdvertisement(
            transport = resolveCurrentTransport(),
            metadata = metadata,
            discoveredAtEpochMillis = now,
            expiresAtEpochMillis = now + options.requestedLeaseSeconds.coerceAtLeast(1) * 1000L
        )
        return P2PHostedRegistryListing(
            kind = P2PHostedListingKind.GRID_REGISTRY,
            metadata = P2PHostedListingMetadata(
                title = options.title.ifBlank { metadata.registryId.ifBlank { nodeDescriptor.agentName } },
                summary = options.summary.ifBlank { nodeDescriptor.agentDescription },
                categories = options.categories.toMutableList(),
                tags = options.tags.toMutableList()
            ),
            publicDescriptor = nodeDescriptor.deepCopy(),
            gridRegistryAdvertisement = advertisement
        )
    }

    /**
     * Drop expired local registration entries before answering registry queries.
     */
    private fun purgeExpiredLocalRegistrationState()
    {
        val now = System.currentTimeMillis()
        val expiredLeaseIds = localRegistrationLeasesById.values
            .filter { it.expiresAtEpochMillis <= now }
            .map { it.leaseId }
            .toSet()
        if(expiredLeaseIds.isEmpty())
        {
            return
        }

        localRegistrationLeasesById.entries.removeIf { it.key in expiredLeaseIds }
        localRegistrationLeaseIdsByNodeId.entries.removeIf { it.value in expiredLeaseIds }
        localRegisteredNodeAdvertisementsById.entries.removeIf { entry ->
            entry.value.lease?.leaseId in expiredLeaseIds
        }
    }

    /**
     * Drop expired entries from discovered registries, discovered nodes, and bootstrap registries.
     *
     * Called at defined lifecycle points so read methods stay pure.
     */
    private fun purgeExpiredDiscoveryState()
    {
        val now = System.currentTimeMillis()
        discoveredRegistriesById.entries.removeIf { it.value.expiresAtEpochMillis <= now }
        discoveredNodeAdvertisementsById.entries.removeIf { (_, adv) ->
            val leaseExpiry = adv.lease?.expiresAtEpochMillis ?: adv.expiresAtEpochMillis
            leaseExpiry <= now || adv.expiresAtEpochMillis <= now
        }
        bootstrapRegistriesById.entries.removeIf { it.value.expiresAtEpochMillis <= now }
    }

    /**
     * Synchronize local node metadata memberships from the current active registry leases.
     */
    private fun syncRegistryMembershipsFromActiveLeases()
    {
        val metadata = p2pDescriptor?.distributionGridMetadata ?: return
        val previousMemberships = metadata.registryMemberships.toSet()
        val nextMemberships = activeRegistryLeasesById.values
            .map { it.registryId }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toMutableList()

        metadata.registryMemberships = nextMemberships
        if(previousMemberships != nextMemberships.toSet())
        {
            invalidateAllSessions("Registry membership changed.")
        }
    }

    /**
     * Resolve the manual renewal window used by `tickRegistryMemberships(...)`.
     *
     * @param lease Lease currently under consideration.
     * @return Renewal window in milliseconds.
     */
    private fun resolveLeaseRenewalWindowMillis(
        lease: DistributionGridRegistrationLease
    ): Long
    {
        val renewalWindowSeconds = minOf(300, maxOf(1, lease.grantedLeaseSeconds / 5))
        return renewalWindowSeconds * 1000L
    }

    /**
     * Canonicalize a negotiated policy so storage-class ordering does not affect equality checks.
     *
     * @param negotiatedPolicy Negotiated policy to normalize for comparison.
     * @return Canonicalized deep copy of the negotiated policy.
     */
    private fun canonicalizeNegotiatedPolicyForComparison(
        negotiatedPolicy: DistributionGridNegotiatedPolicy
    ): DistributionGridNegotiatedPolicy
    {
        return negotiatedPolicy.deepCopy().apply {
            storageClasses = storageClasses.distinct().sorted().toMutableList()
        }
    }

    /**
     * Clamp one execution envelope to the effective negotiated session policy before remote execution continues.
     *
     * @param envelope Envelope to update in place.
     * @param sessionRecord Session record that carries the negotiated policy.
     */
    private fun applyNegotiatedPolicyToEnvelope(
        envelope: DistributionGridEnvelope,
        sessionRecord: DistributionGridSessionRecord
    )
    {
        val negotiatedPolicy = sessionRecord.negotiatedPolicy
        envelope.tracePolicy = negotiatedPolicy.tracePolicy.deepCopy().apply {
            allowedStorageClasses = negotiatedPolicy.storageClasses.deepCopy()
        }
        envelope.routingPolicy = negotiatedPolicy.routingPolicy.deepCopy()
        envelope.credentialPolicy = negotiatedPolicy.credentialPolicy.deepCopy()
    }

    /**
     * Build one grid RPC wrapper that carries a serialized failure payload.
     *
     * @param messageType Failure-flavored grid RPC type.
     * @param targetId Target node identity for the message.
     * @param failure Failure payload to serialize.
     * @return Grid RPC message carrying the failure payload.
     */
    private fun buildFailureRpcMessage(
        messageType: DistributionGridRpcMessageType,
        targetId: String,
        protocolVersion: DistributionGridProtocolVersion? = null,
        sessionRef: DistributionGridSessionRef? = null,
        failure: DistributionGridFailure
    ): DistributionGridRpcMessage
    {
        return DistributionGridRpcMessage(
            messageType = messageType,
            senderNodeId = resolveCurrentNodeId(),
            targetId = targetId,
            protocolVersion = protocolVersion?.deepCopy()
                ?: p2pDescriptor?.distributionGridMetadata?.supportedProtocolVersions?.firstOrNull()?.deepCopy()
                ?: DistributionGridProtocolVersion(),
            sessionRef = sessionRef?.deepCopy(),
            payloadType = FAILURE_PAYLOAD_TYPE,
            payloadJson = serialize(failure)
        )
    }

    /**
     * Map a remote P2P rejection into a more truthful grid failure instead of flattening everything into transport.
     *
     * @param rejection Remote boundary rejection returned by the peer transport.
     * @return Grid failure describing the remote boundary outcome.
     */
    private fun mapRemoteP2PRejectionToFailure(
        rejection: P2PRejection,
        peerDescriptor: P2PDescriptor
    ): DistributionGridFailure
    {
        val kind = when(rejection.errorType)
        {
            P2PError.transport ->
            {
                DistributionGridFailureKind.TRANSPORT_FAILURE
            }

            P2PError.auth ->
            {
                DistributionGridFailureKind.TRUST_REJECTED
            }

            P2PError.configuration,
            P2PError.context,
            P2PError.content,
            P2PError.prompt ->
            {
                DistributionGridFailureKind.POLICY_REJECTED
            }

            P2PError.json ->
            {
                DistributionGridFailureKind.HANDSHAKE_REJECTED
            }

            P2PError.none ->
            {
                DistributionGridFailureKind.ROUTING_FAILURE
            }
        }

        val retryable = when(rejection.errorType)
        {
            P2PError.transport -> true
            else -> false
        }

        return buildFailure(
            kind = kind,
            reason = rejection.reason.ifBlank { "DistributionGrid remote peer rejected the handoff request." },
            retryable = retryable
        ).copy(
            sourceNodeId = peerDescriptor.distributionGridMetadata?.nodeId?.takeIf { it.isNotBlank() }
                ?: peerDescriptor.agentName.takeIf { it.isNotBlank() }
                ?: peerDescriptor.transport.transportAddress.takeIf { it.isNotBlank() }
                ?: resolveCurrentNodeId(),
            transportMethod = peerDescriptor.transport.transportMethod,
            transportAddress = peerDescriptor.transport.transportAddress
        )
    }

    /**
     * Negotiate the Phase 5 handshake policy surface for an inbound explicit-peer handshake.
     *
     * @param handshakeRequest Remote handshake request.
     * @param requestTransport Transport used to reach the current node.
     * @return Negotiated policy or `null` when no safe overlap exists.
     */
    private fun negotiateHandshakePolicy(
        handshakeRequest: DistributionGridHandshakeRequest,
        requestTransport: P2PTransport
    ): DistributionGridNegotiatedPolicy?
    {
        val localDescriptor = p2pDescriptor ?: return null
        val localMetadata = localDescriptor.distributionGridMetadata ?: return null
        if(handshakeRequest.requesterNodeId.isBlank() && handshakeRequest.requesterMetadata.nodeId.isBlank())
        {
            return null
        }

        if(requestTransport.transportMethod !in localMetadata.supportedTransports)
        {
            return null
        }

        if(handshakeRequest.acceptedTransports.isNotEmpty() &&
            requestTransport.transportMethod !in handshakeRequest.acceptedTransports
        )
        {
            return null
        }

        if(!isDescriptorPrivacyCompatible(localDescriptor, handshakeRequest.tracePolicy))
        {
            return null
        }

        val negotiatedVersion = DistributionGridProtocolVersion.negotiateHighestShared(
            localVersions = localMetadata.supportedProtocolVersions,
            remoteVersions = handshakeRequest.supportedProtocolVersions
        ) ?: return null

        val negotiatedTracePolicy = negotiateTracePolicy(
            localPolicy = localMetadata.defaultTracePolicy,
            requestedPolicy = handshakeRequest.tracePolicy
        )
        val negotiatedRoutingPolicy = negotiateRoutingPolicy(
            localPolicy = localMetadata.defaultRoutingPolicy,
            requestedPolicy = handshakeRequest.acceptedRoutingPolicy
        )
        val negotiatedCredentialPolicy = negotiateCredentialPolicy(handshakeRequest.credentialPolicy)
        val storageClasses = negotiateStorageClasses(
            localPolicy = localMetadata.defaultTracePolicy,
            requestedPolicy = handshakeRequest.tracePolicy
        ) ?: return null

        trace(
            TraceEventType.DISTRIBUTION_GRID_POLICY_EVALUATION,
            TracePhase.VALIDATION,
            metadata = mapOf(
                "requesterNodeId" to handshakeRequest.requesterNodeId.ifBlank {
                    handshakeRequest.requesterMetadata.nodeId
                },
                "protocolVersion" to "${negotiatedVersion.major}.${negotiatedVersion.minor}.${negotiatedVersion.patch}"
            )
        )

        return DistributionGridNegotiatedPolicy(
            protocolVersion = negotiatedVersion,
            tracePolicy = negotiatedTracePolicy,
            credentialPolicy = negotiatedCredentialPolicy,
            routingPolicy = negotiatedRoutingPolicy,
            storageClasses = storageClasses
        )
    }

    /**
     * Enforce descriptor privacy hints as hard compatibility filters when the requester policy requires it.
     *
     * @param descriptor Peer descriptor under consideration.
     * @param tracePolicy Requester trace or privacy policy.
     * @return `true` when the descriptor is compatible with the policy.
     */
    private fun isDescriptorPrivacyCompatible(
        descriptor: P2PDescriptor,
        tracePolicy: DistributionGridTracePolicy
    ): Boolean
    {
        if(!tracePolicy.rejectNonCompliantNodes)
        {
            return true
        }

        val recordsSensitiveData = descriptor.recordsPromptContent || descriptor.recordsInteractionContext
        if(!tracePolicy.allowTracing && recordsSensitiveData)
        {
            return false
        }

        if(tracePolicy.requireRedaction && recordsSensitiveData)
        {
            return false
        }

        if(!tracePolicy.allowTracePersistence && recordsSensitiveData)
        {
            return false
        }

        return true
    }

    /**
     * Negotiate the stricter effective trace policy used for the session.
     *
     * @param localPolicy Current node default trace policy.
     * @param requestedPolicy Requester-supplied trace policy.
     * @return Negotiated trace policy.
     */
    private fun negotiateTracePolicy(
        localPolicy: DistributionGridTracePolicy,
        requestedPolicy: DistributionGridTracePolicy
    ): DistributionGridTracePolicy
    {
        val allowedStorageClasses = when
        {
            localPolicy.allowedStorageClasses.isNotEmpty() && requestedPolicy.allowedStorageClasses.isNotEmpty() ->
            {
                localPolicy.allowedStorageClasses
                    .intersect(requestedPolicy.allowedStorageClasses.toSet())
                    .toMutableList()
            }

            localPolicy.allowedStorageClasses.isNotEmpty() ->
            {
                localPolicy.allowedStorageClasses.deepCopy()
            }

            else ->
            {
                requestedPolicy.allowedStorageClasses.deepCopy()
            }
        }

        return DistributionGridTracePolicy(
            allowTracing = localPolicy.allowTracing && requestedPolicy.allowTracing,
            allowTracePersistence = localPolicy.allowTracePersistence && requestedPolicy.allowTracePersistence,
            requireRedaction = localPolicy.requireRedaction || requestedPolicy.requireRedaction,
            rejectNonCompliantNodes = localPolicy.rejectNonCompliantNodes || requestedPolicy.rejectNonCompliantNodes,
            allowedStorageClasses = allowedStorageClasses,
            disallowedStorageClasses = (
                localPolicy.disallowedStorageClasses + requestedPolicy.disallowedStorageClasses
            ).distinct().toMutableList()
        )
    }

    /**
     * Negotiate the small routing-policy slice Phase 5 actually uses for explicit remote handoff.
     *
     * @param localPolicy Current node default routing policy.
     * @param requestedPolicy Requester-accepted routing policy.
     * @return Negotiated routing policy.
     */
    private fun negotiateRoutingPolicy(
        localPolicy: DistributionGridRoutingPolicy,
        requestedPolicy: DistributionGridRoutingPolicy
    ): DistributionGridRoutingPolicy
    {
        val localHopLimit = localPolicy.maxHopCount.coerceAtLeast(1)
        val requestedHopLimit = requestedPolicy.maxHopCount.coerceAtLeast(1)

        return DistributionGridRoutingPolicy(
            completionReturnMode = requestedPolicy.completionReturnMode,
            failureReturnMode = requestedPolicy.failureReturnMode,
            explicitReturnTransport = requestedPolicy.explicitReturnTransport?.deepCopy(),
            returnAfterFirstLocalWork = requestedPolicy.returnAfterFirstLocalWork,
            allowRetrySamePeer = false,
            maxRetryCount = 0,
            allowAlternatePeers = false,
            maxHopCount = minOf(localHopLimit, requestedHopLimit)
        )
    }

    /**
     * Clamp Phase 5 credential negotiation to conservative no-secret-forwarding behavior.
     *
     * @param requestedPolicy Requester credential policy.
     * @return Negotiated credential policy.
     */
    private fun negotiateCredentialPolicy(
        requestedPolicy: DistributionGridCredentialPolicy
    ): DistributionGridCredentialPolicy
    {
        return DistributionGridCredentialPolicy(
            forwardSecrets = false,
            credentialRefs = requestedPolicy.credentialRefs.deepCopy(),
            perHopCredentialRefs = requestedPolicy.perHopCredentialRefs.deepCopy(),
            allowCredentialTransforms = requestedPolicy.allowCredentialTransforms,
            denySecretPropagation = true
        )
    }

    /**
     * Resolve the negotiated storage-class set when both peers impose restrictions.
     *
     * @param localPolicy Current node trace policy.
     * @param requestedPolicy Requester trace policy.
     * @return Effective allowed storage classes or `null` when no safe overlap exists.
     */
    private fun negotiateStorageClasses(
        localPolicy: DistributionGridTracePolicy,
        requestedPolicy: DistributionGridTracePolicy
    ): MutableList<String>?
    {
        if(localPolicy.allowedStorageClasses.isNotEmpty() && requestedPolicy.allowedStorageClasses.isNotEmpty())
        {
            val intersection = localPolicy.allowedStorageClasses
                .intersect(requestedPolicy.allowedStorageClasses.toSet())
                .toMutableList()

            if(intersection.isEmpty())
            {
                return null
            }

            return intersection
        }

        return when
        {
            localPolicy.allowedStorageClasses.isNotEmpty() -> localPolicy.allowedStorageClasses.deepCopy()
            requestedPolicy.allowedStorageClasses.isNotEmpty() -> requestedPolicy.allowedStorageClasses.deepCopy()
            else -> mutableListOf()
        }
    }

    /**
     * Validate and resolve one inbound session reference supplied by a remote peer.
     *
     * @param sessionRef Session reference carried on the RPC wrapper.
     * @param senderNodeId Sending node id from the RPC wrapper.
     * @return Valid cached session record or `null` when the reference cannot be trusted anymore.
     */
    private fun resolveInboundSession(
        sessionRef: DistributionGridSessionRef,
        rpcMessage: DistributionGridRpcMessage,
        senderNodeId: String
    ): DistributionGridSessionRecord?
    {
        val sessionRecord = sessionRecordsById[sessionRef.sessionId] ?: return null
        if(!isSessionValid(
                sessionRecord = sessionRecord,
                expectedRequesterNodeId = senderNodeId,
                expectedResponderNodeId = resolveCurrentNodeId()
            )
        )
        {
            invalidateSession(sessionRecord, "Inbound session reference failed validation.")
            return null
        }

        if(!isInboundSessionWrapperValid(
                sessionRecord = sessionRecord,
                sessionRef = sessionRef,
                rpcMessage = rpcMessage
            )
        )
        {
            invalidateSession(sessionRecord, "Inbound session wrapper did not match the cached session.")
            return null
        }

        return sessionRecord.deepCopy()
    }

    /**
     * Check whether an inbound RPC wrapper matches the cached session record exactly.
     *
     * @param sessionRecord Session record fetched from the cache.
     * @param sessionRef Session reference carried on the RPC wrapper.
     * @param rpcMessage Full RPC wrapper carrying the task handoff.
     * @return `true` when the wrapper matches the cached session and negotiated protocol exactly.
     */
    private fun isInboundSessionWrapperValid(
        sessionRecord: DistributionGridSessionRecord,
        sessionRef: DistributionGridSessionRef,
        rpcMessage: DistributionGridRpcMessage
    ): Boolean
    {
        return sessionRef == sessionRecord.toSessionRef() &&
            rpcMessage.protocolVersion == sessionRecord.negotiatedProtocolVersion
    }

    /**
     * Check whether a cached session record is still safe to use.
     *
     * @param sessionRecord Session record to validate.
     * @param expectedRequesterNodeId Expected requester node id.
     * @param expectedResponderNodeId Expected responder node id.
     * @return `true` when the session is still valid.
     */
    private fun isSessionValid(
        sessionRecord: DistributionGridSessionRecord,
        expectedRequesterNodeId: String,
        expectedResponderNodeId: String
    ): Boolean
    {
        if(sessionRecord.invalidated)
        {
            return false
        }

        if(sessionRecord.expiresAtEpochMillis <= System.currentTimeMillis())
        {
            return false
        }

        if(sessionRecord.requesterNodeId != expectedRequesterNodeId)
        {
            return false
        }

        if(sessionRecord.responderNodeId != expectedResponderNodeId)
        {
            return false
        }

        val localVersions = p2pDescriptor?.distributionGridMetadata?.supportedProtocolVersions ?: return false
        return localVersions.any { it.major == sessionRecord.negotiatedProtocolVersion.major }
    }

    /**
     * Check whether one cached session still satisfies the policy requested by the current task envelope.
     *
     * @param sessionRecord Cached session record under consideration.
     * @param envelope Current task envelope.
     * @return `true` when the cached negotiated policy is no broader than the envelope request.
     */
    private fun sessionPolicySatisfiesEnvelope(
        sessionRecord: DistributionGridSessionRecord,
        envelope: DistributionGridEnvelope
    ): Boolean
    {
        return negotiatedPolicySatisfiesRequestedPolicies(
            negotiatedPolicy = sessionRecord.negotiatedPolicy,
            requestedTracePolicy = envelope.tracePolicy,
            requestedRoutingPolicy = envelope.routingPolicy,
            requestedCredentialPolicy = envelope.credentialPolicy
        )
    }

    /**
     * Check whether one handshake-negotiated policy stayed within the policy requested by the caller.
     *
     * @param negotiatedPolicy Negotiated policy returned by the remote peer.
     * @param handshakeRequest Original handshake request sent by the caller.
     * @return `true` when the negotiated policy does not widen the request.
     */
    private fun negotiatedPolicySatisfiesHandshakeRequest(
        negotiatedPolicy: DistributionGridNegotiatedPolicy,
        handshakeRequest: DistributionGridHandshakeRequest
    ): Boolean
    {
        return negotiatedPolicySatisfiesRequestedPolicies(
            negotiatedPolicy = negotiatedPolicy,
            requestedTracePolicy = handshakeRequest.tracePolicy,
            requestedRoutingPolicy = handshakeRequest.acceptedRoutingPolicy,
            requestedCredentialPolicy = handshakeRequest.credentialPolicy
        )
    }

    /**
     * Check whether the negotiated session expiry stays within the window requested by the caller.
     *
     * @param sessionRecord Negotiated session record returned by the peer.
     * @param handshakeRequest Original handshake request sent by the caller.
     * @return `true` when the peer's expiry remains within the requested duration window.
     */
    private fun isHandshakeSessionDurationWithinRequestedWindow(
        sessionRecord: DistributionGridSessionRecord,
        handshakeRequest: DistributionGridHandshakeRequest
    ): Boolean
    {
        val requestedSeconds = resolveRequestedSessionSeconds(handshakeRequest.requestedSessionDurationSeconds)
            ?: return false
        val maxExpiresAt = System.currentTimeMillis() + requestedSeconds * 1000L
        return sessionRecord.expiresAtEpochMillis <= maxExpiresAt
    }

    /**
     * Check whether one negotiated policy is no broader than the requested trace, routing, and credential policies.
     *
     * @param negotiatedPolicy Effective negotiated policy to validate.
     * @param requestedTracePolicy Requested trace policy.
     * @param requestedRoutingPolicy Requested routing policy.
     * @param requestedCredentialPolicy Requested credential policy.
     * @return `true` when the negotiated policy stays within the requested limits.
     */
    private fun negotiatedPolicySatisfiesRequestedPolicies(
        negotiatedPolicy: DistributionGridNegotiatedPolicy,
        requestedTracePolicy: DistributionGridTracePolicy,
        requestedRoutingPolicy: DistributionGridRoutingPolicy,
        requestedCredentialPolicy: DistributionGridCredentialPolicy
    ): Boolean
    {
        val tracePolicy = negotiatedPolicy.tracePolicy
        if(tracePolicy.allowTracing && !requestedTracePolicy.allowTracing)
        {
            return false
        }

        if(tracePolicy.allowTracePersistence && !requestedTracePolicy.allowTracePersistence)
        {
            return false
        }

        if(requestedTracePolicy.requireRedaction && !tracePolicy.requireRedaction)
        {
            return false
        }

        if(requestedTracePolicy.rejectNonCompliantNodes && !tracePolicy.rejectNonCompliantNodes)
        {
            return false
        }

        if(requestedTracePolicy.allowedStorageClasses.isNotEmpty() &&
            !negotiatedPolicy.storageClasses.all { it in requestedTracePolicy.allowedStorageClasses }
        )
        {
            return false
        }

        if(!tracePolicy.disallowedStorageClasses.containsAll(requestedTracePolicy.disallowedStorageClasses))
        {
            return false
        }

        val routingPolicy = negotiatedPolicy.routingPolicy
        if(routingPolicy.completionReturnMode != requestedRoutingPolicy.completionReturnMode ||
            routingPolicy.failureReturnMode != requestedRoutingPolicy.failureReturnMode ||
            routingPolicy.explicitReturnTransport != requestedRoutingPolicy.explicitReturnTransport ||
            routingPolicy.returnAfterFirstLocalWork != requestedRoutingPolicy.returnAfterFirstLocalWork
        )
        {
            return false
        }

        if(routingPolicy.allowRetrySamePeer && !requestedRoutingPolicy.allowRetrySamePeer)
        {
            return false
        }

        if(routingPolicy.maxRetryCount > requestedRoutingPolicy.maxRetryCount)
        {
            return false
        }

        if(routingPolicy.allowAlternatePeers && !requestedRoutingPolicy.allowAlternatePeers)
        {
            return false
        }

        if(routingPolicy.maxHopCount > requestedRoutingPolicy.maxHopCount.coerceAtLeast(1))
        {
            return false
        }

        val credentialPolicy = negotiatedPolicy.credentialPolicy
        if(credentialPolicy.forwardSecrets && !requestedCredentialPolicy.forwardSecrets)
        {
            return false
        }

        if(!credentialPolicy.credentialRefs.all { it in requestedCredentialPolicy.credentialRefs })
        {
            return false
        }

        if(!credentialPolicy.perHopCredentialRefs.keys.all { requestedCredentialPolicy.perHopCredentialRefs.containsKey(it) })
        {
            return false
        }

        val hasCredentialValueMismatch = credentialPolicy.perHopCredentialRefs.any { (nodeId, credentialRef) ->
            requestedCredentialPolicy.perHopCredentialRefs[nodeId] != credentialRef
        }
        if(hasCredentialValueMismatch)
        {
            return false
        }

        if(credentialPolicy.allowCredentialTransforms && !requestedCredentialPolicy.allowCredentialTransforms)
        {
            return false
        }

        if(requestedCredentialPolicy.denySecretPropagation && !credentialPolicy.denySecretPropagation)
        {
            return false
        }

        return true
    }

    /**
     * Cache one validated session record for later explicit-peer reuse.
     *
     * @param sessionRecord Session record to store.
     * @param peerKey Optional explicit-peer key for outbound reuse.
     */
    private fun cacheSession(
        sessionRecord: DistributionGridSessionRecord,
        peerKey: String? = null,
        registryScope: DistributionGridRegistryScope? = null
    )
    {
        sessionRecordsById[sessionRecord.sessionId] = sessionRecord.deepCopy()

        if(peerKey != null)
        {
            val resolvedRegistryScope = registryScope ?: resolveRegistryScopeFromToken(sessionRecord.registryId)
            sessionRecordsByPeerKey[buildPeerSessionCacheKey(peerKey, resolvedRegistryScope)] = sessionRecord.deepCopy()
        }
    }

    /**
     * Cache one session using the legacy peer-key-only helper signature.
     *
     * @param sessionRecord Session record to store.
     * @param peerKey Optional explicit-peer key for outbound reuse.
     */
    private fun cacheSession(
        sessionRecord: DistributionGridSessionRecord,
        peerKey: String? = null
    )
    {
        cacheSession(
            sessionRecord = sessionRecord,
            peerKey = peerKey,
            registryScope = null
        )
    }

    /**
     * Invalidate one cached session everywhere it may have been stored.
     *
     * @param sessionRecord Session record to invalidate.
     * @param reason Human-readable invalidation reason.
     */
    private fun invalidateSession(
        sessionRecord: DistributionGridSessionRecord,
        reason: String
    )
    {
        sessionRecord.invalidated = true
        sessionRecord.invalidationReason = reason
        sessionRecordsById.remove(sessionRecord.sessionId)
        val peerKeys = sessionRecordsByPeerKey
            .filterValues { it.sessionId == sessionRecord.sessionId }
            .keys
            .toList()
        peerKeys.forEach { sessionRecordsByPeerKey.remove(it) }
    }

    /**
     * Build the stable cache key used for one explicit-peer session.
     *
     * @param peerKey Canonical explicit-peer key.
     * @param registryScope Canonical registry scope for the session.
     * @return Stable cache key.
     */
    private fun buildPeerSessionCacheKey(
        peerKey: String,
        registryScope: DistributionGridRegistryScope
    ): DistributionGridPeerSessionKey
    {
        return DistributionGridPeerSessionKey(
            peerKey = peerKey,
            registryScope = registryScope
        )
    }

    /**
     * Resolve the canonical registry scope that scopes explicit-peer sessions for the current node.
     *
     * @return Canonical registry scope or an empty scope when the node is not registry-scoped.
     */
    private fun resolveCurrentRegistryScope(): DistributionGridRegistryScope
    {
        val memberships = p2pDescriptor?.distributionGridMetadata?.registryMemberships
            .orEmpty()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        return DistributionGridRegistryScope(memberships = memberships)
    }

    /**
     * Resolve the registry relationship token used on the wire for explicit-peer sessions.
     *
     * @return Canonical registry token or blank when the node is not registry-scoped.
     */
    private fun resolveCurrentRegistryContextId(): String
    {
        return resolveCurrentRegistryScope().toRegistryToken()
    }

    /**
     * Decode one registry token back into the canonical scope structure used for cache identity.
     *
     * @param registryToken Registry token stored on a session record or handshake payload.
     * @return Canonical registry scope.
     */
    private fun resolveRegistryScopeFromToken(registryToken: String): DistributionGridRegistryScope
    {
        if(registryToken.isBlank())
        {
            return DistributionGridRegistryScope(memberships = mutableListOf())
        }

        val structuredMemberships = deserialize<List<String>>(registryToken, useRepair = false)
        if(structuredMemberships != null)
        {
            return DistributionGridRegistryScope(
                memberships = structuredMemberships
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                    .toMutableList()
            )
        }

        if(registryToken.indexOf('%') == -1 && registryToken.indexOf('|') == -1)
        {
            return DistributionGridRegistryScope(memberships = mutableListOf(registryToken))
        }

        val memberships = mutableListOf<String>()
        val buffer = StringBuilder()
        var index = 0
        while(index < registryToken.length)
        {
            val character = registryToken[index]
            if(character == '%' && index + 2 < registryToken.length)
            {
                when(registryToken.substring(index, index + 3))
                {
                    "%25" ->
                    {
                        buffer.append('%')
                        index += 3
                        continue
                    }

                    "%7C" ->
                    {
                        buffer.append('|')
                        index += 3
                        continue
                    }
                }
            }

            if(character == '|')
            {
                memberships.add(buffer.toString())
                buffer.clear()
                index += 1
                continue
            }

            buffer.append(character)
            index += 1
        }

        memberships.add(buffer.toString())
        return DistributionGridRegistryScope(
            memberships = memberships.filter { it.isNotBlank() }.distinct().sorted()
        )
    }

    /**
     * Invalidate and clear all cached sessions.
     *
     * @param reason Invalidation reason to record conceptually for diagnostics.
     */
    private fun invalidateAllSessions(reason: String)
    {
        if(sessionRecordsById.isNotEmpty())
        {
            sessionRecordsById.values.toList().forEach { session ->
                session.invalidated = true
                session.invalidationReason = reason
            }
        }

        sessionRecordsByPeerKey.clear()
        sessionRecordsById.clear()
    }

    /**
     * Resolve the requested session duration without silently widening short requests.
     *
     * @param requestedSeconds Requested session duration in seconds.
     * @return Valid session duration in seconds, or `null` when the request is non-positive.
     */
    private fun resolveRequestedSessionSeconds(requestedSeconds: Int): Int?
    {
        if(requestedSeconds <= 0)
        {
            return null
        }

        return requestedSeconds.coerceAtMost(maxSessionDurationSeconds)
    }

    /**
     * Extract the serialized terminal envelope from returned content, or reconstruct a minimal snapshot if missing.
     *
     * @param content Terminal content returned by local execution.
     * @param fallbackEnvelope Fallback envelope to update when metadata is missing.
     * @return Terminal envelope snapshot.
     */
    private fun extractEnvelopeFromTerminalContent(
        content: MultimodalContent,
        fallbackEnvelope: DistributionGridEnvelope
    ): DistributionGridEnvelope
    {
        val metadataEnvelope = content.metadata[ENVELOPE_METADATA_KEY] as? DistributionGridEnvelope
        if(metadataEnvelope != null)
        {
            return metadataEnvelope.deepCopy()
        }

        return fallbackEnvelope.deepCopy().apply {
            this.content = content.deepCopy()
            this.completed = content.passPipeline || content.terminatePipeline
            this.latestOutcome = content.metadata[OUTCOME_METADATA_KEY] as? DistributionGridOutcome
            this.latestFailure = content.metadata[FAILURE_METADATA_KEY] as? DistributionGridFailure
        }
    }

    /**
     * Merge an outbound handoff record and returned remote envelope into the local finalization envelope.
     *
     * @param envelope Local pre-dispatch envelope.
     * @param peerDescriptor Explicit remote peer descriptor.
     * @param remoteEnvelope Remote terminal envelope returned by the peer.
     * @param startedAt Original execution start timestamp.
     * @param completedAt Completion timestamp for the remote exchange.
     * @return Merged envelope ready for local finalization.
     */
    private fun mergeRemoteSuccess(
        envelope: DistributionGridEnvelope,
        peerDescriptor: P2PDescriptor,
        remoteEnvelope: DistributionGridEnvelope,
        startedAt: Long,
        completedAt: Long,
        resolvedSessionRef: DistributionGridSessionRef? = null
    ): DistributionGridEnvelope
    {
        val remoteNodeId = remoteEnvelope.currentNodeId.ifBlank {
            peerDescriptor.distributionGridMetadata?.nodeId ?: envelope.currentNodeId
        }
        val remoteTransport = remoteEnvelope.currentTransport.deepCopy().takeIf {
            it.transportAddress.isNotBlank() || it.transportAuthBody.isNotBlank()
        } ?: peerDescriptor.transport.deepCopy()

        val mergedEnvelope = envelope.deepCopy().apply {
            content = remoteEnvelope.content.deepCopy()
            completed = remoteEnvelope.completed
            latestOutcome = remoteEnvelope.latestOutcome?.deepCopy()
            latestFailure = remoteEnvelope.latestFailure?.deepCopy()
            sessionRef = resolvedSessionRef?.deepCopy()
                ?: remoteEnvelope.sessionRef?.deepCopy()
                ?: sessionRef
            currentNodeId = remoteNodeId
            currentTransport = remoteTransport.deepCopy()
            attributes.putAll(sanitizeInboundAttributes(remoteEnvelope.attributes))
            executionNotes.addAll(remoteEnvelope.executionNotes.takeLast(memoryPolicy.maxExecutionNotes))
            executionNotes.add("Explicit remote handoff returned from '$remoteNodeId'.")
        }

        mergedEnvelope.hopHistory.add(
            buildRemoteDispatchHopRecord(
                envelope = envelope,
                peerDescriptor = peerDescriptor,
                resultSummary = "Remote handoff completed successfully.",
                startedAt = startedAt,
                completedAt = completedAt
            )
        )
        mergedEnvelope.hopHistory.addAll(remoteEnvelope.hopHistory.takeLast(memoryPolicy.maxHopRecords))

        return mergedEnvelope
    }

    /**
     * Merge an outbound handoff record and remote failure details into the local finalization envelope.
     *
     * @param envelope Local pre-dispatch envelope.
     * @param peerDescriptor Explicit remote peer descriptor.
     * @param failure Failure to attach.
     * @param startedAt Original execution start timestamp.
     * @param completedAt Completion timestamp for the remote exchange.
     * @param resultSummary Human-readable hop result summary.
     * @param remoteEnvelope Optional remote envelope when the peer returned one.
     * @return Merged envelope ready for local failure finalization.
     */
    private fun mergeRemoteFailure(
        envelope: DistributionGridEnvelope,
        peerDescriptor: P2PDescriptor,
        failure: DistributionGridFailure,
        startedAt: Long,
        completedAt: Long,
        resultSummary: String,
        remoteEnvelope: DistributionGridEnvelope? = null,
        resolvedSessionRef: DistributionGridSessionRef? = null
    ): DistributionGridEnvelope
    {
        val remoteNodeId = remoteEnvelope?.currentNodeId?.takeIf { it.isNotBlank() }
            ?: peerDescriptor.distributionGridMetadata?.nodeId
            ?: envelope.currentNodeId
        val remoteTransport = remoteEnvelope?.currentTransport?.deepCopy()?.takeIf {
            it.transportAddress.isNotBlank() || it.transportAuthBody.isNotBlank()
        } ?: peerDescriptor.transport.deepCopy()

        val mergedEnvelope = envelope.deepCopy().apply {
            latestFailure = failure.deepCopy()
            latestOutcome = remoteEnvelope?.latestOutcome?.deepCopy()
            sessionRef = resolvedSessionRef?.deepCopy()
                ?: remoteEnvelope?.sessionRef?.deepCopy()
                ?: sessionRef
            content = remoteEnvelope?.content?.deepCopy() ?: content.deepCopy()
            attributes.putAll(sanitizeInboundAttributes(remoteEnvelope?.attributes.orEmpty()))
            executionNotes.addAll(remoteEnvelope?.executionNotes?.takeLast(memoryPolicy.maxExecutionNotes).orEmpty())
            currentNodeId = remoteNodeId
            currentTransport = remoteTransport.deepCopy()
        }

        mergedEnvelope.executionNotes.add("Explicit remote handoff failed: ${failure.reason}")
        mergedEnvelope.hopHistory.add(
            buildRemoteDispatchHopRecord(
                envelope = envelope,
                peerDescriptor = peerDescriptor,
                resultSummary = resultSummary,
                startedAt = startedAt,
                completedAt = completedAt
            )
        )

        if(remoteEnvelope != null)
        {
            mergedEnvelope.hopHistory.addAll(remoteEnvelope.hopHistory.takeLast(memoryPolicy.maxHopRecords))
        }

        return mergedEnvelope
    }

    /**
     * Build one outbound explicit-peer hop record from the sender node's perspective.
     *
     * @param envelope Current local envelope.
     * @param peerDescriptor Explicit peer descriptor being contacted.
     * @param resultSummary Human-readable hop result summary.
     * @param startedAt Hop start timestamp.
     * @param completedAt Hop completion timestamp.
     * @return Hop record for the remote dispatch.
     */
    private fun buildRemoteDispatchHopRecord(
        envelope: DistributionGridEnvelope,
        peerDescriptor: P2PDescriptor,
        resultSummary: String,
        startedAt: Long,
        completedAt: Long
    ): DistributionGridHopRecord
    {
        return DistributionGridHopRecord(
            sourceNodeId = envelope.currentNodeId,
            destinationNodeId = peerDescriptor.distributionGridMetadata?.nodeId
                ?: peerDescriptor.transport.transportAddress,
            transportMethod = peerDescriptor.transport.transportMethod,
            transportAddress = peerDescriptor.transport.transportAddress,
            routerAction = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
            privacyDecision = if(envelope.tracePolicy.requireRedaction) "redacted" else "standard",
            resultSummary = resultSummary,
            startedAtEpochMillis = startedAt,
            completedAtEpochMillis = completedAt
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
            sourceNodeId = envelope.currentNodeId,
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
        invalidateAllSessions("Grid configuration changed.")
    }

    /**
     * Ensure the grid itself has a safe outward node identity and attached grid metadata.
     */
    private fun ensureGridIdentity()
    {
        val resolvedTransport = p2pTransport ?: defaultGridTransport()
        val resolvedRequirements = p2pRequirements ?: synthesizeRequirements()
        val resolvedGridMetadata = (p2pDescriptor?.distributionGridMetadata ?: synthesizeGridMetadata(resolvedTransport)).copy(
            actsAsRegistry = registryMetadata != null,
            registryMemberships = p2pDescriptor?.distributionGridMetadata?.registryMemberships
                ?: mutableListOf()
        )
        val resolvedDescriptor = (p2pDescriptor ?: synthesizeGridDescriptor(
            transport = resolvedTransport,
            agentName = defaultGridAgentName()
        )).copy(
            transport = resolvedTransport,
            distributionGridMetadata = resolvedGridMetadata
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
            actsAsRegistry = registryMetadata != null
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

    private fun tracePublicListingOperation(
        operation: String,
        listingKind: P2PHostedListingKind,
        result: P2PHostedRegistryMutationResult,
        requestedLeaseSeconds: Int,
        transport: P2PTransport,
        listingId: String = ""
    )
    {
        trace(
            TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING,
            if(result.accepted) TracePhase.CLEANUP else TracePhase.VALIDATION,
            metadata = mapOf(
                "operation" to operation,
                "listingKind" to listingKind.name,
                "listingId" to result.listing?.listingId.orEmpty().ifBlank { listingId },
                "accepted" to result.accepted,
                "requestedLeaseSeconds" to requestedLeaseSeconds,
                "transportAddress" to transport.transportAddress,
                "reason" to result.rejectionReason
            )
        )
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
            "paused" to gridPauseRequested,
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

    /**
     * Build the lightweight session reference reused on later grid RPC messages.
     *
     * @return Compact session reference for the record.
     */
    private fun DistributionGridSessionRecord.toSessionRef(): DistributionGridSessionRef
    {
        return DistributionGridSessionRef(
            sessionId = sessionId,
            requesterNodeId = requesterNodeId,
            responderNodeId = responderNodeId,
            registryId = registryId,
            expiresAtEpochMillis = expiresAtEpochMillis
        )
    }
}
