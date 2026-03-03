package env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/**
 * Parameters for the Ollama generation and chat APIs.
 *
 * @property model The model name to use.
 * @property prompt The prompt for the generate API.
 * @property messages The conversation history for the chat API.
 * @property images Optional list of base64-encoded images.
 * @property tools Optional list of tools for native tool calling.
 * @property format The format to return a response in (e.g., "json").
 * @property options Model-specific inference parameters.
 * @property stream Whether to stream the response.
 * @property keepAlive How long to keep the model in memory.
 */
@Serializable
data class InputParams(
    val model: String,
    var prompt: String? = null,
    var messages: List<OllamaMessage>? = null,
    var images: List<String>? = null,
    var tools: List<OllamaTool>? = null,
    var format: JsonElement? = null, // Can be "json" or a JsonObject schema
    var options: OllamaOptions? = null,
    var stream: Boolean = false,
    @SerialName("keep_alive")
    var keepAlive: String? = "5m"
)

/**
 * Represents a tool definition for Ollama's native tool calling.
 * @property type The type of tool (always "function").
 * @property function The function definition.
 */
@Serializable
data class OllamaTool(
    val type: String = "function",
    val function: OllamaFunctionDefinition
)

/**
 * Represents a function definition for an Ollama tool.
 * @property name The name of the function.
 * @property description A description of what the function does.
 * @property parameters The parameters schema for the function.
 */
@Serializable
data class OllamaFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)
