package com.TTT.PipeContextProtocol

import org.junit.Test
import kotlin.test.*

class HttpSsrfTest
{
    @Test
    fun `isPrivateNetwork detects various private ranges`()
    {
        val manager = HttpSecurityManager()

        // Use reflection to test private method if needed,
        // but we can test via validateUrl

        val privateUrls = listOf(
            "http://127.0.0.1",
            "http://localhost",
            "http://10.0.0.1",
            "http://172.16.0.1",
            "http://172.31.255.255",
            "http://192.168.1.1",
            "http://169.254.169.254",
            "http://0.0.0.0",
            "http://[::1]",
            "http://[fc00::1]",
            "http://[fe80::1]"
        )

        for (url in privateUrls)
        {
            val result = manager.validateUrl(url)
            assertFalse(result.isValid, "URL $url should be identified as private/invalid")
            assertTrue(result.errors.any { it.contains("private networks") }, "Error for $url should mention private networks")
        }
    }

    @Test
    fun `validateUrl returns resolved IP`()
    {
        val manager = HttpSecurityManager()
        val result = manager.validateUrl("http://example.com")

        // Since we can't guarantee resolution in all environments,
        // we just check that it's either null (if resolution failed)
        // or a valid IP format if it succeeded.
        if (result.isValid)
        {
            assertNotNull(result.validatedIp, "Validated IP should not be null for a valid external URL")
            assertTrue(result.validatedIp!!.matches(Regex("""\d+\.\d+\.\d+\.\d+|.*:.*""")), "Validated IP should be an IP address")
        }
    }

    @Test
    fun `checkSsrfProtection fails closed on invalid host`()
    {
        val manager = HttpSecurityManager()
        // A host that definitely doesn't exist
        val result = manager.checkSsrfProtection("http://this-host-does-not-exist-at-all-12345.com")

        assertTrue(result.isPrivate, "Non-resolvable host should be treated as private (fail-closed)")
        assertNull(result.resolvedIp, "Resolved IP should be null for non-resolvable host")
    }
}
