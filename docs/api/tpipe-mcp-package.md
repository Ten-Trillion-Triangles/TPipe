# TPipe-MCP Package API

## Table of Contents
- [Overview](#overview)
- [Models](#models)
  - [McpTool](#mcptool)
  - [McpResource](#mcpresource)
  - [McpRequest](#mcprequest)
  - [ConversionResult](#conversionresult)
- [Bridge Classes](#bridge-classes)
  - [McpToPcpConverter](#mcptopcpconverter)
  - [PcpToMcpConverter](#pcptomcpconverter)
  - [McpJsonParser](#mcpjsonparser)
  - [McpJsonBuilder](#mcpjsonbuilder)
- [Extensions](#extensions)
  - [Pipe Extensions](#pipe-extensions)
  - [PCP Accessor](#pcp-accessor)

## Overview

The TPipe-MCP package provides bidirectional conversion between Model Context Protocol (MCP) and TPipe's Pipe Context Protocol (PCP), enabling seamless integration with MCP-compatible tools and services. Implements Model Context Protocol specification **2025-11-25** with full compliance verified through integration tests.

---

## Models

### McpTool

Represents an MCP tool definition with function metadata and schema information.

```kotlin
@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null
)
```

#### Public Properties

**`name`** - Tool/function identifier used for invocation
**`description`** - Optional human-readable description of tool functionality
**`inputSchema`** - JSON Schema defining required and optional input parameters
**`outputSchema`** - Optional JSON Schema defining expected output format

---

### McpResource

Represents an MCP resource definition for file or data access.

```kotlin
@Serializable
data class McpResource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null
)
```

#### Public Properties

**`uri`** - Resource URI/path for access (file://, http://, etc.)
**`name`** - Human-readable resource identifier
**`description`** - Optional description of resource content/purpose
**`mimeType`** - Optional MIME type for content type identification

---

### McpRequest

Container for complete MCP request with tools and resources.

```kotlin
@Serializable
data class McpRequest(
    val tools: List<McpTool> = emptyList(),
    val resources: List<McpResource> = emptyList()
)
```

#### Public Properties

**`tools`** - Available MCP tools/functions for execution
**`resources`** - Available MCP resources for access

---

### ConversionResult

Result container for MCP-PCP conversion operations with status and error information.

```kotlin
@Serializable
data class ConversionResult(
    val success: Boolean,
    val pcpContext: PcpContext? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)
```

#### Public Properties

**`success`** - Whether conversion completed successfully
**`pcpContext`** - Resulting PCP context if conversion succeeded
**`errors`** - List of error messages for failed conversions
**`warnings`** - List of warning messages for non-critical issues

---

## Bridge Classes

### McpToPcpConverter

Converts MCP requests to PCP context format for TPipe integration.

```kotlin
class McpToPcpConverter
```

#### Public Functions

**`convert(mcpRequest: McpRequest): PcpContext`**
Converts MCP request to PCP context format.

**Behavior:**
- **Tools Conversion**: Maps MCP tools to TPipeContextOptions with parameter extraction from JSON Schema
- **Resources Conversion**: Maps MCP resources to StdioContextOptions based on URI schemes
- **Schema Processing**: Extracts parameter definitions, types, descriptions, and enum values
- **Command Mapping**: Maps resource URIs to appropriate shell commands (file:// → cat, http:// → curl)
- **Permission Assignment**: Assigns appropriate permissions based on resource types

---

### PcpToMcpConverter

Converts PCP context back to MCP request format for reverse compatibility.

```kotlin
class PcpToMcpConverter
```

#### Public Functions

**`convert(pcpContext: PcpContext): McpRequest`**
Converts PCP context to MCP request format.

**Behavior:**
- **TPipe Options**: Converts TPipeContextOptions to MCP tools with JSON Schema generation
- **Stdio Options**: Converts StdioContextOptions to MCP resources with URI mapping
- **Schema Generation**: Builds JSON Schema from PCP parameter definitions
- **Type Mapping**: Maps PCP ParamType enums to JSON Schema types
- **URI Construction**: Reconstructs appropriate URIs from commands and arguments

---

### McpJsonParser

Parser for MCP JSON format that extracts structured components.

```kotlin
class McpJsonParser
```

#### Public Functions

**`parseJson(mcpJson: String): McpRequest`**
Parses MCP JSON string into structured McpRequest object.

**Behavior:**
- **JSON Validation**: Validates JSON structure and required fields
- **Tool Extraction**: Extracts tools with inputSchema validation (must be type "object")
- **Resource Extraction**: Extracts resources with required uri and name fields
- **Error Handling**: Throws descriptive exceptions for malformed or invalid JSON

**`validateMcpStructure(json: JsonObject): Boolean`**
Validates JSON contains at least one MCP component.

**`extractTools(json: JsonObject): List<McpTool>`**
Extracts and validates MCP tools from JSON.

**`extractResources(json: JsonObject): List<McpResource>`**
Extracts and validates MCP resources from JSON.

---

### McpJsonBuilder

Builder for creating formatted MCP JSON from requests and contexts.

```kotlin
class McpJsonBuilder
```

#### Public Functions

**`buildMcpJson(mcpRequest: McpRequest): String`**
Converts MCP request to formatted JSON string.

**Behavior:** Serializes McpRequest to pretty-printed JSON. **Warning:** This is internal format only - use JSON-RPC responses for wire format.

**`buildMcpJsonFromPcp(pcpContext: PcpContext): String`**
Converts PCP context to MCP JSON format.

**Behavior:** Uses PcpToMcpConverter to convert context, then serializes to JSON.

---

## Extensions

### Pipe Extensions

Extension functions for Pipe class enabling MCP integration.

#### `Pipe.setMcpContext(mcpJson: String): Pipe`
Sets MCP context by converting MCP JSON to PCP format.

**Behavior:**
- Parses MCP JSON using McpJsonParser
- Converts to PCP context using McpToPcpConverter
- Applies converted context to pipe via setPcPContext()
- Returns pipe for method chaining
- Throws exception if JSON is malformed or conversion fails

#### `Pipe.convertAndApplyMcp(mcpJson: String): ConversionResult`
Converts MCP JSON to PCP context with comprehensive error handling.

**Behavior:**
- Attempts MCP JSON parsing and conversion
- Applies converted context to pipe on success
- Returns ConversionResult with success status and error details
- Captures and reports all conversion failures

#### `Pipe.exportToMcp(): String`
Exports current PCP context as MCP JSON format.

**Behavior:**
- Uses McpJsonBuilder to convert current PCP context
- Returns formatted MCP JSON string
- Enables reverse conversion for MCP compatibility

#### `Pipe.convertPcpToMcp(): ConversionResult`
Converts current PCP context to MCP format with error handling.

**Behavior:**
- Converts PCP context using PcpToMcpConverter
- Returns ConversionResult with conversion status
- Includes warnings about export operation
- Captures conversion errors if they occur

---

### PCP Accessor

Extension property for accessing protected PCP context.

#### `Pipe.pcpContext: PcpContext`
Extension property to access protected pcpContext field.

**Behavior:**
- Uses reflection to access protected field safely
- Enables MCP bridge operations that need direct context access
- Returns current PcpContext of the pipe

---

## Key Behaviors

### Bidirectional Conversion
The bridge supports complete round-trip conversion between MCP and PCP formats, enabling seamless integration with MCP-compatible tools while maintaining TPipe's native capabilities.

### Schema Mapping
Sophisticated mapping between JSON Schema (MCP) and PCP parameter definitions, including:
- Type conversion (string, integer, number, boolean, array, object)
- Enum value preservation
- Description and documentation transfer
- Required field handling

### URI and Command Mapping
Intelligent mapping between MCP resource URIs and shell commands:
- `file://` URIs → `cat` command for file reading
- `http://`/`https://` URIs → `curl` command for HTTP requests
- Custom URI schemes → appropriate shell commands

### Error Handling
Comprehensive error handling with detailed error messages and warnings:
- JSON parsing errors with specific field validation
- Schema validation errors with type checking
- Conversion errors with context information
- Non-critical warnings for informational purposes

### Format Compliance
Strict adherence to MCP specification requirements:
- Tools must have `inputSchema` with type "object"
- Resources must have required `uri` and `name` fields
- JSON Schema validation for parameter definitions
- Proper MIME type handling for resources

### Integration Patterns
Multiple integration approaches for different use cases:
- Direct conversion with exception handling
- Safe conversion with result objects
- Extension methods for fluent API usage
- Export capabilities for reverse compatibility
## Next Steps

- [TPipe-Defaults API](tpipe-defaults-package.md) - Continue into the defaults package.
