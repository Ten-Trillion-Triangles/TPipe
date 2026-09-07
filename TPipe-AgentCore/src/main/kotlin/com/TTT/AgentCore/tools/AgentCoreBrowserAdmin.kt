package com.TTT.AgentCore.tools

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients

/** Control-plane administration for custom AgentCore Browser resources. */
class AgentCoreBrowserAdmin(private val client: BedrockAgentCoreControlClient)
{
    /** Create a custom Browser. */
    suspend fun create(request: CreateBrowserRequest): CreateBrowserResponse = client.createBrowser(request)
    /** Get a custom Browser. */
    suspend fun get(request: GetBrowserRequest): GetBrowserResponse = client.getBrowser(request)
    /** List custom Browsers. */
    suspend fun list(request: ListBrowsersRequest): ListBrowsersResponse = client.listBrowsers(request)
    /** Delete a custom Browser. */
    suspend fun delete(request: DeleteBrowserRequest): DeleteBrowserResponse = client.deleteBrowser(request)
    /** Create a Browser profile. */
    suspend fun createProfile(request: CreateBrowserProfileRequest): CreateBrowserProfileResponse =
        client.createBrowserProfile(request)
    /** Get a Browser profile. */
    suspend fun getProfile(request: GetBrowserProfileRequest): GetBrowserProfileResponse =
        client.getBrowserProfile(request)
    /** List Browser profiles. */
    suspend fun listProfiles(request: ListBrowserProfilesRequest): ListBrowserProfilesResponse =
        client.listBrowserProfiles(request)
    /** Delete a Browser profile. */
    suspend fun deleteProfile(request: DeleteBrowserProfileRequest): DeleteBrowserProfileResponse =
        client.deleteBrowserProfile(request)
}

/** Build Browser administration from the shared client bundle. */
fun AgentCoreClients.browserAdmin(): AgentCoreBrowserAdmin = AgentCoreBrowserAdmin(control)
