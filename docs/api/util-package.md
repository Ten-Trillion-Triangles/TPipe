# Util Package API

## Table of Contents
- [Overview](#overview)
- [Util.kt - Core Utilities](#utilkt---core-utilities)
- [JsonExtractor.kt - JSON Processing](#jsonextractorkt---json-processing)
- [Schema.kt - JSON Schema Generation](#schemakt---json-schema-generation)
- [Rest.kt - HTTP Utilities](#restkt---http-utilities)
- [JsonCleaner.kt - JSON Cleaning](#jsoncleanerkt---json-cleaning)

## Overview

The Util package provides essential utility functions for JSON processing, HTTP requests, file operations, schema generation, and system interactions used throughout TPipe.

---

## Util.kt - Core Utilities

### JSON Serialization & Deserialization

#### `serialize<T>(obj: T, encodedefault: Boolean = true): String`
Serializes objects to JSON with maximum leniency for AI-generated content.

**Behavior:** Uses extremely lenient JSON configuration to handle malformed AI output. Never throws exceptions - returns empty string on failure. Supports all kotlinx.serialization features.

#### `deserialize<T>(jsonString: String, useRepair: Boolean = true): T?`
Deserializes JSON with automatic repair capabilities.

**Behavior:** 
- First attempts standard deserialization
- If `useRepair = true`, applies comprehensive repair strategies
- Returns null on failure rather than throwing exceptions
- Handles malformed AI-generated JSON gracefully

#### `repairJsonString(input: String): String`
Applies multiple repair strategies to fix malformed JSON.

**Behavior:**
- Decodes HTML entities and escape sequences
- Extracts JSON boundaries with bracket matching
- Fixes structural issues (trailing commas, empty values)
- Repairs unquoted keys and values
- Completes incomplete structures
- Handles truncated strings and missing brackets

#### `repairAndDeserialize<T>(malformedJson: String): T?`
Comprehensive repair and deserialization with fallback strategies.

**Behavior:**
- Applies standard JSON repair
- Uses aggressive extraction on repair failure
- Employs reflection-based reconstruction
- Attempts template-based reconstruction
- Returns null if all strategies fail

### Deep Copying

#### `T.deepCopy(): T`
Creates deep copies of data classes using reflection.

**Behavior:**
- Recursively copies nested data classes and collections
- Handles primitive types, strings, lists, sets, maps
- Uses reflection to access primary constructor parameters
- Preserves object structure and relationships

### File Operations

#### `getHomeFolder(): File`
Returns user's home directory across platforms.

#### `copyFile(starPath: String, destPath: String)`
Copies files with comprehensive error handling.

#### `copyDir(starPath: String, destPath: String)`
Recursively copies directories with error recovery.

#### `deleteDir(path: String)`
Safely deletes directories with error handling.

#### `writeStringToFile(filepath: String, content: String)`
Writes strings to files, creating directories as needed.

#### `readStringFromFile(filepath: String): String`
Reads file content with safety checks.

### Process Execution

#### `executeBashCommand(command: String): Int`
Executes bash commands synchronously.

#### `launchProgramAsync(path: MutableList<String>): Deferred<ProgramResult>`
Launches programs asynchronously with output capture.

**Behavior:** Returns `Deferred<ProgramResult>` containing exit code, captured output, and success status.

#### `launchProgramAwait(path: MutableList<String>): ProgramResult`
Convenience function for async execution with immediate await.

### Pipeline Utilities

#### `constructPipeFromTemplate<T>(template: Pipe, copyFunctions: Boolean = false, copyPipes: Boolean = false, copyMetadata: Boolean = false): T?`
Creates new pipes from templates with selective copying.

**Behavior:**
- Serializes and deserializes pipe for base copy
- Optionally copies function references, pipe references, metadata
- Returns typed pipe instance or null on failure

#### `copyPipeline(originalPipeline: Pipeline, copyFunctions: Boolean = false, copyPipes: Boolean = false): Pipeline`
Creates complete pipeline copies.

**Behavior:**
- Copies pipeline-level properties
- Uses `constructPipeFromTemplate` for each pipe
- Maintains pipe order and relationships

#### `getLowestContextWindowSize(pipes: List<Pipe>): Int`
Finds smallest context window across multiple pipes.

### System Utilities

#### `getOs(): String`
Returns operating system identifier ("Linux", "Mac", "Win64").

#### `getWorkingDirectory(): String`
Returns current working directory path.

#### `findFileCascading(path: String): File`
Searches for files up the directory tree.

#### `clearScreen()`
Clears terminal screen across platforms.

### Collection Utilities

#### `combine(listA: List<String>, listB: List<String>): List<String>`
Merges lists preventing duplicates.

#### `findSubStringInList(list: List<String>, target: String): Int`
Finds substring matches in string lists.

#### `getSubStringInList(list: List<String>, value: String): String`
Returns matching string from list.

---

## JsonExtractor.kt - JSON Processing

### Core Extraction Functions

#### `extractAllJsonObjects(input: String): List<JsonElement>`
Extracts all JSON objects and arrays from mixed content.

**Behavior:**
- Finds JSON boundaries with proper bracket matching
- Applies repair strategies for malformed JSON
- Uses lenient parsing configuration
- Handles overlapping ranges and duplicates
- Returns all distinct JSON elements found

#### `extractJson<T>(input: String): T?`
Extracts and deserializes first matching JSON object.

**Behavior:**
- Combines `extractAllJsonObjects` with `deserializeFirstMatch`
- Returns first successfully deserialized object of type T
- Handles mixed content with JSON embedded in text

#### `deserializeFirstMatch<T>(jsonElements: List<JsonElement>): T?`
Attempts deserialization on array of JsonElements.

**Behavior:**
- Tries each JsonElement in sequence
- Uses lenient deserialization with repair fallback
- Returns first successful match or null

#### `extractNonJsonText(input: String): String`
Extracts text content excluding JSON objects/arrays.

**Behavior:**
- Identifies JSON boundaries
- Returns all text outside JSON structures
- Useful for content truncation while preserving JSON

### Internal Utilities

**`findJsonBoundaries(input: String, openChar: Char, closeChar: Char): List<IntRange>`**
Finds complete JSON object/array boundaries with depth tracking.

**`tryFallbackExtraction(jsonCandidate: String): JsonElement?`**
Applies aggressive extraction when standard parsing fails.

---

## Schema.kt - JSON Schema Generation

### Core Classes

#### `JsonSchemaGenerator`
Comprehensive JSON Schema generator for Kotlin serializable classes.

```kotlin
class JsonSchemaGenerator(
    private val module: SerializersModule = EmptySerializersModule(),
    private val options: SchemaOptions = SchemaOptions()
)
```

**Key Methods:**

**`generate<T>(serializer: KSerializer<T>, id: String? = null): JsonObject`**
Generates complete JSON Schema with $defs for reusable types.

**`generateInlined<T>(serializer: KSerializer<T>, id: String? = null): JsonObject`**
Generates inlined schema without $ref for better LLM compatibility.

**`generateExample<T>(serializer: KSerializer<T>): JsonObject`**
Generates example JSON showing expected structure.

#### `SchemaOptions`
Configuration for schema generation.

```kotlin
data class SchemaOptions(
    val schemaDialect: String = "https://json-schema.org/draft/2020-12/schema",
    val classDiscriminator: String = "type",
    val structuredMapKeys: Boolean = false
)
```

### Convenience Functions

#### `schemaFor<T>(serializer: KSerializer<T>, options: SchemaOptions = SchemaOptions()): JsonObject`
Generates JSON Schema from serializer.

#### `inlinedSchemaFor<T>(serializer: KSerializer<T>, options: SchemaOptions = SchemaOptions()): JsonObject`
Generates LLM-friendly inlined schema.

#### `exampleFor<T>(serializer: KSerializer<T>): JsonObject`
Generates example JSON structure.

### Key Features

**Comprehensive Type Support:**
- All Kotlin primitive types with JSON Schema mappings
- Nullable types using union types or anyOf constructs
- Collections (List, Set, Map) with proper constraints
- Sealed classes with discriminator patterns
- Polymorphic types and inheritance
- Enum types with value constraints

**Advanced Features:**
- Circular reference detection and handling
- $ref definitions for reusable types
- Format constraints for dates, UUIDs, etc.
- Map key constraints for complex key types
- Structured map keys for non-string keys

---

## Rest.kt - HTTP Utilities

### Enhanced HTTP Functions

#### `httpRequest(url: String, method: String = "GET", body: String = "", headers: Map<String, String> = emptyMap(), auth: HttpAuth = HttpAuth(), timeoutMs: Long = 30000, followRedirects: Boolean = true): HttpResponseData`
Comprehensive HTTP request with full configuration support.

**Behavior:**
- Supports all HTTP methods with proper body handling
- Configurable authentication (NONE, BASIC, BEARER, API_KEY)
- Custom headers and timeout configuration
- Returns detailed response metadata including timing
- Graceful error handling with structured error responses

#### `HttpResponseData`
Enhanced response object with metadata.

```kotlin
@Serializable
data class HttpResponseData(
    val statusCode: Int,
    val statusMessage: String,
    val headers: Map<String, String>,
    val body: String,
    val responseTimeMs: Long,
    val success: Boolean = statusCode in HttpConstants.SUCCESS_STATUS_RANGE
)
```

#### `HttpAuth`
Authentication configuration.

```kotlin
data class HttpAuth(
    val type: String = "NONE",
    val credentials: Map<String, String> = emptyMap()
)
```

### Legacy HTTP Functions

#### `httpGet(url: String, acceptType: String = "*/*", authToken: String? = null): String`
Simple GET request with Bearer token support.

#### `httpPost(url: String, body: String, acceptType: String = "*/*", authToken: String? = null): String`
Simple POST request with JSON body.

#### `httpPut(url: String, body: String, acceptType: String = "*/*", authToken: String? = null): String`
Simple PUT request with JSON body.

#### `httpDelete(url: String, body: String = "", authToken: String? = null): String`
Simple DELETE request with optional body.

---

## JsonCleaner.kt - JSON Cleaning

### Simple JSON Extraction

#### `cleanJsonString(input: String): String`
Extracts JSON content between first '{' and last '}'.

**Behavior:**
- Finds first opening brace and last closing brace
- Returns substring containing JSON content
- Simple extraction without repair capabilities
- Returns original string if no valid boundaries found

## Key Behaviors

### Lenient JSON Processing
All JSON utilities use extremely lenient configuration to handle AI-generated content with malformed structure, missing quotes, trailing commas, and other common issues.

### Comprehensive Error Handling
Functions prioritize graceful degradation over exceptions, returning null, empty strings, or error objects rather than crashing.

### Cross-Platform Compatibility
File and system operations handle platform differences automatically, supporting Windows, macOS, and Linux.

### Performance Optimization
Async operations use coroutines for non-blocking execution, and caching mechanisms prevent redundant processing.

### AI-Friendly Design
JSON schema generation and repair mechanisms are specifically designed to work well with LLM-generated content and provide LLM-friendly output formats.
