package com.TTT.AgentCore.examples

import com.TTT.AgentCore.gateway.AgentCoreGateway
import com.TTT.MCP.Client.McpRemoteClient
import com.TTT.Pipe.Pipe

/** Bind a generic remote MCP Gateway target to a normal Pipe's PCP context. */
suspend fun bindGatewayExample(pipe: Pipe, client: McpRemoteClient): Pipe =
    AgentCoreGateway(client).bindTools(pipe)
