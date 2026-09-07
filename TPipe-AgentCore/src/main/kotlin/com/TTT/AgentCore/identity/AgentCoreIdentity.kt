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
    /** Load a fresh bearer token.
     *
     * @return A non-blank bearer token.
     */
    suspend fun load(): String
}

/**
 * Dynamic MCP auth provider for workload identity.
 *
 * The token loader is called only when the cached token expires. Tokens are
 * never placed in TPipe P2P descriptors or persisted context.
 *
 * @param loader Source for fresh bearer tokens.
 * @param tokenLifetimeMillis Duration for which a loaded token is cached.
 * @param now Clock used to evaluate token expiry.
 */
class AgentCoreIdentityAuthProvider(
    private val loader: AgentCoreTokenLoader,
    private val tokenLifetimeMillis: Long = 50_000L,
    private val now: () -> Long = System::currentTimeMillis
) : McpRemoteAuthProvider
{
    init
    {
        require(tokenLifetimeMillis > 0L) { "Identity token lifetime must be positive." }
    }

    private val mutex = Mutex()
    private var token: AgentCoreSecretToken? = null
    private var expiresAt: Long = 0L

    override suspend fun headers(): Map<String, String> = mutex.withLock {
        if(token == null || now() >= expiresAt)
        {
            token = AgentCoreSecretToken.of(loader.load())
            expiresAt = now() + tokenLifetimeMillis
        }

        mapOf("Authorization" to checkNotNull(token).authorizationHeader())
    }
}

/** Identity token-cache settings.
 *
 * @param expirySkewMillis Duration subtracted from the token lifetime when validating cache entries.
 */
data class AgentCoreIdentityConfig(
    val expirySkewMillis: Long = 5_000L
)
{
    init
    {
        require(expirySkewMillis >= 0L) { "Identity token expiry skew must not be negative." }
    }
}

/** Non-secret dimensions that distinguish cached AgentCore token scopes. */
data class AgentCoreTokenScope(
    val principalKind: PrincipalKind,
    val principalId: String,
    val resourceId: String,
    val scopes: Set<String> = emptySet()
)
{
    init
    {
        require(principalId.isNotBlank()) { "A token principal id is required." }
        require(resourceId.isNotBlank()) { "A token resource id is required." }
        require(scopes.all { it.isNotBlank() }) { "Token scopes must not be blank." }
    }

    /** Stable non-secret cache key; no token material is included. */
    internal fun cacheKey(): String = buildString {
        appendScopePart(principalKind.name)
        appendScopePart(principalId)
        appendScopePart(resourceId)
        scopes.sorted().forEach { appendScopePart(it) }
    }

    private fun StringBuilder.appendScopePart(value: String)
    {
        append(value.length).append(':').append(value)
    }
}

/** Principal classes used to isolate workload and user token caches. */
enum class PrincipalKind { WORKLOAD, USER }

/** Secret token value whose default representation is safe for logs and diagnostics. */
class AgentCoreSecretToken private constructor(private val rawValue: String)
{
    /** Return the token for an explicit transport boundary. */
    fun value(): String = rawValue

    /** Build the bearer authorization value for an explicit transport boundary. */
    fun authorizationHeader(): String = "Bearer $rawValue"

    /** Return the fixed redacted representation used in diagnostics. */
    fun redacted(): String = REDACTED

    override fun toString(): String = REDACTED

    override fun equals(other: Any?): Boolean = other is AgentCoreSecretToken && rawValue == other.rawValue

    override fun hashCode(): Int = rawValue.hashCode()

    public companion object {
        private const val REDACTED = "[REDACTED]"

        /** Create a secret token after rejecting blank credential material. */
        fun of(value: String): AgentCoreSecretToken
        {
            require(value.isNotBlank()) { "AgentCore token must not be blank." }
            return AgentCoreSecretToken(value)
        }
    }
}

/** Direct data-plane access to the pinned Identity token operations.
 *
 * @param client AgentCore data-plane client.
 */
class AgentCoreIdentityProvider(private val client: BedrockAgentCoreClient)
{
    /** Request a workload access token.
     *
     * @param request Access-token request.
     * @return The service response.
     */
    suspend fun getWorkloadAccessToken(request: GetWorkloadAccessTokenRequest): GetWorkloadAccessTokenResponse =
        client.getWorkloadAccessToken(request)

    /** Request a workload token for a JWT.
     *
     * @param request JWT token request.
     * @return The service response.
     */
    suspend fun getWorkloadAccessTokenForJwt(
        request: GetWorkloadAccessTokenForJwtRequest
    ): GetWorkloadAccessTokenForJwtResponse = client.getWorkloadAccessTokenForJwt(request)

    /** Request a workload token for a user identity.
     *
     * @param request User-identity token request.
     * @return The service response.
     */
    suspend fun getWorkloadAccessTokenForUserId(
        request: GetWorkloadAccessTokenForUserIdRequest
    ): GetWorkloadAccessTokenForUserIdResponse = client.getWorkloadAccessTokenForUserId(request)

    /** Resolve an OAuth resource token.
     *
     * @param request OAuth-token request.
     * @return The service response.
     */
    suspend fun getResourceOauth2Token(request: GetResourceOauth2TokenRequest): GetResourceOauth2TokenResponse =
        client.getResourceOauth2Token(request)

    /** Resolve an API-key resource credential.
     *
     * @param request API-key request.
     * @return The service response.
     */
    suspend fun getResourceApiKey(request: GetResourceApiKeyRequest): GetResourceApiKeyResponse =
        client.getResourceApiKey(request)

    /** Complete an interactive resource-token authorization flow. */
    suspend fun completeResourceTokenAuth(
        request: CompleteResourceTokenAuthRequest
    ): CompleteResourceTokenAuthResponse = client.completeResourceTokenAuth(request)

    /** Explicitly named user-delegated OAuth token helper. */
    suspend fun getOboResourceOauth2Token(
        request: GetResourceOauth2TokenRequest
    ): GetResourceOauth2TokenResponse = getResourceOauth2Token(request)

    /** Explicitly named user-delegated resource-token helper. */
    suspend fun getOboResourceToken(
        request: GetResourceOauth2TokenRequest
    ): GetResourceOauth2TokenResponse = getOboResourceOauth2Token(request)

    /** Resolve an OBO resource token while setting the AWS OBO flow explicitly. */
    suspend fun getOboResourceOauth2Token(
        resourceCredentialProviderName: String,
        workloadIdentityToken: String,
        scopes: List<String> = emptyList(),
        resources: List<String> = emptyList(),
        audiences: List<String> = emptyList()
    ): GetResourceOauth2TokenResponse
    {
        require(resourceCredentialProviderName.isNotBlank()) {
            "A resource credential provider name is required."
        }
        require(workloadIdentityToken.isNotBlank()) { "A workload identity token is required." }
        require(scopes.all { it.isNotBlank() }) { "Resource token scopes must not be blank." }
        require(resources.all { it.isNotBlank() }) { "Resource token resources must not be blank." }
        require(audiences.all { it.isNotBlank() }) { "Resource token audiences must not be blank." }

        return getOboResourceOauth2Token(
            GetResourceOauth2TokenRequest {
                this.resourceCredentialProviderName = resourceCredentialProviderName
                this.workloadIdentityToken = workloadIdentityToken
                this.oauth2Flow = Oauth2FlowType.OnBehalfOfTokenExchange
                this.scopes = scopes
                this.resources = resources
                this.audiences = audiences
            }
        )
    }

    /** Complete an interactive resource-token authorization flow from its two required values. */
    suspend fun completeResourceTokenAuth(
        sessionUri: String,
        userIdentifier: UserIdentifier
    ): CompleteResourceTokenAuthResponse = completeResourceTokenAuth(
        CompleteResourceTokenAuthRequest {
            this.sessionUri = sessionUri
            this.userIdentifier = userIdentifier
        }
    )

    /** Explicitly complete a resource-token flow for a user id. */
    suspend fun completeResourceTokenAuth(
        sessionUri: String,
        userId: String
    ): CompleteResourceTokenAuthResponse = completeResourceTokenAuth(
        sessionUri,
        UserIdentifier.UserId(userId)
    )
}

/** Construct direct Identity access from shared clients. */
fun AgentCoreClients.identityProvider(): AgentCoreIdentityProvider = AgentCoreIdentityProvider(data)

/** Convert a workload token response to a value that is redacted by default. */
fun GetWorkloadAccessTokenResponse.asSecretToken(): AgentCoreSecretToken =
    AgentCoreSecretToken.of(requireNotNull(workloadAccessToken))

/** Convert a JWT workload token response to a value that is redacted by default. */
fun GetWorkloadAccessTokenForJwtResponse.asSecretToken(): AgentCoreSecretToken =
    AgentCoreSecretToken.of(requireNotNull(workloadAccessToken))

/** Convert a user-id workload token response to a value that is redacted by default. */
fun GetWorkloadAccessTokenForUserIdResponse.asSecretToken(): AgentCoreSecretToken =
    AgentCoreSecretToken.of(requireNotNull(workloadAccessToken))

/** Convert an OAuth resource token response when the service returned an access token. */
fun GetResourceOauth2TokenResponse.asSecretTokenOrNull(): AgentCoreSecretToken? =
    accessToken?.let(AgentCoreSecretToken::of)

/** Convert an API-key resource response to a value that is redacted by default. */
fun GetResourceApiKeyResponse.asSecretToken(): AgentCoreSecretToken =
    AgentCoreSecretToken.of(requireNotNull(apiKey))

/** A refreshable, scope-keyed token provider for MCP request headers.
 *
 * @param loader Source for a token for each scope.
 * @param config Token-cache settings.
 * @param now Clock used to evaluate token expiry.
 * @param tokenLifetimeMillis Duration for which a loaded token is cached.
 */
class AgentCoreTokenProvider(
    private val loader: suspend (scopeKey: String) -> String,
    private val config: AgentCoreIdentityConfig = AgentCoreIdentityConfig(),
    private val now: () -> Long = System::currentTimeMillis,
    private val tokenLifetimeMillis: Long = 50_000L
) : McpRemoteAuthProvider
{
    init
    {
        require(tokenLifetimeMillis > 0L) { "Identity token lifetime must be positive." }
    }

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedToken>()

    /** Return a bearer header using a short-lived per-scope cache. */
    override suspend fun headers(): Map<String, String> = headers("default")

    /** Return a bearer header for a resource/session scope. */
    suspend fun headers(scopeKey: String): Map<String, String> = mutex.withLock {
        require(scopeKey.isNotBlank()) { "Identity token scope must not be blank." }
        val current = cache[scopeKey]
        val validUntil = now() + config.expirySkewMillis
        val token = if(current == null || current.expiresAt <= validUntil)
        {
            AgentCoreSecretToken.of(loader(scopeKey)).also { loaded ->
                cache[scopeKey] = CachedToken(loaded, now() + tokenLifetimeMillis)
            }
        }

        else
        {
            current.token
        }

        mapOf("Authorization" to token.authorizationHeader())
    }

    /** Return a bearer header for an explicit workload/user/resource scope. */
    suspend fun headers(scope: AgentCoreTokenScope): Map<String, String> = headers(scope.cacheKey())

    /** Remove a scope from the short-lived cache. */
    suspend fun evict(scopeKey: String): Unit = mutex.withLock { cache.remove(scopeKey) }

    /** Remove an explicit workload/user/resource scope from the short-lived cache. */
    suspend fun evict(scope: AgentCoreTokenScope): Unit = evict(scope.cacheKey())

    private data class CachedToken(val token: AgentCoreSecretToken, val expiresAt: Long)
}

/** Typed control-plane access to Identity resource administration. */
class AgentCoreIdentityAdmin(private val client: BedrockAgentCoreControlClient)
{
    /** Create a workload identity.
     *
     * @param request Identity creation request.
     * @return The service response.
     */
    suspend fun createWorkloadIdentity(request: CreateWorkloadIdentityRequest): CreateWorkloadIdentityResponse =
        client.createWorkloadIdentity(request)

    /** Get a workload identity.
     *
     * @param request Identity lookup request.
     * @return The service response.
     */
    suspend fun getWorkloadIdentity(request: GetWorkloadIdentityRequest): GetWorkloadIdentityResponse =
        client.getWorkloadIdentity(request)

    /** List workload identities. */
    suspend fun listWorkloadIdentities(request: ListWorkloadIdentitiesRequest): ListWorkloadIdentitiesResponse =
        client.listWorkloadIdentities(request)

    /** Update a workload identity. */
    suspend fun updateWorkloadIdentity(
        request: UpdateWorkloadIdentityRequest
    ): UpdateWorkloadIdentityResponse = client.updateWorkloadIdentity(request)

    /** Delete a workload identity.
     *
     * @param request Identity deletion request.
     * @return The service response.
     */
    suspend fun deleteWorkloadIdentity(request: DeleteWorkloadIdentityRequest): DeleteWorkloadIdentityResponse =
        client.deleteWorkloadIdentity(request)

    /** Create an OAuth credential provider.
     *
     * @param request OAuth provider creation request.
     * @return The service response.
     */
    suspend fun createOauth2CredentialProvider(
        request: CreateOauth2CredentialProviderRequest
    ): CreateOauth2CredentialProviderResponse = client.createOauth2CredentialProvider(request)

    /** Get an OAuth2 credential provider. */
    suspend fun getOauth2CredentialProvider(
        request: GetOauth2CredentialProviderRequest
    ): GetOauth2CredentialProviderResponse = client.getOauth2CredentialProvider(request)

    /** List OAuth2 credential providers. */
    suspend fun listOauth2CredentialProviders(
        request: ListOauth2CredentialProvidersRequest
    ): ListOauth2CredentialProvidersResponse = client.listOauth2CredentialProviders(request)

    /** Update an OAuth2 credential provider. */
    suspend fun updateOauth2CredentialProvider(
        request: UpdateOauth2CredentialProviderRequest
    ): UpdateOauth2CredentialProviderResponse = client.updateOauth2CredentialProvider(request)

    /** Delete an OAuth2 credential provider. */
    suspend fun deleteOauth2CredentialProvider(
        request: DeleteOauth2CredentialProviderRequest
    ): DeleteOauth2CredentialProviderResponse = client.deleteOauth2CredentialProvider(request)

    /** Source-compatible spelling alias for OAuth2 provider deletion. */
    suspend fun deleteOAuth2CredentialProvider(
        request: DeleteOauth2CredentialProviderRequest
    ): DeleteOauth2CredentialProviderResponse = deleteOauth2CredentialProvider(request)

    /** Source-compatible spelling alias for OAuth2 provider creation. */
    suspend fun createOAuth2CredentialProvider(
        request: CreateOauth2CredentialProviderRequest
    ): CreateOauth2CredentialProviderResponse = createOauth2CredentialProvider(request)

    /** Source-compatible spelling alias for OAuth2 provider lookup. */
    suspend fun getOAuth2CredentialProvider(
        request: GetOauth2CredentialProviderRequest
    ): GetOauth2CredentialProviderResponse = getOauth2CredentialProvider(request)

    /** Source-compatible spelling alias for OAuth2 provider listing. */
    suspend fun listOAuth2CredentialProviders(
        request: ListOauth2CredentialProvidersRequest
    ): ListOauth2CredentialProvidersResponse = listOauth2CredentialProviders(request)

    /** Source-compatible spelling alias for OAuth2 provider updates. */
    suspend fun updateOAuth2CredentialProvider(
        request: UpdateOauth2CredentialProviderRequest
    ): UpdateOauth2CredentialProviderResponse = updateOauth2CredentialProvider(request)

    /** Create an API-key credential provider.
     *
     * @param request API-key provider creation request.
     * @return The service response.
     */
    suspend fun createApiKeyCredentialProvider(
        request: CreateApiKeyCredentialProviderRequest
    ): CreateApiKeyCredentialProviderResponse = client.createApiKeyCredentialProvider(request)

    /** Get an API-key credential provider. */
    suspend fun getApiKeyCredentialProvider(
        request: GetApiKeyCredentialProviderRequest
    ): GetApiKeyCredentialProviderResponse = client.getApiKeyCredentialProvider(request)

    /** List API-key credential providers. */
    suspend fun listApiKeyCredentialProviders(
        request: ListApiKeyCredentialProvidersRequest
    ): ListApiKeyCredentialProvidersResponse = client.listApiKeyCredentialProviders(request)

    /** Update an API-key credential provider. */
    suspend fun updateApiKeyCredentialProvider(
        request: UpdateApiKeyCredentialProviderRequest
    ): UpdateApiKeyCredentialProviderResponse = client.updateApiKeyCredentialProvider(request)

    /** Delete an API-key credential provider. */
    suspend fun deleteApiKeyCredentialProvider(
        request: DeleteApiKeyCredentialProviderRequest
    ): DeleteApiKeyCredentialProviderResponse = client.deleteApiKeyCredentialProvider(request)

    /** Get the token vault configuration. */
    suspend fun getTokenVault(request: GetTokenVaultRequest): GetTokenVaultResponse = client.getTokenVault(request)

    /** Set the token-vault customer managed KMS key. */
    suspend fun setTokenVaultCmk(request: SetTokenVaultCmkRequest): SetTokenVaultCmkResponse =
        client.setTokenVaultCmk(request)

    /** Read a resource policy. */
    suspend fun getResourcePolicy(request: GetResourcePolicyRequest): GetResourcePolicyResponse =
        client.getResourcePolicy(request)

    /** Create or replace a resource policy. */
    suspend fun putResourcePolicy(request: PutResourcePolicyRequest): PutResourcePolicyResponse =
        client.putResourcePolicy(request)

    /** Delete a resource policy. */
    suspend fun deleteResourcePolicy(request: DeleteResourcePolicyRequest): DeleteResourcePolicyResponse =
        client.deleteResourcePolicy(request)

    /** Create a consent portal. */
    suspend fun createConsentPortal(request: CreateConsentPortalRequest): CreateConsentPortalResponse =
        client.createConsentPortal(request)

    /** Get a consent portal. */
    suspend fun getConsentPortal(request: GetConsentPortalRequest): GetConsentPortalResponse =
        client.getConsentPortal(request)

    /** List consent portals. */
    suspend fun listConsentPortals(request: ListConsentPortalsRequest): ListConsentPortalsResponse =
        client.listConsentPortals(request)

    /** Update a consent portal. */
    suspend fun updateConsentPortal(request: UpdateConsentPortalRequest): UpdateConsentPortalResponse =
        client.updateConsentPortal(request)

    /** Delete a consent portal. */
    suspend fun deleteConsentPortal(request: DeleteConsentPortalRequest): DeleteConsentPortalResponse =
        client.deleteConsentPortal(request)

    /** Wait for a consent portal to become active using bounded cancellation-aware polling. */
    suspend fun awaitConsentPortalActive(
        request: GetConsentPortalRequest,
        timeoutMillis: Long = 30_000L,
        initialDelayMillis: Long = 100L,
        maxDelayMillis: Long = 2_000L
    ): GetConsentPortalResponse = AgentCoreIdentityPoller.awaitConsentPortalActive(
        this,
        request,
        timeoutMillis,
        initialDelayMillis,
        maxDelayMillis
    )

    /** Execute a caller-selected control-plane operation without changing its typed result or error. */
    suspend fun <T> execute(block: suspend BedrockAgentCoreControlClient.() -> T): T = client.block()
}

/** Construct Identity administration from shared clients. */
fun AgentCoreClients.identityAdmin(): AgentCoreIdentityAdmin = AgentCoreIdentityAdmin(control)
