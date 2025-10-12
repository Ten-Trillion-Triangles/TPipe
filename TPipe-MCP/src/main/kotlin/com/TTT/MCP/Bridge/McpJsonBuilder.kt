package com.TTT.MCP.Bridge

import com.TTT.MCP.Models.McpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Builder for creating formatted MCP JSON from MCP requests and PCP contexts.
 */
class McpJsonBuilder 
{
    private val json = Json { prettyPrint = true }

    /**
     * Converts an MCP request to formatted JSON string.
     * WARNING: This is internal format only. For wire format use JSON-RPC responses.
     * 
     * @param mcpRequest The MCP request to serialize
     * @return Pretty-printed JSON string
     */
    fun buildMcpJson(mcpRequest: McpRequest): String 
    {
        return json.encodeToString(mcpRequest)
    }

    /**
     * Converts PCP context to MCP JSON format.
     * 
     * @param pcpContext The PCP context to convert and serialize
     * @return Pretty-printed MCP JSON string
     */
    fun buildMcpJsonFromPcp(pcpContext: com.TTT.PipeContextProtocol.PcpContext): String 
    {
        // Convert PCP context to MCP request format
        val converter = PcpToMcpConverter()
        val mcpRequest = converter.convert(pcpContext)
        return buildMcpJson(mcpRequest)
    }
}