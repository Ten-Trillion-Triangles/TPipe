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
    suspend fun execute(request: PcPRequest): PcpRequestResult
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
     * Execute a single PCP request.
     */
    suspend fun executeRequest(request: PcPRequest): PcpRequestResult
    {
        return try
        {
            val transport = responseParser.determineTransport(request)
            val executor = routeRequest(transport)
            executor.execute(request)
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
     * Execute multiple PCP requests.
     */
    suspend fun executeRequests(requests: List<PcPRequest>): PcpExecutionResult = coroutineScope {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<PcpRequestResult>()
        val errors = mutableListOf<String>()
        
        try
        {
            val jobs = requests.map { request ->
                async { executeRequest(request) }
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
        
        val executionTime = System.currentTimeMillis() - startTime
        
        PcpExecutionResult(
            success = errors.isEmpty() && results.all { it.success },
            results = results,
            executionTimeMs = executionTime,
            errors = errors
        )
    }
}
