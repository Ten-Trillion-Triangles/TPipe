package com.TTT.AgentCore.harness

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients

/** Control-plane lifecycle access for external Harness agents. */
class AgentCoreHarnessAdmin(private val client: BedrockAgentCoreControlClient) {
    /** Create a Harness. */
    suspend fun create(request: CreateHarnessRequest): CreateHarnessResponse = client.createHarness(request)

    /** Read a Harness. */
    suspend fun get(request: GetHarnessRequest): GetHarnessResponse = client.getHarness(request)

    /** Delete a Harness. */
    suspend fun delete(request: DeleteHarnessRequest): DeleteHarnessResponse = client.deleteHarness(request)

    /** Create a Harness endpoint. */
    suspend fun createEndpoint(request: CreateHarnessEndpointRequest): CreateHarnessEndpointResponse =
        client.createHarnessEndpoint(request)

    /** Delete a Harness endpoint. */
    suspend fun deleteEndpoint(request: DeleteHarnessEndpointRequest): DeleteHarnessEndpointResponse =
        client.deleteHarnessEndpoint(request)
}

/** Construct Harness administration from shared clients. */
fun AgentCoreClients.harnessAdmin(): AgentCoreHarnessAdmin = AgentCoreHarnessAdmin(control)
