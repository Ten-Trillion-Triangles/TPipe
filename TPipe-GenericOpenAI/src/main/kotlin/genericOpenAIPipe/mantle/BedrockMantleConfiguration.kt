package genericOpenAIPipe.mantle

import genericOpenAIPipe.api.ApiMode

/**
 * Configuration record for the Amazon Bedrock Mantle endpoint.
 *
 * Mantle is a separate Bedrock regional endpoint surface that exposes selected
 * foundation models over an OpenAI-compatible HTTP wire format. Endpoint URL
 * pattern is documented by AWS as:
 *
 * ```
 * https://bedrock-mantle.{region}.api.aws/openai/v1
 * ```
 *
 * Models currently reachable through Mantle include the OpenAI GPT-5.6 family
 * (for example `openai.gpt-5.6-sol`) and the Google Gemma 4 family (for
 * example `google.gemma-4-31b`). AWS recommends preferring the Mantle
 * endpoint over `bedrock-runtime` whenever the model is available there.
 *
 * @property region AWS region code used to construct the regional endpoint
 *                  (for example `us-east-1` or `us-west-2`).
 * @property modelId The Bedrock model identifier passed verbatim on the
 *                   request body (for example `google.gemma-4-31b`).
 * @property apiMode Which OpenAI-shaped API surface to dispatch against.
 *                   Defaults to [ApiMode.OpenAI] (Chat Completions). Set to
 *                   [ApiMode.OpenAIResponses] to drive the Responses wire
 *                   format at the same endpoint.
 *
 * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/bedrock-mantle.html">Bedrock Mantle Responses API</a>
 * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/inference-chat-completions-mantle.html">Bedrock Mantle Chat Completions API</a>
 */
@kotlinx.serialization.Serializable
data class BedrockMantleConfiguration(
    val region: String,
    val modelId: String,
    val apiMode: ApiMode = ApiMode.OpenAI,
)
{
    init
    {
        require(region.isNotBlank()) { "region cannot be blank" }
        require(modelId.isNotBlank()) { "modelId cannot be blank" }
    }

    /**
     * The fully-qualified Mantle endpoint base URL for this configuration.
     *
     * @return The HTTPS base URL ending in `/openai/v1`.
     */
    fun endpoint(): String = "https://bedrock-mantle.${region.trim()}.api.aws/openai/v1"

    companion object
    {
        /**
         * Construct a configuration for the OpenAI Chat Completions wire format.
         *
         * @param region AWS region code.
         * @param modelId Bedrock model identifier.
         * @return A configuration with [apiMode] set to [ApiMode.OpenAI].
         */
        fun forRegion(region: String, modelId: String): BedrockMantleConfiguration =
            BedrockMantleConfiguration(region = region, modelId = modelId, apiMode = ApiMode.OpenAI)

        /**
         * Construct a configuration for the OpenAI Responses wire format.
         *
         * @param region AWS region code.
         * @param modelId Bedrock model identifier.
         * @return A configuration with [apiMode] set to [ApiMode.OpenAIResponses].
         */
        fun forRegionWithResponses(region: String, modelId: String): BedrockMantleConfiguration =
            BedrockMantleConfiguration(region = region, modelId = modelId, apiMode = ApiMode.OpenAIResponses)
    }
}