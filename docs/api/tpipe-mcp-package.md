# TPipe-MCP Package API

The TPipe-MCP package is the Protocol Bridge—the universal adapter that provides bidirectional conversion between the Model Context Protocol (MCP) and TPipe's native Pipe Context Protocol (PCP). This enables seamless integration with the wider ecosystem of MCP-compatible tools, servers, and resources while maintaining the high-performance, deterministic flow of the TPipe engine.

## Table of Contents
- [Data Models](#data-models)
- [Bridge Classes](#bridge-classes)
- [Pipe Extensions](#pipe-extensions)
- [Key Operational Behaviors](#key-operational-behaviors)

---

## Data Models

### McpTool
Represents a specific function or tool available in the MCP ecosystem.
- **`inputSchema`**: A JSON Schema defining the required and optional input parameters for the tool.
- **`outputSchema`**: An optional JSON Schema defining the expected structure of the tool's response.

### McpResource
Represents a data source (file, database, API) that the model can "Read."
- **`uri`**: The standardized path to the resource (e.g., `file://`, `postgres://`, `http://`).
- **`mimeType`**: Used by the bridge to determine how the content should be "Filtered" (e.g., `text/markdown`, `application/json`).

### ConversionResult
The diagnostic report for a conversion operation.
- **`pcpContext`**: The resulting TPipe Tool Belt if the conversion was successful.
- **`errors` / `warnings`**: A list of technical logs detailing any incompatible schema types or URI schemes encountered during the adaptation.

---

## Bridge Classes

### McpToPcpConverter
The primary Intake converter. It translates MCP requests into a TPipe-native `PcpContext`.
- **Logic**: It maps MCP tools to `TPipeContextOptions` and maps resources to `StdioContextOptions` (e.g., translating a `file://` URI into a secure `cat` command in the Tool Belt).
- **Schema Extraction**: It automatically parses JSON Schema types (string, integer, boolean) and enum values into TPipe's native `ParamType` system.

### PcpToMcpConverter
The "Export" converter. It translates a native TPipe Tool Belt back into the standard MCP format, allowing other MCP-compatible clients to understand and use TPipe's tools.

### McpJsonParser / Builder
Low-level utilities for handling the MCP JSON-RPC wire format. They ensure that all required fields (like `name` and `uri`) are present and valid before conversion begins.

---

## Pipe Extensions

TPipe provides several Fittings for the `Pipe` class to make MCP integration effortless.

#### `Pipe.setMcpContext(mcpJson: String): Pipe`
The standard way to equip a valve with MCP tools. It parses the JSON, converts it to a Tool Belt, and attaches it to the Pipe in one fluent step.

#### `Pipe.convertAndApplyMcp(mcpJson: String): ConversionResult`
A safer variant of the above that returns a full diagnostic report, allowing you to handle conversion warnings programmatically.

#### `Pipe.exportToMcp(): String`
Generates a standard MCP-compliant JSON string representing the Pipe's current Tool Belt.

---

## Key Operational Behaviors

### 1. Schema Mapping
The bridge performs sophisticated mapping between complex JSON Schemas and TPipe's deterministic parameter system. It preserves documentation, descriptions, and strict type constraints (like `minimum` or `maximum` values for numbers).

### 2. URI to Command Translation
TPipe-MCP includes an intelligent mapping system for resources:
- `file://` URIs become secure file-reading tool calls.
- `http://` / `https://` URIs become `curl` or `HttpExecutor` tool calls.
- Custom URI schemes can be extended with specialized shell command mapping.

### 3. Protocol Compliance
The package strictly adheres to the Model Context Protocol specification. It validates that all tools have valid object-type input schemas and that all resources provide the mandatory identity fields, ensuring compatibility with any compliant MCP host or client.
