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
        client.startBatchEvaluation(request.also { it.requireOneEvaluationMode() })

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

    /** List batch evaluations. */
    suspend fun listBatches(request: ListBatchEvaluationsRequest): ListBatchEvaluationsResponse =
        client.listBatchEvaluations(request)

    /** Delete a batch evaluation. */
    suspend fun deleteBatch(request: DeleteBatchEvaluationRequest): DeleteBatchEvaluationResponse =
        client.deleteBatchEvaluation(request)

    /** Start a recommendation job. */
    suspend fun startRecommendation(
        request: StartRecommendationRequest
    ): StartRecommendationResponse = client.startRecommendation(request)

    /** Get a recommendation job. */
    suspend fun getRecommendation(request: GetRecommendationRequest): GetRecommendationResponse =
        client.getRecommendation(request)

    /** List recommendation jobs. */
    suspend fun listRecommendations(request: ListRecommendationsRequest): ListRecommendationsResponse =
        client.listRecommendations(request)

    /** Delete a recommendation job. */
    suspend fun deleteRecommendation(request: DeleteRecommendationRequest): DeleteRecommendationResponse =
        client.deleteRecommendation(request)

    /** Create an A/B test. */
    suspend fun createAbTest(request: CreateAbTestRequest): CreateAbTestResponse = client.createAbTest(request)

    /** Get an A/B test. */
    suspend fun getAbTest(request: GetAbTestRequest): GetAbTestResponse = client.getAbTest(request)

    /** List A/B tests. */
    suspend fun listAbTests(request: ListAbTestsRequest): ListAbTestsResponse = client.listAbTests(request)

    /** Update an A/B test. */
    suspend fun updateAbTest(request: UpdateAbTestRequest): UpdateAbTestResponse = client.updateAbTest(request)

    /** Delete an A/B test. */
    suspend fun deleteAbTest(request: DeleteAbTestRequest): DeleteAbTestResponse = client.deleteAbTest(request)

    /** Run an A/B test operation using the pinned data client.
     *
     * @param block SDK operation to execute.
     * @return The operation result.
     */
    suspend fun <T> execute(block: suspend BedrockAgentCoreClient.() -> T): T = client.block()
}

/** Validate the mutually exclusive evaluator and Insights configuration. */
internal fun StartBatchEvaluationRequest.requireOneEvaluationMode()
{
    require((evaluators ?: emptyList()).isEmpty() || (insights ?: emptyList()).isEmpty()) {
        "A batch evaluation cannot specify both evaluators and Insights."
    }
}

/** Construct an evaluation client from shared AgentCore clients. */
fun AgentCoreClients.evaluationClient(): AgentCoreEvaluationClient = AgentCoreEvaluationClient(data)
