package com.TTT.MCP.Bridge

import com.TTT.MCP.Models.McpRequest
import com.TTT.MCP.Models.McpTool
import com.TTT.MCP.Models.McpResource
import com.TTT.PipeContextProtocol.*
import kotlinx.serialization.json.*

/**
 * Converts PCP context back to MCP request format for reverse compatibility.
 */
class PcpToMcpConverter 
{
    /**
     * Converts PCP context to MCP request format.
     * 
     * @param pcpContext The PCP context to convert
     * @return McpRequest with converted tools and resources
     */
    fun convert(pcpContext: PcpContext): McpRequest 
    {
        // Convert PCP context components to MCP request format
        return McpRequest(
            tools = convertTPipeOptions(pcpContext.tpipeOptions),
            resources = convertStdioOptions(pcpContext.stdioOptions)
        )
    }

    /**
     * Converts TPipe context options back to MCP tools.
     * 
     * @param tpipeOptions List of TPipe context options
     * @return List of MCP tools
     */
    private fun convertTPipeOptions(tpipeOptions: List<TPipeContextOptions>): List<McpTool> 
    {
        // Convert each TPipe option to an MCP tool
        return tpipeOptions.map { option ->
            McpTool(
                name = option.functionName,
                description = option.description.takeIf { it.isNotBlank() },
                // Build JSON schema from parameter definitions
                inputSchema = buildInputSchema(option.params)
            )
        }
    }

    /**
     * Converts stdio context options back to MCP resources.
     * 
     * @param stdioOptions List of stdio context options
     * @return List of MCP resources
     */
    private fun convertStdioOptions(stdioOptions: List<StdioContextOptions>): List<McpResource> 
    {
        // Convert each stdio option to an MCP resource
        return stdioOptions.map { option ->
            McpResource(
                // Map command and args back to appropriate URI
                uri = mapCommandToUri(option.command, option.args),
                name = option.command,
                description = option.description.takeIf { it.isNotBlank() },
                mimeType = inferMimeType(option.command)
            )
        }
    }

    /**
     * Builds JSON schema from parameter definitions.
     * 
     * @param params Map of parameter names to type information
     * @return JsonObject representing the input schema
     */
    private fun buildInputSchema(params: Map<String, Triple<ParamType, String, List<String>>>): JsonObject 
    {
        val requiredFields = mutableListOf<String>()
        
        // Build properties object from parameter definitions
        val properties = buildJsonObject {
            params.forEach { (paramName, paramInfo) ->
                // Assume all parameters are required for now
                requiredFields.add(paramName)
                
                put(paramName, buildJsonObject {
                    val jsonType = mapParamTypeToJsonType(paramInfo.first)
                    put("type", jsonType)
                    
                    if (paramInfo.second.isNotBlank()) {
                        put("description", paramInfo.second)
                    }
                    
                    // Add enum values if specified
                    if (paramInfo.third.isNotEmpty()) {
                        put("enum", JsonArray(paramInfo.third.map { JsonPrimitive(it) }))
                    }
                    
                    // Add items for array types
                    if (jsonType == "array") {
                        put("items", buildJsonObject {
                            put("type", "string") // Default to string items
                        })
                    }
                })
            }
        }

        // Return complete JSON schema object with required fields
        return buildJsonObject {
            put("type", "object")
            put("properties", properties)
            if (requiredFields.isNotEmpty()) {
                put("required", JsonArray(requiredFields.map { JsonPrimitive(it) }))
            }
        }
    }

    /**
     * Maps PCP parameter types back to JSON schema types.
     * 
     * @param paramType The PCP parameter type
     * @return JSON schema type string
     */
    private fun mapParamTypeToJsonType(paramType: ParamType): String 
    {
        // Map PCP parameter types back to JSON schema types
        return when (paramType) {
            ParamType.String -> "string"
            ParamType.Int -> "integer"
            ParamType.Float -> "number"
            ParamType.Bool -> "boolean"
            ParamType.List -> "array"
            ParamType.Map, ParamType.Object -> "object"
            ParamType.Enum -> "string"
            ParamType.Any -> "string"
        }
    }

    /**
     * Maps shell commands back to appropriate URIs.
     * 
     * @param command The shell command
     * @param args Command arguments
     * @return Appropriate URI for the command
     */
    private fun mapCommandToUri(command: String, args: List<String>): String 
    {
        // Map shell commands back to appropriate URI schemes
        return when (command) {
            "cat", "head", "tail" -> "file://${args.firstOrNull() ?: ""}"
            "curl" -> args.firstOrNull() ?: "http://localhost"
            else -> "stdio://$command"
        }
    }

    /**
     * Infers MIME type based on shell command.
     * 
     * @param command The shell command
     * @return Appropriate MIME type or null if unknown
     */
    private fun inferMimeType(command: String): String? 
    {
        // Infer appropriate MIME type based on shell command
        return when (command) {
            "cat", "head", "tail" -> "text/plain"
            "curl" -> "application/json"
            else -> null
        }
    }
}