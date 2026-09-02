package com.TTT.AgentCore.control

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import com.TTT.AgentCore.AgentCoreClients

/**
 * Control-plane access for AgentCore resources.
 *
 * The raw SDK client remains available so callers can use the pinned SDK's
 * complete Runtime, Gateway, Identity, Policy, Browser, Code Interpreter,
 * Evaluator, and Harness request models without a lossy wrapper.
 *
 * @param client AgentCore control-plane client.
 */
class AgentCoreControlPlane(private val client: BedrockAgentCoreControlClient)
{
    /** Execute any control-plane API call with the shared client.
     *
     * @param block SDK operation to execute.
     * @return The operation result.
     */
    suspend fun <T> execute(block: suspend BedrockAgentCoreControlClient.() -> T): T = client.block()

    /** Return the raw pinned control-plane client. */
    fun rawClient(): BedrockAgentCoreControlClient = client
}

/** Create a control-plane facade from the shared client bundle. */
fun AgentCoreClients.controlPlane(): AgentCoreControlPlane = AgentCoreControlPlane(control)
