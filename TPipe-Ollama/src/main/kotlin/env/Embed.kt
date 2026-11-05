package env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request body for Ollama's current embeddings endpoint `POST /api/embed` (introduced v0.3.4;
 * replaces the legacy `/api/embeddings`).
 *
 * @property model The model to embed with.
 * @property input The content to embed: a string, a list of strings, or a multimodal array.
 *   Modeled as [JsonElement] so all three wire shapes are expressible.
 * @property truncate When true, over-long inputs are truncated; when false, an error is returned
 *   (legacy /api/embeddings used an int truncate).
 * @property dimensions Requested embedding dimensionality.
 * @property keepAlive How long to keep the model loaded.
 * @property options Runtime options.
 */
@Serializable
data class EmbedRequest(
    val model: String,
    val input: JsonElement? = null,
    val truncate: Boolean? = null,
    val dimensions: Int? = null,
    @SerialName("keep_alive")
    val keepAlive: String? = "5m",
    val options: OllamaOptions? = null
)

/**
 * Response body from Ollama's `POST /api/embed`.
 *
 * @property model The model used.
 * @property embeddings A single-number array (one input) or a nested array (batch).
 * @property totalDuration Total time in nanoseconds.
 * @property loadDuration Load time in nanoseconds.
 * @property promptEvalCount Number of prompt tokens.
 */
@Serializable
data class EmbedResponse(
    val model: String? = null,
    val embeddings: JsonElement? = null,
    @SerialName("total_duration")
    val totalDuration: Long? = null,
    @SerialName("load_duration")
    val loadDuration: Long? = null,
    @SerialName("prompt_eval_count")
    val promptEvalCount: Int? = null
)
