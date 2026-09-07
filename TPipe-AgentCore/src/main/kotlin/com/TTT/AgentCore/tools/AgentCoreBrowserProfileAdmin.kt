package com.TTT.AgentCore.tools

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateBrowserProfileRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateBrowserProfileResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteBrowserProfileRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteBrowserProfileResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GetBrowserProfileRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GetBrowserProfileResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.ListBrowserProfilesRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.ListBrowserProfilesResponse
import com.TTT.AgentCore.AgentCoreClients

/** Typed control-plane administration for Browser Profile resources. */
class AgentCoreBrowserProfileAdmin(private val client: BedrockAgentCoreControlClient)
{
    /** Create a Browser Profile using the generated AWS request model. */
    suspend fun create(request: CreateBrowserProfileRequest): CreateBrowserProfileResponse =
        client.createBrowserProfile(request)

    /** Get a Browser Profile using the generated AWS request model. */
    suspend fun get(request: GetBrowserProfileRequest): GetBrowserProfileResponse =
        client.getBrowserProfile(request)

    /** List Browser Profiles using the generated AWS request model. */
    suspend fun list(
        request: ListBrowserProfilesRequest = ListBrowserProfilesRequest { }
    ): ListBrowserProfilesResponse = client.listBrowserProfiles(request)

    /** Delete a Browser Profile using the generated AWS request model. */
    suspend fun delete(request: DeleteBrowserProfileRequest): DeleteBrowserProfileResponse =
        client.deleteBrowserProfile(request)
}

/** Construct Browser Profile administration from shared AgentCore clients. */
fun AgentCoreClients.browserProfileAdmin(): AgentCoreBrowserProfileAdmin = AgentCoreBrowserProfileAdmin(control)
