package genericOpenAIPipe.api

/**
 * Typed provider policy for OpenAI Responses serialization and transport.
 *
 * Defaults preserve the existing GenericOpenAI Responses request shape. A
 * provider-specific access profile can opt into a narrower backend contract
 * without changing other Responses providers.
 *
 * @property emitMaxOutputTokens Whether to emit `max_output_tokens`.
 * @property emitSamplingParameters Whether to emit `temperature` and `top_p`.
 * @property emitUser Whether to emit the generic `user` field.
 * @property store Optional `store` wire value; null omits the field.
 * @property include Optional Responses `include` values.
 * @property messageItemType Optional explicit input message-item type.
 * @property forceStreaming Whether the backend requires SSE regardless of caller callbacks.
 * @property emitEmptyToolsWhenNone Whether to emit an empty native-tools control block.
 * @property emptyToolChoice Tool choice used with the empty native-tools block.
 * @property emptyParallelToolCalls Parallel-tool setting used with the empty block.
 */
data class OpenAIResponsesWirePolicy(
    val emitMaxOutputTokens: Boolean = true,
    val emitSamplingParameters: Boolean = true,
    val emitUser: Boolean = true,
    val store: Boolean? = null,
    val include: List<String>? = null,
    val messageItemType: String? = null,
    val forceStreaming: Boolean = false,
    val emitEmptyToolsWhenNone: Boolean = false,
    val emptyToolChoice: String? = null,
    val emptyParallelToolCalls: Boolean? = null,
)
