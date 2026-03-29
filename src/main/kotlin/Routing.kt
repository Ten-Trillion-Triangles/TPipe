package com.TTT

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PRejection
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Context.MemoryServer
import com.TTT.PipeContextProtocol.*
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
        MemoryServer.configureMemoryRouting(this)

        get("/")
        {
            call.respondText("Hello World!")
        }

        post("/p2p")
        {
            try
            {
                val request = call.receive<P2PRequest>().apply {
                    if(transport.transportAuthBody.isBlank())
                    {
                        transport.transportAuthBody = call.request.header("Authorization").orEmpty()
                    }
                }

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

        post("/p2p/registry")
        {
            try
            {
                val request = call.receive<P2PRequest>().apply {
                    if(transport.transportAuthBody.isBlank())
                    {
                        transport.transportAuthBody = call.request.header("Authorization").orEmpty()
                    }
                }

                val authMechanism = P2PRegistry.globalAuthMechanism
                if(authMechanism != null)
                {
                    val isAuthorized = authMechanism(request.transport.transportAuthBody)
                    if(!isAuthorized)
                    {
                        call.respond(P2PResponse().apply {
                            rejection = P2PRejection(P2PError.auth, "Unauthorized P2P hosted registry request")
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
                    rejection = P2PRejection(P2PError.transport, "Failed to process hosted registry request: ${e.message}")
                })
            }
        }

        /**
         * Endpoint for standalone PCP requests.
         * Supports both single PcPRequest and a list of PcPRequests.
         */
        post("/pcp")
        {
            try
            {
                // Authenticate if auth mechanism is set.
                // Note: Standalone PCP requests don't have a P2P-style transportAuthBody,
                // so we check the "Authorization" header.
                val authMechanism = P2PRegistry.globalAuthMechanism
                if(authMechanism != null)
                {
                    val authHeader = call.request.header("Authorization") ?: ""
                    val isAuthorized = authMechanism(authHeader)
                    if(!isAuthorized)
                    {
                        call.respond(PcpExecutionResult(
                            success = false,
                            results = emptyList(),
                            executionTimeMs = 0,
                            errors = listOf("Unauthorized PCP request")
                        ))
                        return@post
                    }
                }

                val bodyText = call.receiveText()
                val requests = com.TTT.Util.extractJson<List<PcPRequest>>(bodyText)
                    ?: com.TTT.Util.extractJson<PcPRequest>(bodyText)?.let { listOf(it) }

                if(requests == null)
                {
                    call.respond(PcpExecutionResult(
                        success = false,
                        results = emptyList(),
                        executionTimeMs = 0,
                        errors = listOf("Failed to parse PCP request body")
                    ))
                    return@post
                }

                val result = PcpRegistry.executeRequests(requests)
                call.respond(result)
            }
            catch(e: Exception)
            {
                call.respond(PcpExecutionResult(
                    success = false,
                    results = emptyList(),
                    executionTimeMs = 0,
                    errors = listOf("Failed to process PCP request: ${e.message}")
                ))
            }
        }
    }
}
