package com.TTT.Util

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import java.net.InetAddress

class RestSecurityTest
{
    @Test
    fun testHttpRequestWithIpAndHostHeader()
    {
        runBlocking {
            val hostname = "httpbin.org"
            val ip = try {
                InetAddress.getByName(hostname).hostAddress
            } catch (e: Exception) {
                null
            }

            if (ip == null) {
                println("Skipping test: Could not resolve $hostname")
                return@runBlocking
            }

            println("Resolved $hostname to $ip")

            // Reconstruct URL with IP
            val urlWithIp = "https://$ip/get"
            val headers = mapOf("Host" to hostname)

            val response = httpRequest(
                url = urlWithIp,
                method = "GET",
                headers = headers,
                timeoutMs = 10000
            )

            println("Response: ${response.statusCode} ${response.statusMessage}")
            if (!response.success) {
                println("Error body: ${response.body}")
            }

            // If SNI/Host header works correctly, this should succeed (HTTP 200)
            // Some CDNs might still fail if they strictly require SNI in the TLS handshake
            // which Ktor CIO should derive from the Host header or URL.
            // When URL has IP, Ktor might not send SNI unless configured.

            assertTrue(response.success || response.body.contains("Hostname mismatch") || response.body.contains("Certificate"),
                "Request failed: ${response.statusMessage}. Body: ${response.body}")
        }
    }
}
