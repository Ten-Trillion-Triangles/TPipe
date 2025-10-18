package com.TTT.Util

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.*
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.io.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

enum class LineEnding {
    Unix,
    Windows,
    Mac,
    Unknown
}

/**
 * Serializes an object into a json string given the loosest restrictions possible. Will attempt to
 * serialize all that it can and never throw an exception. This mechanism is required because we don't
 * know what languge has serialized the object, and we can't trust an AI model to obey the rules of
 * serialization. This is especially true for any model or api that doesn't have support for forced json
 * structuring. In this case TPipe can force it to support json as a return by prompt engineering, but we need
 * to handle unexpected output, missing values, or other nonsense as best we can.
 *
 * @param obj The object to serialize. The object must be serializable by kotlinx serialization. We'll attempt to
 * handle passing invalid objects returning an empty string as often as possible.
 *
 * @return A json string representation of the object. Will return an empty string if the object cannot be
 * serialized.
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> serialize(obj: T, encodedefault : Boolean = true): String
{
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = encodedefault
        explicitNulls = false
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
        allowComments = true
        useArrayPolymorphism = true
        decodeEnumsCaseInsensitive = true
        useAlternativeNames = true
        explicitNulls = true
    }

    return try {
        json.encodeToString(obj)
    }
    catch (e: Exception)
    {
        ""
    }

    return ""
}


/**
 * Deserializes a json string into an object of type T.
 *
 * This function is intentionally lenient in order to handle the fact that an AI model may not
 * always return proper json. We can't trust an AI model to return good json, so we have to be
 * prepared to handle unexpected input, missing values, or other nonsense as best we can.
 *
 * @param jsonString The json string to deserialize.
 * @param useRepair Whether to attempt JSON repair if standard deserialization fails.
 *
 * @return A deserialized object of type T, or null if the string cannot be deserialized.
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> deserialize(jsonString: String, useRepair: Boolean = true): T?
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

    return try {
        json.decodeFromString(jsonString)
    }
    catch (e: Exception)
    {
        if (useRepair) {
            return repairAndDeserialize<T>(jsonString)
        }
        return null
    }
}


/**
 * Applies multiple repair strategies to fix malformed JSON from AI models.
 * Handles missing quotes, trailing commas, HTML entities, and structural issues.
 */
fun repairJsonString(input: String): String
{
    var repaired = input.trim()
    
    // Decode HTML entities and common escape sequences
    repaired = repaired
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\r", "\r")
    
    // Extract JSON boundaries with proper bracket matching
    val jsonBounds = findJsonBounds(repaired)
    if (jsonBounds != null) {
        repaired = repaired.substring(jsonBounds.first, jsonBounds.second + 1)
    }
    
    // Fix structural issues
    repaired = repaired
        .replace(Regex(",+\\s*([}\\]])"), "$1") // Remove trailing commas
        .replace(Regex(",+"), ",") // Fix multiple commas
        .replace(Regex(":\\s*,"), ": null,") // Fix empty values
        .replace(Regex(":\\s*([}\\]])"), ": null$1") // Fix missing values at end
    
    // Fix unquoted keys - more comprehensive pattern
    repaired = Regex("([{,]\\s*)([a-zA-Z_][\\w\\s-]*?)\\s*:").replace(repaired) { match ->
        val key = match.groupValues[2].trim()
        "${match.groupValues[1]}\"$key\":"
    }
    
    // Fix unquoted string values - handle more cases
    repaired = fixUnquotedValues(repaired)
    
    // Fix incomplete structures
    repaired = completeStructure(repaired)
    
    return repaired
}

private fun findJsonBounds(input: String): Pair<Int, Int>?
{
    val start = input.indexOfFirst { it == '{' || it == '[' }
    if (start == -1) return null
    
    val openChar = input[start]
    val closeChar = if (openChar == '{') '}' else ']'
    var depth = 0
    var inString = false
    var escaped = false
    
    for (i in start until input.length) {
        val char = input[i]
        when {
            escaped -> escaped = false
            char == '\\' -> escaped = true
            char == '"' && !escaped -> inString = !inString
            !inString && char == openChar -> depth++
            !inString && char == closeChar -> {
                depth--
                if (depth == 0) return Pair(start, i)
            }
        }
    }
    
    // If unclosed, find reasonable end point
    val lastBrace = input.lastIndexOf(closeChar)
    return if (lastBrace > start) Pair(start, lastBrace) else null
}

private fun fixUnquotedValues(input: String): String
{
    var result = input
    
    // Pattern to match unquoted values after colons
    val valuePattern = Regex("(:\\s*)(?![\"{\\[\\]\\-\\d]|true|false|null)([^,}\\]\"\\n]+?)(?=[,}\\]\\n])")
    
    result = valuePattern.replace(result) { match ->
        val prefix = match.groupValues[1]
        val value = match.groupValues[2].trim()
        
        // Don't quote if it's clearly a number, boolean, or null
        if (value.matches(Regex("\\-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?|true|false|null"))) {
            match.value
        } else {
            "$prefix\"$value\""
        }
    }
    
    return result
}

private fun completeStructure(input: String): String
{
    var result = input.trim()
    
    // Count opening vs closing brackets
    val openBraces = result.count { it == '{' }
    val closeBraces = result.count { it == '}' }
    val openBrackets = result.count { it == '[' }
    val closeBrackets = result.count { it == ']' }
    
    // Add missing closing braces/brackets
    repeat(openBraces - closeBraces) { result += "}" }
    repeat(openBrackets - closeBrackets) { result += "]" }
    
    // Handle truncated strings
    val quoteCount = result.count { it == '"' }
    if (quoteCount % 2 != 0) {
        result += "\""
    }
    
    return result
}


/**
 * Repairs malformed JSON and attempts deserialization with fallback strategies.
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> repairAndDeserialize(malformedJson: String): T?
{
    val repaired = repairJsonString(malformedJson)
    
    // Direct deserialization without calling deserialize() to break cycle
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
    
    // Try standard repair first
    val result = try {
        json.decodeFromString<T>(repaired)
    } catch (e: Exception) {
        null
    }
    
    // If repair failed, try aggressive extraction
    return result ?: aggressiveExtraction<T>(malformedJson)
}

/**
 * Aggressive extraction using multiple fallback strategies when standard repair fails.
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> aggressiveExtraction(malformedJson: String): T?
{
    // Strategy 1: Try reflection-based reconstruction
    reflectionBasedReconstruct<T>(malformedJson)?.let { return it }
    
    // Strategy 2: Extract from multiple JSON fragments
    val allJsonObjects = extractAllJsonObjects(malformedJson)
    for (jsonElement in allJsonObjects) {
        try {
            val jsonString = Json.encodeToString(JsonElement.serializer(), jsonElement)
            return Json { 
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }.decodeFromString<T>(jsonString)
        } catch (e: Exception) { continue }
    }
    
    // Strategy 3: Aggressive text mining
    aggressiveTextMining<T>(malformedJson)?.let { return it }
    
    // Strategy 4: Template-based reconstruction
    return templateBasedReconstruction<T>(malformedJson)
}

/**
 * Mines text for any recognizable patterns and attempts reconstruction.
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> aggressiveTextMining(text: String): T?
{
    val clazz = T::class.java
    val reconstructed = mutableMapOf<String, Any?>()
    
    // Get field names from target class
    val fieldNames = clazz.declaredFields.map { it.name }
    
    // Mine for field values using multiple patterns
    for (fieldName in fieldNames) {
        val patterns = listOf(
            Regex("$fieldName[\"'\\s]*[:=][\"'\\s]*([^,}\\]\\n]+)", RegexOption.IGNORE_CASE),
            Regex("\"$fieldName\"[\\s]*:[\\s]*\"([^\"]*)", RegexOption.IGNORE_CASE),
            Regex("$fieldName[\\s]*=[\\s]*([^,\\n]+)", RegexOption.IGNORE_CASE),
            Regex("$fieldName[\\s]*is[\\s]*([^,\\n]+)", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            pattern.find(text)?.let { match ->
                val value = match.groupValues[1].trim().removeSurrounding("\"", "'")
                if (value.isNotEmpty()) {
                    reconstructed[fieldName] = value
                    break
                }
            }
        }
    }
    
    return if (reconstructed.isNotEmpty()) {
        try {
            val jsonString = Json.encodeToString(reconstructed)
            Json { 
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }.decodeFromString<T>(jsonString)
        } catch (e: Exception) { null }
    } else null
}

/**
 * Creates a template instance and fills it with any extractable data.
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> templateBasedReconstruction(text: String): T?
{
    return try {
        val clazz = T::class.java
        val constructor = clazz.getDeclaredConstructor()
        constructor.isAccessible = true
        val instance = constructor.newInstance()
        
        // Try to fill fields with extracted data
        val extracted = extractJsonData(text)
        clazz.declaredFields.forEach { field ->
            field.isAccessible = true
            extracted[field.name]?.let { value ->
                try {
                    when (field.type) {
                        String::class.java -> field.set(instance, value)
                        Boolean::class.java -> field.set(instance, value.lowercase() == "true")
                        Int::class.java -> field.set(instance, value.toIntOrNull() ?: 0)
                        Double::class.java -> field.set(instance, value.toDoubleOrNull() ?: 0.0)
                    }
                } catch (e: Exception) { /* Skip field */ }
            }
        }
        
        instance as T
    } catch (e: Exception) {
        null
    }
}


/**
 * Extracts key-value pairs from malformed JSON using regex patterns as fallback.
 * Returns a Map that can be manually processed when standard JSON parsing fails.
 */
fun extractJsonData(malformedJson: String): Map<String, String>
{
    val result = mutableMapOf<String, String>()
    
    // More comprehensive pattern for key-value pairs
    val patterns = listOf(
        // "key": "value" or 'key': 'value'
        Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*(?:\\\\.[^\"]*)*)\""),
        Regex("'([^']+)'\\s*:\\s*'([^']*(?:\\\\.[^']*)*)'"),
        // "key": value (unquoted values)
        Regex("\"([^\"]+)\"\\s*:\\s*([^,}\\]\\n]+?)(?=[,}\\]\\n]|$)"),
        // key: "value" (unquoted keys)
        Regex("([a-zA-Z_][\\w\\s-]*)\\s*:\\s*\"([^\"]*(?:\\\\.[^\"]*)*)\""),
        // key: value (both unquoted)
        Regex("([a-zA-Z_][\\w\\s-]*)\\s*:\\s*([^,}\\]\\n]+?)(?=[,}\\]\\n]|$)")
    )
    
    for (pattern in patterns) {
        pattern.findAll(malformedJson).forEach { match ->
            val key = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            if (key.isNotEmpty() && !result.containsKey(key)) {
                result[key] = value
            }
        }
    }
    
    return result
}


/**
 * Creates a deep copy of a data class using reflection.
 * Recursively copies nested data classes and collections.
 *
 * @param T The type of the data class to copy
 * @return A deep copy of the original object
 */
inline fun <reified T : Any> T.deepCopy(): T
{
    return deepCopyInternal(this, T::class) as T
}

/**
 * Internal recursive deep copy implementation.
 * Handles data classes, collections, and primitive types.
 */
fun deepCopyInternal(obj: Any?, kClass: KClass<*>): Any?
{
    return when 
    {
        obj == null -> null
        
        // Primitive types and strings are immutable
        obj is String || obj is Number || obj is Boolean || obj is Char -> obj
        
        // Handle collections
        obj is List<*> -> obj.map { deepCopyInternal(it, it?.let { it::class } ?: Any::class) }
        obj is Set<*> -> obj.map { deepCopyInternal(it, it?.let { it::class } ?: Any::class) }.toSet()
        obj is Map<*, *> -> obj.mapValues { deepCopyInternal(it.value, it.value?.let { it::class } ?: Any::class) }
        
        // Handle data classes
        kClass.isData -> 
        {
            val constructor = kClass.primaryConstructor!!
            
            val args = constructor.parameters.associateWith { param ->
                val property = kClass.memberProperties.find { it.name == param.name } as? KProperty1<Any, *>
                    ?: throw IllegalArgumentException("Property ${param.name} not found")
                
                val value = property.get(obj)
                deepCopyInternal(value, param.type.classifier as? KClass<*> ?: Any::class)
            }
            
            constructor.callBy(args)
        }
        
        // Return as-is for other types
        else -> obj
    }
}

/**
 * Uses reflection to reconstruct JSON from extracted data based on target class structure.
 * Attempts to match extracted keys to class properties and build valid JSON.
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> reflectionBasedReconstruct(malformedJson: String): T?
{
    val extracted = extractJsonData(malformedJson)
    if (extracted.isEmpty()) return null
    
    try {
        val clazz = T::class.java
        val reconstructed = mutableMapOf<String, Any?>()
        
        // Get all declared fields from the class
        clazz.declaredFields.forEach { field ->
            val fieldName = field.name
            val extractedValue = extracted[fieldName]
            
            if (extractedValue != null) {
                // Convert string value to appropriate type
                val convertedValue = when (field.type) {
                    Boolean::class.java -> extractedValue.lowercase() == "true"
                    Int::class.java -> extractedValue.toIntOrNull()
                    Double::class.java -> extractedValue.toDoubleOrNull()
                    Float::class.java -> extractedValue.toFloatOrNull()
                    Long::class.java -> extractedValue.toLongOrNull()
                    String::class.java -> extractedValue
                    else -> {
                        // Handle nested objects by recursively extracting
                        if (extractedValue.startsWith("{") && extractedValue.endsWith("}")) {
                            extractJsonData(extractedValue)
                        } else extractedValue
                    }
                }
                reconstructed[fieldName] = convertedValue
            }
        }
        
        // Direct serialization and deserialization to break cycle
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
        
        val jsonString = try {
            json.encodeToString(reconstructed)
        } catch (e: Exception) {
            return null
        }
        
        return try {
            json.decodeFromString<T>(jsonString)
        } catch (e: Exception) {
            null
        }
        
    } catch (e: Exception) {
        return null
    }
}


/**
 * Construct a new pipe using a prior pipe as a template. Allows for any valid pipe class to be passed as the template
 * to return, and returns a new copied pipe cast to that type.
 */
fun <T> constructPipeFromTemplate(
    template : Pipe, copyFunctions: Boolean = false,
    copyPipes: Boolean = false,
    copyMetadata: Boolean = false) : T?
{
    val newPipe = deserialize<Pipe>(serialize(template)) ?: return null

    if(copyFunctions)
    {
        template.validatorFunction?.let { newPipe.validatorFunction = it }
        template.transformationFunction?.let { newPipe.transformationFunction = it }
        template.preValidationFunction?.let { newPipe.preValidationFunction = it }
        template.preValidationMiniBankFunction?.let { newPipe.preValidationMiniBankFunction = it }
        template.preInvokeFunction?.let { newPipe.preInvokeFunction = it }
        template.onFailure?.let { newPipe.onFailure = it }
    }

    if(copyMetadata)
    {
        for(it in template.pipeMetadata)
        {
            newPipe.pipeMetadata[it.key] = it.value
        }
    }
    
    if(copyPipes)
    {
        template.validatorPipe?.let { newPipe.validatorPipe = it }
        template.transformationPipe?.let { newPipe.transformationPipe = it }
        template.branchPipe?.let { newPipe.branchPipe = it }
    }
    
    return newPipe as? T
}


/**
 * Returns the user's home folder. Or the document folder on Windows.
 * This function expects that standard naming conventions are used.
 * Bizzare non-standard naming conventions are not supported, and it's the user's fault
 * if they do not comply.
 */
fun getHomeFolder(): File {
    val os = System.getProperty("os.name")
    return if (os.contains("Windows")) {
        File(System.getenv("USERPROFILE"))
    } else {
        File(System.getProperty("user.home"))
    }
}


/**
 * Copy file in one directory to another. Only unix is supported.
 * @param starPath The path of the file to copy.
 * @param destPath The path to copy the file to.
 */
fun copyFile(starPath : String, destPath : String) {

    try {
        val targetFile = File(destPath)
        val sourceFile = File(starPath)
        sourceFile.copyTo(targetFile, overwrite = false)
    } catch (e: NoSuchFileException) {
        println("Error: Source file not found: ${e.message}")
    } catch (e: FileAlreadyExistsException) {
        println("Error: Destination file already exists: ${e.message}")
    } catch (e: FileSystemException) {
        println("Error: Failed to create target directory: ${e.message}")
    } catch (e: IOException) {
        println("Error: I/O error occurred: ${e.message}")
    } catch (e: Exception) {
        println("Unexpected error: ${e.message}")
    }

}



/**
 * Copy directory in one directory to another. Only unix is supported.
 * @param starPath The path of the directory to copy.
 * @param destPath The path to copy the directory to.
 */
fun copyDir(starPath : String, destPath : String) {
    //File(starPath).copyRecursively(File(destPath), true)

    File(starPath).copyRecursively(
        File(destPath),
        overwrite = true,
        onError = { file, exception ->
            when (exception) {
                is AccessDeniedException -> {
                    println("Error copying $file: Permission denied")
                    OnErrorAction.SKIP
                }
                is IOException -> {
                    println("Error copying $file: I/O error: ${exception.message}")
                    OnErrorAction.SKIP // Or consider retrying with a delay
                }
                is SecurityException -> {
                    println("Error copying $file: Security violation: ${exception.message}")
                    OnErrorAction.SKIP // Or log and skip, depending on the severity
                }
                else -> {
                    println("Unexpected error copying $file: ${exception.message}")
                    OnErrorAction.SKIP // Log the error and terminate to prevent further issues
                }
            }
        }
    )
}



fun deleteDir(path : String)
{
    try
    {
        File(path).deleteRecursively()
    }
    catch (e : AccessDeniedException)
    {
        println("Error deleting $path: Permission denied")
        OnErrorAction.SKIP
    }
    catch (e : IOException)
    {
        println("Error deleting $path: I/O error: ${e.message}")
        OnErrorAction.SKIP
    }
    catch (e : SecurityException)
    {
        println("Error deleting $path: Security violation: ${e.message}")
        OnErrorAction.SKIP
    }
    catch (e : Exception)
    {
        println("Unexpected error deleting $path: ${e.message}")
        OnErrorAction.SKIP
    }

}


/**
 * Execute a bash command.
 * @param command The command to execute. Does not pipe buffer output. Will stall the thread until
 * the command has finished.
 * @return The exit value of the command.
 */
fun executeBashCommand(command : String) : Int
{
    val process = ProcessBuilder(*command.split("\\s+".toRegex()).toTypedArray())
        .inheritIO()
        .start()
    process.waitFor()
    return process.exitValue()
}


/**
 * Find a file cascading up the directory tree. This is required because we don't know where the program's working dir
 * is compared to where our target file might be up above. This can even vary from running as jar, or as gradlew, or even
 * as a docker container.
 * @param path The path to start from.
 * @return The file found.
 */
fun findFileCascading(path : String) : File
{
    var mutablePath = path
    val maxIterations = 10
    var iterations = 0

    while (!File(mutablePath).exists())
    {
        mutablePath = "../$mutablePath"

        if(iterations > maxIterations)
        {
            return File("") //Return empty if we exceed the max limit.
        }

        iterations++
    }

    return File(mutablePath)
}


//Get the program's working directory
fun getWorkingDirectory() : String
{
    return File(".").absolutePath.removeSuffix(".")
}


/**
 * Write a string to a file with a Unix filepath.
 *
 * @param filepath The Unix filepath to write to.
 * @param content The string to write to the file.
 */
fun writeStringToFile(filepath: String, content: String) {

    if(!File(filepath).exists())
    {
        val file = File(filepath)
        val split = filepath.split("/").toMutableList()
        split.removeAt(split.lastIndex)
        var folderPath = ""

        for(i in split)
        {
            folderPath = "$folderPath$i/"
        }

        //Remove extra trailing / on the path.
        folderPath = folderPath.removeSuffix("/")
        val dir = File(folderPath)

        if(!dir.exists())
        {
            val result = dir.mkdirs()
            if(!result)
            {
                throw error("Unable to load config file because we can't create the main directory to store it in" +
                        " in writeStringToFile @ Util.kt")
                return
            }
        }


    }
    File(filepath).writeText(content)
}



/**
 * Read a string from a file with a Unix filepath.
 *
 * @param filepath The Unix filepath to read from.
 *
 * @return The string read from the file.
 */
fun readStringFromFile(filepath: String): String {

    if(File(filepath).exists() && File(filepath).isFile && File(filepath).canRead())
    {
        return File(filepath).readText()
    }

    return ""

}


/**
 * Launch a program with a given path and arguments.
 * All inputs and outputs will be redirected.
 * The new program will block until it finishes.
 * @param path The path to the program to launch.
 * @param args The arguments to pass to the program.
 */
fun launchProgram(path : MutableList<String>)
{

    //Launch the program and block our process until it finishes.
    val builder = ProcessBuilder(path)
    builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    builder.redirectError(ProcessBuilder.Redirect.INHERIT)
    builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
    //println(builder.command())
    val process = builder.start()
    process.waitFor()
}


/**
 * Data class to hold the result of an async program execution.
 * 
 * @param exitCode The exit code returned by the program.
 * @param output The combined stdout and stderr output as a string.
 * @param success Whether the program executed successfully (exit code 0).
 */
data class ProgramResult(
    val exitCode: Int,
    val output: String,
    val success: Boolean = exitCode == 0
)

/**
 * Launch a program asynchronously with output capture.
 * Returns a Deferred that can be awaited or run in the background.
 * All program output (stdout and stderr) is captured and returned as a string.
 * 
 * @param path The list containing the program path and arguments.
 * @return A Deferred<ProgramResult> that can be awaited for the result.
 */
suspend fun launchProgramAsync(path: MutableList<String>): Deferred<ProgramResult> = coroutineScope {
    async(Dispatchers.IO) {
        try {
            val builder = ProcessBuilder(path)
            builder.redirectErrorStream(true) // Combine stderr with stdout
            val process = builder.start()
            
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            // Read output in a separate coroutine to avoid blocking
            val outputJob = async {
                reader.use { r ->
                    r.lineSequence().forEach { line ->
                        output.appendLine(line)
                    }
                }
            }
            
            // Wait for process completion
            val exitCode = withContext(Dispatchers.IO) {
                process.waitFor()
            }
            
            // Ensure output reading is complete
            outputJob.await()
            
            ProgramResult(exitCode, output.toString())
        } catch (e: Exception) {
            ProgramResult(-1, "Error executing program: ${e.message}")
        }
    }
}

/**
 * Launch a program asynchronously and immediately await the result.
 * Convenience function for when you want async execution but need to wait for completion.
 * 
 * @param path The list containing the program path and arguments.
 * @return The ProgramResult containing exit code and captured output.
 */
suspend fun launchProgramAwait(path: MutableList<String>): ProgramResult
{
    return launchProgramAsync(path).await()
}


/**
 * Get the line ending of a string.
 * @param fileString The string to get the line ending from.
 * @return The line ending of the string.
 */
fun getLineEnding(fileString : String) : LineEnding
{
    if(fileString.contains("\r\n"))
    {
        return LineEnding.Windows
    }
    else if(fileString.contains("\n"))
    {
        return LineEnding.Unix
    }
    else if(fileString.contains("\r"))
    {
        return LineEnding.Mac
    }
    else
    {
        return LineEnding.Unknown
    }
}


fun convertLineEnding(filePath: File) : Boolean
{
    val fileString = filePath.readText()
    val lineEnding = getLineEnding(fileString)

    if(lineEnding == LineEnding.Unknown)
    {
        println("Unknown line ending found @ convertLineEnding in Util.kt")
        return false
    }

    if(lineEnding == LineEnding.Mac)
    {
        filePath.writeText(fileString.replace("\r", "\n"))
        println("Converting mac line endings in $filePath")
        return true
    }

    return true //Valid if unix or windows already.
}



/**
 * Return a list of directories in a given directory recursively.
 * @param root The root directory to start from.
 * @return A list of directories in the root directory.
 */
fun getDirRecursive(root : File) : List<File>
{
    return root.walk().toList()
}


/**
 * Return the os that unreal editor is running on.
 * @since This is only used for the editor. Package platforms such as ios and android are not supported.
 * when setting up build strings for those platforms you need to type them directly into the string formatter.
 */
fun getOs() : String
{
    val os = System.getProperty("os.name").lowercase()
    return when (os) {
        "linux" -> "Linux"
        "mac os x" -> "Mac"
        else -> "Win64"
    }
}


/**
 * Splits a path string stored in the config file back into two separate strings.
 * @return A map containing the path and arguments. The key is the path to the program and the value is the arguments.
 * This is required because ProcessBuilder does not support passing arguments as a single string.
 */
fun splitProgramString(programString: String): MutableList<String>
{
    val first = programString.split(Regex("(?<!\\\\)\\s+")).toSet().toCollection(ArrayList()).toMutableList()
    val newList = mutableListOf<String>()
    for(i in first)
    {
        newList.add(i)
    }

    return newList

}


/**
 * Used to parse through any remaining arguments. Gather all of them and then push them back as a string.
 * This is useful specifically for extra build flags you want to pass into UAT.
 * @param args The list of arguments to parse through.
 * @param index The index to start at.
 */
fun getStringFromArgsbyIndex(args : List<String>, index : Int) : String
{
    var output = ""

    for(i in index until args.size)
    {
        output = "$output ${args[i]}"
    }

    return output
}


fun clearScreen()
{
    when (System.getProperty("os.name")) {
        "Linux", "Mac OS X" -> Runtime.getRuntime().exec("clear")
        "Windows" -> Runtime.getRuntime().exec("cls")
        else -> println("Unsupported operating system")
    }
}



/**
 * Find a string inside a list. Matches if an element contains any part of the substring.
 * @param list The list to search in.
 * @param target The string to search for.
 * @return The index of the string in the list, or -1 if not found.
 */
fun findSubStringInList(list : List<String>, target : String) : Int
{
    var index = 0

    for(i in list.indices)
    {
        if(list[i].contains("$target"))
        {
            return index;
        }

        index += 1
    }

    return -1
}


/**
 * Find a string inside a list, and return that string. Matches if an element contains any part of the substring.
 * @param list The list to search in.
 * @param value The string to search for.
 * @return The string in the list that matches, or an empty string if not found.
 */
fun getSubStringInList(list : List<String>, value : String) : String
{
    val index = com.TTT.Util.findSubStringInList(list, value)

    if(index != -1)
    {
        return list[index]
    }

    return ""
}


/**
 * Combine one list into another preventing any duplicate values from being added in.
 */
fun combine(listA: List<String>, listB: List<String>) : List<String>
{
    val mergeList : MutableList<String> = listA.toMutableList()

    for(i in listB)
    {
        if(!mergeList.contains(i))
        {
            mergeList.add(i)
        }
    }

    return mergeList.toList()
}

/**
 * Copies an entire pipeline by iterating through all pipes and using constructPipeFromTemplate()
 * to fully copy each pipe and rebuild a brand new pipeline.
 *
 * @param originalPipeline The pipeline to copy.
 * @param copyFunctions Whether to copy function references from the original pipes.
 * @param copyPipes Whether to copy pipe references from the original pipes.
 * @return A new pipeline that is a complete copy of the original.
 */
fun copyPipeline(originalPipeline: Pipeline, copyFunctions: Boolean = false, copyPipes: Boolean = false): Pipeline
{
    val newPipeline = Pipeline()

    // Copy pipeline-level properties
    newPipeline.setPipelineName(originalPipeline.pipelineName)
    newPipeline.context = originalPipeline.context

    // Copy all pipes using constructPipeFromTemplate
    for (originalPipe in originalPipeline.getPipes())
    {
        val copiedPipe = constructPipeFromTemplate<Pipe>(originalPipe, copyFunctions, copyPipes)
        if (copiedPipe != null)
        {
            newPipeline.add(copiedPipe)
        }
    }

    return newPipeline
}


/**
 * Finds the smallest context window size from a list of pipes. This is often necessary when mixing different llm
 * models together. In those cases prompts would need to be truncated to fit in the given space allotted.
 *
 * @param pipes The list of pipes to search through.
 * @return The smallest context window size found.
 */
fun getLowestContextWindowSize(pipes:  List<Pipe>) : Int
{
    var lowest = Int.MAX_VALUE
    for(pipe in pipes)
    {
        val pipeSettings = pipe.toPipeSettings()
        val contextWindowSize = pipeSettings.contextWindowSize
        if(contextWindowSize < lowest)
        {
            lowest = contextWindowSize
        }
    }

    return lowest
}


/**
 * Removes the first occurrence of a target string and everything after it from the input string.
 * 
 * @param input The input string to process
 * @param target The target string to find and remove (along with everything after it)
 * @return The input string with the target and everything after it removed, or the original string if target not found
 */
fun removeFromFirstOccurrence(input: String, target: String): String {
    val index = input.indexOf(target)
    return if (index != -1) {
        input.substring(0, index)
    } else {
        input
    }
}


