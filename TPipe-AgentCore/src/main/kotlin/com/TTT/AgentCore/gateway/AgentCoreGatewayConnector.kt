package com.TTT.AgentCore.gateway

import com.TTT.MCP.Client.McpRemoteClient
import com.TTT.MCP.Client.McpRemoteClientConfig
import com.TTT.Pipe.Pipe
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.getPcpContext
import com.TTT.AgentCore.policy.AgentCoreTemporalPolicySession

/**
 * Configuration for an AgentCore Gateway MCP endpoint.
 *
 * @param endpoint Gateway MCP endpoint.
 * @param namespacePrefix Optional PCP namespace prefix.
 * @param mcp Base MCP client configuration.
 * @param temporalPolicySession Optional AgentCore temporal-policy session header.
 */
data class AgentCoreGatewayConfig(
    val endpoint: String,
    val namespacePrefix: String? = null,
    val mcp: McpRemoteClientConfig = McpRemoteClientConfig(endpoint),
    val temporalPolicySession: AgentCoreTemporalPolicySession? = null
)

/** Convert AgentCore Gateway configuration into the generic MCP configuration. */
fun AgentCoreGatewayConfig.toMcpRemoteClientConfig(): McpRemoteClientConfig = mcp.copy(
    endpoint = endpoint,
    namespacePrefix = namespacePrefix ?: mcp.namespacePrefix,
    requestHeaders = mcp.requestHeaders + (temporalPolicySession?.asHeader().orEmpty())
)

/**
 * Connects to an AgentCore Gateway through its MCP endpoint.
 *
 * The connector deliberately returns the generic PCP context produced by
 * [McpRemoteClient]; Gateway is not a second TPipe tool or transport model.
 *
 * @param remoteClient MCP client used to access Gateway tools.
 * @param namespacePrefix Optional PCP namespace prefix.
 */
class AgentCoreGatewayConnector(
    private val remoteClient: McpRemoteClient,
    private val namespacePrefix: String? = null
) : AutoCloseable
{
    /** Create a connector from endpoint/auth configuration. */
    constructor(config: AgentCoreGatewayConfig) : this(
        McpRemoteClient(config.toMcpRemoteClientConfig()),
        config.namespacePrefix ?: config.mcp.namespacePrefix
    )

    /** Discover Gateway tools and return them as executable PCP functions.
     *
     * @return A PCP context containing the discovered tools.
     */
    suspend fun createPcpContext(): PcpContext = remoteClient.toPcpContext(namespacePrefix)

    /** Attach discovered Gateway tools to an existing Pipe's PCP context.
     *
     * @param pipe Pipe receiving the discovered PCP functions.
     * @return The supplied pipe after binding.
     */
    suspend fun attachTo(pipe: Pipe): Pipe
    {
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
