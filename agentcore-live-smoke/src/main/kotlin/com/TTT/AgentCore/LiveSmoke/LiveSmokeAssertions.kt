package com.TTT.AgentCore.LiveSmoke

import aws.sdk.kotlin.services.bedrockagentcore.model.CapacityProviderSessionStatus
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Parsed, non-secret values returned by a disposable OAuth callback. */
data class LiveSmokeOAuthCallback(
    val code: String,
    val state: String?
)

/** Runtime ARN and endpoint qualifier used by the typed InvokeAgentRuntime API. */
data class LiveSmokeRuntimeEndpoint(
    val runtimeArn: String,
    val qualifier: String
)

/** Pure assertions shared by the live runner and its deterministic tests. */
object LiveSmokeAssertions
{
    /** Parse an authorization-code callback without retaining the full callback URL. */
    fun parseOAuthCallback(callbackUrl: String, expectedState: String? = null): LiveSmokeOAuthCallback
    {
        val uri = URI(callbackUrl)
        val values = parseQuery(uri.rawQuery.orEmpty())
        val error = values["error"]
        require(error.isNullOrBlank()) { "OAuth authorization failed: $error" }
        val code = values["code"].orEmpty()
        require(code.isNotBlank()) { "OAuth callback did not contain an authorization code." }
        val state = values["state"]
        if(expectedState != null)
        {
            require(state == expectedState) { "OAuth callback state did not match the authorization request." }
        }
        return LiveSmokeOAuthCallback(code = code, state = state)
    }

    /** Redact callback query values before a URL can cross a report or log boundary. */
    fun redactOAuthCallbackUrl(callbackUrl: String): String
    {
        val uri = URI(callbackUrl)
        val redactedQuery = parseQuery(uri.rawQuery.orEmpty()).keys.sorted().joinToString("&") { key ->
            "${encode(key)}=[REDACTED]"
        }
        return URI(uri.scheme, uri.rawAuthority, uri.path, redactedQuery.ifBlank { null }, null).toString()
    }

    /** Return whether a capacity-session deletion response has reached a safe terminal state. */
    fun isCapacitySessionDeletionTerminal(status: CapacityProviderSessionStatus): Boolean =
        status == CapacityProviderSessionStatus.Deleting || status == CapacityProviderSessionStatus.Deleted

    /** Return whether a Registry has completed an eventually-consistent update. */
    fun isRegistryReady(status: String?): Boolean = status.equals("READY", ignoreCase = true)

    /** Return whether Gateway target synchronization has reached a usable state. */
    fun isGatewayTargetReady(status: String?): Boolean = status.equals("READY", ignoreCase = true)

    /** Build the exact PCP name for a discovered MCP tool. */
    fun namespacedPcpFunctionName(namespacePrefix: String, toolName: String): String =
        namespacePrefix + toolName

    /** Return whether a Registry record has returned to the editable draft state. */
    fun isRegistryRecordDraft(status: String?): Boolean = status.equals("DRAFT", ignoreCase = true)

    /** Split a manifest endpoint ARN without inventing either component. */
    fun runtimeInvocationEndpoint(runtimeEndpointArn: String): LiveSmokeRuntimeEndpoint
    {
        val separator = "/runtime-endpoint/"
        val runtimeArn = runtimeEndpointArn.substringBefore(separator)
        val qualifier = runtimeEndpointArn.substringAfter(separator, "DEFAULT")
        require(runtimeArn.startsWith("arn:")) { "Runtime endpoint identity is not an ARN." }
        require(qualifier.isNotBlank()) { "Runtime endpoint qualifier is blank." }
        return LiveSmokeRuntimeEndpoint(runtimeArn, qualifier)
    }

    private fun parseQuery(rawQuery: String): Map<String, String>
    {
        if(rawQuery.isBlank()) return emptyMap()
        return rawQuery.split('&')
            .filter(String::isNotBlank)
            .mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                val key = decode(parts[0])
                if(key.isBlank()) null else key to decode(parts.getOrElse(1) { "" })
            }
            .toMap()
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
}
