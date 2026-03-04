package com.TTT.MCP.Bridge

import com.TTT.MCP.Models.*
import com.TTT.PipeContextProtocol.*
import kotlinx.serialization.json.*

/**
 * Converts MCP requests to PCP context format for TPipe integration.
 */
class McpToPcpConverter 
{
    /**
     * Converts an MCP request to PCP context format.
     * 
     * @param mcpRequest The MCP request containing tools, resources, templates, and prompts
     * @return PcpContext with converted tools, resources, and prompts
     */
    fun convert(mcpRequest: McpRequest): PcpContext 
    {
        // Create new PCP context to populate
        val pcpContext = PcpContext()
        
        // Convert MCP tools to TPipe options and add to context
        convertTools(mcpRequest.tools).forEach { pcpContext.addTPipeOption(it) }
        // Convert MCP resources to stdio options and add to context
        convertResources(mcpRequest.resources).forEach { pcpContext.addStdioOption(it) }
        // Convert MCP resource templates to stdio options (as dynamic commands)
        convertResourceTemplates(mcpRequest.resourceTemplates).forEach { pcpContext.addStdioOption(it) }
        // Convert MCP prompts to TPipe options (as they are templates)
        convertPrompts(mcpRequest.prompts).forEach { pcpContext.addTPipeOption(it) }
        
        return pcpContext
    }

    /**
     * Converts MCP tools to TPipe context options.
     * 
     * @param tools List of MCP tools to convert
     * @return List of TPipeContextOptions
     */
    private fun convertTools(tools: List<McpTool>): List<TPipeContextOptions> 
    {
        // Map each MCP tool to a TPipe context option
        return tools.map { tool ->
            TPipeContextOptions().apply {
                functionName = tool.name
                description = buildString {
                    append(tool.description ?: "")
                    tool.annotations?.let { ann ->
                        if (ann.priority != null) append("\nPriority: ${ann.priority}")
                        if (ann.audience != null) append("\nAudience: ${ann.audience.joinToString()}")
                    }
                }
                // Extract parameter definitions from JSON schema
                params = extractParams(tool.inputSchema)
            }
        }
    }

    /**
     * Converts MCP resources to stdio context options.
     * 
     * @param resources List of MCP resources to convert
     * @return List of StdioContextOptions
     */
    private fun convertResources(resources: List<McpResource>): List<StdioContextOptions> 
    {
        // Map each MCP resource to a stdio context option
        return resources.map { resource ->
            StdioContextOptions().apply {
                // Map URI to appropriate shell command
                command = mapResourceToCommand(resource.uri)
                args = mutableListOf(resource.uri)
                permissions = mutableListOf(Permissions.Read)
                description = resource.description ?: ""
            }
        }
    }

    /**
     * Converts MCP resource templates to stdio context options.
     */
    private fun convertResourceTemplates(templates: List<McpResourceTemplate>): List<StdioContextOptions>
    {
        return templates.map { template ->
            StdioContextOptions().apply {
                command = "mcp_resource_template"
                args = mutableListOf(template.uriTemplate)
                permissions = mutableListOf(Permissions.Read)
                description = "Template: ${template.name}. ${template.description ?: ""}"
            }
        }
    }

    /**
     * Converts MCP prompts to TPipe context options.
     */
    private fun convertPrompts(prompts: List<McpPrompt>): List<TPipeContextOptions>
    {
        return prompts.map { prompt ->
            TPipeContextOptions().apply {
                functionName = "prompt_${prompt.name}"
                description = prompt.description ?: ""
                params = prompt.arguments?.associate { arg ->
                    arg.name to Triple(
                        ParamType.String,
                        arg.description ?: "",
                        emptyList<String>()
                    )
                }?.toMutableMap() ?: mutableMapOf()
            }
        }
    }

    /**
     * Extracts parameter definitions from JSON schema.
     * 
     * @param schema The JSON schema object
     * @return Map of parameter names to type information
     */
    private fun extractParams(schema: JsonObject): MutableMap<String, Triple<ParamType, String, List<String>>> 
    {
        val params = mutableMapOf<String, Triple<ParamType, String, List<String>>>()
        
        // Extract required fields list
        val requiredFields = schema["required"]?.jsonArray?.mapNotNull { 
            it.jsonPrimitive?.content 
        }?.toSet() ?: emptySet()
        
        // Extract properties from JSON schema
        schema["properties"]?.jsonObject?.let { properties ->
            properties.forEach { (paramName: String, paramDef: JsonElement) ->
                val paramObj = paramDef.jsonObject
                // Map JSON schema type to PCP parameter type
                val type = mapJsonSchemaToParamType(paramObj)
                val description = paramObj["description"]?.jsonPrimitive?.content ?: ""
                
                // Extract enum values if present
                val enumValues = paramObj["enum"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive?.content
                } ?: emptyList()
                
                params[paramName] = Triple(type, description, enumValues)
            }
        }
        
        return params
    }

    /**
     * Maps JSON schema types to PCP parameter types.
     * 
     * @param schema The JSON schema object containing type information
     * @return Corresponding ParamType enum value
     */
    private fun mapJsonSchemaToParamType(schema: JsonObject): ParamType 
    {
        // Map JSON schema types to corresponding PCP parameter types
        return when (schema["type"]?.jsonPrimitive?.content) {
            "string" -> ParamType.String
            "integer" -> ParamType.Int
            "number" -> ParamType.Float
            "boolean" -> ParamType.Bool
            "array" -> ParamType.List
            "object" -> ParamType.Map
            else -> ParamType.Any
        }
    }

    /**
     * Maps resource URIs to appropriate shell commands.
     * 
     * @param uri The resource URI to map
     * @return Appropriate shell command for the resource type
     */
    private fun mapResourceToCommand(uri: String): String 
    {
        // Map resource URI schemes to appropriate shell commands
        return when {
            uri.startsWith("file://") -> "cat"
            uri.startsWith("http://") || uri.startsWith("https://") -> "curl"
            else -> "echo"
        }
    }
}
