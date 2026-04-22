package com.TTT.MCP.Bridge

import com.TTT.MCP.Models.McpRequest
import com.TTT.MCP.Models.McpTool
import com.TTT.MCP.Models.McpResource
import kotlinx.serialization.json.*

/**
 * Parser for MCP JSON format that extracts tools, resources, and prompts.
 */
class McpJsonParser
{
    /**
     * Parses MCP JSON string into structured McpRequest object.
     *
     * @param mcpJson The MCP JSON string to parse
     * @return McpRequest containing parsed tools, resources, and prompts
     * @throws Exception if JSON is malformed or invalid
     */
    fun parseJson(mcpJson: String): McpRequest
    {
        // Parse JSON string into JsonObject for structured access
        val json = Json.parseToJsonElement(mcpJson).jsonObject
        // Extract and construct McpRequest with all components
        return McpRequest(
            tools = extractTools(json),
            resources = extractResources(json),
            resourceTemplates = extractResourceTemplates(json),
            prompts = extractPrompts(json)
        )
    }

    /**
     * Validates that the JSON object contains at least one MCP component.
     *
     * @param json The JSON object to validate
     * @return True if the JSON contains tools or resources
     */
    fun validateMcpStructure(json: JsonObject): Boolean
    {
        // Check if JSON contains valid MCP components
        return json.containsKey("tools") || json.containsKey("resources") || json.containsKey("prompts") || json.containsKey("resourceTemplates")
    }

    /**
     * Extracts MCP tools from the JSON object.
     *
     * @param json The JSON object containing tools array
     * @return List of McpTool objects, empty if no tools found
     * @throws IllegalArgumentException if tool has invalid inputSchema
     */
    fun extractTools(json: JsonObject): List<McpTool>
    {
        // Extract tools array and convert each element to McpTool
        return json["tools"]?.jsonArray?.mapNotNull { element ->
            val toolObj = element.jsonObject
            // Extract required name field, skip if missing
            val name = toolObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val description = toolObj["description"]?.jsonPrimitive?.content

            // Validate inputSchema exists and has type "object"
            val inputSchema = toolObj["inputSchema"]?.jsonObject
                ?: throw IllegalArgumentException("Tool '$name' missing required inputSchema")

            val schemaType = inputSchema["type"]?.jsonPrimitive?.content
            if(schemaType != "object")
            {
                throw IllegalArgumentException("Tool '$name' inputSchema must have type 'object', got '$schemaType'")
            }

            // Extract optional outputSchema
            val outputSchema = toolObj["outputSchema"]?.jsonObject

            // Extract optional icons and annotations
            val icons = toolObj["icons"]?.jsonArray?.map {
                Json.decodeFromJsonElement<com.TTT.MCP.Models.McpIcon>(it)
            }
            val annotations = toolObj["annotations"]?.let {
                Json.decodeFromJsonElement<com.TTT.MCP.Models.McpAnnotations>(it)
            }

            McpTool(name, description, inputSchema, outputSchema, icons, annotations)
        } ?: emptyList()
    }

    /**
     * Extracts MCP resources from the JSON object.
     *
     * @param json The JSON object containing resources array
     * @return List of McpResource objects, empty if no resources found
     * @throws IllegalArgumentException if resource missing required fields
     */
    fun extractResources(json: JsonObject): List<McpResource>
    {
        // Extract resources array and convert each element to McpResource
        return json["resources"]?.jsonArray?.mapNotNull { element ->
            val resourceObj = element.jsonObject
            // Extract required fields, throw if missing
            val uri = resourceObj["uri"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Resource missing required 'uri' field")
            val name = resourceObj["name"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Resource missing required 'name' field")
            val description = resourceObj["description"]?.jsonPrimitive?.content
            val mimeType = resourceObj["mimeType"]?.jsonPrimitive?.content
            val annotations = resourceObj["annotations"]?.let {
                Json.decodeFromJsonElement<com.TTT.MCP.Models.McpAnnotations>(it)
            }
            com.TTT.MCP.Models.McpResource(uri, name, description, mimeType, annotations)
        } ?: emptyList()
    }

    /**
     * Extracts MCP resource templates from the JSON object.
     *
     * @param json The JSON object containing resourceTemplates array
     * @return List of McpResourceTemplate objects, empty if none found
     * @throws IllegalArgumentException if template missing required fields
     */
    fun extractResourceTemplates(json: JsonObject): List<com.TTT.MCP.Models.McpResourceTemplate>
    {
        return json["resourceTemplates"]?.jsonArray?.mapNotNull { element ->
            val templateObj = element.jsonObject
            val uriTemplate = templateObj["uriTemplate"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Resource template missing required 'uriTemplate' field")
            val name = templateObj["name"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Resource template missing required 'name' field")
            val description = templateObj["description"]?.jsonPrimitive?.content
            val mimeType = templateObj["mimeType"]?.jsonPrimitive?.content
            val annotations = templateObj["annotations"]?.let {
                Json.decodeFromJsonElement<com.TTT.MCP.Models.McpAnnotations>(it)
            }
            com.TTT.MCP.Models.McpResourceTemplate(uriTemplate, name, description, mimeType, annotations)
        } ?: emptyList()
    }

    /**
     * Extracts prompt templates from the JSON object.
     *
     * @param json The JSON object containing prompts array
     * @return List of McpPrompt objects, empty if none found
     * @throws IllegalArgumentException if prompt missing required fields
     */
    fun extractPrompts(json: JsonObject): List<com.TTT.MCP.Models.McpPrompt>
    {
        return json["prompts"]?.jsonArray?.mapNotNull { element ->
            val promptObj = element.jsonObject
            val name = promptObj["name"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Prompt missing required 'name' field")
            val description = promptObj["description"]?.jsonPrimitive?.content
            val arguments = promptObj["arguments"]?.jsonArray?.map { argElement ->
                val argObj = argElement.jsonObject
                com.TTT.MCP.Models.McpPromptArgument(
                    name = argObj["name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Prompt argument missing 'name'"),
                    description = argObj["description"]?.jsonPrimitive?.content,
                    required = argObj["required"]?.jsonPrimitive?.boolean ?: false
                )
            }
            val annotations = promptObj["annotations"]?.let {
                Json.decodeFromJsonElement<com.TTT.MCP.Models.McpAnnotations>(it)
            }
            com.TTT.MCP.Models.McpPrompt(name, description, arguments, annotations)
        } ?: emptyList()
    }
}