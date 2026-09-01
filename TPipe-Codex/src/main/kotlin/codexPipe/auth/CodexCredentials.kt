package codexPipe.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Safe account metadata that may be presented to a model picker or login UI. */
data class CodexAccountInfo(
    val accountId: String?,
    val chatgptUserId: String? = null,
    val planType: String? = null,
    val isFedramp: Boolean = false,
    val workspaceId: String? = null,
)
{
    override fun toString(): String = "CodexAccountInfo(accountIdPresent=${!accountId.isNullOrBlank()}, " +
        "planType=${planType ?: "unknown"}, isFedramp=$isFedramp)"
}

/**
 * Secret-bearing OAuth state persisted by TPipe's private credential store.
 *
 * Token values are intentionally excluded from [toString], traces, and normal
 * configuration objects.
 */
@Serializable
data class CodexOAuthCredentials(
    @SerialName("id_token")
    val idToken: String,
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("account_id")
    val accountId: String? = null,
    @SerialName("workspace_id")
    val workspaceId: String? = null,
    @SerialName("chatgpt_user_id")
    val chatgptUserId: String? = null,
    @SerialName("plan_type")
    val planType: String? = null,
    @SerialName("is_fedramp")
    val isFedramp: Boolean = false,
    @SerialName("last_refresh")
    val lastRefresh: Long? = null,
)
{
    init
    {
        require(idToken.isNotBlank()) { "Codex id token cannot be blank" }
        require(accessToken.isNotBlank()) { "Codex access token cannot be blank" }
        require(refreshToken.isNotBlank()) { "Codex refresh token cannot be blank" }
    }

    /** Returns non-secret metadata derived from this credential state. */
    fun accountInfo(): CodexAccountInfo = CodexAccountInfo(
        accountId = accountId,
        chatgptUserId = chatgptUserId,
        planType = planType,
        isFedramp = isFedramp,
        workspaceId = workspaceId,
    )

    override fun toString(): String = "CodexOAuthCredentials(tokens=redacted, " +
        "accountIdPresent=${!accountId.isNullOrBlank()}, planType=${planType ?: "unknown"}, " +
        "isFedramp=$isFedramp, lastRefresh=$lastRefresh)"

    companion object
    {
        /**
         * Builds credentials while extracting optional metadata from the JWTs.
         * Token parsing is best-effort; server-side authentication remains authoritative.
         */
        fun fromTokens(
            idToken: String,
            accessToken: String,
            refreshToken: String,
            explicitAccountId: String? = null,
            explicitWorkspaceId: String? = null,
            lastRefresh: Long? = null,
        ): CodexOAuthCredentials
        {
            val idClaims = CodexJwtClaims.parse(idToken)
            val accessClaims = CodexJwtClaims.parse(accessToken)
            return CodexOAuthCredentials(
                idToken = idToken,
                accessToken = accessToken,
                refreshToken = refreshToken,
                accountId = explicitAccountId?.takeIf { it.isNotBlank() }
                    ?: idClaims?.chatgptAccountId
                    ?: accessClaims?.chatgptAccountId,
                workspaceId = explicitWorkspaceId?.takeIf { it.isNotBlank() }
                    ?: idClaims?.workspaceId
                    ?: accessClaims?.workspaceId,
                chatgptUserId = idClaims?.chatgptUserId
                    ?: idClaims?.userId
                    ?: accessClaims?.chatgptUserId
                    ?: accessClaims?.userId,
                planType = idClaims?.chatgptPlanType ?: accessClaims?.chatgptPlanType,
                isFedramp = idClaims?.isFedramp == true || accessClaims?.isFedramp == true,
                lastRefresh = lastRefresh,
            )
        }
    }
}
