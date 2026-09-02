package com.TTT.AgentCore.tools

import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.*
import com.TTT.AgentCore.AgentCoreClients
import com.TTT.AgentCore.runtime.AgentCoreSessionContext
import com.TTT.AgentCore.runtime.AgentCoreSessionRegistry
import com.TTT.PipeContextProtocol.DynamicFunctionHandler
import com.TTT.PipeContextProtocol.FunctionSignature
import com.TTT.PipeContextProtocol.ParamType
import com.TTT.PipeContextProtocol.ParameterInfo
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.ReturnTypeInfo
import com.TTT.PipeContextProtocol.bindDynamicFunction
import kotlinx.coroutines.CancellationException

/** A Browser session owned by one TPipe/AgentCore runtime session. */
data class AgentCoreBrowserSession(
    val sessionId: String,
    val ownerSessionId: String,
    val browserIdentifier: String
)

/** Explicit Browser data-plane client with session lifecycle operations. */
class AgentCoreBrowserClient(
    private val client: BedrockAgentCoreClient,
    private val sessionRegistry: AgentCoreSessionRegistry? = null
) {
    private data class OwnedSession(val ownerSessionId: String, val browserIdentifier: String)

    private val ownedSessions = java.util.concurrent.ConcurrentHashMap<String, OwnedSession>()

    /** Start a Browser session and record its TPipe owner. */
    suspend fun startSession(
        request: StartBrowserSessionRequest,
        ownerSessionId: String
    ): AgentCoreBrowserSession {
        require(ownerSessionId.isNotBlank()) { "A Browser session owner id is required." }
        val response = client.startBrowserSession(request)
        val sessionId = requireNotNull(response.sessionId) { "AgentCore Browser did not return a session id." }
        val browserIdentifier = requireNotNull(request.browserIdentifier)
        ownedSessions[sessionId] = OwnedSession(ownerSessionId, browserIdentifier)
        sessionRegistry?.registerSessionCleanup(
            sessionId = ownerSessionId,
            key = "agentcore-browser"
        ) {
            stopOwnedSessions(ownerSessionId)
        }
        return AgentCoreBrowserSession(sessionId, ownerSessionId, browserIdentifier)
    }

    /** Look up an existing Browser session. */
    suspend fun getSession(request: GetBrowserSessionRequest): GetBrowserSessionResponse =
        client.getBrowserSession(request)

    /** Look up a Browser session only when it belongs to [ownerSessionId]. */
    suspend fun getSession(
        request: GetBrowserSessionRequest,
        ownerSessionId: String
    ): GetBrowserSessionResponse {
        requireOwner(request.sessionId, ownerSessionId)
        return getSession(request)
    }

    /** Invoke one Browser action. */
    suspend fun invoke(request: InvokeBrowserRequest): InvokeBrowserResponse = client.invokeBrowser(request)

    /** Invoke one Browser action only for its owning TPipe session. */
    suspend fun invoke(
        request: InvokeBrowserRequest,
        ownerSessionId: String
    ): InvokeBrowserResponse {
        requireOwner(request.sessionId, ownerSessionId)
        return invoke(request)
    }

    /** Update a Browser stream/live-view configuration. */
    suspend fun updateStream(request: UpdateBrowserStreamRequest): UpdateBrowserStreamResponse =
        client.updateBrowserStream(request)

    /** Update a Browser stream only for its owning TPipe session. */
    suspend fun updateStream(
        request: UpdateBrowserStreamRequest,
        ownerSessionId: String
    ): UpdateBrowserStreamResponse {
        requireOwner(request.sessionId, ownerSessionId)
        return updateStream(request)
    }

    /** Save a Browser profile. */
    suspend fun saveProfile(request: SaveBrowserSessionProfileRequest): SaveBrowserSessionProfileResponse =
        client.saveBrowserSessionProfile(request)

    /** Save a Browser profile only for its owning TPipe session. */
    suspend fun saveProfile(
        request: SaveBrowserSessionProfileRequest,
        ownerSessionId: String
    ): SaveBrowserSessionProfileResponse {
        requireOwner(request.sessionId, ownerSessionId)
        return saveProfile(request)
    }

    /** Stop a Browser session. */
    suspend fun stopSession(request: StopBrowserSessionRequest): StopBrowserSessionResponse =
        client.stopBrowserSession(request).also { ownedSessions.remove(request.sessionId) }

    /** Stop a Browser session only for its owning TPipe session. */
    suspend fun stopSession(
        request: StopBrowserSessionRequest,
        ownerSessionId: String
    ): StopBrowserSessionResponse {
        requireOwner(request.sessionId, ownerSessionId)
        return stopSession(request)
    }

    /** Stop every Browser session owned by one TPipe runtime session. */
    suspend fun stopOwnedSessions(ownerSessionId: String): List<StopBrowserSessionResponse> {
        val sessions = ownedSessions.entries
            .filter { it.value.ownerSessionId == ownerSessionId }
            .map { (sessionId, owned) ->
                StopBrowserSessionRequest {
                    browserIdentifier = owned.browserIdentifier
                    this.sessionId = sessionId
                }
            }
        val responses = mutableListOf<StopBrowserSessionResponse>()
        sessions.forEach { request ->
            try
            {
                responses += stopSession(request, ownerSessionId)
            }
            catch(exception: CancellationException)
            {
                throw exception
            }
            catch(_: Exception)
            {
                // Cleanup is best effort so one failed AWS stop does not leak
                // every other session owned by this runtime root.
            }
        }
        return responses
    }

    /** Execute an SDK operation not yet wrapped by this facade. */
    suspend fun <T> execute(block: suspend BedrockAgentCoreClient.() -> T): T = client.block()

    private fun requireOwner(sessionId: String?, ownerSessionId: String) {
        require(!sessionId.isNullOrBlank()) { "AgentCore Browser session id is required." }
        check(ownedSessions[sessionId]?.ownerSessionId == ownerSessionId) {
            "AgentCore Browser session '$sessionId' is not owned by '$ownerSessionId'."
        }
    }
}

/** Code Interpreter session owned by one TPipe/AgentCore runtime session. */
data class AgentCoreCodeInterpreterSession(
    val sessionId: String,
    val ownerSessionId: String,
    val codeInterpreterIdentifier: String
)

/** Explicit Code Interpreter data-plane client with session lifecycle operations. */
class AgentCoreCodeInterpreterClient(
    private val client: BedrockAgentCoreClient,
    private val sessionRegistry: AgentCoreSessionRegistry? = null
) {
    private data class OwnedSession(val ownerSessionId: String, val codeInterpreterIdentifier: String)

    private val ownedSessions = java.util.concurrent.ConcurrentHashMap<String, OwnedSession>()

    /** Start a Code Interpreter session and record its TPipe owner. */
    suspend fun startSession(
        request: StartCodeInterpreterSessionRequest,
        ownerSessionId: String
    ): AgentCoreCodeInterpreterSession {
        require(ownerSessionId.isNotBlank()) { "A Code Interpreter session owner id is required." }
        val response = client.startCodeInterpreterSession(request)
        val sessionId = requireNotNull(response.sessionId) {
            "AgentCore Code Interpreter did not return a session id."
        }
        val codeInterpreterIdentifier = requireNotNull(request.codeInterpreterIdentifier)
        ownedSessions[sessionId] = OwnedSession(ownerSessionId, codeInterpreterIdentifier)
        sessionRegistry?.registerSessionCleanup(
            sessionId = ownerSessionId,
            key = "agentcore-code-interpreter"
        ) {
            stopOwnedSessions(ownerSessionId)
        }
        return AgentCoreCodeInterpreterSession(
            sessionId,
            ownerSessionId,
            codeInterpreterIdentifier
        )
    }

    /** Look up an existing Code Interpreter session. */
    suspend fun getSession(request: GetCodeInterpreterSessionRequest): GetCodeInterpreterSessionResponse =
        client.getCodeInterpreterSession(request)

    /** Look up a Code Interpreter session only for its owning TPipe session. */
    suspend fun getSession(
        request: GetCodeInterpreterSessionRequest,
        ownerSessionId: String
    ): GetCodeInterpreterSessionResponse {
        requireOwner(request.sessionId, ownerSessionId)
        return getSession(request)
    }

    /** Invoke a Code Interpreter operation while consuming its streaming response. */
    suspend fun <T> invoke(
        request: InvokeCodeInterpreterRequest,
        handler: suspend (InvokeCodeInterpreterResponse) -> T
    ): T = client.invokeCodeInterpreter(request, handler)

    /** Invoke Code Interpreter only for its owning TPipe session. */
    suspend fun <T> invoke(
        request: InvokeCodeInterpreterRequest,
        ownerSessionId: String,
        handler: suspend (InvokeCodeInterpreterResponse) -> T
    ): T {
        requireOwner(request.sessionId, ownerSessionId)
        return invoke(request, handler)
    }

    /** Stop a Code Interpreter session. */
    suspend fun stopSession(request: StopCodeInterpreterSessionRequest): StopCodeInterpreterSessionResponse =
        client.stopCodeInterpreterSession(request).also { ownedSessions.remove(request.sessionId) }

    /** Stop a Code Interpreter session only for its owning TPipe session. */
    suspend fun stopSession(
        request: StopCodeInterpreterSessionRequest,
        ownerSessionId: String
    ): StopCodeInterpreterSessionResponse {
        requireOwner(request.sessionId, ownerSessionId)
        return stopSession(request)
    }

    /** Stop every Code Interpreter session owned by one TPipe runtime session. */
    suspend fun stopOwnedSessions(ownerSessionId: String): List<StopCodeInterpreterSessionResponse> {
        val sessions = ownedSessions.entries
            .filter { it.value.ownerSessionId == ownerSessionId }
            .map { (sessionId, owned) ->
                StopCodeInterpreterSessionRequest {
                    codeInterpreterIdentifier = owned.codeInterpreterIdentifier
                    this.sessionId = sessionId
                }
            }
        val responses = mutableListOf<StopCodeInterpreterSessionResponse>()
        sessions.forEach { request ->
            try
            {
                responses += stopSession(request, ownerSessionId)
            }
            catch(exception: CancellationException)
            {
                throw exception
            }
            catch(_: Exception)
            {
                // Cleanup is best effort so one failed AWS stop does not leak
                // every other session owned by this runtime root.
            }
        }
        return responses
    }

    /** Execute an SDK operation not yet wrapped by this facade. */
    suspend fun <T> execute(block: suspend BedrockAgentCoreClient.() -> T): T = client.block()

    private fun requireOwner(sessionId: String?, ownerSessionId: String) {
        require(!sessionId.isNullOrBlank()) { "AgentCore Code Interpreter session id is required." }
        check(ownedSessions[sessionId]?.ownerSessionId == ownerSessionId) {
            "AgentCore Code Interpreter session '$sessionId' is not owned by '$ownerSessionId'."
        }
    }
}

/** Optional Browser PCP registration settings. */
data class AgentCoreBrowserToolsConfig(
    val namespace: String = "agentcore_browser__",
    val enabledActions: Set<String> = emptySet(),
    val invoke: (suspend (String, Map<String, String>) -> Any?)? = null
)

/** Optional Code Interpreter PCP registration settings. */
data class AgentCoreCodeInterpreterToolsConfig(
    val namespace: String = "agentcore_code_interpreter__",
    val enabledActions: Set<String> = emptySet(),
    val invoke: (suspend (String, Map<String, String>) -> Any?)? = null
)

/** Explicitly register caller-backed Browser capabilities as PCP functions. */
object AgentCoreBrowserTools {
    /** Register only enabled actions and never execute client-defined tools implicitly. */
    fun register(pcpContext: PcpContext, config: AgentCoreBrowserToolsConfig): PcpContext =
        registerActions(pcpContext, config.namespace, config.enabledActions, config.invoke)
}

/** Explicitly register caller-backed Code Interpreter capabilities as PCP functions. */
object AgentCoreCodeInterpreterTools {
    /** Register only enabled actions and never execute client-defined tools implicitly. */
    fun register(pcpContext: PcpContext, config: AgentCoreCodeInterpreterToolsConfig): PcpContext =
        registerActions(pcpContext, config.namespace, config.enabledActions, config.invoke)
}

private fun registerActions(
    context: PcpContext,
    namespace: String,
    actions: Set<String>,
    invoke: (suspend (String, Map<String, String>) -> Any?)?
): PcpContext {
    val handlerFactory = invoke ?: return context
    actions.forEach { action ->
        val name = namespace + action
        val signature = FunctionSignature(
            name = name,
            parameters = listOf(
                ParameterInfo(
                    name = "arguments",
                    type = ParamType.Map,
                    kotlinType = "kotlin.collections.Map<kotlin.String, kotlin.Any?>",
                    isOptional = true,
                    description = "JSON object arguments for the explicitly enabled AgentCore capability."
                )
            ),
            returnType = ReturnTypeInfo(ParamType.Any, "kotlin.Any?", isNullable = true),
            description = "Explicitly enabled AgentCore capability: $action"
        )
        val handler: DynamicFunctionHandler = { args -> handlerFactory(action, args) }
        context.bindDynamicFunction(name, signature, handler)
    }
    return context
}

/** Construct an explicit Browser client from shared AgentCore clients. */
fun AgentCoreClients.browser(
    sessionRegistry: AgentCoreSessionRegistry? = null
): AgentCoreBrowserClient = AgentCoreBrowserClient(data, sessionRegistry)

/** Construct an explicit Code Interpreter client. */
fun AgentCoreClients.codeInterpreter(
    sessionRegistry: AgentCoreSessionRegistry? = null
): AgentCoreCodeInterpreterClient = AgentCoreCodeInterpreterClient(data, sessionRegistry)

/**
 * Construct a Browser client bound to the runtime session being created.
 *
 * Passing the context's registry ensures Browser sessions started by the root
 * are stopped when the owning runtime session is evicted or the host closes.
 */
fun AgentCoreSessionContext.browserClient(
    clients: AgentCoreClients
): AgentCoreBrowserClient = clients.browser(sessionRegistry)

/**
 * Construct a Code Interpreter client bound to the runtime session being
 * created.
 */
fun AgentCoreSessionContext.codeInterpreterClient(
    clients: AgentCoreClients
): AgentCoreCodeInterpreterClient = clients.codeInterpreter(sessionRegistry)
