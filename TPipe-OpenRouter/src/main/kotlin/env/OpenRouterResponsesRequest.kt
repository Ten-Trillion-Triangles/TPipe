package env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request body for the OpenRouter `/api/v1/responses` endpoint (the Responses
 * API, OpenAI-compatible).
 *
 * This is the wire spec — intentionally distinct from [OpenRouterChatRequest]
 * (the normalised chat-completions shape) so [openrouterPipe.OpenRouterResponsesRequestSerializer]
 * can map between the two cleanly. Key differences from chat-completions:
 *  - `instructions` is top-level (system messages are hoisted here)
 *  - `input` is a list of typed [OpenRouterResponsesMessageItem]s whose `content` is
 *    a list of typed [OpenRouterResponsesInputPart]s (there is no `messages`)
 *  - `max_output_tokens` replaces `max_tokens`
 *  - `text.format` wraps `response_format.type`
 *  - `tools` are flat function tools ([OpenRouterResponsesTool])
 *  - `reasoning` carries `{ effort, summary }`
 *
 * @see <a href="https://openrouter.ai/docs/api-reference#responses">OpenRouter Responses API</a>
 */
@Serializable
data class OpenRouterResponsesRequest(
    val model: String,
    val input: List<OpenRouterResponsesMessageItem>,
    val instructions: String? = null,
    @SerialName("max_output_tokens")
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("top_k")
    val topK: Int? = null,
    val stream: Boolean = false,
    val tools: List<OpenRouterResponsesTool>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean? = null,
    val text: OpenRouterResponsesTextConfig? = null,
    val reasoning: OpenRouterResponsesReasoning? = null
)

/**
 * One item in the `input` list. Today only `message` items are emitted; the
 * Responses API also supports `function_call_output`, `item_reference` and
 * `file_search_call`, which are intentionally not modelled yet.
 *
 * @property type Item type discriminator (defaults to `null`, which the
 *   serializer emits as `message` for conversation turns).
 * @property role The message role: `system`, `user`, `assistant`, `developer` or `tool`.
 * @property content The typed content parts of the message.
 */
@Serializable
data class OpenRouterResponsesMessageItem(
    val type: String? = null,
    val role: String,
    val content: List<OpenRouterResponsesInputPart> = emptyList()
)

/**
 * The `text` config block — when present, constrains the model's output to the
 * declared format (text / json_object / json_schema).
 */
@Serializable
data class OpenRouterResponsesTextConfig(
    val format: OpenRouterResponsesTextFormat
)

/**
 * The `text.format` payload. `schema` is required when [type] is `json_schema`.
 */
@Serializable
data class OpenRouterResponsesTextFormat(
    val type: String,
    val schema: JsonObject? = null
)

/**
 * The `reasoning` block — mirrors the chat-completions `reasoning` shape
 * (`{ effort, summary }`) under the Responses-API name for clarity.
 */
@Serializable
data class OpenRouterResponsesReasoning(
    val effort: String? = null,
    val summary: String? = null
)

/**
 * A Responses-API tool. OpenRouter exposes tools in a FLAT shape (name /
 * description / parameters at the top level), unlike the chat-completions
 * nested `function` object. Drives both the request and the response parser.
 *
 * @property type Tool type (currently only `function`).
 * @property name The function name.
 * @property description Human-readable description for the model.
 * @property parameters JSON Schema for the function parameters.
 */
@Serializable
data class OpenRouterResponsesTool(
    val type: String,
    val name: String? = null,
    val description: String? = null,
    val parameters: JsonObject? = null
)
