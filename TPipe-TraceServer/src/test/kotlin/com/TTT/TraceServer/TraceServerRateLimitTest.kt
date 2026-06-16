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

class TraceServerRateLimitTest {

    @AfterTest
    fun tearDown() {
        TraceServerRegistry.agentAuthMechanism = null
        TraceServerRegistry.clientAuthMechanism = null
        TraceServerRegistry.authConfig = AuthConfig()
        TraceServerRegistry.configureStore(com.TTT.TraceServer.store.InMemoryTraceStore())
        for (tenant in listOf("default", "alpha", "beta")) {
            TraceServerRegistry.sessionsFor(tenant).clear()
        }
        TraceServerRegistry.clearTenantRateBuckets()
    }

    @Test
    fun burstAbovePerIpLimitReturns429() = testApplication {
        val config = TraceServerConfigBridge.legacy().copy(
            rateLimit = RateLimitConfig(perIpWrites = 3, perTenantWrites = 1000)
        )
        application { traceServerModule(config) }
        TraceServerRegistry.agentAuthMechanism = { true }
        val statuses = (1..5).map {
            client.post("/api/traces") {
                contentType(ContentType.Application.Json)
                setBody("""{"pipelineId":"p-$it","htmlContent":"<x/>","name":"P$it","status":"SUCCESS"}""")
            }.status
        }
        // The first 3 succeed; the rest are 429.
        val ok = statuses.count { it == HttpStatusCode.OK }
        val tooMany = statuses.count { it == HttpStatusCode.TooManyRequests }
        assertEquals(3, ok, "expected 3 OK, got: $statuses")
        assertEquals(2, tooMany, "expected 2 rate-limited, got: $statuses")
    }

    @Test
    fun perTenantBucketAppliesAcrossIps() = testApplication {
        // Use a very small per-tenant cap so the test runs fast.
        val config = TraceServerConfigBridge.legacy().copy(
            rateLimit = RateLimitConfig(perIpWrites = 100, perTenantWrites = 2)
        )
        application { traceServerModule(config) }
        TraceServerRegistry.agentAuthMechanism = { true }
        val statuses = (1..4).map {
            client.post("/api/traces") {
                contentType(ContentType.Application.Json)
                header("X-Tenant", "alpha")
                setBody("""{"pipelineId":"p-$it","htmlContent":"<x/>","name":"P$it","status":"SUCCESS"}""")
            }.status
        }
        val ok = statuses.count { it == HttpStatusCode.OK }
        val tooMany = statuses.count { it == HttpStatusCode.TooManyRequests }
        assertEquals(2, ok, "expected 2 OK, got: $statuses")
        assertEquals(2, tooMany, "expected 2 rate-limited, got: $statuses")
    }

    @Test
    fun rateLimitDisabledBypassesCheck() = testApplication {
        val config = TraceServerConfigBridge.legacy().copy(
            rateLimit = RateLimitConfig(enabled = false, perIpWrites = 1)
        )
        application { traceServerModule(config) }
        TraceServerRegistry.agentAuthMechanism = { true }
        val statuses = (1..5).map {
            client.post("/api/traces") {
                contentType(ContentType.Application.Json)
                setBody("""{"pipelineId":"p-$it","htmlContent":"<x/>","name":"P$it","status":"SUCCESS"}""")
            }.status
        }
        assertTrue(statuses.all { it == HttpStatusCode.OK }, "expected all OK with rate limit disabled, got: $statuses")
    }
}
