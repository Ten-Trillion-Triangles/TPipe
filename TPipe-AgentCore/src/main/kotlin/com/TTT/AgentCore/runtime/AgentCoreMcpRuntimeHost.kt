package com.TTT.AgentCore.runtime

import com.TTT.MCP.Server.McpBridgeHttpHost
import com.TTT.MCP.Server.McpHttpHostConfig

/** Configuration for an AgentCore-compatible MCP endpoint. */
data class AgentCoreMcpRuntimeHostConfig(
    val bindAddress: String = "0.0.0.0",
    val port: Int = 8000,
    val path: String = "/mcp",
    val authKey: String? = null
)

/**
 * Hosts TPipe's existing MCP bridge at the AgentCore MCP path.
 *
 * This adapter delegates to the generic TPipe-MCP host and does not modify
 * the core transport enum or create an AgentCore-specific PCP transport.
 */
object AgentCoreMcpRuntimeHost {
    /** Run the MCP endpoint until the hosting process exits. */
    fun run(config: AgentCoreMcpRuntimeHostConfig = AgentCoreMcpRuntimeHostConfig()) {
        McpBridgeHttpHost.run(
            McpHttpHostConfig(
                port = config.port,
                authKey = config.authKey,
                bindAddress = config.bindAddress,
                path = config.path
            )
        )
    }
}
