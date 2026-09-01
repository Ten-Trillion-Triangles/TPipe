package codexPipe.auth

import codexPipe.CodexConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/** Authentication failure that requires a new ChatGPT login. */
class CodexAuthenticationException(message: String) : IOException(message)

/**
 * Owns the active TPipe Codex credential profile and its refresh lifecycle.
 *
 * One manager should be shared by the pipes in a single Manifold or PumpStation.
 * The mutex prevents those pipes from consuming a rotating refresh token twice;
 * every refresh also reloads the file store so separate manager instances can
 * observe an already-persisted rotation.
 */
class CodexAuthManager(
    private val credentialStore: CodexCredentialStore = FileCodexCredentialStore(),
    private val httpClient: HttpClient = HttpClient(CIO),
    private val issuer: String = CodexConstants.AUTH_BASE_URL,
    private val clientId: String = CodexConstants.clientId(),
    private val importCodexCliCredentialsIfMissing: Boolean = true,
    private val cliAuthFile: java.nio.file.Path = CodexPaths.codexCliAuthFile(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val refreshFallbackIntervalMillis: Long = CodexConstants.FALLBACK_REFRESH_INTERVAL_MILLIS,
    private val accessTokenRefreshLeewayMillis: Long = CodexConstants.ACCESS_TOKEN_REFRESH_LEEWAY_MILLIS,
)
{
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private var credentials: CodexOAuthCredentials? = null

    private val deviceAuthClient: CodexDeviceAuthClient by lazy {
        CodexDeviceAuthClient(
            httpClient = httpClient,
            issuer = issuer,
            clientId = clientId,
            credentialStore = credentialStore,
            nowMillis = nowMillis,
        )
    }

    /** Returns the current account metadata, or null when no login exists. */
    suspend fun currentAccount(): CodexAccountInfo? = refreshMutex.withLock {
        loadOrImportLocked()?.accountInfo()
    }

    /** Returns current bearer/account headers after proactive refresh. */
    suspend fun authorizationHeaders(): Map<String, String>
    {
        refreshIfNeeded(force = false)
        return refreshMutex.withLock {
            val active = loadOrImportLocked()
                ?: throw CodexAuthenticationException("Codex OAuth credentials are required; complete device login first")
            buildMap {
                put("Authorization", "Bearer ${active.accessToken}")
                active.accountId?.takeIf { it.isNotBlank() }?.let {
                    put("ChatGPT-Account-ID", it)
                }
                if(active.isFedramp) put("X-OpenAI-Fedramp", "true")
            }
        }
    }

    /** Starts device login and returns the displayable verification details. */
    suspend fun requestDeviceCode(): CodexDeviceCode = deviceAuthClient.requestDeviceCode()

    /** Completes device login and returns the persisted account metadata. */
    suspend fun completeDeviceLogin(deviceCode: CodexDeviceCode): CodexAccountInfo
    {
        val info = deviceAuthClient.completeDeviceLogin(deviceCode)
        refreshMutex.withLock {
            credentials = credentialStore.load()
        }
        return info
    }

    /**
     * Refreshes when required, or unconditionally when [force] is true.
     *
     * @return true when another request may use the resulting credentials.
     */
    suspend fun refreshIfNeeded(force: Boolean = false): Boolean = refreshIfNeeded(force, null)

    private suspend fun refreshIfNeeded(
        force: Boolean,
        observedAuthorizationHeader: String?,
    ): Boolean = refreshMutex.withLock {
        val previous = credentials
        val stored = readOrImportLocked()
            ?: throw CodexAuthenticationException("Codex OAuth credentials are required; complete device login first")

        val diskReplacedInMemoryState = previous != null && credentialsDiffer(previous, stored)
        if(diskReplacedInMemoryState)
        {
            credentials = stored
            if(force) return@withLock true
        }
        if(force && observedAuthorizationHeader != null &&
            observedAuthorizationHeader != "Bearer ${stored.accessToken}")
        {
            credentials = stored
            return@withLock true
        }
        credentials = stored

        if(!force && !needsRefresh(stored, nowMillis())) return@withLock false

        val refreshed = requestRefresh(stored.refreshToken)
        val updated = CodexOAuthCredentials.fromTokens(
            idToken = refreshed.idToken ?: stored.idToken,
            accessToken = refreshed.accessToken ?: stored.accessToken,
            refreshToken = refreshed.refreshToken ?: stored.refreshToken,
            explicitAccountId = stored.accountId,
            explicitWorkspaceId = stored.workspaceId,
            lastRefresh = nowMillis(),
        )
        credentialStore.save(updated)
        credentials = updated
        true
    }

    /**
     * Handles one unauthorized inference response without issuing the retry.
     * GenericOpenAI owns the one-retry bound.
     */
    suspend fun recoverUnauthorized(observedAuthorizationHeader: String? = null): Boolean
    {
        return try
        {
            refreshIfNeeded(force = true, observedAuthorizationHeader = observedAuthorizationHeader)
            true
        }
        catch(e: CancellationException)
        {
            throw e
        }
        catch(_: CodexAuthenticationException)
        {
            false
        }
        catch(_: IOException)
        {
            false
        }
    }

    /** Deletes the TPipe-owned credential profile. */
    suspend fun logout(): Boolean = refreshMutex.withLock {
        credentials = null
        credentialStore.delete()
    }

    /** Exposes the configured TPipe store for diagnostics and test setup only. */
    fun credentialStore(): CodexCredentialStore = credentialStore

    private fun loadOrImportLocked(): CodexOAuthCredentials?
    {
        val stored = readOrImportLocked()
        credentials = stored
        return stored
    }

    /** Reads the store without changing the in-memory snapshot used for rotation detection. */
    private fun readOrImportLocked(): CodexOAuthCredentials?
    {
        val stored = credentialStore.load()
        if(stored != null)
        {
            return stored
        }
        if(importCodexCliCredentialsIfMissing)
        {
            val imported = CodexCliCredentialImporter(credentialStore, cliAuthFile).importIfMissing()
            if(imported != null)
            {
                return imported
            }
        }
        return null
    }

    private fun needsRefresh(credentials: CodexOAuthCredentials, now: Long): Boolean
    {
        val expiration = CodexJwtClaims.parse(credentials.accessToken)?.expirationEpochSeconds
        if(expiration != null)
        {
            return expiration * 1_000L - now <= accessTokenRefreshLeewayMillis
        }
        val lastRefresh = credentials.lastRefresh ?: return true
        return now - lastRefresh >= refreshFallbackIntervalMillis
    }

    private suspend fun requestRefresh(refreshToken: String): RefreshTokenResult
    {
        val payload = buildJsonObject {
            put("client_id", JsonPrimitive(clientId))
            put("grant_type", JsonPrimitive("refresh_token"))
            put("refresh_token", JsonPrimitive(refreshToken))
        }
        val response = httpClient.post("${issuer.trimEnd('/')}${CodexConstants.OAUTH_TOKEN_PATH}")
        {
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val body = response.bodyAsText()
        if(response.status.value !in 200..299)
        {
            if(response.status.value == 400 || response.status.value == 401 ||
                body.contains("invalid_grant", ignoreCase = true))
            {
                credentials = null
                credentialStore.delete()
                throw CodexAuthenticationException("Codex OAuth refresh is no longer valid; complete device login again")
            }
            throw IOException("Codex OAuth refresh failed with HTTP status ${response.status.value}")
        }

        val objectValue = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrElse {
            throw IOException("Codex OAuth refresh returned an invalid response")
        }
        return RefreshTokenResult(
            idToken = objectValue.string("id_token"),
            accessToken = objectValue.string("access_token"),
            refreshToken = objectValue.string("refresh_token"),
        ).also {
            if(it.accessToken.isNullOrBlank())
            {
                throw IOException("Codex OAuth refresh returned no access token")
            }
        }
    }

    private fun credentialsDiffer(
        first: CodexOAuthCredentials,
        second: CodexOAuthCredentials,
    ): Boolean = first.accessToken != second.accessToken || first.refreshToken != second.refreshToken

    private data class RefreshTokenResult(
        val idToken: String?,
        val accessToken: String?,
        val refreshToken: String?,
    )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object
    {
        /** Creates a manager backed by the default TPipe-owned credential file. */
        fun default(
            importCodexCliCredentialsIfMissing: Boolean = true,
        ): CodexAuthManager = CodexAuthManager(
            importCodexCliCredentialsIfMissing = importCodexCliCredentialsIfMissing,
        )
    }
}
