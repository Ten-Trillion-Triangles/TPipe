package com.TTT.PipeContextProtocol

import com.TTT.Config.AuthRegistry
import com.TTT.Util.HttpAuth
import com.TTT.Util.httpRequest
import java.net.URL

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
     * Execute HTTP request with context validation and security enforcement.
     * 
     * @param request The PCP request to execute
     * @param context The security context defining allowed operations
     * @return PcpRequestResult with execution results or validation errors
     */
    override suspend fun execute(request: PcPRequest, context: PcpContext): PcpRequestResult
    {
        val startTime = System.currentTimeMillis()

        val matchingOption = if(context.httpOptions.isNotEmpty())
        {
            findMatchingEndpoint(request.httpContextOptions, context)
                ?: return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    transport = Transport.Http,
                    error = "HTTP endpoint '${request.httpContextOptions.baseUrl}${request.httpContextOptions.endpoint}' not in security whitelist"
                )
        }
        else
        {
            null
        }

        val mergedOptions = mergeContextOptions(request.httpContextOptions, matchingOption)

        // Resolve auth token automatically if not manually set
        if(mergedOptions.authType.isEmpty() || mergedOptions.authType.uppercase() == HttpConstants.AUTH_TYPE_NONE)
        {
            val token = AuthRegistry.getToken(mergedOptions.baseUrl)
            if(token.isNotEmpty())
            {
                mergedOptions.authType = HttpConstants.AUTH_TYPE_BEARER
                mergedOptions.authCredentials["token"] = token
            }
        }

        if(mergedOptions.baseUrl.isEmpty())
        {
            return PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Http,
                error = "Base URL is required for HTTP requests"
            )
        }

        val secureRequest = request.copy(httpContextOptions = mergedOptions)
        return executeSecure(secureRequest)
    }

    /**
     * Find matching HTTP endpoint in context options.
     * Matches by base URL and optional method/path constraints.
     */
    private fun findMatchingEndpoint(requestOptions: HttpContextOptions, context: PcpContext): HttpContextOptions?
    {
        val requestDetails = parseUrl(requestOptions.baseUrl) ?: return null
        val requestMethod = requestOptions.method.ifBlank { "GET" }.uppercase()
        val requestPath = buildNormalizedPath(requestDetails.path, requestOptions.endpoint)

        return context.httpOptions.firstOrNull { contextOption ->
            val contextDetails = parseUrl(contextOption.baseUrl) ?: return@firstOrNull false

            if(!hostsMatch(requestDetails, contextDetails)) return@firstOrNull false
            if(!schemesMatch(requestDetails, contextDetails)) return@firstOrNull false

            val contextMethod = contextOption.method.takeIf { it.isNotBlank() }?.uppercase()
            if(contextMethod != null && contextMethod != requestMethod) return@firstOrNull false

            val allowedPath = buildNormalizedPath(contextDetails.path, contextOption.endpoint)
            if(allowedPath != "/" && !requestPath.startsWith(allowedPath)) return@firstOrNull false

            true
        }
    }
    
    /**
     * Merge context HTTP options with request options.
     * Context options take precedence for security settings.
     */
    private fun mergeContextOptions(requestOptions: HttpContextOptions, contextOptions: HttpContextOptions?): HttpContextOptions
    {
        val contextOption = contextOptions

        return HttpContextOptions().apply {
            baseUrl = when {
                contextOption?.baseUrl?.isNotEmpty() == true -> contextOption.baseUrl
                else -> requestOptions.baseUrl
            }

            val resolvedMethodRaw = when {
                contextOption?.method?.isNotEmpty() == true -> contextOption.method
                requestOptions.method.isNotEmpty() -> requestOptions.method
                else -> "GET"
            }
            method = resolvedMethodRaw.uppercase()
            val resolvedMethod = method

            endpoint = if(contextOption?.endpoint?.isNotEmpty() == true) {
                contextOption.endpoint
            }
            else
            {
                requestOptions.endpoint
            }

            headers.putAll(requestOptions.headers)
            contextOption?.headers?.let { headers.putAll(it) }

            timeoutMs = when {
                contextOption?.timeoutMs ?: 0 > 0 -> contextOption!!.timeoutMs
                requestOptions.timeoutMs > 0 -> requestOptions.timeoutMs
                else -> timeoutMs
            }

            permissions.addAll(contextOption?.permissions ?: emptyList())
            if(permissions.isEmpty())
            {
                permissions.addAll(requestOptions.permissions)
            }

            requestBody = requestOptions.requestBody

            allowedMethods.addAll((contextOption?.allowedMethods ?: emptyList()).map { it.uppercase() })
            if(allowedMethods.isEmpty())
            {
                allowedMethods.addAll(requestOptions.allowedMethods.map { it.uppercase() })
            }
            if(allowedMethods.isEmpty())
            {
                allowedMethods.add(resolvedMethod)
            }
            val normalizedMethods = mutableSetOf<String>()
            normalizedMethods.addAll(allowedMethods)
            allowedMethods.clear()
            allowedMethods.addAll(normalizedMethods)

            authType = if(contextOption?.authType?.isNotEmpty() == true)
            {
                contextOption.authType
            }
            else
            {
                requestOptions.authType
            }
            authCredentials.putAll(requestOptions.authCredentials)
            contextOption?.authCredentials?.let { authCredentials.putAll(it) }

            allowedHosts.addAll(contextOption?.allowedHosts ?: emptyList())
            if(allowedHosts.isEmpty())
            {
                allowedHosts.addAll(requestOptions.allowedHosts)
            }
            if(allowedHosts.isEmpty() && baseUrl.isNotEmpty())
            {
                parseUrl(baseUrl)?.let { addAllowedHostVariants(allowedHosts, it) }
            }
            normaliseAndDeduplicate(allowedHosts)

            followRedirects = contextOption?.followRedirects ?: requestOptions.followRedirects

            description = if(contextOption?.description?.isNotEmpty() == true)
            {
                contextOption.description
            }
            else
            {
                requestOptions.description
            }
        }
    }


    /**
     * Execute HTTP request with merged security options.
     */
    private suspend fun executeSecure(request: PcPRequest): PcpRequestResult
    {
        val startTime = System.currentTimeMillis()
        val options = request.httpContextOptions

        val validation = securityManager.validateHttpRequest(options)
        if(!validation.isValid)
        {
            return PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Http,
                error = "HTTP security validation failed: ${validation.errors.joinToString("; " )}"
            )
        }

        val trimmedEndpoint = options.endpoint.trim()

        // SSRF PROTECTION: Use validated IP address for the request to prevent DNS rebinding
        val originalUrl = try { URL(options.baseUrl) } catch(e: Exception) { null }
        val requestUrl = if(validation.validatedIp != null && originalUrl != null)
        {
            // Reconstruct URL with IP address but keep original port/path/query/fragment
            val protocol = originalUrl.protocol
            val port = if(originalUrl.port != -1) ":${originalUrl.port}" else ""
            val ipAddress = if(validation.validatedIp!!.contains(":")) "[${validation.validatedIp}]" else validation.validatedIp
            val path = originalUrl.path
            val query = if(originalUrl.query != null) "?${originalUrl.query}" else ""
            val fragment = if(originalUrl.ref != null) "#${originalUrl.ref}" else ""
            "$protocol://$ipAddress$port$path$query$fragment"
        }
        else
        {
            options.baseUrl
        }

        val fullUrl = buildString {
            append(requestUrl)
            if(trimmedEndpoint.isNotEmpty())
            {
                if(!requestUrl.endsWith("/") && !trimmedEndpoint.startsWith("/"))
                {
                    append('/')
                }
                append(trimmedEndpoint)
            }
        }

        val contentTypeHeader = options.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: "application/json"

        val headers = mutableMapOf<String, String>()
        options.headers.forEach { (key, value) ->
            headers[key] = value
        }

        // SSRF PROTECTION: Manually set Host header to original hostname
        if(validation.validatedIp != null && originalUrl != null)
        {
            headers["Host"] = originalUrl.host
        }

        val authType = options.authType.uppercase()
        val httpAuth = if(authType.isNotEmpty() && authType != HttpConstants.AUTH_TYPE_NONE)
        {
            headers.entries.removeIf { it.key.equals("Authorization", ignoreCase = true) }
            HttpAuth(authType, options.authCredentials.toMap())
        }
        else
        {
            HttpAuth()
        }

        return try
        {
            val sanitizedBody = if(options.requestBody.isNotEmpty())
            {
                securityManager.sanitizeRequestBody(options.requestBody, contentTypeHeader)
            }
            else
            {
                ""
            }

            val response = httpRequest(
                url = fullUrl,
                method = options.method,
                body = sanitizedBody,
                headers = headers,
                auth = httpAuth,
                timeoutMs = options.timeoutMs.toLong(),
                followRedirects = options.followRedirects
            )

            val responseOutput = formatHttpResponse(response)
            val output = if(validation.warnings.isNotEmpty())
            {
                buildString {
                    append("Warnings:\n")
                    validation.warnings.forEach { append("- ").append(it).append('\n') }
                    append('\n')
                    append(responseOutput)
                }.trimEnd()
            }
            else
            {
                responseOutput
            }

            val error = when
            {
                response.success -> null
                response.statusCode > 0 -> "HTTP ${response.statusCode} ${response.statusMessage}"
                response.body.isNotEmpty() -> response.body
                else -> response.statusMessage
            }

            PcpRequestResult(
                success = response.success,
                output = output,
                executionTimeMs = response.responseTimeMs,
                transport = Transport.Http,
                error = error
            )
        }
        catch(e: Exception)
        {
            PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                transport = Transport.Http,
                error = "HTTP request failed: ${e.message}"
            )
        }
    }


    /**
     * Check if two URLs match for endpoint validation.
     */
    private fun parseUrl(value: String): ParsedUrl?
    {
        if(value.isBlank()) return null

        return try
        {
            val url = URL(value)
            ParsedUrl(
                original = value,
                scheme = url.protocol.lowercase(),
                host = url.host.lowercase(),
                port = if(url.port == -1) url.defaultPort else url.port,
                path = url.path.ifEmpty { "/" }
            )
        }
        catch(_: Exception)
        {
            null
        }
    }

    private fun hostsMatch(request: ParsedUrl, context: ParsedUrl): Boolean
    {
        if(!request.host.equals(context.host, ignoreCase = true)) return false
        return request.port == context.port
    }

    private fun schemesMatch(request: ParsedUrl, context: ParsedUrl): Boolean
    {
        // If context specifies a scheme it must match, otherwise allow anything
        return context.scheme.isBlank() || request.scheme.equals(context.scheme, ignoreCase = true)
    }

    private fun buildNormalizedPath(basePath: String, endpoint: String): String
    {
        val base = normalizePathComponent(basePath)
        val addition = normalizePathComponent(endpoint)

        val combined = when
        {
            base.isEmpty() && addition.isEmpty() -> ""
            base.isEmpty() -> addition
            addition.isEmpty() -> base
            else -> "$base/$addition"
        }

        return if(combined.isEmpty()) "/" else "/" + combined.trim('/').replace(Regex("/+"), "/")
    }

    private fun normalizePathComponent(raw: String): String
    {
        if(raw.isBlank()) return ""

        val withoutQuery = raw.substringBefore('?').substringBefore('#')
        val sanitizedSeparators = withoutQuery.replace('\\', '/')
        val trimmed = sanitizedSeparators.trim()

        return trimmed.trim('/').replace(Regex("/+"), "/")
    }

    private fun addAllowedHostVariants(target: MutableList<String>, url: ParsedUrl)
    {
        val baseHost = url.host
        val hostWithPort = if(url.port == url.defaultPortForScheme()) baseHost else "$baseHost:${url.port}"

        if(target.none { it.equals(baseHost, true) })
        {
            target.add(baseHost)
        }

        if(hostWithPort != baseHost && target.none { it.equals(hostWithPort, true) })
        {
            target.add(hostWithPort)
        }
    }

    private fun normaliseAndDeduplicate(values: MutableList<String>)
    {
        val normalised = values.mapNotNull { it.trim().lowercase().takeIf { trimmed -> trimmed.isNotEmpty() } }
        val deduped = LinkedHashSet<String>()
        normalised.forEach { deduped.add(it) }
        values.clear()
        values.addAll(deduped)
    }

    private fun ParsedUrl.defaultPortForScheme(): Int
    {
        return when(scheme.lowercase())
        {
            "http" -> 80
            "https" -> 443
            else -> port
        }
    }

    private data class ParsedUrl(
        val original: String,
        val scheme: String,
        val host: String,
        val port: Int,
        val path: String
    )

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
        
        if(response.headers.isNotEmpty())
        {
            output.appendLine()
        }
        
        // Add response body
        if(response.body.isNotEmpty())
        {
            output.appendLine(response.body)
        }
        
        return output.toString()
    }
}
