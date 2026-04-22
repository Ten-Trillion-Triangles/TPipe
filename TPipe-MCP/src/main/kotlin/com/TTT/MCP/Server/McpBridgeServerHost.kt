package com.TTT.MCP.Server

import com.TTT.MCP.Bridge.McpJsonParser
import com.TTT.MCP.Bridge.McpToPcpConverter
import com.TTT.MCP.Bridge.McpProtocolHandler
import com.TTT.MCP.Models.JsonRpcRequest
import com.TTT.MCP.Models.JsonRpcResponse
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.PcpRegistry
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
 * Unified MCP bridge server that combines TPipe-MCP bridge with MCP server functionality.
 * 
 * This class bridges incoming MCP JSON requests to PCP context operations:
 * - Accepts MCP JSON string via constructor and converts to [PcpContext]
 * - Routes JSON-RPC requests via [McpProtocolHandler] to appropriate handlers
 * - Uses MCP Kotlin SDK [Server] with [StdioServerTransport] for stdio transport
 * - Sets PCP context globally via [PcpRegistry.setContext] before handling requests
 * 
 * This differs from [McpServerHost] which assumes pre-configured PCP context.
 * McpBridgeServerHost handles the bridge conversion from raw MCP JSON.
 *
 * @param mcpJson The MCP JSON string containing tools, resources, templates, and prompts
 * @param capabilities The MCP server capabilities to expose (default: tools, resources, prompts)
 * @param serverInfo Server implementation information
 * @throws IllegalArgumentException if MCP JSON is malformed or missing required fields
 */
class McpBridgeServerHost(
    private val mcpJson: String,
    private val capabilities: ServerCapabilities = ServerCapabilities(
        tools = ServerCapabilities.Tools(listChanged = true),
        resources = ServerCapabilities.Resources(subscribe = null, listChanged = true),
        prompts = ServerCapabilities.Prompts(listChanged = true)
    ),
    private val serverInfo: Implementation = Implementation(name = "tpipe-bridge", version = "1.0.0")
) {
    private val mcpJsonParser = McpJsonParser()
    private val mcpToPcpConverter = McpToPcpConverter()
    
    val pcpContext: PcpContext
    private val protocolHandler: McpProtocolHandler
    private val toolRegistry: McpToolRegistry
    private val resourceProvider: McpResourceProvider
    private val promptProvider: McpPromptProvider
    private val server: Server
    
    init {
        val mcpRequest = mcpJsonParser.parseJson(mcpJson)
        pcpContext = mcpToPcpConverter.convert(mcpRequest)
        
        toolRegistry = McpToolRegistry(pcpContext)
        resourceProvider = McpResourceProvider(pcpContext)
        promptProvider = McpPromptProvider(pcpContext)
        
        protocolHandler = McpProtocolHandler(
            pcpContext = pcpContext,
            toolRegistry = toolRegistry,
            resourceProvider = resourceProvider,
            promptProvider = promptProvider,
            serverInfo = serverInfo,
            capabilities = capabilities
        )
        
        server = Server(
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
    }
    
    fun handleRequest(request: JsonRpcRequest): JsonRpcResponse {
        runBlocking {
            PcpRegistry.updateGlobalContext(pcpContext)
        }
        return protocolHandler.route(request)
    }
    
    fun getServer(): Server = server

    suspend fun runOnce() {
        PcpRegistry.updateGlobalContext(pcpContext)
        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered()
        )
        server.createSession(transport)
        runBlocking {
            server.onClose { }
            Thread.currentThread().join()
        }
    }

    suspend fun runLoop() {
        PcpRegistry.updateGlobalContext(pcpContext)
        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered()
        )
        server.createSession(transport)
        runBlocking {
            server.onClose { }
            Thread.currentThread().join()
        }
    }

    fun shutdown() {
        runBlocking { server.close() }
    }
}