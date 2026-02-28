package com.TTT.PipeContextProtocol

import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.net.URL
import java.util.regex.Pattern

/**
 * HTTP security levels for different use cases.
 */
enum class HttpSecurityLevel
{
    STRICT,     // Require explicit allowlists, minimal timeouts, strict validation
    BALANCED,   // Reasonable defaults with some flexibility  
    PERMISSIVE, // Looser restrictions for development/testing
    DISABLED    // Minimal security (not recommended for production)
}

/**
 * HTTP security configuration with per-level defaults.
 */
@Serializable
data class HttpSecurityConfig(
    val level: HttpSecurityLevel = HttpSecurityLevel.BALANCED,
    val maxTimeoutMs: Long = getDefaultMaxTimeout(level),
    val maxRequestBodySize: Int = getDefaultMaxBodySize(level),
    val maxHeaders: Int = getDefaultMaxHeaders(level),
    val requireExplicitHosts: Boolean = getDefaultRequireHosts(level),
    val requireExplicitMethods: Boolean = getDefaultRequireMethods(level),
    val requirePermissions: Boolean = getDefaultRequirePermissions(level),
    val allowPrivateNetworks: Boolean = getDefaultAllowPrivate(level)
) {
    companion object {
        fun getDefaultMaxTimeout(level: HttpSecurityLevel): Long = when(level) {
            HttpSecurityLevel.STRICT -> 30000L      // 30 seconds
            HttpSecurityLevel.BALANCED -> 300000L   // 5 minutes  
            HttpSecurityLevel.PERMISSIVE -> 1800000L // 30 minutes
            HttpSecurityLevel.DISABLED -> Long.MAX_VALUE
        }
        
        fun getDefaultMaxBodySize(level: HttpSecurityLevel): Int = when(level) {
            HttpSecurityLevel.STRICT -> 65536       // 64KB
            HttpSecurityLevel.BALANCED -> 1048576   // 1MB
            HttpSecurityLevel.PERMISSIVE -> 10485760 // 10MB
            HttpSecurityLevel.DISABLED -> Int.MAX_VALUE
        }
        
        fun getDefaultMaxHeaders(level: HttpSecurityLevel): Int = when(level) {
            HttpSecurityLevel.STRICT -> 10
            HttpSecurityLevel.BALANCED -> 50
            HttpSecurityLevel.PERMISSIVE -> 100
            HttpSecurityLevel.DISABLED -> Int.MAX_VALUE
        }
        
        fun getDefaultRequireHosts(level: HttpSecurityLevel): Boolean = when(level) {
            HttpSecurityLevel.STRICT -> true
            HttpSecurityLevel.BALANCED -> true
            HttpSecurityLevel.PERMISSIVE -> false
            HttpSecurityLevel.DISABLED -> false
        }
        
        fun getDefaultRequireMethods(level: HttpSecurityLevel): Boolean = when(level) {
            HttpSecurityLevel.STRICT -> true
            HttpSecurityLevel.BALANCED -> true  
            HttpSecurityLevel.PERMISSIVE -> false
            HttpSecurityLevel.DISABLED -> false
        }
        
        fun getDefaultRequirePermissions(level: HttpSecurityLevel): Boolean = when(level) {
            HttpSecurityLevel.STRICT -> true
            HttpSecurityLevel.BALANCED -> true
            HttpSecurityLevel.PERMISSIVE -> true
            HttpSecurityLevel.DISABLED -> false
        }
        
        fun getDefaultAllowPrivate(level: HttpSecurityLevel): Boolean = when(level) {
            HttpSecurityLevel.STRICT -> false
            HttpSecurityLevel.BALANCED -> false
            HttpSecurityLevel.PERMISSIVE -> false
            HttpSecurityLevel.DISABLED -> true
        }
    }
}

/**
 * HTTP validation result.
 */
@Serializable
data class HttpValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String> = emptyList(),
    val validatedIp: String? = null
)

/**
 * Validates HTTP requests for security compliance with configurable security levels.
 * Prevents SSRF attacks and enforces URL whitelisting based on security configuration.
 */
class HttpSecurityManager(
    private var securityConfig: HttpSecurityConfig = HttpSecurityConfig()
)
{
    /**
     * Private network IP ranges that should be blocked to prevent SSRF attacks.
     * 
     * SSRF (Server-Side Request Forgery) attacks occur when an attacker can make the server
     * send HTTP requests to internal/private networks that should not be accessible externally.
     * 
     * Examples of attacks prevented:
     * - http://localhost:8080/admin - Access local admin interfaces
     * - http://192.168.1.1/config - Access router admin panels
     * - http://169.254.169.254/metadata - Access cloud metadata (AWS, GCP, Azure)
     * - http://10.0.0.5:22 - Port scan internal network services
     */
    private val privateNetworkRanges = listOf(
        "127.0.0.0/8",      // Loopback addresses (localhost, 127.0.0.1, etc.)
        "10.0.0.0/8",       // Private Class A networks (10.x.x.x)
        "172.16.0.0/12",    // Private Class B networks (172.16-31.x.x)
        "192.168.0.0/16",   // Private Class C networks (192.168.x.x)
        "169.254.0.0/16",   // Link-local addresses (AWS/GCP metadata services)
        "::1/128",          // IPv6 loopback (localhost equivalent)
        "fc00::/7",         // IPv6 unique local addresses (private)
        "fe80::/10"         // IPv6 link-local addresses
    )
    
    /**
     * HTTP headers that are potentially dangerous and should trigger warnings.
     * 
     * These headers can be used for:
     * - Authentication bypass (authorization, cookie)
     * - IP spoofing attacks (x-forwarded-for, x-real-ip)
     * - Host header injection (host)
     * 
     * We don't block these entirely as they may be legitimate, but we warn about them
     * so developers are aware when LLMs are trying to manipulate authentication/routing.
     */
    private val dangerousHeaders = setOf(
        "authorization",    // Bearer tokens, Basic auth - could leak credentials
        "cookie",          // Session cookies - could hijack sessions
        "x-forwarded-for", // IP spoofing - could bypass IP-based restrictions
        "x-real-ip",       // IP spoofing - could bypass IP-based restrictions  
        "host"             // Host header injection - could bypass virtual host restrictions
    )
    
    /**
     * Update security configuration.
     */
    fun setSecurityLevel(level: HttpSecurityLevel)
    {
        securityConfig = HttpSecurityConfig(level = level)
    }
    
    /**
     * Update security configuration with custom settings.
     */
    fun setSecurityConfig(config: HttpSecurityConfig)
    {
        securityConfig = config
    }
    
    /**
     * Get current security configuration.
     */
    fun getSecurityConfig(): HttpSecurityConfig = securityConfig
    
    /**
     * Validate HTTP request against security configuration.
     * 
     * This is the main security gate that prevents:
     * 1. Unauthorized requests (missing permissions)
     * 2. SSRF attacks (requests to private networks)
     * 3. Resource exhaustion (oversized requests/timeouts)
     * 4. Unrestricted access (requires explicit allowlists based on security level)
     * 
     * Security levels determine how strict the validation is:
     * - STRICT: Requires explicit hosts/methods, short timeouts, small requests
     * - BALANCED: Reasonable defaults with some flexibility (DEFAULT)
     * - PERMISSIVE: Looser restrictions for development
     * - DISABLED: Minimal security (NOT RECOMMENDED)
     */
    fun validateHttpRequest(options: HttpContextOptions): HttpValidationResult
    {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var validatedIp: String? = null
        
        // Validate required fields
        if (options.baseUrl.isEmpty())
        {
            errors.add("Base URL is required")
        }
        
        val normalizedMethod = options.method.uppercase()

        if (!HttpConstants.ALL_METHODS.contains(normalizedMethod))
        {
            errors.add("Unsupported HTTP method: ${options.method}")
        }

        // PERMISSION VALIDATION - Prevent unauthorized requests
        // Unlike the original stdio system that allowed any command with empty permissions,
        // we require explicit permissions for ALL HTTP methods by default
        if (securityConfig.requirePermissions)
        {
            when
            {
                normalizedMethod in HttpConstants.READ_METHODS ->
                {
                    if (!options.permissions.contains(Permissions.Read))
                    {
                        errors.add("Read permission required for ${normalizedMethod} requests")
                    }
                }
                normalizedMethod in HttpConstants.WRITE_METHODS ->
                {
                    if (!options.permissions.contains(Permissions.Write))
                    {
                        errors.add("Write permission required for ${normalizedMethod} requests")
                    }
                }
            }
        }

        // Endpoint sanitisation
        val trimmedEndpoint = options.endpoint.trim()
        if (trimmedEndpoint.startsWith("http", ignoreCase = true) || trimmedEndpoint.startsWith("//"))
        {
            errors.add("Endpoint must be relative and may not include a scheme or host")
        }
        if (trimmedEndpoint.contains(".."))
        {
            errors.add("Relative path traversal (.. segments) is not allowed in endpoint")
        }
        if (trimmedEndpoint.contains('\\'))
        {
            errors.add("Backslash characters are not allowed in HTTP endpoints")
        }

        // HOST ALLOWLIST VALIDATION - Prevent requests to arbitrary external services
        // This prevents LLMs from making requests to any website on the internet
        // unless explicitly allowed by the developer
        if (securityConfig.requireExplicitHosts && options.allowedHosts.isEmpty())
        {
            errors.add("Allowed hosts list is required at current security level")
        }

        if (options.allowedHosts.any { it.trim() == "*" })
        {
            errors.add("Wildcard host '*' is not permitted in allowed hosts")
        }
        
        // METHOD ALLOWLIST VALIDATION - Prevent use of dangerous HTTP methods
        // This prevents LLMs from using methods like DELETE unless explicitly allowed
        if (securityConfig.requireExplicitMethods && options.allowedMethods.isEmpty())
        {
            errors.add("Allowed methods list is required at current security level")
        }
        
        // Validate method against allowed methods if specified
        if (options.allowedMethods.isNotEmpty() && !options.allowedMethods.contains(normalizedMethod))
        {
            errors.add("Method ${normalizedMethod} not in allowed methods list")
        }

        // RESOURCE LIMIT VALIDATION - Prevent resource exhaustion attacks
        // These limits prevent LLMs from creating requests that could hang or crash the system
        if (options.timeoutMs > securityConfig.maxTimeoutMs)
        {
            errors.add("Timeout ${options.timeoutMs}ms exceeds maximum allowed ${securityConfig.maxTimeoutMs}ms")
        }
        
        if (options.requestBody.length > securityConfig.maxRequestBodySize)
        {
            errors.add("Request body size ${options.requestBody.length} exceeds maximum ${securityConfig.maxRequestBodySize} bytes")
        }
        
        if (options.headers.size > securityConfig.maxHeaders)
        {
            errors.add("Header count ${options.headers.size} exceeds maximum ${securityConfig.maxHeaders}")
        }
        
        // URL SECURITY VALIDATION - Prevent SSRF and malicious URLs
        val fullUrl = buildString {
            append(options.baseUrl)
            if (trimmedEndpoint.isNotEmpty())
            {
                if (!options.baseUrl.endsWith("/") && !trimmedEndpoint.startsWith("/"))
                {
                    append('/')
                }
                append(trimmedEndpoint)
            }
        }
        val urlValidation = validateUrl(fullUrl, options.allowedHosts)
        if (!urlValidation.isValid)
        {
            errors.addAll(urlValidation.errors)
        }
        warnings.addAll(urlValidation.warnings)
        validatedIp = urlValidation.validatedIp
        
        // HEADER SECURITY VALIDATION - Detect potentially dangerous headers
        val headerValidation = validateHeaders(options.headers)
        if (!headerValidation.isValid)
        {
            errors.addAll(headerValidation.errors)
        }
        warnings.addAll(headerValidation.warnings)
        
        // AUTHENTICATION VALIDATION - Ensure auth credentials are properly formatted
        val authValidation = validateAuthentication(options.authType, options.authCredentials)
        if (!authValidation.isValid)
        {
            errors.addAll(authValidation.errors)
        }
        
        return HttpValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            validatedIp = validatedIp
        )
    }
    
    /**
     * Validate URL against security policies and SSRF protection.
     */
    fun validateUrl(url: String, allowedHosts: List<String> = emptyList()): HttpValidationResult
    {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var validatedIp: String? = null
        
        try
        {
            val urlObj = URL(url)
            
            // Check protocol
            if (urlObj.protocol !in listOf("http", "https"))
            {
                errors.add("Only HTTP and HTTPS protocols are allowed")
            }
            
            // SSRF PROTECTION - Block requests to private networks unless explicitly allowed
            // This prevents attacks like:
            // - http://localhost:8080/admin (access local services)
            // - http://192.168.1.1/config (access internal network devices)  
            // - http://169.254.169.254/metadata (access cloud metadata services)
            // Can be disabled for local development by setting allowPrivateNetworks = true
            if (!securityConfig.allowPrivateNetworks)
            {
                val result = checkSsrfProtection(url)
                if (result.isPrivate)
                {
                    errors.add("Access to private networks is not allowed (SSRF protection)")
                }
                validatedIp = result.resolvedIp
            }
            
            // Check allowed hosts if specified
            if (allowedHosts.isNotEmpty())
            {
                val host = urlObj.host.lowercase()
                val hostWithPort = if (urlObj.port > 0 && urlObj.port != urlObj.defaultPort)
                {
                    "$host:${urlObj.port}"
                }
                else host

                val normalizedAllowed = allowedHosts.map { it.trim().lowercase() }
                val matches = normalizedAllowed.any { allowed ->
                    when
                    {
                        allowed.contains(":") -> hostWithPort == allowed
                        else -> host == allowed || host.endsWith(".${allowed.trimStart('.')}")
                    }
                }

                if (!matches)
                {
                    errors.add("Host '${urlObj.host}' is not in allowed hosts list")
                }
            }
            
            // Check for suspicious patterns
            if (url.contains("@") && !url.startsWith("https://"))
            {
                warnings.add("URL contains '@' character which may indicate credential injection")
            }
            
            // Check for port scanning attempts
            val port = urlObj.port
            if (port != -1 && port !in listOf(80, 443, 8080, 8443))
            {
                warnings.add("Non-standard port $port detected")
            }
        }
        catch (e: Exception)
        {
            errors.add("Invalid URL format: ${e.message}")
        }
        
        return HttpValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            validatedIp = validatedIp
        )
    }

    /**
     * Result of SSRF protection check.
     */
    data class SsrfCheckResult(
        val isPrivate: Boolean,
        val resolvedIp: String? = null
    )
    
    /**
     * Check if URL targets private networks (SSRF protection).
     * Now returns the resolved IP to prevent DNS rebinding attacks.
     */
    fun checkSsrfProtection(url: String): SsrfCheckResult
    {
        try
        {
            val urlObj = URL(url)
            val host = urlObj.host
            
            // Check for localhost variations first to avoid DNS lookup if possible
            if (host.lowercase() in listOf("localhost", "127.0.0.1", "::1", "0.0.0.0"))
            {
                return SsrfCheckResult(true, host)
            }
            
            // Resolve hostname to IP and check against private ranges
            // This is done once here and the IP must be used for the actual request
            val address = InetAddress.getByName(host)
            val ip = address.hostAddress
            
            return SsrfCheckResult(isPrivateNetwork(ip), ip)
        }
        catch (e: Exception)
        {
            // If we can't resolve, we MUST assume it's unsafe (fail-closed)
            // because an attacker might use a non-resolvable host that resolves
            // to a private IP during the actual request window.
            return SsrfCheckResult(true, null)
        }
    }
    
    /**
     * Validate HTTP headers for security issues.
     */
    fun validateHeaders(headers: Map<String, String>): HttpValidationResult
    {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        headers.forEach { (name, value) ->
            val lowerName = name.lowercase()
            
            // Check for dangerous headers
            if (lowerName in dangerousHeaders)
            {
                warnings.add("Potentially sensitive header: $name")
            }
            
            // Check for injection attempts
            if (value.contains("\r") || value.contains("\n"))
            {
                errors.add("Header injection detected in $name")
            }
            
            // Check for overly long headers
            if (value.length > 8192)
            {
                errors.add("Header $name exceeds maximum length")
            }
        }
        
        return HttpValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
    
    /**
     * Sanitize request body based on content type.
     */
    /**
     * Sanitize HTTP request body with real security validation.
     */
    fun sanitizeRequestBody(body: String, contentType: String): String
    {
        if (body.isEmpty()) return body
        
        var sanitized = body
        
        when
        {
            contentType.contains("application/json") ->
            {
                // JSON sanitization
                sanitized = sanitizeJsonBody(sanitized)
            }
            contentType.contains("application/x-www-form-urlencoded") ->
            {
                // Form data sanitization
                sanitized = sanitizeFormData(sanitized)
            }
            contentType.contains("text/") ->
            {
                // Text content sanitization
                sanitized = sanitizeTextContent(sanitized)
            }
        }
        
        // Apply general sanitization
        sanitized = applyGeneralSanitization(sanitized)
        
        return sanitized
    }
    
    /**
     * Sanitize JSON request body.
     */
    private fun sanitizeJsonBody(json: String): String
    {
        var sanitized = json
        
        // Remove control characters that could break JSON parsing
        sanitized = sanitized.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
        
        // Prevent JSON injection by escaping dangerous patterns
        sanitized = sanitized.replace("</script>", "<\\/script>")
        sanitized = sanitized.replace("javascript:", "javascript\\u003a")
        
        // Limit JSON depth to prevent parser attacks
        if (countJsonDepth(sanitized) > MAX_JSON_DEPTH)
        {
            throw SecurityException("JSON depth exceeds maximum allowed: $MAX_JSON_DEPTH")
        }
        
        return sanitized
    }
    
    /**
     * Sanitize form data.
     */
    private fun sanitizeFormData(formData: String): String
    {
        var sanitized = formData
        
        // Remove dangerous characters
        sanitized = sanitized.replace(Regex("[<>\"'&=]"), "")
        
        // Limit length
        if (sanitized.length > MAX_TEXT_LENGTH)
        {
            sanitized = sanitized.substring(0, MAX_TEXT_LENGTH)
        }
        
        return sanitized
    }
    
    /**
     * Sanitize text content.
     */
    private fun sanitizeTextContent(text: String): String
    {
        var sanitized = text
        
        // Remove control characters
        sanitized = sanitized.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
        
        // Limit length to prevent DoS
        if (sanitized.length > MAX_TEXT_LENGTH)
        {
            sanitized = sanitized.substring(0, MAX_TEXT_LENGTH)
        }
        
        return sanitized
    }
    
    /**
     * Apply general sanitization rules.
     */
    private fun applyGeneralSanitization(content: String): String
    {
        var sanitized = content
        
        // Remove null bytes
        sanitized = sanitized.replace("\u0000", "")
        
        // Limit total size
        if (sanitized.length > MAX_BODY_SIZE)
        {
            throw SecurityException("Request body size exceeds maximum: $MAX_BODY_SIZE bytes")
        }
        
        return sanitized
    }
    
    /**
     * Count JSON nesting depth to prevent parser attacks.
     */
    private fun countJsonDepth(json: String): Int
    {
        var depth = 0
        var maxDepth = 0
        
        for (char in json)
        {
            when (char)
            {
                '{', '[' ->
                {
                    depth++
                    maxDepth = maxOf(maxDepth, depth)
                }
                '}', ']' ->
                {
                    depth--
                }
            }
        }
        
        return maxDepth
    }
    
    companion object
    {
        private const val MAX_JSON_DEPTH = 20
        private const val MAX_TEXT_LENGTH = 1048576 // 1MB
        private const val MAX_BODY_SIZE = 10485760 // 10MB
    }
    
    /**
     * Generate authentication headers based on type and credentials.
     * 
     * Handles the three main HTTP authentication methods:
     * - BASIC: Base64 encodes username:password for Authorization header
     * - BEARER: Adds token to Authorization header with Bearer prefix  
     * - API_KEY: Adds key to custom header (default X-API-Key)
     * 
     * @param authType The authentication type (BASIC, BEARER, API_KEY, or empty for none)
     * @param credentials Map containing auth data (username/password, token, or key)
     * @return Map of headers to add to the HTTP request
     */
    fun generateAuthHeaders(authType: String, credentials: Map<String, String>): Map<String, String>
    {
        val headers = mutableMapOf<String, String>()
        
        when (authType.uppercase())
        {
            "BASIC" ->
            {
                val username = credentials["username"] ?: ""
                val password = credentials["password"] ?: ""
                val encodedCredentials = java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray())
                headers["Authorization"] = "Basic $encodedCredentials"
            }
            
            "BEARER" ->
            {
                val token = credentials["token"] ?: ""
                headers["Authorization"] = "Bearer $token"
            }
            
            "API_KEY" ->
            {
                val key = credentials["key"] ?: ""
                val headerName = credentials["header"] ?: "X-API-Key"
                headers[headerName] = key
            }
        }
        
        return headers
    }

    /**
     * Check if IP address is in private network range.
     */
    private fun isPrivateNetwork(ip: String): Boolean
    {
        // Check if it's a localhost variation
        if (ip.lowercase() in listOf("localhost", "127.0.0.1", "::1", "0.0.0.0")) return true

        try
        {
            val address = InetAddress.getByName(ip)

            // Use built-in InetAddress checks
            if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress)
            {
                return true
            }

            // Manual range checks for more comprehensive coverage
            val bytes = address.address

            // IPv4 Checks
            if (bytes.size == 4)
            {
                val b1 = bytes[0].toInt() and 0xFF
                val b2 = bytes[1].toInt() and 0xFF

                return when
                {
                    b1 == 10 -> true // 10.0.0.0/8
                    b1 == 172 && b2 in 16..31 -> true // 172.16.0.0/12
                    b1 == 192 && b2 == 168 -> true // 192.168.0.0/16
                    b1 == 169 && b2 == 254 -> true // 169.254.0.0/16 (Link-local)
                    b1 == 127 -> true // 127.0.0.0/8 (Loopback)
                    b1 == 0 -> true // 0.0.0.0/8
                    else -> false
                }
            }
            // IPv6 Checks
            else if (bytes.size == 16)
            {
                val b1 = bytes[0].toInt() and 0xFF

                return when
                {
                    b1 == 0xFC || b1 == 0xFD -> true // fc00::/7 (Unique Local Address)
                    b1 == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80 -> true // fe80::/10 (Link-Local)
                    else -> false
                }
            }
        }
        catch (e: Exception)
        {
            return true // Fail-closed on parse error
        }

        return false
    }
    
    /**
     * Validate authentication configuration.
     */
    fun validateAuthentication(authType: String, credentials: Map<String, String>): HttpValidationResult
    {
        val errors = mutableListOf<String>()
        
        when (authType.lowercase())
        {
            "bearer" ->
            {
                val token = credentials["token"]
                if (token.isNullOrBlank())
                {
                    errors.add("Bearer token is required")
                }
                else if (token.length < 10)
                {
                    errors.add("Bearer token appears too short")
                }
            }
            
            "basic" ->
            {
                val username = credentials["username"]
                val password = credentials["password"]
                if (username.isNullOrBlank() || password.isNullOrBlank())
                {
                    errors.add("Username and password are required for basic auth")
                }
            }
            
            "apikey" ->
            {
                val key = credentials["key"]
                if (key.isNullOrBlank())
                {
                    errors.add("API key is required")
                }
            }
            
            "" -> {} // No auth is valid
            
            else ->
            {
                errors.add("Unsupported authentication type: $authType")
            }
        }
        
        return HttpValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }
}
