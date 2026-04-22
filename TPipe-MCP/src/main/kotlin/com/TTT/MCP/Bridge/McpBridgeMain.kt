package com.TTT.MCP.Bridge

import com.TTT.MCP.Server.McpBridgeStdioHost

object McpBridgeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        mcpmain(args)
    }
}

fun mcpmain(args: Array<String>) {
    when (args.firstOrNull()) {
        "--mcp-bridge-stdio-once" -> McpBridgeStdioHost.runOnce()
        "--mcp-bridge-stdio-loop" -> McpBridgeStdioHost.runLoop()
        "--help" -> {
            println("TPipe-MCP Bridge Server")
            println("Usage: java -jar TPipe-MCP-*-all.jar [command]")
            println("Commands:")
            println("  --mcp-bridge-stdio-once   Run MCP bridge for single request")
            println("  --mcp-bridge-stdio-loop   Run MCP bridge with request loop")
            println("  --help                    Show this help message")
        }
        else -> {
            println("TPipe-MCP Bridge Server")
            println("Usage: java -jar TPipe-MCP-*-all.jar [command]")
            println("Commands:")
            println("  --mcp-bridge-stdio-once   Run MCP bridge for single request")
            println("  --mcp-bridge-stdio-loop   Run MCP bridge with request loop")
            println("  --help                    Show this help message")
        }
    }
}