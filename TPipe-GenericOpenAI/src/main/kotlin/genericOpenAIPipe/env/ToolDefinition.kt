package genericOpenAIPipe.env

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Tool definition for function calling.
 *
 * @property type Tool type (currently only "function" is supported).
 *   The field is marked [EncodeDefault.Mode.ALWAYS] so that when callers rely
 *   on the default value `"function"`, it is still emitted on the wire. This
 *   is required because the request serializer uses `encodeDefaults = false`
 *   (see `Util.serialize()`), and MiniMax / OpenAI providers reject the tool
 *   definition with `"invalid tool type"` when `"type"` is missing from the
 *   JSON payload.
 * @property function Function schema definition
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ToolDefinition(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
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