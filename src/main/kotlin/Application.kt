package com.TTT

import io.ktor.server.application.*
import io.ktor.server.engine.*
import com.TTT.P2P.P2PStdioHost
import com.TTT.PipeContextProtocol.PcpStdioHost
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
