package com.TTT.MCP.Models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Represents an icon for MCP entities.
 *
 * @property src The URI or data URI of the icon
 * @property type Optional MIME type of the icon
 */
@Serializable
data class McpIcon(
    val src: String,
    val type: String? = null
)

/**
 * Represents annotations for MCP entities to provide metadata for models and clients.
 *
 * @property audience Optional list of intended audiences (e.g., "user", "assistant")
 * @property priority Optional priority hint for the entity (0.0 to 1.0)
 */
@Serializable
data class McpAnnotations(
    val audience: List<String>? = null,
    val priority: Double? = null
)

/**
 * Represents an MCP tool definition with function name, description, and input schema.
 * 
 * @property name The name of the tool/function
 * @property description Optional description of what the tool does
 * @property inputSchema JSON schema defining the tool's input parameters
 * @property outputSchema Optional JSON schema defining the tool's output format
 * @property icons Optional list of icons for the tool
 * @property annotations Optional metadata annotations
 */
@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val icons: List<McpIcon>? = null,
    val annotations: McpAnnotations? = null
)

/**
 * Represents an MCP resource definition for file or data access.
 * 
 * @property uri The URI/path to the resource
 * @property name The name of the resource
 * @property description Optional description of the resource
 * @property mimeType Optional MIME type of the resource
 * @property annotations Optional metadata annotations
 */
@Serializable
data class McpResource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null,
    val annotations: McpAnnotations? = null
)

/**
 * Represents an MCP resource template for dynamic resource generation.
 *
 * @property uriTemplate RFC 6570 URI template
 * @property name The name of the template
 * @property description Optional description of the template
 * @property mimeType Optional MIME type of the generated resource
 * @property annotations Optional metadata annotations
 */
@Serializable
data class McpResourceTemplate(
    val uriTemplate: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null,
    val annotations: McpAnnotations? = null
)

/**
 * Represents an argument for an MCP prompt.
 *
 * @property name The name of the argument
 * @property description Optional description of the argument
 * @property required Whether the argument is required
 */
@Serializable
data class McpPromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false
)

/**
 * Represents an MCP prompt template.
 *
 * @property name The name of the prompt
 * @property description Optional description of the prompt
 * @property arguments Optional list of arguments for the prompt
 * @property annotations Optional metadata annotations
 */
@Serializable
data class McpPrompt(
    val name: String,
    val description: String? = null,
    val arguments: List<McpPromptArgument>? = null,
    val annotations: McpAnnotations? = null
)

/**
 * Represents a complete MCP request/context containing tools, resources, templates, and prompts.
 * 
 * @property tools List of available tools/functions
 * @property resources List of available resources
 * @property resourceTemplates List of available resource templates
 * @property prompts List of available prompts
 */
@Serializable
data class McpRequest(
    val tools: List<McpTool> = emptyList(),
    val resources: List<McpResource> = emptyList(),
    val resourceTemplates: List<McpResourceTemplate> = emptyList(),
    val prompts: List<McpPrompt> = emptyList()
)

/**
 * Represents a JSON-RPC 2.0 error object.
 */
@Serializable
data class McpJsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

/**
 * Represents a JSON-RPC 2.0 request or notification.
 */
@Serializable
data class McpJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

/**
 * Represents a JSON-RPC 2.0 response.
 */
@Serializable
data class McpJsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement?,
    val result: JsonObject? = null,
    val error: McpJsonRpcError? = null
)
