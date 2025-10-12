package com.TTT.PipeContextProtocol

/**
 * Handler for PCP requests that involve native function calls.
 * Integrates with existing PCP request processing pipeline to execute
 * bound native functions when requested by LLM.
 */
class PcpFunctionHandler : PcpExecutor 
{
    private val functionInvoker = FunctionInvoker()
    private val returnValueHandler = ReturnValueHandler()
    
    /**
     * Execute PCP request by wrapping existing handleFunctionRequest.
     * Implements PcpExecutor interface for integration with execution dispatcher.
     */
    override suspend fun execute(request: PcPRequest): PcpRequestResult
    {
        val functionResponse = handleFunctionRequest(request)
        
        return PcpRequestResult(
            success = functionResponse.success,
            output = functionResponse.result,
            executionTimeMs = functionResponse.executionTimeMs,
            transport = Transport.Tpipe,
            error = functionResponse.error
        )
    }
    
    /**
     * Process PCP requests containing native function calls.
     * Handles function execution and return value management.
     * 
     * @param request The PCP request from LLM
     * @return PcpFunctionResponse with execution results
     */
    suspend fun handleFunctionRequest(request: PcPRequest): PcpFunctionResponse 
    {
        return try 
        {
            val functionName = request.tPipeContextOptions.functionName
            if (functionName.isEmpty()) 
            {
                return PcpFunctionResponse(
                    success = false,
                    result = "",
                    error = "No function name specified in request"
                )
            }
            
            // Convert arguments to parameter map
            val parameters = convertArgumentsToParameters(functionName, request.argumentsOrFunctionParams)
            
            // Execute the function
            val invocationResult = executeFunction(functionName, parameters)
            
            // Store return value if successful
            var returnKey = ""
            if (invocationResult.success && invocationResult.returnValue != null) 
            {
                returnKey = returnValueHandler.storeReturnValue(value = invocationResult.returnValue)
            }
            
            PcpFunctionResponse(
                success = invocationResult.success,
                result = invocationResult.returnValueAsString,
                returnValueKey = returnKey,
                executionTimeMs = invocationResult.executionTimeMs,
                error = invocationResult.error
            )
        } 
        catch (e: Exception) 
        {
            PcpFunctionResponse(
                success = false,
                result = "",
                error = "Function request handling failed: ${e.message}"
            )
        }
    }
    
    /**
     * Execute native function and return formatted response.
     * Uses FunctionInvoker to handle the actual function execution.
     * 
     * @param functionName The name of the function to execute
     * @param parameters The parameters to pass to the function
     * @return InvocationResult with execution details
     */
    private suspend fun executeFunction(functionName: String, parameters: Map<String, String>): InvocationResult 
    {
        return functionInvoker.invoke(functionName, parameters)
    }
    
    /**
     * Convert PCP argument list to parameter map.
     * Maps positional arguments to named parameters based on function signature.
     * 
     * @param functionName The name of the function to get signature for
     * @param arguments List of argument strings from PCP request
     * @return Map of parameter names to values
     */
    private fun convertArgumentsToParameters(functionName: String, arguments: List<String>): Map<String, String> 
    {
        val parameters = mutableMapOf<String, String>()
        val nativeFunction = FunctionRegistry.getFunction(functionName)
        
        if (nativeFunction != null) 
        {
            // Map arguments to actual parameter names from signature
            nativeFunction.signature.parameters.forEachIndexed { index, paramInfo ->
                if (index < arguments.size) 
                {
                    parameters[paramInfo.name] = arguments[index]
                }
            }
        } 
        else 
        {
            // Fallback to generic parameter names
            arguments.forEachIndexed { index, value ->
                parameters["param$index"] = value
            }
        }
        
        return parameters
    }
    
    /**
     * Get stored return value by key.
     * Allows retrieval of previously stored function return values.
     * 
     * @param key The return value key
     * @return The stored return value or null if not found
     */
    fun getStoredReturnValue(key: String): Any? 
    {
        return returnValueHandler.getReturnValue(key)
    }
    
    /**
     * Clear stored return values.
     * Useful for cleanup between pipeline executions.
     */
    fun clearStoredReturnValues() 
    {
        returnValueHandler.clearAll()
    }
}

/**
 * Response from PCP function execution.
 * Contains all information about function call results for LLM consumption.
 */
data class PcpFunctionResponse(
    val success: Boolean,
    val result: String,
    val returnValueKey: String = "",
    val executionTimeMs: Long = 0,
    val error: String? = null
)