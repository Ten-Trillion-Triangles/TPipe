package com.TTT

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PRejection
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Configures the routing for the TPipe Ktor application.
 */
fun Application.configureRouting()
{
    routing {
        get("/")
        {
            call.respondText("Hello World!")
        }

        post("/p2p")
        {
            try
            {
                val request = call.receive<P2PRequest>()

                // Handle global authentication if set
                val authMechanism = P2PRegistry.globalAuthMechanism
                if(authMechanism != null)
                {
                    val isAuthorized = authMechanism(request.transport.transportAuthBody)
                    if(!isAuthorized)
                    {
                        call.respond(P2PResponse().apply {
                            rejection = P2PRejection(P2PError.auth, "Unauthorized P2P request")
                        })
                        return@post
                    }
                }

                val response = P2PRegistry.executeP2pRequest(request)
                call.respond(response)
            }
            catch(e: Exception)
            {
                call.respond(P2PResponse().apply {
                    rejection = P2PRejection(P2PError.transport, "Failed to process P2P request: ${e.message}")
                })
            }
        }
    }
}
