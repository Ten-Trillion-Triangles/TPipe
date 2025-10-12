package com.TTT.PipeContextProtocol

import com.TTT.Util.HttpAuth
import com.TTT.Util.httpRequest
import kotlinx.serialization.Serializable

/**
 * Executes HTTP requests with authentication, timeout, and response handling.
 * Uses TPipe's existing Ktor-based HTTP utilities with PCP integration and configurable security.
 */
class HttpExecutor : PcpExecutor
{
    private val securityManager = HttpSecurityManager()
    
    /**
     * Set HTTP security level for this executor.
     */
    fun setSecurityLevel(level: HttpSecurityLevel)
    {
        securityManager.setSecurityLevel(level)
    }
    
    /**
     * Set custom HTTP security configuration.
     */
    fun setSecurityConfig(config: HttpSecurityConfig)
    {
        securityManager.setSecurityConfig(config)
    }
    
    /**
     * Execute HTTP request based on PCP context options.
     */
    override suspend fun execute(request: PcPRequest): PcpRequestResult
    {
        val startTime = System.currentTimeMillis()
        val options = request.httpContextOptions
        
        return try
        {
            // Validate permissions and security using new comprehensive method
            val validation = securityManager.validateHttpRequest(options)
            if (!validation.isValid)
            {
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Http,
                    error = "HTTP validation failed: ${validation.errors.joinToString(", ")}"
                )
            }
            
            // Build full URL
            val fullUrl = options.baseUrl + options.endpoint
            
            // Create auth configuration
            val auth = HttpAuth(
                type = options.authType,
                credentials = options.authCredentials
            )
            
            // Execute HTTP request using enhanced Rest.kt
            val response = httpRequest(
                url = fullUrl,
                method = options.method,
                body = options.requestBody,
                headers = options.headers,
                auth = auth,
                timeoutMs = options.timeoutMs.toLong(),
                followRedirects = options.followRedirects
            )
            
            // Format response for PCP
            val output = formatHttpResponse(response)
            
            PcpRequestResult(
                success = response.success,
                output = output,
                executionTimeMs = response.responseTimeMs,
                transport = Transport.Http,
                error = if (!response.success) "HTTP ${response.statusCode}: ${response.statusMessage}" else null
            )
        }
        catch (e: Exception)
        {
            PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Http,
                error = "HTTP execution failed: ${e.message}"
            )
        }
    }
    
    /**
     * Format HTTP response for PCP output.
     */
    private fun formatHttpResponse(response: com.TTT.Util.HttpResponseData): String
    {
        val output = StringBuilder()
        
        output.appendLine("HTTP ${response.statusCode} ${response.statusMessage}")
        output.appendLine("Response Time: ${response.responseTimeMs}ms")
        output.appendLine()
        
        // Add important headers
        val importantHeaders = listOf("content-type", "content-length", "location", "set-cookie")
        importantHeaders.forEach { headerName ->
            response.headers.entries.find { it.key.lowercase() == headerName }?.let { (name, value) ->
                output.appendLine("$name: $value")
            }
        }
        
        if (response.headers.isNotEmpty())
        {
            output.appendLine()
        }
        
        // Add response body
        if (response.body.isNotEmpty())
        {
            output.appendLine(response.body)
        }
        
        return output.toString()
    }
}
