package com.TTT.PipeContextProtocol

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KFunction
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaType

/**
 * Thread-safe singleton registry for managing all bound native functions.
 * Provides registration, lookup, validation, and lifecycle management for
 * native functions integrated with the PCP protocol.
 */
object FunctionRegistry 
{
    private val functions = ConcurrentHashMap<String, NativeFunction>()
    private val typeConverters = mutableListOf<TypeConverter>()
    
    init 
    {
        // Initialize default type converters
        typeConverters.add(PrimitiveConverter())
        typeConverters.add(CollectionConverter())
        typeConverters.add(ObjectConverter())
    }
    
    /**
     * Register a native function with automatic signature detection.
     * Uses reflection to analyze the KFunction and create appropriate signature metadata.
     * 
     * @param name The name to register the function under
     * @param function The KFunction to register
     * @return The generated function signature
     */
    fun registerFunction(name: String, function: KFunction<*>): FunctionSignature 
    {
        val signature = createSignatureFromKFunction(name, function)
        val wrapper = KotlinFunction(function, signature)
        functions[name] = wrapper
        return signature
    }
    
    /**
     * Register a lambda function with explicit signature.
     * Requires manual signature definition since lambda type information is limited.
     * 
     * @param name The name to register the lambda under
     * @param lambda The lambda function to register
     * @param signature The explicit function signature
     * @return The provided function signature
     */
    fun <T> registerLambda(name: String, lambda: T, signature: FunctionSignature): FunctionSignature 
    {
        val wrapper = LambdaFunction(lambda, signature)
        functions[name] = wrapper
        return signature
    }
    
    /**
     * Lookup registered function by name.
     * Returns null if no function is registered with the given name.
     * 
     * @param name The name of the function to lookup
     * @return The registered NativeFunction or null if not found
     */
    fun getFunction(name: String): NativeFunction? 
    {
        return functions[name]
    }
    
    /**
     * Get all registered function names.
     * Useful for debugging and PCP context generation.
     * 
     * @return Set of all registered function names
     */
    fun getFunctionNames(): Set<String> 
    {
        return functions.keys.toSet()
    }
    
    /**
     * Validate all registered functions.
     * Checks that all registered functions are properly configured and callable.
     * 
     * @return List of validation error messages, empty if all functions are valid
     */
    fun validateAll(): List<String> 
    {
        val errors = mutableListOf<String>()
        functions.forEach { (name, function) ->
            if (!function.validate()) 
            {
                errors.add("Function '$name' failed validation")
            }
        }
        return errors
    }
    
    /**
     * Get available type converters for parameter conversion.
     * Used by FunctionInvoker to convert PCP parameters to native types.
     * 
     * @return List of available type converters
     */
    fun getTypeConverters(): List<TypeConverter> 
    {
        return typeConverters.toList()
    }
    
    /**
     * Clear all registered functions.
     * Useful for testing and cleanup scenarios.
     */
    fun clear() 
    {
        functions.clear()
    }
    
    /**
     * Create function signature from KFunction using reflection.
     * Analyzes parameter types and return type to generate complete signature metadata.
     */
    private fun createSignatureFromKFunction(name: String, function: KFunction<*>): FunctionSignature 
    {
        val parameters = function.valueParameters.map { param ->
            val kotlinType = param.type.toString()
            val paramType = mapKotlinTypeToParamType(kotlinType)
            
            ParameterInfo(
                name = param.name ?: "param${param.index}",
                type = paramType,
                kotlinType = kotlinType,
                isOptional = param.isOptional,
                defaultValue = null,
                enumValues = emptyList(),
                description = ""
            )
        }
        
        val returnKotlinType = function.returnType.toString()
        val returnType = ReturnTypeInfo(
            type = mapKotlinTypeToParamType(returnKotlinType),
            kotlinType = returnKotlinType,
            isNullable = function.returnType.isMarkedNullable,
            description = ""
        )
        
        return FunctionSignature(
            name = name,
            parameters = parameters,
            returnType = returnType,
            description = "",
            permissions = emptyList()
        )
    }
    
    /**
     * Map Kotlin type strings to PCP ParamType enum values.
     * Provides basic type mapping for automatic signature generation.
     */
    private fun mapKotlinTypeToParamType(kotlinType: String): ParamType 
    {
        return when 
        {
            kotlinType.contains("String") -> ParamType.String
            kotlinType.contains("Int") -> ParamType.Int
            kotlinType.contains("Boolean") -> ParamType.Bool
            kotlinType.contains("Float") || kotlinType.contains("Double") -> ParamType.Float
            kotlinType.contains("List") || kotlinType.contains("Array") -> ParamType.List
            kotlinType.contains("Map") -> ParamType.Map
            kotlinType.contains("Enum") -> ParamType.Enum
            else -> ParamType.Object
        }
    }
}