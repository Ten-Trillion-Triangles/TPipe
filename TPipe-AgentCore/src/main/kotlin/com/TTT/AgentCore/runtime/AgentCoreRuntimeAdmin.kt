package com.TTT.AgentCore.runtime

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients

/** Typed control-plane lifecycle access for AgentCore Runtime deployments.
 *
 * @param client AgentCore control-plane client.
 */
class AgentCoreRuntimeAdmin(private val client: BedrockAgentCoreControlClient)
{
    /** Create a Runtime.
     *
     * @param request Runtime creation request.
     * @return The service response.
     */
    suspend fun create(request: CreateAgentRuntimeRequest): CreateAgentRuntimeResponse =
        client.createAgentRuntime(request.also(::validateRuntimeStorage))

    /** Read a Runtime.
     *
     * @param request Runtime lookup request.
     * @return The service response.
     */
    suspend fun get(request: GetAgentRuntimeRequest): GetAgentRuntimeResponse = client.getAgentRuntime(request)

    /** Update a Runtime.
     *
     * @param request Runtime update request.
     * @return The service response.
     */
    suspend fun update(request: UpdateAgentRuntimeRequest): UpdateAgentRuntimeResponse =
        client.updateAgentRuntime(request.also(::validateRuntimeStorage))

    /** Delete a Runtime.
     *
     * @param request Runtime deletion request.
     * @return The service response.
     */
    suspend fun delete(request: DeleteAgentRuntimeRequest): DeleteAgentRuntimeResponse = client.deleteAgentRuntime(request)

    /** Create a Runtime endpoint.
     *
     * @param request Endpoint creation request.
     * @return The service response.
     */
    suspend fun createEndpoint(request: CreateAgentRuntimeEndpointRequest): CreateAgentRuntimeEndpointResponse =
        client.createAgentRuntimeEndpoint(request)

    /** Read a Runtime endpoint.
     *
     * @param request Endpoint lookup request.
     * @return The service response.
     */
    suspend fun getEndpoint(request: GetAgentRuntimeEndpointRequest): GetAgentRuntimeEndpointResponse =
        client.getAgentRuntimeEndpoint(request)

    /** Update a Runtime endpoint.
     *
     * @param request Endpoint update request.
     * @return The service response.
     */
    suspend fun updateEndpoint(request: UpdateAgentRuntimeEndpointRequest): UpdateAgentRuntimeEndpointResponse =
        client.updateAgentRuntimeEndpoint(request)

    /** Delete a Runtime endpoint.
     *
     * @param request Endpoint deletion request.
     * @return The service response.
     */
    suspend fun deleteEndpoint(request: DeleteAgentRuntimeEndpointRequest): DeleteAgentRuntimeEndpointResponse =
        client.deleteAgentRuntimeEndpoint(request)

    /** Create an EC2-backed Runtime capacity provider. */
    suspend fun createCapacityProvider(
        request: CreateCapacityProviderRequest
    ): CreateCapacityProviderResponse = client.createCapacityProvider(request)

    /** Get a Runtime capacity provider. */
    suspend fun getCapacityProvider(
        request: GetCapacityProviderRequest
    ): GetCapacityProviderResponse = client.getCapacityProvider(request)

    /** List Runtime capacity providers. */
    suspend fun listCapacityProviders(
        request: ListCapacityProvidersRequest
    ): ListCapacityProvidersResponse = client.listCapacityProviders(request)

    /** Update a Runtime capacity provider. */
    suspend fun updateCapacityProvider(
        request: UpdateCapacityProviderRequest
    ): UpdateCapacityProviderResponse = client.updateCapacityProvider(request)

    /** Delete a Runtime capacity provider. */
    suspend fun deleteCapacityProvider(
        request: DeleteCapacityProviderRequest
    ): DeleteCapacityProviderResponse = client.deleteCapacityProvider(request)

    /** List Runtime versions associated with a capacity provider. */
    suspend fun listAgentRuntimeVersionsByCapacityProvider(
        request: ListAgentRuntimeVersionsByCapacityProviderRequest
    ): ListAgentRuntimeVersionsByCapacityProviderResponse =
        client.listAgentRuntimeVersionsByCapacityProvider(request)
}

/** Validate deterministic compute/storage combinations before an AWS call. */
private fun validateRuntimeStorage(request: CreateAgentRuntimeRequest)
{
    val instances = request.capacityProviderConfiguration != null
    val vpc = request.networkConfiguration?.networkMode == NetworkMode.Vpc
    request.filesystemConfigurations.orEmpty().forEach { filesystem ->
        when
        {
            filesystem.asCapacityProviderVolumeOrNull() != null -> require(instances) {
                "Capacity-provider volumes require an Instances runtime."
            }
            filesystem.asSessionStorageOrNull() != null -> require(!instances) {
                "Managed session storage requires a microVM runtime."
            }
            filesystem.asS3FilesAccessPointOrNull() != null || filesystem.asEfsAccessPointOrNull() != null -> {
                require(!instances && vpc) {
                    "S3 Files and EFS runtime storage require a microVM VPC runtime."
                }
            }
        }
    }
}

/** Validate deterministic compute/storage combinations for Runtime updates. */
private fun validateRuntimeStorage(request: UpdateAgentRuntimeRequest)
{
    // Update requests are partial. A missing field does not mean that the
    // existing Runtime uses microVM compute or a public network mode.
    val explicitlyInstances = request.capacityProviderConfiguration != null
    val explicitlyNonVpc = request.networkConfiguration?.networkMode?.let { it != NetworkMode.Vpc } == true
    request.filesystemConfigurations.orEmpty().forEach { filesystem ->
        when
        {
            filesystem.asSessionStorageOrNull() != null -> require(!explicitlyInstances) {
                "Managed session storage requires a microVM runtime."
            }
            filesystem.asS3FilesAccessPointOrNull() != null || filesystem.asEfsAccessPointOrNull() != null -> {
                require(!explicitlyInstances && !explicitlyNonVpc) {
                    "S3 Files and EFS runtime storage conflict with the explicit Runtime update configuration."
                }
            }
        }
    }
}

/** Build Runtime administration from shared AgentCore clients. */
fun AgentCoreClients.runtimeAdmin(): AgentCoreRuntimeAdmin = AgentCoreRuntimeAdmin(control)
