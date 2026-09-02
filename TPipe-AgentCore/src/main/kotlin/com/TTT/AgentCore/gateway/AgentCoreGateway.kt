package com.TTT.AgentCore.gateway

import com.TTT.MCP.Client.McpRemoteClient
import com.TTT.Pipe.Pipe
import com.TTT.PipeContextProtocol.getPcpContext

/**
 * Gateway adapter that uses MCP tools as PCP dynamic functions.
 *
 * Gateway policy and identity remain separate concerns: this class only
 * discovers and binds tools through the generic MCP client.
 *
 * @param remoteClient MCP client used to access Gateway tools.
 */
class AgentCoreGateway(
    private val remoteClient: McpRemoteClient
)
{
    public companion object {
        /** Create an IAM-authenticated Gateway adapter with request-aware SigV4 signing. */
        fun withIamAuth(
            endpoint: String,
            region: String,
            credentialsProvider: AgentCoreGatewayCredentialsProvider,
            namespacePrefix: String? = "gateway__"
        ): AgentCoreGateway = AgentCoreGateway(
            McpRemoteClient(
                com.TTT.MCP.Client.McpRemoteClientConfig(
                    endpoint = endpoint,
                    namespacePrefix = namespacePrefix,
                    requestSigner = AgentCoreGatewaySigV4Auth(region, credentialsProvider)
                )
            )
        )
    }

    /** Connect to the gateway MCP endpoint and bind its tools to [pipe].
     *
     * @param pipe Pipe receiving the discovered PCP functions.
     * @return The supplied pipe after binding.
     */
    suspend fun bindTools(pipe: Pipe): Pipe
    {
        val context = pipe.getPcpContext()
        remoteClient.bindToolsToPcp(context)
        pipe.setPcPContext(context)
        return pipe
    }

    /** Return the underlying client for resources, prompts, and raw MCP calls. */
    fun mcpClient(): McpRemoteClient = remoteClient
}
