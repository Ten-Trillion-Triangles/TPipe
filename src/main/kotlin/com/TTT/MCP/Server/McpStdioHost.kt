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
     * Run the MCP server once, processing a single request from stdin.
     * Blocking call - runs to completion before returning.
     */
    fun runOnce() {
        runBlocking {
            val pcpContext = PcpContext()
            // FIX S1: Populate tpipeOptions from FunctionRegistry
            FunctionRegistry.listFunctions().forEach { descriptor ->
                pcpContext.addTPipeOption(TPipeContextOptions().apply {
                    functionName = descriptor.name
                })
            }
            val config = McpCapabilityConfig()
            val host = McpServerHost(pcpContext, config.buildServerCapabilities())
            host.runOnce()
        }
    }

    /**
     * Run the MCP server in a loop, continuously processing requests from stdin.
     * Blocking call - runs until EOF or "exit".
     */
    fun runLoop() {
        runBlocking {
            val pcpContext = PcpContext()
            // FIX S1: Populate tpipeOptions from FunctionRegistry
            FunctionRegistry.listFunctions().forEach { descriptor ->
                pcpContext.addTPipeOption(TPipeContextOptions().apply {
                    functionName = descriptor.name
                })
            }
            val config = McpCapabilityConfig()
            val host = McpServerHost(pcpContext, config.buildServerCapabilities())
            host.runLoop()
        }
    }
}