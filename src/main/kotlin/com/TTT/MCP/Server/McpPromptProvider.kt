package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.PcpRegistry
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking

/**
 * Handles MCP prompts/list and prompts/get by bridging to PCP function registry.
 *
 * prompts/list: Filters tpipeOptions by "prompt_" prefix
 * prompts/get: Returns prompt template with arguments from FunctionSignature
 */
class McpPromptProvider(private val pcpContext: PcpContext) {

    /**
     * Handle prompts/list request.
     * Returns functions starting with "prompt_" as MCP prompts.
     */
    fun listPrompts(): List<Prompt> {
        return pcpContext.tpipeOptions
            .filter { it.functionName.startsWith("prompt_") }
            .map { option ->
                val name = option.functionName.removePrefix("prompt_")
                Prompt(
                    name = name,
                    description = option.description.takeIf { it.isNotBlank() },
                    arguments = option.params.map { (paramName, paramInfo) ->
                        PromptArgument(
                            name = paramName,
                            description = paramInfo.description,
                            required = paramInfo.isRequired
                        )
                    }
                )
            }
    }

    /**
     * Handle prompts/get request.
     * Returns prompt template with resolved arguments.
     */
    fun getPrompt(name: String, arguments: Map<String, String>): GetPromptResult {
        val fullName = "prompt_$name"
        val function = FunctionRegistry.getFunction(fullName)
            ?: return GetPromptResult(
                messages = listOf(
                    PromptMessage(
                        role = Role.User,
                        content = TextContent(text = "Error: Prompt '$name' not found")
                    )
                )
            )

        // Build prompt text from function description and arguments
        val description = function.signature.description
        val argText = arguments.entries.joinToString(", ") { "${it.key}=${it.value}" }
        val promptText = buildString {
            append(description)
            if(argText.isNotEmpty()){
                append("\nArguments: $argText")
            }
        }

        return GetPromptResult(
            messages = listOf(
                PromptMessage(
                    role = Role.User,
                    content = TextContent(text = promptText)
                )
            )
        )
    }
}