package com.TTT.TraceServer

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TraceServerAuthRefreshTest {

    @AfterTest
    fun tearDown() {
        TraceServerRegistry.agentAuthMechanism = null
        TraceServerRegistry.clientAuthMechanism = null
        TraceServerRegistry.authConfig = AuthConfig()
        TraceServerRegistry.configureStore(com.TTT.TraceServer.store.InMemoryTraceStore())
        for (tenant in listOf("default", "alpha", "beta")) {
            TraceServerRegistry.sessionsFor(tenant).clear()
        }
    }

    private suspend fun login(client: io.ktor.client.HttpClient): Pair<String, String> {
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"key":"dashboard-key"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        val token = Regex(""""token"\s*:\s*"([^"]+)"""")
            .find(body)?.groupValues?.get(1).orEmpty()
        val refresh = Regex(""""refreshToken"\s*:\s*"([^"]+)"""")
            .find(body)?.groupValues?.get(1).orEmpty()
        return token to refresh
    }

    @Test
    fun refreshRotatesAndOldRefreshIsInvalidated() = testApplication {
        application { traceServerModule() }
        TraceServerRegistry.clientAuthMechanism = { key -> key == "dashboard-key" }

        val (_, refresh1) = login(client)
        val res = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refresh1"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        val newRefresh = Regex(""""refreshToken"\s*:\s*"([^"]+)"""")
            .find(body)?.groupValues?.get(1).orEmpty()
        assertNotEquals(refresh1, newRefresh)

        // Replay the old refresh token; should be rejected because the
        // rotation revoked both the refresh and its paired access token.
        val replay = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refresh1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
    }

    @Test
    fun refreshRejectsBlankTokenWith400() = testApplication {
        application { traceServerModule() }
        val res = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun refreshRejectsExpiredRefreshWith401() = testApplication {
        application { traceServerModule() }
        TraceServerRegistry.clientAuthMechanism = { key -> key == "dashboard-key" }
        val (_, refresh) = login(client)
        // Manually expire the refresh token by clearing expiry.
        val session = TraceServerRegistry.lookupSession(refresh)!!
        TraceServerRegistry.sessionsFor("default")[refresh] = session.copy(expiresAt = 1L)
        val res = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refresh"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun newAccessTokenWorksForProtectedRoute() = testApplication {
        application { traceServerModule() }
        TraceServerRegistry.clientAuthMechanism = { key -> key == "dashboard-key" }
        val (_, refresh) = login(client)
        val res = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refresh"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val newAccess = Regex(""""token"\s*:\s*"([^"]+)"""")
            .find(res.bodyAsText())?.groupValues?.get(1).orEmpty()
        assertTrue(newAccess.isNotBlank())
        val list = client.get("/api/traces") {
            headers.append("Authorization", "Bearer $newAccess")
        }
        assertEquals(HttpStatusCode.OK, list.status)
    }
}
