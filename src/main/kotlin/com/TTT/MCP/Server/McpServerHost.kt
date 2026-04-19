package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.PcpContext
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Core MCP server wrapper using the MCP Kotlin SDK.
 * Manages server lifecycle with stdio transport for process-based hosting.
 *
 * @param pcpContext The PCP context for function execution
 * @param capabilities The MCP server capabilities to expose
 * @param serverInfo Server implementation information
 */
class McpServerHost(
    private val pcpContext: PcpContext,
    private val capabilities: ServerCapabilities,
    private val serverInfo: Implementation = Implementation(name = "tpipe", version = "1.0.0")
) {
    private val toolRegistry = McpToolRegistry(pcpContext)

    private val server = Server(
        serverInfo = serverInfo,
        options = ServerOptions(capabilities = capabilities)
    ) {
        val tools = toolRegistry.listTools()
        tools.forEach { tool ->
            addTool(
                name = tool.name,
                description = tool.description ?: "",
                inputSchema = tool.inputSchema
            ) { request ->
                val arguments = request.params.arguments?.toMap()?.mapValues { it.value.toString() } ?: emptyMap()
                toolRegistry.callTool(request.params.name, arguments)
            }
        }
    }

    /**
     * Get the underlying MCP Server instance.
     */
    fun getServer(): Server = server

    /**
     * Run the server once, processing requests from stdin until it closes.
     * Suitable for one-shot stdio mode where the MCP client sends requests and stdin closes after.
     */
    suspend fun runOnce() {
        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered()
        )
        server.createSession(transport)
        // createSession starts transport message loop in background.
        // Block current thread until the server is closed (stdin EOF triggers transport close).
        runBlocking {
            server.onClose { }
            // Wait indefinitely - the transport runs until stdin EOF or error,
            // then close is called on the transport, triggering server close callbacks.
            Thread.currentThread().join()
        }
    }

    /**
     * Run the server in a loop, continuously processing requests from stdin.
     * Suitable for interactive stdio mode where the MCP client sends multiple requests.
     */
    suspend fun runLoop() {
        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered()
        )
        server.createSession(transport)
        // Same as runOnce for stdio - block until stdin closes
        runBlocking {
            server.onClose { }
            Thread.currentThread().join()
        }
    }

    /**
     * Shutdown the server.
     */
    fun shutdown() {
        runBlocking { server.close() }
    }
}