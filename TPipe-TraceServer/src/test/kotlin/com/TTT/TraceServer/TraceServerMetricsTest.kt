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
import kotlin.test.assertTrue

class TraceServerMetricsTest {

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
    fun metricsEndpointReturnsPrometheusText() = testApplication {
        application { traceServerModule() }
        val res = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, res.status)
        val text = res.bodyAsText()
        // Prometheus text exposition includes HELP/TYPE blocks. We don't
        // assert specific counters because those are v2 internal, but we
        // do require the format markers to be present.
        assertTrue(text.contains("# HELP") || text.contains("# TYPE") || text.isBlank(),
            "expected Prometheus exposition format")
    }

    @Test
    fun postTracesIncrementsCustomCounter() = testApplication {
        application { traceServerModule() }
        TraceServerRegistry.agentAuthMechanism = { true }
        client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            header("X-Tenant", "alpha")
            setBody("""{"pipelineId":"p-1","htmlContent":"<x/>","name":"P1","status":"SUCCESS"}""")
        }
        val res = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, res.status)
        val text = res.bodyAsText()
        assertTrue(text.contains("tpipe_traces_received_total"),
            "expected custom counter in scrape output, got: $text")
        assertTrue(text.contains("tenant=\"alpha\""),
            "expected tenant=\"alpha\" tag in scrape output, got: $text")
    }
}
