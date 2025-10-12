package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Test HTTP security levels and configuration.
 */
class HttpSecurityLevelTest
{
    @Test
    fun testSecurityLevelDefaults()
    {
        // Test STRICT level defaults
        val strictConfig = HttpSecurityConfig(HttpSecurityLevel.STRICT)
        assertEquals(30000L, strictConfig.maxTimeoutMs)
        assertEquals(65536, strictConfig.maxRequestBodySize)
        assertEquals(10, strictConfig.maxHeaders)
        assertTrue(strictConfig.requireExplicitHosts)
        assertTrue(strictConfig.requireExplicitMethods)
        assertTrue(strictConfig.requirePermissions)
        assertFalse(strictConfig.allowPrivateNetworks)
        
        // Test PERMISSIVE level defaults
        val permissiveConfig = HttpSecurityConfig(HttpSecurityLevel.PERMISSIVE)
        assertEquals(1800000L, permissiveConfig.maxTimeoutMs)
        assertEquals(10485760, permissiveConfig.maxRequestBodySize)
        assertEquals(100, permissiveConfig.maxHeaders)
        assertFalse(permissiveConfig.requireExplicitHosts)
        assertFalse(permissiveConfig.requireExplicitMethods)
        assertTrue(permissiveConfig.requirePermissions)
        assertFalse(permissiveConfig.allowPrivateNetworks)
    }
    
    @Test
    fun testStrictSecurityValidation()
    {
        val securityManager = HttpSecurityManager(HttpSecurityConfig(HttpSecurityLevel.STRICT))
        
        // Test that STRICT requires explicit hosts
        val requestWithoutHosts = HttpContextOptions().apply {
            baseUrl = "https://api.github.com"
            endpoint = "/users"
            method = "GET"
            permissions = mutableListOf(Permissions.Read)
        }
        
        val validation = securityManager.validateHttpRequest(requestWithoutHosts)
        assertFalse(validation.isValid, "STRICT should require explicit hosts")
        assertTrue(validation.errors.any { it.contains("Allowed hosts list is required") })
    }
    
    @Test
    fun testPermissiveSecurityValidation()
    {
        val securityManager = HttpSecurityManager(HttpSecurityConfig(HttpSecurityLevel.PERMISSIVE))
        
        // Test that PERMISSIVE allows requests without explicit hosts
        val requestWithoutHosts = HttpContextOptions().apply {
            baseUrl = "https://api.github.com"
            endpoint = "/users"
            method = "GET"
            permissions = mutableListOf(Permissions.Read)
        }
        
        val validation = securityManager.validateHttpRequest(requestWithoutHosts)
        assertTrue(validation.isValid, "PERMISSIVE should allow requests without explicit hosts")
    }
    
    @Test
    fun testPermissionRequirements()
    {
        val securityManager = HttpSecurityManager(HttpSecurityConfig(HttpSecurityLevel.BALANCED))
        
        // Test GET requires Read permission
        val getRequest = HttpContextOptions().apply {
            baseUrl = "https://httpbin.org"
            endpoint = "/get"
            method = "GET"
            allowedHosts = mutableListOf("httpbin.org")
            allowedMethods = mutableListOf("GET")
            // No permissions - should fail
        }
        
        val getValidation = securityManager.validateHttpRequest(getRequest)
        assertFalse(getValidation.isValid, "GET should require Read permission")
        assertTrue(getValidation.errors.any { it.contains("Read permission required") })
        
        // Test POST requires Write permission
        val postRequest = HttpContextOptions().apply {
            baseUrl = "https://httpbin.org"
            endpoint = "/post"
            method = "POST"
            allowedHosts = mutableListOf("httpbin.org")
            allowedMethods = mutableListOf("POST")
            permissions = mutableListOf(Permissions.Read) // Wrong permission
        }
        
        val postValidation = securityManager.validateHttpRequest(postRequest)
        assertFalse(postValidation.isValid, "POST should require Write permission")
        assertTrue(postValidation.errors.any { it.contains("Write permission required") })
    }
    
    @Test
    fun testSSRFProtectionByLevel()
    {
        // BALANCED level blocks private networks
        val balancedManager = HttpSecurityManager(HttpSecurityConfig(HttpSecurityLevel.BALANCED))
        val localhostValidation = balancedManager.validateUrl("http://localhost:8080/admin")
        assertFalse(localhostValidation.isValid, "BALANCED should block localhost")
        
        // DISABLED level allows private networks
        val disabledConfig = HttpSecurityConfig(
            level = HttpSecurityLevel.DISABLED,
            allowPrivateNetworks = true
        )
        val disabledManager = HttpSecurityManager(disabledConfig)
        val localhostValidationDisabled = disabledManager.validateUrl("http://localhost:8080/admin")
        assertTrue(localhostValidationDisabled.isValid, "DISABLED should allow localhost when configured")
    }
    
    @Test
    fun testCustomSecurityConfig()
    {
        // Test custom configuration override
        val customConfig = HttpSecurityConfig(
            level = HttpSecurityLevel.BALANCED,
            maxTimeoutMs = 60000L, // Custom 1 minute timeout
            requireExplicitHosts = false, // Override BALANCED default
            allowPrivateNetworks = true // Allow localhost for development
        )
        
        val securityManager = HttpSecurityManager(customConfig)
        
        assertEquals(60000L, securityManager.getSecurityConfig().maxTimeoutMs)
        assertFalse(securityManager.getSecurityConfig().requireExplicitHosts)
        assertTrue(securityManager.getSecurityConfig().allowPrivateNetworks)
    }
}
