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
    
    // Find boundaries for both objects and arrays
    val objectBoundaries = findJsonBoundaries(input, '{', '}')
    val arrayBoundaries = findJsonBoundaries(input, '[', ']')
    
    // Combine and sort by range size (larger ranges first to prefer complete objects over nested arrays)
    // Sort by range size (smaller first) - this prefers more specific/smaller JSON objects
    // over larger wrapper objects (like ConverseHistory) when searching for a target type.
    // This prevents larger JSON objects from incorrectly matching due to lenient deserialization
    // with encodeDefaults=true, which would populate missing fields with defaults.
    val allBoundaries = (objectBoundaries + arrayBoundaries).sortedBy { it.last - it.first }
    
    // Process all boundaries, preferring larger ranges
    allBoundaries.forEach { range ->
        if(!overlapsWithProcessed(range, processedRanges))
        {
            val jsonCandidate = input.substring(range)
            
            try {
                // Try parsing original first
                val element = json.parseToJsonElement(jsonCandidate)
                results.add(element)
                processedRanges.add(range)
            }
            catch(e: Exception)
            {
                // Only repair if original parsing failed
                try {
                    val repaired = repairJsonString(jsonCandidate)
                    val element = json.parseToJsonElement(repaired)
                    results.add(element)
                    processedRanges.add(range)
                }
                catch(e2: Exception)
                {
                    // Try fallback repair for severely malformed JSON
                    tryFallbackExtraction(jsonCandidate)?.let { fallbackElement ->
                        results.add(fallbackElement)
                        processedRanges.add(range)
                    }
                }
            }
        }
    }
    
    // Also find JSON arrays
    arrayBoundaries.forEach { range ->
        if(!overlapsWithProcessed(range, processedRanges))
        {
            val jsonCandidate = input.substring(range)
            
            try {
                // Try parsing original first
                val element = json.parseToJsonElement(jsonCandidate)
                results.add(element)
                processedRanges.add(range)
            }
            catch(e: Exception)
            {
                // Only repair if original parsing failed
                try {
                    val repaired = repairJsonString(jsonCandidate)
                    val element = json.parseToJsonElement(repaired)
                    results.add(element)
                    processedRanges.add(range)
                }
                catch(e2: Exception)
                {
                    // Try fallback repair for severely malformed JSON
                    tryFallbackExtraction(jsonCandidate)?.let { fallbackElement ->
                        results.add(fallbackElement)
                        processedRanges.add(range)
                    }
                }
            }
        }
    }
    
    return results
}

/**
 * Finds the boundaries of JSON objects by tracking nesting depth from first { to matching }.
 * When depth returns to 0, we have a complete object. Continue scanning for next top-level {.
 */
private fun findJsonBoundaries(input: String, openChar: Char, closeChar: Char): List<IntRange>
{
    val boundaries = mutableListOf<IntRange>()
    var i = 0
    
    while(i < input.length)
    {
        // Find next top-level opening bracket
        while(i < input.length && input[i] != openChar)
        {
            i++
        }
        
        if(i >= input.length) break
        
        val start = i
        var depth = 0
        var inString = false
        var escaped = false
        
        // Track from opening bracket to matching closing bracket
        while(i < input.length)
        {
            val char = input[i]
            
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' && !escaped -> inString = !inString
                !inString && char == openChar -> depth++
                !inString && char == closeChar -> {
                    depth--
                    if(depth == 0)
                    {
                        boundaries.add(start..i)
                        i++
                        break
                    }
                }
            }
            i++
        }
        
        // Handle truncated JSON - if we reached end of input with unclosed brackets
        if(depth > 0 && start < input.length)
        {
            boundaries.add(start until input.length)
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
    if(extracted.isNotEmpty())
    {
        try {
            val reconstructedJson = buildJsonFromExtracted(extracted)
            return json.parseToJsonElement(reconstructedJson)
        }
        catch(e: Exception)
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
        if(index > 0) jsonBuilder.append(",")
        
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
        encodeDefaults = false
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

    for(element in jsonElements)
    {
        try {
            // Try direct deserialization first
            val jsonString = json.encodeToString(JsonElement.serializer(), element)
            return json.decodeFromString<T>(jsonString)
        }
        catch(e: Exception)
        {
            // If element is an array, try deserializing its first element
            if(element is kotlinx.serialization.json.JsonArray && element.isNotEmpty())
            {
                try {
                    val firstElement = element[0]
                    val jsonString = json.encodeToString(JsonElement.serializer(), firstElement)
                    return json.decodeFromString<T>(jsonString)
                }
                catch(e3: Exception)
                {
                    // Continue to repair fallback
                }
            }
            
            // Try with repair as fallback
            try {
                val jsonString = json.encodeToString(JsonElement.serializer(), element)
                return deserialize<T>(jsonString)
            }
            catch(e2: Exception)
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
        if(!overlapsWithProcessed(range, jsonRanges.toSet()))
        {
            jsonRanges.add(range)
        }
    }
    
    // Sort ranges by start position
    jsonRanges.sortBy { it.first }
    
    // Extract text between JSON ranges
    val result = StringBuilder()
    var lastEnd = 0
    
    for(range in jsonRanges)
    {
        if(range.first > lastEnd)
        {
            result.append(input.substring(lastEnd, range.first))
        }
        lastEnd = maxOf(lastEnd, range.last + 1)
    }
    
    // Append remaining text after last JSON
    if(lastEnd < input.length)
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

