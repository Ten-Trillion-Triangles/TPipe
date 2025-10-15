package env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration options for LLM inference
 *
 * @property numPredict Maximum number of tokens to predict when generating text
 * @property numContext Size of the context window in tokens
 * @property repeatPenalty Penalizes repeated tokens (1.0 = no penalty, >1.0 = penalty, <1.0 = encourage repetition)
 * @property seed Random number seed for reproducible sampling
 * @property temperature Controls randomness (0.0 = deterministic, 1.0 = more random)
 * @property topK Limits the next token selection to the K most probable tokens
 * @property topP Limits the next token selection to the smallest set whose cumulative probability exceeds P
 * @property minP Sets a minimum base probability threshold for token selection
 * @property typicalP Controls the cumulative probability of the sampled tokens
 * @property tfsZ Tail free sampling parameter (1.0 = disabled, lower values = more conservative sampling)
 * @property mirostat Enables Mirostat sampling (0 = disabled, 1 = Mirostat, 2 = Mirostat 2.0)
 * @property mirostatEta Learning rate for Mirostat
 * @property mirostatTau Controls the balance between coherence and diversity for Mirostat
 * @property repeatLastN Number of tokens to consider for the repeat penalty
 * @property presencePenalty Penalty for new tokens based on their presence in the text so far
 * @property frequencyPenalty Penalty for new tokens based on their frequency in the text so far
 * @property stop List of strings that will stop generation when encountered
 * @property numGpu Number of GPU layers to use (-1 for all)
 * @property mainGpu Main GPU device to use
 * @property numThread Number of threads to use for generation
 * @property numBatch Batch size for prompt processing
 * @property numGpu Number of GPUs to use
 * @property mainGpu Main GPU to use
 * @property lowVram Whether to use low VRAM mode
 * @property vocabOnly Whether to only load the vocabulary (no weights)
 * @property useMmap Whether to use memory-mapped files
 * @property useMlock Whether to lock the model in memory
 * @property numThread Number of threads to use for generation
 */
@Serializable @SerialName("options")
data class OllamaOptions(
    /** Number of tokens to keep from the initial prompt */
    @SerialName("num_keep")
    var numKeep: Int? = null,

    /** Random number seed for reproducibility */
    @SerialName("seed")
    var seed: Long? = null,

    /** Maximum number of tokens to predict when generating text */
    @SerialName("num_predict")
    var numPredict: Int? = null,

    /** Top-k sampling: only keep the top k tokens with highest probability */
    @SerialName("top_k")
    var topK: Int? = null,

    /** Nucleus sampling: limit to the top p probability mass */
    @SerialName("top_p")
    var topP: Double? = null,

    /** Minimum probability for a token to be considered */
    @SerialName("min_p")
    var minP: Float? = null,

    /** Typical probability for sampling */
    @SerialName("typical_p")
    var typicalP: Float? = null,

    /** Number of tokens to consider for the repeat penalty */
    @SerialName("repeat_last_n")
    var repeatLastN: Int? = null,

    /** Temperature for sampling (0.0 to 1.0) */
    @SerialName("temperature")
    var temperature: Double? = null,

    /** Penalty for repeated tokens */
    @SerialName("repeat_penalty")
    var repeatPenalty: Float? = null,

    /** Penalty for new tokens based on presence in the text so far */
    @SerialName("presence_penalty")
    var presencePenalty: Float? = null,

    /** Penalty for new tokens based on their frequency in the text so far */
    @SerialName("frequency_penalty")
    var frequencyPenalty: Float? = null,

    /** Controls the algorithm used for text generation (0=disabled, 1=Mirostat, 2=Mirostat 2.0) */
    @SerialName("mirostat")
    var mirostat: Int? = null,

    /** Target entropy for Mirostat algorithm */
    @SerialName("mirostat_tau")
    var mirostatTau: Float? = null,

    /** Learning rate for Mirostat algorithm */
    @SerialName("mirostat_eta")
    var mirostatEta: Float? = null,

    /** Whether to penalize newline tokens */
    @SerialName("penalize_newline")
    var penalizeNewline: Boolean? = null,

    /** List of strings that will stop the generation */
    @SerialName("stop")
    var stop: List<String>? = null,

    /** Whether to use NUMA optimization */
    @SerialName("numa")
    var numa: Boolean? = null,

    /** Context window size in tokens */
    @SerialName("num_ctx")
    var numCtx: Int? = null,

    /** Batch size for prompt processing */
    @SerialName("num_batch")
    var numBatch: Int? = null,

    /** Number of GPUs to use */
    @SerialName("num_gpu")
    var numGpu: Int? = null,

    /** Main GPU to use */
    @SerialName("main_gpu")
    var mainGpu: Int? = null,

    /** Whether to use low VRAM mode */
    @SerialName("low_vram")
    var lowVram: Boolean? = null,

    /** Whether to only load the vocabulary (no weights) */
    @SerialName("vocab_only")
    var vocabOnly: Boolean? = null,

    /** Whether to use memory-mapped files */
    @SerialName("use_mmap")
    var useMmap: Boolean? = null,

    /** Whether to lock the model in memory */
    @SerialName("use_mlock")
    var useMlock: Boolean? = null,

    /** Number of threads to use for generation */
    @SerialName("num_thread")
    var numThread: Int? = null
)
