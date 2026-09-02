package com.TTT.AgentCore.evaluations

import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import com.TTT.AgentCore.AgentCoreClients

/** Evaluation facade that keeps evaluator calls separate from normal pipeline execution. */
class AgentCoreEvaluations(
    private val dataClient: BedrockAgentCoreClient,
    private val controlClient: BedrockAgentCoreControlClient
) {
    /** Execute a data-plane evaluation API call supplied by the caller. */
    suspend fun <T> executeData(block: suspend BedrockAgentCoreClient.() -> T): T = dataClient.block()

    /** Execute a control-plane evaluator/configuration API call supplied by the caller. */
    suspend fun <T> executeControl(block: suspend BedrockAgentCoreControlClient.() -> T): T = controlClient.block()
}

/** Create an evaluation facade from shared clients. */
fun AgentCoreClients.evaluations(): AgentCoreEvaluations = AgentCoreEvaluations(data, control)
