package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.TPipeContextOptions
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.server.cio.CIO
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking

/**
 * HTTP transport wrapper for MCP server hosting.
 * Allows MCP clients to connect over HTTP instead of stdio.
 */
object McpHttpHost {

    /**
     * Run the MCP server with HTTP transport on the specified port.
     * @param port The port to listen on
     * @param authKey Optional authentication key. When non-null and non-blank, requires Bearer token auth
     * @param bindAddress The address to bind to (default 127.0.0.1 for local-only access)
     */
    fun run(port: Int, authKey: String? = null, bindAddress: String = "127.0.0.1") {
        runBlocking {
            val pcpContext = PcpContext()
            // FIX S1: Populate tpipeOptions from FunctionRegistry
            FunctionRegistry.listFunctions().forEach { descriptor ->
                pcpContext.addTPipeOption(TPipeContextOptions().apply {
                    functionName = descriptor.name
                })
            }
            val config = McpCapabilityConfig()
            val host = McpServerHost(
                pcpContext,
                config.buildServerCapabilities()
            )

            embeddedServer(CIO, host = bindAddress, port = port) {
                // FIX S2 + C1: Install auth and enforce at route level
                if(!authKey.isNullOrBlank()){
                    install(Authentication) {
                        bearer("mcp-auth") {
                            authenticate { tokenCredential: io.ktor.server.auth.BearerTokenCredential ->
                                if (tokenCredential.token == authKey) {
                                    UserIdPrincipal("mcp-client")
                                } else {
                                    null
                                }
                            }
                        }
                    }
                    routing {
                        authenticate("mcp-auth") {
                            mcpStreamableHttp("/mcp") { host.getServer() }
                        }
                    }
                } else {
                    mcpStreamableHttp("/mcp") { host.getServer() }
                }
            }.start(wait = true)
        }
    }

    /**
     * Run with custom capabilities.
     */
    fun run(port: Int, capabilities: ServerCapabilities, authKey: String? = null, bindAddress: String = "127.0.0.1") {
        runBlocking {
            val pcpContext = PcpContext()
            // FIX S1: Populate tpipeOptions from FunctionRegistry
            FunctionRegistry.listFunctions().forEach { descriptor ->
                pcpContext.addTPipeOption(TPipeContextOptions().apply {
                    functionName = descriptor.name
                })
            }
            val host = McpServerHost(pcpContext, capabilities)

            embeddedServer(CIO, host = bindAddress, port = port) {
                // FIX S2 + C1: Install auth and enforce at route level
                if(!authKey.isNullOrBlank()){
                    install(Authentication) {
                        bearer("mcp-auth") {
                            authenticate { tokenCredential: io.ktor.server.auth.BearerTokenCredential ->
                                if (tokenCredential.token == authKey) {
                                    UserIdPrincipal("mcp-client")
                                } else {
                                    null
                                }
                            }
                        }
                    }
                    routing {
                        authenticate("mcp-auth") {
                            mcpStreamableHttp("/mcp") { host.getServer() }
                        }
                    }
                } else {
                    mcpStreamableHttp("/mcp") { host.getServer() }
                }
            }.start(wait = true)
        }
    }
}