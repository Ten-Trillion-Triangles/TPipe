package genericOpenAIPipe.mantle

import genericOpenAIPipe.api.ApiMode
import kotlinx.serialization.Serializable

/**
 * Caller-supplied Mantle GPT-5.6 prompt-cache configuration carried through
 * [com.TTT.Pipe.Pipe.pipeMetadata] under [MantleMetadataKeys.GPT56_PROMPT_CACHING].
 *
 * Why this lives in `pipeMetadata` instead of a first-class pipe variable:
 * the feature is too narrow (Mantle + Responses API + GPT-5.6 family only)
 * to justify growing [genericOpenAIPipe.GenericOpenAIPipe]'s public API
 * surface. The typed object (instead of loose `Map<String, Any?>` keys)
 * preserves type safety at the read site — the Responses serializer does a
 * typed `as? MantleGpt56PromptCacheMetadata` cast and ignores anything that
 * does not match.
 *
 * @property mode `"explicit"` (the only supported value). The Mantle default
 *           is `"implicit"`, which is not a good fit for agentic loops.
 * @property ttl Cache retention. AWS supports `"5m"` and `"1h"` for GPT-5.6.
 *           Default `"30m"` matches the ChatGPT-share research's documented
 *           AWS recommendation.
 * @property boundary Where to place the per-block `prompt_cache_breakpoint`
 *           marker. [MantleGpt56CacheBoundary.NONE] emits only the top-level
 *           `prompt_cache_options` block (no per-block marker). [MantleGpt56CacheBoundary.AFTER_INSTRUCTIONS]
 *           transforms the system prompt into a `developer`-role input
 *           message carrying the marker so Mantle places the cache entry at
 *           the system/user boundary.
 */
@Serializable
data class MantleGpt56PromptCacheMetadata(
    val mode: String = "explicit",
    val ttl: String = "30m",
    val boundary: MantleGpt56CacheBoundary = MantleGpt56CacheBoundary.NONE,
)

/**
 * Per-block cache breakpoint placement options for Mantle GPT-5.6 explicit
 * caching.
 *
 * Adding new variants here is the supported extension point for callers who
 * need finer-grained cache placement. The Responses serializer's `when`
 * over this enum is exhaustive.
 */
@Serializable
enum class MantleGpt56CacheBoundary
{
    /** No per-block breakpoint marker emitted. Top-level `prompt_cache_options` still set. */
    NONE,

    /**
     * Emit the system prompt as a `developer`-role input message with a
     * `prompt_cache_breakpoint: { mode: "explicit" }` marker on its
     * `input_text` part. `instructions` is set to null. Mantle places the
     * cache entry at the boundary between stable system content and dynamic
     * user/assistant turns.
     */
    AFTER_INSTRUCTIONS,
}

/**
 * String constants for keys written into [com.TTT.Pipe.Pipe.pipeMetadata].
 *
 * Centralizing these avoids the string-drift bug class where two call sites
 * use slightly different keys for the same concept and the metadata silently
 * fails to round-trip.
 */
object MantleMetadataKeys
{
    /**
     * Carries a [MantleGpt56PromptCacheMetadata] value. Read by the OpenAI
     * Responses serializer when targeting Bedrock Mantle GPT-5.6 variants.
     */
    const val GPT56_PROMPT_CACHING: String = "bedrockMantle.gpt56.promptCaching"
}

/**
 * Returns true when [model] / [apiMode] is a configuration Mantle supports
 * for explicit prompt caching.
 *
 * Currently:
 *   - model must start with `"openai.gpt-5.6-"` (Sol, Terra, Luna)
 *   - apiMode must be [ApiMode.OpenAIResponses] (explicit breakpoints are a
 *     Responses-API-only feature)
 *
 * Used by the OpenAI Responses serializer to validate caller intent before
 * emitting Mantle-specific JSON. Exposed package-private so callers (and
 * tests) can probe support without triggering the throw.
 */
fun supportsMantleGpt56ExplicitCaching(model: String, apiMode: ApiMode): Boolean =
    apiMode is ApiMode.OpenAIResponses && model.startsWith("openai.gpt-5.6-")

/**
 * Throws [IllegalStateException] when the (model, apiMode) combination is
 * not supported for explicit Mantle GPT-5.6 prompt caching.
 *
 * Called by the OpenAI Responses serializer the moment it sees the
 * [MantleMetadataKeys.GPT56_PROMPT_CACHING] metadata key in
 * [genericOpenAIPipe.api.RequestSerializationOptions.metadata]. Silently
 * ignoring a requested cost-control mechanism would make usage and billing
 * impossible to reason about — explicit misuse MUST surface.
 *
 * @throws IllegalStateException if the (model, apiMode) combination is not
 *         supported. The message names the model and apiMode so the call
 *         site is easy to identify.
 */
fun requireMantleGpt56ExplicitCachingSupport(model: String, apiMode: ApiMode)
{
    if(!supportsMantleGpt56ExplicitCaching(model, apiMode))
    {
        throw IllegalStateException(
            "Explicit Mantle prompt caching is supported only for " +
            "GPT-5.6 Sol/Terra/Luna on the OpenAI Responses API. " +
            "Got model='$model', apiMode=$apiMode."
        )
    }
}
