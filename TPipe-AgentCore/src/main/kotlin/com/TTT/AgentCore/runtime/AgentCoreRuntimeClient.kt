package com.TTT.AgentCore.runtime

import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeAgentRuntimeRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeAgentRuntimeResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.StopRuntimeSessionRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.StopRuntimeSessionResponse
import aws.smithy.kotlin.runtime.content.toFlow
import com.TTT.AgentCore.AgentCoreClients
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.headers
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.isWebsocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.collect
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID

/** Signs one outgoing AgentCore Runtime HTTP request. */
fun interface AgentCoreRuntimeRequestSigner
{
    /**
     * Return authentication headers for the exact request that will be sent.
     *
     * @param url Final request URL.
     * @param method HTTP method.
     * @param headers Request headers before signing.
     * @param body Exact request body bytes.
     * @return Headers to add to the request.
     */
    suspend fun sign(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray
    ): Map<String, String>
}

/**
 * HTTP client configuration for an AgentCore Runtime endpoint.
 *
 * @param endpoint Runtime endpoint.
 * @param invocationPath Runtime invocation path.
 * @param requestHeaders Static request headers.
 * @param sessionHeader Header carrying the runtime session identifier.
 * @param runtimeArn Optional runtime ARN for SDK session operations.
 * @param qualifier Optional runtime qualifier.
 * @param requestSigner Optional dynamic request signer, evaluated for every request.
 * @param websocketPath Runtime WebSocket invocation path.
 * @param pingPath Runtime health-check path.
 */
data class AgentCoreRuntimeClientConfig(
    val endpoint: String,
    val invocationPath: String = "/invocations",
    val requestHeaders: Map<String, String> = emptyMap(),
    val sessionHeader: String = "x-amzn-bedrock-agentcore-runtime-session-id",
    val runtimeArn: String? = null,
    val qualifier: String? = null,
    val requestSigner: AgentCoreRuntimeRequestSigner? = null,
    val websocketPath: String = "/ws",
    val pingPath: String = "/ping"
)

/** Client for the AgentCore Runtime `/invocations` contract.
 *
 * @param config Runtime endpoint and request settings.
 * @param httpClient Optional injected HTTP client.
 * @param dataClient Optional pinned SDK data-plane seam.
 */
class AgentCoreRuntimeClient(
    private val config: AgentCoreRuntimeClientConfig,
    httpClient: HttpClient? = null,
    private val dataClient: AgentCoreRuntimeDataClient? = null
) : AutoCloseable
{
    private val ownsHttpClient = httpClient == null
    private val sourceHttpClient = httpClient ?: HttpClient()
    private val sourceHasWebSocketsPlugin = sourceHttpClient.pluginOrNull(WebSockets) != null
    private val httpClient = if(config.requestSigner != null || !sourceHasWebSocketsPlugin)
    {
        sourceHttpClient.config {
            if(!sourceHasWebSocketsPlugin)
            {
                install(WebSockets)
            }
        }
    }
    else
    {
        sourceHttpClient
    }
    private val configuredHttpClient = httpClient !== sourceHttpClient

    init
    {
        config.requestSigner?.let {
            this@AgentCoreRuntimeClient.httpClient.plugin(HttpSend).intercept { request ->
                // The WebSocket handshake is signed explicitly below. A
                // second HttpSend signature can add a second Host header and
                // prevent Ktor's WebSocket capability from completing the
                // upgrade on AgentCore Runtime.
                if(!request.url.protocol.isWebsocket())
                {
                    applyDynamicAuthentication(request)
                }
                execute(request)
            }
        }
    }

    /** Create a client that can use both the local HTTP contract and the pinned SDK. */
    constructor(
        config: AgentCoreRuntimeClientConfig,
        clients: AgentCoreClients,
        httpClient: HttpClient? = null
    ) : this(config, httpClient, AwsAgentCoreRuntimeDataClient(clients.data))

    /** Invoke the runtime and decode its JSON response.
     *
     * @param input Text prompt.
     * @param sessionId Optional runtime session identifier.
     * @return Decoded invocation response.
     */
    suspend fun invoke(input: String, sessionId: String? = null): AgentCoreInvocationResponse
    {
        return invoke(AgentCoreInvocationRequest(input = input, sessionId = sessionId))
    }

    /** Invoke the runtime with the canonical prompt/content request model.
     *
     * @param request Invocation request.
     * @return Decoded invocation response.
     */
    suspend fun invoke(request: AgentCoreInvocationRequest): AgentCoreInvocationResponse
    {
        require(!request.stream) { "Non-streaming runtime invocation cannot set stream=true." }
        val response = httpClient.post(requestUrl(config.invocationPath)) {
            contentType(ContentType.Application.Json)
            headers { config.requestHeaders.forEach { (name, value) -> append(name, value) } }
            request.sessionId?.let { header(config.sessionHeader, it) }
            setBody(AgentCoreRuntimeJson.encodeRequest(request).toByteArray())
        }
        val body = response.bodyAsText()
        if(!response.status.isSuccess())
            {
                throw IllegalStateException("AgentCore Runtime returned ${response.status}: $body")
            }

        return AgentCoreRuntimeJson.decodeResponse(body).copy(
            sessionId = response.headers[config.sessionHeader] ?: request.sessionId.orEmpty()
        )
    }

    /** Read the AgentCore Runtime health endpoint. */
    suspend fun ping(): AgentCorePingResponse
    {
        val response = httpClient.get(requestUrl(config.pingPath)) {
            headers { config.requestHeaders.forEach { (name, value) -> append(name, value) } }
        }
        val body = response.bodyAsText()
        check(response.status.isSuccess()) {
            "AgentCore Runtime ping returned ${response.status}: $body"
        }
        return AgentCoreRuntimeJson.decodePing(body)
    }

    /** Send an intentionally raw JSON invocation for contract/error smoke tests. */
    suspend fun invokeRawJson(
        json: String,
        accept: String = ContentType.Application.Json.toString()
    ): AgentCoreRuntimeRawResponse
    {
        val response = httpClient.post(requestUrl(config.invocationPath)) {
            contentType(ContentType.Application.Json)
            header("Accept", accept)
            headers { config.requestHeaders.forEach { (name, value) -> append(name, value) } }
            setBody(json.toByteArray())
        }
        return AgentCoreRuntimeRawResponse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
            sessionId = response.headers[config.sessionHeader],
            requestId = response.headers["x-amzn-requestid"]
        )
    }

    /** Invoke the runtime in streaming mode and forward each SSE text event.
     *
     * @param input Text prompt.
     * @param sessionId Optional runtime session identifier.
     * @param onChunk Callback invoked for each non-empty text chunk.
     * @return Decoded streamed response.
     */
    suspend fun invokeStreaming(
        input: String,
        sessionId: String? = null,
        onChunk: suspend (String) -> Unit
    ): AgentCoreInvocationResponse
    {
        return invokeStreaming(
            AgentCoreInvocationRequest(input = input, sessionId = sessionId, stream = true),
            onChunk
        )
    }

    /** Invoke the canonical request model and consume the SSE body incrementally.
     *
     * @param request Streaming invocation request.
     * @param onChunk Callback invoked for each non-empty text chunk.
     * @return Decoded streamed response.
     */
    suspend fun invokeStreaming(
        request: AgentCoreInvocationRequest,
        onChunk: suspend (String) -> Unit
    ): AgentCoreInvocationResponse
    {
        require(request.stream) { "Streaming runtime requests must set stream=true." }
        return httpClient.preparePost(requestUrl(config.invocationPath)) {
            contentType(ContentType.Application.Json)
            headers {
                append("Accept", "text/event-stream")
                config.requestHeaders.forEach { (name, value) -> append(name, value) }
                request.sessionId?.let { header(config.sessionHeader, it) }
            }
            setBody(AgentCoreRuntimeJson.encodeRequest(request).toByteArray())
        }.execute { response ->
            if(!response.status.isSuccess())
            {
                throw IllegalStateException(
                    "AgentCore Runtime returned ${response.status}: ${response.bodyAsText()}"
                )
            }

            var resolvedSessionId = response.headers[config.sessionHeader]
                ?: request.sessionId
                ?: UUID.randomUUID().toString()
            val output = StringBuilder()
            var receivedTerminalEvent = false
            val channel = response.bodyAsChannel()
            while(true)
            {
                val line = channel.readUTF8Line() ?: break
                if(!line.startsWith("data:")) continue

                val eventJson = line.removePrefix("data:").trimStart()
                val event = AgentCoreRuntimeJson.decodeStreamEvent(eventJson)
                event.sessionId?.let { resolvedSessionId = it }
                event.error?.let { error ->
                    throw IllegalStateException("AgentCore Runtime invocation failed: $error")
                }
                if(event.text.isNotEmpty())
                {
                    output.append(event.text)
                    onChunk(event.text)
                }
                if(event.done)
                {
                    receivedTerminalEvent = true
                    break
                }
            }

            if(!receivedTerminalEvent)
            {
                throw IllegalStateException("AgentCore Runtime SSE stream ended before a terminal event.")
            }

            AgentCoreInvocationResponse(
                output = output.toString(),
                sessionId = resolvedSessionId,
                streamed = true
            )
        }
    }

    /** Invoke the runtime over its WebSocket contract and consume stream events. */
    suspend fun invokeWebSocket(
        input: String,
        sessionId: String? = null,
        onChunk: suspend (String) -> Unit = {}
    ): AgentCoreInvocationResponse
    {
        val resolvedSessionId = sessionId ?: UUID.randomUUID().toString()
        val output = StringBuilder()
        var completed = false
        val handshakeHeaders = buildMap {
            config.requestHeaders.forEach { (name, value) -> put(name, value) }
            put(config.sessionHeader, resolvedSessionId)
        }
        val signedHandshakeHeaders = config.requestSigner?.sign(
            url = requestUrl(config.websocketPath),
            method = "GET",
            headers = handshakeHeaders,
            body = ByteArray(0)
        ).orEmpty()
        httpClient.webSocket(
            urlString = requestUrl(config.websocketPath).replaceFirst(
                "https://",
                "wss://",
                ignoreCase = true
            ),
            request = {
                headers {
                    handshakeHeaders.forEach { (name, value) -> append(name, value) }
                    signedHandshakeHeaders.filterKeys { !it.equals("Host", ignoreCase = true) }.forEach { (name, value) ->
                        remove(name)
                        append(name, value)
                    }
                }
            }
        ) {
            send(
                Frame.Text(
                    AgentCoreRuntimeJson.encodeRequest(
                        AgentCoreInvocationRequest(
                            input = input,
                            sessionId = resolvedSessionId,
                            stream = true
                        )
                    )
                )
            )
            for(frame in incoming)
            {
                if(frame !is Frame.Text) continue
                val event = AgentCoreRuntimeJson.decodeStreamEvent(frame.readText())
                event.error?.let { throw IllegalStateException("AgentCore Runtime invocation failed: $it") }
                event.text.takeIf { it.isNotEmpty() }?.let {
                    output.append(it)
                    onChunk(it)
                }
                if(event.done)
                {
                    completed = true
                    break
                }
            }
        }
        check(completed) { "AgentCore Runtime WebSocket ended before a terminal event." }
        return AgentCoreInvocationResponse(
            output = output.toString(),
            sessionId = resolvedSessionId,
            streamed = true
        )
    }

    /** Invoke AgentCore's pinned SDK data-plane operation directly.
     *
     * @param request SDK invocation request.
     * @return SDK invocation response.
     */
    suspend fun invoke(request: InvokeAgentRuntimeRequest): InvokeAgentRuntimeResponse =
        checkNotNull(dataClient) {
            "This runtime client was not constructed with AgentCoreClients."
        }.invoke(request)

    /**
     * Consume the SDK response body incrementally while retaining the raw
     * response metadata for runtime/session correlation.
     */
    suspend fun invokeSdkStreaming(
        request: InvokeAgentRuntimeRequest,
        onChunk: suspend (ByteArray) -> Unit
    ): InvokeAgentRuntimeResponse
    {
        val response = invoke(request)
        response.response?.toFlow()?.collect { chunk -> onChunk(chunk) }
        return response
    }

    /** Stop a pinned SDK runtime session when this client was SDK-configured.
     *
     * @param sessionId Runtime session identifier.
     * @return SDK stop-session response.
     */
    suspend fun stopSession(sessionId: String): StopRuntimeSessionResponse =
        checkNotNull(dataClient) {
            "This runtime client was not constructed with AgentCoreClients."
        }.stop(
            StopRuntimeSessionRequest {
                agentRuntimeArn = checkNotNull(config.runtimeArn) {
                    "runtimeArn is required for stopSession()."
                }
                qualifier = config.qualifier
                runtimeSessionId = sessionId
            }
        )

    /** Close the owned HTTP client. */
    override fun close()
    {
        if(configuredHttpClient)
        {
            httpClient.close()
        }
        if(ownsHttpClient)
        {
            sourceHttpClient.close()
        }
    }

    private fun requestUrl(path: String): String = config.endpoint.trimEnd('/') + path

    private suspend fun applyDynamicAuthentication(request: io.ktor.client.request.HttpRequestBuilder)
    {
        val signer = config.requestSigner ?: return
        val body = when(val content = request.body)
        {
            is OutgoingContent.ByteArrayContent -> content.bytes()
            is OutgoingContent.NoContent -> ByteArray(0)
            else -> ByteArray(0)
        }
        val headers = buildMap {
            request.headers.entries().forEach { (name, values) ->
                put(name, values.joinToString(","))
            }
            (request.body as? OutgoingContent)?.contentType?.let {
                put("Content-Type", it.toString())
            }
        }
        signer.sign(
            url = request.url.buildString(),
            method = request.method.value,
            headers = headers,
            body = body
        ).forEach { (name, value) ->
            request.headers.remove(name)
            request.headers.append(name, value)
        }
    }
}

/** Narrow SDK seam that keeps runtime client tests independent of AWS. */
interface AgentCoreRuntimeDataClient {
    /**
     * Invoke the runtime through the SDK data plane.
     *
     * @param request SDK invocation request.
     * @return SDK invocation response.
     */
    suspend fun invoke(request: InvokeAgentRuntimeRequest): InvokeAgentRuntimeResponse

    /**
     * Stop a runtime session through the SDK data plane.
     *
     * @param request SDK stop-session request.
     * @return SDK stop-session response.
     */
    suspend fun stop(request: StopRuntimeSessionRequest): StopRuntimeSessionResponse
}

private class AwsAgentCoreRuntimeDataClient(
    private val delegate: aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
) : AgentCoreRuntimeDataClient
{
    override suspend fun invoke(request: InvokeAgentRuntimeRequest): InvokeAgentRuntimeResponse =
        requireNotNull(captureInvokeResponse(request))

    private suspend fun captureInvokeResponse(request: InvokeAgentRuntimeRequest): InvokeAgentRuntimeResponse?
    {
        var response: InvokeAgentRuntimeResponse? = null
        delegate.invokeAgentRuntime(request) { runtimeResponse ->
            response = runtimeResponse
        }
        return response
    }

    override suspend fun stop(request: StopRuntimeSessionRequest): StopRuntimeSessionResponse =
        delegate.stopRuntimeSession(request)
}

/** A P2P adapter that routes generic TPipe requests to AgentCore Runtime.
 *
 * @param client Runtime HTTP client.
 * @param agentName Name advertised in the P2P descriptor.
 */
class AgentCoreRuntimeAgent(
    private val client: AgentCoreRuntimeClient,
    agentName: String = "agentcore-runtime"
) : P2PInterface
{
    private var descriptor: P2PDescriptor = P2PDescriptor(
        agentName = agentName,
        agentDescription = "TPipe agent hosted by AgentCore Runtime",
        transport = P2PTransport(Transport.Http, "agentcore-runtime"),
        requiresAuth = false,
        usesConverse = false,
        allowsAgentDuplication = false,
        allowsCustomContext = true,
        allowsCustomAgentJson = false,
        recordsInteractionContext = false,
        recordsPromptContent = false,
        allowsExternalContext = true,
        contextProtocol = ContextProtocol.none,
        supportedContentTypes = mutableListOf(SupportedContentTypes.text)
    )
    private val callbacks = CopyOnWriteArrayList<suspend (String) -> Unit>()

    override var killSwitch: com.TTT.P2P.KillSwitch? = null

    override fun setP2pDescription(description: P2PDescriptor)
    {
        descriptor = description
    }

    override fun getP2pDescription(): P2PDescriptor = descriptor

    override fun setP2pTransport(transport: P2PTransport)
    {
        descriptor.transport = transport
    }

    override fun getP2pTransport(): P2PTransport = descriptor.transport

    override fun setStreamingCallbackRecursive(callback: suspend (String) -> Unit)
    {
        if (callbacks.none { it === callback }) callbacks.add(callback)
    }

    override fun removeStreamingCallbackRecursive(callback: suspend (String) -> Unit)
    {
        callbacks.removeIf { it === callback }
    }

    override fun clearStreamingCallbacksRecursive()
    {
        callbacks.clear()
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
    {
        val input = request.prompt.text
        val invocation = callbacks.takeIf { it.isNotEmpty() }?.let { sinks ->
            client.invokeStreaming(input, onChunk = { chunk ->
                sinks.forEach { sink -> sink(chunk) }
            })
        } ?: client.invoke(input)
        return P2PResponse(
            output = invocation.outputContent ?: invocation.output.let { value -> MultimodalContent(text = value) }
        )
    }
}
