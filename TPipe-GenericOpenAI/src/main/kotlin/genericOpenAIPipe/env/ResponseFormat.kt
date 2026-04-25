package genericOpenAIPipe.env

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Response format constraint.
 *
 * @property type Format type: "text", "json_object", or "json_schema"
 * @property jsonSchema JSON Schema definition (required for json_schema type)
 */
@Serializable
data class ResponseFormat(
    val type: String,
    val jsonSchema: JsonObject? = null
)