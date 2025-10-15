package env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/**
 * Request body to generate text using Ollama. Inspect directly in code to see what each variable does.
 */
@Serializable
data class GeneratedRequest(
    var model: String, //Ollama model name to load.
    var from : String = "", //Used when creating a new model.
    var prompt: String, //User prompt.
    var system: String = "", //System prompt.
    var suffix: String = "",
    var format: String = "", //Which form of structured outputs to use.
    var raw : Boolean = false,
    var stream: Boolean = false,
    var think: Boolean = false,
    var template: String = "")
{
    /**
     * Enable or disable streaming mode for the generated text.
     *
     * @param stream Set to true to enable streaming mode, set to false in order to disable.
     * @return This object, allowing method chaining.
     */
    fun setStreamingMode(stream: Boolean) : GeneratedRequest
    {
        this.stream = stream
        return this
    }

    /**
     * Enable or disable raw mode for the generated text.
     *
     * If raw mode is enabled, the generated text will not be processed by Ollama's post processor.
     *
     * @param raw Set to true to enable raw mode, set to false in order to disable.
     * @return This object, allowing method chaining.
     */
    fun setRawMode(raw: Boolean) : GeneratedRequest
    {
        this.raw = raw
        return this
    }
}

/**
 * Response body from Ollama for generated text.
 */
@Serializable
data class GeneratedResponse(
    val model: String?,

    @SerialName("created_at")
    val createdAt: String?,
    val response: String?,
    val done: Boolean?,

    @SerialName("done_reason")
    val doneReason: String?,
    val context: List<Int>?,

    @SerialName("total_duration")
    val totalDuration: Long?,

    @SerialName("load_duration")
    val loadDuration: Long?,

    @SerialName("prompt_eval_count")
    val promptEvalCount: Int?,

    @SerialName("prompt_eval_duration")
    val promptEvalDuration: Long?,

    @SerialName("eval_count")
    val evalCount: Int?,

    @SerialName("eval_duration")
    val evalDuration: Long?,

    val options: OllamaOptions?
)

/**
 * Response body from Ollama for version information.
 */
@Serializable
data class versionResponce(val version: String?)


