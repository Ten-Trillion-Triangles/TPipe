package com.TTT.AgentCore.evaluations

import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.*
import com.TTT.AgentCore.AgentCoreClients

/** Data-plane evaluation access kept separate from normal Pipeline execution.
 *
 * @param client AgentCore data-plane client.
 */
class AgentCoreEvaluationClient(private val client: BedrockAgentCoreClient)
{
    /** Run a direct evaluation.
     *
     * @param request Evaluation request.
     * @return The evaluation response.
     */
    suspend fun evaluate(request: EvaluateRequest): EvaluateResponse = client.evaluate(request)

    /** Start a batch evaluation.
     *
     * @param request Batch-evaluation request.
     * @return The evaluation response.
     */
    suspend fun startBatch(request: StartBatchEvaluationRequest): StartBatchEvaluationResponse =
        client.startBatchEvaluation(request)

    /** Read a batch evaluation.
     *
     * @param request Batch-evaluation lookup request.
     * @return The evaluation response.
     */
    suspend fun getBatch(request: GetBatchEvaluationRequest): GetBatchEvaluationResponse =
        client.getBatchEvaluation(request)

    /** Stop a batch evaluation.
     *
     * @param request Batch-evaluation stop request.
     * @return The evaluation response.
     */
    suspend fun stopBatch(request: StopBatchEvaluationRequest): StopBatchEvaluationResponse =
        client.stopBatchEvaluation(request)

    /** Run an A/B test operation using the pinned data client.
     *
     * @param block SDK operation to execute.
     * @return The operation result.
     */
    suspend fun <T> execute(block: suspend BedrockAgentCoreClient.() -> T): T = client.block()
}

/** Construct an evaluation client from shared AgentCore clients. */
fun AgentCoreClients.evaluationClient(): AgentCoreEvaluationClient = AgentCoreEvaluationClient(data)
