package com.TTT.PipeContextProtocol

import kotlinx.serialization.Serializable

/**
 * Main engine for invoking registered native functions.
 * Handles parameter conversion, validation, invocation, and return value processing
 * with comprehensive error handling and performance tracking.
 */
class FunctionInvoker 
{
    private val typeConverters = FunctionRegistry.getTypeConverters()
    
    /**
     * Invoke a registered function with PCP parameters.
     * Handles all conversion, validation, and error management with timing information.
     * 
     * @param functionName The name of the function to invoke
     * @param parameters Map of parameter names to string values from PCP
     * @return InvocationResult containing success status, return value, and metadata
     */
    suspend fun invoke(functionName: String, parameters: Map<String, String>): InvocationResult 
    {
        val startTime = System.currentTimeMillis()
        
        return try 
        {
            // Lookup function in registry
            val nativeFunction = FunctionRegistry.getFunction(functionName)
                ?: return InvocationResult(
                    success = false,
                    returnValue = null,
                    returnValueAsString = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    error = "Function '$functionName' not found in registry"
                )
            
            // Validate parameters against signature
            val validationResult = validateParameters(nativeFunction.signature, parameters)
            if (!validationResult.isValid) 
            {
                return InvocationResult(
                    success = false,
                    returnValue = null,
                    returnValueAsString = "",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    error = "Parameter validation failed: ${validationResult.errors.joinToString(", ")}"
                )
            }
            
            // Convert parameters to native types
            val convertedParams = convertParameters(nativeFunction.signature, parameters)
            
            // Invoke the function
            val returnValue = nativeFunction.invoke(convertedParams)
            
            // Convert return value back to string
            val returnValueString = convertReturnValue(returnValue, nativeFunction.signature.returnType)
            
            InvocationResult(
                success = true,
                returnValue = returnValue,
                returnValueAsString = returnValueString,
                executionTimeMs = System.currentTimeMillis() - startTime,
                error = null
            )
        } 
        catch (e: Exception) 
        {
            InvocationResult(
                success = false,
                returnValue = null,
                returnValueAsString = "",
                executionTimeMs = System.currentTimeMillis() - startTime,
                error = "Function invocation failed: ${e.message}"
            )
        }
    }
    
    /**
     * Validate parameters against function signature before invocation.
     * Checks parameter presence, type compatibility, and required parameter validation.
     * 
     * @param signature The function signature to validate against
     * @param parameters The parameters to validate
     * @return ValidationResult indicating success or failure with error details
     */
    fun validateParameters(signature: FunctionSignature, parameters: Map<String, String>): ValidationResult 
    {
        val errors = mutableListOf<String>()
        
        // Check required parameters are present
        signature.parameters.forEach { paramInfo ->
            if (!paramInfo.isOptional && !parameters.containsKey(paramInfo.name)) 
            {
                errors.add("Required parameter '${paramInfo.name}' is missing")
            }
        }
        
        // Check parameter types can be converted
        parameters.forEach { (paramName, paramValue) ->
            val paramInfo = signature.parameters.find { it.name == paramName }
            if (paramInfo != null) 
            {
                val canConvert = typeConverters.any { converter ->
                    converter.canConvert(paramInfo.type, paramInfo.kotlinType)
                }
                if (!canConvert) 
                {
                    errors.add("Cannot convert parameter '$paramName' to type '${paramInfo.kotlinType}'")
                }
            }
        }
        
        return ValidationResult(errors.isEmpty(), errors)
    }
    
    /**
     * Convert PCP parameters to native types based on function signature.
     * Uses registered type converters to handle conversion with error handling.
     * 
     * @param signature The function signature containing parameter type information
     * @param parameters The string parameters from PCP to convert
     * @return Map of parameter names to converted native values
     */
    private fun convertParameters(signature: FunctionSignature, parameters: Map<String, String>): Map<String, Any?> 
    {
        val convertedParams = mutableMapOf<String, Any?>()
        
        signature.parameters.forEach { paramInfo ->
            val paramValue = parameters[paramInfo.name]

            if (paramValue != null)
            {
                val converter = typeConverters.find { it.canConvert(paramInfo.type, paramInfo.kotlinType) }
                    ?: throw IllegalArgumentException(
                        "No converter found for parameter '${paramInfo.name}' of type '${paramInfo.kotlinType}'"
                    )

                convertedParams[paramInfo.name] = converter.convert(paramValue, paramInfo.kotlinType)
            }
            // Missing optional parameters are intentionally omitted so that Kotlin callBy can apply defaults.
        }
        
        return convertedParams
    }
    
    /**
     * Convert return value back to PCP string format.
     * Uses type converters to handle return value serialization.
     * 
     * @param returnValue The native return value from function invocation
     * @param returnTypeInfo The return type information from function signature
     * @return String representation of return value suitable for PCP
     */
    private fun convertReturnValue(returnValue: Any?, returnTypeInfo: ReturnTypeInfo): String 
    {
        if (returnValue == null) return ""
        
        val converter = typeConverters.find { it.canConvert(returnTypeInfo.type, returnTypeInfo.kotlinType) }
        return converter?.convertBack(returnValue, returnTypeInfo.type) ?: returnValue.toString()
    }
}

/**
 * Result of function invocation including return value and metadata.
 * Contains all information needed to handle function call results in PCP context.
 */
data class InvocationResult(
    val success: Boolean,
    val returnValue: Any? = null,
    val returnValueAsString: String,
    val executionTimeMs: Long,
    val error: String? = null
)

/**
 * Result of parameter validation before function invocation.
 * Contains validation status and detailed error information for debugging.
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>
)
