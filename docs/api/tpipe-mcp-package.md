# TPipe-MCP Package API

## Table of Contents
- [Overview](#overview)
- [Models](#models)
  - [McpTool](#mcptool)
  - [McpResource](#mcpresource)
  - [McpResourceTemplate](#mcpresourcetemplate)
  - [McpPromptArgument](#mcppromptargument)
  - [McpPrompt](#mcpprompt)
  - [McpRequest](#mcprequest)
  - [ConversionResult](#conversionresult)
  - [McpIcon](#mcpicon)
  - [McpAnnotations](#mcpannotations)
- [JSON-RPC Models](#json-rpc-models)
  - [JsonRpcRequest](#jsonrpcrequest)
  - [JsonRpcResponse](#jsonrpcresponse)
  - [JsonRpcError](#jsonrpcerror)
  - [JsonRpcBatchRequest](#jsonrpcbatchrequest)
  - [JsonRpcBatchResponse](#jsonrpcbatchresponse)
  - [McpJsonRpcError](#mcpjsonrpcerror)
- [Bridge Classes](#bridge-classes)
  - [McpToPcpConverter](#mcptopcpconverter)
  - [PcpToMcpConverter](#pcptomcpconverter)
  - [McpJsonParser](#mcpjsonparser)
  - [McpJsonBuilder](#mcpjsonbuilder)
  - [PcpBuilder](#pcpbuilder)
  - [McpBridgeMain](#mcpbridgemain)
- [Extensions](#extensions)
  - [Pipe Extensions](#pipe-extensions)
  - [PCP Accessor](#pcp-accessor)
- [MCP Server Hosting](#mcp-server-hosting)
  - [McpCapabilityConfig](#mcpcapabilityconfig)
  - [McpToolRegistry](#mcptoolregistry)
  - [McpPromptProvider](#mcppromptprovider)
  - [McpResourceProvider (Main TPipe)](#mcpresourceprovider-main-tpipe)
  - [McpSessionManager](#mcpsessionmanager)
  - [McpServerHost](#mcpserverhost)
  - [McpHttpHost](#mcphttphost)
  - [McpStdioHost](#mcpstdiohost)
- [MCP Bridge Server Hosting](#mcp-bridge-server-hosting)
  - [McpBridgeServerHost](#mcpbridgeserverhost)
  - [McpBridgeHttpHost](#mcpbridgehttphost)
  - [McpBridgeStdioHost](#mcpbridgestdiohost)
  - [McpProtocolHandler](#mcpprotocolhandler)
  - [RateLimitConfig](#ratelimitconfig)
  - [McpResourceProvider (TPipe-MCP)](#mcpresourceprovider-tpipe-mcp)
  - [McpRootsProvider](#mcprootsprovider)
  - [McpSamplingHandler](#mcpsamplinghandler)
  - [McpResourceSubscriptionManager](#mcpresourcesubscriptionmanager)

## Overview

The TPipe-MCP package provides bidirectional conversion between Model Context Protocol (MCP) and TPipe's Pipe Context Protocol (PCP), enabling seamless integration with MCP-compatible tools and services. Implements Model Context Protocol specification **2024-11-05** with full compliance verified through integration tests.

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
    val outputSchema: JsonObject? = null,
    val icons: List<McpIcon>? = null,
    val annotations: McpAnnotations? = null
)
```

#### Public Properties

**`name`** - Tool/function identifier used for invocation
**`description`** - Optional human-readable description of tool functionality
**`inputSchema`** - JSON Schema defining required and optional input parameters
**`outputSchema`** - Optional JSON Schema defining expected output format
**`icons`** - Optional list of icons for the tool
**`annotations`** - Optional metadata annotations for models and clients

---

### McpResource

Represents an MCP resource definition for file or data access.

```kotlin
@Serializable
data class McpResource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null,
    val annotations: McpAnnotations? = null
)
```

#### Public Properties

**`uri`** - Resource URI/path for access (file://, http://, etc.)
**`name`** - Human-readable resource identifier
**`description`** - Optional description of resource content/purpose
**`mimeType`** - Optional MIME type for content type identification
**`annotations`** - Optional metadata annotations

---

### McpResourceTemplate

Represents an MCP resource template for dynamic resource generation.

```kotlin
@Serializable
data class McpResourceTemplate(
    val uriTemplate: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null,
    val annotations: McpAnnotations? = null
)
```

#### Public Properties

**`uriTemplate`** - RFC 6570 URI template
**`name`** - Human-readable template identifier
**`description`** - Optional description of the template
**`mimeType`** - Optional MIME type of the generated resource
**`annotations`** - Optional metadata annotations

---

### McpPromptArgument

Represents an argument for an MCP prompt.

```kotlin
@Serializable
data class McpPromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false
)
```

#### Public Properties

**`name`** - Argument name
**`description`** - Optional description of the argument
**`required`** - Whether the argument is required

---

### McpPrompt

Represents an MCP prompt template.

```kotlin
@Serializable
data class McpPrompt(
    val name: String,
    val description: String? = null,
    val arguments: List<McpPromptArgument>? = null,
    val annotations: McpAnnotations? = null
)
```

#### Public Properties

**`name`** - Prompt name
**`description`** - Optional description of the prompt
**`arguments`** - Optional list of prompt arguments
**`annotations`** - Optional metadata annotations

---

### McpRequest

Container for complete MCP request with tools, resources, templates, and prompts.

```kotlin
@Serializable
data class McpRequest(
    val tools: List<McpTool> = emptyList(),
    val resources: List<McpResource> = emptyList(),
    val resourceTemplates: List<McpResourceTemplate> = emptyList(),
    val prompts: List<McpPrompt> = emptyList()
)
```

#### Public Properties

**`tools`** - Available MCP tools/functions for execution
**`resources`** - Available MCP resources for access
**`resourceTemplates`** - Available resource templates
**`prompts`** - Available prompt templates

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

### McpIcon

Represents an icon for MCP entities.

```kotlin
@Serializable
data class McpIcon(
    val src: String,
    val type: String? = null
)
```

#### Public Properties

**`src`** - The URI or data URI of the icon
**`type`** - Optional MIME type of the icon

---

### McpAnnotations

Represents annotations for MCP entities to provide metadata for models and clients.

```kotlin
@Serializable
data class McpAnnotations(
    val audience: List<String>? = null,
    val priority: Double? = null
)
```

#### Public Properties

**`audience`** - Optional list of intended audiences (e.g., "user", "assistant")
**`priority`** - Optional priority hint for the entity (0.0 to 1.0)

---

## JSON-RPC Models

### JsonRpcRequest

Represents a JSON-RPC 2.0 request or notification.

```kotlin
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
) {
    val isNotification: Boolean get() = id == null || id is JsonNull
}
```

#### Public Properties

**`jsonrpc`** - JSON-RPC version (always "2.0")
**`id`** - Request identifier (null for notifications)
**`method`** - Method name to invoke
**`params`** - Optional parameters as JSON object
**`isNotification`** - True if this is a notification (no id)

#### Companion Functions

**`fromJson(jsonStr: String): JsonRpcRequest`** - Parse from JSON string
**`fromJsonElement(element: JsonElement): JsonRpcRequest`** - Parse from JSON element

---

### JsonRpcResponse

Represents a JSON-RPC 2.0 response.

```kotlin
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement?,
    val result: JsonObject? = null,
    val error: McpJsonRpcError? = null
) {
    val isError: Boolean get() = error != null
    val isSuccess: Boolean get() = error == null
}
```

#### Public Properties

**`jsonrpc`** - JSON-RPC version (always "2.0")
**`id`** - Request identifier this response matches
**`result`** - Result data if successful
**`error`** - Error information if failed
**`isError`** - True if this is an error response
**`isSuccess`** - True if this is a success response

#### Companion Functions

**`success(id: JsonElement, result: JsonObject?)`** - Create success response
**`error(id: JsonElement?, error: McpJsonRpcError)`** - Create error response
**`error(id: JsonElement?, jsonRpcError: JsonRpcError)`** - Create error response from JsonRpcError

---

### JsonRpcError

Represents a JSON-RPC 2.0 error with error code constants.

```kotlin
@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)
```

#### Error Codes

| Code | Constant | Description |
|------|----------|-------------|
| -32700 | `PARSE_ERROR` | Invalid JSON |
| -32600 | `INVALID_REQUEST` | Invalid request format |
| -32601 | `METHOD_NOT_FOUND` | Method not found |
| -32602 | `INVALID_PARAMS` | Invalid parameters |
| -32603 | `INTERNAL_ERROR` | Internal error |
| -32099 to -32000 | `SERVER_ERROR_MIN/MAX` | Server-defined errors |

#### Companion Functions

**`parseError(message, data)`** - Create parse error
**`invalidRequest(message, data)`** - Create invalid request error
**`methodNotFound(message, data)`** - Create method not found error
**`invalidParams(message, data)`** - Create invalid params error
**`internalError(message, data)`** - Create internal error
**`serverError(code, message, data)`** - Create server error (code must be -32099 to -32000)

---

### JsonRpcBatchRequest

Represents a batch of JSON-RPC 2.0 requests.

```kotlin
@Serializable
data class JsonRpcBatchRequest(
    val requests: List<JsonRpcRequest>
) {
    val isNotificationBatch: Boolean get() = requests.all { it.isNotification }
}
```

#### Public Properties

**`requests`** - List of requests in the batch
**`isNotificationBatch`** - True if all requests are notifications (no response expected)

#### Companion Functions

**`fromJson(jsonStr: String): JsonRpcBatchRequest`** - Parse batch request from JSON string

---

### JsonRpcBatchResponse

Represents a batch of JSON-RPC 2.0 responses.

```kotlin
@Serializable
data class JsonRpcBatchResponse(
    val responses: List<JsonRpcResponse>
)
```

#### Public Properties

**`responses`** - List of responses in the batch

#### Companion Functions

**`from(responses: List<JsonRpcResponse>): JsonRpcBatchResponse`** - Create batch response from list

---

### McpJsonRpcError

Represents a JSON-RPC 2.0 error for MCP protocol.

```kotlin
@Serializable
data class McpJsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)
```

#### Public Properties

**`code`** - Error code
**`message`** - Human-readable error message
**`data`** - Optional additional error data

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

**`extractResourceTemplates(json: JsonObject): List<McpResourceTemplate>`**
Extracts resource templates (templates with variable placeholders like `{path}`) from JSON.

**`extractPrompts(json: JsonObject): List<McpPrompt>`**
Extracts prompt definitions with their arguments from JSON.

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

### PcpBuilder

Builder for constructing PCP contexts programmatically.

```kotlin
class PcpBuilder
```

#### Public Functions

**`buildPcpContext(): PcpContext`**
Returns the built PCP context.

**`addTPipeFunction(name: String, description: String, params: Map<String, ContextOptionParameter>): PcpBuilder`**
Adds a TPipe function to the PCP context.

| Parameter | Type | Description |
|-----------|------|-------------|
| `name` | String | The function name |
| `description` | String | Function description |
| `params` | Map<String, ContextOptionParameter> | Map of parameter names to type information |

**`addStdioCommand(command: String, args: List<String>, permissions: List<Permissions>): PcpBuilder`**
Adds a stdio command to the PCP context.

| Parameter | Type | Description |
|-----------|------|-------------|
| `command` | String | The shell command |
| `args` | List<String> | Command arguments |
| `permissions` | List<Permissions> | List of allowed permissions |

**Behavior:**
- Fluent builder pattern for constructing PcpContext
- Supports adding TPipe functions with parameter metadata
- Supports adding stdio commands with permissions
- Returns builder for method chaining

---

### McpBridgeMain

Entry point for MCP bridge server CLI. Provides command-line interface for running bridge modes.

```kotlin
object McpBridgeMain {
    @JvmStatic
    fun main(args: Array<String>)
}

fun mcpmain(args: Array<String>)
```

#### Supported Commands

| Command | Description |
|---------|-------------|
| `--mcp-bridge-stdio-once` | Run MCP bridge for single request |
| `--mcp-bridge-stdio-loop` | Run MCP bridge with request loop |
| `--mcp-bridge-http` | Run MCP bridge with HTTP server |
| `--help` | Show help message |

#### HTTP Server Options

| Option | Description | Default |
|--------|-------------|---------|
| `--port <number>` | HTTP port | 8080 |
| `--auth-key <key>` | Optional Bearer auth key | none |
| `--bind-address <addr>` | Bind address | 127.0.0.1 |

**Note:** Bridge modes require `TPIPE_MCP_JSON` environment variable to be set with the MCP JSON configuration. Use the TPipe-MCP standalone JAR, not the main TPipe JAR.

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
- Attempts to convert PCP context using PcpToMcpConverter
- Returns ConversionResult with success=true if conversion succeeded
- Note: This extension method is primarily for error reporting; the actual MCP conversion is performed by `PcpToMcpConverter.convert()` which returns `McpRequest` directly
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

## MCP Server Hosting

The MCP Server Hosting classes enable TPipe to act as an MCP server, exposing registered PCP functions as MCP tools to connected MCP clients. This allows MCP-compatible clients (such as Claude Desktop, Cursor, and other MCP-enabled applications) to invoke TPipe functions directly.

### McpCapabilityConfig

Feature gates and ServerCapabilities builder for MCP server configuration.

```kotlin
data class McpCapabilityConfig(
    val toolsEnabled: Boolean = true,
    val resourcesEnabled: Boolean = true,
    val promptsEnabled: Boolean = true,
    val loggingEnabled: Boolean = false,
    val completionsEnabled: Boolean = false
)
```

#### Public Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `toolsEnabled` | Boolean | true | Enable tools capability |
| `resourcesEnabled` | Boolean | true | Enable resources capability |
| `promptsEnabled` | Boolean | true | Enable prompts capability |
| `loggingEnabled` | Boolean | false | Enable logging capability |
| `completionsEnabled` | Boolean | false | Enable completions capability |

#### Public Functions

**`buildServerCapabilities(): ServerCapabilities`**
Builds ServerCapabilities object based on the feature flags.

---

### McpToolRegistry

Handles MCP `tools/list` and `tools/call` requests by bridging to FunctionRegistry and PcpRegistry.

```kotlin
class McpToolRegistry(private val pcpContext: PcpContext)
```

#### Public Functions

**`listTools(): List<Tool>`**
Handles `tools/list` request. Returns list of all registered functions as MCP tools with JSON Schema input schemas.

**`callTool(name: String, arguments: Map<String, String>): CallToolResult`**
Handles `tools/call` request. Converts CallToolRequest to PCP request, executes via PcpRegistry, and maps response back to CallToolResult.

**Behavior:**
- Converts function name and arguments to PcPRequest format
- Executes via PcpRegistry.executeRequests()
- Maps success/error results to CallToolResult
- Returns content as TextContent with isError flag

---

### McpPromptProvider

Handles MCP `prompts/list` and `prompts/get` by bridging to PCP function registry.

```kotlin
class McpPromptProvider(private val pcpContext: PcpContext)
```

#### Public Functions

**`listPrompts(): List<Prompt>`**
Handles `prompts/list` request. Returns functions starting with `prompt_` prefix as MCP prompts.

**`getPrompt(name: String, arguments: Map<String, String>): GetPromptResult`**
Handles `prompts/get` request. Returns prompt template with resolved arguments.

**Behavior:**
- Filters tpipeOptions by "prompt_" prefix for list
- Resolves arguments and builds prompt text from function description
- Returns GetPromptResult with formatted prompt messages

---

### McpResourceProvider (Main TPipe)

Handles MCP `resources/list` and `resources/read` by bridging to PCP stdio options.

**Location:** `src/main/kotlin/com/TTT/MCP/Server/McpResourceProvider.kt`

```kotlin
class McpResourceProvider(private val pcpContext: PcpContext)
```

#### Public Functions

**`listResources(): ListResourcesResult`**
Handles `resources/list` request. Returns all stdio options as MCP resources.

**`readResource(uri: String): ReadResourceResult`**
Handles `resources/read` request. Reads content via StdioExecutor for file:// and http:// URIs.

**Behavior:**
- Maps URI schemes to appropriate stdio commands:
  - `file://` → `cat`
  - `head`/`tail` commands → `file://`
  - `http://`/`https://` → `curl`
  - Unknown commands → `stdio://` scheme
- Respects `TPIPE_MCP_ALLOWED_FILE_PATHS` environment variable for path restrictions
- Returns content as BlobResourceContents

**Path Security:**
- Defaults to `user.dir` and `/tmp` if `TPIPE_MCP_ALLOWED_FILE_PATHS` not set
- Canonicalizes paths to prevent path traversal attacks

---

### McpSessionManager

Manages multiple concurrent MCP client sessions using MCP SDK's server.sessions and TPipe's P2PConcurrencyMode.

```kotlin
class McpSessionManager(
    private val server: Server,
    private val pcpContext: PcpContext,
    private val concurrencyMode: P2PConcurrencyMode = P2PConcurrencyMode.SHARED
)
```

#### Public Properties

| Property | Type | Description |
|----------|------|-------------|
| `concurrencyMode` | P2PConcurrencyMode | SHARED (one context, default) or ISOLATED (clone per session) |

#### Public Functions

**`getActiveSessionIds(): Set<String>`**
Get all active session IDs.

**`getSessionCount(): Int`**
Get current session count.

**`getContextForSession(sessionId: String): PcpContext`**
Get context for a specific session. Returns shared context for SHARED mode, or session-specific clone for ISOLATED mode.

**`getClientInfoForSession(sessionId: String): String?`**
Get client info (name) for a specific session.

**`getSessions(): Map<String, ServerSession>`**
Get all sessions as a map of sessionId to ServerSession.

**`registerLifecycleCallbacks(onConnect, onDisconnect)`**
Register lifecycle callbacks for connect/disconnect events. Internally tracks session changes via Set diff and registers per-session onClose callbacks.

**`removeSessionContext(sessionId: String)`**
Remove a session's context (cleanup).

**`clearAllContexts()`**
Clear all session contexts.

#### Protected Functions

**`createIsolatedContext(): PcpContext`** *(protected open)*
Factory method for creating isolated context clones in ISOLATED concurrency mode. Override to customize context creation for per-session isolation.

---

### McpServerHost

Core MCP server wrapper using the MCP Kotlin SDK. Manages server lifecycle with stdio transport for process-based hosting.

```kotlin
class McpServerHost(
    private val pcpContext: PcpContext,
    private val capabilities: ServerCapabilities,
    private val serverInfo: Implementation = Implementation(name = "tpipe", version = "1.0.0")
)
```

#### Public Functions

**`getServer(): Server`**
Get the underlying MCP Server instance.

**`suspend runOnce()`**
Run the server once, processing requests from stdin until it closes. Suitable for one-shot stdio mode.

**`suspend runLoop()`**
Run the server in a loop, continuously processing requests from stdin. Suitable for interactive stdio mode.

**`shutdown()`**
Shutdown the server.

---

### McpHttpHost

HTTP transport wrapper for MCP server hosting. Allows MCP clients to connect over HTTP instead of stdio.

```kotlin
object McpHttpHost
```

#### Public Functions

**`fun run(port: Int, authKey: String? = null, bindAddress: String = "127.0.0.1")`**
Run the MCP server with HTTP transport on the specified port.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `port` | Int | (required) | Port to listen on |
| `authKey` | String? | null | Optional Bearer token authentication |
| `bindAddress` | String | "127.0.0.1" | Address to bind to (local-only by default) |

**Environment Variables:**
- `TPIPE_MCP_HTTP_PORT` - Alternative port configuration
- `TPIPE_MCP_HTTP_AUTH_KEY` - Alternative auth key
- `TPIPE_MCP_HTTP_BIND` - Alternative bind address

**Behavior:**
- Populates PCP context from FunctionRegistry automatically
- Requires Bearer token auth if authKey is non-null and non-blank
- Endpoint: `POST /mcp` with JSON-RPC 2.0

**`fun run(port: Int, capabilities: ServerCapabilities, authKey: String? = null, bindAddress: String = "127.0.0.1")`**
Run the MCP server with custom server capabilities.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `port` | Int | (required) | Port to listen on |
| `capabilities` | ServerCapabilities | - | Custom MCP server capabilities |
| `authKey` | String? | null | Optional Bearer token authentication |
| `bindAddress` | String | "127.0.0.1" | Address to bind to (local-only by default) |

---

### McpStdioHost

STDIO transport wrapper for MCP server hosting. Provides entry points for Application.kt integration.

```kotlin
object McpStdioHost
```

#### Public Functions

**`fun runOnce()`**
Run the MCP server once, processing a single request from stdin. Blocking call.

**`fun runLoop()`**
Run the MCP server in a loop, continuously processing requests from stdin. Blocking call until EOF.

**Behavior:**
- Creates McpServerHost with PCP context populated from FunctionRegistry
- Uses McpCapabilityConfig with all capabilities enabled by default

---

## MCP Bridge Server Hosting

The MCP Bridge Server Hosting classes enable TPipe to act as a bridge server, converting incoming MCP JSON requests to PCP context operations. The bridge differs from the native MCP server by accepting raw MCP JSON configuration and converting it dynamically.

### McpBridgeServerHost

Unified MCP bridge server that combines TPipe-MCP bridge with MCP server functionality.

```kotlin
class McpBridgeServerHost(
    private val mcpJson: String,
    private val capabilities: ServerCapabilities = ServerCapabilities(...),
    private val serverInfo: Implementation = Implementation(name = "tpipe-bridge", version = "1.0.0")
)
```

#### Public Functions

**`handleRequest(request: JsonRpcRequest): JsonRpcResponse`**
Handle a JSON-RPC request by routing to appropriate handler.

**`getServer(): Server`**
Get the underlying MCP Server instance.

**`suspend runOnce()`**
Run the bridge server once with stdio transport.

**`suspend runLoop()`**
Run the bridge server in a loop with stdio transport.

**`shutdown()`**
Shutdown the bridge server.

**Behavior:**
- Accepts MCP JSON string via constructor and converts to PcpContext via McpToPcpConverter
- Routes JSON-RPC requests via McpProtocolHandler
- Sets PCP context globally via PcpRegistry.updateGlobalContext() before handling

---

### McpBridgeHttpHost

HTTP transport wrapper for MCP bridge server hosting.

```kotlin
object McpBridgeHttpHost
```

#### Public Functions

**`fun run(port: Int, authKey: String? = null, bindAddress: String = "127.0.0.1")`**
Run the MCP bridge server with HTTP transport.

**Required Environment Variable:**
- `TPIPE_MCP_JSON` - MCP JSON string containing tools, resources, templates, and prompts

**Behavior:**
- Endpoint: `POST /mcp/bridge` with JSON-RPC 2.0
- Throws IllegalStateException if TPIPE_MCP_JSON is missing

---

### McpBridgeStdioHost

STDIO transport wrapper for MCP bridge server hosting.

```kotlin
object McpBridgeStdioHost
```

#### Public Functions

**`fun runOnce()`**
Run the bridge server once, processing a single request from stdin.

**`fun runLoop()`**
Run the bridge server in a loop, continuously processing requests from stdin.

**Required Environment Variable:**
- `TPIPE_MCP_JSON` - MCP JSON string containing tools, resources, templates, and prompts

---

### McpProtocolHandler

Routes JSON-RPC requests to appropriate MCP handlers based on method name.

```kotlin
class McpProtocolHandler(
    private val pcpContext: PcpContext,
    private val toolRegistry: McpToolRegistry,
    private val resourceProvider: McpResourceProvider,
    private val promptProvider: McpPromptProvider,
    private val rootsProvider: McpRootsProvider? = null,
    private val samplingHandler: McpSamplingHandler? = null,
    private val serverInfo: Implementation = Implementation(name = "tpipe", version = "1.0.0"),
    private val capabilities: ServerCapabilities = ServerCapabilities(...)
)
```

#### Supported Methods

| Method | Handler |
|--------|---------|
| `initialize` | Protocol initialization |
| `tools/list` | McpToolRegistry.listTools() |
| `tools/call` | McpToolRegistry.callTool() |
| `resources/list` | McpResourceProvider.listResources() |
| `resources/read` | McpResourceProvider.readResource() |
| `prompts/list` | McpPromptProvider.listPrompts() |
| `prompts/get` | McpPromptProvider.getPrompt() |
| `roots/list` | McpRootsProvider.listRoots() |
| `sampling/create` | McpSamplingHandler.handleSamplingCreate() |
| `shutdown` | Server shutdown |
| `notifications/initialized` | Client notification |

#### Public Functions

**`route(request: JsonRpcRequest): JsonRpcResponse`**
Routes a JSON-RPC request to the appropriate handler based on method name.

**Behavior:**
- Validates protocol version during initialization
- Enforces rate limiting (10 requests/second per connection)
- Returns error response for unsupported methods
- Tracks server state (INITIALIZING, READY, SHUTTING_DOWN)

**Rate Limiting:**
- Enabled by default (10 requests/second per connection ID)
- Returns error code -32050 when exceeded
- Configurable via RateLimitConfig

#### ServerState Enum

| State | Description |
|-------|-------------|
| `INITIALIZING` | Server is initializing, not yet ready |
| `READY` | Server is ready to accept requests |
| `SHUTTING_DOWN` | Server is shutting down |

**`getServerState(): ServerState`** - Get current server state

---

### RateLimitConfig (nested within McpProtocolHandler)

Configuration for rate limiting in McpProtocolHandler.

```kotlin
data class RateLimitConfig(
    val enabled: Boolean = true,
    val burstSize: Int = 10
)
```

#### Public Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | true | Enable/disable rate limiting |
| `burstSize` | Int | 10 | Maximum requests per second per connection |

---

### McpResourceProvider (TPipe-MCP)

Extended resource provider with subscription support and additional security features.

**Location:** `TPipe-MCP/src/main/kotlin/com/TTT/MCP/Server/McpResourceProvider.kt`

```kotlin
class McpResourceProvider(private val pcpContext: PcpContext)
```

#### Public Functions

**`listResources(): ListResourcesResult`**
Handles `resources/list` request. Returns all stdio options as MCP resources.

**`readResource(uri: String): ReadResourceResult`**
Handles `resources/read` request. Reads content via StdioExecutor for file:// and http:// URIs.

**Behavior:**
- Maps URI schemes to appropriate stdio commands:
  - `file://` → `cat`
  - `http://`/`https://` → `curl`
- Supports resource subscriptions for file watching
- Enforces `TPIPE_MCP_ALLOWED_FILE_PATHS` and `TPIPE_MCP_FORBIDDEN_FILE_PATHS` for path restrictions

**Path Security:**
- Configurable via `TPIPE_MCP_ALLOWED_FILE_PATHS` and `TPIPE_MCP_FORBIDDEN_FILE_PATHS` environment variables
- Uses path canonicalization to prevent path traversal attacks
- Default allows `user.dir` and `/tmp`

---

```kotlin
class McpRootsProvider(private val pcpContext: PcpContext)
```

#### Public Functions

**`listRoots(): ListRootsResult`**
Returns roots configured via environment variable as MCP Root objects.

**Environment Variable:**
- `TPIPE_MCP_ROOTS` - Comma-separated list of root paths

**Behavior:**
- Validates paths and canonicalizes to prevent path traversal
- Only includes paths that exist
- Returns empty list if TPIPE_MCP_ROOTS is not set

---

### McpSamplingHandler

Handler for MCP `sampling/create` requests. Bridges MCP sampling protocol to TPipe's Pipe execution.

```kotlin
class McpSamplingHandler(private val pipe: Pipe)
```

#### Public Functions

**`handleSamplingCreate(request: JsonRpcRequest): Result<JsonObject>`**
Handles a sampling/create request by executing the Pipe with provided parameters.

**Parameters are parsed from `request.params` JSON object:**
| Parameter | Source | Description |
|-----------|--------|-------------|
| `systemPrompt` | `request.params["systemPrompt"]` | Optional system prompt |
| `messages` | `request.params["messages"]` | Array of message objects with role and content |
| `maxTokens` | `request.params["maxTokens"]` | Optional max tokens setting |
| `temperature` | `request.params["temperature"]` | Optional temperature setting |

**Behavior:**
- Parses method name from `request.method` (must be "sampling/create")
- Extracts parameters from `request.params` JSON object
- Builds prompt from systemPrompt and messages array
- Configures pipe with provided parameters
- Executes pipe and returns `Result<JsonObject>` wrapping the response

**Returns `Result.success(JsonObject)` with structure:**
```json
{
  "content": [{"type": "text", "text": "<response text>"}],
  "role": "assistant",
  "model": "tpipe",
  "stopReason": "endTurn"
}
```
Returns `Result.failure` if pipe execution fails.

---

### McpResourceSubscriptionManager

Manages file system watch subscriptions for MCP resource change notifications.

```kotlin
class McpResourceSubscriptionManager(
    private val server: Server,
    private val pcpContext: PcpContext
)
```

#### Public Functions

**`subscribeResource(uri: String): String`**
Subscribe to changes on a file:// resource. Returns subscription ID.

**`unsubscribeResource(subscriptionId: String): Boolean`**
Unsubscribe from a resource. Closes watch service and removes subscription.

**`listSubscriptions(): List<Subscription>`**
List all active subscriptions. Returns list of Subscription objects.

**`setResourceChangedHandler(callback: (uri: String, eventType: String) -> Unit)`**
Set callback for resource change events. Callback receives URI and event type (ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE, or OVERFLOW).

#### Subscription Data Class

```kotlin
data class Subscription(
    val subscriptionId: String,
    val uri: String,
    val subscribedAt: Long
)
```

**Properties:**
- `subscriptionId` - Unique identifier for the subscription
- `uri` - The resource URI being watched
- `subscribedAt` - Timestamp when subscription was created

**`close()`**
Clean up all subscriptions and shutdown executor.

**Behavior:**
- Uses Java NIO FileSystems for file watching
- Supports ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE events
- Sends ResourceUpdatedNotification to all connected sessions
- Handles OVERFLOW events gracefully

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
