package com.TTT.AgentCore.gateway

import com.TTT.MCP.Client.McpRemoteClient
import com.TTT.MCP.Client.McpRemoteClientConfig
import com.TTT.Pipe.Pipe
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.getPcpContext

/** Configuration for an AgentCore Gateway MCP endpoint. */
data class AgentCoreGatewayConfig(
    val endpoint: String,
    val namespacePrefix: String? = null,
    val mcp: McpRemoteClientConfig = McpRemoteClientConfig(endpoint)
)

/**
 * Connects to an AgentCore Gateway through its MCP endpoint.
 *
 * The connector deliberately returns the generic PCP context produced by
 * [McpRemoteClient]; Gateway is not a second TPipe tool or transport model.
 */
class AgentCoreGatewayConnector(
    private val remoteClient: McpRemoteClient,
    private val namespacePrefix: String? = null
) : AutoCloseable {
    /** Create a connector from endpoint/auth configuration. */
    constructor(config: AgentCoreGatewayConfig) : this(
        McpRemoteClient(
            config.mcp.copy(
                endpoint = config.endpoint,
                namespacePrefix = config.namespacePrefix ?: config.mcp.namespacePrefix
            )
        ),
        config.namespacePrefix ?: config.mcp.namespacePrefix
    )

    /** Discover Gateway tools and return them as executable PCP functions. */
    suspend fun createPcpContext(): PcpContext = remoteClient.toPcpContext(namespacePrefix)

    /** Attach discovered Gateway tools to an existing Pipe's PCP context. */
    suspend fun attachTo(pipe: Pipe): Pipe {
        val context = pipe.getPcpContext()
        remoteClient.bindToolsToPcp(context, namespacePrefix)
        pipe.setPcPContext(context)
        return pipe
    }

    /** Access the generic MCP connection for resources/prompts/raw calls. */
    fun mcpClient(): McpRemoteClient = remoteClient

    /** Close the underlying MCP session. */
    override fun close() = remoteClient.close()
}
