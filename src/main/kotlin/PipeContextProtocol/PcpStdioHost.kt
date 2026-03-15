package com.TTT.PipeContextProtocol

import com.TTT.P2P.P2PRegistry
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking

/**
 * Host for handling PCP requests over Stdio.
 * Allows external systems to call TPipe's PCP functions via stdin/stdout.
 */
object PcpStdioHost
{
    /**
     * Run the host once, processing a single PCP request from stdin and writing the result to stdout.
     * Supports both a single PcPRequest or a list of PcPRequests as JSON.
     */
    fun runOnce() = runBlocking {
        val input = System.`in`.bufferedReader().readLine() ?: return@runBlocking
        val result = processInput(input)
        println(serialize(result))
    }

    /**
     * Run the host in a loop, processing PCP requests from stdin until EOF or "exit" is received.
     * Each line is treated as a separate JSON payload containing one or more PCP requests.
     */
    fun runLoop() = runBlocking {
        val reader = System.`in`.bufferedReader()
        while(true)
        {
            val input = reader.readLine() ?: break
            if(input.trim().lowercase() == "exit") break

            val result = processInput(input)
            println(serialize(result))
        }
    }

    /**
     * Internal helper to process a single JSON input string into a PcpExecutionResult.
     * @param input The JSON serialized PcPRequest or List<PcPRequest> string.
     * @return The resulting PcpExecutionResult object.
     */
    private suspend fun processInput(input: String): PcpExecutionResult
    {
        val requests = com.TTT.Util.extractJson<List<PcPRequest>>(input)
            ?: com.TTT.Util.extractJson<PcPRequest>(input)?.let { listOf(it) }

        if(requests == null)
        {
            return PcpExecutionResult(
                success = false,
                results = emptyList(),
                executionTimeMs = 0,
                errors = listOf("Failed to deserialize PCP request from stdin")
            )
        }

        // Handle global authentication if set
        val authMechanism = P2PRegistry.globalAuthMechanism
        if(authMechanism != null)
        {
            // For Stdio PCP, we extract the auth token from the first request's callParams.
            val authBody = requests.firstOrNull()?.callParams?.get("authBody") ?: ""
            val isAuthorized = authMechanism(authBody)
            if(!isAuthorized)
            {
                return PcpExecutionResult(
                    success = false,
                    results = emptyList(),
                    executionTimeMs = 0,
                    errors = listOf("Unauthorized PCP request over Stdio")
                )
            }
        }

        return PcpRegistry.executeRequests(requests)
    }
}
