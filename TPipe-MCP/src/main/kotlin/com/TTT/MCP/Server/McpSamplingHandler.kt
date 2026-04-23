package com.TTT.MCP.Server

import com.TTT.MCP.Models.JsonRpcRequest
import com.TTT.Pipe.Pipe
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Handler for MCP sampling/create requests.
 * Bridges MCP sampling protocol to TPipe's Pipe execution.
 *
 * @param pipe The Pipe instance used for LLM calls
 */
class McpSamplingHandler(
    private val pipe: Pipe
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    /**
     * Handles a sampling/create request by executing the Pipe with provided parameters.
     *
     * @param request The JSON-RPC request containing sampling parameters
     * @return Result containing the CreateMessageResult as JsonObject, or failure
     */
    fun handleSamplingCreate(request: JsonRpcRequest): Result<JsonObject> {
        return try {
            val params = request.params
                ?: return Result.failure(IllegalArgumentException("Missing params"))

            // Parse systemPrompt
            val systemPrompt = (params["systemPrompt"] as? JsonPrimitive)?.content

            // Parse messages array
            val messagesElement = params["messages"]
            val prompt = buildPromptFromMessages(systemPrompt, messagesElement)

            // Parse maxTokens
            val maxTokens = (params["maxTokens"] as? JsonPrimitive)?.content?.toIntOrNull()

            // Parse temperature
            val temperature = (params["temperature"] as? JsonPrimitive)?.content?.toDoubleOrNull()

            // Configure pipe with parameters
            var configuredPipe = pipe
            if (systemPrompt != null) {
                configuredPipe = configuredPipe.setSystemPrompt(systemPrompt)
            }
            if (maxTokens != null) {
                configuredPipe = configuredPipe.setMaxTokens(maxTokens)
            }
            if (temperature != null) {
                configuredPipe = configuredPipe.setTemperature(temperature)
            }

            // Execute pipe
            val responseText = runBlocking {
                configuredPipe.execute(prompt)
            }

            // Build CreateMessageResult
            val contentObj = buildJsonObject {
                put("type", "text")
                put("text", responseText)
            }
            val result = buildJsonObject {
                put("content", JsonArray(listOf(contentObj)))
                put("role", "assistant")
                put("model", "tpipe")
                put("stopReason", "endTurn")
            }

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Builds a prompt string from system prompt and messages array.
     *
     * @param systemPrompt Optional system prompt
     * @param messagesElement JSON element containing messages array
     * @return Formatted prompt string
     */
    private fun buildPromptFromMessages(systemPrompt: String?, messagesElement: JsonElement?): String {
        val sb = StringBuilder()

        // Add system prompt if provided
        if (!systemPrompt.isNullOrBlank()) {
            sb.append("System: $systemPrompt\n")
        }

        // Process messages if provided
        if (messagesElement != null && messagesElement is JsonArray) {
            for (messageElement in messagesElement) {
                if (messageElement is JsonObject) {
                    val role = (messageElement["role"] as? JsonPrimitive)?.content ?: "user"
                    val content = (messageElement["content"] as? JsonPrimitive)?.content ?: ""

                    when (role.lowercase()) {
                        "user" -> sb.append("User: $content\n")
                        "assistant" -> sb.append("Assistant: $content\n")
                        "system" -> sb.append("System: $content\n")
                        else -> sb.append("$role: $content\n")
                    }
                }
            }
        }

        // Add final assistant prompt if no content yet
        if (sb.isEmpty()) {
            sb.append(" ")
        }

        return sb.toString()
    }
}