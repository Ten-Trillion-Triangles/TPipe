package com.TTT.AgentCore.policy

/** AgentCore-only propagation helper for temporal policy sessions. */
@JvmInline
value class AgentCoreTemporalPolicySession(val id: String)
{
    init { require(id.isNotBlank()) { "A temporal policy-session id is required." } }

    /** Header name defined by AgentCore for temporal policy calls. */
    val headerName: String get() = HEADER_NAME

    /** Return this session as a request header map. */
    fun asHeader(): Map<String, String> = mapOf(headerName to id)

    public companion object
    {
        /** AgentCore-only header used to carry a temporal policy session. */
        const val HEADER_NAME = "x-amzn-bedrock-agentcore-policy-session-id"
    }
}
