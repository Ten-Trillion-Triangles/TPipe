package com.TTT.AgentCore.evaluations

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients

/** Control-plane evaluator and online-evaluation configuration access.
 *
 * @param client AgentCore control-plane client.
 */
class AgentCoreEvaluationAdmin(private val client: BedrockAgentCoreControlClient)
{
    /** Create an evaluator.
     *
     * @param request Evaluator request.
     * @return The evaluator response.
     */
    suspend fun createEvaluator(request: CreateEvaluatorRequest): CreateEvaluatorResponse =
        client.createEvaluator(request)

    /** Read an evaluator.
     *
     * @param request Evaluator lookup request.
     * @return The evaluator response.
     */
    suspend fun getEvaluator(request: GetEvaluatorRequest): GetEvaluatorResponse = client.getEvaluator(request)

    /** Update an evaluator.
     *
     * @param request Evaluator update request.
     * @return The evaluator response.
     */
    suspend fun updateEvaluator(request: UpdateEvaluatorRequest): UpdateEvaluatorResponse =
        client.updateEvaluator(request)

    /** Delete an evaluator.
     *
     * @param request Evaluator delete request.
     * @return The evaluator response.
     */
    suspend fun deleteEvaluator(request: DeleteEvaluatorRequest): DeleteEvaluatorResponse =
        client.deleteEvaluator(request)

    /** Execute a control-plane operation that is only present in the pinned SDK model.
     *
     * @param block SDK operation to execute.
     * @return The operation result.
     */
    suspend fun <T> execute(block: suspend BedrockAgentCoreControlClient.() -> T): T = client.block()
}

/** Construct evaluation administration from shared AgentCore clients. */
fun AgentCoreClients.evaluationAdmin(): AgentCoreEvaluationAdmin = AgentCoreEvaluationAdmin(control)
