package com.TTT.AgentCore.examples

import com.TTT.AgentCore.gateway.AgentCoreGateway
import com.TTT.MCP.Client.McpRemoteClient
import com.TTT.Pipe.Pipe

/**
 * Bind a generic remote MCP Gateway target to a normal Pipe's PCP context.
 *
 * @param pipe Pipe whose PCP context receives the gateway tools.
 * @param client Remote MCP client used by the gateway.
 * @return The supplied pipe after its tools are bound.
 */
suspend fun bindGatewayExample(pipe: Pipe, client: McpRemoteClient): Pipe =
    AgentCoreGateway(client).bindTools(pipe)
