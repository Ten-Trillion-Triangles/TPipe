package com.TTT.Native

import com.TTT.PipeContextProtocol.FunctionInvoker
import com.TTT.PipeContextProtocol.FunctionRegistry
import kotlinx.coroutines.runBlocking

/**
 * Handle representing a TPipe Pipe Context Protocol (PCP) instance.
 *
 * PCP enables secure multi-language tool execution (Kotlin/JS/Python)
 * with strict transport routing, session management, and sandboxed security.
 */
class PCPHandle(
    val functionInvoker: FunctionInvoker = FunctionInvoker()
) {
    /**
     * Execute a registered function with parameters.
     * 
     * @param functionName The name of the function to invoke
     * @param parameters Map of parameter names to string values
     * @return Result containing execution result or error
     */
    fun execute(functionName: String, parameters: Map<String, String>): Result {
        return try {
            val result = runBlocking { functionInvoker.invoke(functionName, parameters) }
            if (result.success) {
                Result.Success(result.returnValueAsString)
            } else {
                Result.Error(result.error ?: "Function invocation failed")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "PCP execution failed")
        }
    }
    
    /**
     * Check if a function is registered.
     * 
     * @param functionName The function name to check
     * @return True if the function exists in the registry
     */
    fun isFunctionRegistered(functionName: String): Boolean {
        return FunctionRegistry.getFunction(functionName) != null
    }
    
    /**
     * Get the list of registered function names.
     */
    fun getRegisteredFunctions(): List<String> {
        return FunctionRegistry.getFunctionNames().toList()
    }
    
    /**
     * Validate parameters for a function without executing.
     * 
     * @param functionName The function name
     * @param parameters The parameters to validate
     * @return Validation result as string
     */
    fun validateParameters(functionName: String, parameters: Map<String, String>): String {
        val nativeFunction = FunctionRegistry.getFunction(functionName) ?: return """{"valid":false,"errors":["Function '$functionName' not found"]}"""
        val validationResult = functionInvoker.validateParameters(nativeFunction.signature, parameters)
        return if (validationResult.isValid) {
            """{"valid":true,"errors":[]}"""
        } else {
            """{"valid":false,"errors":${validationResult.errors.map { "\"$it\"" }}}"""
        }
    }
    
    sealed class Result {
        data class Success(val returnValue: String) : Result()
        data class Error(val message: String) : Result()
    }
}