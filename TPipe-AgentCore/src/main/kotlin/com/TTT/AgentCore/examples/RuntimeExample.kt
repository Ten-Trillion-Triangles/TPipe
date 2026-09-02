package com.TTT.AgentCore.examples

import com.TTT.AgentCore.Runtime.AgentCoreRuntimeBootstrap
import com.TTT.AgentCore.Runtime.AgentCoreRuntimeHost
import com.TTT.AgentCore.Runtime.AgentCoreRuntimeHostConfig
import com.TTT.AgentCore.Runtime.AgentCoreSessionContext
import com.TTT.P2P.P2PInterface

/**
 * Example of delegating normal TPipe root construction to the bootstrap.
 *
 * @param buildRoot Factory that creates a root for each runtime session.
 * @param config Runtime host configuration.
 * @return The started runtime host.
 */
fun startRuntimeExample(
    buildRoot: suspend (AgentCoreSessionContext) -> P2PInterface,
    config: AgentCoreRuntimeHostConfig = AgentCoreRuntimeHostConfig()
): AgentCoreRuntimeHost = AgentCoreRuntimeBootstrap.start(config, wait = false, buildRoot)
