package com.TTT.Pipeline

import com.TTT.Debug.TraceConfig
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.KillSwitchContext
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
 * Sealed class representing the state machine stages for type-safe DistributionGrid DSL building.
 *
 * - [Initial]   : Nothing configured yet
 * - [HasRouter] : router { } has been called
 * - [Ready]     : router { } and worker { } have been called — all required configuration is complete
 */
sealed class GridStage
{
    object Initial   : GridStage()
    object HasRouter : GridStage()
    object Ready     : GridStage()
}

/**
 * Root DistributionGrid DSL builder. Generic over the current configuration stage to enforce compile-time safety.
 *
 * @param S Current stage in the builder state machine.
 */
@DistributionGridDslMarker
class DistributionGridBuilder<S : GridStage> @PublishedApi internal constructor(
    private val gridIdentity: DistributionGridGridIdentityConfiguration = DistributionGridGridIdentityConfiguration(),
    private var p2pBlockSeen: Boolean = false,
    private var securityBlockSeen: Boolean = false,
    private var routerConfiguration: DistributionGridRoleBindingConfiguration? = null,
    private var workerConfiguration: DistributionGridRoleBindingConfiguration? = null,
    private val localPeerConfigurations: MutableList<DistributionGridPeerBindingConfiguration> = mutableListOf(),
    private val externalPeerDescriptors: MutableList<P2PDescriptor> = mutableListOf(),
    private var discoveryConfiguration: DistributionGridDiscoveryConfiguration? = null,
    private var routingConfiguration: DistributionGridRoutingPolicy? = null,
    private var memoryConfiguration: DistributionGridMemoryPolicy? = null,
    private var durabilityConfiguration: DistributionGridDurableStore? = null,
    private var tracingConfiguration: DistributionGridTracingConfiguration? = null,
    private var hooksConfiguration: DistributionGridHooksConfiguration? = null,
    private var operationsConfiguration: DistributionGridOperationsConfiguration? = null,
    private var killSwitchConfiguration: KillSwitch? = null
)
{
    /**
     * Factory for creating new builder instances with different type parameters while preserving all configuration.
     * Mutable list references are shared so that mutations made before the transition remain visible.
     */
    private fun <T : GridStage> createNew(): DistributionGridBuilder<T> = DistributionGridBuilder(
        gridIdentity = gridIdentity,
        p2pBlockSeen = p2pBlockSeen,
        securityBlockSeen = securityBlockSeen,
        routerConfiguration = routerConfiguration,
        workerConfiguration = workerConfiguration,
        localPeerConfigurations = localPeerConfigurations,
        externalPeerDescriptors = externalPeerDescriptors,
        discoveryConfiguration = discoveryConfiguration,
        routingConfiguration = routingConfiguration,
        memoryConfiguration = memoryConfiguration,
        durabilityConfiguration = durabilityConfiguration,
        tracingConfiguration = tracingConfiguration,
        hooksConfiguration = hooksConfiguration,
        operationsConfiguration = operationsConfiguration,
        killSwitchConfiguration = killSwitchConfiguration
    )

    //=========================================Router===================================================================

    /**
     * Bind the local router component directly.
     *
     * This is the first required step. After calling this method the builder advances to [GridStage.HasRouter] so
     * that [worker] becomes available.
     *
     * @param component Router component to bind.
     * @param descriptor Optional explicit descriptor override.
     * @param requirements Optional explicit requirements override.
     * @return A new builder in the [GridStage.HasRouter] stage.
     */
    fun router(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    ): DistributionGridBuilder<GridStage.HasRouter>
    {
        require(routerConfiguration == null) { "DistributionGrid DSL already has a router binding." }
        routerConfiguration = DistributionGridRoleBindingConfiguration(component, descriptor, requirements)
        return createNew()
    }

    /**
     * Configure the local router binding.
     *
     * This is the first required step. After calling this method the builder advances to [GridStage.HasRouter] so
     * that [worker] becomes available.
     *
     * @param block Builder block that supplies the router component and optional descriptor or requirements.
     * @return A new builder in the [GridStage.HasRouter] stage.
     */
    fun router(block: DistributionGridBindingDsl.() -> Unit): DistributionGridBuilder<GridStage.HasRouter>
    {
        require(routerConfiguration == null) { "DistributionGrid DSL already has a router binding." }
        routerConfiguration = DistributionGridBindingDsl("router").apply(block).build()
        return createNew()
    }

    //=========================================Worker===================================================================

    /**
     * Bind the local worker component directly.
     *
     * This is the second required step. After calling this method the builder advances to [GridStage.Ready] so
     * that [build] becomes available.
     *
     * @param component Worker component to bind.
     * @param descriptor Optional explicit descriptor override.
     * @param requirements Optional explicit requirements override.
     * @return A new builder in the [GridStage.Ready] stage.
     */
    fun worker(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    ): DistributionGridBuilder<GridStage.Ready>
    {
        require(workerConfiguration == null) { "DistributionGrid DSL already has a worker binding." }
        workerConfiguration = DistributionGridRoleBindingConfiguration(component, descriptor, requirements)
        return createNew()
    }

    /**
     * Configure the local worker binding.
     *
     * This is the second required step. After calling this method the builder advances to [GridStage.Ready] so
     * that [build] becomes available.
     *
     * @param block Builder block that supplies the worker component and optional descriptor or requirements.
     * @return A new builder in the [GridStage.Ready] stage.
     */
    fun worker(block: DistributionGridBindingDsl.() -> Unit): DistributionGridBuilder<GridStage.Ready>
    {
        require(workerConfiguration == null) { "DistributionGrid DSL already has a worker binding." }
        workerConfiguration = DistributionGridBindingDsl("worker").apply(block).build()
        return createNew()
    }

    //=========================================Optional config==========================================================

    /**
     * Configure the outward P2P identity for the grid node itself.
     *
     * @param block Builder block that configures descriptor, transport, requirements, and optional container object.
     * @return This builder for chaining.
     */
    fun p2p(block: GridIdentityDsl.() -> Unit): DistributionGridBuilder<S>
    {
        require(!p2pBlockSeen) { "DistributionGrid DSL already has a p2p { ... } block." }
        p2pBlockSeen = true
        GridIdentityDsl(gridIdentity).apply(block)
        return this
    }

    /**
     * Configure auth- and privacy-relevant outward descriptor or requirement hints for the grid node itself.
     *
     * @param block Builder block that adjusts the grid's outward auth and privacy posture.
     * @return This builder for chaining.
     */
    fun security(block: DistributionGridSecurityDsl.() -> Unit): DistributionGridBuilder<S>
    {
        require(!securityBlockSeen) { "DistributionGrid DSL already has a security { ... } block." }
        securityBlockSeen = true
        DistributionGridSecurityDsl(gridIdentity).apply(block)
        return this
    }

    /**
     * Add a local peer binding without forcing a canonical key.
     *
     * @param component Local peer component to attach.
     * @param descriptor Optional explicit descriptor override.
     * @param requirements Optional explicit requirements override.
     * @return This builder for chaining.
     */
    fun peer(
        component: P2PInterface,
        descriptor: P2PDescriptor? = null,
        requirements: P2PRequirements? = null
    ): DistributionGridBuilder<S>
    {
        localPeerConfigurations.add(
            DistributionGridPeerBindingConfiguration(
                requestedKey = null,
                binding = DistributionGridRoleBindingConfiguration(component, descriptor, requirements)
            )
        )
        return this
    }

    /**
     * Add a local peer binding with a stable requested key.
     *
     * When the binding does not supply an explicit descriptor, this key becomes the local transport address used to
     * preserve the caller's expected peer identity.
     *
     * @param key Requested canonical peer key or shorthand transport address.
     * @param block Builder block that supplies the peer component and optional descriptor or requirements.
     * @return This builder for chaining.
     */
    fun peer(key: String, block: DistributionGridBindingDsl.() -> Unit): DistributionGridBuilder<S>
    {
        localPeerConfigurations.add(
            DistributionGridPeerBindingConfiguration(
                requestedKey = key,
                binding = DistributionGridBindingDsl("peer '$key'").apply(block).build()
            )
        )
        return this
    }

    /**
     * Register an external peer descriptor directly.
     *
     * @param descriptor External peer descriptor to store.
     * @return This builder for chaining.
     */
    fun peerDescriptor(descriptor: P2PDescriptor): DistributionGridBuilder<S>
    {
        externalPeerDescriptors.add(descriptor.deepCopy())
        return this
    }

    /**
     * Configure one external peer descriptor.
     *
     * @param block Builder block that materializes the external descriptor.
     * @return This builder for chaining.
     */
    fun peerDescriptor(block: ExternalPeerDescriptorDsl.() -> Unit): DistributionGridBuilder<S>
    {
        externalPeerDescriptors.add(ExternalPeerDescriptorDsl().apply(block).build())
        return this
    }

    /**
     * Configure discovery and registry behavior.
     *
     * @param block Builder block for discovery mode, bootstrap registries, registry metadata, and trust verification.
     * @return This builder for chaining.
     */
    fun discovery(block: DistributionGridDiscoveryDsl.() -> Unit): DistributionGridBuilder<S>
    {
        require(discoveryConfiguration == null) { "DistributionGrid DSL already has a discovery { ... } block." }
        discoveryConfiguration = DistributionGridDiscoveryDsl().apply(block).build()
        return this
    }

    /**
     * Configure the routing policy stored on the grid shell.
     *
     * @param block Builder block that mutates the routing policy.
     * @return This builder for chaining.
     */
    fun routing(block: DistributionGridRoutingDsl.() -> Unit): DistributionGridBuilder<S>
    {
        require(routingConfiguration == null) { "DistributionGrid DSL already has a routing { ... } block." }
        routingConfiguration = DistributionGridRoutingDsl().apply(block).build()
        return this
    }

    /**
     * Configure the stored memory policy.
     *
     * @param block Builder block that mutates the memory policy.
     * @return This builder for chaining.
     */
    fun memory(block: DistributionGridMemoryDsl.() -> Unit): DistributionGridBuilder<S>
    {
        require(memoryConfiguration == null) { "DistributionGrid DSL already has a memory { ... } block." }
        memoryConfiguration = DistributionGridMemoryDsl().apply(block).build()
        return this
    }

    /**
     * Configure the durable-store binding.
     *
     * @param block Builder block that supplies the store.
     * @return This builder for chaining.
     */
    fun durability(block: DistributionGridDurabilityDsl.() -> Unit): DistributionGridBuilder<S>
    {
        require(durabilityConfiguration == null) { "DistributionGrid DSL already has a durability { ... } block." }
        durabilityConfiguration = DistributionGridDurabilityDsl().apply(block).build()
        return this
    }

    /**
     * Configure tracing for the grid shell.
     *
     * @param block Builder block that enables, disables, or overrides the trace config.
     * @return This builder for chaining.
     */
    fun tracing(block: DistributionGridTracingDsl.() -> Unit): DistributionGridBuilder<S>
    {
        require(tracingConfiguration == null) { "DistributionGrid DSL already has a tracing { ... } block." }
        tracingConfiguration = DistributionGridTracingDsl().apply(block).build()
        return this
    }

    /**
     * Configure orchestration hooks on the grid shell.
     *
     * @param block Builder block that attaches the desired hooks.
     * @return This builder for chaining.
     */
    fun hooks(block: DistributionGridHooksDsl.() -> Unit): DistributionGridBuilder<S>
    {
        require(hooksConfiguration == null) { "DistributionGrid DSL already has a hooks { ... } block." }
        hooksConfiguration = DistributionGridHooksDsl().apply(block).build()
        return this
    }

    /**
     * Configure low-level operational knobs that already exist on the raw shell.
     *
     * @param block Builder block that stores max hops, RPC timeout, or session-duration caps.
     * @return This builder for chaining.
     */
    fun operations(block: DistributionGridOperationsDsl.() -> Unit): DistributionGridBuilder<S>
    {
        require(operationsConfiguration == null) { "DistributionGrid DSL already has an operations { ... } block." }
        operationsConfiguration = DistributionGridOperationsDsl().apply(block).build()
        return this
    }

    /**
     * Configure an emergency kill switch to halt execution if token limits are exceeded.
     *
     * The kill switch monitors input and output token usage across router and all workers.
     * When tripped, it immediately terminates the grid regardless of any retry policies.
     *
     * @param inputTokenLimit Maximum input tokens allowed (prompt + context). null = no limit.
     * @param outputTokenLimit Maximum output tokens allowed (response + reasoning). null = no limit.
     * @param onTripped Optional callback invoked when the kill switch trips.
     * @return This builder for chaining.
     */
    fun killSwitch(
        inputTokenLimit: Int? = null,
        outputTokenLimit: Int? = null,
        onTripped: ((KillSwitchContext) -> Nothing)? = null
    ): DistributionGridBuilder<S>
    {
        killSwitchConfiguration = if(onTripped != null)
        {
            KillSwitch(inputTokenLimit = inputTokenLimit, outputTokenLimit = outputTokenLimit, onTripped = onTripped)
        }
        else
        {
            KillSwitch(inputTokenLimit = inputTokenLimit, outputTokenLimit = outputTokenLimit)
        }
        return this
    }

    //=========================================Build===================================================================

    /**
     * Validate all DSL declarations and materialize a configured [DistributionGrid].
     *
     * This is the single source of truth for DSL-to-DistributionGrid wiring so [buildInternal] and [buildSuspend]
     * stay in sync.
     *
     * @return Configured but not yet initialized grid.
     */
    @PublishedApi
    internal fun configureGridInternal(): DistributionGrid
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

        if(killSwitchConfiguration != null)
        {
            grid.killSwitch = killSwitchConfiguration
        }

        return grid
    }

    /**
     * Build and initialize the configured [DistributionGrid] synchronously.
     *
     * Used internally by the [distributionGrid] entry point. To enforce compile-time safety from external callers
     * use the [build] extension on [DistributionGridBuilder] in the [GridStage.Ready] state.
     *
     * @return Fully initialized grid ready for use.
     */
    @PublishedApi
    internal fun buildInternal(): DistributionGrid
    {
        val grid = configureGridInternal()
        runBlocking {
            grid.init()
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

//=========================================Entry points================================================================

/**
 * Entry point for the type-safe DistributionGrid DSL.
 *
 * The block runs against a [DistributionGridBuilder] in the [GridStage.Initial] stage. Compile-time safety is
 * provided via the [build] extension which is restricted to [GridStage.Ready] for manual chaining use. The entry
 * point itself validates required configuration at runtime before initializing the grid.
 *
 * @param block Builder block that configures the node shell, policies, peers, discovery, and hooks.
 * @return Fully initialized grid ready for execution.
 */
fun distributionGrid(block: DistributionGridBuilder<GridStage.Initial>.() -> Unit): DistributionGrid
{
    val builder = DistributionGridBuilder<GridStage.Initial>()
    builder.block()
    return builder.buildInternal()
}

/**
 * Factory function to create the initial [DistributionGridBuilder] in the [GridStage.Initial] stage.
 *
 * Use this when you want to assemble and chain the builder manually rather than with the [distributionGrid] block DSL.
 *
 * @return A new builder in the [GridStage.Initial] stage.
 */
fun distributionGridBuilder(): DistributionGridBuilder<GridStage.Initial>
{
    return DistributionGridBuilder()
}

/**
 * Build and initialize the configured [DistributionGrid] synchronously.
 *
 * Only available on [DistributionGridBuilder] in the [GridStage.Ready] state — a compile error otherwise.
 *
 * @receiver A [DistributionGridBuilder] with both router and worker configured.
 * @return Fully initialized grid ready for use.
 */
fun DistributionGridBuilder<GridStage.Ready>.build(): DistributionGrid
{
    return this.buildInternal()
}

/**
 * Build and initialize the configured [DistributionGrid] in a suspend context.
 *
 * Only available on [DistributionGridBuilder] in the [GridStage.Ready] state — a compile error otherwise.
 *
 * @receiver A [DistributionGridBuilder] with both router and worker configured.
 * @return Fully initialized grid ready for use.
 */
suspend fun DistributionGridBuilder<GridStage.Ready>.buildSuspend(): DistributionGrid
{
    val grid = this.configureGridInternal()
    grid.init()
    return grid
}

//=========================================Internal data classes=======================================================

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

//=========================================Nested DSL builders=========================================================

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
