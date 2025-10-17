package com.TTT.PipeContextProtocol

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/**
 * Result of executing a single PCP request.
 */
@Serializable
data class PcpRequestResult(
    val success: Boolean,
    val output: String,
    val executionTimeMs: Long,
    val transport: Transport,
    val error: String? = null
)

/**
 * Result of executing multiple PCP requests.
 */
@Serializable
data class PcpExecutionResult(
    val success: Boolean,
    val results: List<PcpRequestResult>,
    val executionTimeMs: Long,
    val errors: List<String>
)

/**
 * Interface for PCP executors.
 */
interface PcpExecutor
{
    suspend fun execute(request: PcPRequest, context: PcpContext): PcpRequestResult
}

/**
 * Central dispatcher that routes PCP requests to appropriate executors.
 */
class PcpExecutionDispatcher
{
    private val functionHandler = PcpFunctionHandler()
    private val stdioExecutor = StdioExecutor()
    private val httpExecutor = HttpExecutor()
    private val pythonExecutor = PythonExecutor()
    private val responseParser = PcpResponseParser()
    
    /**
     * Route request to appropriate executor.
     */
    private fun routeRequest(transport: Transport): PcpExecutor
    {
        return when (transport)
        {
            Transport.Tpipe -> functionHandler
            Transport.Stdio -> stdioExecutor
            Transport.Http -> httpExecutor
            Transport.Python -> pythonExecutor
            else -> throw IllegalArgumentException("Unsupported transport type: $transport")
        }
    }
    
    /**
     * Execute a single PCP request with context validation.
     */
    suspend fun executeRequest(request: PcPRequest, context: PcpContext): PcpRequestResult
    {
        return try
        {
            val requestTransport = responseParser.determineTransport(request)
            
            // Validate transport against context restrictions
            val transportValidation = validateTransport(requestTransport, context)
            if (!transportValidation.isValid)
            {
                return PcpRequestResult(
                    success = false,
                    output = "",
                    executionTimeMs = 0,
                    transport = requestTransport,
                    error = transportValidation.error
                )
            }
            
            val executor = routeRequest(requestTransport)
            executor.execute(request, context)
        }
        catch (e: Exception)
        {
            val transport = responseParser.determineTransport(request)
            PcpRequestResult(
                success = false,
                output = "",
                executionTimeMs = 0,
                transport = transport,
                error = "Failed to execute request: ${e.message}"
            )
        }
    }
    
    /**
     * Validate request transport against context restrictions.
     */
    private fun validateTransport(requestTransport: Transport, context: PcpContext): TransportValidationResult
    {
        // Auto transport allows any request type
        if (context.transport == Transport.Auto)
        {
            return TransportValidationResult(true, null)
        }
        
        // Specific transport must match context
        if (context.transport != requestTransport)
        {
            return TransportValidationResult(
                false, 
                "Transport mismatch: context allows ${context.transport}, request uses $requestTransport"
            )
        }
        
        return TransportValidationResult(true, null)
    }
    
    /**
     * Transport validation result.
     */
    private data class TransportValidationResult(val isValid: Boolean, val error: String?)
    
    /**
     * Execute multiple PCP requests with context validation.
     */
    suspend fun executeRequests(requests: List<PcPRequest>, context: PcpContext): PcpExecutionResult = coroutineScope {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<PcpRequestResult>()
        val errors = mutableListOf<String>()
        
        try
        {
            val jobs = requests.map { request ->
                async { executeRequest(request, context) }
            }
            
            jobs.forEach { job ->
                val result = job.await()
                results.add(result)
                if (!result.success && result.error != null)
                {
                    errors.add(result.error)
                }
            }
        }
        catch (e: Exception)
        {
            errors.add("Execution dispatcher failed: ${e.message}")
        }
        
        return@coroutineScope PcpExecutionResult(
            success = errors.isEmpty(),
            results = results,
            executionTimeMs = System.currentTimeMillis() - startTime,
            errors = errors
        )
    }
}
