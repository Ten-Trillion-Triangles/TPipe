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
import io.ktor.client.request.headers
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.collect
import java.util.UUID

/** HTTP client configuration for an AgentCore Runtime endpoint. */
data class AgentCoreRuntimeClientConfig(
    val endpoint: String,
    val invocationPath: String = "/invocations",
    val requestHeaders: Map<String, String> = emptyMap(),
    val sessionHeader: String = "x-amzn-bedrock-agentcore-runtime-session-id",
    val runtimeArn: String? = null,
    val qualifier: String? = null
)

/** Client for the AgentCore Runtime `/invocations` contract. */
class AgentCoreRuntimeClient(
    private val config: AgentCoreRuntimeClientConfig,
    httpClient: HttpClient? = null,
    private val dataClient: AgentCoreRuntimeDataClient? = null
) : AutoCloseable {
    private val ownsHttpClient = httpClient == null
    private val httpClient = httpClient ?: HttpClient()

    /** Create a client that can use both the local HTTP contract and the pinned SDK. */
    constructor(
        config: AgentCoreRuntimeClientConfig,
        clients: AgentCoreClients,
        httpClient: HttpClient? = null
    ) : this(config, httpClient, AwsAgentCoreRuntimeDataClient(clients.data))

    /** Invoke the runtime and decode its JSON response. */
    suspend fun invoke(input: String, sessionId: String? = null): AgentCoreInvocationResponse {
        return invoke(AgentCoreInvocationRequest(input = input, sessionId = sessionId))
    }

    /** Invoke the runtime with the canonical prompt/content request model. */
    suspend fun invoke(request: AgentCoreInvocationRequest): AgentCoreInvocationResponse {
        require(!request.stream) { "Non-streaming runtime invocation cannot set stream=true." }
        val response = httpClient.post(url()) {
            contentType(ContentType.Application.Json)
            headers { config.requestHeaders.forEach { (name, value) -> append(name, value) } }
            request.sessionId?.let { header(config.sessionHeader, it) }
            setBody(AgentCoreRuntimeJson.encodeRequest(request))
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("AgentCore Runtime returned ${response.status}: $body")
        }
        return AgentCoreRuntimeJson.decodeResponse(body).copy(
            sessionId = response.headers[config.sessionHeader] ?: request.sessionId.orEmpty()
        )
    }

    /** Invoke the runtime in streaming mode and forward each SSE text event. */
    suspend fun invokeStreaming(
        input: String,
        sessionId: String? = null,
        onChunk: suspend (String) -> Unit
    ): AgentCoreInvocationResponse {
        return invokeStreaming(
            AgentCoreInvocationRequest(input = input, sessionId = sessionId, stream = true),
            onChunk
        )
    }

    /** Invoke the canonical request model and consume the SSE body incrementally. */
    suspend fun invokeStreaming(
        request: AgentCoreInvocationRequest,
        onChunk: suspend (String) -> Unit
    ): AgentCoreInvocationResponse {
        require(request.stream) { "Streaming runtime requests must set stream=true." }
        return httpClient.preparePost(url()) {
            contentType(ContentType.Application.Json)
            headers {
                append("Accept", "text/event-stream")
                config.requestHeaders.forEach { (name, value) -> append(name, value) }
                request.sessionId?.let { header(config.sessionHeader, it) }
            }
            setBody(AgentCoreRuntimeJson.encodeRequest(request))
        }.execute { response ->
            if (!response.status.isSuccess()) {
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
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue

                val eventJson = line.removePrefix("data:").trimStart()
                val event = AgentCoreRuntimeJson.decodeStreamEvent(eventJson)
                event.sessionId?.let { resolvedSessionId = it }
                event.error?.let { error ->
                    throw IllegalStateException("AgentCore Runtime invocation failed: $error")
                }
                if (event.text.isNotEmpty()) {
                    output.append(event.text)
                    onChunk(event.text)
                }
                if (event.done) {
                    receivedTerminalEvent = true
                    break
                }
            }
            if (!receivedTerminalEvent) {
                throw IllegalStateException("AgentCore Runtime SSE stream ended before a terminal event.")
            }
            AgentCoreInvocationResponse(
                output = output.toString(),
                sessionId = resolvedSessionId,
                streamed = true
            )
        }
    }

    /** Invoke AgentCore's pinned SDK data-plane operation directly. */
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
    ): InvokeAgentRuntimeResponse {
        val response = invoke(request)
        response.response?.toFlow()?.collect { chunk -> onChunk(chunk) }
        return response
    }

    /** Stop a pinned SDK runtime session when this client was SDK-configured. */
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
    override fun close() {
        if (ownsHttpClient) httpClient.close()
    }

    private fun url(): String = config.endpoint.trimEnd('/') + config.invocationPath
}

/** Narrow SDK seam that keeps runtime client tests independent of AWS. */
interface AgentCoreRuntimeDataClient {
    suspend fun invoke(request: InvokeAgentRuntimeRequest): InvokeAgentRuntimeResponse
    suspend fun stop(request: StopRuntimeSessionRequest): StopRuntimeSessionResponse
}

private class AwsAgentCoreRuntimeDataClient(
    private val delegate: aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
) : AgentCoreRuntimeDataClient {
    override suspend fun invoke(request: InvokeAgentRuntimeRequest): InvokeAgentRuntimeResponse =
        requireNotNull(captureInvokeResponse(request))

    private suspend fun captureInvokeResponse(request: InvokeAgentRuntimeRequest): InvokeAgentRuntimeResponse? {
        var response: InvokeAgentRuntimeResponse? = null
        delegate.invokeAgentRuntime(request) { result ->
            response = result
        }
        return response
    }

    override suspend fun stop(request: StopRuntimeSessionRequest): StopRuntimeSessionResponse =
        delegate.stopRuntimeSession(request)
}

/** A P2P adapter that routes generic TPipe requests to AgentCore Runtime. */
class AgentCoreRuntimeAgent(
    private val client: AgentCoreRuntimeClient,
    agentName: String = "agentcore-runtime"
) : P2PInterface {
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
    private var callback: (suspend (String) -> Unit)? = null

    override var killSwitch: com.TTT.P2P.KillSwitch? = null

    override fun setP2pDescription(description: P2PDescriptor) {
        descriptor = description
    }

    override fun getP2pDescription(): P2PDescriptor = descriptor

    override fun setP2pTransport(transport: P2PTransport) {
        descriptor.transport = transport
    }

    override fun getP2pTransport(): P2PTransport = descriptor.transport

    override fun setStreamingCallbackRecursive(callback: suspend (String) -> Unit) {
        this.callback = callback
    }

    override fun clearStreamingCallbacksRecursive() {
        callback = null
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse {
        return try {
            val input = request.prompt.text
            val invocation = callback?.let { sink ->
                client.invokeStreaming(input, onChunk = sink)
            } ?: client.invoke(input)
            P2PResponse(
                output = invocation.outputContent ?: invocation.output.let { value -> MultimodalContent(text = value) }
            )
        } finally {
            // A runtime adapter may be reused directly, outside the HTTP host.
            // Do not retain a caller-owned callback across requests.
            clearStreamingCallbacksRecursive()
        }
    }
}
