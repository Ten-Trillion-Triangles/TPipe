package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.TPipeContextOptions
import kotlinx.coroutines.runBlocking

/**
 * Thin object wrapper for MCP stdio hosting.
 * Provides entry points for Application.kt integration following the PcpStdioHost pattern.
 */
object McpStdioHost {

    /**
     * Create an MCP server host with PCP context populated from FunctionRegistry.
     */
    private fun createHost(): McpServerHost {
        val pcpContext = PcpContext()
        FunctionRegistry.listFunctions().forEach { descriptor ->
            pcpContext.addTPipeOption(TPipeContextOptions().apply {
                functionName = descriptor.name
            })
        }
        val config = McpCapabilityConfig()
        return McpServerHost(pcpContext, config.buildServerCapabilities())
    }

    /**
     * Run the MCP server once, processing a single request from stdin.
     * Blocking call - runs to completion before returning.
     */
    fun runOnce() {
        runBlocking {
            createHost().runOnce()
        }
    }

    /**
     * Run the MCP server in a loop, continuously processing requests from stdin.
     * Blocking call - runs until EOF or "exit".
     */
    fun runLoop() {
        runBlocking {
            createHost().runLoop()
        }
    }
}
