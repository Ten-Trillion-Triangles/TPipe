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
import java.time.Duration
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import java.io.File

fun main() {
    embeddedServer(Netty, port = 8081, host = "0.0.0.0", module = Application::traceServerModule)
        .start(wait = true)
}

fun Application.traceServerModule() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
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
            // In dev environment, resources might be on disk. In prod, they are in JAR.
            // We'll read from classpath.
            val html = object {}.javaClass.classLoader.getResource("static/index.html")?.readText()
            if (html != null) {
                call.respondText(html, ContentType.Text.Html)
            } else {
                call.respondText("Dashboard not found (index.html missing from static resources)", status = HttpStatusCode.NotFound)
            }
        }

        get("/dashboard.js") {
            val js = object {}.javaClass.classLoader.getResource("static/dashboard.js")?.readText()
            if (js != null) {
                call.respondText(js, ContentType.Application.JavaScript)
            } else {
                call.respondText("Script not found (dashboard.js missing from static resources)", status = HttpStatusCode.NotFound)
            }
        }

        get("/api/traces") {
            val auth = call.request.headers["Authorization"]
            if (TraceServerRegistry.globalAuthMechanism?.invoke(auth) == false) {
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                return@get
            }
            call.respond(TraceServerRegistry.getAllSummaries())
        }

        get("/api/traces/{id}") {
            val auth = call.request.headers["Authorization"]
            if (TraceServerRegistry.globalAuthMechanism?.invoke(auth) == false) {
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                return@get
            }
            val id = call.parameters["id"]
            val trace = TraceServerRegistry.traces[id]
            if (trace != null) {
                call.respond(trace)
            } else {
                call.respond(HttpStatusCode.NotFound, "Trace not found")
            }
        }

        post("/api/traces") {
            val auth = call.request.headers["Authorization"]
            if (TraceServerRegistry.globalAuthMechanism?.invoke(auth) == false) {
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                return@post
            }

            val payload = call.receive<TracePayload>()
            TraceServerRegistry.registerTrace(payload)

            // Broadcast new trace summary to all connected WebSocket clients
            val summary = TraceSummary(payload.pipelineId, System.currentTimeMillis(), payload.name, payload.status)
            val jsonSummary = Json.encodeToString(TraceSummary.serializer(), summary)

            synchronized(TraceServerRegistry.connections) {
                val iter = TraceServerRegistry.connections.iterator()
                while (iter.hasNext()) {
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
            TraceServerRegistry.connections += this
            try {
                for (frame in incoming) {
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
