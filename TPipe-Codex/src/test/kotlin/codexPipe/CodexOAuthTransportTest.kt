package codexPipe

import codexPipe.auth.CodexAccountInfo
import codexPipe.auth.CodexAuthManager
import codexPipe.auth.CodexAuthenticationException
import codexPipe.auth.CodexCliCredentialImporter
import codexPipe.auth.CodexCredentialStore
import codexPipe.auth.CodexDeviceAuthClient
import codexPipe.auth.CodexOAuthCredentials
import codexPipe.auth.FileCodexCredentialStore
import codexPipe.model.CodexModelCatalogClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Focused fake-driven tests for Codex OAuth storage, lifecycle, and discovery. */
class CodexOAuthTransportTest
{
    @Test
    fun jwtMetadataIsBestEffortAndNeverAppearsInCredentialToString()
    {
        val token = jwt("""
            {
              "email":"user@example.com",
              "exp":4000,
              "https://api.openai.com/auth.chatgpt_plan_type":"pro",
              "https://api.openai.com/auth.chatgpt_user_id":"user-1",
              "https://api.openai.com/auth.chatgpt_account_id":"acct-1",
              "https://api.openai.com/auth.chatgpt_account_is_fedramp":true
            }
        """)
        val credentials = CodexOAuthCredentials.fromTokens(token, token, "refresh-secret")

        assertEquals("acct-1", credentials.accountId)
        assertEquals("user-1", credentials.chatgptUserId)
        assertEquals("pro", credentials.planType)
        assertTrue(credentials.isFedramp)
        assertFalse(credentials.toString().contains("refresh-secret"))
        assertNull(codexPipe.auth.CodexJwtClaims.parse("not-a-jwt"))
    }

    @Test
    fun fileStoreCreatesPrivateAtomicCredentialFile()
    {
        val directory = Files.createTempDirectory("tpipe-codex-store")
        val path = directory.resolve("nested/auth.json")
        val store = FileCodexCredentialStore(path)
        val credentials = CodexOAuthCredentials("id", "access", "refresh", accountId = "acct")

        store.save(credentials)

        assertEquals(credentials, store.load())
        assertTrue(Files.isDirectory(path.parent))
        assertEquals(2, Files.getPosixFilePermissions(path).size)
        assertTrue(Files.getPosixFilePermissions(path).all { it.name.startsWith("OWNER_") })
        assertTrue(store.delete())
        assertNull(store.load())
    }

    @Test
    fun cliImporterIsOneWayAndDoesNotOverwriteExistingTpipeProfile()
    {
        val directory = Files.createTempDirectory("tpipe-codex-import")
        val source = directory.resolve(".codex/auth.json")
        Files.createDirectories(source.parent)
        val sourceContents = """
            {
              "last_refresh":"2026-01-01T00:00:00Z",
              "tokens": {
                "id_token":"id-from-cli",
                "access_token":"access-from-cli",
                "refresh_token":"refresh-from-cli",
                "account_id":"acct-cli"
              }
            }
        """.trimIndent()
        Files.writeString(source, sourceContents)
        val store = MemoryStore()
        val imported = CodexCliCredentialImporter(store, source).importIfMissing()

        assertNotNull(imported)
        assertEquals("acct-cli", imported!!.accountId)
        assertEquals("access-from-cli", store.load()!!.accessToken)
        assertNull(CodexCliCredentialImporter(store, source).importIfMissing())
        assertEquals(sourceContents, Files.readString(source))
    }

    @Test
    fun cliImporterRejectsNonChatgptAuthModesEvenWhenTokensArePresent()
    {
        val directory = Files.createTempDirectory("tpipe-codex-auth-mode")
        val source = directory.resolve("auth.json")
        Files.writeString(source, """
            {"auth_mode":"api_key","tokens":{"id_token":"id","access_token":"access","refresh_token":"refresh"}}
        """.trimIndent())

        assertNull(CodexCliCredentialImporter(MemoryStore(), source).importIfMissing())
    }

    @Test
    fun deviceLoginPollsPending403ThenExchangesAndPersistsOnlyAfterExchange() = runBlocking<Unit>
    {
        val pollCalls = AtomicInteger(0)
        val engine = MockEngine { request ->
            when(request.url.encodedPath)
            {
                CodexConstants.DEVICE_USER_CODE_PATH -> respond(
                    """{"device_auth_id":"device-1","user_code":"ABCD","interval":1}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                CodexConstants.DEVICE_TOKEN_PATH -> if(pollCalls.getAndIncrement() == 0)
                {
                    respond("", HttpStatusCode.Forbidden)
                }
                else
                {
                    respond(
                        """{"authorization_code":"auth-code","code_challenge":"challenge","code_verifier":"verifier"}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                CodexConstants.OAUTH_TOKEN_PATH -> respond(
                    """{"id_token":"id-token","access_token":"access-token","refresh_token":"refresh-token"}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }
        val store = MemoryStore()
        val client = HttpClient(engine)
        try
        {
            val auth = CodexDeviceAuthClient(
                httpClient = client,
                issuer = "https://auth.test",
                clientId = "client-test",
                credentialStore = store,
                delayMillis = {},
            )
            val deviceCode = auth.requestDeviceCode()
            val account = auth.completeDeviceLogin(deviceCode)

            assertEquals("ABCD", deviceCode.userCode)
            assertEquals(2, pollCalls.get())
            assertEquals("access-token", store.load()!!.accessToken)
            assertNotNull(account)
        }
        finally
        {
            client.close()
        }
    }

    @Test
    fun authManagerRefreshesOnceAndSharesRotatedTokenAcrossConcurrentCallers() = runBlocking<Unit>
    {
        val now = 1_000_000_000L
        val old = CodexOAuthCredentials(
            idToken = jwt("""{"exp":9999999999}"""),
            accessToken = jwt("""{"exp":${now / 1000 + 1}}"""),
            refreshToken = "refresh-old",
            lastRefresh = now,
        )
        val refreshedAccess = jwt("""{"exp":9999999999}""")
        val refreshCalls = AtomicInteger(0)
        val engine = MockEngine { request ->
            assertEquals(CodexConstants.OAUTH_TOKEN_PATH, request.url.encodedPath)
            refreshCalls.incrementAndGet()
            respond(
                """{"id_token":"id-new","access_token":"$refreshedAccess","refresh_token":"refresh-new"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val store = MemoryStore(old)
        val client = HttpClient(engine)
        try
        {
            val manager = CodexAuthManager(
                credentialStore = store,
                httpClient = client,
                issuer = "https://auth.test",
                clientId = "client-test",
                importCodexCliCredentialsIfMissing = false,
                nowMillis = { now },
            )
            val headers = coroutineScope {
                (1..2).map { async { manager.authorizationHeaders() } }.awaitAll()
            }

            assertEquals(1, refreshCalls.get())
            assertEquals("Bearer $refreshedAccess", headers[0]["Authorization"])
            assertEquals("refresh-new", store.load()!!.refreshToken)
        }
        finally
        {
            client.close()
        }
    }

    @Test
    fun concurrentUnauthorizedRecoveryDoesNotRotateRefreshTokenTwice() = runBlocking<Unit>
    {
        val oldAccess = "old-access"
        val newAccess = "new-access"
        val refreshCalls = AtomicInteger(0)
        val store = MemoryStore(CodexOAuthCredentials("id", oldAccess, "refresh-old"))
        val client = HttpClient(MockEngine {
            refreshCalls.incrementAndGet()
            respond(
                """{"access_token":"$newAccess","refresh_token":"refresh-new"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        })
        try
        {
            val manager = CodexAuthManager(
                credentialStore = store,
                httpClient = client,
                importCodexCliCredentialsIfMissing = false,
            )
            val observed = "Bearer $oldAccess"
            val results = coroutineScope {
                (1..2).map { async { manager.recoverUnauthorized(observed) } }.awaitAll()
            }

            assertTrue(results.all { it })
            assertEquals(1, refreshCalls.get())
            assertEquals(newAccess, store.load()!!.accessToken)
        }
        finally
        {
            client.close()
        }
    }

    @Test
    fun authManagerUsesEightDayFallbackWhenAccessExpiryIsNotDecodable() = runBlocking<Unit>
    {
        val now = 2_000_000_000L
        val store = MemoryStore(
            CodexOAuthCredentials(
                idToken = "id-old",
                accessToken = "opaque-access",
                refreshToken = "refresh-old",
                lastRefresh = now - CodexConstants.FALLBACK_REFRESH_INTERVAL_MILLIS,
            )
        )
        val client = HttpClient(MockEngine {
            respond(
                """{"access_token":"opaque-new","refresh_token":"refresh-new"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        })
        try
        {
            val manager = CodexAuthManager(
                credentialStore = store,
                httpClient = client,
                importCodexCliCredentialsIfMissing = false,
                nowMillis = { now },
            )

            assertEquals("Bearer opaque-new", manager.authorizationHeaders()["Authorization"])
            assertEquals("refresh-new", store.load()!!.refreshToken)
        }
        finally
        {
            client.close()
        }
    }

    @Test
    fun authorizationHeadersIncludeAccountAndFedrampOnlyWhenCredentialMetadataProvidesThem() = runBlocking<Unit>
    {
        val store = MemoryStore(
            CodexOAuthCredentials(
                idToken = "id",
                accessToken = "access",
                refreshToken = "refresh",
                accountId = "acct-1",
                isFedramp = true,
                lastRefresh = 10_000L,
            )
        )
        val client = HttpClient(MockEngine { error("refresh should not be called") })
        try
        {
            val manager = CodexAuthManager(
                credentialStore = store,
                httpClient = client,
                importCodexCliCredentialsIfMissing = false,
                nowMillis = { 10_000L },
            )

            val headers = manager.authorizationHeaders()

            assertEquals("acct-1", headers["ChatGPT-Account-ID"])
            assertEquals("true", headers["X-OpenAI-Fedramp"])
        }
        finally
        {
            client.close()
        }
    }

    @Test
    fun invalidGrantDeletesCredentialsWithoutLeakingRefreshToken() = runBlocking<Unit>
    {
        val refreshToken = "refresh-secret"
        val store = MemoryStore(
            CodexOAuthCredentials("id", "access", refreshToken, lastRefresh = 0L)
        )
        val client = HttpClient(MockEngine {
            respond(
                """{"error":"invalid_grant"}""",
                HttpStatusCode.BadRequest,
                jsonHeaders,
            )
        })
        try
        {
            val manager = CodexAuthManager(
                credentialStore = store,
                httpClient = client,
                importCodexCliCredentialsIfMissing = false,
                nowMillis = { CodexConstants.FALLBACK_REFRESH_INTERVAL_MILLIS + 1L },
            )
            val exception = assertThrows(CodexAuthenticationException::class.java) {
                runBlocking { manager.authorizationHeaders() }
            }

            assertNull(store.load())
            assertFalse(exception.message.orEmpty().contains(refreshToken))
        }
        finally
        {
            client.close()
        }
    }

    @Test
    fun modelCatalogPreservesEtagAndCachesForFiveMinutes() = runBlocking<Unit>
    {
        val token = CodexOAuthCredentials(
            "id",
            jwt("""{"exp":9999999999}"""),
            "refresh",
            accountId = "acct-catalog",
            isFedramp = true,
            lastRefresh = 1_000_000L,
        )
        val store = MemoryStore(token)
        val calls = AtomicInteger(0)
        val engine = MockEngine { request ->
            calls.incrementAndGet()
            assertEquals(CodexConstants.CLIENT_VERSION, request.url.parameters["client_version"])
            assertEquals("acct-catalog", request.headers["ChatGPT-Account-ID"])
            assertEquals("true", request.headers["X-OpenAI-Fedramp"])
            respond(
                """{"models":[{"slug":"gpt-5-codex","display_name":"GPT-5-Codex","visibility":"list","priority":1,"default_reasoning_effort":"high","supported_reasoning_efforts":[{"effort":"low"},{"effort":"high"}],"context_window":200000,"input_modalities":["text","image"],"supports_verbosity":true}]}""",
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.ETag to listOf("\"catalog-1\""),
                ),
            )
        }
        val client = HttpClient(engine)
        try
        {
            val manager = CodexAuthManager(
                credentialStore = store,
                httpClient = client,
                importCodexCliCredentialsIfMissing = false,
                nowMillis = { 1_000_000L },
            )
            val catalog = CodexModelCatalogClient(manager, client, nowMillis = { 1_000_000L })
            assertEquals("gpt-5-codex", catalog.listModels().single().slug)
            assertEquals("\"catalog-1\"", catalog.lastCatalog!!.etag)
            catalog.listModels()
            assertEquals(1, calls.get())
        }
        finally
        {
            client.close()
        }
    }

    @Test
    fun factoryUsesGenericOpenAIResponsesModeAndCodexEndpoint()
    {
        val pipe = CodexPipes.create("gpt-5-codex", MemoryAuthManager().manager)

        assertEquals("/responses", pipe.internalGetEndpointForTest())
    }

    private class MemoryStore(initial: CodexOAuthCredentials? = null) : CodexCredentialStore
    {
        private var value = initial

        override fun load(): CodexOAuthCredentials? = value

        override fun save(credentials: CodexOAuthCredentials)
        {
            value = credentials
        }

        override fun delete(): Boolean
        {
            val hadValue = value != null
            value = null
            return hadValue
        }
    }

    private class MemoryAuthManager
    {
        val manager = CodexAuthManager(
            credentialStore = MemoryStore(CodexOAuthCredentials("id", "access", "refresh")),
            importCodexCliCredentialsIfMissing = false,
        )
    }

    private fun jwt(payload: String): String
    {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "${encoder.encodeToString("{}".toByteArray())}.${encoder.encodeToString(payload.trimIndent().toByteArray())}.signature"
    }

    private companion object
    {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }

}
