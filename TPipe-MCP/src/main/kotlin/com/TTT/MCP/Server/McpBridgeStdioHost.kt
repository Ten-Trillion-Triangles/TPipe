package com.TTT.MCP.Server

import kotlinx.coroutines.runBlocking

/**
 * Entry point object for MCP bridge stdio hosting.
 * Provides runOnce() and runLoop() for --mcp-bridge-stdio-once and --mcp-bridge-stdio-loop CLI args.
 */
object McpBridgeStdioHost {

    private const val ENV_MCP_JSON = "TPIPE_MCP_JSON"

    private fun getMcpJson(): String {
        return System.getenv(ENV_MCP_JSON)
            ?: throw IllegalStateException("Missing $ENV_MCP_JSON environment variable")
    }

    private fun createHost(): McpBridgeServerHost {
        val mcpJson = getMcpJson()
        return McpBridgeServerHost(mcpJson)
    }

    fun runOnce() {
        runBlocking {
            createHost().runOnce()
        }
    }

    fun runLoop() {
        runBlocking {
            createHost().runLoop()
        }
    }
}