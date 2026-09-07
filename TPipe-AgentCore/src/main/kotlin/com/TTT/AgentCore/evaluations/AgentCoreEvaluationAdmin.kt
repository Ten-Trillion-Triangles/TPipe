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

    /** List evaluators. */
    suspend fun listEvaluators(request: ListEvaluatorsRequest): ListEvaluatorsResponse = client.listEvaluators(request)

    /** Create an online-evaluation configuration. */
    suspend fun createOnlineEvaluationConfig(
        request: CreateOnlineEvaluationConfigRequest
    ): CreateOnlineEvaluationConfigResponse = client.createOnlineEvaluationConfig(request)

    /** Get an online-evaluation configuration. */
    suspend fun getOnlineEvaluationConfig(
        request: GetOnlineEvaluationConfigRequest
    ): GetOnlineEvaluationConfigResponse = client.getOnlineEvaluationConfig(request)

    /** List online-evaluation configurations. */
    suspend fun listOnlineEvaluationConfigs(
        request: ListOnlineEvaluationConfigsRequest
    ): ListOnlineEvaluationConfigsResponse = client.listOnlineEvaluationConfigs(request)

    /** Update an online-evaluation configuration. */
    suspend fun updateOnlineEvaluationConfig(
        request: UpdateOnlineEvaluationConfigRequest
    ): UpdateOnlineEvaluationConfigResponse = client.updateOnlineEvaluationConfig(request)

    /** Delete an online-evaluation configuration. */
    suspend fun deleteOnlineEvaluationConfig(
        request: DeleteOnlineEvaluationConfigRequest
    ): DeleteOnlineEvaluationConfigResponse = client.deleteOnlineEvaluationConfig(request)

    /** Create a dataset. */
    suspend fun createDataset(request: CreateDatasetRequest): CreateDatasetResponse = client.createDataset(request)

    /** Get a dataset. */
    suspend fun getDataset(request: GetDatasetRequest): GetDatasetResponse = client.getDataset(request)

    /** List datasets. */
    suspend fun listDatasets(request: ListDatasetsRequest): ListDatasetsResponse = client.listDatasets(request)

    /** Update a dataset. */
    suspend fun updateDataset(request: UpdateDatasetRequest): UpdateDatasetResponse = client.updateDataset(request)

    /** Delete a dataset. */
    suspend fun deleteDataset(request: DeleteDatasetRequest): DeleteDatasetResponse = client.deleteDataset(request)

    /** Add examples to a dataset. */
    suspend fun addDatasetExamples(request: AddDatasetExamplesRequest): AddDatasetExamplesResponse =
        client.addDatasetExamples(request)

    /** List dataset examples. */
    suspend fun listDatasetExamples(request: ListDatasetExamplesRequest): ListDatasetExamplesResponse =
        client.listDatasetExamples(request)

    /** Update dataset examples. */
    suspend fun updateDatasetExamples(request: UpdateDatasetExamplesRequest): UpdateDatasetExamplesResponse =
        client.updateDatasetExamples(request)

    /** Delete dataset examples. */
    suspend fun deleteDatasetExamples(request: DeleteDatasetExamplesRequest): DeleteDatasetExamplesResponse =
        client.deleteDatasetExamples(request)

    /** Create a dataset version. */
    suspend fun createDatasetVersion(request: CreateDatasetVersionRequest): CreateDatasetVersionResponse =
        client.createDatasetVersion(request)

    /** List dataset versions. */
    suspend fun listDatasetVersions(request: ListDatasetVersionsRequest): ListDatasetVersionsResponse =
        client.listDatasetVersions(request)

    /** Create a configuration bundle. */
    suspend fun createConfigurationBundle(
        request: CreateConfigurationBundleRequest
    ): CreateConfigurationBundleResponse = client.createConfigurationBundle(request)

    /** Get a configuration bundle. */
    suspend fun getConfigurationBundle(
        request: GetConfigurationBundleRequest
    ): GetConfigurationBundleResponse = client.getConfigurationBundle(request)

    /** List configuration bundles. */
    suspend fun listConfigurationBundles(
        request: ListConfigurationBundlesRequest
    ): ListConfigurationBundlesResponse = client.listConfigurationBundles(request)

    /** Update a configuration bundle. */
    suspend fun updateConfigurationBundle(
        request: UpdateConfigurationBundleRequest
    ): UpdateConfigurationBundleResponse = client.updateConfigurationBundle(request)

    /** Delete a configuration bundle. */
    suspend fun deleteConfigurationBundle(
        request: DeleteConfigurationBundleRequest
    ): DeleteConfigurationBundleResponse = client.deleteConfigurationBundle(request)

    /** Get one configuration-bundle version. */
    suspend fun getConfigurationBundleVersion(
        request: GetConfigurationBundleVersionRequest
    ): GetConfigurationBundleVersionResponse = client.getConfigurationBundleVersion(request)

    /** List configuration-bundle versions. */
    suspend fun listConfigurationBundleVersions(
        request: ListConfigurationBundleVersionsRequest
    ): ListConfigurationBundleVersionsResponse = client.listConfigurationBundleVersions(request)

    /** Execute a control-plane operation that is only present in the pinned SDK model.
     *
     * @param block SDK operation to execute.
     * @return The operation result.
     */
    suspend fun <T> execute(block: suspend BedrockAgentCoreControlClient.() -> T): T = client.block()
}

/** Construct evaluation administration from shared AgentCore clients. */
fun AgentCoreClients.evaluationAdmin(): AgentCoreEvaluationAdmin = AgentCoreEvaluationAdmin(control)
