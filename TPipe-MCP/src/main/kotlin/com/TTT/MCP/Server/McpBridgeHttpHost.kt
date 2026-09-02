package com.TTT.MCP.Server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.cio.CIO
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.runBlocking

/** Configuration for hosting a TPipe MCP bridge over Streamable HTTP. */
data class McpHttpHostConfig(
    val port: Int = 8080,
    val authKey: String? = null,
    val bindAddress: String = "127.0.0.1",
    val path: String = "/mcp/bridge"
)

/**
 * HTTP transport wrapper for MCP bridge server hosting.
 * Allows MCP clients to connect over HTTP with bridge server conversion.
 */
object McpBridgeHttpHost {

    private const val ENV_MCP_JSON = "TPIPE_MCP_JSON"

    private fun getMcpJson(): String {
        return System.getenv(ENV_MCP_JSON)
            ?: System.getProperty(ENV_MCP_JSON)
            ?: throw IllegalStateException("Missing $ENV_MCP_JSON environment variable or system property")
    }

    private fun createHost(): McpBridgeServerHost {
        val mcpJson = getMcpJson()
        return McpBridgeServerHost(mcpJson)
    }

    /**
     * Run the MCP bridge server with HTTP transport on the specified port.
     * @param port The port to listen on
     * @param authKey Optional authentication key. When non-null and non-blank, requires Bearer token auth
     * @param bindAddress The address to bind to (default 127.0.0.1 for local-only access)
     */
    fun run(port: Int, authKey: String? = null, bindAddress: String = "127.0.0.1")
    {
        run(
            McpHttpHostConfig(
                port = port,
                authKey = authKey,
                bindAddress = bindAddress
            )
        )
    }

    /**
     * Run a bridge with an explicit endpoint path.
     *
     * The legacy overload keeps `/mcp/bridge`; callers hosting an AgentCore
     * MCP endpoint can select `/mcp` without changing the bridge server.
     */
    fun run(config: McpHttpHostConfig)
    {
        require(config.path.startsWith("/")) { "MCP endpoint path must start with '/'." }
        runBlocking {
            val host = createHost()

            embeddedServer(CIO, host = config.bindAddress, port = config.port) {
                if (!config.authKey.isNullOrBlank())
                {
                    install(Authentication) {
                        bearer("mcp-auth") {
                            authenticate { tokenCredential: io.ktor.server.auth.BearerTokenCredential ->
                                if (tokenCredential.token == config.authKey)
                                {
                                    UserIdPrincipal("mcp-client")
                                }
                                else
                                {
                                    null
                                }
                            }
                        }
                    }
                }
                routing {
                    if (!config.authKey.isNullOrBlank())
                    {
                        authenticate("mcp-auth") {
                            mcpStreamableHttp(config.path) { host.getServer() }
                        }
                    }
                    else
                    {
                        mcpStreamableHttp(config.path) { host.getServer() }
                    }
                }
            }.start(wait = true)
        }
    }
}
