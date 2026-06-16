package com.TTT.TraceServer

import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TraceServerEventsTest {

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

    @Test
    fun postEventPersistsAndRestReturnsIt() = testApplication {
        application { traceServerModule() }
        TraceServerRegistry.agentAuthMechanism = { true }
        TraceServerRegistry.clientAuthMechanism = null

        // Seed a trace
        client.post("/api/traces") {
            contentType(ContentType.Application.Json)
            header("X-Tenant", "alpha")
            setBody("""{"pipelineId":"e-1","htmlContent":"<x/>","name":"E1","status":"RUNNING"}""")
        }

        // Post an event
        val postRes = client.post("/api/traces/e-1/events") {
            contentType(ContentType.Application.Json)
            header("X-Tenant", "alpha")
            setBody("""{"eventId":"evt-1","type":"llm_call","payload":{"model":"claude","tokens":42}}""")
        }
        assertEquals(HttpStatusCode.Accepted, postRes.status)

        // Fetch events via REST
        val listRes = client.get("/api/traces/e-1/events") {
            header("X-Tenant", "alpha")
        }
        assertEquals(HttpStatusCode.OK, listRes.status)
        val body = listRes.bodyAsText()
        // prettyPrint = true adds spaces; check for individual tokens.
        assertTrue(body.contains("evt-1"), body)
        assertTrue(body.contains("llm_call"), body)
    }



}
