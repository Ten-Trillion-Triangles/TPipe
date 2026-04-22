package com.TTT.MCP.Bridge

import com.TTT.MCP.Server.McpBridgeHttpHost
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
        "--mcp-bridge-http" -> runHttpServer(args.drop(1).toTypedArray())
        "--help" -> printHelp()
        else -> printHelp()
    }
}

private fun runHttpServer(args: Array<String>) {
    var port = 8080
    var authKey: String? = null
    var bindAddress = "127.0.0.1"

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> {
                i++
                if (i < args.size) port = args[i].toIntOrNull() ?: 8080
            }
            "--auth-key" -> {
                i++
                if (i < args.size) authKey = args[i]
            }
            "--bind-address" -> {
                i++
                if (i < args.size) bindAddress = args[i]
            }
            else -> i++
        }
    }

    McpBridgeHttpHost.run(port, authKey, bindAddress)
}

private fun printHelp() {
    println("TPipe-MCP Bridge Server")
    println("Usage: java -jar TPipe-MCP-*-all.jar [command]")
    println("Commands:")
    println("  --mcp-bridge-stdio-once   Run MCP bridge for single request")
    println("  --mcp-bridge-stdio-loop   Run MCP bridge with request loop")
    println("  --mcp-bridge-http         Run MCP bridge with HTTP server")
    println("                            --port <number>  Port (default 8080)")
    println("                            --auth-key <key>  Optional Bearer auth key")
    println("                            --bind-address <addr>  Bind address (default 127.0.0.1)")
    println("  --help                    Show this help message")
}