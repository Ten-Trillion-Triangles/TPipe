package com.TTT.AgentCore.gateway

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients

/**
 * Typed control-plane convenience methods for Gateway lifecycle operations.
 *
 * @param client AgentCore control-plane client.
 */
class AgentCoreGatewayAdmin(private val client: BedrockAgentCoreControlClient)
{
    /** Create a Gateway.
     *
     * @param request Gateway creation request.
     * @return The service response.
     */
    suspend fun create(request: CreateGatewayRequest): CreateGatewayResponse = client.createGateway(request)

    /** Read a Gateway.
     *
     * @param request Gateway lookup request.
     * @return The service response.
     */
    suspend fun get(request: GetGatewayRequest): GetGatewayResponse = client.getGateway(request)

    /** Update a Gateway.
     *
     * @param request Gateway update request.
     * @return The service response.
     */
    suspend fun update(request: UpdateGatewayRequest): UpdateGatewayResponse = client.updateGateway(request)

    /** Delete a Gateway.
     *
     * @param request Gateway deletion request.
     * @return The service response.
     */
    suspend fun delete(request: DeleteGatewayRequest): DeleteGatewayResponse = client.deleteGateway(request)

    /** Create an external MCP target.
     *
     * @param request Target creation request.
     * @return The service response.
     */
    suspend fun createTarget(request: CreateGatewayTargetRequest): CreateGatewayTargetResponse =
        client.createGatewayTarget(request)

    /** Read an external MCP target.
     *
     * @param request Target lookup request.
     * @return The service response.
     */
    suspend fun getTarget(request: GetGatewayTargetRequest): GetGatewayTargetResponse = client.getGatewayTarget(request)

    /** Update an external MCP target.
     *
     * @param request Target update request.
     * @return The service response.
     */
    suspend fun updateTarget(request: UpdateGatewayTargetRequest): UpdateGatewayTargetResponse =
        client.updateGatewayTarget(request)

    /** Delete an external MCP target.
     *
     * @param request Target deletion request.
     * @return The service response.
     */
    suspend fun deleteTarget(request: DeleteGatewayTargetRequest): DeleteGatewayTargetResponse =
        client.deleteGatewayTarget(request)

    /** Start bounded Gateway target synchronization.
     *
     * @param request Synchronization request.
     * @return The service response.
     */
    suspend fun synchronize(request: SynchronizeGatewayTargetsRequest): SynchronizeGatewayTargetsResponse =
        client.synchronizeGatewayTargets(request)
}

/** Build Gateway administration from the shared client bundle. */
fun AgentCoreClients.gatewayAdmin(): AgentCoreGatewayAdmin = AgentCoreGatewayAdmin(control)
