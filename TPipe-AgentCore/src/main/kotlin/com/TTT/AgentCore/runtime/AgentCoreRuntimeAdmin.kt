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
    suspend fun create(request: CreateAgentRuntimeRequest): CreateAgentRuntimeResponse = client.createAgentRuntime(request)

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
    suspend fun update(request: UpdateAgentRuntimeRequest): UpdateAgentRuntimeResponse = client.updateAgentRuntime(request)

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
}

/** Build Runtime administration from shared AgentCore clients. */
fun AgentCoreClients.runtimeAdmin(): AgentCoreRuntimeAdmin = AgentCoreRuntimeAdmin(control)
