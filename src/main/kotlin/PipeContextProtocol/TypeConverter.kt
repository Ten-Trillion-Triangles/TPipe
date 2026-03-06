package com.TTT.PipeContextProtocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Main interface for type conversion between PCP parameters and native types.
 * Handles bidirectional conversion with validation and error handling for
 * seamless integration between string-based PCP protocol and typed function calls.
 */
interface TypeConverter 
{
    /**
     * Check if this converter can handle conversion from PCP type to target Kotlin type.
     * 
     * @param from The PCP parameter type
     * @param to The target Kotlin type name
     * @return True if conversion is supported, false otherwise
     */
    fun canConvert(from: ParamType, to: String): Boolean
    
    /**
     * Convert a value from PCP format to native Kotlin type.
     * 
     * @param value The value to convert (typically a string from PCP)
     * @param targetType The target Kotlin type name
     * @return The converted value in native type, or null if conversion fails
     */
    fun convert(value: Any?, targetType: String): Any?
    
    /**
     * Convert a native value back to PCP string format.
     * 
     * @param value The native value to convert
     * @param sourceType The PCP parameter type to convert to
     * @return String representation suitable for PCP protocol
     */
    fun convertBack(value: Any?, sourceType: ParamType): String
}

/**
 * Handles conversion of primitive types (String, Int, Boolean, Float, Double).
 * Includes validation and safe conversion with comprehensive error handling
 * for all basic Kotlin primitive types.
 */
class PrimitiveConverter : TypeConverter 
{
    /**
     * Check if primitive conversion is supported for the given types.
     * Supports String, Int, Boolean, Float, Double, and their nullable variants.
     */
    override fun canConvert(from: ParamType, to: String): Boolean 
    {
        return when(from)
        {
            ParamType.String -> to.contains("String")
            ParamType.Int -> to.contains("Int")
            ParamType.Bool -> to.contains("Boolean")
            ParamType.Float -> to.contains("Float") || to.contains("Double")
            ParamType.Enum -> true // Can convert any enum type
            else -> false
        }
    }
    
    /**
     * Convert string value to appropriate primitive type with validation.
     * Handles nullable types and provides meaningful error messages for invalid conversions.
     */
    override fun convert(value: Any?, targetType: String): Any? 
    {
        if(value == null) return null
        
        val stringValue = value.toString()
        val isNullable = targetType.endsWith("?")
        
        if(stringValue.isEmpty() && isNullable) return null
        
        return try
        {
            when
            {
                targetType.contains("String") -> stringValue
                targetType.contains("Int") -> stringValue.toInt()
                targetType.contains("Boolean") -> stringValue.toBoolean()
                targetType.contains("Double") -> stringValue.toDouble()
                targetType.contains("Float") -> stringValue.toFloat()
                else -> convertToEnum(stringValue, targetType)
            }
        } 
        catch(e: NumberFormatException)
        {
            throw IllegalArgumentException("Cannot convert '$stringValue' to $targetType: ${e.message}")
        }
    }

    private fun convertToEnum(value: String, targetType: String): Any?
    {
        val className = targetType.removeSuffix("?")
        val candidate = value.trim()

        val enumClass = resolveEnumClass(className)

        return try
        {
            java.lang.Enum.valueOf(enumClass, candidate)
        }
        catch(e: IllegalArgumentException)
        {
            throw IllegalArgumentException("Value '$candidate' is not valid for enum ${enumClass.name}", e)
        }
    }

    private fun resolveEnumClass(className: String): Class<out Enum<*>>
    {
        val attempts = LinkedHashSet<String>()
        attempts.add(className)

        var binaryCandidate = className
        while(true)
        {
            val lastDot = binaryCandidate.lastIndexOf('.')
            if(lastDot < 0) break
            binaryCandidate = binaryCandidate.substring(0, lastDot) + "$" + binaryCandidate.substring(lastDot + 1)
            attempts.add(binaryCandidate)
        }

        val classLoaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            PrimitiveConverter::class.java.classLoader,
            ClassLoader.getSystemClassLoader()
        ).distinct()

        for(loader in classLoaders)
        {
            for(candidate in attempts)
            {
                try
                {
                    val rawClass = loader.loadClass(candidate)
                    if(rawClass.isEnum)
                    {
                        @Suppress("UNCHECKED_CAST")
                        return rawClass as Class<out Enum<*>>
                    }
                }
                catch(_: ClassNotFoundException)
                {
                    // Try next candidate
                }
            }
        }

        throw IllegalArgumentException("Enum type '$className' not found")
    }

    /**
     * Convert primitive value back to string format for PCP protocol.
     * Handles null values and provides consistent string representation.
     */
    override fun convertBack(value: Any?, sourceType: ParamType): String 
    {
        return when
        {
            value == null -> ""
            sourceType == ParamType.Enum && value is Enum<*> -> value.name
            else -> value.toString()
        }
    }
}

/**
 * Handles conversion of collection types (List, Map, Array).
 * Supports nested type conversion and maintains type safety for
 * complex data structures used in function parameters.
 */
class CollectionConverter : TypeConverter 
{
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Check if collection conversion is supported.
     * Supports List, Map, Array, and Set types with generic parameters.
     */
    override fun canConvert(from: ParamType, to: String): Boolean 
    {
        return when(from)
        {
            ParamType.List -> to.contains("List") || to.contains("Array")
            ParamType.Map -> to.contains("Map")
            else -> false
        }
    }
    
    /**
     * Convert JSON string to collection type using kotlinx.serialization.
     * Handles nested collections and maintains type information where possible.
     */
    override fun convert(value: Any?, targetType: String): Any? 
    {
        if(value == null) return null
        
        val stringValue = value.toString()
        if(stringValue.isEmpty()) return null
        
        return try 
        {
            when 
            {
                targetType.contains("List") -> json.decodeFromString<List<Any>>(stringValue)
                targetType.contains("Map") -> json.decodeFromString<Map<String, Any>>(stringValue)
                targetType.contains("Array") -> json.decodeFromString<List<Any>>(stringValue).toTypedArray()
                else -> throw IllegalArgumentException("Unsupported collection type: $targetType")
            }
        } 
        catch(e: Exception)
        {
            throw IllegalArgumentException("Cannot convert '$stringValue' to $targetType: ${e.message}")
        }
    }
    
    /**
     * Convert collection back to JSON string format.
     * Uses kotlinx.serialization for consistent JSON representation.
     */
    override fun convertBack(value: Any?, sourceType: ParamType): String 
    {
        return if(value == null) "" else json.encodeToString(value)
    }
}

/**
 * Handles conversion of complex object types using JSON serialization.
 * Provides fallback conversion for any object type not handled by
 * primitive or collection converters.
 */
class ObjectConverter : TypeConverter 
{
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Object converter can handle any type as fallback.
     * Always returns true to serve as the last resort converter.
     */
    override fun canConvert(from: ParamType, to: String): Boolean 
    {
        return from == ParamType.Object || from == ParamType.Any
    }
    
    /**
     * Convert JSON string to generic object using Map representation.
     * Provides basic object conversion for complex types.
     */
    override fun convert(value: Any?, targetType: String): Any? 
    {
        if(value == null) return null
        
        val stringValue = value.toString()
        if(stringValue.isEmpty()) return null
        
        return try 
        {
            json.decodeFromString<Map<String, Any>>(stringValue)
        } 
        catch(e: Exception)
        {
            // Fallback to string if JSON parsing fails
            stringValue
        }
    }
    
    /**
     * Convert object to JSON string representation.
     * Handles various object types with fallback to toString().
     */
    override fun convertBack(value: Any?, sourceType: ParamType): String 
    {
        return when(value)
        {
            null -> ""
            is String -> value
            else -> try 
            {
                json.encodeToString(value)
            } 
            catch(e: Exception)
            {
                value.toString()
            }
        }
    }
}
