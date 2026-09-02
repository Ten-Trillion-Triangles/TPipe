package com.TTT.AgentCore.Gateway

import com.TTT.AgentCore.gateway.AgentCoreGatewayCredentials
import com.TTT.AgentCore.gateway.AgentCoreGatewayCredentialsProvider
import com.TTT.AgentCore.gateway.AgentCoreGatewaySigV4Auth
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentCoreGatewayAuthTest {
    @Test
    fun signsTheExactRequestBodyAndIncludesSessionCredentials() = runBlocking {
        val auth = AgentCoreGatewaySigV4Auth(
            region = "us-east-1",
            credentialsProvider = AgentCoreGatewayCredentialsProvider {
                AgentCoreGatewayCredentials(
                    accessKeyId = "AKIDEXAMPLE",
                    secretAccessKey = "secret",
                    sessionToken = "session-token"
                )
            },
            clock = Clock.fixed(Instant.parse("2025-01-02T03:04:05Z"), ZoneOffset.UTC)
        )
        val first = auth.sign(
            url = "https://gateway.example.com/mcp?cursor=a%20b",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = "first".toByteArray(StandardCharsets.UTF_8)
        )
        val second = auth.sign(
            url = "https://gateway.example.com/mcp?cursor=a%20b",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = "second".toByteArray(StandardCharsets.UTF_8)
        )

        assertEquals("20250102T030405Z", first["x-amz-date"])
        assertEquals("gateway.example.com", first["Host"])
        assertEquals("session-token", first["x-amz-security-token"])
        assertEquals(sha256("first"), first["x-amz-content-sha256"])
        assertTrue(first["Authorization"].orEmpty().startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"))
        assertNotEquals(first["Authorization"], second["Authorization"])
    }

    @Test
    fun preservesEncodedPathDelimitersInCanonicalRequest() = runBlocking {
        val auth = AgentCoreGatewaySigV4Auth(
            region = "us-east-1",
            credentialsProvider = AgentCoreGatewayCredentialsProvider {
                AgentCoreGatewayCredentials("AKIDEXAMPLE", "secret")
            },
            clock = Clock.fixed(Instant.parse("2025-01-02T03:04:05Z"), ZoneOffset.UTC)
        )

        val path = auth.sign(
            url = "https://gateway.example.com/a/b",
            method = "POST",
            headers = emptyMap(),
            body = ByteArray(0)
        )
        val encodedDelimiter = auth.sign(
            url = "https://gateway.example.com/a%2Fb",
            method = "POST",
            headers = emptyMap(),
            body = ByteArray(0)
        )

        assertNotEquals(path["Authorization"], encodedDelimiter["Authorization"])
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
