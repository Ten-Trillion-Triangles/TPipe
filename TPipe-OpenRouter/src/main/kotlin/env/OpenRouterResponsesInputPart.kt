@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package env

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive

/**
 * One part of a [OpenRouterResponsesMessageItem]'s `content` array in an
 * OpenRouter Responses input item.
 *
 * Mirrors the Responses wire spec where a message's `content` is a list of
 * typed parts (`input_text`, `input_image`). Each part carries its own custom
 * serializer so the `type` discriminator is always emitted (even when the part
 * is serialized as a standalone root object, which the standard polymorphic
 * encoder would omit for a default-valued field).
 *
 * @see <a href="https://openrouter.ai/docs/api-reference#responses">OpenRouter Responses API</a>
 */
@Serializable(with = OpenRouterResponsesInputPart.OpenRouterResponsesInputPartSerializer::class)
sealed class OpenRouterResponsesInputPart
{
    /**
     * Plain text part of a message (`type = "input_text"`).
     *
     * @property text The text content.
     * @property type The part discriminator (always emitted on the wire).
     */
    @Serializable(with = OpenRouterResponsesInputPart.InputTextPartSerializer::class)
    data class InputTextPart(
        val text: String,
        val type: String = "input_text"
    ) : OpenRouterResponsesInputPart()

    /**
     * Image part of a message (`type = "input_image"`).
     *
     * `imageUrl` intentionally serializes camelCase to match the OpenRouter
     * Responses sample payload; note this deviates from the snake_case wire
     * convention used elsewhere — treat as a P2 multimodal simplification.
     *
     * @property imageUrl A remote URL or base64 `data:` URI.
     * @property detail Detail level: "auto", "low", or "high" (null omits it).
     * @property type The part discriminator (always emitted on the wire).
     */
    @Serializable(with = OpenRouterResponsesInputPart.InputImagePartSerializer::class)
    data class InputImagePart(
        val imageUrl: String,
        val detail: String? = "auto",
        val type: String = "input_image"
    ) : OpenRouterResponsesInputPart()

    /**
     * Routes sealed-class (in-list / standalone-parent-typed) serialization by
     * the `type` field and delegates each part to its own serializer.
     */
    class OpenRouterResponsesInputPartSerializer : KSerializer<OpenRouterResponsesInputPart>
    {
        override val descriptor: SerialDescriptor =
            buildSerialDescriptor("com.TTT.env.OpenRouterResponsesInputPart", PolymorphicKind.OPEN)

        override fun serialize(encoder: Encoder, value: OpenRouterResponsesInputPart)
        {
            when(value)
            {
                is InputTextPart -> encoder.encodeSerializableValue(InputTextPartSerializer(), value)
                is InputImagePart -> encoder.encodeSerializableValue(InputImagePartSerializer(), value)
            }
        }

        override fun deserialize(decoder: Decoder): OpenRouterResponsesInputPart
        {
            val jsonDecoder = decoder as JsonDecoder
            val obj = jsonDecoder.decodeJsonElement().jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            return when(type)
            {
                "input_text" -> jsonDecoder.json.decodeFromJsonElement(InputTextPartSerializer(), obj)
                "input_image" -> jsonDecoder.json.decodeFromJsonElement(InputImagePartSerializer(), obj)
                else -> jsonDecoder.json.decodeFromJsonElement(InputTextPartSerializer(), obj)
            }
        }
    }

    /** Manual serializer: always writes `type` + `text`. */
    class InputTextPartSerializer : KSerializer<InputTextPart>
    {
        override val descriptor: SerialDescriptor =
            buildSerialDescriptor("com.TTT.env.OpenRouterResponsesInputPart.InputTextPart", PolymorphicKind.OPEN)

        override fun serialize(encoder: Encoder, value: InputTextPart)
        {
            (encoder as JsonEncoder).encodeJsonElement(buildJsonObject
            {
                put("type", JsonPrimitive(value.type))
                put("text", JsonPrimitive(value.text))
            })
        }

        override fun deserialize(decoder: Decoder): InputTextPart
        {
            val obj = (decoder as JsonDecoder).decodeJsonElement().jsonObject
            return InputTextPart(text = obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
        }
    }

    /** Manual serializer: always writes `type` + `imageUrl` (+ `detail` when set). */
    class InputImagePartSerializer : KSerializer<InputImagePart>
    {
        override val descriptor: SerialDescriptor =
            buildSerialDescriptor("OpenRouterResponsesInputPart.InputImagePart", PolymorphicKind.OPEN)

        override fun serialize(encoder: Encoder, value: InputImagePart)
        {
            (encoder as JsonEncoder).encodeJsonElement(buildJsonObject
            {
                put("type", JsonPrimitive(value.type))
                put("imageUrl", JsonPrimitive(value.imageUrl))
                value.detail?.let{ put("detail", JsonPrimitive(it)) }
            })
        }

        override fun deserialize(decoder: Decoder): InputImagePart
        {
            val obj = (decoder as JsonDecoder).decodeJsonElement().jsonObject
            return InputImagePart(
                imageUrl = obj["imageUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                detail = obj["detail"]?.jsonPrimitive?.contentOrNull
            )
        }
    }
}
