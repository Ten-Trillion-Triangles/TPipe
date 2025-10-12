package com.TTT.Util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Extracts all distinct JSON objects and arrays from a given string and converts them to JsonElement objects.
 * Uses the same level of laxness as the project's existing JSON handling mechanisms.
 * 
 * This function will find JSON content that may be embedded within other text, malformed, or mixed with
 * non-JSON content. It applies the same repair strategies used throughout the TPipe framework.
 *
 * @param input The input string that may contain one or more JSON objects or arrays
 * @return A list of JsonElement objects representing all distinct JSON content found in the string
 */
@OptIn(ExperimentalSerializationApi::class)
fun extractAllJsonObjects(input: String): List<JsonElement>
{
    val results = mutableListOf<JsonElement>()
    val processedRanges = mutableSetOf<IntRange>()
    
    // Configure lenient JSON parser matching project standards
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
        allowComments = true
        useArrayPolymorphism = true
        decodeEnumsCaseInsensitive = true
        useAlternativeNames = true
        allowTrailingComma = true
    }
    
    // Find all potential JSON objects
    findJsonBoundaries(input, '{', '}').forEach { range ->
        if (!overlapsWithProcessed(range, processedRanges))
        {
            val jsonCandidate = input.substring(range)
            val repaired = repairJsonString(jsonCandidate)
            
            try {
                val element = json.parseToJsonElement(repaired)
                results.add(element)
                processedRanges.add(range)
            }
            catch (e: Exception)
            {
                // Try fallback repair for severely malformed JSON
                tryFallbackExtraction(jsonCandidate)?.let { fallbackElement ->
                    results.add(fallbackElement)
                    processedRanges.add(range)
                }
            }
        }
    }
    
    // Find all potential JSON arrays
    findJsonBoundaries(input, '[', ']').forEach { range ->
        if (!overlapsWithProcessed(range, processedRanges))
        {
            val jsonCandidate = input.substring(range)
            val repaired = repairJsonString(jsonCandidate)
            
            try {
                val element = json.parseToJsonElement(repaired)
                results.add(element)
                processedRanges.add(range)
            }
            catch (e: Exception)
            {
                // Arrays are less likely to need fallback, but attempt if needed
                tryFallbackExtraction(jsonCandidate)?.let { fallbackElement ->
                    results.add(fallbackElement)
                    processedRanges.add(range)
                }
            }
        }
    }
    
    return results
}

/**
 * Finds the boundaries of JSON objects or arrays by matching opening and closing brackets.
 * Handles nested structures correctly by tracking bracket depth.
 */
private fun findJsonBoundaries(input: String, openChar: Char, closeChar: Char): List<IntRange>
{
    val boundaries = mutableListOf<IntRange>()
    var i = 0
    
    while (i < input.length)
    {
        if (input[i] == openChar)
        {
            val start = i
            var depth = 1
            var inString = false
            var escaped = false
            i++
            
            while (i < input.length && depth > 0)
            {
                val char = input[i]
                
                when
                {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' && !escaped -> inString = !inString
                    !inString && char == openChar -> depth++
                    !inString && char == closeChar -> depth--
                }
                
                i++
            }
            
            if (depth == 0)
            {
                boundaries.add(start until i)
            }
        }
        else
        {
            i++
        }
    }
    
    return boundaries
}

/**
 * Checks if a given range overlaps with any previously processed ranges.
 */
private fun overlapsWithProcessed(range: IntRange, processedRanges: Set<IntRange>): Boolean
{
    return processedRanges.any { processed ->
        range.first <= processed.last && range.last >= processed.first
    }
}

/**
 * Attempts fallback extraction using the project's existing repair mechanisms.
 * This handles cases where standard JSON parsing fails even after initial repair.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun tryFallbackExtraction(jsonCandidate: String): JsonElement?
{
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
        allowComments = true
        useArrayPolymorphism = true
        decodeEnumsCaseInsensitive = true
        useAlternativeNames = true
        allowTrailingComma = true
    }
    
    // Extract key-value pairs and attempt manual reconstruction
    val extracted = extractJsonData(jsonCandidate)
    if (extracted.isNotEmpty())
    {
        try {
            val reconstructedJson = buildJsonFromExtracted(extracted)
            return json.parseToJsonElement(reconstructedJson)
        }
        catch (e: Exception)
        {
            return null
        }
    }
    
    return null
}

/**
 * Builds a JSON string from extracted key-value pairs.
 */
private fun buildJsonFromExtracted(extracted: Map<String, String>): String
{
    val jsonBuilder = StringBuilder("{")
    
    extracted.entries.forEachIndexed { index, (key, value) ->
        if (index > 0) jsonBuilder.append(",")
        
        jsonBuilder.append("\"$key\":")
        
        // Determine if value should be quoted
        when
        {
            value.equals("true", ignoreCase = true) || 
            value.equals("false", ignoreCase = true) ||
            value.equals("null", ignoreCase = true) ||
            value.matches(Regex("\\d+(\\.\\d+)?")) -> jsonBuilder.append(value)
            else -> jsonBuilder.append("\"$value\"")
        }
    }
    
    jsonBuilder.append("}")
    return jsonBuilder.toString()
}

/**
 * Attempts to deserialize any JsonElement in the given array to the target type T.
 * Uses the project's lenient deserialization approach and returns the first successful match.
 *
 * @param jsonElements Array of JsonElement objects to attempt deserialization on
 * @return The first JsonElement successfully deserialized to type T, or null if none can be deserialized
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> deserializeFirstMatch(jsonElements: List<JsonElement>): T?
{
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
        useArrayPolymorphism = false
        useAlternativeNames = true
        allowTrailingComma = true
        allowComments = true
        decodeEnumsCaseInsensitive = true
    }
    
    for (element in jsonElements)
    {
        try {
            // Properly serialize JsonElement to JSON string, then deserialize
            val jsonString = json.encodeToString(JsonElement.serializer(), element)
            return json.decodeFromString<T>(jsonString)
        }
        catch (e: Exception)
        {
            // Try with repair as fallback
            try {
                val jsonString = json.encodeToString(JsonElement.serializer(), element)
                return deserialize<T>(jsonString)
            }
            catch (e2: Exception)
            {
                //Exists so a breakpoint can be hooked here.
                val  doesNothing = true
                // Continue to next element
            }
        }
    }
    
    return null
}

/**
 * Extracts all text from a string that exists outside of JSON objects and arrays.
 * This is useful for truncating non-JSON content while preserving JSON structures intact.
 *
 * @param input The input string that may contain JSON mixed with other text
 * @return The text content with all JSON objects and arrays removed
 */
fun extractNonJsonText(input: String): String
{
    val jsonRanges = mutableListOf<IntRange>()
    
    // Collect all JSON object boundaries
    findJsonBoundaries(input, '{', '}').forEach { range ->
        jsonRanges.add(range)
    }
    
    // Collect all JSON array boundaries
    findJsonBoundaries(input, '[', ']').forEach { range ->
        if (!overlapsWithProcessed(range, jsonRanges.toSet()))
        {
            jsonRanges.add(range)
        }
    }
    
    // Sort ranges by start position
    jsonRanges.sortBy { it.first }
    
    // Extract text between JSON ranges
    val result = StringBuilder()
    var lastEnd = 0
    
    for (range in jsonRanges)
    {
        if (range.first > lastEnd)
        {
            result.append(input.substring(lastEnd, range.first))
        }
        lastEnd = maxOf(lastEnd, range.last + 1)
    }
    
    // Append remaining text after last JSON
    if (lastEnd < input.length)
    {
        result.append(input.substring(lastEnd))
    }
    
    return result.toString()
}

/**
 * Extracts and deserializes the first matching JSON object of type T from a string.
 * This is the final abstraction that combines JSON extraction and deserialization.
 * Uses the project's lenient parsing and repair mechanisms.
 *
 * @param input The input string that may contain JSON objects mixed with other text
 * @return The first JSON object successfully deserialized to type T, or null if none found
 */
inline fun <reified T> extractJson(input: String): T?
{
    val jsonElements = extractAllJsonObjects(input)
    return deserializeFirstMatch<T>(jsonElements)
}

