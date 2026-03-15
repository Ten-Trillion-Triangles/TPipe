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
        TraceServerRegistry.clientSessions.clear()
        TraceServerRegistry.traces.clear()
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

        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"key":"dashboard-key"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val token = Regex(""""token"\s*:\s*"([^"]+)"""")
            .find(loginResponse.bodyAsText())
            ?.groupValues
            ?.get(1)
            .orEmpty()
        assertTrue(token.isNotBlank())

        val listWithSession = client.get("/api/traces") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, listWithSession.status)
        assertTrue(listWithSession.bodyAsText().contains("trace-1"))
    }
}
