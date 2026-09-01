package codexPipe.auth

import codexPipe.CodexConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.Closeable

/** Public device-code details that an embedding application can display. */
data class CodexDeviceCode(
    val verificationUrl: String,
    val userCode: String,
    internal val deviceAuthId: String,
    internal val intervalSeconds: Long,
)

/** Token set returned by the OAuth authorization-code exchange. */
internal data class CodexAuthorizationGrant(
    val authorizationCode: String,
    val codeChallenge: String,
    val codeVerifier: String,
)

/** Token set returned by the OAuth authorization-code exchange. */
internal data class CodexTokenSet(
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
)

/**
 * Ktor client for the current ChatGPT device-code flow.
 *
 * No terminal UI is performed here: callers receive the verification URL and
 * user code and decide how to present them.
 */
class CodexDeviceAuthClient(
    private val httpClient: HttpClient = HttpClient(CIO),
    private val issuer: String = CodexConstants.AUTH_BASE_URL,
    private val clientId: String = CodexConstants.clientId(),
    private val credentialStore: CodexCredentialStore? = null,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val maxWaitMillis: Long = CodexConstants.DEVICE_LOGIN_TIMEOUT_MILLIS,
) : Closeable
{
    private val json = Json { ignoreUnknownKeys = true }

    /** Requests a displayable device verification URL and one-time user code. */
    suspend fun requestDeviceCode(): CodexDeviceCode
    {
        val response = httpClient.post(url(CodexConstants.DEVICE_USER_CODE_PATH))
        {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("client_id" to clientId)))
        }
        val body = response.bodyAsText()
        requireSuccess(response.status, "device code request")
        val obj = parseObject(body, "device code response")
        val deviceAuthId = obj.string("device_auth_id")
            ?: throw IllegalStateException("Codex device code response omitted its session id")
        val userCode = obj.string("user_code") ?: obj.string("usercode")
            ?: throw IllegalStateException("Codex device code response omitted its user code")
        val interval = obj.long("interval") ?: 5L
        return CodexDeviceCode(
            verificationUrl = "${issuer.trimEnd('/')}/codex/device",
            userCode = userCode,
            deviceAuthId = deviceAuthId,
            intervalSeconds = interval,
        )
    }

    /**
     * Polls until the user completes authentication, exchanges the returned
     * authorization code, and persists credentials only after that exchange.
     */
    suspend fun completeDeviceLogin(deviceCode: CodexDeviceCode): CodexAccountInfo
    {
        val grant = pollForToken(deviceCode)
        val credentials = exchangeAuthorizationCode(grant)
        val stored = CodexOAuthCredentials.fromTokens(
            idToken = credentials.idToken,
            accessToken = credentials.accessToken,
            refreshToken = credentials.refreshToken,
            lastRefresh = nowMillis(),
        )
        credentialStore?.save(stored)
        return stored.accountInfo()
    }

    private suspend fun pollForToken(deviceCode: CodexDeviceCode): CodexAuthorizationGrant
    {
        val startedAt = nowMillis()
        val deadline = startedAt + maxWaitMillis
        while(true)
        {
            val response = httpClient.post(url(CodexConstants.DEVICE_TOKEN_PATH))
            {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(mapOf(
                    "device_auth_id" to deviceCode.deviceAuthId,
                    "user_code" to deviceCode.userCode,
                )))
            }
            val body = response.bodyAsText()
            if(response.status.value in 200..299)
            {
                val obj = parseObject(body, "device token response")
                return CodexAuthorizationGrant(
                    authorizationCode = obj.requiredString("authorization_code"),
                    codeChallenge = obj.requiredString("code_challenge"),
                    codeVerifier = obj.requiredString("code_verifier"),
                )
            }

            if(response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.NotFound)
            {
                val remaining = deadline - nowMillis()
                if(remaining <= 0L)
                {
                    throw IllegalStateException("Codex device authentication timed out after 15 minutes")
                }
                delayMillis(minOf(deviceCode.intervalSeconds.coerceAtLeast(1L) * 1_000L, remaining))
                continue
            }
            throw IllegalStateException("Codex device authentication failed with HTTP status ${response.status.value}")
        }
    }

    private suspend fun exchangeAuthorizationCode(grant: CodexAuthorizationGrant): CodexTokenSet
    {
        val redirectUri = "${issuer.trimEnd('/')}/deviceauth/callback"
        val form = listOf(
            "grant_type" to "authorization_code",
            "code" to grant.authorizationCode,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to grant.codeVerifier,
        ).joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val response = httpClient.post(url(CodexConstants.OAUTH_TOKEN_PATH))
        {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(form)
        }
        val body = response.bodyAsText()
        requireSuccess(response.status, "authorization-code exchange")
        val obj = parseObject(body, "authorization-code response")
        return CodexTokenSet(
            idToken = obj.requiredString("id_token"),
            accessToken = obj.requiredString("access_token"),
            refreshToken = obj.requiredString("refresh_token"),
        )
    }

    private fun url(path: String): String = issuer.trimEnd('/') + path

    private fun parseObject(body: String, label: String): JsonObject =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw IllegalStateException("Invalid Codex $label")
        }

    private fun requireSuccess(status: HttpStatusCode, operation: String)
    {
        if(status.value !in 200..299)
        {
            throw IllegalStateException("Codex $operation failed with HTTP status ${status.value}")
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.requiredString(key: String): String =
        string(key) ?: throw IllegalStateException("Codex response omitted required field")

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull ?: string(key)?.toLongOrNull()

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)

    override fun close()
    {
        httpClient.close()
    }
}
