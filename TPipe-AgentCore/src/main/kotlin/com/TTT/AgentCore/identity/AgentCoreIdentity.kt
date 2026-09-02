package com.TTT.AgentCore.identity

import com.TTT.MCP.Client.McpRemoteAuthProvider
import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.*
import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Loads a short-lived AgentCore workload token without storing static credentials. */
fun interface AgentCoreTokenLoader {
    /** Load a fresh bearer token. */
    suspend fun load(): String
}

/**
 * Dynamic MCP auth provider for workload identity.
 *
 * The token loader is called only when the cached token expires. Tokens are
 * never placed in TPipe P2P descriptors or persisted context.
 */
class AgentCoreIdentityAuthProvider(
    private val loader: AgentCoreTokenLoader,
    private val tokenLifetimeMillis: Long = 50_000L,
    private val now: () -> Long = System::currentTimeMillis
) : McpRemoteAuthProvider {
    private val mutex = Mutex()
    private var token: String? = null
    private var expiresAt: Long = 0L

    override suspend fun headers(): Map<String, String> = mutex.withLock {
        if (token == null || now() >= expiresAt) {
            token = loader.load().also { require(it.isNotBlank()) { "AgentCore token loader returned a blank token." } }
            expiresAt = now() + tokenLifetimeMillis
        }
        mapOf("Authorization" to "Bearer ${checkNotNull(token)}")
    }
}

/** Identity token-cache settings. */
data class AgentCoreIdentityConfig(
    val expirySkewMillis: Long = 5_000L
)

/** Direct data-plane access to the pinned Identity token operations. */
class AgentCoreIdentityProvider(private val client: BedrockAgentCoreClient) {
    /** Request a workload access token. */
    suspend fun getWorkloadAccessToken(request: GetWorkloadAccessTokenRequest): GetWorkloadAccessTokenResponse =
        client.getWorkloadAccessToken(request)

    /** Request a workload token for a JWT. */
    suspend fun getWorkloadAccessTokenForJwt(
        request: GetWorkloadAccessTokenForJwtRequest
    ): GetWorkloadAccessTokenForJwtResponse = client.getWorkloadAccessTokenForJwt(request)

    /** Request a workload token for a user identity. */
    suspend fun getWorkloadAccessTokenForUserId(
        request: GetWorkloadAccessTokenForUserIdRequest
    ): GetWorkloadAccessTokenForUserIdResponse = client.getWorkloadAccessTokenForUserId(request)

    /** Resolve an OAuth resource token. */
    suspend fun getResourceOauth2Token(request: GetResourceOauth2TokenRequest): GetResourceOauth2TokenResponse =
        client.getResourceOauth2Token(request)

    /** Resolve an API-key resource credential. */
    suspend fun getResourceApiKey(request: GetResourceApiKeyRequest): GetResourceApiKeyResponse =
        client.getResourceApiKey(request)
}

/** Construct direct Identity access from shared clients. */
fun AgentCoreClients.identityProvider(): AgentCoreIdentityProvider = AgentCoreIdentityProvider(data)

/** A refreshable, scope-keyed token provider for MCP request headers. */
class AgentCoreTokenProvider(
    private val loader: suspend (scopeKey: String) -> String,
    private val config: AgentCoreIdentityConfig = AgentCoreIdentityConfig(),
    private val now: () -> Long = System::currentTimeMillis,
    private val tokenLifetimeMillis: Long = 50_000L
) : McpRemoteAuthProvider {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedToken>()

    /** Return a bearer header using a short-lived per-scope cache. */
    override suspend fun headers(): Map<String, String> = headers("default")

    /** Return a bearer header for a resource/session scope. */
    suspend fun headers(scopeKey: String): Map<String, String> = mutex.withLock {
        val current = cache[scopeKey]
        val validUntil = now() + config.expirySkewMillis
        val token = if (current == null || current.expiresAt <= validUntil) {
            loader(scopeKey).also {
                require(it.isNotBlank()) { "AgentCore token loader returned a blank token." }
            }.also { loaded ->
                cache[scopeKey] = CachedToken(loaded, now() + tokenLifetimeMillis)
            }
        } else {
            current.value
        }
        mapOf("Authorization" to "Bearer $token")
    }

    /** Remove a scope from the short-lived cache. */
    suspend fun evict(scopeKey: String): Unit = mutex.withLock { cache.remove(scopeKey) }

    private data class CachedToken(val value: String, val expiresAt: Long)
}

/** Typed control-plane access to Identity resource administration. */
class AgentCoreIdentityAdmin(private val client: BedrockAgentCoreControlClient) {
    /** Create a workload identity. */
    suspend fun createWorkloadIdentity(request: CreateWorkloadIdentityRequest): CreateWorkloadIdentityResponse =
        client.createWorkloadIdentity(request)

    /** Get a workload identity. */
    suspend fun getWorkloadIdentity(request: GetWorkloadIdentityRequest): GetWorkloadIdentityResponse =
        client.getWorkloadIdentity(request)

    /** Delete a workload identity. */
    suspend fun deleteWorkloadIdentity(request: DeleteWorkloadIdentityRequest): DeleteWorkloadIdentityResponse =
        client.deleteWorkloadIdentity(request)

    /** Create an OAuth credential provider. */
    suspend fun createOauth2CredentialProvider(
        request: CreateOauth2CredentialProviderRequest
    ): CreateOauth2CredentialProviderResponse = client.createOauth2CredentialProvider(request)

    /** Create an API-key credential provider. */
    suspend fun createApiKeyCredentialProvider(
        request: CreateApiKeyCredentialProviderRequest
    ): CreateApiKeyCredentialProviderResponse = client.createApiKeyCredentialProvider(request)
}

/** Construct Identity administration from shared clients. */
fun AgentCoreClients.identityAdmin(): AgentCoreIdentityAdmin = AgentCoreIdentityAdmin(control)
