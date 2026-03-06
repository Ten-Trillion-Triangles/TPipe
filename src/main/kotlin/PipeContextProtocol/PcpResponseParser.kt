package com.TTT.PipeContextProtocol

import com.TTT.Util.extractJson
import kotlinx.serialization.Serializable

/**
 * Result of parsing LLM response for PCP requests.
 * Contains extracted requests, validation status, and error information.
 */
@Serializable
data class PcpParseResult(
    val success: Boolean,
    val requests: List<PcPRequest>,
    val errors: List<String>,
    val originalResponse: String
)

/**
 * Result of validating a single PCP request.
 */
@Serializable
data class PcpValidationResult(
    val isValid: Boolean,
    val errors: List<String>
)

/**
 * Parses LLM responses to extract and validate PCP requests.
 * Handles malformed JSON, multiple requests, and validation errors using TPipe's existing JSON utilities.
 */
class PcpResponseParser
{
    /**
     * Extract PCP requests from LLM response text.
     * Uses TPipe's extractJson utility which handles malformed JSON and repair automatically.
     * 
     * @param llmResponse The raw response text from the LLM
     * @return PcpParseResult containing extracted requests and validation status
     */
    fun extractPcpRequests(llmResponse: String): PcpParseResult
    {
        val errors = mutableListOf<String>()
        val requests = mutableListOf<PcPRequest>()
        
        try
        {
            // Try to extract single PCP request first using TPipe's extractJson
            val singleRequest = extractJson<PcPRequest>(llmResponse)
            if(singleRequest != null)
            {
                val validation = validatePcpRequest(singleRequest)
                if(validation.isValid)
                {
                    requests.add(singleRequest)
                }
                else
                {
                    errors.addAll(validation.errors)
                }
            }
            else
            {
                // Try to extract array of PCP requests
                val multipleRequests = extractJson<List<PcPRequest>>(llmResponse)
                if(multipleRequests != null)
                {
                    for(request in multipleRequests)
                    {
                        val validation = validatePcpRequest(request)
                        if(validation.isValid)
                        {
                            requests.add(request)
                        }
                        else
                        {
                            errors.addAll(validation.errors)
                        }
                    }
                }
                else
                {
                    errors.add("No valid PCP requests found in response")
                }
            }
        }
        catch(e: Exception)
        {
            errors.add("Failed to parse PCP requests: ${e.message}")
        }
        
        return PcpParseResult(
            success = requests.isNotEmpty() && errors.isEmpty(),
            requests = requests,
            errors = errors,
            originalResponse = llmResponse
        )
    }
    
    /**
     * Validate a PCP request for completeness and correctness.
     * Checks required fields and transport-specific requirements.
     * 
     * @param request The PCP request to validate
     * @return ValidationResult indicating if request is valid
     */
    fun validatePcpRequest(request: PcPRequest): PcpValidationResult
    {
        val errors = mutableListOf<String>()
        
        // Determine transport based on populated context options
        val transport = determineTransport(request)
        if(transport == Transport.Unknown)
        {
            errors.add("No valid transport context found - at least one context option must be populated")
        }
        
        // Validate transport-specific requirements
        when(transport)
        {
            Transport.Tpipe ->
            {
                if(request.tPipeContextOptions.functionName.isEmpty())
                {
                    errors.add("Function name is required for native function transport")
                }
            }
            
            Transport.Stdio ->
            {
                if(request.stdioContextOptions.command.isEmpty())
                {
                    errors.add("Command is required for stdio transport")
                }
            }
            
            Transport.Http ->
            {
                if(request.httpContextOptions.baseUrl.isEmpty())
                {
                    errors.add("Base URL is required for HTTP transport")
                }
            }
            
            Transport.Python ->
            {
                if(request.argumentsOrFunctionParams.isEmpty())
                {
                    errors.add("Python script is required for Python transport")
                }
            }

            Transport.Kotlin ->
            {
                if(request.argumentsOrFunctionParams.isEmpty())
                {
                    errors.add("Kotlin script is required for Kotlin transport")
                }
            }

            Transport.JavaScript ->
            {
                if(request.argumentsOrFunctionParams.isEmpty())
                {
                    errors.add("JavaScript script is required for JavaScript transport")
                }
            }
            
            else -> {}
        }
        
        return PcpValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }
    
    /**
     * Determine transport type based on which context options are populated.
     * 
     * @param request The PCP request to analyze
     * @return Transport type based on populated context options
     */
    fun determineTransport(request: PcPRequest): Transport
    {
        val transport = when
        {
            request.tPipeContextOptions.functionName.isNotEmpty() -> Transport.Tpipe
            request.stdioContextOptions.command.isNotEmpty() -> Transport.Stdio
            request.httpContextOptions.baseUrl.isNotEmpty() -> Transport.Http
            request.pythonContextOptions.pythonPath.isNotEmpty() -> Transport.Python
            request.kotlinContextOptions.cinit -> Transport.Kotlin
            request.javascriptContextOptions.cinit -> Transport.JavaScript

            // Fallback heuristics based on argumentsOrFunctionParams if no explicit context is set
            request.argumentsOrFunctionParams.isNotEmpty() -> {
                val script = request.argumentsOrFunctionParams.first()
                when {
                    // Check for Python specific patterns (Try first as it's the most common)
                    (Regex("(?m)^\\s*(import|from|def|class)\\s+").containsMatchIn(script) ||
                    script.contains("print(")) -> Transport.Python

                    // Check for Kotlin specific patterns (more likely to be at start or start of line)
                    (Regex("(?m)^\\s*(import|val|var|fun|package)\\s+").containsMatchIn(script) ||
                    script.contains("println(")) -> Transport.Kotlin

                    // Check for JavaScript specific patterns
                    (Regex("(?m)^\\s*(const|let|var|function|import|require)\\s+").containsMatchIn(script) ||
                    script.contains("console.log(")) -> Transport.JavaScript

                    else -> Transport.Unknown
                }
            }
            else -> Transport.Unknown
        }

        return transport
    }
}
