package com.TTT.PipeContextProtocol

import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Manages return values from native function calls.
 * Handles storage, retrieval, and integration with pipe context for
 * seamless return value usage in pipeline workflows.
 */
class ReturnValueHandler 
{
    private val returnValues = ConcurrentHashMap<String, Any?>()
    private val typeConverters = FunctionRegistry.getTypeConverters()
    
    /**
     * Store return value with generated or explicit key.
     * Generates unique key if none provided and stores the value for later retrieval.
     * 
     * @param key The key to store the value under, generates UUID if empty
     * @param value The return value to store
     * @return The key used for storage
     */
    fun storeReturnValue(key: String = "", value: Any?): String 
    {
        val storageKey = if (key.isEmpty()) UUID.randomUUID().toString() else key
        returnValues[storageKey] = value
        return storageKey
    }
    
    /**
     * Retrieve stored return value by key.
     * Returns null if key not found or value was null.
     * 
     * @param key The key to retrieve the value for
     * @return The stored value or null if not found
     */
    fun getReturnValue(key: String): Any? 
    {
        return returnValues[key]
    }
    
    /**
     * Convert return value to context window entry.
     * Creates a key-value pair suitable for injection into context window.
     * 
     * @param key The key for the context entry
     * @param value The value to convert to string format
     * @return Pair of key and string representation of value
     */
    fun toContextEntry(key: String, value: Any?): Pair<String, String> 
    {
        val stringValue = convertValueToString(value)
        return Pair(key, stringValue)
    }
    
    /**
     * Get all stored return values as context entries.
     * Useful for bulk injection into context window.
     * 
     * @return Map of all stored values converted to string format
     */
    fun getAllAsContextEntries(): Map<String, String> 
    {
        return returnValues.mapValues { (_, value) -> convertValueToString(value) }
    }
    
    /**
     * Clear stored return value by key.
     * Removes the value from storage to free memory.
     * 
     * @param key The key to remove
     * @return True if value was removed, false if key not found
     */
    fun clearReturnValue(key: String): Boolean 
    {
        return returnValues.remove(key) != null
    }
    
    /**
     * Clear all stored return values.
     * Useful for cleanup between pipeline executions.
     */
    fun clearAll() 
    {
        returnValues.clear()
    }
    
    /**
     * Check if a return value exists for the given key.
     * 
     * @param key The key to check
     * @return True if value exists, false otherwise
     */
    fun hasReturnValue(key: String): Boolean 
    {
        return returnValues.containsKey(key)
    }
    
    /**
     * Get all stored return value keys.
     * Useful for debugging and context management.
     * 
     * @return Set of all stored keys
     */
    fun getStoredKeys(): Set<String> 
    {
        return returnValues.keys.toSet()
    }
    
    /**
     * Convert any value to string representation using type converters.
     * Handles null values and uses appropriate converter for type.
     */
    private fun convertValueToString(value: Any?): String 
    {
        if (value == null) return ""
        
        // Try to find appropriate converter
        val converter = typeConverters.find { 
            it.canConvert(mapValueToParamType(value), value::class.java.simpleName) 
        }
        
        return converter?.convertBack(value, mapValueToParamType(value)) ?: value.toString()
    }
    
    /**
     * Map runtime value to PCP ParamType for converter selection.
     * Provides basic type mapping for return value conversion.
     */
    private fun mapValueToParamType(value: Any): ParamType 
    {
        return when (value) 
        {
            is String -> ParamType.String
            is Int -> ParamType.Int
            is Boolean -> ParamType.Bool
            is Float, is Double -> ParamType.Float
            is List<*> -> ParamType.List
            is Map<*, *> -> ParamType.Map
            else -> ParamType.Object
        }
    }
}