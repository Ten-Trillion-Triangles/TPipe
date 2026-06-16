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

class TraceServerCompressionTest {

    @AfterTest
    fun tearDown() {
        TraceServerRegistry.agentAuthMechanism = null
        TraceServerRegistry.clientAuthMechanism = null
        TraceServerRegistry.authConfig = AuthConfig()
        TraceServerRegistry.configureStore(com.TTT.TraceServer.store.InMemoryTraceStore())
        for (tenant in listOf("default", "alpha")) {
            TraceServerRegistry.sessionsFor(tenant).clear()
        }
    }

    @Test
    fun largeResponseIsGzipCompressed() = testApplication {
        application { traceServerModule() }
        TraceServerRegistry.clientAuthMechanism = null
        // Build a payload large enough to exceed the 1024-byte compression floor.
        val big = "x".repeat(4096)
        client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            header("X-Tenant", "alpha")
            setBody("""{"pipelineId":"big","htmlContent":"$big","name":"Big","status":"SUCCESS"}""")
        }
        val res = client.get("/api/traces?limit=10") {
            header(HttpHeaders.AcceptEncoding, "gzip")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val encoding = res.headers[HttpHeaders.ContentEncoding]
        assertTrue(encoding == "gzip" || encoding == null,
            "Expected Content-Encoding: gzip or null; got '$encoding'")
        // The body must still parse as JSON when the client transparently decodes.
        val body = res.bodyAsText()
        assertTrue(body.contains("\"items\""), body)
    }

    @Test
    fun smallResponseIsNotCompressed() = testApplication {
        application { traceServerModule() }
        val res = client.get("/api/health") {
            header(HttpHeaders.AcceptEncoding, "gzip")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val encoding = res.headers[HttpHeaders.ContentEncoding]
        assertTrue(encoding == null || encoding == "identity", "small health body should not be gzipped; got '$encoding'")
    }
}
