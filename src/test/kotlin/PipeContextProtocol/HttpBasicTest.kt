package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Basic HTTP functionality test.
 */
class HttpBasicTest
{
    @Test
    fun testHttpTransportDetection()
    {
        val parser = PcpResponseParser()
        
        // Test HTTP transport detection
        val httpRequest = PcPRequest(
            httpContextOptions = HttpContextOptions().apply {
                baseUrl = "https://example.com"
                endpoint = "/api/test"
                method = "GET"
            }
        )
        
        val transport = parser.determineTransport(httpRequest)
        assertEquals(Transport.Http, transport, "Should detect HTTP transport")
    }
    
    @Test
    fun testHttpValidation()
    {
        val parser = PcpResponseParser()
        
        // Test valid HTTP request
        val validRequest = PcPRequest(
            httpContextOptions = HttpContextOptions().apply {
                baseUrl = "https://example.com"
                endpoint = "/api/test"
                method = "GET"
            }
        )
        
        val validation = parser.validatePcpRequest(validRequest)
        assertTrue(validation.isValid, "Valid HTTP request should pass validation")
        
        // Test invalid HTTP request (missing baseUrl) - this won't be detected as HTTP transport
        val invalidRequest = PcPRequest(
            httpContextOptions = HttpContextOptions().apply {
                endpoint = "/api/test"
                method = "GET"
            }
        )
        
        // Since baseUrl is empty, this won't be detected as HTTP transport
        val transport = parser.determineTransport(invalidRequest)
        assertEquals(Transport.Unknown, transport, "Request without baseUrl should not be detected as HTTP")
    }
    
    @Test
    fun testHttpSecurityManager()
    {
        val securityManager = HttpSecurityManager()
        
        // Test valid URL
        val validUrl = securityManager.validateUrl("https://api.github.com/users")
        assertTrue(validUrl.isValid, "Valid external URL should pass")
        
        // Test SSRF protection
        val ssrfUrl = securityManager.validateUrl("http://localhost:8080/admin")
        assertTrue(!ssrfUrl.isValid, "Localhost URL should be blocked")
        assertTrue(ssrfUrl.errors.any { it.contains("private networks") }, 
            "Should indicate SSRF protection")
        
        // Test authentication validation
        val validAuth = securityManager.validateAuthentication("BEARER", mapOf("token" to "valid-token-123"))
        assertTrue(validAuth.isValid, "Valid bearer auth should pass")
        
        val invalidAuth = securityManager.validateAuthentication("BEARER", mapOf())
        assertTrue(!invalidAuth.isValid, "Missing bearer token should fail")
    }
}
