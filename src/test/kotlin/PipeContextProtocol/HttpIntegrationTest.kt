package com.TTT.PipeContextProtocol

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for HTTP execution pipeline.
 * Tests end-to-end flow from LLM response parsing to HTTP request execution.
 */
class HttpIntegrationTest
{
    @Test
    fun testCompleteHttpPipeline()
    {
        runBlocking {
            delay(5000)
            // Simulate LLM response with HTTP PCP request
            val llmResponse = """
            {
                "httpContextOptions": {
                    "baseUrl": "https://httpbin.org",
                    "endpoint": "/get",
                    "method": "GET",
                    "permissions": ["Read"]
                }
            }
            """.trimIndent()
            
            // Test complete pipeline
            val parser = PcpResponseParser()
            val dispatcher = PcpExecutionDispatcher()
            
            // 1. Parse LLM response
            val parseResult = parser.extractPcpRequests(llmResponse)
            assertTrue(parseResult.success, "Should parse HTTP request successfully")
            assertEquals(1, parseResult.requests.size, "Should find one request")
            
            val request = parseResult.requests.first()
            assertEquals(Transport.Http, parser.determineTransport(request), "Should detect HTTP transport")
            
            // 2. Execute through dispatcher
            val executionResult = dispatcher.executeRequests(parseResult.requests, PcpContext())
            assertTrue(executionResult.success, "Should execute HTTP request successfully")
            assertEquals(1, executionResult.results.size, "Should have one result")
            
            val result = executionResult.results.first()
            assertTrue(result.success, "HTTP request should execute successfully")
            assertTrue(result.output.contains("HTTP 200"), "Should return successful HTTP response")
            assertEquals(Transport.Http, result.transport, "Should use HTTP transport")
        }
    }
    
    @Test
    fun testHttpSecurityValidation()
    {
        runBlocking {
            delay(5000)
            // Test SSRF protection
            val ssrfResponse = """
            {
                "httpContextOptions": {
                    "baseUrl": "http://localhost:8080",
                    "endpoint": "/admin",
                    "method": "GET"
                }
            }
            """.trimIndent()
            
            val parser = PcpResponseParser()
            val dispatcher = PcpExecutionDispatcher()
            
            val parseResult = parser.extractPcpRequests(ssrfResponse)
            assertTrue(parseResult.success, "Should parse SSRF request")
            
            val executionResult = dispatcher.executeRequests(parseResult.requests, PcpContext())
            assertTrue(!executionResult.success, "Should block SSRF attempt")
            
            val result = executionResult.results.first()
            assertTrue(!result.success, "SSRF request should be blocked")
            assertTrue(result.error?.contains("private networks") == true, "Should indicate SSRF protection")
        }
    }
    
    @Test
    fun testHttpAuthentication()
    {
        runBlocking {
            delay(5000)
            // Test Bearer token authentication
            val authResponse = """
            {
                "httpContextOptions": {
                    "baseUrl": "https://httpbin.org",
                    "endpoint": "/bearer",
                    "method": "GET",
                    "authType": "BEARER",
                    "authCredentials": {
                        "token": "test-token-123"
                    },
                    "permissions": ["Read"]
                }
            }
            """.trimIndent()
            
            val parser = PcpResponseParser()
            val dispatcher = PcpExecutionDispatcher()
            
            val parseResult = parser.extractPcpRequests(authResponse)
            assertTrue(parseResult.success, "Should parse auth request successfully")
            
            val executionResult = dispatcher.executeRequests(parseResult.requests, PcpContext())
            assertTrue(executionResult.success, "Should execute auth request successfully")
            
            val result = executionResult.results.first()
            assertTrue(result.success, "Auth request should execute successfully")
            assertTrue(result.output.contains("HTTP 200"), "Should return successful response")
        }
    }
    
    @Test
    fun testHttpPostWithBody()
    {
        runBlocking {
            delay(5000)
            // Test POST request with JSON body
            val postResponse = """
            {
                "httpContextOptions": {
                    "baseUrl": "https://httpbin.org",
                    "endpoint": "/post",
                    "method": "POST",
                    "requestBody": "{\"test\": \"data\"}",
                    "headers": {
                        "Content-Type": "application/json"
                    },
                    "permissions": ["Write"]
                }
            }
            """.trimIndent()
            
            val parser = PcpResponseParser()
            val dispatcher = PcpExecutionDispatcher()
            
            val parseResult = parser.extractPcpRequests(postResponse)
            assertTrue(parseResult.success, "Should parse POST request successfully")
            
            val executionResult = dispatcher.executeRequests(parseResult.requests, PcpContext())
            assertTrue(executionResult.success, "Should execute POST request successfully")
            
            val result = executionResult.results.first()
            assertTrue(result.success, "POST request should execute successfully")
            assertTrue(result.output.contains("HTTP 200"), "Should return successful response")
        }
    }
    
    @Test
    fun testHttpPermissionValidation()
    {
        runBlocking {
            // Test that POST requires Write permission
            val postWithoutPermission = """
            {
                "httpContextOptions": {
                    "baseUrl": "https://httpbin.org",
                    "endpoint": "/post",
                    "method": "POST",
                    "requestBody": "{\"test\": \"data\"}"
                }
            }
            """.trimIndent()
            
            val parser = PcpResponseParser()
            val dispatcher = PcpExecutionDispatcher()
            
            val parseResult = parser.extractPcpRequests(postWithoutPermission)
            assertTrue(parseResult.success, "Should parse POST request")
            
            val executionResult = dispatcher.executeRequests(parseResult.requests, PcpContext())
            assertTrue(!executionResult.success, "Should fail due to missing permission")
            
            val result = executionResult.results.first()
            assertTrue(!result.success, "POST without Write permission should fail")
            assertTrue(result.error?.contains("Write permission required") == true, "Should indicate permission error")
        }
    }
}
