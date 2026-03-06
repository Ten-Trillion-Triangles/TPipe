package com.TTT.Util

import com.TTT.PipeContextProtocol.HttpConstants
import com.TTT.PipeContextProtocol.HttpSecurityManager
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Enhanced HTTP response with metadata.
 */
@Serializable
data class HttpResponseData(
    val statusCode: Int,
    val statusMessage: String,
    val headers: Map<String, String>,
    val body: String,
    val responseTimeMs: Long,
    val success: Boolean = statusCode in HttpConstants.SUCCESS_STATUS_RANGE
)

/**
 * HTTP authentication configuration.
 */
data class HttpAuth(
    val type: String = "NONE", // NONE, BASIC, BEARER, API_KEY
    val credentials: Map<String, String> = emptyMap()
)

/**
 * Enhanced HTTP request with full configuration support.
 */
suspend fun httpRequest(
    url: String,
    method: String = "GET",
    body: String = "",
    headers: Map<String, String> = emptyMap(),
    auth: HttpAuth = HttpAuth(),
    timeoutMs: Long = 30000,
    followRedirects: Boolean = true
): HttpResponseData
{
    val client = HttpClient(CIO) 
    {
        install(HttpTimeout) 
        {
            requestTimeoutMillis = timeoutMs
        }
        this.followRedirects = followRedirects

        engine {
            https {
                // If we have a Host header, use it for SNI even if we connect to an IP
                val hostHeader = headers.entries.find { it.key.equals("Host", ignoreCase = true) }?.value
                if(hostHeader != null)
                {
                    serverName = hostHeader.substringBefore(':')
                }
            }
        }
    }
    
    val startTime = System.currentTimeMillis()
    
    return try 
    {
        val response: HttpResponse = client.request(url) 
        {
            this.method = HttpMethod.parse(method.uppercase())
            
            // Set headers
            headers.forEach { (name, value) ->
                header(name, value)
            }
            
            // Handle authentication using HttpSecurityManager
            if(auth.type.isNotEmpty() && auth.type.uppercase() != HttpConstants.AUTH_TYPE_NONE)
            {
                val securityManager = HttpSecurityManager()
                val authHeaders = securityManager.generateAuthHeaders(auth.type, auth.credentials)
                authHeaders.forEach { (name, value) ->
                    header(name, value)
                }
            }
            
            // Set body for methods that support it
            if(body.isNotEmpty() && method.uppercase() in HttpConstants.BODY_METHODS)
            {
                setBody(body)
                if(!headers.containsKey("Content-Type"))
                {
                    contentType(ContentType.Application.Json)
                }
            }
        }
        
        val responseTime = System.currentTimeMillis() - startTime
        val responseBody = response.bodyAsText()
        
        // Convert headers to map
        val responseHeaders = mutableMapOf<String, String>()
        response.headers.forEach { name, values ->
            responseHeaders[name] = values.joinToString(", ")
        }
        
        HttpResponseData(
            statusCode = response.status.value,
            statusMessage = response.status.description,
            headers = responseHeaders,
            body = responseBody,
            responseTimeMs = responseTime
        )
    }
    catch(e: Exception)
    {
        val responseTime = System.currentTimeMillis() - startTime
        HttpResponseData(
            statusCode = 0,
            statusMessage = "Request Failed",
            headers = emptyMap(),
            body = "Error: ${e.message}",
            responseTimeMs = responseTime
        )
    } finally {
        client.close()
    }
}

/**
 * Sends an HTTP GET request to the specified URL.
 *
 * @param url The URL to which the GET request is sent.
 * @param acceptType The value of the Accept header indicating the type of content that can be sent back.
 *                   Defaults to "any content".
 * @param authToken Optional authorization token to include in the request as a Bearer token.
 * @return The response body as a text string, or an error message in case of an IOException.
 */
suspend fun httpGet(url: String, acceptType: String = "*/*", authToken: String? = null): String
{
    val client = HttpClient()
    return try {
        val response: HttpResponse = client.get(url) {
            header(HttpHeaders.Accept, acceptType)
            authToken?.let {
                header(HttpHeaders.Authorization, "Bearer $it")
            }
        }
        response.bodyAsText()
    }
    catch(e: IOException)
    {
        "Error: ${e.message}"
    } finally {
        client.close()
    }
}



/**
 * Sends an HTTP PUT request to the specified URL.
 *
 * @param url The URL to which the PUT request is sent.
 * @param body The content of the request body.
 * @param acceptType The value of the Accept header indicating the type of content that can be sent back.
 *                   Defaults to "any content".
 * @param authToken Optional authorization token to include in the request as a Bearer token.
 * @return The response body as a text string, or an error message in case of an IOException.
 */
suspend fun httpPut(url: String, body: String, acceptType: String = "*/*", authToken: String? = null): String
{

    val client = HttpClient()

    return try {
        val response: HttpResponse = client.put(url) {
            setBody(body) // Set the request body
            contentType(ContentType.Application.Json) // Set content type, adjust if needed
            header(HttpHeaders.Accept, acceptType)
            authToken?.let {
                header(HttpHeaders.Authorization, "Bearer $it")
            }
        }
        response.bodyAsText()
    }
    catch(e: IOException)
    {
        "Error: ${e.message}"
    } finally {
        client.close()
    }
}


/**
 * Sends an HTTP POST request to the specified URL.
 *
 * @param url The URL to which the POST request is sent.
 * @param body The content of the request body.
 * @param acceptType The value of the Accept header indicating the type of content that can be sent back.
 *                   Defaults to "any content".
 * @param authToken Optional authorization token to include in the request as a Bearer token.
 * @return The response body as a text string, or an error message in case of an IOException.
 */
suspend fun httpPost(url: String, body: String, acceptType: String = "*/*", authToken: String? = null): String
{
    val client = HttpClient()

    return try {
        val response: HttpResponse = client.post(url) {
            setBody(body) // Set the request body
            contentType(ContentType.Application.Json) // Set content type, adjust if needed
            header(HttpHeaders.Accept, acceptType)
            authToken?.let {
                header(HttpHeaders.Authorization, "Bearer $it")
            }
        }
        response.bodyAsText()
    }
    catch(e: IOException)
    {
        "Error: ${e.message}"
    } finally {
        client.close()
    }
}



/**
 * Sends an HTTP DELETE request to the specified URL.
 *
 * @param url The URL to which the DELETE request is sent.
 * @param body Optional content of the request body.
 * @param authToken Optional authorization token to include in the request as a Bearer token.
 * @return The response body as a text string, or an error message in case of an IOException.
 */
suspend fun httpDelete(url: String, body: String = "", authToken: String? = null): String
{
    val client = HttpClient()

    return try {
        val response: HttpResponse = if(body.isEmpty()) {
            client.delete(url) {
                authToken?.let {
                    header(HttpHeaders.Authorization, "Bearer $it")
                }
            }
        }
        else
        {
            client.delete(url) {
                setBody(body) // Set the request body
                contentType(ContentType.Application.Json) // Set content type, adjust if needed
                authToken?.let {
                    header(HttpHeaders.Authorization, "Bearer $it")
                }
            }
        }
        response.bodyAsText()
    }
    catch(e: IOException)
    {
        "Error: ${e.message}"
    } finally {
        client.close()
    }
}
