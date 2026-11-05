package env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A single OpenRouter model entry from `GET /models` / `GET /models/{id}`.
 *
 * @property id OpenRouter model slug (e.g. "anthropic/claude-3-5-sonnet").
 * @property name Human-readable model name.
 * @property contextLength The model's context window in tokens (null when unknown).
 * @property supportedParameters Model-specific parameter overrides supported by the model.
 * @property pricing Per-token / per-request pricing (free-form [JsonObject]).
 * @property huggingFaceId Upstream Hugging Face identifier, when present.
 * @property reasoning Model reasoning capability descriptor, when present.
 */
@Serializable
data class OpenRouterModel(
    val id: String,
    val name: String? = null,
    @SerialName("context_length")
    val contextLength: Int? = null,
    @SerialName("supported_parameters")
    val supportedParameters: List<String>? = null,
    val pricing: JsonObject? = null,
    @SerialName("hugging_face_id")
    val huggingFaceId: String? = null,
    val reasoning: JsonObject? = null
)

/**
 * `GET /models` list response.
 *
 * @property data The list of models.
 * @property totalCount Total model count, when provided.
 */
@Serializable
data class OpenRouterModelsResponse(
    val data: List<OpenRouterModel> = emptyList(),
    @SerialName("total_count")
    val totalCount: Int? = null
)

/**
 * `GET /models/{id}` single-model response.
 *
 * @property data The model entry.
 */
@Serializable
data class OpenRouterModelResponse(
    val data: OpenRouterModel
)
