package com.TTT.TraceServer

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceServerOpenApiTest {

    @Test
    fun openApiSpecIsServed() = testApplication {
        application { traceServerModule() }
        val res = client.get("/api/openapi.yaml")
        assertEquals(HttpStatusCode.OK, res.status)
        val text = res.bodyAsText()
        assertTrue(text.startsWith("openapi:"), "expected YAML root, got: ${text.take(40)}")
        assertTrue(text.contains("/api/traces"))
        assertTrue(text.contains("/api/auth/refresh"))
        assertTrue(text.contains("/metrics"))
    }
}
