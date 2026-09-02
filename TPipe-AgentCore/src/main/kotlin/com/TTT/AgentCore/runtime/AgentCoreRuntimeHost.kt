package com.TTT.AgentCore.runtime

import com.TTT.AgentCore.AgentCoreClients
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import com.TTT.AgentCore.tools.AgentCoreBrowserClient
import com.TTT.AgentCore.tools.AgentCoreCodeInterpreterClient
import com.TTT.AgentCore.tools.browser
import com.TTT.AgentCore.tools.codeInterpreter
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Configuration for the local AgentCore Runtime HTTP/WebSocket host. */
data class AgentCoreRuntimeHostConfig(
    val bindAddress: String = "0.0.0.0",
    val port: Int = 8080,
    val invocationPath: String = "/invocations",
    val pingPath: String = "/ping",
    val websocketPath: String = "/ws",
    val sessionHeader: String = "x-amzn-bedrock-agentcore-runtime-session-id",
    val sessionMode: AgentCoreSessionMode = AgentCoreSessionMode.ISOLATED,
    val abortOnClientDisconnect: Boolean = true
)

/**
 * Hosts a TPipe root behind the AgentCore Runtime HTTP contract.
 *
 * The host does not introduce a new TPipe transport enum. It converts each
 * request into the existing [P2PRequest] contract and retains the root in the
 * [AgentCoreSessionRegistry].
 */
class AgentCoreRuntimeHost(
    private val config: AgentCoreRuntimeHostConfig,
    factory: AgentCoreSessionFactory,
    private val clock: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val sessions = AgentCoreSessionRegistry(config.sessionMode, factory, clock)
    private var engine: EmbeddedServer<*, *>? = null
    private val healthStatus = AtomicReference("Healthy")
    private val healthUpdatedAt = AtomicLong(clock())

    /** Start the runtime server and return its engine. */
    fun start(wait: Boolean = false): EmbeddedServer<*, *> {
        check(engine == null) { "AgentCore Runtime host is already started." }
        return embeddedServer(CIO, host = config.bindAddress, port = config.port) {
            installRuntimeRoutes()
        }.also {
            engine = it.start(wait = wait)
        }
    }

    /** Stop the runtime server and release the session registry. */
    override fun close() {
        engine?.stop(1000, 2000)
        engine = null
        sessions.close()
    }

    /** Evict inactive session roots older than [olderThan]. */
    suspend fun evictIdleSessions(olderThan: Long): List<String> = sessions.evictIdle(olderThan)

    /** Number of retained session roots. */
    suspend fun sessionCount(): Int = sessions.size()

    /** Registry that AgentCore tool clients can use for owner-bound cleanup. */
    fun sessionRegistry(): AgentCoreSessionRegistry = sessions

    /** Construct a Browser client bound to this host's session registry. */
    fun browserClient(clients: AgentCoreClients): AgentCoreBrowserClient = clients.browser(sessions)

    /** Construct a Code Interpreter client bound to this host's session registry. */
    fun codeInterpreterClient(clients: AgentCoreClients): AgentCoreCodeInterpreterClient =
        clients.codeInterpreter(sessions)

    private fun Application.installRuntimeRoutes() {
        install(WebSockets)
        routing {
            get(config.pingPath) {
                call.respondText(AgentCoreRuntimeJson.encodePing(healthResponse()), ContentType.Application.Json)
            }
            post(config.invocationPath) {
                val request = runCatching { AgentCoreRuntimeJson.decodeRequest(call.receiveText()) }.getOrElse {
                    call.respondText(
                        AgentCoreRuntimeJson.encodeError(
                            AgentCoreInvocationError("Invalid invocation request: ${it.message}")
                        ),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                    return@post
                }
                val sessionId = request.sessionId
                    ?: call.request.headers[config.sessionHeader]
                    ?: UUID.randomUUID().toString()
                if (request.stream) {
                    call.response.headers.append(config.sessionHeader, sessionId)
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        var emittedChunks = false
                        var completedSuccessfully = false
                        try {
                            val response = this@AgentCoreRuntimeHost.execute(
                                sessionId = sessionId,
                                content = request.effectiveContent(),
                                stream = { chunk ->
                                    emittedChunks = true
                                    write(
                                        "data: ${AgentCoreRuntimeJson.encodeStreamEvent(AgentCoreStreamEvent(text = chunk, sessionId = sessionId))}\n\n"
                                    )
                                    flush()
                                }
                            )
                            response.rejection?.let { rejection ->
                                write(
                                    "data: ${AgentCoreRuntimeJson.encodeError(AgentCoreInvocationError("${rejection.errorType}: ${rejection.reason}", sessionId))}\n\n"
                                )
                                flush()
                            } ?: response.output?.text?.takeIf { it.isNotEmpty() }?.let { output ->
                                if (!emittedChunks) {
                                    write(
                                        "data: ${AgentCoreRuntimeJson.encodeStreamEvent(AgentCoreStreamEvent(text = output, sessionId = sessionId))}\n\n"
                                    )
                                    flush()
                                }
                            }
                            completedSuccessfully = response.rejection == null
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            write(
                                "data: ${AgentCoreRuntimeJson.encodeError(AgentCoreInvocationError(exception.message ?: "Invocation failed", sessionId))}\n\n"
                            )
                            flush()
                        }
                        if (completedSuccessfully) {
                            write(
                                "data: ${AgentCoreRuntimeJson.encodeStreamEvent(AgentCoreStreamEvent(done = true, sessionId = sessionId))}\n\n"
                            )
                            flush()
                        }
                    }
                } else {
                    val response = try {
                        execute(sessionId, request.effectiveContent(), null)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        call.respondText(
                            AgentCoreRuntimeJson.encodeError(
                                AgentCoreInvocationError(exception.message ?: "Invocation failed", sessionId)
                            ),
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError
                        )
                        return@post
                    }
                    response.rejection?.let { rejection ->
                        call.response.headers.append(config.sessionHeader, sessionId)
                        call.respondText(
                            AgentCoreRuntimeJson.encodeError(
                                AgentCoreInvocationError(
                                    error = "${rejection.errorType}: ${rejection.reason}",
                                    sessionId = sessionId
                                )
                            ),
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                    } ?: run {
                        call.response.headers.append(config.sessionHeader, sessionId)
                        call.respondText(
                            AgentCoreRuntimeJson.encodeResponse(response.toInvocationResponse(sessionId)),
                            ContentType.Application.Json
                        )
                    }
                }
            }
            webSocket(config.websocketPath) {
                for (frame in incoming) {
                    if (!isActive || frame !is Frame.Text) continue
                    val request = runCatching {
                        AgentCoreRuntimeJson.decodeRequest(frame.readText())
                    }.getOrElse {
                        send(Frame.Text(AgentCoreRuntimeJson.encodeError(AgentCoreInvocationError(it.message ?: "Invalid request"))))
                        continue
                    }
                    val sessionId = request.sessionId
                        ?: call.request.headers[config.sessionHeader]
                        ?: UUID.randomUUID().toString()
                    if (request.stream) {
                        var emittedChunks = false
                        try {
                            val response = execute(
                                sessionId,
                                request.effectiveContent(),
                                stream = { chunk ->
                                    emittedChunks = true
                                    send(
                                        Frame.Text(
                                            AgentCoreRuntimeJson.encodeStreamEvent(
                                                AgentCoreStreamEvent(text = chunk, sessionId = sessionId)
                                            )
                                        )
                                    )
                                },
                                protocol = AgentCoreRuntimeProtocol.HTTP
                            )
                            response.rejection?.let { rejection ->
                                send(
                                    Frame.Text(
                                        AgentCoreRuntimeJson.encodeError(
                                            AgentCoreInvocationError(
                                                error = "${rejection.errorType}: ${rejection.reason}",
                                                sessionId = sessionId
                                            )
                                        )
                                    )
                                )
                            } ?: run {
                                if (!emittedChunks) {
                                    response.output?.text?.takeIf { it.isNotEmpty() }?.let { output ->
                                        send(
                                            Frame.Text(
                                                AgentCoreRuntimeJson.encodeStreamEvent(
                                                    AgentCoreStreamEvent(text = output, sessionId = sessionId)
                                                )
                                            )
                                        )
                                    }
                                }
                                send(
                                    Frame.Text(
                                        AgentCoreRuntimeJson.encodeStreamEvent(
                                            AgentCoreStreamEvent(done = true, sessionId = sessionId)
                                        )
                                    )
                                )
                            }
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            send(
                                Frame.Text(
                                    AgentCoreRuntimeJson.encodeError(
                                        AgentCoreInvocationError(exception.message ?: "Invocation failed", sessionId)
                                    )
                                )
                            )
                        }
                        continue
                    }
                    val response = try {
                        execute(sessionId, request.effectiveContent(), null, AgentCoreRuntimeProtocol.HTTP)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        send(
                            Frame.Text(
                                AgentCoreRuntimeJson.encodeError(
                                    AgentCoreInvocationError(exception.message ?: "Invocation failed", sessionId)
                                )
                            )
                        )
                        continue
                    }
                    response.rejection?.let { rejection ->
                        send(
                            Frame.Text(
                                AgentCoreRuntimeJson.encodeError(
                                    AgentCoreInvocationError(
                                        error = "${rejection.errorType}: ${rejection.reason}",
                                        sessionId = sessionId
                                    )
                                )
                            )
                        )
                    } ?: send(
                        Frame.Text(
                            AgentCoreRuntimeJson.encodeResponse(
                                response.toInvocationResponse(sessionId),
                                includeSessionId = true
                            )
                        )
                    )
                }
            }
        }
    }

    private suspend fun execute(
        sessionId: String,
        content: MultimodalContent,
        stream: (suspend (String) -> Unit)?,
        protocol: AgentCoreRuntimeProtocol = AgentCoreRuntimeProtocol.HTTP
    ): P2PResponse {
        return sessions.withSession(sessionId, protocol) { root ->
            try {
                stream?.let { callback -> root.setStreamingCallbackRecursive(callback) }
                root.executeP2PRequest(
                    P2PRequest(
                        transport = P2PTransport(Transport.Http, "${config.bindAddress}:${config.port}"),
                        prompt = content
                    )
                ) ?: P2PResponse()
            } catch (exception: CancellationException) {
                if (config.abortOnClientDisconnect) {
                    runCatching { root.abortRecursive() }
                }
                throw exception
            } finally {
                root.clearStreamingCallbacksRecursive()
            }
        }
    }

    private suspend fun healthResponse(): AgentCorePingResponse {
        val nextStatus = if (sessions.isBusy()) "HealthyBusy" else "Healthy"
        if (healthStatus.getAndSet(nextStatus) != nextStatus) {
            healthUpdatedAt.set(clock())
        }
        return AgentCorePingResponse(status = nextStatus, timeOfLastUpdate = healthUpdatedAt.get())
    }

    private fun P2PResponse.toInvocationResponse(sessionId: String): AgentCoreInvocationResponse {
        return AgentCoreInvocationResponse(
            output = output?.text.orEmpty(),
            sessionId = sessionId,
            outputContent = output
        )
    }
}

/** Single streamed text event used by the runtime SSE response. */
data class AgentCoreStreamEvent(
    val text: String = "",
    val done: Boolean = false,
    val sessionId: String? = null,
    val error: String? = null
)
