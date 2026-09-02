package com.TTT.AgentCore.runtime

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients

/** Typed control-plane lifecycle access for AgentCore Runtime deployments. */
class AgentCoreRuntimeAdmin(private val client: BedrockAgentCoreControlClient) {
    /** Create a Runtime. */
    suspend fun create(request: CreateAgentRuntimeRequest): CreateAgentRuntimeResponse = client.createAgentRuntime(request)

    /** Read a Runtime. */
    suspend fun get(request: GetAgentRuntimeRequest): GetAgentRuntimeResponse = client.getAgentRuntime(request)

    /** Update a Runtime. */
    suspend fun update(request: UpdateAgentRuntimeRequest): UpdateAgentRuntimeResponse = client.updateAgentRuntime(request)

    /** Delete a Runtime. */
    suspend fun delete(request: DeleteAgentRuntimeRequest): DeleteAgentRuntimeResponse = client.deleteAgentRuntime(request)

    /** Create a Runtime endpoint. */
    suspend fun createEndpoint(request: CreateAgentRuntimeEndpointRequest): CreateAgentRuntimeEndpointResponse =
        client.createAgentRuntimeEndpoint(request)

    /** Read a Runtime endpoint. */
    suspend fun getEndpoint(request: GetAgentRuntimeEndpointRequest): GetAgentRuntimeEndpointResponse =
        client.getAgentRuntimeEndpoint(request)

    /** Update a Runtime endpoint. */
    suspend fun updateEndpoint(request: UpdateAgentRuntimeEndpointRequest): UpdateAgentRuntimeEndpointResponse =
        client.updateAgentRuntimeEndpoint(request)

    /** Delete a Runtime endpoint. */
    suspend fun deleteEndpoint(request: DeleteAgentRuntimeEndpointRequest): DeleteAgentRuntimeEndpointResponse =
        client.deleteAgentRuntimeEndpoint(request)
}

/** Build Runtime administration from shared AgentCore clients. */
fun AgentCoreClients.runtimeAdmin(): AgentCoreRuntimeAdmin = AgentCoreRuntimeAdmin(control)
