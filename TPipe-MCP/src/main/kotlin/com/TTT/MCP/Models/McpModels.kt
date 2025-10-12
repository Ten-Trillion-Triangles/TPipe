package com.TTT.MCP.Models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Represents an MCP tool definition with function name, description, and input schema.
 * 
 * @property name The name of the tool/function
 * @property description Optional description of what the tool does
 * @property inputSchema JSON schema defining the tool's input parameters
 * @property outputSchema Optional JSON schema defining the tool's output format
 */
@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null
)

/**
 * Represents an MCP resource definition for file or data access.
 * 
 * @property uri The URI/path to the resource
 * @property name The name of the resource
 * @property description Optional description of the resource
 * @property mimeType Optional MIME type of the resource
 */
@Serializable
data class McpResource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null
)

/**
 * Represents a complete MCP request containing tools, resources, and prompts.
 * 
 * @property tools List of available tools/functions
 * @property resources List of available resources
 * @property prompts Map of prompt templates
 */
@Serializable
data class McpRequest(
    val tools: List<McpTool> = emptyList(),
    val resources: List<McpResource> = emptyList()
)