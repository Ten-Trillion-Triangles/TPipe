package com.TTT

import io.ktor.server.application.*
import io.ktor.server.engine.*
import com.TTT.P2P.P2PStdioHost
import com.TTT.PipeContextProtocol.PcpStdioHost
import com.TTT.MCP.Server.McpStdioHost
import com.TTT.MCP.Server.McpHttpHost
import com.TTT.Config.TPipeConfig
import io.ktor.server.netty.*

/**
 * Main entry point for starting the TPipe host.
 * Supports command-line arguments to choose the hosting mode.
 * @param args Command-line arguments.
 */
fun main(args: Array<String>)
{
    if(args.contains("--remote-memory"))
    {
        TPipeConfig.remoteMemoryEnabled = true
    }

    if(args.isEmpty() || args.contains("--http") || args.contains("--remote-memory"))
    {
        // Start HTTP host (Ktor)
        embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
            .start(wait = true)
    }
    else if(args.contains("--stdio-once"))
    {
        P2PStdioHost.runOnce()
    }
    else if(args.contains("--stdio-loop"))
    {
        P2PStdioHost.runLoop()
    }
    else if(args.contains("--pcp-stdio-once"))
    {
        PcpStdioHost.runOnce()
    }
    else if(args.contains("--pcp-stdio-loop"))
    {
        PcpStdioHost.runLoop()
    }
    else if(args.contains("--mcp-stdio-once"))
    {
        McpStdioHost.runOnce()
    }
    else if(args.contains("--mcp-stdio-loop"))
    {
        McpStdioHost.runLoop()
    }
    else if(args.contains("--mcp-http"))
    {
        val port = extractMcpHttpPort(args)
        val authKey = extractMcpHttpAuthKey(args)
        val bindAddress = extractMcpHttpBindAddress(args)
        McpHttpHost.run(port, authKey, bindAddress)
    }
}

/**
 * Configuration module for the Ktor application.
 */
fun Application.module()
{
    configureSerialization()
    configureRouting()
}

/**
 * Global application module configuration helper.
 */
fun module() : Application.() -> Unit = {
    configureSerialization()
    configureRouting()
}

private fun extractMcpHttpPort(args: Array<String>): Int {
    val portArg = args.find { it.startsWith("--mcp-http-port=") }
    if(portArg != null) {
        return portArg.substringAfter("=").toIntOrNull() ?: 8090
    }
    return System.getenv("TPIPE_MCP_HTTP_PORT")?.toIntOrNull() ?: 8090
}

private fun extractMcpHttpAuthKey(args: Array<String>): String? {
    val authKeyArg = args.find { it.startsWith("--mcp-http-auth-key=") }
    if(authKeyArg != null) {
        return authKeyArg.substringAfter("=").ifEmpty { null }
    }
    return System.getenv("TPIPE_MCP_HTTP_AUTH_KEY")
}

private fun extractMcpHttpBindAddress(args: Array<String>): String {
    val bindArg = args.find { it.startsWith("--mcp-http-bind=") }
    if(bindArg != null) {
        return bindArg.substringAfter("=").ifEmpty { "127.0.0.1" }
    }
    return System.getenv("TPIPE_MCP_HTTP_BIND") ?: "127.0.0.1"
}