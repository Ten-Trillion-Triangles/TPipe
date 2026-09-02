package com.TTT.AgentCore.runtime

/** Runtime protocol used for an invocation. */
enum class AgentCoreRuntimeProtocol {
    HTTP,
    MCP,
    AGUI,

    /** @deprecated Use [AGUI]; retained for source compatibility. */
    @Deprecated("Use AGUI")
    AG_UI
}
