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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TraceServerApiTest {

    @AfterTest
    fun tearDown() {
        TraceServerRegistry.agentAuthMechanism = null
        TraceServerRegistry.clientAuthMechanism = null
        TraceServerRegistry.configureStore(com.TTT.TraceServer.store.InMemoryTraceStore())
        for (tenant in listOf("default", "alpha", "beta")) {
            TraceServerRegistry.sessionsFor(tenant).clear()
        }
    }

    private fun ApplicationTestBuilder.installModule() {
        application { traceServerModule() }
    }

    private suspend fun loginAndGetToken(client: io.ktor.client.HttpClient, key: String = "dashboard-key"): String {
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"key":"$key"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val token = Regex(""""token"\s*:\s*"([^"]+)"""")
            .find(res.bodyAsText())
            ?.groupValues
            ?.get(1)
            .orEmpty()
        assertTrue(token.isNotBlank(), "login should return a token")
        return token
    }

    @Test
    fun healthEndpointReturnsEnvelopeShape() = testApplication {
        installModule()
        val res = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"status\"") && body.contains("\"ok\""), "expected status=ok in body")
        assertTrue(body.contains("\"uptimeMs\""), "expected uptimeMs in body")
        assertTrue(body.contains("\"traces\""), "expected traces in body")
        assertTrue(body.contains("\"version\""), "expected version in body")
    }

    @Test
    fun postTracesRejectsBlankFieldsWith400() = testApplication {
        installModule()
        val res = client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipelineId":"","htmlContent":"<x/>","name":"","status":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"error\"") && body.contains("\"bad_request\""), body)
    }

    @Test
    fun postTracesRejectsOversizedPayloadWith413() = testApplication {
        val config = TraceServerConfigBridge.legacy().copy(maxPayloadBytes = 64L)
        application { traceServerModule(config) }
        val huge = "<x>" + "a".repeat(1024) + "</x>"
        val res = client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipelineId":"big","htmlContent":${'"'}$huge${'"'},"name":"Big","status":"SUCCESS"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertTrue(res.bodyAsText().contains("\"error\"") && res.bodyAsText().contains("\"payload_too_large\""))
    }

    @Test
    fun postTracesHappyPathAndGetById() = testApplication {
        installModule()
        TraceServerRegistry.clientAuthMechanism = { key -> key == "dashboard-key" }
        val token = loginAndGetToken(client)

        val postRes = client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipelineId":"trace-1","htmlContent":"<html/>","name":"Trace 1","status":"SUCCESS"}""")
        }
        assertEquals(HttpStatusCode.OK, postRes.status)

        val getRes = client.get("/api/traces/trace-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, getRes.status)
        assertTrue(getRes.bodyAsText().contains("\"pipelineId\"") && getRes.bodyAsText().contains("\"trace-1\""))
    }

    @Test
    fun deleteTracesReturns204AndThen404() = testApplication {
        installModule()
        TraceServerRegistry.clientAuthMechanism = { key -> key == "dashboard-key" }
        val token = loginAndGetToken(client)

        client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipelineId":"trace-1","htmlContent":"<html/>","name":"Trace 1","status":"SUCCESS"}""")
        }

        val first = client.delete("/api/traces/trace-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NoContent, first.status)

        val second = client.delete("/api/traces/trace-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, second.status)
        assertTrue(second.bodyAsText().contains("\"error\"") && second.bodyAsText().contains("\"not_found\""))
    }

    @Test
    fun deleteWithoutSessionReturns401WhenAuthEnabled() = testApplication {
        installModule()
        TraceServerRegistry.clientAuthMechanism = { key -> key == "dashboard-key" }
        client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipelineId":"trace-1","htmlContent":"<html/>","name":"Trace 1","status":"SUCCESS"}""")
        }
        val res = client.delete("/api/traces/trace-1")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertTrue(res.bodyAsText().contains("\"error\"") && res.bodyAsText().contains("\"unauthorized\""))
    }

    @Test
    fun listTracesSupportsStatusQueryAndSince() = testApplication {
        installModule()
        TraceServerRegistry.clientAuthMechanism = { key -> key == "dashboard-key" }
        val token = loginAndGetToken(client)

        val now = System.currentTimeMillis() - 1
        for ((id, status) in listOf("a" to "SUCCESS", "b" to "FAILURE", "c" to "SUCCESS")) {
            client.post("/api/traces") {
                contentType(ContentType.Application.Json)
                setBody("""{"pipelineId":"$id","htmlContent":"<x/>","name":"$id","status":"$status"}""")
            }
        }

        val all = client.get("/api/traces?status=SUCCESS") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, all.status)
        val allBody = all.bodyAsText()
        assertTrue(allBody.contains("\"items\""), allBody)
        assertTrue(allBody.contains("\"total\"") && allBody.contains("2"), allBody)
        assertTrue(allBody.contains("\"a\"") && allBody.contains("\"c\""))

        val since = client.get("/api/traces?since=$now") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, since.status)
        val sinceBody = since.bodyAsText()
        assertTrue(sinceBody.contains("\"total\"") && sinceBody.contains("3"), sinceBody)
    }

    @Test
    fun xTenantHeaderPartitionsTraces() = testApplication {
        installModule()
        // Disable client auth so the GET endpoints do not require a session;
        // this test is purely about tenant partitioning.
        TraceServerRegistry.clientAuthMechanism = null

        // Submit a trace under tenant "alpha"
        client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            header("X-Tenant", "alpha")
            setBody("""{"pipelineId":"alpha-1","htmlContent":"<x/>","name":"Alpha 1","status":"SUCCESS"}""")
        }
        // Submit a trace under tenant "beta"
        client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            header("X-Tenant", "beta")
            setBody("""{"pipelineId":"beta-1","htmlContent":"<x/>","name":"Beta 1","status":"SUCCESS"}""")
        }

        val alphaList = client.get("/api/traces") {
            header("X-Tenant", "alpha")
        }
        assertEquals(HttpStatusCode.OK, alphaList.status)
        val alphaBody = alphaList.bodyAsText()
        assertTrue(alphaBody.contains("\"alpha-1\""), alphaBody)
        assertTrue(!alphaBody.contains("\"beta-1\""), "alpha list should not include beta traces: $alphaBody")
    }

    @Test
    fun corsPreflightFromOffAllowlistOriginIsRejected() = testApplication {
        val config = TraceServerConfigBridge.legacy().copy(
            cors = CorsConfig(allowedHosts = listOf("localhost"))
        )
        application { traceServerModule(config) }
        val res = client.options("/api/traces") {
            header(HttpHeaders.Origin, "https://attacker.example.com")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
        // CORS rejection: a missing ACAO header means the preflight failed.
        val acao = res.headers[HttpHeaders.AccessControlAllowOrigin]
        assertNotNull(acao == null || acao != "https://attacker.example.com", "off-allowlist origin should not get ACAO")
    }
}
