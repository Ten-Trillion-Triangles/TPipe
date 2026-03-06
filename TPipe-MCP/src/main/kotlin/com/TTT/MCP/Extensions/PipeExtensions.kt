package com.TTT.MCP.Extensions

import com.TTT.Pipe.Pipe
import com.TTT.MCP.Bridge.*
import com.TTT.MCP.Models.ConversionResult

/**
 * Sets the MCP context for this pipe by converting MCP JSON to PCP format.
 * 
 * @param mcpJson The MCP JSON string containing tools, resources, and prompts
 * @return This Pipe object for method chaining
 * @throws Exception if the MCP JSON is malformed or conversion fails
 */
fun Pipe.setMcpContext(mcpJson: String): Pipe 
{
    // Parse MCP JSON into structured request
    val converter = McpToPcpConverter()
    val parser = McpJsonParser()
    val mcpRequest = parser.parseJson(mcpJson)
    // Convert MCP request to PCP context format
    val pcpContext = converter.convert(mcpRequest)
    // Apply the converted context to this pipe
    this.setPcPContext(pcpContext)
    return this
}

/**
 * Converts MCP JSON to PCP context and applies it to this pipe with error handling.
 * 
 * @param mcpJson The MCP JSON string to convert and apply
 * @return ConversionResult indicating success/failure with details
 */
fun Pipe.convertAndApplyMcp(mcpJson: String): ConversionResult 
{
    return try {
        // Attempt to parse and convert MCP JSON to PCP context
        val converter = McpToPcpConverter()
        val parser = McpJsonParser()
        val mcpRequest = parser.parseJson(mcpJson)
        val pcpContext = converter.convert(mcpRequest)
        // Apply the converted context to this pipe
        this.setPcPContext(pcpContext)
        ConversionResult(success = true, pcpContext = pcpContext)
    }
    catch(e: Exception)
    {
        // Return error result if conversion fails
        ConversionResult(
            success = false,
            errors = listOf("Conversion failed: ${e.message}")
        )
    }
}

/**
 * Exports the current PCP context as MCP JSON format.
 * 
 * @return MCP JSON string representation of the current PCP context
 */
fun Pipe.exportToMcp(): String 
{
    // Build MCP JSON from the current PCP context
    val builder = McpJsonBuilder()
    return builder.buildMcpJsonFromPcp(this.pcpContext)
}

/**
 * Converts the current PCP context to MCP format with error handling.
 * 
 * @return ConversionResult containing the conversion status and any warnings/errors
 */
fun Pipe.convertPcpToMcp(): ConversionResult 
{
    return try {
        // Convert current PCP context to MCP request format
        val converter = PcpToMcpConverter()
        val mcpRequest = converter.convert(this.pcpContext)
        ConversionResult(
            success = true,
            pcpContext = this.pcpContext,
            warnings = listOf("Exported MCP JSON from PCP context")
        )
    }
    catch(e: Exception)
    {
        // Return error result if conversion fails
        ConversionResult(
            success = false,
            errors = listOf("PCP to MCP conversion failed: ${e.message}")
        )
    }
}