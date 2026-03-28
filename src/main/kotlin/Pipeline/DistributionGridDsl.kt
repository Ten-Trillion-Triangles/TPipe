package com.TTT.Pipeline

import com.TTT.Debug.TraceConfig
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PSkills
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deepCopy
import kotlinx.coroutines.runBlocking

/**
 * Restricts nested DistributionGrid DSL receivers so role, policy, and hook blocks do not leak into one another.
 */
@DslMarker
annotation class DistributionGridDslMarker

/**
 * Kotlin-first builder for creating an initialized [DistributionGrid].
 *
 * @param block Builder block that configures the node shell, policies, peers, discovery, and hooks.
 * @return Fully initialized grid ready for execution.
 */
fun distributionGrid(block: DistributionGridDsl.() -> Unit): DistributionGrid
{
    val builder = DistributionGridDsl()
    builder.block()
    return builder.build()
}

internal data class DistributionGridGridIdentityConfiguration(
    var descriptor: P2PDescriptor? = null,
    var transport: P2PTransport? = null,
    var requirements: P2PRequirements? = null,
    var containerObject: Any? = null
)

internal data class DistributionGridRoleBindingConfiguration(
    val component: P2PInterface,
    val descriptor: P2PDescriptor? = null,
    val requirements: P2PRequirements? = null
)

internal data class DistributionGridPeerBindingConfiguration(
    val requestedKey: String?,
    val binding: DistributionGridRoleBindingConfiguration
)

internal data class DistributionGridDiscoveryConfiguration(
    var mode: DistributionGridPeerDiscoveryMode = DistributionGridPeerDiscoveryMode.HYBRID,
    var registryMetadata: DistributionGridRegistryMetadata? = null,
    var trustVerifier: DistributionGridTrustVerifier? = null,
    var bootstrapRegistries: MutableList<DistributionGridRegistryAdvertisement> = mutableListOf(),
    var bootstrapCatalogSources: MutableList<DistributionGridBootstrapCatalogSource> = mutableListOf()
)

internal data class DistributionGridTracingConfiguration(
    var enabled: Boolean = true,
    var config: TraceConfig = TraceConfig(enabled = true)
)

internal data class DistributionGridHooksConfiguration(
    var beforeRouteHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null,
    var beforeLocalWorkerHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null,
    var afterLocalWorkerHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null,
    var beforePeerDispatchHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null,
    var afterPeerResponseHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null,
    var outboundMemoryHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null,
    var failureHook: (suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)? = null,
    var outcomeTransformationHook: (suspend (com.TTT.Pipe.MultimodalContent, DistributionGridEnvelope) -> com.TTT.Pipe.MultimodalContent)? = null
)

internal data class DistributionGridOperationsConfiguration(
    var maxHops: Int? = null,
    var rpcTimeoutMillis: Long? = null,
    var maxSessionDurationSeconds: Int? = null
)

/**
 * Root DistributionGrid DSL.
 *
 * This builder mirrors the raw shell API while grouping configuration by concern so one node can be assembled,
 * validated, and initialized in one place.
 */
@DistributionGridDslMarker
class DistributionGridDsl
{
    private val gridIdentity = DistributionGridGridIdentityConfiguration()
    private var p2pBlockSeen = false
    private var securityBlockSeen = false
    private var routerConfiguration: DistributionGridRoleBindingConfiguration? = null
    private var workerConfiguration: DistributionGridRoleBindingConfiguration? = null
    private val localPeerConfigurations = mutableListOf<DistributionGridPeerBindingConfiguration>()
    private val externalPeerDescriptors = mutableListOf<P2PDescriptor>()
    private var discoveryConfiguration: DistributionGridDiscoveryConfiguration? = null
    private var routingConfiguration: DistributionGridRoutingPolicy? = null
    private var memoryConfiguration: DistributionGridMemoryPolicy? = null
    private var durabilityConfiguration: DistributionGridDurableStore? = null
    private var tracingConfiguration: DistributionGridTracingConfiguration? = null
    private var hooksConfiguration: DistributionGridHooksConfiguration? = null
    private var operationsConfiguration: DistributionGridOperationsConfiguration? = null

    /**
     * Configure the outward P2P identity for the grid node itself.
     *
     * @param block Builder block that configures descriptor, transport, requirements, and optional container object.
     */
    fun p2p(block: GridIdentityDsl.() -> Unit)
    {
        require(!p2pBlockSeen) { "DistributionGrid DSL already has a p2p { ... } block." }
        p2pBlockSeen = true
        GridIdentityDsl(gridIdentity).apply(block)
    }

    /**
     * Configure auth- and privacy-relevant outward descriptor or requirement hints for the grid node itself.
     *
     * @param block Builder block that adjusts the grid's outward auth and privacy posture.
     */
    fun security(block: DistributionGridSecurityDsl.() -> Unit)
    {
        require(!securityBlockSeen) { "DistributionGrid DSL already has a security { ... } block." }
        securityBlockSeen = true
        DistributionGridSecurityDsl(gridIdentity).apply(block)
    }

    /**
     * Bind the local router component directly.
     *
     * @param component Router component to bind.
     * @param descriptor Optional explicit descriptor override.
     * @param requirements Optional explicit requirements override.
     */
    fun router(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    )
    {
        require(routerConfiguration == null) { "DistributionGrid DSL already has a router binding." }
        routerConfiguration = DistributionGridRoleBindingConfiguration(component, descriptor, requirements)
    }

    /**
     * Configure the local router binding.
     *
     * @param block Builder block that supplies the router component and optional descriptor or requirements.
     */
    fun router(block: DistributionGridBindingDsl.() -> Unit)
    {
        require(routerConfiguration == null) { "DistributionGrid DSL already has a router binding." }
        routerConfiguration = DistributionGridBindingDsl("router").apply(block).build()
    }

    /**
     * Bind the local worker component directly.
     *
     * @param component Worker component to bind.
     * @param descriptor Optional explicit descriptor override.
     * @param requirements Optional explicit requirements override.
     */
    fun worker(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    )
    {
        require(workerConfiguration == null) { "DistributionGrid DSL already has a worker binding." }
        workerConfiguration = DistributionGridRoleBindingConfiguration(component, descriptor, requirements)
    }

    /**
     * Configure the local worker binding.
     *
     * @param block Builder block that supplies the worker component and optional descriptor or requirements.
     */
    fun worker(block: DistributionGridBindingDsl.() -> Unit)
    {
        require(workerConfiguration == null) { "DistributionGrid DSL already has a worker binding." }
        workerConfiguration = DistributionGridBindingDsl("worker").apply(block).build()
    }

    /**
     * Add a local peer binding without forcing a canonical key.
     *
     * @param component Local peer component to attach.
     * @param descriptor Optional explicit descriptor override.
     * @param requirements Optional explicit requirements override.
     */
    fun peer(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    )
    {
        localPeerConfigurations.add(
            DistributionGridPeerBindingConfiguration(
                requestedKey = null,
                binding = DistributionGridRoleBindingConfiguration(component, descriptor, requirements)
            )
        )
    }

    /**
     * Add a local peer binding with a stable requested key.
     *
     * When the binding does not supply an explicit descriptor, this key becomes the local transport address used to
     * preserve the caller's expected peer identity.
     *
     * @param key Requested canonical peer key or shorthand transport address.
     * @param block Builder block that supplies the peer component and optional descriptor or requirements.
     */
    fun peer(key: String, block: DistributionGridBindingDsl.() -> Unit)
    {
        localPeerConfigurations.add(
            DistributionGridPeerBindingConfiguration(
                requestedKey = key,
                binding = DistributionGridBindingDsl("peer '$key'").apply(block).build()
            )
        )
    }

    /**
     * Register an external peer descriptor directly.
     *
     * @param descriptor External peer descriptor to store.
     */
    fun peerDescriptor(descriptor: P2PDescriptor)
    {
        externalPeerDescriptors.add(descriptor.deepCopy())
    }

    /**
     * Configure one external peer descriptor.
     *
     * @param block Builder block that materializes the external descriptor.
     */
    fun peerDescriptor(block: ExternalPeerDescriptorDsl.() -> Unit)
    {
        externalPeerDescriptors.add(ExternalPeerDescriptorDsl().apply(block).build())
    }

    /**
     * Configure discovery and registry behavior.
     *
     * @param block Builder block for discovery mode, bootstrap registries, registry metadata, and trust verification.
     */
    fun discovery(block: DistributionGridDiscoveryDsl.() -> Unit)
    {
        require(discoveryConfiguration == null) { "DistributionGrid DSL already has a discovery { ... } block." }
        discoveryConfiguration = DistributionGridDiscoveryDsl().apply(block).build()
    }

    /**
     * Configure the routing policy stored on the grid shell.
     *
     * @param block Builder block that mutates the routing policy.
     */
    fun routing(block: DistributionGridRoutingDsl.() -> Unit)
    {
        require(routingConfiguration == null) { "DistributionGrid DSL already has a routing { ... } block." }
        routingConfiguration = DistributionGridRoutingDsl().apply(block).build()
    }

    /**
     * Configure the stored memory policy.
     *
     * @param block Builder block that mutates the memory policy.
     */
    fun memory(block: DistributionGridMemoryDsl.() -> Unit)
    {
        require(memoryConfiguration == null) { "DistributionGrid DSL already has a memory { ... } block." }
        memoryConfiguration = DistributionGridMemoryDsl().apply(block).build()
    }

    /**
     * Configure the durable-store binding.
     *
     * @param block Builder block that supplies the store.
     */
    fun durability(block: DistributionGridDurabilityDsl.() -> Unit)
    {
        require(durabilityConfiguration == null) { "DistributionGrid DSL already has a durability { ... } block." }
        durabilityConfiguration = DistributionGridDurabilityDsl().apply(block).build()
    }

    /**
     * Configure tracing for the grid shell.
     *
     * @param block Builder block that enables, disables, or overrides the trace config.
     */
    fun tracing(block: DistributionGridTracingDsl.() -> Unit)
    {
        require(tracingConfiguration == null) { "DistributionGrid DSL already has a tracing { ... } block." }
        tracingConfiguration = DistributionGridTracingDsl().apply(block).build()
    }

    /**
     * Configure orchestration hooks on the grid shell.
     *
     * @param block Builder block that attaches the desired hooks.
     */
    fun hooks(block: DistributionGridHooksDsl.() -> Unit)
    {
        require(hooksConfiguration == null) { "DistributionGrid DSL already has a hooks { ... } block." }
        hooksConfiguration = DistributionGridHooksDsl().apply(block).build()
    }

    /**
     * Configure low-level operational knobs that already exist on the raw shell.
     *
     * @param block Builder block that stores max hops, RPC timeout, or session-duration caps.
     */
    fun operations(block: DistributionGridOperationsDsl.() -> Unit)
    {
        require(operationsConfiguration == null) { "DistributionGrid DSL already has an operations { ... } block." }
        operationsConfiguration = DistributionGridOperationsDsl().apply(block).build()
    }

    /**
     * Build and initialize the configured grid synchronously.
     *
     * @return Fully initialized grid ready for use.
     */
    fun build(): DistributionGrid
    {
        val grid = configureGrid()
        runBlocking {
            grid.init()
        }
        return grid
    }

    /**
     * Build and initialize the configured grid asynchronously.
     *
     * @return Fully initialized grid ready for use.
     */
    suspend fun buildSuspend(): DistributionGrid
    {
        val grid = configureGrid()
        grid.init()
        return grid
    }

    private fun configureGrid(): DistributionGrid
    {
        val router = routerConfiguration
            ?: throw IllegalArgumentException("DistributionGrid DSL requires a router before build().")
        val worker = workerConfiguration
            ?: throw IllegalArgumentException("DistributionGrid DSL requires a worker before build().")

        val grid = DistributionGrid()
        applyGridIdentity(grid)
        grid.setRouter(router.component, router.descriptor?.deepCopy(), router.requirements?.copy())
        grid.setWorker(worker.component, worker.descriptor?.deepCopy(), worker.requirements?.copy())

        localPeerConfigurations.forEach { peer ->
            val requestedKey = peer.requestedKey
            val descriptor = resolvePeerDescriptor(requestedKey, peer.binding.descriptor)
            grid.addPeer(peer.binding.component, descriptor, peer.binding.requirements?.copy())
        }

        externalPeerDescriptors.forEach { descriptor ->
            grid.addPeerDescriptor(descriptor.deepCopy())
        }

        discoveryConfiguration?.let { discovery ->
            grid.setDiscoveryMode(discovery.mode)
            discovery.registryMetadata?.let { grid.setRegistryMetadata(it.deepCopy()) }
            discovery.trustVerifier?.let { grid.setTrustVerifier(it) }
            discovery.bootstrapRegistries.forEach { grid.addBootstrapRegistry(it.deepCopy()) }
            discovery.bootstrapCatalogSources.forEach { grid.addBootstrapCatalogSource(it.deepCopy()) }
        }

        routingConfiguration?.let { grid.setRoutingPolicy(it.deepCopy()) }
        memoryConfiguration?.let { grid.setMemoryPolicy(it.copy()) }
        if(durabilityConfiguration != null)
        {
            grid.setDurableStore(durabilityConfiguration)
        }

        tracingConfiguration?.let { tracing ->
            if(tracing.enabled)
            {
                grid.enableTracing(tracing.config)
            }
            else
            {
                grid.disableTracing()
            }
        }

        hooksConfiguration?.let { hooks ->
            grid.setBeforeRouteHook(hooks.beforeRouteHook)
            grid.setBeforeLocalWorkerHook(hooks.beforeLocalWorkerHook)
            grid.setAfterLocalWorkerHook(hooks.afterLocalWorkerHook)
            grid.setBeforePeerDispatchHook(hooks.beforePeerDispatchHook)
            grid.setAfterPeerResponseHook(hooks.afterPeerResponseHook)
            grid.setOutboundMemoryHook(hooks.outboundMemoryHook)
            grid.setFailureHook(hooks.failureHook)
            grid.setOutcomeTransformationHook(hooks.outcomeTransformationHook)
        }

        operationsConfiguration?.let { operations ->
            operations.maxHops?.let { grid.setMaxHops(it) }
            operations.rpcTimeoutMillis?.let { grid.setRpcTimeout(it) }
            operations.maxSessionDurationSeconds?.let { grid.setMaxSessionDuration(it) }
        }

        return grid
    }

    private fun applyGridIdentity(grid: DistributionGrid)
    {
        gridIdentity.transport?.let { grid.setP2pTransport(it.deepCopy()) }
        gridIdentity.descriptor?.let { descriptor ->
            val transport = gridIdentity.transport ?: descriptor.transport
            grid.setP2pDescription(
                descriptor.deepCopy().apply {
                    this.transport = transport.deepCopy()
                }
            )
        }
        gridIdentity.requirements?.let { grid.setP2pRequirements(it.copy()) }
        gridIdentity.containerObject?.let { grid.setContainerObject(it) }
    }

    private fun resolvePeerDescriptor(
        requestedKey: String?,
        explicitDescriptor: P2PDescriptor?
    ): P2PDescriptor?
    {
        if(requestedKey.isNullOrBlank())
        {
            return explicitDescriptor?.deepCopy()
        }

        val canonicalTransport = if(requestedKey.contains("::"))
        {
            val parts = requestedKey.split("::", limit = 2)
            val method = Transport.entries.firstOrNull { it.toString() == parts[0] } ?: Transport.Tpipe
            P2PTransport(
                transportMethod = method,
                transportAddress = parts[1]
            )
        }
        else
        {
            P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = requestedKey
            )
        }

        val descriptor = explicitDescriptor?.deepCopy() ?: defaultDescriptor().apply {
            agentName = canonicalTransport.transportAddress.ifBlank { requestedKey }
            agentDescription = "Local peer binding declared through the DistributionGrid DSL"
        }

        descriptor.transport = canonicalTransport
        if(descriptor.agentName.isBlank())
        {
            descriptor.agentName = canonicalTransport.transportAddress.ifBlank { requestedKey }
        }
        return descriptor
    }

    private fun defaultDescriptor(): P2PDescriptor
    {
        return P2PDescriptor(
            agentName = "",
            agentDescription = "",
            transport = P2PTransport(),
            requiresAuth = false,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none,
            supportedContentTypes = mutableListOf(SupportedContentTypes.text)
        )
    }

}

/**
 * Builder for the grid node's outward P2P identity.
 */
@DistributionGridDslMarker
class GridIdentityDsl internal constructor(
    private val configuration: DistributionGridGridIdentityConfiguration
)
{
    fun descriptor(descriptor: P2PDescriptor)
    {
        configuration.descriptor = descriptor.deepCopy()
        if(configuration.transport == null)
        {
            configuration.transport = descriptor.transport.deepCopy()
        }
    }

    fun transport(transport: P2PTransport)
    {
        configuration.transport = transport.deepCopy()
        if(configuration.descriptor != null)
        {
            configuration.descriptor = configuration.descriptor!!.deepCopy().apply {
                this.transport = transport.deepCopy()
            }
        }
    }

    fun requirements(requirements: P2PRequirements)
    {
        configuration.requirements = requirements.copy()
    }

    fun containerObject(container: Any)
    {
        configuration.containerObject = container
    }

    fun agentName(name: String)
    {
        ensureDescriptor().agentName = name
    }

    fun description(description: String)
    {
        ensureDescriptor().agentDescription = description
    }

    fun transportMethod(method: Transport)
    {
        ensureTransport().transportMethod = method
        ensureDescriptor().transport = configuration.transport!!.deepCopy()
    }

    fun transportAddress(address: String)
    {
        ensureTransport().transportAddress = address
        ensureDescriptor().transport = configuration.transport!!.deepCopy()
    }

    fun usesConverse(enabled: Boolean)
    {
        ensureDescriptor().usesConverse = enabled
    }

    fun supportedContent(vararg contentTypes: SupportedContentTypes)
    {
        ensureDescriptor().supportedContentTypes = contentTypes.toMutableList()
    }

    fun requestTemplate(template: P2PRequest)
    {
        ensureDescriptor().requestTemplate = template.deepCopy()
    }

    fun skill(name: String, description: String)
    {
        val descriptor = ensureDescriptor()
        if(descriptor.agentSkills == null)
        {
            descriptor.agentSkills = mutableListOf()
        }
        descriptor.agentSkills!!.add(P2PSkills(name, description))
    }

    fun distributionGridMetadata(metadata: DistributionGridNodeMetadata)
    {
        ensureDescriptor().distributionGridMetadata = metadata.deepCopy()
    }

    fun allowExternalConnections(enabled: Boolean)
    {
        ensureRequirements().allowExternalConnections = enabled
    }

    fun acceptedContent(vararg contentTypes: SupportedContentTypes)
    {
        ensureRequirements().acceptedContent = contentTypes.toMutableList()
    }

    fun maxTokens(max: Int)
    {
        ensureRequirements().maxTokens = max
    }

    fun maxBinarySize(max: Int)
    {
        ensureRequirements().maxBinarySize = max
    }

    fun authMechanism(authMechanism: suspend (String) -> Boolean)
    {
        ensureRequirements().authMechanism = authMechanism
    }

    private fun ensureDescriptor(): P2PDescriptor
    {
        if(configuration.descriptor == null)
        {
            configuration.descriptor = P2PDescriptor(
                agentName = "distribution-grid-node",
                agentDescription = "DistributionGrid node shell",
                transport = configuration.transport?.deepCopy() ?: P2PTransport(),
                requiresAuth = false,
                usesConverse = false,
                allowsAgentDuplication = false,
                allowsCustomContext = false,
                allowsCustomAgentJson = false,
                recordsInteractionContext = false,
                recordsPromptContent = false,
                allowsExternalContext = false,
                contextProtocol = ContextProtocol.none,
                supportedContentTypes = mutableListOf(SupportedContentTypes.text)
            )
        }
        return configuration.descriptor!!
    }

    private fun ensureTransport(): P2PTransport
    {
        if(configuration.transport == null)
        {
            configuration.transport = configuration.descriptor?.transport?.deepCopy() ?: P2PTransport()
        }
        return configuration.transport!!
    }

    private fun ensureRequirements(): P2PRequirements
    {
        if(configuration.requirements == null)
        {
            configuration.requirements = P2PRequirements()
        }
        return configuration.requirements!!
    }
}

/**
 * Builder for grid-level auth and privacy hints.
 */
@DistributionGridDslMarker
class DistributionGridSecurityDsl internal constructor(
    private val configuration: DistributionGridGridIdentityConfiguration
)
{
    fun requiresAuth(enabled: Boolean)
    {
        ensureDescriptor().requiresAuth = enabled
    }

    fun recordsInteractionContext(enabled: Boolean)
    {
        ensureDescriptor().recordsInteractionContext = enabled
    }

    fun recordsPromptContent(enabled: Boolean)
    {
        ensureDescriptor().recordsPromptContent = enabled
    }

    fun allowsExternalContext(enabled: Boolean)
    {
        ensureDescriptor().allowsExternalContext = enabled
    }

    fun authMechanism(authMechanism: suspend (String) -> Boolean)
    {
        ensureRequirements().authMechanism = authMechanism
    }

    fun allowExternalConnections(enabled: Boolean)
    {
        ensureRequirements().allowExternalConnections = enabled
    }

    private fun ensureDescriptor(): P2PDescriptor
    {
        if(configuration.descriptor == null)
        {
            GridIdentityDsl(configuration).agentName("distribution-grid-node")
        }
        return configuration.descriptor!!
    }

    private fun ensureRequirements(): P2PRequirements
    {
        return configuration.requirements ?: P2PRequirements().also {
            configuration.requirements = it
        }
    }
}

/**
 * Builder for router, worker, or local peer bindings.
 */
@DistributionGridDslMarker
class DistributionGridBindingDsl internal constructor(
    private val label: String
)
{
    private var component: P2PInterface? = null
    private var descriptor: P2PDescriptor? = null
    private var requirements: P2PRequirements? = null

    fun component(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    )
    {
        this.component = component
        this.descriptor = descriptor?.deepCopy()
        this.requirements = requirements?.copy()
    }

    fun descriptor(descriptor: P2PDescriptor)
    {
        this.descriptor = descriptor.deepCopy()
    }

    fun requirements(requirements: P2PRequirements)
    {
        this.requirements = requirements.copy()
    }

    internal fun build(): DistributionGridRoleBindingConfiguration
    {
        return DistributionGridRoleBindingConfiguration(
            component = component ?: throw IllegalArgumentException("DistributionGrid DSL $label block requires component(...)."),
            descriptor = descriptor,
            requirements = requirements
        )
    }
}

/**
 * Builder for one external peer descriptor.
 */
@DistributionGridDslMarker
class ExternalPeerDescriptorDsl
{
    private var descriptor: P2PDescriptor? = null

    fun descriptor(descriptor: P2PDescriptor)
    {
        this.descriptor = descriptor.deepCopy()
    }

    internal fun build(): P2PDescriptor
    {
        return descriptor ?: throw IllegalArgumentException("DistributionGrid DSL peerDescriptor { ... } requires descriptor(...).")
    }
}

/**
 * Builder for discovery and registry configuration.
 */
@DistributionGridDslMarker
class DistributionGridDiscoveryDsl
{
    private val configuration = DistributionGridDiscoveryConfiguration()

    fun mode(mode: DistributionGridPeerDiscoveryMode)
    {
        configuration.mode = mode
    }

    fun registryMetadata(metadata: DistributionGridRegistryMetadata)
    {
        configuration.registryMetadata = metadata.deepCopy()
    }

    fun trustVerifier(verifier: DistributionGridTrustVerifier)
    {
        configuration.trustVerifier = verifier
    }

    fun bootstrapRegistry(advertisement: DistributionGridRegistryAdvertisement)
    {
        configuration.bootstrapRegistries.add(advertisement.deepCopy())
    }

    fun bootstrapCatalogSource(source: DistributionGridBootstrapCatalogSource)
    {
        configuration.bootstrapCatalogSources.add(source.deepCopy())
    }

    internal fun build(): DistributionGridDiscoveryConfiguration
    {
        return configuration.copy(
            registryMetadata = configuration.registryMetadata?.deepCopy(),
            bootstrapRegistries = configuration.bootstrapRegistries.map { it.deepCopy() }.toMutableList(),
            bootstrapCatalogSources = configuration.bootstrapCatalogSources.map { it.deepCopy() }.toMutableList()
        )
    }
}

/**
 * Builder for stored routing policy.
 */
@DistributionGridDslMarker
class DistributionGridRoutingDsl
{
    private var policy = DistributionGridRoutingPolicy()

    fun policy(policy: DistributionGridRoutingPolicy)
    {
        this.policy = policy.deepCopy()
    }

    fun completionReturnMode(mode: DistributionGridReturnMode)
    {
        policy.completionReturnMode = mode
    }

    fun failureReturnMode(mode: DistributionGridReturnMode)
    {
        policy.failureReturnMode = mode
    }

    fun explicitReturnTransport(transport: P2PTransport?)
    {
        policy.explicitReturnTransport = transport?.deepCopy()
    }

    fun returnAfterFirstLocalWork(enabled: Boolean)
    {
        policy.returnAfterFirstLocalWork = enabled
    }

    fun allowRetrySamePeer(enabled: Boolean)
    {
        policy.allowRetrySamePeer = enabled
    }

    fun maxRetryCount(count: Int)
    {
        policy.maxRetryCount = count
    }

    fun allowAlternatePeers(enabled: Boolean)
    {
        policy.allowAlternatePeers = enabled
    }

    fun maxHopCount(count: Int)
    {
        policy.maxHopCount = count
    }

    internal fun build(): DistributionGridRoutingPolicy = policy.deepCopy()
}

/**
 * Builder for stored memory policy.
 */
@DistributionGridDslMarker
class DistributionGridMemoryDsl
{
    private var policy = DistributionGridMemoryPolicy()

    fun policy(policy: DistributionGridMemoryPolicy)
    {
        this.policy = policy.copy()
    }

    fun outboundTokenBudget(tokens: Int)
    {
        policy.outboundTokenBudget = tokens
    }

    fun safetyReserveTokens(tokens: Int)
    {
        policy.safetyReserveTokens = tokens
    }

    fun minimumCriticalBudget(tokens: Int)
    {
        policy.minimumCriticalBudget = tokens
    }

    fun minimumRecentBudget(tokens: Int)
    {
        policy.minimumRecentBudget = tokens
    }

    fun maxExecutionNotes(count: Int)
    {
        policy.maxExecutionNotes = count
    }

    fun maxHopRecords(count: Int)
    {
        policy.maxHopRecords = count
    }

    fun enableSummarization(enabled: Boolean)
    {
        policy.enableSummarization = enabled
    }

    fun summaryBudget(tokens: Int)
    {
        policy.summaryBudget = tokens
    }

    fun maxSummaryCharacters(characters: Int)
    {
        policy.maxSummaryCharacters = characters
    }

    fun redactContentSections(enabled: Boolean)
    {
        policy.redactContentSections = enabled
    }

    fun redactTraceMetadata(enabled: Boolean)
    {
        policy.redactTraceMetadata = enabled
    }

    fun summarizer(summarizer: (String) -> String)
    {
        policy.summarizer = summarizer
    }

    internal fun build(): DistributionGridMemoryPolicy = policy.copy()
}

/**
 * Builder for durable-store wiring.
 */
@DistributionGridDslMarker
class DistributionGridDurabilityDsl
{
    private var store: DistributionGridDurableStore? = null

    fun store(store: DistributionGridDurableStore?)
    {
        this.store = store
    }

    internal fun build(): DistributionGridDurableStore?
    {
        return store
    }
}

/**
 * Builder for shell tracing configuration.
 */
@DistributionGridDslMarker
class DistributionGridTracingDsl
{
    private var enabled = true
    private var config = TraceConfig(enabled = true)

    fun enabled(config: TraceConfig = TraceConfig(enabled = true))
    {
        enabled = true
        this.config = config
    }

    fun disabled()
    {
        enabled = false
        config = TraceConfig(enabled = false)
    }

    fun config(config: TraceConfig)
    {
        enabled = config.enabled
        this.config = config
    }

    internal fun build(): DistributionGridTracingConfiguration
    {
        return DistributionGridTracingConfiguration(enabled = enabled, config = config)
    }
}

/**
 * Builder for orchestration hooks.
 */
@DistributionGridDslMarker
class DistributionGridHooksDsl
{
    private val configuration = DistributionGridHooksConfiguration()

    fun beforeRoute(hook: suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)
    {
        configuration.beforeRouteHook = hook
    }

    fun beforeLocalWorker(hook: suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)
    {
        configuration.beforeLocalWorkerHook = hook
    }

    fun afterLocalWorker(hook: suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)
    {
        configuration.afterLocalWorkerHook = hook
    }

    fun beforePeerDispatch(hook: suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)
    {
        configuration.beforePeerDispatchHook = hook
    }

    fun afterPeerResponse(hook: suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)
    {
        configuration.afterPeerResponseHook = hook
    }

    fun outboundMemory(hook: suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)
    {
        configuration.outboundMemoryHook = hook
    }

    fun failure(hook: suspend (DistributionGridEnvelope) -> DistributionGridEnvelope)
    {
        configuration.failureHook = hook
    }

    fun outcomeTransformation(hook: suspend (com.TTT.Pipe.MultimodalContent, DistributionGridEnvelope) -> com.TTT.Pipe.MultimodalContent)
    {
        configuration.outcomeTransformationHook = hook
    }

    internal fun build(): DistributionGridHooksConfiguration = configuration
}

/**
 * Builder for low-level operational shell knobs.
 */
@DistributionGridDslMarker
class DistributionGridOperationsDsl
{
    private val configuration = DistributionGridOperationsConfiguration()

    fun maxHops(max: Int)
    {
        configuration.maxHops = max
    }

    fun rpcTimeoutMillis(millis: Long)
    {
        configuration.rpcTimeoutMillis = millis
    }

    fun maxSessionDurationSeconds(seconds: Int)
    {
        configuration.maxSessionDurationSeconds = seconds
    }

    internal fun build(): DistributionGridOperationsConfiguration = configuration
}
