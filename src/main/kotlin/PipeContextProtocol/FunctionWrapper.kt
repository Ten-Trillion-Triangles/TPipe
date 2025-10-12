package com.TTT.PipeContextProtocol

import kotlin.reflect.KFunction

/**
 * Abstract base class for all native function wrappers.
 * Provides common interface for function invocation regardless of source type,
 * enabling uniform handling of Kotlin functions, Java methods, and lambda expressions.
 */
abstract class NativeFunction 
{
    abstract val signature: FunctionSignature
    
    /**
     * Invoke the wrapped function with converted parameters.
     * Parameters are provided as a map of parameter names to converted values.
     * 
     * @param parameters Map of parameter names to their converted native values
     * @return The function's return value, or null if the function returns Unit
     */
    abstract suspend fun invoke(parameters: Map<String, Any?>): Any?
    
    /**
     * Validate that the wrapped function is properly configured and callable.
     * Performs basic validation of function signature and parameter compatibility.
     * 
     * @return True if the function is valid and can be invoked, false otherwise
     */
    abstract fun validate(): Boolean
}

/**
 * Wrapper for Kotlin functions using reflection.
 * Handles KFunction objects and provides type-safe invocation with automatic
 * parameter mapping and return value extraction.
 */
class KotlinFunction(
    private val function: KFunction<*>,
    override val signature: FunctionSignature
) : NativeFunction() 
{
    /**
     * Invoke the Kotlin function using reflection with parameter mapping.
     * Converts the parameter map to ordered arguments based on function signature.
     */
    override suspend fun invoke(parameters: Map<String, Any?>): Any? 
    {
        // Create ordered parameter array based on function signature
        val orderedParams = signature.parameters.map { paramInfo ->
            parameters[paramInfo.name] ?: if (paramInfo.isOptional) null 
            else throw IllegalArgumentException("Required parameter '${paramInfo.name}' not provided")
        }.toTypedArray()
        
        // Invoke function with reflection
        return function.call(*orderedParams)
    }
    
    /**
     * Validate that the KFunction matches the provided signature.
     * Checks parameter count and basic type compatibility.
     */
    override fun validate(): Boolean 
    {
        return try 
        {
            // Basic validation - check parameter count matches
            function.parameters.size == signature.parameters.size
        } 
        catch (e: Exception) 
        {
            false
        }
    }
}

/**
 * Wrapper for lambda expressions and function objects.
 * Provides simplified binding for functional programming patterns with
 * explicit signature definition for type safety.
 */
class LambdaFunction<T>(
    private val lambda: T,
    override val signature: FunctionSignature
) : NativeFunction() 
{
    /**
     * Invoke the lambda function with parameter conversion.
     * Uses reflection to call the lambda's invoke method with converted parameters.
     */
    override suspend fun invoke(parameters: Map<String, Any?>): Any? 
    {
        // Create ordered parameter array based on signature
        val orderedParams = signature.parameters.map { paramInfo ->
            parameters[paramInfo.name] ?: if (paramInfo.isOptional) null 
            else throw IllegalArgumentException("Required parameter '${paramInfo.name}' not provided")
        }.toTypedArray()
        
        // Use reflection to invoke the lambda
        val invokeMethod = lambda!!::class.java.methods.find { it.name == "invoke" }
            ?: throw IllegalStateException("Lambda function does not have invoke method")
        
        return invokeMethod.invoke(lambda, *orderedParams)
    }
    
    /**
     * Validate that the lambda is not null and has an invoke method.
     * Basic validation for lambda function wrapper.
     */
    override fun validate(): Boolean 
    {
        return try 
        {
            lambda != null && lambda!!::class.java.methods.any { it.name == "invoke" }
        } 
        catch (e: Exception) 
        {
            false
        }
    }
}