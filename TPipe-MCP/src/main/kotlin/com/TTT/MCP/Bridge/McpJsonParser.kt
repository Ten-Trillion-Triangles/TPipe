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
            resources = extractResources(json)
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
        // Check if JSON contains valid MCP components (no prompts in wire format)
        return json.containsKey("tools") || json.containsKey("resources")
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
            if (schemaType != "object") {
                throw IllegalArgumentException("Tool '$name' inputSchema must have type 'object', got '$schemaType'")
            }
            
            // Extract optional outputSchema
            val outputSchema = toolObj["outputSchema"]?.jsonObject
            
            McpTool(name, description, inputSchema, outputSchema)
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
            McpResource(uri, name, description, mimeType)
        } ?: emptyList()
    }

    /**
     * Extracts prompt templates from the JSON object.
     * NOTE: Prompts should not be in top-level JSON - use JSON-RPC prompts/list method
     * 
     * @param json The JSON object containing prompts
     * @return Map of prompt names to prompt content
     */
    private fun extractPrompts(json: JsonObject): Map<String, String> 
    {
        // Prompts should not be in wire format - return empty
        return emptyMap()
    }
}