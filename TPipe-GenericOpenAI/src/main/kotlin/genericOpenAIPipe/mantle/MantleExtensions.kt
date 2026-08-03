package genericOpenAIPipe.mantle

import genericOpenAIPipe.GenericOpenAIPipe

/**
 * Opt this pipe into Bedrock Mantle GPT-5.6 explicit prompt caching.
 *
 * The extension does NOT grow [GenericOpenAIPipe]'s public API surface — it
 * populates [com.TTT.Pipe.Pipe.pipeMetadata] under
 * [MantleMetadataKeys.GPT56_PROMPT_CACHING] with a typed
 * [MantleGpt56PromptCacheMetadata] object. The OpenAI Responses serializer
 * reads that key during request conversion and emits the corresponding
 * `prompt_cache_options` top-level field plus any per-block
 * `prompt_cache_breakpoint` markers.
 *
 * Behavior on first request (assuming `apiMode = ApiMode.OpenAIResponses`
 * and a model id starting with `openai.gpt-5.6-`):
 *
 *   - **Default ([MantleGpt56CacheBoundary.NONE])** — top-level
 *     `prompt_cache_options: { mode: "explicit", ttl: "30m" }` is emitted.
 *     No per-block markers. Works on every Mantle GPT-5.6 model.
 *
 *   - **[MantleGpt56CacheBoundary.AFTER_INSTRUCTIONS]** — same top-level
 *     block, plus the system prompt is emitted as a `developer`-role input
 *     message with `prompt_cache_breakpoint: { mode: "explicit" }` on its
 *     `input_text` part, and `instructions` is set to `null`. Mantle places
 *     the cache entry at the system/user boundary. **Requires live verification
 *     that Mantle accepts the `developer`-role input block on your model.**
 *
 * The pipe's existing model + region configuration is unchanged. Pair with
 * [GenericOpenAIPipe.setBedrockMantleWithResponses] or
 * [GenericOpenAIPipe.setBedrockMantle] (the latter requires switching the
 * pipe to the Responses API mode via the same call if you need explicit
 * caching — Chat Completions does not support this extension).
 *
 * @param boundary Where to place the per-block breakpoint marker. Defaults
 *                to [MantleGpt56CacheBoundary.NONE] (top-level options only).
 * @param ttl Cache retention in Mantle's `"5m"` / `"1h"` vocabulary.
 *            Default `"30m"` matches the documented AWS recommendation
 *            for GPT-5.6 prompt-cache TTL.
 * @return This pipe instance for fluent chaining.
 * @throws IllegalStateException at first request if the (model, apiMode)
 *         combination is not supported. See
 *         [requireMantleGpt56ExplicitCachingSupport].
 */
fun GenericOpenAIPipe.enableMantleGpt56ExplicitPromptCaching(
    boundary: MantleGpt56CacheBoundary = MantleGpt56CacheBoundary.NONE,
    ttl: String = "30m",
): GenericOpenAIPipe
{
    pipeMetadata[MantleMetadataKeys.GPT56_PROMPT_CACHING] = MantleGpt56PromptCacheMetadata(
        mode = "explicit",
        ttl = ttl,
        boundary = boundary,
    )
    return this
}
