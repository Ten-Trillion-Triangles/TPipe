# Util Package API

The `Util` package is the Toolbox of the TPipe ecosystem. It provides high-performance, industrial-grade utilities for JSON processing, HTTP requests, filesystem operations, and system interactions. These tools are specifically engineered to handle the non-deterministic nature of AI model outputs, offering robust repair and extraction capabilities.

## Table of Contents
- [Core Utilities: Serializing and Deep Copying](#core-utilities-serializing-and-deep-copying)
- [JSON Extraction and Repair](#json-extraction-and-repair)
- [JSON Schema Generation](#json-schema-generation)
- [HTTP and Process Execution](#http-and-process-execution)
- [Pipeline Maintenance Utilities](#pipeline-maintenance-utilities)

---

## Core Utilities: Serializing and Deep Copying

#### `serialize<T>(obj: T): String`
Converts a Kotlin object to JSON. It uses an extremely lenient configuration to ensure that even if an AI model "Leaks" extra text or formatting, the serialization remains valid.

#### `deserialize<T>(jsonString: String, useRepair: Boolean = true): T?`
The primary Intake valve for AI-generated JSON.
- **Auto-Repair**: If `useRepair` is true, TPipe applies several Patching strategies to fix common AI errors like missing quotes, trailing commas, or truncated brackets.
- **Fail-Safe**: Returns null on failure rather than throwing an exception, allowing the pipeline to handle the failure through the **Error Handling** system.

#### `T.deepCopy(): T`
Creates a perfect Mirror of a data class using reflection. This is used by the **Snapshot System** to preserve state before a risky pipe execution.

---

## JSON Extraction and Repair: Patching the Mainline

#### `extractJson<T>(input: String): T?`
Scours a raw text string for valid JSON objects. It can find a JSON Cargo unit even if it is buried under paragraphs of model-generated text.

#### `repairJsonString(input: String): String`
A high-intensity repair tool that:
- Decodes HTML entities and escape sequences.
- Fixes unquoted keys and malformed arrays.
- Force-closes any brackets that the model left "Open" due to token limits.

---

## JSON Schema Generation: Blueprinting

#### `JsonSchemaGenerator`
Generates a complete JSON Schema from a Kotlin `@Serializable` class. This is used to teach the model the exact "Specification" it must follow.
- **`generateInlined()`**: Creates a schema without complex definitions ($defs), which is significantly easier for models to follow.
- **`generateExample()`**: Produces a sample JSON object showing the model exactly what the Ideal Flow looks like.

---

## HTTP and Process Execution

#### `httpRequest(url, method, ...): HttpResponseData`
A robust network connector supporting BASIC, BEARER, and API_KEY authentication. It includes built-in timeouts and returns high-resolution metadata, including response timing and success status.

#### `launchProgramAsync(path): Deferred<ProgramResult>`
The Power Tool for shell interaction. it launches external programs asynchronously and captures their `stdout`, `stderr`, and exit codes.

---

## Pipeline Maintenance Utilities

#### `constructPipeFromTemplate<T>(template): T?`
Creates a new specialized valve from an existing pipe template. You can choose whether to copy internal functions, metadata, or child pipes, allowing you to Clone complex configurations easily.

#### `copyPipeline(originalPipeline): Pipeline`
Creates a complete, independent copy of an entire mainline, preserving the order and relationships of every valve in the chain.

---

## Key Operational Behaviors

### 1. High Leniency
The Toolbox is designed for the Real World of AI engineering. It expects malformed inputs and provides multiple layers of recovery to keep the data flowing.

### 2. Cross-Platform Consistency
Filesystem and process utilities handle the differences between Linux, Mac, and Windows automatically. Whether you are resolving a home folder or clearing a terminal screen, the API remains the same.

### 3. Asynchronous Efficiency
All heavy operations (Network, IO, Processes) are built on Kotlin Coroutines, ensuring that your agent infrastructure remains non-blocking and highly concurrent.
