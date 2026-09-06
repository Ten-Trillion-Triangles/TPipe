package com.TTT.AgentCore.runtime

/** Raw response used for deliberate AgentCore wire-contract probes. */
data class AgentCoreRuntimeRawResponse(
    val statusCode: Int,
    val body: String,
    val sessionId: String? = null,
    val requestId: String? = null
)
