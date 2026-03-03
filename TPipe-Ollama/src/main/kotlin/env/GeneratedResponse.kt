package env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request body to generate text using Ollama's /api/generate endpoint.
 * @property model The model name.
 * @property prompt The user prompt.
 * @property system The system prompt.
 * @property template The prompt template.
 * @property context The session context.
 * @property stream Whether to stream.
 * @property raw Whether to use raw mode.
 * @property format The output format.
 * @property images List of base64 images.
 * @property options Inference options.
 * @property keepAlive Keep-alive duration.
 */
@Serializable
data class GeneratedRequest(
    val model: String,
    val prompt: String,
    val system: String? = null,
    val template: String? = null,
    val context: List<Int>? = null,
    val stream: Boolean = false,
    val raw: Boolean = false,
    val format: JsonElement? = null,
    val images: List<String>? = null,
    val options: OllamaOptions? = null,
    @SerialName("keep_alive")
    val keepAlive: String? = "5m"
)

/**
 * Response body from Ollama's /api/generate endpoint.
 * @property model The model used.
 * @property createdAt Timestamp.
 * @property response The generated text.
 * @property done Whether generation is finished.
 * @property context The session context.
 * @property totalDuration Total time.
 * @property loadDuration Load time.
 * @property promptEvalCount Prompt token count.
 * @property promptEvalDuration Prompt eval time.
 * @property evalCount Eval token count.
 * @property evalDuration Eval time.
 */
@Serializable
data class GeneratedResponse(
    val model: String,
    @SerialName("created_at")
    val createdAt: String,
    val response: String? = null,
    val done: Boolean,
    val context: List<Int>? = null,
    @SerialName("total_duration")
    val totalDuration: Long? = null,
    @SerialName("load_duration")
    val loadDuration: Long? = null,
    @SerialName("prompt_eval_count")
    val promptEvalCount: Int? = null,
    @SerialName("prompt_eval_duration")
    val promptEvalDuration: Long? = null,
    @SerialName("eval_count")
    val evalCount: Int? = null,
    @SerialName("eval_duration")
    val evalDuration: Long? = null
)

/**
 * Request body for Ollama's /api/chat endpoint.
 * @property model The model name.
 * @property messages The message history.
 * @property tools Available tools.
 * @property format Output format.
 * @property stream Whether to stream.
 * @property options Inference options.
 * @property keepAlive Keep-alive duration.
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val tools: List<OllamaTool>? = null,
    val format: JsonElement? = null,
    val stream: Boolean = false,
    val options: OllamaOptions? = null,
    @SerialName("keep_alive")
    val keepAlive: String? = "5m"
)

/**
 * Response body from Ollama's /api/chat endpoint.
 * @property model The model used.
 * @property createdAt Timestamp.
 * @property message The response message.
 * @property done Whether finished.
 * @property doneReason Reason for stopping.
 * @property totalDuration Total time.
 * @property loadDuration Load time.
 * @property promptEvalCount Prompt token count.
 * @property promptEvalDuration Prompt eval time.
 * @property evalCount Eval token count.
 * @property evalDuration Eval time.
 */
@Serializable
data class ChatResponse(
    val model: String,
    @SerialName("created_at")
    val createdAt: String,
    val message: OllamaMessage? = null,
    val done: Boolean,
    @SerialName("done_reason")
    val doneReason: String? = null,
    @SerialName("total_duration")
    val totalDuration: Long? = null,
    @SerialName("load_duration")
    val loadDuration: Long? = null,
    @SerialName("prompt_eval_count")
    val promptEvalCount: Int? = null,
    @SerialName("prompt_eval_duration")
    val promptEvalDuration: Long? = null,
    @SerialName("eval_count")
    val evalCount: Int? = null,
    @SerialName("eval_duration")
    val evalDuration: Long? = null
)

/**
 * Response body from Ollama for version information.
 * @property version The version string.
 */
@Serializable
data class versionResponce(val version: String?)
