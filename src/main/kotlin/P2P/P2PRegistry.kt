package com.TTT.P2P

import com.TTT.Config.AuthRegistry
import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.Context.Dictionary
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.toTruncationSettings
import com.TTT.Pipe.TruncationSettings
import com.TTT.Pipe.getMimeType
import com.TTT.PipeContextProtocol.SessionResponse
import com.TTT.PipeContextProtocol.StdioExecutionMode
import com.TTT.PipeContextProtocol.StdioSessionManager
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Concurrency mode for P2P agent registration. Controls whether inbound requests share one instance or get
 * fresh isolated copies.
 */
enum class P2PConcurrencyMode
{
    /** Current default — one shared instance handles all requests. */
    SHARED,
    /** Each request gets a fresh clone or factory-built instance. */
    ISOLATED
}

/**
 * Contains the critical contents of an agent listing. The agent descriptor which advertises agent capabilities.
 * The requirements of the agent, and the container which will be used to run the agent.
 *
 * @param descriptor Public advertisement for what the agent does, allows, and it's feature set. This is made visible
 * only to the running instance of the TPipe library unless broadcasted through a server.
 *
 * @param requirements the agent demands of the caller in order to run. Requirements are not publicly advertised.
 * An agent seeking to connect via P2P must adhere to what the descriptor says however extra security requirements,
 * or strictness levels are not made visible to the agent or external system.
 */
data class P2PAgentListing(
    var descriptor: P2PDescriptor,
    var requirements: P2PRequirements,
    var container: P2PInterface,
    var concurrencyMode: P2PConcurrencyMode = P2PConcurrencyMode.SHARED,
    var factory: (suspend () -> P2PInterface)? = null
)

/**
 * Trusted hosted-registry source used by plain P2P clients to import public AGENT listings into the local
 * client-side catalog.
 */
data class P2PTrustedRegistrySource(
    var sourceId: String = "",
    var transport: P2PTransport = P2PTransport(),
    var query: P2PHostedRegistryQuery = P2PHostedRegistryQuery(
        listingKinds = mutableListOf(P2PHostedListingKind.AGENT)
    ),
    var autoPullOnRegister: Boolean = false,
    var includeInAutoRefresh: Boolean = true,
    var authBody: String = "",
    var transportAuthBody: String = "",
    var admissionPolicy: P2PTrustedRegistryAdmissionPolicy? = null,
    var admissionFilter: (suspend (source: P2PTrustedRegistrySource, listing: P2PHostedRegistryListing) -> String?)? = null
)

/**
 * Lightweight trusted-import policy for plain P2P hosted-registry sources.
 *
 * This remains intentionally lighter than DistributionGrid trust verification.
 */
data class P2PTrustedRegistryAdmissionPolicy(
    var requireVerificationEvidence: Boolean = false,
    var minimumRemainingLeaseMillis: Long = 0L,
    var requireActiveModerationState: Boolean = true,
    var sourceLabel: String = ""
)

/**
 * Diagnostic record for a hosted-registry import collision that was rejected instead of being allowed to overwrite an
 * existing agent entry.
 */
data class P2PTrustedRegistryImportCollision(
    var sourceId: String = "",
    var listingId: String = "",
    var agentName: String = "",
    var reason: String = "",
    var recordedAtEpochMillis: Long = 0L
)

private data class P2PTrustedImportedAgentRecord(
    var listingId: String,
    var agentName: String,
    var importedAtEpochMillis: Long,
    var attestationRef: String = "",
    var sourceLabel: String = ""
)

/**
 * Inspectable provenance record for one trusted hosted-registry agent import.
 */
data class P2PTrustedImportedAgentInfo(
    var listingId: String = "",
    var agentName: String = "",
    var sourceId: String = "",
    var importedAtEpochMillis: Long = 0L,
    var attestationRef: String = "",
    var sourceLabel: String = ""
)

/**
 * Registry for P2P. Records pipelines, or containers of pipelines, their requirements to allow p2p, and transport path.
 * Each container or pipeline name can only be registered once. Unlike other global objects in TPipe, fields here
 * will always be entirely public so that they can be accessed from anywhere. And eventually, be addressable by a
 * supporting 2P2 server module in the future.
 */
object P2PRegistry
{

//----------------------------------------------Properties--------------------------------------------------------------
    /**
     * For thread saftey if and when any containers register agents inside any running coroutines.
     */
    val agentListMutex = Mutex()

    /**
     * Map containing all listed and registered agents.
     */
    @Volatile
    private var Agents = mutableMapOf<P2PTransport, P2PAgentListing>()

    /**
     * Client side agent list. Simplified to handle the less complex agent request method the llm makes vs the full
     * request the human parses and handles. Maps agent name to its public descriptor. Agents placed here are
     * considered external and likely remote or at least stdio.
     */
    private var clientAgentList = mutableMapOf<String, P2PDescriptor>()

    /**
     * Set of templates that can be invoked to simplify the construction of P2P requests from agent requests.
     * Helps simplify allows Any to be used a key to map to a template and be addressed by a helper function to
     * retrieve and use the template to build a full P2P Request.
     * @see com.TTT.P2P.AgentRequest.buildRequestFromRegistry
     */
    var requestTemplates = mutableMapOf<Any, P2PRequest>()

    /**
     * Universal transport-level authentication gatekeeper for all TPipe hosted services.
     * This hook allows developers to integrate custom authentication logic.
     *
     * The `authBody` parameter value originates from various sources depending on the transport layer:
     * - **Remote Memory & Trace Server:** Extracted from the HTTP `Authorization` header.
     * - **P2P HTTP/Stdio:** Extracted from `P2PRequest.transport.transportAuthBody`.
     * - **PCP Stdio:** Extracted from the `authBody` key within `PcPRequest.callParams`.
     */
    var globalAuthMechanism: (suspend (authBody: String) -> Boolean)? = null

    /**
     * Trusted hosted-registry sources used to import public AGENT listings into the client-side remote catalog.
     */
    private val trustedRegistrySourcesById = linkedMapOf<String, P2PTrustedRegistrySource>()

    /**
     * Tracks which agent names are currently imported from which trusted source so refresh and removal can clean up
     * only source-owned entries.
     */
    private val trustedImportedAgentsBySourceId = linkedMapOf<String, MutableMap<String, P2PTrustedImportedAgentRecord>>()

    /**
     * Reverse lookup from imported agent name to owning trusted source id.
     */
    private val trustedImportedSourceIdByAgentName = linkedMapOf<String, String>()

    /**
     * Collision diagnostics for rejected trusted hosted-registry imports.
     */
    private val trustedRegistryImportCollisions = linkedMapOf<Pair<String, String>, P2PTrustedRegistryImportCollision>()

    private val trustedRegistryStateMutex = Mutex()
    private val trustedRegistryRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var trustedRegistryRefreshJob: Job? = null

//-----------------------------------------------Constructor------------------------------------------------------------


    /**
     * Init function to load in a list of remote agents that can be called externally. This differs from internal
     * registers which are for agents that are created, and being hosted in this instance of our running program.
     * External agents are agents that are outside this program and are being loaded in after having their descriptors
     * sent over through an external service, method, or transport supplied by the parent program that is implementing
     * the TPipe library.
     */
    fun loadAgents(agents: List<P2PDescriptor>)
    {
        for(agent in agents)
        {
            /**
             * Get the agent name from the passed in descriptor this is used a key for client side agents as well as
             * storing a template mapped to the registry to that will be used to construct the full request body to it.
             * We need to split the smaller request of the TPipe agent from the larget human request to reduce token
             * wastage as well as lower the chances of the llm getting confused on how to call a TPipe agent.
             */
            val agentName = agent.agentName
            clientAgentList[agentName] = agent //Map agent name to agent descriptor in the client side list.

            //Save the request template to our mapped registry templates for simplicity.
            requestTemplates[agentName] = agent.requestTemplate ?: P2PRequest()

        }
    }

    /**
     * Import hosted public agent listings into the client-side remote catalog.
     *
     * Only generic `AGENT` listings are loaded here. DistributionGrid-specific listings stay in their own discovery
     * path so public catalog data cannot silently bypass grid trust and handshake rules.
     *
     * @param listings Hosted-registry listings to import.
     * @param replaceExisting Whether existing client-side descriptors/templates with the same agent name should be replaced.
     */
    fun loadHostedAgentListings(
        listings: List<P2PHostedRegistryListing>,
        replaceExisting: Boolean = true
    )
    {
        listings.forEach { listing ->
            if(listing.kind != P2PHostedListingKind.AGENT)
            {
                return@forEach
            }

            val descriptor = listing.publicDescriptor?.deepCopy<P2PDescriptor>() ?: return@forEach
            val agentName = descriptor.agentName
            if(agentName.isBlank())
            {
                return@forEach
            }

            if(!replaceExisting && clientAgentList.containsKey(agentName))
            {
                return@forEach
            }

            clientAgentList[agentName] = descriptor
            requestTemplates[agentName] = descriptor.requestTemplate?.deepCopy<P2PRequest>() ?: P2PRequest()
        }
    }

    /**
     * List the imported remote-client agent descriptors currently visible to simplified agent requests.
     */
    fun listClientAgents(): List<P2PDescriptor>
    {
        return clientAgentList.values.map { it.deepCopy<P2PDescriptor>() }
    }

    /**
     * Register one trusted hosted-registry source.
     *
     * If [P2PTrustedRegistrySource.autoPullOnRegister] is true, one pull runs immediately after registration.
     */
    suspend fun addTrustedRegistrySource(source: P2PTrustedRegistrySource)
    {
        require(source.sourceId.isNotBlank()) { "Trusted registry source requires a nonblank sourceId." }
        require(source.transport.transportAddress.isNotBlank()) { "Trusted registry source '${source.sourceId}' requires a transport address." }

        val normalizedSource = source.copy(
            transport = source.transport.deepCopy(),
            query = source.query.deepCopy<P2PHostedRegistryQuery>().apply {
                if(listingKinds.isEmpty())
                {
                    listingKinds.add(P2PHostedListingKind.AGENT)
                }
            }
        )

        trustedRegistryStateMutex.withLock {
            trustedRegistrySourcesById[normalizedSource.sourceId] = normalizedSource
        }

        if(normalizedSource.autoPullOnRegister)
        {
            pullTrustedRegistrySources(listOf(normalizedSource.sourceId))
        }
    }

    /**
     * Remove one trusted hosted-registry source and any agent listings it imported.
     */
    suspend fun removeTrustedRegistrySource(sourceId: String)
    {
        trustedRegistryStateMutex.withLock {
            trustedRegistrySourcesById.remove(sourceId)
            removeImportedAgentsLocked(sourceId)
        }
    }

    /**
     * Read the configured trusted hosted-registry source ids.
     */
    suspend fun getTrustedRegistrySourceIds(): List<String>
    {
        return trustedRegistryStateMutex.withLock {
            trustedRegistrySourcesById.keys.toList()
        }
    }

    /**
     * Read the recorded trusted hosted-registry import collisions.
     */
    suspend fun getTrustedRegistryImportCollisions(): List<P2PTrustedRegistryImportCollision>
    {
        return trustedRegistryStateMutex.withLock {
            trustedRegistryImportCollisions.values.map { it.copy() }
        }
    }

    /**
     * Inspect the currently imported trusted hosted-registry agents together with their provenance metadata.
     */
    suspend fun listTrustedImportedAgents(): List<P2PTrustedImportedAgentInfo>
    {
        return trustedRegistryStateMutex.withLock {
            trustedImportedAgentsBySourceId.flatMap { (sourceId, records) ->
                records.values.map { record ->
                    P2PTrustedImportedAgentInfo(
                        listingId = record.listingId,
                        agentName = record.agentName,
                        sourceId = sourceId,
                        importedAtEpochMillis = record.importedAtEpochMillis,
                        attestationRef = record.attestationRef,
                        sourceLabel = record.sourceLabel
                    )
                }
            }
        }
    }

    /**
     * Remove all configured trusted sources, imported entries, collision diagnostics, and any active refresh loop.
     */
    suspend fun clearTrustedRegistryImportState()
    {
        stopTrustedRegistryAutoRefresh()
        trustedRegistryStateMutex.withLock {
            trustedRegistrySourcesById.clear()
            trustedImportedAgentsBySourceId.keys.toList().forEach { sourceId ->
                removeImportedAgentsLocked(sourceId)
            }
            trustedImportedAgentsBySourceId.clear()
            trustedImportedSourceIdByAgentName.clear()
            trustedRegistryImportCollisions.clear()
        }
    }

    /**
     * Pull one or more trusted hosted-registry sources and reconcile their imported AGENT listings into the live
     * client-side catalog.
     *
     * A failed pull leaves the previous successful imports for that source intact.
     */
    suspend fun pullTrustedRegistrySources(sourceIds: List<String> = emptyList())
    {
        val sources = trustedRegistryStateMutex.withLock {
            val ids = if(sourceIds.isEmpty()) trustedRegistrySourcesById.keys.toList() else sourceIds
            ids.mapNotNull { sourceId -> trustedRegistrySourcesById[sourceId]?.copy(
                transport = trustedRegistrySourcesById[sourceId]!!.transport.deepCopy(),
                query = trustedRegistrySourcesById[sourceId]!!.query.deepCopy()
            ) }
        }

        sources.forEach { source ->
            val result = P2PHostedRegistryClient.searchListings(
                transport = source.transport,
                query = source.query.deepCopy<P2PHostedRegistryQuery>().apply {
                    if(listingKinds.isEmpty())
                    {
                        listingKinds.add(P2PHostedListingKind.AGENT)
                    }
                },
                authBody = source.authBody,
                transportAuthBody = source.transportAuthBody
            )

            if(!result.accepted)
            {
                return@forEach
            }

            val acceptedListings = mutableListOf<P2PHostedRegistryListing>()
            result.results.forEach { listing ->
                if(listing.kind != P2PHostedListingKind.AGENT)
                {
                    return@forEach
                }

                val descriptor = listing.publicDescriptor ?: return@forEach
                if(descriptor.agentName.isBlank())
                {
                    return@forEach
                }

                val policyRejection = evaluateTrustedAdmissionPolicy(source, listing)
                if(!policyRejection.isNullOrBlank())
                {
                    return@forEach
                }

                val rejection = source.admissionFilter?.invoke(source, listing.deepCopy())
                if(!rejection.isNullOrBlank())
                {
                    return@forEach
                }

                acceptedListings.add(listing.deepCopy())
            }

            trustedRegistryStateMutex.withLock {
                reconcileTrustedSourceLocked(source, acceptedListings)
            }
        }
    }

    /**
     * Start an opt-in background refresh loop for trusted hosted-registry sources.
     */
    fun startTrustedRegistryAutoRefresh(intervalMillis: Long)
    {
        require(intervalMillis > 0L) { "Trusted registry auto-refresh interval must be greater than zero." }
        stopTrustedRegistryAutoRefresh()
        trustedRegistryRefreshJob = trustedRegistryRefreshScope.launch {
            while(isActive)
            {
                val sourceIds = trustedRegistryStateMutex.withLock {
                    trustedRegistrySourcesById.values
                        .filter { it.includeInAutoRefresh }
                        .map { it.sourceId }
                }
                if(sourceIds.isNotEmpty())
                {
                    pullTrustedRegistrySources(sourceIds)
                }
                delay(intervalMillis)
            }
        }
    }

    /**
     * Stop the background refresh loop for trusted hosted-registry sources.
     */
    fun stopTrustedRegistryAutoRefresh()
    {
        trustedRegistryRefreshJob?.cancel()
        trustedRegistryRefreshJob = null
    }

    /**
     * Check whether the trusted hosted-registry auto-refresh loop is currently active.
     */
    fun isTrustedRegistryAutoRefreshRunning(): Boolean
    {
        return trustedRegistryRefreshJob?.isActive == true
    }


//------------------------------------------------Functions-------------------------------------------------------------

    /**
     * Register an agent with the registry.
     *
     * @param agent the agent to be registered
     * @param transport the transport path to the agent
     * @param descriptor the agent descriptor
     * @param requirements the agent requirements
     * @param concurrencyMode SHARED routes all requests to this instance. ISOLATED clones it per request.
     */
    fun register(
        agent: P2PInterface,
        transport: P2PTransport,
        descriptor: P2PDescriptor,
        requirements: P2PRequirements,
        concurrencyMode: P2PConcurrencyMode = P2PConcurrencyMode.SHARED
    )
    {
        if(descriptor.requiresAuth && requirements.authMechanism == null && globalAuthMechanism == null)
        {
            throw IllegalArgumentException(
                "Agent '${descriptor.agentName}' has requiresAuth=true but no authMechanism is configured and no globalAuthMechanism is set."
            )
        }

        Agents[transport] = P2PAgentListing(descriptor, requirements, agent, concurrencyMode)
    }

    /**
     * Register a factory function that produces a fresh agent instance per inbound request.
     *
     * Factory mode implies ISOLATED concurrency — every request calls the factory, executes against the fresh
     * instance, and discards it after completion.
     *
     * @param factory Suspend function that produces a fresh P2PInterface instance.
     * @param transport the transport path for the agent
     * @param descriptor the agent descriptor
     * @param requirements the agent requirements
     */
    fun register(
        factory: suspend () -> P2PInterface,
        transport: P2PTransport,
        descriptor: P2PDescriptor,
        requirements: P2PRequirements
    )
    {
        if(descriptor.requiresAuth && requirements.authMechanism == null && globalAuthMechanism == null)
        {
            throw IllegalArgumentException(
                "Agent '${descriptor.agentName}' has requiresAuth=true but no authMechanism is configured and no globalAuthMechanism is set."
            )
        }

        Agents[transport] = P2PAgentListing(
            descriptor = descriptor,
            requirements = requirements,
            container = object : P2PInterface {},
            concurrencyMode = P2PConcurrencyMode.ISOLATED,
            factory = factory
        )
    }

    /**
     * Overload that uses the stored settings on the agent interface to register the agent.
     * @throws Exception if the agent does not have a transport path, descriptor, or requirements.
     */
    fun register(agent: P2PInterface)
    {
        val transport = agent.getP2pTransport() ?: throw Exception("Agent does not have a transport path")
        val descriptor = agent.getP2pDescription() ?: throw Exception("Agent does not have a descriptor")
        var requirements = agent.getP2pRequirements()

        /**
         * If the container is null or not supported, this agent automatically becomes public.
         */
        val container = agent.getContainerObject()

        //We need to auto determine requirements if none were assigned.
        if(requirements == null)
        {
            requirements = P2PRequirements()
            if(agent.getContainerObject() == null) requirements.allowExternalConnections = true
            if(descriptor.allowsAgentDuplication) requirements.allowAgentDuplication = true
            if(descriptor.allowsCustomContext) requirements.allowCustomContext = true
            if(descriptor.allowsCustomAgentJson) requirements.allowCustomJson = true
            if(descriptor.allowsExternalContext) requirements.allowExternalConnections = true
            if(descriptor.usesConverse) requirements.requireConverseInput = true
            if(descriptor.requiresAuth) requirements.authMechanism = { _ -> false } // Default deny
            requirements.acceptedContent = descriptor.supportedContentTypes
            requirements.maxTokens = descriptor.contextWindowSize
        }

        Agents[transport] = P2PAgentListing(descriptor, requirements, agent)
    }

    /**
     * Remove an agent from the registry using its transport path.
     */
    fun remove(transport: P2PTransport) = Agents.remove(transport)

    /**
     * Remove an agent from the registry using the agent interface.
     * @param agent the agent to be removed
     */
    fun remove(agent: P2PInterface) = Agents.remove(agent.getP2pTransport())

    /**
     * Return a listing of all agents in the registry that allows external connections outside their container
     * object.
     */
    fun listGlobalAgents() : List<P2PDescriptor>
    {
        val descriptors = mutableListOf<P2PDescriptor>()

        for(it in Agents)
        {
            val descriptor = it.value.descriptor
            val requirements = it.value.requirements
            if(requirements.allowExternalConnections) descriptors.add(descriptor)
        }

        return descriptors
    }

    /**
     * List all local agents in the registry that are inside the given container object. An agent is local to a container
     * when it is a pipeline that is held inside something like a Manifold, or DistributionGrid that allows multi-agent
     * supervisor to worker, or decentralized swarms. Agents that are registered as local can only be called by another
     * that is inside that local space of said container.
     */
    fun listLocalAgents(container: Any) : List<P2PDescriptor>
    {
        val localDescriptors = mutableListOf<P2PDescriptor>()
        for(entry in Agents)
        {
            val registeredAgent = entry.value
            val agentContainer = registeredAgent.container
            val hostContainer = agentContainer.getContainerObject()

            val isLocal = agentContainer == container || hostContainer == container
            if(isLocal && !registeredAgent.requirements.allowExternalConnections)
            {
                localDescriptors.add(registeredAgent.descriptor)
            }
        }

        return localDescriptors
    }

    /**
     * Check if a P2P request meets the agent's requirements.
     * Validates multimodal content, converse input format, content types, token limits, and authentication.
     *
     * @param request The P2P request to validate
     * @param requirements The agent's requirements to check against
     * @return True if all requirements are met, false otherwise
     */
    suspend fun checkAgentRequirements(request: P2PRequest, requirements: P2PRequirements, agent: P2PInterface): Pair<Boolean, P2PRejection?>
    {
        //Test to see if we contain converse data as our prompt.
        val converseJson = extractJson<ConverseData>(request.prompt.text)
        val converseHistoryJson = extractJson<ConverseHistory>(request.prompt.text)

        if(requirements.requireConverseInput)
        {
            if(converseJson == null && converseHistoryJson == null)
            {
                val rejection = P2PRejection()
                rejection.reason = "Agent requires converse input format but none was provided"
                rejection.errorType = P2PError.prompt
                return Pair(false, rejection)
            }
        }

        //Disallow connections from agents that aren't running in this program instance of TPipe.
        if(!requirements.allowExternalConnections)
        {
            val returnPathIsLocal = Agents[request.returnAddress]
            if(returnPathIsLocal == null)
            {
                val rejection = P2PRejection()
                rejection.reason = "Agent does not allow external connections"
                rejection.errorType = P2PError.transport
                return Pair(false, rejection)
            }
        }

        /**
         * Test to see if the request is taking an action that requires pipeline duplication to execute. If we don't allow
         * that we need to exit here and fail.
         */
        if(!requirements.allowAgentDuplication && (request.outputSchema != null || request.inputSchema != null || request.contextExplanationMessage.isNotEmpty()))
        {
            val rejection = P2PRejection()
            rejection.reason = "Agent does not allow duplication but request requires it"
            rejection.errorType = P2PError.json
            return Pair(false, rejection)
        }

        /**Count the tokens of the text to determine if the prompt is too large based on supplied settings.
         * Requires max tokens be to be greater than 0 and for some token counting settings to also be present.
         * If both of these aren't present and accounted for this check will be skipped.
         */
        if(requirements.maxTokens > 0 && (requirements.tokenCountingSettings != null || requirements.multiPageBudgetSettings != null))
        {
            try{
                val countingSettings = when {
                    requirements.multiPageBudgetSettings != null ->
                        requirements.multiPageBudgetSettings!!.toTruncationSettings(agent as? Pipe)
                    requirements.tokenCountingSettings != null ->
                        requirements.tokenCountingSettings!!
                    else -> null
                }

                if(countingSettings != null)
                {
                    val tokenCount = Dictionary.countTokens(request.prompt.text, countingSettings)
                    if(tokenCount > requirements.maxTokens)
                    {
                        val rejection = P2PRejection()
                        rejection.reason = "Request exceeds maximum token limit of ${requirements.maxTokens}"
                        rejection.errorType = P2PError.prompt
                        return Pair(false, rejection)
                    }
                }
            }
            catch(e: Exception)
            {
                val rejection = P2PRejection()
                rejection.reason = "Failed to count tokens: ${e.message}"
                rejection.errorType = P2PError.prompt
                return Pair(false, rejection)
            }
        }

        // Validate multi-page context usage against agent requirements
        val hasMultiPageContext = detectMultiPageContext(request, agent)
        if(hasMultiPageContext && !requirements.allowMultiPageContext)
        {
            val rejection = P2PRejection()
            rejection.reason = "Agent does not allow multi-page context but request contains multi-page features"
            rejection.errorType = P2PError.context
            return Pair(false, rejection)
        }

        // Enhanced validation when multi-page budget settings are specified
        if(requirements.multiPageBudgetSettings != null && hasMultiPageContext)
        {
            val pipe = agent as? Pipe
            if(pipe != null)
            {
                try {
                    val truncationSettings = pipe.getTruncationSettings()
                    val pipeStrategy = truncationSettings.multiPageBudgetStrategy
                    val reqStrategy = requirements.multiPageBudgetSettings!!.multiPageBudgetStrategy
                    
                    if(pipeStrategy != null && pipeStrategy != reqStrategy)
                    {
                        val rejection = P2PRejection()
                        rejection.reason = "Pipe multi-page strategy ($pipeStrategy) doesn't match requirements ($reqStrategy)"
                        rejection.errorType = P2PError.configuration
                        return Pair(false, rejection)
                    }
                }
                catch(e: Exception)
                {
                    // If we can't get truncation settings, skip this validation
                }
            }
        }

        // Check accepted content types
        if(requirements.acceptedContent != null)
        {
            for(binaryContent in request.prompt.binaryContent)
            {
                val mimeType = binaryContent.getMimeType()
                val isSupported = requirements.acceptedContent!!.any { contentType ->
                    when(contentType.toString().lowercase())
                    {
                        "image" -> mimeType.startsWith("image/")
                        "document" -> mimeType.startsWith("application/") || mimeType.startsWith("text/")
                        "video" -> mimeType.startsWith("video/")
                        "audio" -> mimeType.startsWith("audio/")
                        else -> mimeType.contains(contentType.toString(), ignoreCase = true)
                    }
                }
                if(!isSupported)
                {
                    val rejection = P2PRejection()
                    rejection.reason = "Unsupported content type: $mimeType"
                    rejection.errorType = P2PError.content
                    return Pair(false, rejection)
                }
            }
        }

        val effectiveAuthMechanism = requirements.authMechanism ?: globalAuthMechanism
        if(effectiveAuthMechanism != null)
        {
            val result = effectiveAuthMechanism.invoke(request.authBody)
            if(result == false)
            {
                val rejection = P2PRejection()
                rejection.reason = "Authentication failed"
                rejection.errorType = P2PError.auth
                return Pair(false, rejection)
            }
        }

        return Pair(true, null)
    }

    /**
     * Detects if a P2P request or agent involves multi-page context features.
     * Checks both request content and agent configuration for multi-page indicators.
     *
     * @param request The P2P request to analyze
     * @param agent The target agent to check
     * @return True if multi-page context is detected, false otherwise
     */
    private fun detectMultiPageContext(request: P2PRequest, agent: P2PInterface): Boolean
    {
        // Check request content for multi-page indicators
        val hasMultiPageInRequest = request.contextExplanationMessage.contains("pageKey", ignoreCase = true) ||
                                   request.contextExplanationMessage.contains("MiniBank", ignoreCase = true) ||
                                   request.contextExplanationMessage.contains("multiPage", ignoreCase = true)
        
        // Check agent configuration for multi-page setup using public methods
        val pipe = agent as? Pipe
        val agentHasMultiPage = if(pipe != null) {
            try {
                val truncationSettings = pipe.getTruncationSettings()
                truncationSettings.multiPageBudgetStrategy != null || 
                truncationSettings.pageWeights != null ||
                truncationSettings.fillMode
            }
            catch(e: Exception)
            {
                false
            }
        } else false
        
        return hasMultiPageInRequest || agentHasMultiPage
    }

    /**
     * Call upon receiving a p2p request. How the request has arrived here may vary and could be from stdio, http,
     * or directly from TPipe through an internal call in the same program. By the time we're reached this function
     * the means of transport has been handled. So we'll be extracting the request, locating the agent, and then
     * determining if the agent supports the kind of request that has been made. If it does the agent will be
     * called through it's p2p interface object and the response will propagate back to here. And then this
     * result will be routed through a router function, and returned backwards via the transport method that was
     * used to get to here in the first place.
     */
    suspend fun executeP2pRequest(request: P2PRequest) : P2PResponse
    {
        //Try to find our agent on this system. If we can't, we need to produce a failure and exit from here.
        val agent = Agents[request.transport]
        if(agent == null)
        {
            val response = P2PResponse()
            val rejection = P2PRejection()
            rejection.reason = "Agent not found at adress ${request.transport.transportAddress}"
            rejection.errorType = P2PError.transport
            response.rejection = rejection
            return response
        }

        val requirements = agent.requirements
        //For factory registrations, agent.container is a placeholder — pipe-level checks (token limits, multi-page
        //budget) are skipped because the factory produces instances that may have different pipe configurations.
        val (isValid, rejectionReason) = checkAgentRequirements(request, agent.requirements, agent.container)
        if(!isValid)
        {
            val response = P2PResponse()
            response.rejection = rejectionReason
            return response
        }

        val result: Deferred<P2PResponse> = kotlinx.coroutines.coroutineScope {
            async {
                when(agent.concurrencyMode)
                {
                    P2PConcurrencyMode.SHARED ->
                    {
                        agent.container.executeP2PRequest(request) ?: P2PResponse()
                    }

                    P2PConcurrencyMode.ISOLATED ->
                    {
                        val preExistingAgents = agentListMutex.withLock { Agents.toMap() }

                        val freshInstance = if(agent.factory != null)
                        {
                            agent.factory!!.invoke()
                        }
                        else
                        {
                            val clone = cloneInstance(agent.container)
                            //Containers require init() before execution. Factory mode assumes the factory returns ready instances.
                            when(clone)
                            {
                                is com.TTT.Pipeline.Manifold -> clone.init()
                                is com.TTT.Pipeline.Junction -> clone.init()
                                is com.TTT.Pipeline.DistributionGrid -> clone.init()
                                is com.TTT.Pipeline.Pipeline -> clone.init(true)
                            }
                            clone
                        }

                        try
                        {
                            freshInstance.executeP2PRequest(request) ?: P2PResponse()
                        }
                        finally
                        {
                            agentListMutex.withLock {
                                //Remove any agents added during isolated execution.
                                val addedKeys = Agents.keys.filter { it !in preExistingAgents }
                                for(key in addedKeys)
                                {
                                    Agents.remove(key)
                                }
                                //Restore any agents that were overwritten by clone init() or execution.
                                for((key, listing) in preExistingAgents)
                                {
                                    Agents[key] = listing
                                }
                            }
                        }
                    }
                }
            }
        }

        val response = result.await()

        return response
    }


    /**
     * Client side call to call a remote P2P agent. This function addresses when the request is through http or stdio
     * instead of a local TPipe agent on the system. Handles routing to the external service, awaiting execution,
     * and returning the result, then routing back to the caller.
     *
     * @param request Agent request to send. Contains simplified contents that are less confusing for the llm to
     * produce. If supplied without the template, a template will be auto pulled to construct the full p2p request body.
     * @param httpAuthBody If sending over http using a REST API, this may be required. Will be appended to the transport
     * if not non-null.
     * @param p2pAuthBody Potentially required by the agent being connected to. Typically, this value will not be present
     * inside the agent's own template for obvious reasons. So this can be directly supplied to add it to the generated
     * p2p request.
     * @param template p2p request template to use. Allows the agent to generate a simplified request, and the other remaining
     * required components to be auto generated based on the template.
     */
    suspend fun sendP2pRequest(
        request: AgentRequest,
        httpAuthBody: String = "",
        p2pAuthBody: String = "",
        template: P2PRequest? = null,
        returnAddressOverride: P2PTransport? = null
    ) : P2PResponse
    {

        /**
         * First, we need to track down the descriptor for the given agent. We'll start by looking at any remote
         * agents that have been registered and check for a match. If we don't find a match there, we'll then
         * proceed to look at the agents that are registered and running on this instance of TPipe instead.
         * If for some reason, a remote and local agent share the same name, the local agent will be prioritized
         * instead of the remote one.
         *
         * It's extremely important to exercise caution with agent names to avoid overlap with external services,
         * and to ensure agent names are unique to prevent collisions. TPipe has to deploy simplified
         * versions of the request object, and descriptor objects to reduce token cost
         * and lower the chances of the llm becoming confused by variables in the json schema that aren't relevant
         * to it's decision to call an agent or not.
         */

        //Read from remote first. If valid, this will be assigned over to the fullDescriptor.
        val agentDescriptor = clientAgentList[request.agentName]
        var fullDescriptor: P2PDescriptor? = null //Declare now. Assign later when we know we have a match.


        /**
         * Agent is not registered as a remote agent. So now we need to try to track it down in the agents that this
         * instance of TPipe has running and are registered.
         */
        if(agentDescriptor == null)
        {
            val tpipeAgents = mutableListOf<P2PDescriptor>()

            /**Find all local TPipe agents that are responding to internal TPipe calls.
             * Remote agents are likely being hosted as a service and should be ignored because they are designed
             * to listen or respond to an external call and should not be doing double duty as a local agent which
             * is an antipattern to agent registry design.
             */
            for(it in Agents)
            {
                val transport = it.key
                val method = transport.transportMethod

                if(method == Transport.Tpipe)
                {
                    tpipeAgents.add(it.value.descriptor)
                }
            }

            /**
             * Search for the first agent whose name matches the agent name in this request body.
             * The first one located will be the one we route the request to.
             */
            for(descriptor in tpipeAgents)
            {
                if(descriptor.agentName == request.agentName)
                {
                    fullDescriptor = descriptor //Assign here and break.
                    break
                }
            }

            /**
             * We can only proceed if we actually found a given agent. If we didn't we need generate an error object
             * and return the result now.
             */
            if(fullDescriptor != null) //Removes the need for ? or !! constantly.
            {
                //Declare full request object first.
                var fullRequest: P2PRequest? = null

                fullRequest = buildRequestForAgent(
                    request = request,
                    descriptor = fullDescriptor,
                    template = template,
                    returnAddressOverride = returnAddressOverride
                )

                /**
                 * Bind the auth values if they were supplied. This is important because the agent may have a
                 * different auth mechanism than the one used by TPipe. Also, if we're sending over http we may
                 * need a standard auth body to apply to the header.
                 */
                if(httpAuthBody.isNotEmpty()) fullRequest.transport.transportAuthBody = httpAuthBody
                if(p2pAuthBody.isNotEmpty()) fullRequest.authBody = p2pAuthBody

                //Attempt to execute and propagate the result back.
                return executeP2pRequest(fullRequest)
            }

            val failureResponse = P2PResponse()
            val rejection = P2PRejection()
            rejection.errorType = P2PError.transport
            rejection.reason = "Agent not found as remote or running under this Instance of TPipe"
            failureResponse.rejection = rejection

            return failureResponse //No agent was found so this call needs to be returned as a failure.

        }

        //Remote agent was valid, assign now to complete the search for our agent.
        fullDescriptor = agentDescriptor
        var fullRequest: P2PRequest? = null

        fullRequest = buildRequestForAgent(
            request = request,
            descriptor = fullDescriptor,
            template = template,
            returnAddressOverride = returnAddressOverride
        )

        /**
         * Bind the auth values if they were supplied or can be resolved.
         */
        val resolvedHttpAuth = httpAuthBody.ifEmpty { AuthRegistry.getToken(fullRequest.transport.transportAddress) }
        val resolvedP2pAuth = p2pAuthBody.ifEmpty { AuthRegistry.getToken(request.agentName) }

        if(resolvedHttpAuth.isNotEmpty()) fullRequest.transport.transportAuthBody = resolvedHttpAuth
        if(resolvedP2pAuth.isNotEmpty()) fullRequest.authBody = resolvedP2pAuth

        return externalP2PCall(fullRequest)
    }

    /**
     * Build the full request for an agent using the normal template precedence, then layer a caller return path on top
     * without replacing worker-owned request-template fields.
     */
    private fun buildRequestForAgent(
        request: AgentRequest,
        descriptor: P2PDescriptor,
        template: P2PRequest? = null,
        returnAddressOverride: P2PTransport? = null
    ) : P2PRequest
    {
        val baseRequest = when
        {
            template != null -> request.buildP2PRequest(template)
            descriptor.requestTemplate != null -> request.buildP2PRequest(descriptor.requestTemplate)
            else -> request.buildRequestFromRegistry(descriptor.agentName)
        }

        val fullRequest = baseRequest.deepCopy<P2PRequest>()

        if(fullRequest.returnAddress.transportAddress.isEmpty() && returnAddressOverride != null)
        {
            fullRequest.returnAddress = returnAddressOverride
        }

        return fullRequest
    }

    /**
     * External caller to make a remote P2P agent call outside TPipe to some external system.
     * @param request The P2PRequest object containing all necessary details for the call.
     * @return The P2PResponse object containing the output or rejection details.
     */
    suspend fun externalP2PCall(request: P2PRequest) : P2PResponse
    {

        when(request.transport.transportMethod)
        {
            Transport.Http ->
            {
                if(request.transport.transportAuthBody.isEmpty())
                {
                    request.transport.transportAuthBody = AuthRegistry.getToken(request.transport.transportAddress)
                }

                val jsonPayload = serialize(request)
                val requestHeaders = mutableMapOf("Content-Type" to "application/json")
                if(request.transport.transportAuthBody.isNotEmpty())
                {
                    requestHeaders["Authorization"] = request.transport.transportAuthBody
                }

                val httpResponseData = httpRequest(
                    url = request.transport.transportAddress,
                    method = "POST",
                    body = jsonPayload,
                    headers = requestHeaders
                )

                if(httpResponseData.success)
                {
                    return deserialize<P2PResponse>(httpResponseData.body) ?: P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Failed to deserialize P2PResponse from external host")
                    }
                }
                else
                {
                    return P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "HTTP request failed with status ${httpResponseData.statusCode}: ${httpResponseData.statusMessage}")
                    }
                }
            }
            Transport.Stdio ->
            {
                if(request.transport.transportAuthBody.isEmpty())
                {
                    request.transport.transportAuthBody = AuthRegistry.getToken(request.transport.transportAddress)
                }

                val executionMode = request.pcpRequest?.stdioContextOptions?.executionMode ?: StdioExecutionMode.ONE_SHOT
                val jsonPayload = serialize(request)

                val sessionResponse = try
                {
                    when(executionMode)
                    {
                        StdioExecutionMode.ONE_SHOT ->
                        {
                            val commandList = splitProgramString(request.transport.transportAddress)
                            val session = StdioSessionManager.createSession(
                                command = commandList[0],
                                args = commandList.drop(1),
                                ownerId = "p2p_caller"
                            )
                            try
                            {
                                StdioSessionManager.sendInput(session.sessionId, jsonPayload)
                            }
                            finally
                            {
                                StdioSessionManager.closeSession(session.sessionId)
                            }
                        }
                        StdioExecutionMode.INTERACTIVE ->
                        {
                            val commandList = splitProgramString(request.transport.transportAddress)
                            val session = StdioSessionManager.createSession(
                                command = commandList[0],
                                args = commandList.drop(1),
                                ownerId = "p2p_caller"
                            )
                            StdioSessionManager.sendInput(session.sessionId, jsonPayload)
                        }
                        StdioExecutionMode.CONNECT ->
                        {
                            val existingSessionId = request.pcpRequest?.stdioContextOptions?.sessionId
                                ?: throw IllegalArgumentException("Session ID required for CONNECT mode")
                            StdioSessionManager.sendInput(existingSessionId, jsonPayload)
                        }
                        else -> throw IllegalArgumentException("Unsupported stdio execution mode: $executionMode")
                    }
                }
                catch(e: Exception)
                {
                    SessionResponse("", "Stdio execution failed: ${e.message}", false)
                }

                if(sessionResponse.error.isEmpty())
                {
                    return deserialize<P2PResponse>(sessionResponse.output) ?: P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Failed to deserialize P2PResponse from session output")
                    }
                }
                else
                {
                    return P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Session communication error: ${sessionResponse.error}")
                    }
                }
            }
            Transport.Tpipe -> return executeP2pRequest(request)
            Transport.Python -> throw IllegalArgumentException("python is not supported in p2p.")
            Transport.Kotlin -> throw IllegalArgumentException("kotlin is not supported in p2p.")
            Transport.JavaScript -> throw IllegalArgumentException("javascript is not supported in p2p.")
            Transport.Unknown -> throw IllegalArgumentException("unknown transport type.")
            Transport.Auto -> throw IllegalArgumentException("auto transport not supported in p2p.")
        }

    }

    private fun reconcileTrustedSourceLocked(
        source: P2PTrustedRegistrySource,
        acceptedListings: List<P2PHostedRegistryListing>
    )
    {
        val existingRecords = trustedImportedAgentsBySourceId.getOrPut(source.sourceId) { linkedMapOf() }
        val desiredByListingId = acceptedListings.associateBy { it.listingId }

        val staleListingIds = existingRecords.keys.filter { it !in desiredByListingId.keys }
        staleListingIds.forEach { listingId ->
            val staleRecord = existingRecords.remove(listingId) ?: return@forEach
            trustedImportedSourceIdByAgentName.remove(staleRecord.agentName)
            clientAgentList.remove(staleRecord.agentName)
            requestTemplates.remove(staleRecord.agentName)
        }

        acceptedListings.forEach { listing ->
            val descriptor = listing.publicDescriptor?.deepCopy<P2PDescriptor>() ?: return@forEach
            val agentName = descriptor.agentName
            val listingId = listing.listingId
            val existingRecord = existingRecords[listingId]

            if(existingRecord != null && existingRecord.agentName == agentName)
            {
                clientAgentList[agentName] = descriptor
                requestTemplates[agentName] = descriptor.requestTemplate?.deepCopy<P2PRequest>() ?: P2PRequest()
                return@forEach
            }

            val importedOwner = trustedImportedSourceIdByAgentName[agentName]
            when
            {
                importedOwner != null && importedOwner != source.sourceId ->
                {
                    recordTrustedImportCollisionLocked(
                        sourceId = source.sourceId,
                        listingId = listingId,
                        agentName = agentName,
                        reason = "Agent name '$agentName' is already imported from trusted source '$importedOwner'."
                    )
                    return@forEach
                }

                importedOwner == null && clientAgentList.containsKey(agentName) ->
                {
                    recordTrustedImportCollisionLocked(
                        sourceId = source.sourceId,
                        listingId = listingId,
                        agentName = agentName,
                        reason = "Agent name '$agentName' already exists in the local client catalog."
                    )
                    return@forEach
                }

                existingRecords.values.any { it.agentName == agentName && it.listingId != listingId } ->
                {
                    recordTrustedImportCollisionLocked(
                        sourceId = source.sourceId,
                        listingId = listingId,
                        agentName = agentName,
                        reason = "Trusted source '${source.sourceId}' returned duplicate agent name '$agentName' under a different listing id."
                    )
                    return@forEach
                }
            }

            clientAgentList[agentName] = descriptor
            requestTemplates[agentName] = descriptor.requestTemplate?.deepCopy<P2PRequest>() ?: P2PRequest()
            existingRecords[listingId] = P2PTrustedImportedAgentRecord(
                listingId = listingId,
                agentName = agentName,
                importedAtEpochMillis = System.currentTimeMillis(),
                attestationRef = listing.attestationRef.ifBlank {
                    listing.gridNodeAdvertisement?.attestationRef
                        ?: listing.gridRegistryAdvertisement?.attestationRef
                        ?: ""
                },
                sourceLabel = source.admissionPolicy?.sourceLabel.orEmpty()
            )
            trustedImportedSourceIdByAgentName[agentName] = source.sourceId
        }

        if(existingRecords.isEmpty())
        {
            trustedImportedAgentsBySourceId.remove(source.sourceId)
        }
    }

    private fun removeImportedAgentsLocked(sourceId: String)
    {
        val importedRecords = trustedImportedAgentsBySourceId.remove(sourceId).orEmpty()
        importedRecords.values.forEach { record ->
            trustedImportedSourceIdByAgentName.remove(record.agentName)
            clientAgentList.remove(record.agentName)
            requestTemplates.remove(record.agentName)
        }
    }

    private fun recordTrustedImportCollisionLocked(
        sourceId: String,
        listingId: String,
        agentName: String,
        reason: String
    )
    {
        trustedRegistryImportCollisions[Pair(sourceId, agentName)] =
            P2PTrustedRegistryImportCollision(
                sourceId = sourceId,
                listingId = listingId,
                agentName = agentName,
                reason = reason,
                recordedAtEpochMillis = System.currentTimeMillis()
            )
    }

    private fun evaluateTrustedAdmissionPolicy(
        source: P2PTrustedRegistrySource,
        listing: P2PHostedRegistryListing
    ): String?
    {
        val policy = source.admissionPolicy ?: return null
        if(policy.requireActiveModerationState && listing.moderationState != P2PHostedModerationState.ACTIVE)
        {
            return "Listing '${listing.listingId}' is not active."
        }

        if(policy.requireVerificationEvidence)
        {
            val hasEvidence = listing.attestationRef.isNotBlank() ||
                listing.gridNodeAdvertisement?.attestationRef?.isNotBlank() == true ||
                listing.gridRegistryAdvertisement?.attestationRef?.isNotBlank() == true
            if(!hasEvidence)
            {
                return "Listing '${listing.listingId}' is missing required verification evidence."
            }
        }

        if(policy.minimumRemainingLeaseMillis > 0L)
        {
            val expiresAt = listing.lease?.expiresAtEpochMillis ?: 0L
            if(expiresAt <= 0L || (expiresAt - System.currentTimeMillis()) < policy.minimumRemainingLeaseMillis)
            {
                return "Listing '${listing.listingId}' does not satisfy the minimum remaining lease threshold."
            }
        }

        return null
    }

}
