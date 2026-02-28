package com.TTT.P2P

import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import java.util.Scanner

/**
 * Host for handling P2P requests over Stdio.
 */
object P2PStdioHost
{

    /**
     * Run the host once, processing a single P2PRequest from stdin and writing a P2PResponse to stdout.
     */
    fun runOnce() = runBlocking {
        val scanner = Scanner(System.`in`)
        if(scanner.hasNextLine())
        {
            val input = scanner.nextLine()
            val response = processInput(input)
            println(serialize(response))
        }
    }

    /**
     * Run the host in a loop, processing P2PRequests from stdin until EOF or "exit" is received.
     */
    fun runLoop() = runBlocking {
        val scanner = Scanner(System.`in`)
        while(scanner.hasNextLine())
        {
            val input = scanner.nextLine()
            if(input.trim().lowercase() == "exit") break

            val response = processInput(input)
            println(serialize(response))
        }
    }

    /**
     * Internal helper to process a single JSON input string into a P2PResponse.
     * @param input The JSON serialized P2PRequest string.
     * @return The resulting P2PResponse object.
     */
    private suspend fun processInput(input: String): P2PResponse
    {
        val request = deserialize<P2PRequest>(input) ?: return P2PResponse().apply {
            rejection = P2PRejection(P2PError.transport, "Failed to deserialize P2PRequest from stdin")
        }

        // Handle global authentication if set
        val authMechanism = P2PRegistry.globalAuthMechanism
        if(authMechanism != null)
        {
            val isAuthorized = authMechanism(request.transport.transportAuthBody)
            if(!isAuthorized)
            {
                return P2PResponse().apply {
                    rejection = P2PRejection(P2PError.auth, "Unauthorized P2P request")
                }
            }
        }

        return P2PRegistry.executeP2pRequest(request)
    }
}
