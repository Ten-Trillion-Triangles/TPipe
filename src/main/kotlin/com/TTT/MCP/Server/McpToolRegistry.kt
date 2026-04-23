package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.*
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Handles MCP tools/list and tools/call requests by bridging to FunctionRegistry and PcpRegistry.
 *
 * tools/list: Enumerates all registered functions from FunctionRegistry and converts them
 * to MCP tool format with JSON Schema for input parameters.
 *
 * tools/call: Routes tool calls to PcpRegistry.executeRequests() after converting MCP
 * CallToolRequest parameters to PCP callParams format.
 */
class McpToolRegistry(private val pcpContext: PcpContext) {

    /**
     * Handle tools/list request.
     * Returns list of all registered functions as MCP tools with JSON Schema input schemas.
     */
    fun listTools(): List<io.modelcontextprotocol.kotlin.sdk.types.Tool> {
        return FunctionRegistry.listFunctions().map { descriptor ->
            io.modelcontextprotocol.kotlin.sdk.types.Tool(
                name = descriptor.name,
                description = descriptor.signature.description.ifEmpty { null },
                inputSchema = buildInputSchema(descriptor.signature)
            )
        }
    }

    /**
     * Handle tools/call request.
     * Converts CallToolRequest to PCP request, executes via PcpRegistry, and maps
     * response back to CallToolResult.
     */
    fun callTool(name: String, arguments: Map<String, String>): CallToolResult {
        val pcpRequest = PcPRequest(
            tPipeContextOptions = TPipeContextOptions().apply {
                this.functionName = name
            },
            callParams = arguments
        )

        val result = runBlocking {
            // FIX S3: Use injected context, not globalContext
            PcpRegistry.executeRequests(listOf(pcpRequest), pcpContext)
        }

        val firstResult = result.results.firstOrNull()
        val isError = !result.success || (firstResult != null && !firstResult.success)

        val contentText = when {
            firstResult != null && firstResult.success -> firstResult.output
            firstResult != null && !firstResult.success -> "Error: ${firstResult.error ?: "Unknown error"}"
            !result.success -> "Errors: ${result.errors.joinToString("; ")}"
            else -> ""
        }

        return CallToolResult(
            content = listOf(TextContent(contentText)),
            isError = isError
        )
    }

    /**
     * Convert FunctionSignature.parameters to ToolSchema format for MCP input schema.
     */
    private fun buildInputSchema(signature: FunctionSignature): ToolSchema {
        val properties = buildJsonObject { }
        val requiredParams = mutableListOf<String>()

        signature.parameters.forEach { param ->
            val paramSchema = buildParamSchema(param)
            (properties as MutableMap<String, JsonElement>)[param.name] = paramSchema

            if(!param.isOptional){
                requiredParams.add(param.name)
            }
        }

        return ToolSchema(
            properties = properties,
            required = if (requiredParams.isNotEmpty()) requiredParams else null
        )
    }

    /**
     * Map ParamType to JSON Schema type string.
     */
    private fun paramTypeToJsonSchemaType(paramType: ParamType): String {
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
     * Build JSON Schema for a single parameter.
     */
    private fun buildParamSchema(param: ParameterInfo): JsonObject {
        return buildJsonObject {
            put("type", paramTypeToJsonSchemaType(param.type))
            if(param.description.isNotEmpty()){
                put("description", param.description)
            }
            if(param.enumValues.isNotEmpty()){
                put("enum", JsonArray(param.enumValues.map { JsonPrimitive(it) }))
            }
        }
    }
}