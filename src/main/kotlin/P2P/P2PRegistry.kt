package com.TTT.P2P

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
    var container: P2PInterface
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


//------------------------------------------------Functions-------------------------------------------------------------

    /**
     * Register an agent with the registry.
     *
     * @param agent the agent to be registered
     * @param transport the transport path to the agent
     * @param descriptor the agent descriptor
     * @param requirements the agent requirements
     */
    fun register(agent: P2PInterface, transport: P2PTransport, descriptor: P2PDescriptor, requirements: P2PRequirements)
    {
        Agents[transport] = P2PAgentListing(descriptor, requirements, agent)
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
            if(converseJson == null && converseHistoryJson == null) {
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
            if(returnPathIsLocal == null) {
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
                    if(tokenCount > requirements.maxTokens) {
                        val rejection = P2PRejection()
                        rejection.reason = "Request exceeds maximum token limit of ${requirements.maxTokens}"
                        rejection.errorType = P2PError.prompt
                        return Pair(false, rejection)
                    }
                }
            }
            catch (e: Exception)
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
                } catch (e: Exception) {
                    // If we can't get truncation settings, skip this validation
                }
            }
        }

        // Check accepted content types
        if (requirements.acceptedContent != null)
        {
            for (binaryContent in request.prompt.binaryContent)
            {
                val mimeType = binaryContent.getMimeType()
                val isSupported = requirements.acceptedContent!!.any { contentType ->
                    when (contentType.toString().lowercase())
                    {
                        "image" -> mimeType.startsWith("image/")
                        "document" -> mimeType.startsWith("application/") || mimeType.startsWith("text/")
                        "video" -> mimeType.startsWith("video/")
                        "audio" -> mimeType.startsWith("audio/")
                        else -> mimeType.contains(contentType.toString(), ignoreCase = true)
                    }
                }
                if (!isSupported) {
                    val rejection = P2PRejection()
                    rejection.reason = "Unsupported content type: $mimeType"
                    rejection.errorType = P2PError.content
                    return Pair(false, rejection)
                }
            }
        }

        if(requirements.authMechanism != null)
        {
            val result = requirements.authMechanism?.invoke(request.authBody)
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
        val agentHasMultiPage = if (pipe != null) {
            try {
                val truncationSettings = pipe.getTruncationSettings()
                truncationSettings.multiPageBudgetStrategy != null || 
                truncationSettings.pageWeights != null ||
                truncationSettings.fillMode
            } catch (e: Exception) {
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
        val (isValid, rejectionReason) = checkAgentRequirements(request, agent.requirements, agent.container)
        if(!isValid)
        {
            val response = P2PResponse()
            response.rejection = rejectionReason
            return response
        }

        val result: Deferred<P2PResponse> = kotlinx.coroutines.coroutineScope {
            async {
                agent.container.executeP2PRequest(request) ?: P2PResponse()
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
    suspend fun sendP2pRequest(request: AgentRequest, httpAuthBody: String = "", p2pAuthBody: String = "", template: P2PRequest? = null) : P2PResponse
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

                //Pull from provided template if not null.
                if(template != null)
                {
                    fullRequest = request.buildP2PRequest(template)
                }

                //Pull from its internal template if provided.
               else if(fullDescriptor.requestTemplate != null)
                {
                    fullRequest = request.buildP2PRequest(fullDescriptor.requestTemplate)
                }

                //Otherwise pull from the registry.
                else
                {
                    fullRequest = request.buildRequestFromRegistry(fullDescriptor.agentName)
                }

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

        //Build from supplied template if valid.
        if(template != null)
        {
            fullRequest = request.buildP2PRequest(template)
        }

        //Build from provided template if supplied.
      else if(fullDescriptor.requestTemplate != null)
        {
            fullRequest = request.buildP2PRequest(fullDescriptor.requestTemplate)
        }

        else
        {
            //Otherwise, attempt to build using the registry.
            fullRequest = request.buildRequestFromRegistry(request.agentName)
        }

        //todo: Only tpipe is supported as a transport. All others will throw for now.
        return externalP2PCall(fullRequest)
    }

    /**
     * External caller to make a remote P2P agent call outside TPipe to some external system.
     */
    suspend fun externalP2PCall(request: P2PRequest) : P2PResponse
    {

        when (request.transport.transportMethod)
        {
            Transport.Http -> {
                val jsonRequest = serialize(request)
                val headers = mutableMapOf("Content-Type" to "application/json")
                if (request.transport.transportAuthBody.isNotEmpty()) {
                    headers["Authorization"] = request.transport.transportAuthBody
                }

                val responseData = httpRequest(
                    url = request.transport.transportAddress,
                    method = "POST",
                    body = jsonRequest,
                    headers = headers
                )

                if (responseData.success) {
                    return deserialize<P2PResponse>(responseData.body) ?: P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Failed to deserialize P2PResponse from external host")
                    }
                } else {
                    return P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "HTTP request failed with status ${responseData.statusCode}: ${responseData.statusMessage}")
                    }
                }
            }
            Transport.Stdio -> {
                val executionMode = request.pcpRequest?.stdioContextOptions?.executionMode ?: StdioExecutionMode.ONE_SHOT
                val jsonRequest = serialize(request)

                val response = try {
                    when (executionMode) {
                        StdioExecutionMode.ONE_SHOT -> {
                            val command = splitProgramString(request.transport.transportAddress)
                            val session = StdioSessionManager.createSession(
                                command = command[0],
                                args = command.drop(1),
                                ownerId = "p2p_caller"
                            )
                            try {
                                StdioSessionManager.sendInput(session.sessionId, jsonRequest)
                            } finally {
                                StdioSessionManager.closeSession(session.sessionId)
                            }
                        }
                        StdioExecutionMode.INTERACTIVE -> {
                            val command = splitProgramString(request.transport.transportAddress)
                            val session = StdioSessionManager.createSession(
                                command = command[0],
                                args = command.drop(1),
                                ownerId = "p2p_caller"
                            )
                            StdioSessionManager.sendInput(session.sessionId, jsonRequest)
                        }
                        StdioExecutionMode.CONNECT -> {
                            val sessionId = request.pcpRequest?.stdioContextOptions?.sessionId
                                ?: throw IllegalArgumentException("Session ID required for CONNECT mode")
                            StdioSessionManager.sendInput(sessionId, jsonRequest)
                        }
                        else -> throw IllegalArgumentException("Unsupported stdio execution mode: $executionMode")
                    }
                } catch (e: Exception) {
                    SessionResponse("", "Stdio execution failed: ${e.message}", false)
                }

                if (response.error.isEmpty()) {
                    return deserialize<P2PResponse>(response.output) ?: P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Failed to deserialize P2PResponse from session output")
                    }
                } else {
                    return P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Session communication error: ${response.error}")
                    }
                }
            }
            Transport.Tpipe -> return executeP2pRequest(request)
            Transport.Python -> throw IllegalArgumentException("python is not supported in p2p.")
            Transport.Unknown -> throw IllegalArgumentException("unknown transport type.")
            Transport.Auto -> throw IllegalArgumentException("auto transport not supported in p2p.")
        }

    }


}
