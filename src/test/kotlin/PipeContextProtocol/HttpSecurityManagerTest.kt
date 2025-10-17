package com.TTT.PipeContextProtocol

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpSecurityManagerTest
{
    private fun baseOptions(): HttpContextOptions
    {
        return HttpContextOptions().apply {
            baseUrl = "https://api.example.com"
            endpoint = "/resource"
            method = "GET"
            permissions.add(Permissions.Read)
            allowedMethods.add("GET")
            allowedHosts.add("api.example.com")
        }
    }

    @Test
    fun `fails when base URL missing`()
    {
        val manager = HttpSecurityManager()
        val options = baseOptions().apply { baseUrl = "" }

        val result = manager.validateHttpRequest(options)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Base URL is required") })
    }

    @Test
    fun `fails on unsupported http method`()
    {
        val manager = HttpSecurityManager()
        val options = baseOptions().apply {
            method = "TRACE"
            allowedMethods.clear()
            allowedMethods.add("TRACE")
        }

        val result = manager.validateHttpRequest(options)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Unsupported HTTP method") })
    }

    @Test
    fun `fails on path traversal in endpoint`()
    {
        val manager = HttpSecurityManager()
        val options = baseOptions().apply { endpoint = "../admin" }

        val result = manager.validateHttpRequest(options)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Relative path traversal") })
    }

    @Test
    fun `fails when allowed hosts omit target`()
    {
        val manager = HttpSecurityManager()
        val options = baseOptions().apply {
            allowedHosts.clear()
            allowedHosts.add("other.example.com")
        }

        val result = manager.validateHttpRequest(options)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("not in allowed hosts list") })
    }

    @Test
    fun `fails when wildcard host provided`()
    {
        val manager = HttpSecurityManager()
        val options = baseOptions().apply {
            allowedHosts.clear()
            allowedHosts.add("*")
        }

        val result = manager.validateHttpRequest(options)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Wildcard host '*' is not permitted") })
    }

    @Test
    fun `fails on private network URL`()
    {
        val manager = HttpSecurityManager()
        val options = baseOptions().apply {
            baseUrl = "http://127.0.0.1"
            allowedHosts.clear()
            allowedHosts.add("127.0.0.1")
        }

        val result = manager.validateHttpRequest(options)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("private networks") })
    }

    @Test
    fun `passes for fully whitelisted request`()
    {
        val manager = HttpSecurityManager()
        val options = baseOptions()

        val result = manager.validateHttpRequest(options)

        assertTrue(result.isValid, "Expected request to pass validation: ${result.errors}")
    }
}
