package com.TTT.AgentCore.harness

import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeHarnessRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeHarnessStreamOutput
import com.TTT.AgentCore.AgentCoreClients
import kotlinx.coroutines.flow.collect

/** Data-plane client for invoking an existing AgentCore Harness agent. */
class AgentCoreHarnessClient(private val client: BedrockAgentCoreClient) {
    /**
     * Invoke a Harness agent and consume its stream incrementally.
     *
     * The raw response remains available to callers through [execute]; this
     * helper returns the collected stream items for ordinary P2P adapters.
     */
    suspend fun invoke(
        request: InvokeHarnessRequest,
        onOutput: suspend (InvokeHarnessStreamOutput) -> Unit = {}
    ): List<InvokeHarnessStreamOutput> {
        val outputs = mutableListOf<InvokeHarnessStreamOutput>()
        client.invokeHarness(request) { response ->
            response.stream?.collect { output ->
                outputs += output
                onOutput(output)
            }
        }
        return outputs
    }

    /** Execute a pinned SDK operation not represented by this facade. */
    suspend fun <T> execute(block: suspend BedrockAgentCoreClient.() -> T): T = client.block()
}

/** Construct a Harness client from shared AgentCore clients. */
fun AgentCoreClients.harnessClient(): AgentCoreHarnessClient = AgentCoreHarnessClient(data)
