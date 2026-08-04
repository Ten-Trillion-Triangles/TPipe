package genericOpenAIPipe.api

/**
 * Caller-supplied options passed through [RequestSerializer.serialize].
 *
 * The serializer contract previously only received the normalized request
 * and the target [ApiMode]. Adding a third parameter for serializer hints
 * lets providers expose provider-specific JSON extensions (e.g. Bedrock
 * Mantle GPT-5.6 prompt caching) without polluting [com.TTT.Pipe.Pipe]
 * with one-off fields for every provider variation.
 *
 * Serializers that do not recognize any of the keys in [metadata] MUST
 * ignore them entirely — the option bag is opt-in per-serializer.
 *
 * @property metadata Key-value bag of provider-specific hints. Keys are
 *                    string constants (e.g.
 *                    [genericOpenAIPipe.mantle.MantleMetadataKeys.GPT56_PROMPT_CACHING])
 *                    and values are typed objects (e.g.
 *                    [genericOpenAIPipe.mantle.MantleGpt56PromptCacheMetadata]).
 *                    Empty by default so existing call sites see no behavior
 *                    change.
 */
data class RequestSerializationOptions(
    val metadata: Map<String, Any?> = emptyMap(),
)
