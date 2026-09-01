package codexPipe

/**
 * Compatibility constants for the current ChatGPT/Codex transport contract.
 *
 * These values are intentionally isolated so an upstream backend change has a
 * single maintenance point. The backend is experimental and version-sensitive.
 */
object CodexConstants
{
    /** ChatGPT authentication issuer. */
    const val AUTH_BASE_URL: String = "https://auth.openai.com"

    /** ChatGPT subscription-backed Responses API base URL. */
    const val CODEX_BASE_URL: String = "https://chatgpt.com/backend-api/codex"

    /** Current first-party OAuth client identifier used by device and refresh flows. */
    const val DEFAULT_CLIENT_ID: String = "app_EMoamEEZ73f0CkXaXp7hrann"

    /** Environment override for the upstream compatibility client identifier. */
    const val CLIENT_ID_ENV: String = "TPIPE_CODEX_CLIENT_ID"

    /** Environment override for the TPipe-owned credential file. */
    const val AUTH_FILE_ENV: String = "TPIPE_CODEX_AUTH_FILE"

    /** Device-code user-code endpoint. */
    const val DEVICE_USER_CODE_PATH: String = "/api/accounts/deviceauth/usercode"

    /** Device-code polling endpoint. */
    const val DEVICE_TOKEN_PATH: String = "/api/accounts/deviceauth/token"

    /** OAuth authorization-code and refresh endpoint. */
    const val OAUTH_TOKEN_PATH: String = "/oauth/token"

    /** Model catalog endpoint. */
    const val MODELS_PATH: String = "/models"

    /** Upstream device authorization maximum. */
    const val DEVICE_LOGIN_TIMEOUT_MILLIS: Long = 15 * 60 * 1_000L

    /** Proactive access-token refresh leeway. */
    const val ACCESS_TOKEN_REFRESH_LEEWAY_MILLIS: Long = 5 * 60 * 1_000L

    /** Fallback refresh interval when access-token expiry cannot be decoded. */
    const val FALLBACK_REFRESH_INTERVAL_MILLIS: Long = 8 * 24 * 60 * 60 * 1_000L

    /** Client version query value used for model discovery. */
    const val CLIENT_VERSION: String = "tpipe-1.0.0"

    /** Returns the configured client ID without logging or retaining secrets. */
    fun clientId(): String = System.getenv(CLIENT_ID_ENV)
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_CLIENT_ID
}
