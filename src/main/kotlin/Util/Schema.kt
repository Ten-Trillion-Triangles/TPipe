@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.TTT.Util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * JSON Schema Generator for Kotlin Serialization
 * 
 * Generates JSON Schema Draft 2020-12 compliant schemas from Kotlin serializable classes.
 * Supports all Kotlin serialization features including nullable types, sealed classes,
 * polymorphic types, and complex nested structures.
 */

/**
 * Configuration options for JSON schema generation.
 * 
 * @param schemaDialect The JSON Schema specification version to use
 * @param classDiscriminator The discriminator field name for polymorphic types (must match Json config)
 * @param structuredMapKeys Whether to use structured map keys (array of key-value objects vs object with string keys)
 */
data class SchemaOptions(
    val schemaDialect: String = "https://json-schema.org/draft/2020-12/schema",
    val classDiscriminator: String = "type",  // match your Json { classDiscriminator = "..." }
    val structuredMapKeys: Boolean = false    // match your Json { allowStructuredMapKeys = true }
)

/**
 * Generates JSON Schema from Kotlin serializable classes.
 * 
 * This generator introspects Kotlin serialization descriptors to produce accurate JSON schemas
 * that can be used by LLMs or other systems to generate valid JSON that deserializes correctly.
 * 
 * Key features:
 * - Handles all Kotlin primitive types with appropriate JSON Schema types
 * - Supports nullable types using union types or anyOf constructs
 * - Generates proper $ref definitions for reusable complex types
 * - Handles sealed classes with discriminator patterns
 * - Supports polymorphic types and inheritance
 * - Manages circular references through definition tracking
 * 
 * @param module SerializersModule for resolving custom serializers
 * @param options Configuration options for schema generation
 */
class JsonSchemaGenerator(
    private val module: SerializersModule = EmptySerializersModule(),
    private val options: SchemaOptions = SchemaOptions()
) {
    /** Cache of generated type definitions to avoid duplication and handle circular references */
    private val defs = mutableMapOf<String, JsonObject>()
    
    /** Set of types currently being built to detect and prevent infinite recursion */
    private val building = mutableSetOf<String>()
    private val enumHints = linkedMapOf<String, List<String>>()
    private val pathStack = ArrayDeque<String>()

    /**
     * Generates a complete JSON Schema from a serializer.
     * 
     * This is the main entry point that produces a full JSON Schema document with
     * $schema, optional $id, root type definition, and $defs section for reusable types.
     * 
     * @param serializer The KSerializer for the root type
     * @param id Optional schema ID for the generated schema
     * @return Complete JSON Schema as JsonObject
     */
    fun <T> generate(serializer: KSerializer<T>, id: String? = null): JsonObject {
        // Clear state for fresh generation
        defs.clear(); building.clear()
        
        // Generate the root schema structure
        val root = schemaForDescriptor(serializer.descriptor)
        
        // Build the complete schema document
        return buildJsonObject {
            put("\$schema", options.schemaDialect)
            id?.let { put("\$id", it) }
            
            // Merge root schema properties
            for ((k, v) in root) put(k, v)
            
            // Add definitions section if any complex types were encountered
            if (defs.isNotEmpty()) put("\$defs", JsonObject(defs))
        }
    }

    /**
     * Generates a simplified JSON Schema that inlines all definitions for better LLM compatibility.
     * This produces schemas that are more likely to generate correct JSON objects.
     * 
     * @param serializer The KSerializer for the root type
     * @param id Optional schema ID for the generated schema
     * @return Simplified JSON Schema as JsonObject
     */
    fun <T> generateInlined(serializer: KSerializer<T>, id: String? = null): JsonObject {
        // Clear state for fresh generation
        defs.clear(); building.clear()
        
        // Generate the root schema structure with inlining
        val root = schemaForDescriptorInlined(serializer.descriptor)
        
        // Build the complete schema document without $defs
        return buildJsonObject {
            put("\$schema", options.schemaDialect)
            id?.let { put("\$id", it) }
            
            // Merge root schema properties
            for ((k, v) in root) put(k, v)
        }
    }

    /**
     * Generates actual example JSON data showing the expected structure.
     * This is the most LLM-friendly format as it shows exactly what the JSON should look like.
     * 
     * @param serializer The KSerializer for the root type
     * @return Example JSON as JsonObject
     */
    /**
     * Container that holds the generated example JSON along with any enum legend metadata.
     */
    data class ExampleGenerationResult(
        val example: JsonObject,
        val enumLegend: Map<String, List<String>>
    )

    /**
     * Convenience overload that keeps backwards compatibility with existing callers that only need
     * the JSON example. Use [generateExampleWithLegend] when enum guidance is also required.
     */
    fun <T> generateExample(serializer: KSerializer<T>): JsonObject =
        generateExampleWithLegend(serializer).example

    /**
     * Generates example JSON and captures the available enum values for every field encountered.
     */
    fun <T> generateExampleWithLegend(serializer: KSerializer<T>): ExampleGenerationResult {
        building.clear()
        enumHints.clear()
        pathStack.clear()

        val exampleJson = exampleForDescriptor(serializer.descriptor) as JsonObject
        return ExampleGenerationResult(exampleJson, LinkedHashMap(enumHints))
    }

    /**
     * Renders the example JSON and appends an enum legend two line breaks below the schema when
     * enum values are present. This format is LLM friendly: the legend clarifies the valid values
     * without altering the JSON structure itself.
     */
    fun formatExampleWithLegend(
        result: ExampleGenerationResult,
        prettyPrint: Boolean = true
    ): String {
        val jsonFormatter = Json {
            this.prettyPrint = prettyPrint
            encodeDefaults = true
        }

        val exampleString = jsonFormatter.encodeToString(JsonObject.serializer(), result.example)

        if (result.enumLegend.isEmpty()) {
            return exampleString
        }

        val legendText = buildString {
            append("Enum Legend:\n")
            result.enumLegend.forEach { (path, values) ->
                append("- ")
                append(path)
                append(": ")
                append(values.joinToString(" | "))
                append('\n')
            }
        }.trimEnd()

        return buildString {
            append(exampleString.trimEnd())
            append("\n\n")
            append(legendText)
        }
    }

    /**
     * Detects whether the supplied serializer's descriptor tree contains any enum values.
     */
    fun <T> containsEnums(serializer: KSerializer<T>): Boolean =
        descriptorContainsEnum(serializer.descriptor, mutableSetOf())

    /**
     * Generates example JSON data showing the expected structure.
     */
    private fun exampleForDescriptor(desc: SerialDescriptor): JsonElement {
        if (desc.isNullable) {
            return exampleForDescriptor(desc.nonNullOriginal)
        }

        return when (desc.kind) {
            is PrimitiveKind -> when (desc.kind) {
                PrimitiveKind.STRING, PrimitiveKind.CHAR -> JsonPrimitive("example_string")
                PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> JsonPrimitive(0)
                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> JsonPrimitive(0.0)
                PrimitiveKind.BOOLEAN -> JsonPrimitive(false)
                else -> JsonPrimitive("example_string")
            }
            SerialKind.ENUM -> {
                registerEnumHint(currentPath(), desc)
                val enumValue = if (desc.elementsCount > 0) desc.getElementName(0) else "ENUM_VALUE"
                JsonPrimitive(enumValue)
            }
            StructureKind.LIST -> {
                val elementDescriptor = desc.getElementDescriptor(0)
                if (elementDescriptor.kind == SerialKind.ENUM) {
                    registerEnumHint(listPath(), elementDescriptor)
                }
                pathStack.addLast("[]")
                val elementExample = exampleForDescriptor(elementDescriptor)
                pathStack.removeLast()
                JsonArray(listOf(elementExample))
            }
            StructureKind.MAP -> buildJsonObject {
                put("example_key", exampleForDescriptor(desc.getElementDescriptor(1)))
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val name = sanitize(desc.serialName)
                if (name in building) {
                    return buildJsonObject {}
                }
                building += name
                val result = buildJsonObject {
                    for (i in 0 until desc.elementsCount) {
                        val propName = desc.getElementName(i)
                        val child = desc.getElementDescriptor(i)
                        pathStack.addLast(propName)
                        put(propName, exampleForDescriptor(child))
                        pathStack.removeLast()
                    }
                }
                building -= name
                result
            }
            else -> buildJsonObject {}
        }
    }

    private fun currentPath(): String = pathStack.joinToString(".")

    private fun listPath(): String {
        val base = currentPath()
        return if (base.isBlank()) "[]" else "$base[]"
    }

    private fun registerEnumHint(path: String, descriptor: SerialDescriptor) {
        if (descriptor.kind != SerialKind.ENUM || descriptor.elementsCount == 0) return
        val normalized = path
            .replace(".[]", "[]")
            .ifBlank { "<root>" }
        enumHints.putIfAbsent(normalized, (0 until descriptor.elementsCount).map { descriptor.getElementName(it) })
    }

    private fun descriptorContainsEnum(desc: SerialDescriptor, visited: MutableSet<String>): Boolean {
        if (desc.isNullable) {
            return descriptorContainsEnum(desc.nonNullOriginal, visited)
        }

        return when (desc.kind) {
            SerialKind.ENUM -> true
            is PrimitiveKind -> false
            StructureKind.LIST -> descriptorContainsEnum(desc.getElementDescriptor(0), visited)
            StructureKind.MAP -> descriptorContainsEnum(desc.getElementDescriptor(0), visited) ||
                descriptorContainsEnum(desc.getElementDescriptor(1), visited)
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val name = desc.serialName
                if (!visited.add(name)) {
                    false
                } else {
                    val contains = (0 until desc.elementsCount).any { index ->
                        descriptorContainsEnum(desc.getElementDescriptor(index), visited)
                    }
                    visited.remove(name)
                    contains
                }
            }
            is PolymorphicKind.SEALED, is PolymorphicKind.OPEN -> {
                (0 until desc.elementsCount).any { index ->
                    descriptorContainsEnum(desc.getElementDescriptor(index), visited)
                }
            }
            else -> false
        }
    }

    /**
     * Generates a JSON Schema from a KClass (convenience method).
     * 
     * Uses the serialization module to resolve the serializer for the given class.
     * Note: This doesn't support generic types on the root level.
     * 
     * @param kclass The Kotlin class to generate schema for
     * @param id Optional schema ID
     * @return Complete JSON Schema as JsonObject
     */
    fun generate(kclass: KClass<*>, id: String? = null): JsonObject {
        // Resolve serializer using the official runtime API
        val ser = module.serializer(kclass, emptyList(), isNullable = false)
        return generate(ser, id)
    }

    // =============== Core Schema Generation Logic ===============

    /**
     * Core method that converts a SerialDescriptor into a JSON Schema object.
     * 
     * This is the heart of the schema generation process. It analyzes the descriptor's
     * kind and properties to determine the appropriate JSON Schema representation.
     * Handles nullable types, primitive types, collections, objects, and polymorphic types.
     * 
     * @param desc The SerialDescriptor to convert
     * @param inlineNullable Whether to inline nullable types as union types vs anyOf
     * @return JSON Schema representation of the descriptor
     */
    private fun schemaForDescriptor(desc: SerialDescriptor, inlineNullable: Boolean = true): JsonObject {
        // Handle nullable types first - they wrap the underlying type
        if (desc.isNullable) {
            // Get schema for the non-null version
            val nonNull = schemaForDescriptor(desc.nonNullOriginal, inlineNullable = true)
            
            // For simple types, create union type ["string", "null"]
            return if (inlineNullable && nonNull["type"] is JsonPrimitive) {
                val t = nonNull["type"]!!
                buildJsonObject {
                    // Copy all properties except "type"
                    for ((k, v) in nonNull) if (k != "type") put(k, v)
                    
                    // Create union type with null
                    put("type", when (t) {
                        is JsonPrimitive -> JsonArray(listOf(t, JsonPrimitive("null")))
                        is JsonArray -> JsonArray(t + JsonPrimitive("null"))  // Already a union
                        else -> JsonPrimitive("null")
                    })
                }
            } else {
                // For complex types, use anyOf construct
                buildJsonObject {
                    put("anyOf", buildJsonArray {
                        add(JsonObject(nonNull))
                        add(buildJsonObject { put("type", "null") })
                    })
                }
            }
        }

        // Dispatch to appropriate schema generator based on descriptor kind
        return when (desc.kind) {
            is PrimitiveKind -> primitiveSchema(desc)
            SerialKind.ENUM -> enumSchema(desc)
            // Detect Set types by name since StructureKind.SET may not be available
            StructureKind.LIST -> arraySchema(desc, uniqueItems = desc.serialName.contains("Set"))
            StructureKind.MAP -> mapSchema(desc)
            StructureKind.CLASS, StructureKind.OBJECT -> classSchema(desc)
            is PolymorphicKind.SEALED -> sealedSchema(desc)
            is PolymorphicKind.OPEN -> polymorphicOpenSchema()
            // Fallback for unknown types
            else -> buildJsonObject { put("type", "object") }
        }
    }

    /**
     * Inlined version of schemaForDescriptor that doesn't use $ref definitions.
     * 
     * @param desc The SerialDescriptor to convert
     * @param inlineNullable Whether to inline nullable types as union types vs anyOf
     * @return JSON Schema representation of the descriptor
     */
    private fun schemaForDescriptorInlined(desc: SerialDescriptor, inlineNullable: Boolean = true): JsonObject {
        // Handle nullable types first - they wrap the underlying type
        if (desc.isNullable) {
            // Get schema for the non-null version
            val nonNull = schemaForDescriptorInlined(desc.nonNullOriginal, inlineNullable = true)
            
            // For simple types, create union type ["string", "null"]
            return if (inlineNullable && nonNull["type"] is JsonPrimitive) {
                val t = nonNull["type"]!!
                buildJsonObject {
                    // Copy all properties except "type"
                    for ((k, v) in nonNull) if (k != "type") put(k, v)
                    
                    // Create union type with null
                    put("type", when (t) {
                        is JsonPrimitive -> JsonArray(listOf(t, JsonPrimitive("null")))
                        is JsonArray -> JsonArray(t + JsonPrimitive("null"))  // Already a union
                        else -> JsonPrimitive("null")
                    })
                }
            } else {
                // For complex types, use anyOf construct
                buildJsonObject {
                    put("anyOf", buildJsonArray {
                        add(JsonObject(nonNull))
                        add(buildJsonObject { put("type", "null") })
                    })
                }
            }
        }

        // Dispatch to appropriate schema generator based on descriptor kind
        return when (desc.kind) {
            is PrimitiveKind -> primitiveSchema(desc)
            SerialKind.ENUM -> enumSchema(desc)
            // Detect Set types by name since StructureKind.SET may not be available
            StructureKind.LIST -> arraySchemaInlined(desc, uniqueItems = desc.serialName.contains("Set"))
            StructureKind.MAP -> mapSchemaInlined(desc)
            StructureKind.CLASS, StructureKind.OBJECT -> classSchemaInlined(desc)
            is PolymorphicKind.SEALED -> sealedSchemaInlined(desc)
            is PolymorphicKind.OPEN -> polymorphicOpenSchema()
            // Fallback for unknown types
            else -> buildJsonObject { put("type", "object") }
        }
    }

    /**
     * Generates JSON Schema for primitive Kotlin types.
     * 
     * Maps Kotlin primitive types to their JSON Schema equivalents and adds
     * format constraints for well-known types like dates and UUIDs.
     * 
     * @param desc SerialDescriptor for a primitive type
     * @return JSON Schema object with type and optional format/constraints
     */
    private fun primitiveSchema(desc: SerialDescriptor): JsonObject = buildJsonObject {
        // Map Kotlin primitive kinds to JSON Schema types
        val t = when (desc.kind) {
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
            PrimitiveKind.BOOLEAN -> "boolean"
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
            else -> "string"  // Safe fallback
        }
        put("type", t)
        
        // Add format constraints and validation rules for specific types
        when (desc.serialName) {
            "kotlin.Char" -> { 
                put("minLength", 1)
                put("maxLength", 1) 
            }
            "kotlinx.datetime.Instant", "java.time.Instant" -> put("format", "date-time")
            "kotlinx.datetime.LocalDate", "java.time.LocalDate" -> put("format", "date")
            "kotlinx.uuid.Uuid", "java.util.UUID" -> put("format", "uuid")
        }
    }

    /**
     * Generates JSON Schema for Kotlin enum types.
     * 
     * Creates a string type with enum constraint listing all possible values.
     * 
     * @param desc SerialDescriptor for an enum type
     * @return JSON Schema object with string type and enum constraint
     */
    private fun enumSchema(desc: SerialDescriptor): JsonObject = buildJsonObject {
        put("type", "string")
        
        // Extract all enum value names
        val names = (0 until desc.elementsCount).map { desc.getElementName(it) }
        put("enum", JsonArray(names.map(::JsonPrimitive)))
    }

    /**
     * Generates JSON Schema for array/list types.
     * 
     * Creates an array schema with items constraint. Supports unique items constraint
     * for Set-like collections.
     * 
     * @param desc SerialDescriptor for a list/array type
     * @param uniqueItems Whether to add uniqueItems constraint (for Sets)
     * @return JSON Schema object for array type
     */
    private fun arraySchema(desc: SerialDescriptor, uniqueItems: Boolean = false): JsonObject = buildJsonObject {
        put("type", "array")
        
        // Generate schema for the element type (index 0 is the element type)
        put("items", schemaForDescriptor(desc.getElementDescriptor(0)))
        
        // Add uniqueItems constraint for Set types
        if (uniqueItems) put("uniqueItems", true)
    }

    /**
     * Inlined version of arraySchema.
     */
    private fun arraySchemaInlined(desc: SerialDescriptor, uniqueItems: Boolean = false): JsonObject = buildJsonObject {
        put("type", "array")
        
        // Generate schema for the element type (index 0 is the element type)
        put("items", schemaForDescriptorInlined(desc.getElementDescriptor(0)))
        
        // Add uniqueItems constraint for Set types
        if (uniqueItems) put("uniqueItems", true)
    }

    /**
     * Generates JSON Schema for Map types.
     * 
     * Handles two serialization modes:
     * 1. Standard mode: JSON object with string keys and typed values
     * 2. Structured keys mode: Array of {key, value} objects for complex key types
     * 
     * @param desc SerialDescriptor for a map type
     * @return JSON Schema object representing the map
     */
    private fun mapSchema(desc: SerialDescriptor): JsonObject {
        val keyDesc = desc.getElementDescriptor(0)    // Map key type
        val valueDesc = desc.getElementDescriptor(1)  // Map value type

        // Check if we need structured key representation
        if (options.structuredMapKeys && keyDesc.kind !is PrimitiveKind && keyDesc.kind != SerialKind.ENUM) {
            // Complex key types require array-of-objects representation
            // Matches Json { allowStructuredMapKeys = true } serialization
            return buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("key", schemaForDescriptor(keyDesc))
                        put("value", schemaForDescriptor(valueDesc))
                    })
                    put("required", JsonArray(listOf("key","value").map(::JsonPrimitive)))
                    put("additionalProperties", JsonPrimitive(false))
                })
            }
        }

        // Standard JSON object representation with string keys
        return buildJsonObject {
            put("type", "object")
            // Value type goes in additionalProperties since we don't know the keys
            put("additionalProperties", schemaForDescriptor(valueDesc))
            // Add key constraints if the key type has restrictions
            mapKeyConstraint(keyDesc)?.let { put("propertyNames", it) }
        }
    }

    /**
     * Inlined version of mapSchema.
     */
    private fun mapSchemaInlined(desc: SerialDescriptor): JsonObject {
        val keyDesc = desc.getElementDescriptor(0)    // Map key type
        val valueDesc = desc.getElementDescriptor(1)  // Map value type

        // Check if we need structured key representation
        if (options.structuredMapKeys && keyDesc.kind !is PrimitiveKind && keyDesc.kind != SerialKind.ENUM) {
            // Complex key types require array-of-objects representation
            // Matches Json { allowStructuredMapKeys = true } serialization
            return buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("key", schemaForDescriptorInlined(keyDesc))
                        put("value", schemaForDescriptorInlined(valueDesc))
                    })
                    put("required", JsonArray(listOf("key","value").map(::JsonPrimitive)))
                    put("additionalProperties", JsonPrimitive(false))
                })
            }
        }

        // Standard JSON object representation with string keys
        return buildJsonObject {
            put("type", "object")
            // Value type goes in additionalProperties since we don't know the keys
            put("additionalProperties", schemaForDescriptorInlined(valueDesc))
            // Add key constraints if the key type has restrictions
            mapKeyConstraint(keyDesc)?.let { put("propertyNames", it) }
        }
    }

    /**
     * Generates property name constraints for map keys.
     * 
     * When maps are serialized as JSON objects, the keys become property names.
     * This method creates validation rules for those property names based on the key type.
     * 
     * @param keyDesc SerialDescriptor for the map key type
     * @return JSON Schema constraint for property names, or null if no constraint needed
     */
    private fun mapKeyConstraint(keyDesc: SerialDescriptor): JsonObject? = when {
        // Enum keys: restrict to enum values
        keyDesc.kind == SerialKind.ENUM -> {
            val names = (0 until keyDesc.elementsCount).map { keyDesc.getElementName(it) }
            buildJsonObject { 
                put("type", "string")
                put("enum", JsonArray(names.map(::JsonPrimitive))) 
            }
        }
        // Primitive keys: add format constraints where applicable
        keyDesc.kind is PrimitiveKind && keyDesc.serialName.startsWith("kotlin.") -> when (keyDesc.kind) {
            PrimitiveKind.BOOLEAN -> buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(listOf(JsonPrimitive("true"), JsonPrimitive("false"))))
            }
            // Numeric keys: must match number pattern when serialized as strings
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE ->
                buildJsonObject { 
                    put("type", "string")
                    put("pattern", "^-?\\d+$") 
                }
            else -> null
        }
        else -> null
    }

    /**
     * Generates JSON Schema for class/object types.
     * 
     * Uses $ref definitions for reusable types to avoid duplication and handle
     * circular references. Tracks building state to prevent infinite recursion.
     * 
     * @param desc SerialDescriptor for a class or object type
     * @return JSON Schema object, either inline definition or $ref to definition
     */
    private fun classSchema(desc: SerialDescriptor): JsonObject {
        val name = defName(desc)
        
        if (name != null) {
            // Check if we need to generate the definition
            if (name !in defs && name !in building) {
                // Mark as building to prevent infinite recursion
                building += name
                
                // Generate the definition and store it
                defs[name] = buildClassDefinition(desc)
                
                // Remove from building set
                building -= name
            }
            
            // Return reference to the definition
            return buildJsonObject { put("\$ref", "#/\$defs/$name") }
        }
        
        // For types that don't get definitions, inline the schema
        return buildClassDefinition(desc)
    }

    /**
     * Generates inlined JSON Schema for class/object types without $ref.
     * 
     * @param desc SerialDescriptor for a class or object type
     * @return JSON Schema object with inlined definition
     */
    private fun classSchemaInlined(desc: SerialDescriptor): JsonObject {
        val name = sanitize(desc.serialName)
        
        // Prevent infinite recursion by checking if we're already building this type
        if (name in building) {
            // Return a simple object schema to break the cycle
            return buildJsonObject { 
                put("type", "object")
                put("additionalProperties", JsonPrimitive(true))
            }
        }
        
        // Mark as building and generate inline definition
        building += name
        val result = buildClassDefinition(desc, inline = true)
        building -= name
        
        return result
    }

    /**
     * Builds the actual JSON Schema definition for a class/object.
     * 
     * Creates an object schema with:
     * - Properties for each serializable field
     * - Required array for non-optional, non-nullable fields
     * - additionalProperties: false for strict validation
     * 
     * @param desc SerialDescriptor for the class/object
     * @param inline Whether to inline nested types instead of using $ref
     * @return JSON Schema object definition
     */
    private fun buildClassDefinition(desc: SerialDescriptor, inline: Boolean = false): JsonObject = buildJsonObject {
        put("type", "object")
        
        // Generate properties for each field
        val props = buildJsonObject {
            for (i in 0 until desc.elementsCount) {
                // Get property name (respects @SerialName annotations)
                val propName = desc.getElementName(i)
                val child = desc.getElementDescriptor(i)
                
                // Generate schema for this property
                put(propName, if (inline) schemaForDescriptorInlined(child) else schemaForDescriptor(child))
            }
        }
        put("properties", props)

        // Determine required fields (non-optional and non-nullable)
        val required = (0 until desc.elementsCount)
            .filter { i -> 
                !desc.isElementOptional(i) && !desc.getElementDescriptor(i).isNullable 
            }
            .map { i -> desc.getElementName(i) }
            
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map(::JsonPrimitive)))
        }

        // Strict validation - no additional properties allowed
        put("additionalProperties", JsonPrimitive(false))
    }

    /**
     * Generates JSON Schema for sealed class hierarchies.
     * 
     * Creates a oneOf schema where each subtype is represented as an allOf combining:
     * 1. The subtype's schema (via $ref)
     * 2. A discriminator constraint requiring the specific type value
     * 
     * This matches kotlinx.serialization's polymorphic serialization with discriminators.
     * 
     * @param desc SerialDescriptor for a sealed class
     * @return JSON Schema with oneOf constraint for all subtypes
     */
    private fun sealedSchema(desc: SerialDescriptor): JsonObject {
        // Get all subtype descriptors
        val subs = (0 until desc.elementsCount).map { desc.getElementDescriptor(it) }
        val discriminator = options.classDiscriminator
        
        // Create oneOf array with each subtype
        val oneOf = JsonArray(subs.map { sub ->
            // Ensure the subtype definition exists
            val ref = classSchema(sub)
            
            buildJsonObject {
                // Combine subtype schema with discriminator constraint
                put("allOf", buildJsonArray {
                    // Reference to the actual subtype schema
                    add(JsonObject(ref))
                    
                    // Discriminator constraint
                    add(buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put(discriminator, buildJsonObject { 
                                put("const", JsonPrimitive(sub.serialName)) 
                            })
                        })
                        put("required", JsonArray(listOf(JsonPrimitive(discriminator))))
                        put("additionalProperties", JsonPrimitive(true))
                    })
                })
            }
        })
        
        return buildJsonObject { put("oneOf", oneOf) }
    }

    /**
     * Inlined version of sealedSchema.
     */
    private fun sealedSchemaInlined(desc: SerialDescriptor): JsonObject {
        // Get all subtype descriptors
        val subs = (0 until desc.elementsCount).map { desc.getElementDescriptor(it) }
        val discriminator = options.classDiscriminator
        
        // Create oneOf array with each subtype
        val oneOf = JsonArray(subs.map { sub ->
            // Get inlined subtype schema
            val subSchema = classSchemaInlined(sub)
            
            buildJsonObject {
                // Combine subtype schema with discriminator constraint
                put("allOf", buildJsonArray {
                    // Inlined subtype schema
                    add(JsonObject(subSchema))
                    
                    // Discriminator constraint
                    add(buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put(discriminator, buildJsonObject { 
                                put("const", JsonPrimitive(sub.serialName)) 
                            })
                        })
                        put("required", JsonArray(listOf(JsonPrimitive(discriminator))))
                        put("additionalProperties", JsonPrimitive(true))
                    })
                })
            }
        })
        
        return buildJsonObject { put("oneOf", oneOf) }
    }

    /**
     * Generates JSON Schema for open polymorphic types.
     * 
     * Since we can't enumerate all possible subtypes for open polymorphism,
     * we create a flexible schema that requires the discriminator field but
     * allows any additional properties.
     * 
     * @return JSON Schema for open polymorphic type
     */
    private fun polymorphicOpenSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        
        // Require discriminator field but allow any other properties
        put("properties", buildJsonObject {
            put(options.classDiscriminator, buildJsonObject { put("type", "string") })
        })
        
        // Allow additional properties since we don't know all possible subtypes
        put("additionalProperties", JsonPrimitive(true))
    }

    /**
     * Determines if a type should get a named definition in $defs.
     * 
     * Only classes, objects, and enums get named definitions for reuse.
     * Primitive types and collections are always inlined.
     * 
     * @param desc SerialDescriptor to check
     * @return Sanitized name for $defs, or null if should be inlined
     */
    private fun defName(desc: SerialDescriptor): String? = when (desc.kind) {
        StructureKind.CLASS, StructureKind.OBJECT, SerialKind.ENUM -> sanitize(desc.serialName)
        else -> null
    }

    /**
     * Sanitizes a serial name for use as a JSON Schema definition name.
     * 
     * Replaces invalid characters with underscores to ensure valid JSON Schema identifiers.
     * 
     * @param serialName The original serial name
     * @return Sanitized name safe for use in JSON Schema
     */
    private fun sanitize(serialName: String): String =
        serialName.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

// =============== Convenience Functions ===============

/**
 * Convenience function to generate JSON Schema from a serializer.
 * 
 * @param serializer The KSerializer for the type
 * @param options Schema generation options
 * @return Complete JSON Schema as JsonObject
 */
fun <T> schemaFor(serializer: KSerializer<T>, options: SchemaOptions = SchemaOptions()): JsonObject =
    JsonSchemaGenerator(options = options).generate(serializer)

/**
 * Convenience function to generate JSON Schema from a KClass.
 * 
 * @param kclass The Kotlin class to generate schema for
 * @param module SerializersModule for custom serializers
 * @param options Schema generation options
 * @return Complete JSON Schema as JsonObject
 */
fun schemaFor(kclass: KClass<*>, module: SerializersModule = EmptySerializersModule(), options: SchemaOptions = SchemaOptions()): JsonObject =
    JsonSchemaGenerator(module, options).generate(kclass)

/**
 * Convenience function to generate inlined JSON Schema from a serializer.
 * This produces schemas that are more LLM-friendly by avoiding $ref and $defs.
 * 
 * @param serializer The KSerializer for the type
 * @param options Schema generation options
 * @return Inlined JSON Schema as JsonObject
 */
fun <T> inlinedSchemaFor(serializer: KSerializer<T>, options: SchemaOptions = SchemaOptions()): JsonObject =
    JsonSchemaGenerator(options = options).generateInlined(serializer)

/**
 * Convenience function to generate inlined JSON Schema from a KClass.
 * This produces schemas that are more LLM-friendly by avoiding $ref and $defs.
 * 
 * @param kclass The Kotlin class to generate schema for
 * @param module SerializersModule for custom serializers
 * @param options Schema generation options
 * @return Inlined JSON Schema as JsonObject
 */
fun inlinedSchemaFor(kclass: KClass<*>, module: SerializersModule = EmptySerializersModule(), options: SchemaOptions = SchemaOptions()): JsonObject {
    val ser = module.serializer(kclass, emptyList(), isNullable = false)
    return JsonSchemaGenerator(module, options).generateInlined(ser)
}

/**
 * Convenience function to generate example JSON from a serializer.
 * This produces the most LLM-friendly format showing exact JSON structure.
 * 
 * @param serializer The KSerializer for the type
 * @param module SerializersModule for custom serializers
 * @return Example JSON as JsonObject
 */
fun <T> exampleFor(serializer: KSerializer<T>, module: SerializersModule = EmptySerializersModule()): JsonObject =
    JsonSchemaGenerator(module).generateExample(serializer)

/**
 * Convenience function to generate example JSON from a KClass.
 * This produces the most LLM-friendly format showing exact JSON structure.
 * 
 * @param kclass The Kotlin class to generate example for
 * @param module SerializersModule for custom serializers
 * @return Example JSON as JsonObject
 */
fun exampleFor(kclass: KClass<*>, module: SerializersModule = EmptySerializersModule()): JsonObject {
    val ser = module.serializer(kclass, emptyList(), isNullable = false)
    return JsonSchemaGenerator(module).generateExample(ser)
}

/**
 * Convenience helpers that mirror [exampleFor] but return a prompt-ready string. If enums are present,
 * the legend is appended automatically two line breaks below the JSON example.
 */
fun <T> examplePromptFor(
    serializer: KSerializer<T>,
    module: SerializersModule = EmptySerializersModule()
): String {
    val generator = JsonSchemaGenerator(module)
    val result = generator.generateExampleWithLegend(serializer)
    return generator.formatExampleWithLegend(result)
}

fun examplePromptFor(
    kclass: KClass<*>,
    module: SerializersModule = EmptySerializersModule()
): String {
    val serializer = module.serializer(kclass, emptyList(), isNullable = false)
    return examplePromptFor(serializer, module)
}

inline fun <reified T> examplePromptFor(
    module: SerializersModule = EmptySerializersModule()
): String = examplePromptFor(T::class, module)
