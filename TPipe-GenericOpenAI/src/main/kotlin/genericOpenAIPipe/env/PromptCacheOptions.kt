package genericOpenAIPipe.env

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire spec for the Mantle GPT-5.6 prompt cache top-level options block.
 *
 * AWS Bedrock Mantle accepts this on Responses-API requests targeting the
 * GPT-5.6 family (`openai.gpt-5.6-sol`, `openai.gpt-5.6-terra`,
 * `openai.gpt-5.6-luna`) when the caller wants to declare cache behavior
 * explicitly. Without this block, Mantle defaults to `implicit` caching,
 * which auto-places a breakpoint on the latest message and is not a good
 * fit for agentic loops with a stable system prompt prefix and a
 * changing tail.
 *
 * Field absence preserves today's wire shape (no field emitted when null
 * thanks to `encodeDefaults = false`).
 *
 * @property mode `"explicit"` or `"implicit"`. The TPipe extension only
 *           emits `"explicit"`; `"implicit"` is the Mantle default and
 *           does not need to be sent.
 * @property ttl Cache retention. AWS supports `"5m"` and `"1h"` for most
 *           models. GPT-5.6 supports `"5m"` and `"1h"`.
 *
 * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-openai-gpt-56-luna.html">GPT-5.6 Luna model card</a>
 */
@Serializable
data class PromptCacheOptions(
    val mode: String,
    val ttl: String? = null,
)

/**
 * Per-input-block breakpoint marker for Mantle GPT-5.6 explicit caching.
 *
 * AWS accepts this on `input_text`, `input_image`, and `input_file` parts
 * inside Responses-API `input` items. Placing it on a stable block (e.g.
 * the system-prompt developer role) marks the boundary at which Mantle
 * should commit a cache entry, allowing reuse across turns while letting
 * everything after the breakpoint change freely.
 *
 * The [mode] field is annotated [EncodeDefault.Mode.ALWAYS] so the wire
 * payload always carries `"mode":"explicit"` — Mantle does not infer the
 * mode from the field's presence. Without this annotation the default
 * value would be stripped by `encodeDefaults = false` and Mantle would
 * reject the request.
 *
 * @property mode Currently always `"explicit"`. Reserved for future
 *           AWS extensions (e.g. `"implicit"` per-block overrides).
 *
 * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-openai-gpt-56-luna.html">GPT-5.6 Luna prompt caching fields</a>
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PromptCacheBreakpoint(
    @SerialName("mode")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val mode: String = "explicit",
)
