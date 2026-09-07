package com.TTT.AgentCore.runtime

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.header
import io.ktor.http.URLBuilder
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Signed WSS client for the AgentCore Runtime interactive shell protocol. */
class AgentCoreRuntimeShellClient(
    private val config: AgentCoreRuntimeClientConfig,
    httpClient: HttpClient? = null
) : AutoCloseable
{
    private val ownsHttpClient = httpClient == null
    private val baseHttpClient = httpClient ?: HttpClient { install(WebSockets) }
    private val configuredHttpClient: HttpClient = if(baseHttpClient.pluginOrNull(WebSockets) == null)
    {
        baseHttpClient.config { install(WebSockets) }
    }
    else
    {
        baseHttpClient
    }
    private val ownsConfiguredHttpClient = configuredHttpClient !== baseHttpClient

    /** Open a shell for a Runtime session. */
    suspend fun open(
        runtimeArn: String = checkNotNull(config.runtimeArn) { "runtimeArn is required to open a shell." },
        runtimeSessionId: String,
        shellId: String? = null,
        qualifier: String? = config.qualifier,
        reconnectConfig: AgentCoreShellReconnectConfig = AgentCoreShellReconnectConfig()
    ): AgentCoreRuntimeShellSession
    {
        validateAgentCoreRuntimeSessionId(runtimeSessionId)
        shellId?.let(::validateAgentCoreShellId)
        val openSocket: suspend () -> DefaultClientWebSocketSession = {
            openSocket(runtimeArn, runtimeSessionId, shellId, qualifier)
        }
        return AgentCoreRuntimeShellSession(
            runtimeSessionId = runtimeSessionId,
            shellId = shellId,
            reconnectConfig = reconnectConfig,
            socket = openSocket(),
            reconnectSocket = openSocket
        )
    }

    /** Close the shell HTTP client when this factory owns it. */
    override fun close()
    {
        if(ownsConfiguredHttpClient) configuredHttpClient.close()
        else if(ownsHttpClient) baseHttpClient.close()
    }

    private suspend fun openSocket(
        runtimeArn: String,
        runtimeSessionId: String,
        shellId: String?,
        qualifier: String?
    ): DefaultClientWebSocketSession
    {
        val url = buildShellUrl(runtimeArn, runtimeSessionId, shellId, qualifier)
        val websocketUrl = toWebSocketUrl(url)
        val headers = buildMap {
            config.requestHeaders.forEach { (name, value) -> put(name, value) }
            put(config.sessionHeader, runtimeSessionId)
            shellId?.let { put("x-amzn-bedrock-agentcore-shell-id", it) }
        }
        val signed = config.requestSigner?.sign(
            url = url.replaceFirst("wss://", "https://", ignoreCase = true),
            method = "GET",
            headers = headers,
            body = ByteArray(0)
        ).orEmpty()
        return configuredHttpClient.webSocketSession(urlString = websocketUrl) {
            val handshakeHeaders = linkedMapOf<String, String>()
            fun addHeader(name: String, value: String)
            {
                handshakeHeaders.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let {
                    handshakeHeaders.remove(it)
                }
                if(!name.equals("Host", ignoreCase = true)) handshakeHeaders[name] = value
            }
            headers.forEach(::addHeader)
            signed.forEach(::addHeader)
            handshakeHeaders.forEach { (name, value) -> header(name, value) }
        }
    }

    /** Build the signed-handshake URL without opening a network connection. */
    internal fun buildShellUrl(
        runtimeArn: String,
        runtimeSessionId: String,
        shellId: String? = null,
        qualifier: String? = config.qualifier
    ): String
    {
        require(runtimeArn.isNotBlank()) { "runtimeArn must not be blank." }
        validateAgentCoreRuntimeSessionId(runtimeSessionId)
        shellId?.let(::validateAgentCoreShellId)
        val path = "/runtimes/${urlEncode(runtimeArn)}/ws/shells"
        return URLBuilder(config.endpoint.trimEnd('/') + path).apply {
            parameters.append("runtimeSessionId", runtimeSessionId)
            qualifier?.let { parameters.append("qualifier", it) }
            shellId?.let { parameters.append("shellId", it) }
        }.buildString()
    }

    /** Convert the signed HTTPS handshake URL to the required secure WSS URL. */
    internal fun toWebSocketUrl(url: String): String
    {
        require(url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("wss://", ignoreCase = true)) {
            "AgentCore Runtime shells require an HTTPS/WSS endpoint."
        }
        return url.replaceFirst("https://", "wss://", ignoreCase = true)
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
}

/** Stateful Runtime shell session; shell input/output is never logged by this type. */
class AgentCoreRuntimeShellSession internal constructor(
    val runtimeSessionId: String,
    val shellId: String?,
    private val reconnectConfig: AgentCoreShellReconnectConfig,
    private var socket: DefaultClientWebSocketSession,
    private val reconnectSocket: suspend () -> DefaultClientWebSocketSession
) : AutoCloseable
{
    private val mutex = Mutex()
    private var detached = false
    private var closed = false

    /** Send UTF-8 text to shell stdin. */
    suspend fun sendText(value: String) = sendBytes(value.toByteArray(StandardCharsets.UTF_8))

    /** Send bytes to shell stdin. */
    suspend fun sendBytes(value: ByteArray) = send(AgentCoreShellFrame(AgentCoreShellChannel.Stdin, value))

    /** Send a terminal resize frame. */
    suspend fun resize(columns: Int, rows: Int)
    {
        require(columns > 0 && rows > 0) { "Shell dimensions must be positive." }
        send(
            AgentCoreShellFrame(
                AgentCoreShellChannel.Resize,
                "{\"cols\":$columns,\"rows\":$rows}".toByteArray(StandardCharsets.UTF_8)
            )
        )
    }

    /** Receive the next binary shell frame. Text frames are not part of the protocol. */
    suspend fun receive(): AgentCoreShellFrame
    {
        val currentSocket = mutex.withLock { socket }
        val frame = currentSocket.incoming.receive()
        return when(frame)
        {
            is Frame.Binary -> AgentCoreShellFrame.decode(frame.data)
            is Frame.Close -> error("AgentCore Runtime shell closed.")
            else -> error("AgentCore Runtime shell received an unsupported text frame.")
        }
    }

    /** Receive binary shell frames until the current WebSocket closes. */
    fun frames(): Flow<AgentCoreShellFrame> = flow {
        val currentSocket = mutex.withLock { socket }
        for(frame in currentSocket.incoming)
        {
            when(frame)
            {
                is Frame.Binary -> emit(AgentCoreShellFrame.decode(frame.data))
                is Frame.Close -> return@flow
                else -> error("AgentCore Runtime shell received an unsupported text frame.")
            }
        }
    }

    /** Detach without sending terminal close data. */
    suspend fun detach()
    {
        mutex.withLock {
            if(detached || closed) return@withLock
            detached = true
            socket.close(CloseReason(CloseReason.Codes.NORMAL, "detached"))
        }
    }

    /** Send the protocol CLOSE frame, then close the WebSocket gracefully. */
    suspend fun closeGracefully()
    {
        mutex.withLock {
            if(closed) return@withLock
            if(!detached)
            {
                runCatching {
                    socket.send(
                        Frame.Binary(
                            fin = true,
                            data = AgentCoreShellFrame(AgentCoreShellChannel.Close, ByteArray(0)).encode()
                        )
                    )
                }
            }
            socket.close(CloseReason(CloseReason.Codes.NORMAL, "closed"))
            detached = true
            closed = true
        }
    }

    /** Reconnect after a transient disconnect using bounded exponential backoff. */
    suspend fun reconnect(): Int
    {
        mutex.withLock { check(!closed) { "AgentCore Runtime shell is closed." } }
        var attempts = 0
        var waitMillis = reconnectConfig.initialDelayMillis
        while(attempts < reconnectConfig.maxAttempts)
        {
            attempts++
            try
            {
                val replacement = reconnectSocket()
                mutex.withLock {
                    if(closed)
                    {
                        replacement.close(CloseReason(CloseReason.Codes.NORMAL, "closed"))
                        error("AgentCore Runtime shell is closed.")
                    }
                    socket.close(CloseReason(CloseReason.Codes.NORMAL, "replaced"))
                    socket = replacement
                    detached = false
                }
                return attempts
            }
            catch(exception: kotlinx.coroutines.CancellationException)
            {
                throw exception
            }
            catch(exception: Throwable)
            {
                if(attempts >= reconnectConfig.maxAttempts) throw exception
                delay(waitMillis)
                waitMillis = (waitMillis * 2L).coerceAtMost(reconnectConfig.maxDelayMillis)
            }
        }
        error("AgentCore Runtime shell reconnect attempts exhausted.")
    }

    /** Close this session synchronously for AutoCloseable callers. */
    override fun close() = runBlocking { closeGracefully() }

    private suspend fun send(frame: AgentCoreShellFrame)
    {
        mutex.withLock {
            check(!detached && !closed) { "AgentCore Runtime shell is detached or closed." }
            socket.send(Frame.Binary(fin = true, data = frame.encode()))
        }
    }
}
