@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package env

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Non-streaming response body from the OpenRouter `/api/v1/responses` endpoint.
 *
 * `output` is a polymorphic list of item objects: `message` items carry
 * user-visible text + refusal, `reasoning` items carry the model's
 * chain-of-thought, `function_call` items carry tool calls. Unknown item types
 * (e.g. `web_search_call`) decode to [OpenRouterResponsesOutputItem.Unknown]
 * instead of failing the whole response.
 *
 * @see <a href="https://openrouter.ai/docs/api-reference#responses">OpenRouter Responses API</a>
 */
@Serializable
data class OpenRouterResponsesResponse(
    val id: String,
    @SerialName("object")
    val objectType: String = "response",
    @SerialName("created_at")
    val createdAt: Long = 0L,
    val status: String = "completed",
    val model: String,
    val output: List<OpenRouterResponsesOutputItem> = emptyList(),
    val usage: OpenRouterResponsesUsage? = null,
    val error: OpenRouterResponsesResponseError? = null
)

/**
 * One item in [OpenRouterResponsesResponse.output].
 *
 * Modelled as a sealed class with a tolerant [Unknown] fallback so an
 * unrecognized `type` never fails the whole response.
 */
@Serializable(with = OpenRouterResponsesOutputItemSerializer::class)
sealed class OpenRouterResponsesOutputItem
{
    /**
     * Assistant message output (text + refusal content).
     */
    @Serializable
    @SerialName("message")
    data class Message(
        val id: String? = null,
        val role: String = "assistant",
        val status: String? = null,
        val content: List<OpenRouterResponsesContentPart> = emptyList()
    ) : OpenRouterResponsesOutputItem()

    /**
     * Reasoning item emitted by a reasoning-capable model. `summary` carries the
     * provider's `summary_text` parts; `content` carries the `reasoning_text`
     * parts. Both project into the trace's reasoning transcript.
     */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val id: String? = null,
        val summary: List<OpenRouterResponsesContentPart> = emptyList(),
        val content: List<OpenRouterResponsesContentPart> = emptyList()
    ) : OpenRouterResponsesOutputItem()

    /**
     * Function-call item (a tool call the model emitted).
     */
    @Serializable
    @SerialName("function_call")
    data class FunctionCall(
        val id: String? = null,
        @SerialName("call_id")
        val callId: String? = null,
        val name: String? = null,
        val arguments: String? = null,
        val status: String? = null
    ) : OpenRouterResponsesOutputItem()

    /**
     * Catch-all for an item type the pipe does not model (raw retained).
     */
    @Serializable
    data class Unknown(
        val type: String,
        val raw: JsonObject
    ) : OpenRouterResponsesOutputItem()
}

/**
 * One content part inside a message or reasoning item.
 */
@Serializable
sealed class OpenRouterResponsesContentPart
{
    /** Plain text output part (`type = "output_text"`). */
    @Serializable
    @SerialName("output_text")
    data class OutputText(
        val text: String
    ) : OpenRouterResponsesContentPart()

    /** Refusal part (`type = "refusal"`). */
    @Serializable
    @SerialName("refusal")
    data class Refusal(
        val refusal: String
    ) : OpenRouterResponsesContentPart()

    /** Reasoning-text part (`type = "reasoning_text"`). */
    @Serializable
    @SerialName("reasoning_text")
    data class ReasoningText(
        val text: String
    ) : OpenRouterResponsesContentPart()

    /** Reasoning summary part (`type = "summary_text"`). */
    @Serializable
    @SerialName("summary_text")
    data class SummaryText(
        val text: String
    ) : OpenRouterResponsesContentPart()
}

/**
 * Token usage for a Responses response (mirrors chat-completions `UsageInfo`
 * under a Responses-specific name).
 */
@Serializable
data class OpenRouterResponsesUsage(
    @SerialName("input_tokens")
    val inputTokens: Int = 0,
    @SerialName("output_tokens")
    val outputTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0,
    @SerialName("input_tokens_details")
    val inputTokensDetails: OpenRouterResponsesInputTokensDetails? = null,
    @SerialName("output_tokens_details")
    val outputTokensDetails: OpenRouterResponsesOutputTokensDetails? = null
)

/**
 * Detailed breakdown of input tokens (e.g. prompt-cache hits).
 */
@Serializable
data class OpenRouterResponsesInputTokensDetails(
    @SerialName("cached_tokens")
    val cachedTokens: Int? = null
)

/**
 * Detailed breakdown of output tokens (e.g. reasoning tokens).
 */
@Serializable
data class OpenRouterResponsesOutputTokensDetails(
    @SerialName("reasoning_tokens")
    val reasoningTokens: Int? = null
)

/**
 * Error detail embedded on a failed [OpenRouterResponsesResponse] (most errors
 * arrive as a separate `{"error": {...}}` body, which the parser maps first).
 */
@Serializable
data class OpenRouterResponsesResponseError(
    val type: String? = null,
    val code: JsonElement? = null,
    val message: String? = null
)

/**
 * Tolerant polymorphic serializer for [OpenRouterResponsesOutputItem]: known
 * `type` values dispatch to the concrete subtype; anything else decodes to
 * [OpenRouterResponsesOutputItem.Unknown] with the raw object retained.
 */
class OpenRouterResponsesOutputItemSerializer : KSerializer<OpenRouterResponsesOutputItem>
{
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor("com.TTT.env.OpenRouterResponsesOutputItem", PolymorphicKind.OPEN)

    override fun serialize(encoder: Encoder, value: OpenRouterResponsesOutputItem)
    {
        when(value)
        {
            is OpenRouterResponsesOutputItem.Message ->
                encoder.encodeSerializableValue(OpenRouterResponsesOutputItem.Message.serializer(), value)

            is OpenRouterResponsesOutputItem.Reasoning ->
                encoder.encodeSerializableValue(OpenRouterResponsesOutputItem.Reasoning.serializer(), value)

            is OpenRouterResponsesOutputItem.FunctionCall ->
                encoder.encodeSerializableValue(OpenRouterResponsesOutputItem.FunctionCall.serializer(), value)

            is OpenRouterResponsesOutputItem.Unknown ->
                encoder.encodeSerializableValue(OpenRouterResponsesOutputItem.Unknown.serializer(), value)
        }
    }

    override fun deserialize(decoder: Decoder): OpenRouterResponsesOutputItem
    {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        val obj = element.jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull

        return when(type)
        {
            "message" -> jsonDecoder.json.decodeFromJsonElement(OpenRouterResponsesOutputItem.Message.serializer(), obj)

            "reasoning" -> jsonDecoder.json.decodeFromJsonElement(OpenRouterResponsesOutputItem.Reasoning.serializer(), obj)

            "function_call" -> jsonDecoder.json.decodeFromJsonElement(OpenRouterResponsesOutputItem.FunctionCall.serializer(), obj)

            else -> OpenRouterResponsesOutputItem.Unknown(type ?: "unknown", obj)
        }
    }
}
