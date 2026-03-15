package com.TTT.TraceServer

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.serialization.encodeToString
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import kotlin.time.Duration.Companion.seconds

/**
 * Main entry point for standalone execution.
 * Supports command line arguments like:
 * --port 8081
 * --host 0.0.0.0
 */
fun main(args: Array<String>)
{
    var port = TraceServerConfig.port
    var host = TraceServerConfig.host

    for(i in args.indices)
    {
        if(args[i] == "--port" && i + 1 < args.size)
        {
            port = args[i + 1].toIntOrNull() ?: port
        }
        if(args[i] == "--host" && i + 1 < args.size)
        {
            host = args[i + 1]
        }
    }

    startTraceServer(port, host, wait = true)
}

/**
 * Starts the TraceServer programmatically.
 * @param port The port to host the server on. Defaults to [TraceServerConfig.port].
 * @param host The host to bind to. Defaults to [TraceServerConfig.host].
 * @param wait Whether to block the thread waiting for the server to stop. Default is false.
 */
fun startTraceServer(
    port: Int = TraceServerConfig.port,
    host: String = TraceServerConfig.host,
    wait: Boolean = false
)
{
    embeddedServer(Netty, port = port, host = host, module = Application::traceServerModule)
        .start(wait = wait)
}

/**
 * Configures the Ktor application with WebSockets, JSON content negotiation,
 * CORS, and the trace server routing.
 */
fun Application.traceServerModule()
{
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(CORS) {
        anyHost()
        allowHeader("Authorization")
        allowHeader("Content-Type")
    }

    routing {
        get("/") {
            val html = object {}.javaClass.classLoader.getResource("static/index.html")?.readText()
            if(html != null)
            {
                call.respondText(html, ContentType.Text.Html)
            } else {
                call.respondText("Dashboard not found", status = HttpStatusCode.NotFound)
            }
        }

        get("/dashboard.js") {
            val js = object {}.javaClass.classLoader.getResource("static/dashboard.js")?.readText()
            if(js != null)
            {
                call.respondText(js, ContentType.Application.JavaScript)
            } else {
                call.respondText("Script not found", status = HttpStatusCode.NotFound)
            }
        }

        post("/api/auth/login") {
            // Human client login flow
            if(TraceServerRegistry.clientAuthMechanism == null)
            {
                // No client auth required, return anonymous session
                call.respond(AuthResponse(TraceServerRegistry.createSession()))
                return@post
            }

            try {
                val req = call.receive<AuthRequest>()
                if(TraceServerRegistry.clientAuthMechanism?.invoke(req.key) == true)
                {
                    call.respond(AuthResponse(TraceServerRegistry.createSession()))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid request format")
            }
        }

        get("/api/traces") {
            // Check client session token
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")

            // Allow if no auth required OR session is valid
            if(TraceServerRegistry.clientAuthMechanism != null && !TraceServerRegistry.validateSession(token))
            {
                call.respond(HttpStatusCode.Unauthorized, "Session expired or unauthorized")
                return@get
            }

            call.respond(TraceServerRegistry.getAllSummaries())
        }

        get("/api/traces/{id}") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            if(TraceServerRegistry.clientAuthMechanism != null && !TraceServerRegistry.validateSession(token))
            {
                call.respond(HttpStatusCode.Unauthorized, "Session expired or unauthorized")
                return@get
            }

            val id = call.parameters["id"]
            val trace = TraceServerRegistry.traces[id]
            if(trace != null)
            {
                call.respond(trace)
            } else {
                call.respond(HttpStatusCode.NotFound, "Trace not found")
            }
        }

        post("/api/traces") {
            // Check Agent auth mechanism for submitting traces
            val auth = call.request.headers["Authorization"]
            if(TraceServerRegistry.agentAuthMechanism?.invoke(auth) == false)
            {
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized Agent")
                return@post
            }

            val payload = call.receive<TracePayload>()
            TraceServerRegistry.registerTrace(payload)

            // Broadcast new trace summary to all connected WebSocket clients
            val summary = TraceSummary(payload.pipelineId, System.currentTimeMillis(), payload.name, payload.status)
            val jsonSummary = Json.encodeToString(TraceSummary.serializer(), summary)

            synchronized(TraceServerRegistry.connections) {
                val iter = TraceServerRegistry.connections.iterator()
                while(iter.hasNext())
                {
                    val session = iter.next()
                    GlobalScope.launch {
                        try {
                            session.send(Frame.Text(jsonSummary))
                        } catch (e: Exception) {
                            session.close()
                        }
                    }
                }
            }

            call.respond(HttpStatusCode.OK)
        }

        webSocket("/ws/traces") {
            // Validate connection query parameter for session token if auth is enabled
            val token = call.request.queryParameters["token"]
            if(TraceServerRegistry.clientAuthMechanism != null && !TraceServerRegistry.validateSession(token))
            {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            TraceServerRegistry.connections += this
            try {
                for(frame in incoming)
                {
                    // Just keeping the connection open
                }
            } catch (e: ClosedReceiveChannelException) {
                // Connection closed
            } catch (e: Throwable) {
                e.printStackTrace()
            } finally {
                TraceServerRegistry.connections -= this
            }
        }
    }
}
