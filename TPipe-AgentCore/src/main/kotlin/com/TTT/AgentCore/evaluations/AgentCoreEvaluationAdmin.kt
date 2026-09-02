package com.TTT.AgentCore.evaluations

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients

/** Control-plane evaluator and online-evaluation configuration access. */
class AgentCoreEvaluationAdmin(private val client: BedrockAgentCoreControlClient) {
    /** Create an evaluator. */
    suspend fun createEvaluator(request: CreateEvaluatorRequest): CreateEvaluatorResponse =
        client.createEvaluator(request)

    /** Read an evaluator. */
    suspend fun getEvaluator(request: GetEvaluatorRequest): GetEvaluatorResponse = client.getEvaluator(request)

    /** Update an evaluator. */
    suspend fun updateEvaluator(request: UpdateEvaluatorRequest): UpdateEvaluatorResponse =
        client.updateEvaluator(request)

    /** Delete an evaluator. */
    suspend fun deleteEvaluator(request: DeleteEvaluatorRequest): DeleteEvaluatorResponse =
        client.deleteEvaluator(request)

    /** Execute a control-plane operation that is only present in the pinned SDK model. */
    suspend fun <T> execute(block: suspend BedrockAgentCoreControlClient.() -> T): T = client.block()
}

/** Construct evaluation administration from shared AgentCore clients. */
fun AgentCoreClients.evaluationAdmin(): AgentCoreEvaluationAdmin = AgentCoreEvaluationAdmin(control)
