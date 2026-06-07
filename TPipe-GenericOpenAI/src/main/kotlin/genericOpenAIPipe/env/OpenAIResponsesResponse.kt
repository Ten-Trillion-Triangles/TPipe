package genericOpenAIPipe.env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Non-streaming response body from the OpenAI `/v1/responses` endpoint.
 *
 * The `output` list is a polymorphic list of `item` objects: assistant `message` items
 * carry text, refusal, or tool-call content; `reasoning` items are emitted by
 * reasoning-capable models (e.g. `o3`, `o4-mini`, `MiniMax-M2.7`) and contain the
 * model's internal chain-of-thought in their `content` list.
 *
 * @see <a href="https://platform.openai.com/docs/api-reference/responses/object">OpenAI Responses object</a>
 */
@Serializable
data class OpenAIResponsesResponse(
    val id: String,
    @SerialName("object")
    val objectType: String = "response",
    @SerialName("created_at")
    val createdAt: Long = 0L,
    val status: String = "completed",
    val model: String,
    val output: List<OpenAIResponsesOutputItem> = emptyList(),
    val usage: OpenAIResponsesUsage? = null,
    val error: OpenAIResponsesErrorDetail? = null
)

/**
 * One item in the [OpenAIResponsesResponse.output] list.
 *
 * Modelled as a sealed class so the parser can branch on `type` cleanly. The OpenAI
 * Responses API also supports `function_call`, `file_search_call`, `web_search_call`,
 * `computer_call`, `item_reference` and others — those variants are intentionally
 * absent here; if/when the pipe is extended to consume them they can be added without
 * breaking the existing parser.
 */
@Serializable
sealed class OpenAIResponsesOutputItem
{
    /**
     * Assistant message output containing text or refusal content.
     */
    @Serializable
    @SerialName("message")
    data class Message(
        val id: String? = null,
        val role: String = "assistant",
        val status: String? = null,
        val content: List<OpenAIResponsesContentPart> = emptyList()
    ) : OpenAIResponsesOutputItem()

    /**
     * Reasoning item emitted by a reasoning-capable model (`o3`, `o4-mini`,
     * `MiniMax-M2.7`, etc.). Carries the model's internal chain-of-thought.
     *
     * The OpenAI Responses wire spec puts reasoning text in two places on this
     * item:
     *  - `summary`: a short human-readable summary the model writes after
     *    reasoning (often empty for fast reasoning).
     *  - `content`: a list of typed `reasoning_text` parts containing the
     *    actual chain-of-thought. The pipe concatenates these so the trace
     *    contains the full reasoning transcript, matching the Bedrock
     *    gold-standard `MultimodalContent.modelReasoning` field.
     */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val id: String? = null,
        val summary: List<OpenAIResponsesContentPart> = emptyList(),
        val content: List<OpenAIResponsesContentPart> = emptyList()
    ) : OpenAIResponsesOutputItem()
}

/**
 * One content part inside an [OpenAIResponsesOutputItem.Message] or
 * [OpenAIResponsesOutputItem.Reasoning] item.
 */
@Serializable
sealed class OpenAIResponsesContentPart
{
    /**
     * Plain text output part (`type = "output_text"`). Used on assistant
     * `message` items for the user-visible answer.
     */
    @Serializable
    @SerialName("output_text")
    data class OutputText(
        val text: String,
        val annotations: List<kotlinx.serialization.json.JsonElement> = emptyList()
    ) : OpenAIResponsesContentPart()

    /**
     * Refusal part (`type = "refusal"`). Appears on assistant `message` items
     * when the model refuses to answer.
     */
    @Serializable
    @SerialName("refusal")
    data class Refusal(
        val refusal: String
    ) : OpenAIResponsesContentPart()

    /**
     * Reasoning-text part (`type = "reasoning_text"`). Appears on `reasoning`
     * output items; concatenating every `ReasoningText.text` across all
     * reasoning items in a response yields the full chain-of-thought.
     */
    @Serializable
    @SerialName("reasoning_text")
    data class ReasoningText(
        val text: String
    ) : OpenAIResponsesContentPart()
}

/**
 * Token usage statistics for a Responses response.
 *
 * Mirrors the chat-completions `UsageInfo` shape but kept under a Responses-specific
 * name so the parser can evolve independently.
 */
@Serializable
data class OpenAIResponsesUsage(
    @SerialName("input_tokens")
    val inputTokens: Int = 0,
    @SerialName("output_tokens")
    val outputTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0,
    @SerialName("output_tokens_details")
    val outputTokensDetails: OutputTokensDetails? = null
)

/**
 * Detailed breakdown of output tokens (e.g. reasoning tokens spent on chain-of-thought).
 */
@Serializable
data class OutputTokensDetails(
    @SerialName("reasoning_tokens")
    val reasoningTokens: Int? = null
)

/**
 * Error detail object embedded directly on a response (rare — most errors are returned
 * as a separate `{ "error": { ... } }` body).
 */
@Serializable
data class OpenAIResponsesErrorDetail(
    val type: String? = null,
    val code: String? = null,
    val message: String? = null
)
