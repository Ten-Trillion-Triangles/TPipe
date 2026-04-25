package genericOpenAIPipe.env

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Tool definition for function calling.
 *
 * @property type Tool type (currently only "function" is supported)
 * @property function Function schema definition
 */
@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionSchema
)

/**
 * Schema definition for a callable function.
 *
 * @property name Function name
 * @property description Function description for the model
 * @property parameters JSON Schema for function parameters
 */
@Serializable
data class FunctionSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject
)