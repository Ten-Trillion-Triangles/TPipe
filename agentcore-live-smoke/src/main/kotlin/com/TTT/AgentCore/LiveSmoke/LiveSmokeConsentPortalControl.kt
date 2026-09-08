package com.TTT.AgentCore.LiveSmoke

import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.ConsentPortalSourceType
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.ConsentPortalSource
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateConsentPortalRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteConsentPortalRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GetConsentPortalRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.ListConsentPortalsRequest
import com.TTT.AgentCore.AgentCoreClients
import com.TTT.AgentCore.AgentCoreConfig
import com.TTT.AgentCore.identity.identityAdmin
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Narrow control-plane fallback for the consent-portal operation that is not
 * exposed by the configured AWS MCP validator.
 *
 * This command accepts only run-owned identifiers and emits metadata only;
 * it never prints portal URLs, credentials, tokens, or secret values.
 */
fun main()
{
    val region = required("TPIPE_AGENTCORE_REGION")
    val action = (System.getenv("TPIPE_AGENTCORE_CONSENT_PORTAL_ACTION") ?: "create").lowercase()
    val clients = AgentCoreClients(AgentCoreConfig(region))
    try
    {
        runBlocking {
            when(action)
            {
                "create" -> createPortal(clients)
                "get" -> getPortal(clients)
                "delete" -> deletePortal(clients)
                "list" -> listPortals(clients)
                else -> error("TPIPE_AGENTCORE_CONSENT_PORTAL_ACTION must be create, get, delete, or list.")
            }
        }
    }
    finally
    {
        clients.close()
    }
}

private suspend fun createPortal(clients: AgentCoreClients)
{
    val name = required("TPIPE_AGENTCORE_CONSENT_PORTAL_NAME")
    val providerArn = required("TPIPE_AGENTCORE_CONSENT_PROVIDER_ARN")
    val gatewayArn = required("TPIPE_AGENTCORE_CONSENT_GATEWAY_ARN")
    val executionRoleArn = required("TPIPE_AGENTCORE_CONSENT_EXECUTION_ROLE_ARN")
    val idpAudience = System.getenv("TPIPE_AGENTCORE_CONSENT_IDP_AUDIENCE")
        ?.takeIf(String::isNotBlank)
    val response = clients.identityAdmin().createConsentPortal(
        CreateConsentPortalRequest {
            this.name = name
            description = "Run-owned TPipe AgentCore live smoke consent portal."
            this.executionRoleArn = executionRoleArn
            idpConfig {
                credentialProviderArn = providerArn
                scopes = listOf("openid", "email")
                idpAudience?.let { audience = it }
            }
            sources = listOf(ConsentPortalSource {
                identifier = gatewayArn
                type = ConsentPortalSourceType.AgentcoreGateway
            })
        }
    )
    println(
        "created " + metadata(
            response.consentPortalId,
            response.consentPortalArn,
            response.name,
            response.status?.value
        )
    )
}

private suspend fun getPortal(clients: AgentCoreClients)
{
    val response = clients.identityAdmin().getConsentPortal(
        GetConsentPortalRequest { consentPortalIdentifier = required("TPIPE_AGENTCORE_CONSENT_PORTAL_ID") }
    )
    response.portalUrl?.takeIf(String::isNotBlank)?.let { portalUrl ->
        System.getenv("TPIPE_AGENTCORE_CONSENT_PORTAL_URL_FILE")?.let { path ->
            Files.writeString(Path.of(path), portalUrl, StandardCharsets.UTF_8)
        }
    }
    val sourceIds = response.sources.joinToString(",") { it.identifier.orEmpty() }
    val scopes = response.idpConfig?.scopes.orEmpty().joinToString(",")
    println(
        "read " + metadata(response.consentPortalId, response.consentPortalArn, response.name, response.status?.value) +
            " audience=${response.idpConfig?.audience.orEmpty()} scopes=$scopes sources=$sourceIds" +
            " executionRole=${response.executionRoleArn.orEmpty()}"
    )
}

private suspend fun deletePortal(clients: AgentCoreClients)
{
    val id = required("TPIPE_AGENTCORE_CONSENT_PORTAL_ID")
    clients.identityAdmin().deleteConsentPortal(
        DeleteConsentPortalRequest { consentPortalIdentifier = id }
    )
    println("deleted " + metadata(id, null, null, null))
}

private suspend fun listPortals(clients: AgentCoreClients)
{
    val runId = required("TPIPE_AGENTCORE_RUN_ID")
    val response = clients.identityAdmin().listConsentPortals(ListConsentPortalsRequest {})
    response.consentPortals
        .filter { it.name?.contains(runId) == true || it.name?.contains(runId.replace('_', '-')) == true }
        .forEach { portal ->
            println("listed " + metadata(portal.consentPortalId, portal.consentPortalArn, portal.name, portal.status?.value))
        }
}

private fun metadata(id: String?, arn: String?, name: String?, status: String?): String =
    listOfNotNull(
        id?.takeIf(String::isNotBlank)?.let { "id=$it" },
        arn?.takeIf(String::isNotBlank)?.let { "arn=$it" },
        name?.takeIf(String::isNotBlank)?.let { "name=$it" },
        status?.takeIf(String::isNotBlank)?.let { "status=$it" }
    ).joinToString(" ")

private fun required(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
    ?: error("$name is required.")
