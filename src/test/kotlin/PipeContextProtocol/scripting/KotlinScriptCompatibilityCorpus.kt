package com.TTT.PipeContextProtocol.scripting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One deterministic Kotlin script used by the backend compatibility corpus. */
data class KotlinScriptCase(
    val id: String,
    val source: String,
    val mode: String
)

/** Canonical, duration-independent result retained from the Kotlin 2.3 path. */
data class KotlinScriptGolden(
    val id: String,
    val success: Boolean,
    val output: String,
    val stdout: String,
    val stderr: String,
    val errorPrefix: String? = null
)

/** Loads the committed script cases and canonical outcomes. */
object KotlinScriptCompatibilityCorpus
{
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the committed deterministic script cases. */
    fun cases(): List<KotlinScriptCase>
    {
        return casesFromResource("PipeContextProtocol/kotlin-scripting/cases.json")
    }

    /** Returns canonical outcomes with execution time and compiler noise omitted. */
    fun goldens(): List<KotlinScriptGolden>
    {
        return loadObjects("PipeContextProtocol/kotlin-scripting/jsr223-2.3.21-golden.json") { objectValue ->
            KotlinScriptGolden(
                id = objectValue.getValue("id").jsonPrimitive.content,
                success = objectValue.getValue("success").jsonPrimitive.boolean,
                output = objectValue.getValue("output").jsonPrimitive.content,
                stdout = objectValue.getValue("stdout").jsonPrimitive.content,
                stderr = objectValue.getValue("stderr").jsonPrimitive.content,
                errorPrefix = objectValue["errorPrefix"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    private fun casesFromResource(resource: String): List<KotlinScriptCase>
    {
        return loadObjects(resource) { objectValue ->
            KotlinScriptCase(
                id = objectValue.getValue("id").jsonPrimitive.content,
                source = objectValue.getValue("source").jsonPrimitive.content,
                mode = objectValue.getValue("mode").jsonPrimitive.content
            )
        }
    }

    private fun <T> loadObjects(resource: String, factory: (Map<String, kotlinx.serialization.json.JsonElement>) -> T): List<T>
    {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
            "Missing compatibility corpus resource: $resource"
        }
        return stream.use { input ->
            json.parseToJsonElement(input.reader().readText()).jsonArray.map { factory(it.jsonObject) }
        }
    }
}
