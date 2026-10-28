package genericOpenAIPipe.mantle

/**
 * Service tiers accepted by Amazon Bedrock Mantle on OpenAI-shaped calls
 * (Chat Completions and Responses).
 *
 * Mantle's `service_tier` request field accepts exactly three Bedrock
 * values: `default`, `priority`, `flex`. OpenAI's own `auto` value is NOT
 * accepted by Mantle and has no variant in this enum. The Anthropic
 * Messages wire is a different API surface with a different tier
 * vocabulary — Mantle tiers must never be sent on that call (the Anthropic
 * serializer builds its request from an explicit field list, so a tier set
 * here cannot leak through it).
 *
 * Flex shares on-demand quota with the standard tier; an IAM policy using
 * the `bedrock:ServiceTier` condition key can block specific tiers on a
 * given account.
 *
 * @see BedrockMantleConfiguration
 * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/bedrock-mantle.html">Bedrock Mantle</a>
 */
enum class MantleServiceTier(private val wireValue: String)
{
    /** Maps to the wire value `default` (standard on-demand tier). */
    Standard("default"),

    /** Maps to the wire value `priority`. */
    Priority("priority"),

    /** Maps to the wire value `flex`. Shares on-demand quota with [Standard]. */
    Flex("flex");

    /**
     * The wire value emitted on the `service_tier` request field.
     *
     * @return One of `default`, `priority`, `flex`.
     */
    fun toWireValue(): String = wireValue
}
