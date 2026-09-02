package com.TTT.AgentCore.Runtime.AgUi

import com.TTT.AgentCore.AgentCoreClients
import com.TTT.AgentCore.runtime.AgentCoreRuntimeHostConfig
import com.TTT.AgentCore.runtime.AgentCoreRuntimeProtocol
import com.TTT.AgentCore.runtime.AgentCoreSessionFactory
import com.TTT.AgentCore.runtime.AgentCoreSessionRegistry
import com.TTT.AgentCore.tools.AgentCoreBrowserClient
import com.TTT.AgentCore.tools.AgentCoreCodeInterpreterClient
import com.TTT.AgentCore.tools.browser
import com.TTT.AgentCore.tools.codeInterpreter
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import java.io.Writer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** AgentCore-only AG-UI transport host; Core protocol types remain unchanged.
 *
 * @param config Runtime server configuration.
 * @param factory Factory for new AG-UI session roots.
 * @param mapper Input mapper for the AG-UI envelope.
 */
class AgentCoreAgUiRuntimeHost(
    private val config: AgentCoreRuntimeHostConfig = AgentCoreRuntimeHostConfig(),
    factory: AgentCoreSessionFactory,
    private val mapper: AgentCoreAgUiInputMapper = AgentCoreAgUiInputMapper()
) : AutoCloseable
{
    private val sessions = AgentCoreSessionRegistry(config.sessionMode, factory)
    private var engine: EmbeddedServer<*, *>? = null
    private val healthStatus = AtomicReference("Healthy")
    private val healthUpdatedAt = AtomicLong(System.currentTimeMillis())

    /** Start the SSE/WebSocket AG-UI host.
     *
     * @param wait Whether to block while the server is running.
     * @return The started embedded server.
     */
    fun start(wait: Boolean = false): EmbeddedServer<*, *>
    {
        check(engine == null) { "AG-UI runtime host is already started." }
        return embeddedServer(CIO, host = config.bindAddress, port = config.port) {
            installAgUiRoutes()
        }.also { server -> engine = server.start(wait = wait) }
    }

    /** Stop the host and release retained session roots. */
    override fun close()
    {
        engine?.stop(1000, 2000)
        engine = null
        sessions.close()
    }

    /** Registry that AgentCore tool clients can use for owner-bound cleanup. */
    fun sessionRegistry(): AgentCoreSessionRegistry = sessions

    /** Construct a Browser client bound to this host's session registry. */
    fun browserClient(clients: AgentCoreClients): AgentCoreBrowserClient = clients.browser(sessions)

    /** Construct a Code Interpreter client bound to this host's session registry. */
    fun codeInterpreterClient(clients: AgentCoreClients): AgentCoreCodeInterpreterClient =
        clients.codeInterpreter(sessions)

    private fun Application.installAgUiRoutes()
    {
        install(WebSockets)
        routing {
            get(config.pingPath) {
                call.respondText(
                    com.TTT.AgentCore.runtime.AgentCoreRuntimeJson.encodePing(healthResponse()),
                    ContentType.Application.Json
                )
            }
            post(config.invocationPath) {
                val rawInput = call.receiveText()
                val decoded = runCatching { mapper.decode(rawInput) }
                if(decoded.isFailure)
                {
                    call.response.status(HttpStatusCode.BadRequest)
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        writeFailure(
                            RunAgentInput(threadId = "", runId = "", messages = emptyList()),
                            "Invalid AG-UI input: ${decoded.exceptionOrNull()?.message.orEmpty()}"
                        )
                    }

                    return@post
                }

                val decodedInput = decoded.getOrThrow()
                val input = decodedInput.sessionId?.let { decodedInput }
                    ?: call.request.headers[config.sessionHeader]?.let { decodedInput.copy(sessionId = it) }
                    ?: decodedInput
                val mapped = runCatching { mapper.map(input) }
                if(mapped.isFailure)
                {
                    call.response.status(HttpStatusCode.BadRequest)
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        writeFailure(input, mapped.exceptionOrNull()?.message.orEmpty())
                    }

                    return@post
                }

                call.respondTextWriter(ContentType.Text.EventStream) {
                    writeAll(input, mapped.getOrThrow())
                }
            }

            webSocket(config.websocketPath) {
                for(frame in incoming)
                {
                    if(!isActive || frame !is Frame.Text) continue
                    val decoded = runCatching { mapper.decode(frame.readText()) }
                    if(decoded.isFailure)
                    {
                        send(
                            Frame.Text(
                                AgentCoreAgUiEventEncoder.encodeWebSocket(
                                    AgentCoreAgUiEventMapper.failed(
                                        RunAgentInput(threadId = "", runId = "", messages = emptyList()),
                                        "Invalid AG-UI input: ${decoded.exceptionOrNull()?.message.orEmpty()}"
                                    )
                                )
                            )
                        )
                        continue
                    }

                    val decodedInput = decoded.getOrThrow()
                    val input = decodedInput.sessionId?.let { decodedInput }
                        ?: call.request.headers[config.sessionHeader]?.let { decodedInput.copy(sessionId = it) }
                        ?: decodedInput
                    val mappedResult = runCatching { mapper.map(input) }
                    if(mappedResult.isFailure)
                    {
                        send(
                            Frame.Text(
                                AgentCoreAgUiEventEncoder.encodeWebSocket(
                                    AgentCoreAgUiEventMapper.failed(
                                        input,
                                        mappedResult.exceptionOrNull()?.message.orEmpty()
                                    )
                                )
                            )
                        )
                        continue
                    }

                    val mapped = mappedResult.getOrThrow()
                    val mappedInput = RunAgentInput(
                        threadId = mapped.threadId,
                        runId = mapped.runId,
                        messages = listOf(RunAgentMessage("user", mapped.request.prompt.text)),
                        sessionId = mapped.sessionId
                    )
                    try
                    {
                        executeStreaming(mappedInput, mapped) { event ->
                            send(Frame.Text(AgentCoreAgUiEventEncoder.encodeWebSocket(event)))
                        }
                    }

                    catch(exception: CancellationException)
                    {
                        throw exception
                    }

                    catch(exception: Exception)
                    {
                        send(
                            Frame.Text(
                                AgentCoreAgUiEventEncoder.encodeWebSocket(
                                    AgentCoreAgUiEventMapper.failed(mappedInput, exception.message.orEmpty())
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun Writer.writeAll(input: RunAgentInput, mapped: AgentCoreAgUiMappedRequest)
    {
        try
        {
            executeStreaming(input, mapped) { event ->
                write(AgentCoreAgUiEventEncoder.encodeSse(event))
                flush()
            }
        }

        catch(exception: CancellationException)
        {
            throw exception
        }

        catch(exception: Exception)
        {
            write(AgentCoreAgUiEventEncoder.encodeSse(AgentCoreAgUiEventMapper.failed(input, exception.message.orEmpty())))
            flush()
        }
    }

    private fun Writer.writeFailure(input: RunAgentInput, message: String)
    {
        write(AgentCoreAgUiEventEncoder.encodeSse(AgentCoreAgUiEventMapper.failed(input, message)))
        flush()
    }

    private suspend fun executeStreaming(
        input: RunAgentInput,
        mapped: AgentCoreAgUiMappedRequest,
        emit: suspend (AgentCoreAgUiEvent) -> Unit
    )
    {
        AgentCoreAgUiEventMapper.started(input).forEach { event -> emit(event) }
        var emittedChunks = false
        val response = sessions.withSession(
            sessionId = mapped.sessionId,
            protocol = AgentCoreRuntimeProtocol.AGUI,
            requestCorrelationId = "${input.threadId}:${input.runId}",
            approvedRequestHeaders = emptyMap(),
            threadId = input.threadId,
            runId = input.runId
        ) { root ->
            try
            {
                root.setStreamingCallbackRecursive { chunk ->
                    if(chunk.isNotEmpty())
                    {
                        emittedChunks = true
                        emit(AgentCoreAgUiEventMapper.content(input, chunk))
                    }
                }
                root.executeP2PRequest(mapped.request) ?: P2PResponse()
            }

            catch(exception: CancellationException)
            {
                if(config.abortOnClientDisconnect)
                {
                    runCatching { root.abortRecursive() }
                }
                throw exception
            }

            finally
            {
                root.clearStreamingCallbacksRecursive()
            }
        }
        val rejection = response.rejection
        if(rejection != null)
        {
            emit(AgentCoreAgUiEventMapper.failed(input, rejection.reason))
        }

        else
        {
            if(!emittedChunks)
            {
                response.output?.text?.takeIf { it.isNotEmpty() }?.let { output ->
                    emit(AgentCoreAgUiEventMapper.content(input, output))
                }
            }

            AgentCoreAgUiEventMapper.finished(input).forEach { event -> emit(event) }
        }
    }

    private suspend fun healthResponse(): com.TTT.AgentCore.runtime.AgentCorePingResponse
    {
        val nextStatus = if(sessions.isBusy()) "HealthyBusy" else "Healthy"
        if(healthStatus.getAndSet(nextStatus) != nextStatus)
        {
            healthUpdatedAt.set(System.currentTimeMillis())
        }

        return com.TTT.AgentCore.runtime.AgentCorePingResponse(
            status = nextStatus,
            timeOfLastUpdate = healthUpdatedAt.get()
        )
    }
}
