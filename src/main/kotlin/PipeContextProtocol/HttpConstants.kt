package com.TTT.PipeContextProtocol

/**
 * Centralized HTTP constants to eliminate duplication across the codebase.
 * Single source of truth for HTTP method classifications and validation.
 */
object HttpConstants
{
    /**
     * HTTP methods that only read data and require Read permission.
     */
    val READ_METHODS = setOf("GET", "HEAD", "OPTIONS")
    
    /**
     * HTTP methods that modify data and require Write permission.
     */
    val WRITE_METHODS = setOf("POST", "PUT", "DELETE", "PATCH")
    
    /**
     * HTTP methods that support request bodies.
     */
    val BODY_METHODS = setOf("POST", "PUT", "PATCH")
    
    /**
     * All supported HTTP methods.
     */
    val ALL_METHODS = READ_METHODS + WRITE_METHODS
    
    /**
     * HTTP status codes that indicate success.
     */
    val SUCCESS_STATUS_RANGE = 200..299
    
    /**
     * Authentication type constants.
     */
    val AUTH_TYPE_NONE = "NONE"
    val AUTH_TYPE_BASIC = "BASIC"
    val AUTH_TYPE_BEARER = "BEARER"
    val AUTH_TYPE_API_KEY = "API_KEY"
}
