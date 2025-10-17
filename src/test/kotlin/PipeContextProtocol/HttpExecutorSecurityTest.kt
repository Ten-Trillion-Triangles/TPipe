package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Focused tests for HttpExecutor's context merging and whitelist enforcement.
 */
class HttpExecutorSecurityTest
{
    private fun secureContextOption(
        baseUrl: String = "https://api.example.com",
        endpoint: String = "/allowed",
        method: String = "GET"
    ): HttpContextOptions = HttpContextOptions().apply {
        this.baseUrl = baseUrl
        this.endpoint = endpoint
        this.method = method
        permissions.add(Permissions.Read)
        allowedMethods.add(method.uppercase())
        URIUtils.hostVariants(baseUrl).forEach { allowedHosts.add(it) }
    }

    private fun request(
        baseUrl: String,
        endpoint: String,
        method: String,
        permissions: List<Permissions>
    ): PcPRequest = PcPRequest(
        httpContextOptions = HttpContextOptions().apply {
            this.baseUrl = baseUrl
            this.endpoint = endpoint
            this.method = method
            this.permissions.addAll(permissions)
            URIUtils.hostVariants(baseUrl).forEach { allowedHosts.add(it) }
            allowedMethods.add(method.uppercase())
        }
    )

    private object URIUtils
    {
        fun hostVariants(url: String): List<String>
        {
            val parsed = java.net.URL(url)
            val host = parsed.host.lowercase()
            val portSuffix = when {
                parsed.port == -1 || parsed.port == parsed.defaultPort -> null
                else -> ":${parsed.port}"
            }

            return buildList {
                add(host)
                portSuffix?.let { add(host + it) }
            }
        }
    }

    @Test
    fun `rejects requests to hosts not in context whitelist`()
    {
        val executor = HttpExecutor()
        val context = PcpContext().apply {
            addHttpOption(secureContextOption(baseUrl = "https://api.example.com"))
        }

        val request = request(
            baseUrl = "https://evil.example.org",
            endpoint = "/allowed",
            method = "GET",
            permissions = listOf(Permissions.Read)
        )

        val result = runBlocking { executor.execute(request, context) }

        assertFalse(result.success)
        assertTrue(result.error?.contains("not in security whitelist") == true)
    }

    @Test
    fun `rejects requests using disallowed HTTP method`()
    {
        val executor = HttpExecutor()
        val context = PcpContext().apply {
            addHttpOption(secureContextOption(method = "GET"))
        }

        val request = request(
            baseUrl = "https://api.example.com",
            endpoint = "/allowed",
            method = "POST",
            permissions = listOf(Permissions.Write)
        )

        val result = runBlocking { executor.execute(request, context) }

        assertFalse(result.success)
        assertTrue(result.error?.contains("not in security whitelist") == true)
    }

    @Test
    fun `rejects requests outside permitted path prefix`()
    {
        val executor = HttpExecutor()
        val context = PcpContext().apply {
            addHttpOption(secureContextOption(endpoint = "/allowed"))
        }

        val request = request(
            baseUrl = "https://api.example.com",
            endpoint = "/other/resource",
            method = "GET",
            permissions = listOf(Permissions.Read)
        )

        val result = runBlocking { executor.execute(request, context) }

        assertFalse(result.success)
        assertTrue(result.error?.contains("not in security whitelist") == true)
    }

    @Test
    fun `matches when method and path align with context`()
    {
        val executor = HttpExecutor()
        executor.setSecurityConfig(
            HttpSecurityConfig(
                level = HttpSecurityLevel.BALANCED,
                requireExplicitHosts = true,
                requireExplicitMethods = true,
                requirePermissions = true
            )
        )

        val context = PcpContext().apply {
            addHttpOption(secureContextOption(endpoint = "/allowed"))
        }

        val request = request(
            baseUrl = "https://api.example.com",
            endpoint = "/allowed/resource",
            method = "GET",
            permissions = listOf(Permissions.Read)
        )

        val result = runBlocking { executor.execute(request, context) }

        assertFalse(result.success)
        assertTrue(result.error?.contains("Error:") == true)
        assertEquals(Transport.Http, result.transport)
    }
}
