package com.TTT.TraceServer

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceServerAuthTest {

    @AfterTest
    fun tearDown() {
        TraceServerRegistry.agentAuthMechanism = null
        TraceServerRegistry.clientAuthMechanism = null
        TraceServerRegistry.configureStore(com.TTT.TraceServer.store.InMemoryTraceStore())
        for (tenant in listOf("default", "alpha")) {
            TraceServerRegistry.sessionsFor(tenant).clear()
        }
    }

    private suspend fun loginAndGetToken(client: io.ktor.client.HttpClient, key: String = "dashboard-key"): String {
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"key":"$key"}""")
        }
        return Regex(""""token"\s*:\s*"([^"]+)"""")
            .find(res.bodyAsText())
            ?.groupValues
            ?.get(1)
            .orEmpty()
    }

    @Test
    fun agentPostUsesAgentAuthAndClientReadsUseSessionAuth() = testApplication {
        application {
            traceServerModule()
        }

        TraceServerRegistry.agentAuthMechanism = { authHeader ->
            authHeader == "Bearer agent-token"
        }
        TraceServerRegistry.clientAuthMechanism = { key ->
            key == "dashboard-key"
        }

        val unauthorizedPost = client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer bad-token")
            setBody("""{"pipelineId":"trace-1","htmlContent":"<html></html>","name":"Trace 1","status":"SUCCESS"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorizedPost.status)

        val authorizedPost = client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer agent-token")
            setBody("""{"pipelineId":"trace-1","htmlContent":"<html></html>","name":"Trace 1","status":"SUCCESS"}""")
        }
        assertEquals(HttpStatusCode.OK, authorizedPost.status)

        val listWithoutSession = client.get("/api/traces")
        assertEquals(HttpStatusCode.Unauthorized, listWithoutSession.status)

        val token = loginAndGetToken(client, "dashboard-key")
        assertTrue(token.isNotBlank())

        val listWithSession = client.get("/api/traces") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, listWithSession.status)
        assertTrue(listWithSession.bodyAsText().contains("trace-1"))
    }

    @Test
    fun missingAuthHeaderReturns401WhenAuthEnabled() = testApplication {
        application { traceServerModule() }
        TraceServerRegistry.clientAuthMechanism = { _ -> true }
        val res = client.get("/api/traces")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertTrue(res.bodyAsText().contains("\"error\"") && res.bodyAsText().contains("\"unauthorized\""))
    }

    @Test
    fun expiredSessionReturns401() = testApplication {
        application { traceServerModule() }
        TraceServerRegistry.clientAuthMechanism = { _ -> true }
        val token = "expired-token-123"
        TraceServerRegistry.clientSessions[token] = System.currentTimeMillis() - 1
        val res = client.get("/api/traces") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun deleteRoundTripWithValidSession() = testApplication {
        application { traceServerModule() }
        TraceServerRegistry.agentAuthMechanism = { auth -> auth == "Bearer agent-token" }
        TraceServerRegistry.clientAuthMechanism = { _ -> true }
        val token = loginAndGetToken(client)

        client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer agent-token")
            setBody("""{"pipelineId":"trace-1","htmlContent":"<x/>","name":"T1","status":"SUCCESS"}""")
        }

        val del = client.delete("/api/traces/trace-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NoContent, del.status)
    }
}
