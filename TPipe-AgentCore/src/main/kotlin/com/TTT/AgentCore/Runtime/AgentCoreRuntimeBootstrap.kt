package com.TTT.AgentCore.Runtime

import com.TTT.P2P.P2PInterface

/**
 * Thin consumer-owned entry point for hosting an existing TPipe root.
 *
 * This bootstrap creates the AgentCore adapter only. The supplied factory
 * remains responsible for constructing the consumer's normal Pipeline,
 * Manifold, or other [P2PInterface] root.
 */
object AgentCoreRuntimeBootstrap {
    /**
     * Build and start a runtime host using a session-aware root factory.
     *
     * @param config Runtime host settings; environment defaults are used when omitted.
     * @param wait Whether to block after starting the embedded server.
     * @param buildRoot Consumer-owned factory for one root per isolated session.
     * @return The started host, which the application can close during shutdown.
     */
    fun start(
        config: AgentCoreRuntimeHostConfig = fromEnvironment(),
        wait: Boolean = true,
        buildRoot: suspend (AgentCoreSessionContext) -> P2PInterface
    ): AgentCoreRuntimeHost
    {
        val host = AgentCoreRuntimeHost(config, AgentCoreSessionFactory(buildRoot))
        host.start(wait)
        return host
    }

    /** Resolve non-secret bind settings from the container environment. */
    fun fromEnvironment(): AgentCoreRuntimeHostConfig = AgentCoreRuntimeHostConfig(
        bindAddress = System.getenv("TPIPE_AGENTCORE_BIND_ADDRESS") ?: "0.0.0.0",
        port = System.getenv("TPIPE_AGENTCORE_PORT")?.toIntOrNull() ?: 8080
    )
}
