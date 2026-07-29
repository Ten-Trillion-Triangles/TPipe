package genericOpenAIPipe.env

/**
 * Central environment configuration for Amazon Bedrock Mantle credentials.
 *
 * Mirrors the shape of [GenericOpenAIEnv]: every getter follows the same
 * precedence chain — programmatic setter first, then `-D` system property,
 * then env var. Falls through to the standard AWS credential environment
 * variables as a final default, so callers that already set `AWS_ACCESS_KEY_ID`
 * for the AWS SDK do not have to mirror them under the Mantle-specific names.
 *
 * The accessor functions are intentionally zero-arg and side-effect-free so
 * that [SigV4Signer] can call them at request time without holding references
 * to live state.
 *
 * Precedence for the access key id (top wins):
 *   1. Programmatic setter (test-only)
 *   2. System property `tpipe.bedrockMantle.accessKeyId`
 *   3. Env var `BEDROCK_MANTLE_ACCESS_KEY_ID`
 *   4. Standard AWS env var `AWS_ACCESS_KEY_ID`
 *
 * The same chain applies symmetrically to the secret key, session token, and
 * region. The region defaults to `us-east-1` when nothing is set.
 */
object BedrockMantleEnv
{
    private const val DEFAULT_REGION: String = "us-east-1"

    private const val ACCESS_KEY_ID_SYSTEM_PROPERTY: String = "tpipe.bedrockMantle.accessKeyId"
    private const val SECRET_ACCESS_KEY_SYSTEM_PROPERTY: String = "tpipe.bedrockMantle.secretAccessKey"
    private const val SESSION_TOKEN_SYSTEM_PROPERTY: String = "tpipe.bedrockMantle.sessionToken"
    private const val REGION_SYSTEM_PROPERTY: String = "tpipe.bedrockMantle.region"

    private const val ACCESS_KEY_ID_ENV_VAR: String = "BEDROCK_MANTLE_ACCESS_KEY_ID"
    private const val SECRET_ACCESS_KEY_ENV_VAR: String = "BEDROCK_MANTLE_SECRET_ACCESS_KEY"
    private const val SESSION_TOKEN_ENV_VAR: String = "BEDROCK_MANTLE_SESSION_TOKEN"
    private const val REGION_ENV_VAR: String = "BEDROCK_MANTLE_REGION"

    private const val AWS_ACCESS_KEY_ID_ENV_VAR: String = "AWS_ACCESS_KEY_ID"
    private const val AWS_SECRET_ACCESS_KEY_ENV_VAR: String = "AWS_SECRET_ACCESS_KEY"
    private const val AWS_SESSION_TOKEN_ENV_VAR: String = "AWS_SESSION_TOKEN"
    private const val AWS_REGION_ENV_VAR: String = "AWS_REGION"

    private var accessKeyIdOverride: String = ""
    private var secretAccessKeyOverride: String = ""
    private var sessionTokenOverride: String = ""
    private var regionOverride: String = ""

    /**
     * Programmatic override for the AWS access key id. Intended for tests and
     * explicit credential injection. Cleared via [clearAccessKeyId].
     */
    fun setAccessKeyId(value: String) { accessKeyIdOverride = value }
    fun clearAccessKeyId() { accessKeyIdOverride = "" }

    /**
     * Programmatic override for the AWS secret access key. Intended for tests
     * and explicit credential injection. Cleared via [clearSecretAccessKey].
     */
    fun setSecretAccessKey(value: String) { secretAccessKeyOverride = value }
    fun clearSecretAccessKey() { secretAccessKeyOverride = "" }

    /**
     * Programmatic override for the AWS session token. Pass `null` to clear.
     * Intended for tests and explicit credential injection.
     */
    fun setSessionToken(value: String?) {
        sessionTokenOverride = value ?: ""
    }
    fun clearSessionToken() { sessionTokenOverride = "" }

    /**
     * Programmatic override for the AWS region. Intended for tests and
     * explicit configuration. Cleared via [clearRegion].
     */
    fun setRegion(value: String) { regionOverride = value }
    fun clearRegion() { regionOverride = "" }

    /**
     * Resolve the AWS access key id, walking the precedence chain.
     *
     * @return The non-blank access key id, or an empty string when nothing is
     *         configured.
     */
    fun resolveAccessKeyId(): String =
        accessKeyIdOverride.ifBlank {
            System.getProperty(ACCESS_KEY_ID_SYSTEM_PROPERTY)?.takeIf { it.isNotBlank() }
                ?: System.getenv(ACCESS_KEY_ID_ENV_VAR)?.takeIf { it.isNotBlank() }
                ?: System.getenv(AWS_ACCESS_KEY_ID_ENV_VAR)?.takeIf { it.isNotBlank() }
                ?: ""
        }

    /**
     * Resolve the AWS secret access key, walking the precedence chain.
     *
     * @return The non-blank secret key, or an empty string when nothing is
     *         configured.
     */
    fun resolveSecretAccessKey(): String =
        secretAccessKeyOverride.ifBlank {
            System.getProperty(SECRET_ACCESS_KEY_SYSTEM_PROPERTY)?.takeIf { it.isNotBlank() }
                ?: System.getenv(SECRET_ACCESS_KEY_ENV_VAR)?.takeIf { it.isNotBlank() }
                ?: System.getenv(AWS_SECRET_ACCESS_KEY_ENV_VAR)?.takeIf { it.isNotBlank() }
                ?: ""
        }

    /**
     * Resolve the AWS session token, walking the precedence chain.
     *
     * @return The session token or `null` when no temporary credentials are
     *         configured.
     */
    fun resolveSessionToken(): String? =
        sessionTokenOverride.takeIf { it.isNotBlank() }
            ?: System.getProperty(SESSION_TOKEN_SYSTEM_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: System.getenv(SESSION_TOKEN_ENV_VAR)?.takeIf { it.isNotBlank() }
            ?: System.getenv(AWS_SESSION_TOKEN_ENV_VAR)?.takeIf { it.isNotBlank() }

    /**
     * Resolve the AWS region for the Mantle endpoint.
     *
     * Defaults to `us-east-1` when nothing is configured, matching AWS
     * conventions for the Bedrock service.
     *
     * @return The non-blank region code, never empty.
     */
    fun resolveRegion(): String =
        regionOverride.ifBlank {
            System.getProperty(REGION_SYSTEM_PROPERTY)?.takeIf { it.isNotBlank() }
                ?: System.getenv(REGION_ENV_VAR)?.takeIf { it.isNotBlank() }
                ?: System.getenv(AWS_REGION_ENV_VAR)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_REGION
        }

    /**
     * Whether both access key id and secret access key are configured (either
     * programmatically, via system property, or via env var).
     */
    fun hasCredentials(): Boolean =
        resolveAccessKeyId().isNotBlank() && resolveSecretAccessKey().isNotBlank()
}