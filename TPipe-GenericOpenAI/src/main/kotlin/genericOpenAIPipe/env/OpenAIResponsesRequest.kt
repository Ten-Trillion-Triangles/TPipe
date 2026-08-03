package genericOpenAIPipe.env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for the OpenAI `/v1/responses` endpoint (the Responses API).
 *
 * This is the wire spec — it is intentionally distinct from [GenericOpenAIChatRequest]
 * (the normalised in-process shape) so the serializer can map between the two cleanly.
 *
 * Key differences from the chat-completions shape:
 *  - `instructions` is top-level (system messages are hoisted here)
 *  - `input` is a list of typed `Message` items with a `content` list of typed parts
 *    ([OpenAIResponsesInputPart.InputTextPart] / [OpenAIResponsesInputPart.InputImagePart])
 *  - `max_output_tokens` replaces `max_tokens`
 *  - `text.format` is a wrapper around `response_format.type`
 *  - `tool_choice` accepts the same string values
 *  - `reasoning.effort` / `reasoning.max_tokens` mirror the chat-completions knobs
 *
 * @see <a href="https://platform.openai.com/docs/api-reference/responses">OpenAI Responses API</a>
 */
@Serializable
data class OpenAIResponsesRequest(
    val model: String,
    val input: List<OpenAIResponsesMessageItem>,
    val instructions: String? = null,
    @SerialName("max_output_tokens")
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    val stream: Boolean = false,
    val tools: List<OpenAIResponsesTool>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean? = null,
    val text: OpenAIResponsesTextConfig? = null,
    val reasoning: OpenAIResponsesReasoning? = null,
    val user: String? = null,
    /**
     * Bedrock Mantle GPT-5.6 prompt cache top-level options. Null on every
     * non-Mantle-GPT-5.6 target so the wire shape is unchanged. Populated by
     * [genericOpenAIPipe.api.OpenAIResponsesRequestSerializer] when the pipe
     * carries [genericOpenAIPipe.mantle.MantleMetadataKeys.GPT56_PROMPT_CACHING]
     * metadata.
     */
    @SerialName("prompt_cache_options")
    val promptCacheOptions: PromptCacheOptions? = null,
)

/**
 * One input item in the `input` list. Today only `message` items are emitted; the
 * Responses API also supports `function_call_output`, `item_reference`, `file_search_call`
 * etc., which are intentionally not modelled yet.
 */
@Serializable
data class OpenAIResponsesMessageItem(
    val role: String,
    val content: List<OpenAIResponsesInputPart>
)

/**
 * `text` config block — when present, the model constrains its output to the
 * declared format (text / json_object / json_schema).
 */
@Serializable
data class OpenAIResponsesTextConfig(
    val format: OpenAIResponsesTextFormat
)

/**
 * `text.format` payload. The `schema` field is required when [type] is `json_schema`.
 */
@Serializable
data class OpenAIResponsesTextFormat(
    val type: String,
    val schema: kotlinx.serialization.json.JsonObject? = null
)

/**
 * `reasoning` block — mirrors the chat-completions `reasoning` field but kept under
 * the Responses-API name for clarity.
 */
@Serializable
data class OpenAIResponsesReasoning(
    val effort: String? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null
)

/**
 * `tools` entry. Mirrors the chat-completions shape so the same [ToolDefinition]
 * / [FunctionSchema] data classes can drive both endpoints.
 */
@Serializable
data class OpenAIResponsesTool(
    val type: String = "function",
    val function: FunctionSchema
)
