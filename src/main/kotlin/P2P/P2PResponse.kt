package com.TTT.P2P

import com.TTT.Pipe.MultimodalContent
import io.ktor.utils.io.KtorDsl

/**
 * Error enum for p2p rejections. Helps denote categories of reasons the agent did not allow the p2p connection.
 */
enum class P2PError
{
    auth,
    prompt,
    json,
    content,
    transport,
    context,
    configuration,
    none
}

/**
 * Data class containing a p2p error and reason for the error. This occurs prior to the llm even running, and is typically
 * a result of either a connection issue, or failing to conform to specific formatting requirements of the p2p request.
 */
@kotlinx.serialization.Serializable
data class P2PRejection(
    var errorType: P2PError = P2PError.none,
    var reason: String = ""
)

/**
 * Response object from a p2p request. Contains the output of the agent, and any rejection error caused by faliing
 * to connect, or having the connection rejected.
 */
@kotlinx.serialization.Serializable
data class P2PResponse(
    var output: MultimodalContent? = null,
    var rejection: P2PRejection? = null
)
