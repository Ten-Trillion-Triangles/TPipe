package com.TTT.TraceServer

import com.TTT.TraceServer.auth.HashedPassword
import com.TTT.TraceServer.auth.Pbkdf2PasswordHasher
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TraceServerAuthHashTest {

    private val hasher = Pbkdf2PasswordHasher(iterations = 100_000) // fast for tests

    @AfterTest
    fun tearDown() {
        TraceServerRegistry.agentAuthMechanism = null
        TraceServerRegistry.clientAuthMechanism = null
        TraceServerRegistry.authConfig = AuthConfig() // reset to default
        TraceServerRegistry.configureStore(com.TTT.TraceServer.store.InMemoryTraceStore())
        for (tenant in listOf("default", "alpha", "beta")) {
            TraceServerRegistry.sessionsFor(tenant).clear()
        }
    }

    @Test
    fun pbkdf2HasherProducesDifferentHashesForSamePlaintext() {
        val h1 = hasher.hash("hunter2")
        val h2 = hasher.hash("hunter2")
        assertNotEquals(h1.hash, h2.hash, "Random salt should produce different hashes")
        assertEquals(h1.algorithm, h2.algorithm)
    }

    @Test
    fun pbkdf2HasherVerifiesCorrectPlaintext() {
        val expected = hasher.hash("hunter2")
        assertTrue(hasher.verify("hunter2", expected))
        assertFalse(hasher.verify("hunter3", expected))
    }

    @Test
    fun loginSucceedsWithHashedPassword() = testApplication {
        application { traceServerModule() }
        TraceServerRegistry.agentAuthMechanism = null
        TraceServerRegistry.clientAuthMechanism = null
        TraceServerRegistry.authConfig = TraceServerRegistry.authConfig.copy(
            passwordHasherEnabled = true,
            expectedHash = hasher.hash("dashboard-pw")
        )
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"dashboard-pw"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"token\""), body)
        assertTrue(body.contains("\"refreshToken\""), body)
        assertTrue(body.contains("\"expiresInMs\""), body)
    }

    @Test
    fun loginRejectsWrongHashedPasswordWith401() = testApplication {
        application { traceServerModule() }
        TraceServerRegistry.agentAuthMechanism = null
        TraceServerRegistry.clientAuthMechanism = null
        TraceServerRegistry.authConfig = TraceServerRegistry.authConfig.copy(
            passwordHasherEnabled = true,
            expectedHash = hasher.hash("dashboard-pw")
        )
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"wrong-pw"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertTrue(res.bodyAsText().contains("\"unauthorized\""))
    }

    @Test
    fun loginFallsBackToLambdaWhenExpectedHashIsNull() = testApplication {
        // Legacy path: expectedHash is null, so the v1 clientAuthMechanism lambda is used.
        application { traceServerModule() }
        TraceServerRegistry.clientAuthMechanism = { key -> key == "demo" }
        TraceServerRegistry.authConfig = TraceServerRegistry.authConfig.copy(
            passwordHasherEnabled = true,
            expectedHash = null
        )
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"key":"demo"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val resBad = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"key":"nope"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resBad.status)
    }

    @Test
    fun hashedPasswordBase64RoundTrip() {
        val raw = hasher.hash("hello")
        val decoded = HashedPassword.decode(raw.salt)
        assertTrue(decoded.isNotEmpty())
    }
}
