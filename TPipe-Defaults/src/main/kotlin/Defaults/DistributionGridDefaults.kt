package Defaults

import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Debug.TraceConfig
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PRequirements
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.DistributionGrid
import com.TTT.Pipeline.DistributionGridBuilder
import com.TTT.Pipeline.DistributionGridDirective
import com.TTT.Pipeline.DistributionGridDirectiveKind
import com.TTT.Pipeline.DistributionGridDurableStore
import com.TTT.Pipeline.GridStage
import com.TTT.Pipeline.distributionGrid
import com.TTT.Pipeline.DistributionGridMemoryPolicy
import com.TTT.Pipeline.DistributionGridPeerDiscoveryMode
import com.TTT.Pipeline.DistributionGridRegistryAdvertisement
import com.TTT.Pipeline.DistributionGridRegistryMetadata
import com.TTT.Pipeline.DistributionGridRoutingPolicy
import com.TTT.Pipeline.DistributionGridTrustVerifier
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.deepCopy
import com.TTT.Util.extractJson
import kotlinx.coroutines.runBlocking
import ollamaPipe.OllamaPipe

private const val GRID_DIRECTIVE_METADATA_KEY = "distributionGridDirective"
private const val ORIGINAL_ROUTER_TEXT_METADATA_KEY = "distributionGridDefaultsOriginalText"

/**
 * Central factory for creating pre-configured `DistributionGrid` instances with provider-specific defaults.
 *
 * This mirrors the role `ManifoldDefaults` plays for manifolds while keeping the core `DistributionGrid` DSL and
 * runtime provider-agnostic.
 */
object DistributionGridDefaults
{
    /**
     * Creates an initialized Bedrock-backed `DistributionGrid`.
     *
     * @param configuration Grid-specific Bedrock defaults configuration.
     * @return Initialized `DistributionGrid`.
     */
    fun withBedrock(configuration: BedrockGridConfiguration): DistributionGrid
    {
        require(configuration.validate()) { "Invalid Bedrock grid configuration: $configuration" }

        return distributionGrid {
            defaults { bedrock(configuration) }
        }
    }

    /**
     * Creates an initialized Ollama-backed `DistributionGrid`.
     *
     * @param configuration Grid-specific Ollama defaults configuration.
     * @return Initialized `DistributionGrid`.
     */
    fun withOllama(configuration: OllamaGridConfiguration): DistributionGrid
    {
        require(configuration.validate()) { "Invalid Ollama grid configuration: $configuration" }

        return distributionGrid {
            defaults { ollama(configuration) }
        }
    }

    /**
     * Build the provider-backed default router pipeline for a Bedrock-configured grid.
     *
     * @param configuration Grid-specific Bedrock defaults configuration.
     * @return Router pipeline ready to bind into `distributionGrid { router(...) }`.
     */
    fun buildDefaultRouterPipeline(configuration: BedrockGridConfiguration): Pipeline
    {
        require(configuration.validate()) { "Invalid Bedrock grid configuration: $configuration" }
        return Pipeline().apply {
            add(createBedrockRouterPipe(configuration))
        }
    }

    /**
     * Build the provider-backed default router pipeline for an Ollama-configured grid.
     *
     * @param configuration Grid-specific Ollama defaults configuration.
     * @return Router pipeline ready to bind into `distributionGrid { router(...) }`.
     */
    fun buildDefaultRouterPipeline(configuration: OllamaGridConfiguration): Pipeline
    {
        require(configuration.validate()) { "Invalid Ollama grid configuration: $configuration" }
        return Pipeline().apply {
            add(createOllamaRouterPipe(configuration))
        }
    }

    /**
     * Build the provider-backed default worker pipeline for a Bedrock-configured grid.
     *
     * @param configuration Grid-specific Bedrock defaults configuration.
     * @return Worker pipeline ready to bind into `distributionGrid { worker(...) }`.
     */
    fun buildDefaultWorkerPipeline(configuration: BedrockGridConfiguration): Pipeline
    {
        require(configuration.validate()) { "Invalid Bedrock grid configuration: $configuration" }
        return Pipeline().apply {
            add(createBedrockWorkerPipe(configuration))
        }
    }

    /**
     * Build the provider-backed default worker pipeline for an Ollama-configured grid.
     *
     * @param configuration Grid-specific Ollama defaults configuration.
     * @return Worker pipeline ready to bind into `distributionGrid { worker(...) }`.
     */
    fun buildDefaultWorkerPipeline(configuration: OllamaGridConfiguration): Pipeline
    {
        require(configuration.validate()) { "Invalid Ollama grid configuration: $configuration" }
        return Pipeline().apply {
            add(createOllamaWorkerPipe(configuration))
        }
    }

    internal fun applyBedrockDefaults(dsl: DistributionGridBuilder<GridStage.Initial>, configuration: BedrockGridConfiguration)
    {
        require(configuration.validate()) { "Invalid Bedrock grid configuration: $configuration" }

        dsl.router(
            component = buildDefaultRouterPipeline(configuration),
            descriptor = configuration.gridDefaults.routerSeed.descriptor?.deepCopy(),
            requirements = configuration.gridDefaults.routerSeed.requirements?.copy()
        )
        dsl.worker(
            component = buildDefaultWorkerPipeline(configuration),
            descriptor = configuration.gridDefaults.workerSeed.descriptor?.deepCopy(),
            requirements = configuration.gridDefaults.workerSeed.requirements?.copy()
        )
        applyGridDefaults(dsl, configuration.gridDefaults)
    }

    internal fun applyOllamaDefaults(dsl: DistributionGridBuilder<GridStage.Initial>, configuration: OllamaGridConfiguration)
    {
        require(configuration.validate()) { "Invalid Ollama grid configuration: $configuration" }

        dsl.router(
            component = buildDefaultRouterPipeline(configuration),
            descriptor = configuration.gridDefaults.routerSeed.descriptor?.deepCopy(),
            requirements = configuration.gridDefaults.routerSeed.requirements?.copy()
        )
        dsl.worker(
            component = buildDefaultWorkerPipeline(configuration),
            descriptor = configuration.gridDefaults.workerSeed.descriptor?.deepCopy(),
            requirements = configuration.gridDefaults.workerSeed.requirements?.copy()
        )
        applyGridDefaults(dsl, configuration.gridDefaults)
    }

    private fun applyGridDefaults(dsl: DistributionGridBuilder<*>, configuration: DistributionGridDefaultsConfiguration)
    {
        applyP2pDefaults(
            dsl = dsl,
            descriptor = configuration.p2pDescriptor,
            requirements = configuration.p2pRequirements
        )
        applyRoutingDefaults(dsl, configuration.routingPolicy)
        applyMemoryDefaults(dsl, configuration.memoryPolicy)
        applyTraceDefaults(dsl, configuration.traceConfig)
        applyDiscoveryDefaults(
            dsl = dsl,
            discoveryMode = configuration.discoveryMode,
            registryMetadata = configuration.registryMetadata,
            trustVerifier = configuration.trustVerifier,
            bootstrapRegistries = configuration.bootstrapRegistries
        )
        applyDurabilityDefaults(dsl, configuration.durableStore)
        applyOperationsDefaults(
            dsl = dsl,
            maxHops = configuration.maxHops,
            rpcTimeoutMillis = configuration.rpcTimeoutMillis,
            maxSessionDurationSeconds = configuration.maxSessionDurationSeconds
        )
    }

    private fun applyP2pDefaults(
        dsl: DistributionGridBuilder<*>,
        descriptor: P2PDescriptor?,
        requirements: P2PRequirements?
    )
    {
        if(descriptor == null && requirements == null)
        {
            return
        }

        dsl.p2p {
            descriptor?.let { descriptor(it.deepCopy()) }
            requirements?.let { requirements(it.copy()) }
        }
    }

    private fun applyRoutingDefaults(
        dsl: DistributionGridBuilder<*>,
        policy: DistributionGridRoutingPolicy?
    )
    {
        if(policy == null)
        {
            return
        }

        dsl.routing {
            policy(policy.deepCopy())
        }
    }

    private fun applyMemoryDefaults(
        dsl: DistributionGridBuilder<*>,
        policy: DistributionGridMemoryPolicy?
    )
    {
        if(policy == null)
        {
            return
        }

        dsl.memory {
            policy(policy.copy())
        }
    }

    private fun applyTraceDefaults(
        dsl: DistributionGridBuilder<*>,
        traceConfig: TraceConfig?
    )
    {
        if(traceConfig == null)
        {
            return
        }

        dsl.tracing {
            enabled(traceConfig)
        }
    }

    private fun applyDiscoveryDefaults(
        dsl: DistributionGridBuilder<*>,
        discoveryMode: DistributionGridPeerDiscoveryMode?,
        registryMetadata: DistributionGridRegistryMetadata?,
        trustVerifier: DistributionGridTrustVerifier?,
        bootstrapRegistries: List<DistributionGridRegistryAdvertisement>
    )
    {
        if(
            discoveryMode == null &&
            registryMetadata == null &&
            trustVerifier == null &&
            bootstrapRegistries.isEmpty()
        )
        {
            return
        }

        dsl.discovery {
            discoveryMode?.let { mode(it) }
            registryMetadata?.let { registryMetadata(it.deepCopy()) }
            trustVerifier?.let { trustVerifier(it) }
            bootstrapRegistries.forEach { bootstrapRegistry(it.deepCopy()) }
        }
    }

    private fun applyDurabilityDefaults(
        dsl: DistributionGridBuilder<*>,
        durableStore: DistributionGridDurableStore?
    )
    {
        if(durableStore == null)
        {
            return
        }

        dsl.durability {
            store(durableStore)
        }
    }

    private fun applyOperationsDefaults(
        dsl: DistributionGridBuilder<*>,
        maxHops: Int?,
        rpcTimeoutMillis: Long?,
        maxSessionDurationSeconds: Int?
    )
    {
        if(maxHops == null && rpcTimeoutMillis == null && maxSessionDurationSeconds == null)
        {
            return
        }

        dsl.operations {
            maxHops?.let { maxHops(it) }
            rpcTimeoutMillis?.let { rpcTimeoutMillis(it) }
            maxSessionDurationSeconds?.let { maxSessionDurationSeconds(it) }
        }
    }

    private fun createBedrockRouterPipe(configuration: BedrockGridConfiguration): BedrockMultimodalPipe
    {
        val providerConfiguration = configuration.toProviderConfiguration()
        return BedrockMultimodalPipe().apply {
            if(providerConfiguration.inferenceProfile.isNotEmpty())
            {
                setModel(providerConfiguration.inferenceProfile)
            }
            else
            {
                setModel(providerConfiguration.model)
            }

            setRegion(providerConfiguration.region)
            if(providerConfiguration.useConverseApi)
            {
                useConverseApi()
            }

            configureRouterPipe()
        }
    }

    private fun createOllamaRouterPipe(configuration: OllamaGridConfiguration): OllamaPipe
    {
        val providerConfiguration = configuration.toProviderConfiguration()
        return OllamaPipe().apply {
            setModel(providerConfiguration.model)
            setIP(providerConfiguration.host)
            setPort(providerConfiguration.port)
            configureRouterPipe()
        }
    }

    private fun createBedrockWorkerPipe(configuration: BedrockGridConfiguration): BedrockMultimodalPipe
    {
        val providerConfiguration = configuration.toProviderConfiguration()
        return BedrockMultimodalPipe().apply {
            if(providerConfiguration.inferenceProfile.isNotEmpty())
            {
                setModel(providerConfiguration.inferenceProfile)
            }
            else
            {
                setModel(providerConfiguration.model)
            }

            setRegion(providerConfiguration.region)
            if(providerConfiguration.useConverseApi)
            {
                useConverseApi()
            }

            configureWorkerPipe()
        }
    }

    private fun createOllamaWorkerPipe(configuration: OllamaGridConfiguration): OllamaPipe
    {
        val providerConfiguration = configuration.toProviderConfiguration()
        return OllamaPipe().apply {
            setModel(providerConfiguration.model)
            setIP(providerConfiguration.host)
            setPort(providerConfiguration.port)
            configureWorkerPipe()
        }
    }

    private fun com.TTT.Pipe.Pipe.configureRouterPipe(): com.TTT.Pipe.Pipe
    {
        return this
            .setPipeName("distribution-grid-default-router")
            .setJsonOutput(DistributionGridDirective())
            .setSystemPrompt(
                """
                You are the default router for a TPipe DistributionGrid node.
                Return ONLY a DistributionGridDirective JSON object.
                Default to RUN_LOCAL_WORKER unless the task clearly needs to be rejected, terminated, returned
                upstream, or the prompt explicitly names a real downstream peer target.
                Never invent targetPeerId, targetNodeId, or targetTransport values when they are not provided.
                """.trimIndent()
            )
            .setMiddlePrompt(
                """
                Decide the next DistributionGrid action for the current task.
                If no trustworthy downstream target is explicitly provided in the task text, choose RUN_LOCAL_WORKER.
                Keep notes short and operational.
                """.trimIndent()
            )
            .setFooterPrompt(
                """
                Return a valid DistributionGridDirective JSON object.
                Prefer RUN_LOCAL_WORKER over speculative remote routing.
                Use REJECT only for clearly disallowed tasks and TERMINATE only for unrecoverable execution situations.
                """.trimIndent()
            )
            .setPreInvokeFunction { content ->
                val existingDirective = content.metadata[GRID_DIRECTIVE_METADATA_KEY] as? DistributionGridDirective
                if(existingDirective != null)
                {
                    return@setPreInvokeFunction true
                }

                content.metadata[ORIGINAL_ROUTER_TEXT_METADATA_KEY] = content.text
                content.text = buildRouterPromptText(content.text)
                false
            }
            .setPostGenerateFunction { content ->
                val parsedDirective = extractJson<DistributionGridDirective>(content.text) ?: DistributionGridDirective(
                    kind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER,
                    notes = "Defaults router fell back to local worker because it could not parse a valid directive."
                )

                val originalText = content.metadata.remove(ORIGINAL_ROUTER_TEXT_METADATA_KEY) as? String ?: content.text
                content.metadata[GRID_DIRECTIVE_METADATA_KEY] = parsedDirective
                content.text = originalText
            }
    }

    private fun com.TTT.Pipe.Pipe.configureWorkerPipe(): com.TTT.Pipe.Pipe
    {
        return this
            .setPipeName("distribution-grid-default-worker")
            .setSystemPrompt(
                """
                You are the default worker for a TPipe DistributionGrid node.
                Execute the task directly and return the best final answer you can from this node.
                Do not return routing directives. Return task output for the caller.
                """.trimIndent()
            )
    }

    private fun buildRouterPromptText(originalText: String): String
    {
        return """
            Current task content:
            $originalText

            Choose the next DistributionGrid directive for this task.
        """.trimIndent()
    }
}
