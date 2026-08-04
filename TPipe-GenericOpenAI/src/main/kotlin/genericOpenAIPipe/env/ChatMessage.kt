package genericOpenAIPipe.env

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Custom serializer for [MessageContent] that emits the OpenAI Chat
 * Completions wire format on the encode side.
 *
 * The OpenAI Chat Completions wire format expects `content` as a raw
 * string for plain-text user prompts. On the encode side we always
 * emit a raw string for [MessageContent.TextContent] (which holds the
 * common case of a single text prompt) and for [MessageContent.PlainContent].
 *
 * For [MessageContent.MultimodalContent] (multi-block content arrays) we
 * emit the object form with `type=multi` and a `blocks` array, because
 * that shape cannot be represented as a string.
 *
 * On the decode side we accept BOTH shapes — the raw string and the
 * `{"type":"text"|"plain"|"multi", ...}` object — because OpenAI
 * providers differ in their response shape.
 */
@OptIn(InternalSerializationApi::class)
object MessageContentPolymorphicSerializer : KSerializer<MessageContent>
{
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor("MessageContent", PolymorphicKind.SEALED) {}

    override fun deserialize(decoder: Decoder): MessageContent
    {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw IllegalStateException("MessageContentPolymorphicSerializer requires JSON")
        val element = jsonDecoder.decodeJsonElement()
        if(element is JsonPrimitive && element.isString)
        {
            return MessageContent.TextContent(element.content)
        }
        val obj: JsonObject = element.jsonObject
        val type = (obj["type"] as? JsonPrimitive)?.content
        return when(type)
        {
            "text" -> MessageContent.TextContent(
                (obj["text"] as? JsonPrimitive)?.content ?: ""
            )
            "plain" -> MessageContent.TextContent(
                (obj["content"] as? JsonPrimitive)?.content ?: ""
            )
            "multi" -> MessageContent.TextContent(obj.toString())
            null -> if(obj.containsKey("text"))
            {
                MessageContent.TextContent(
                    (obj["text"] as? JsonPrimitive)?.content ?: ""
                )
            }
            else
            {
                MessageContent.TextContent(obj.toString())
            }
            else -> MessageContent.TextContent(obj.toString())
        }
    }

    override fun serialize(encoder: Encoder, value: MessageContent)
    {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw IllegalStateException("MessageContentPolymorphicSerializer requires JSON")
        when(value)
        {
            is MessageContent.PlainContent ->
                jsonEncoder.encodeString(value.content)
            is MessageContent.TextContent ->
                // OpenAI Chat Completions wire form: raw string content.
                jsonEncoder.encodeString(value.text)
            is MessageContent.MultimodalContent ->
                jsonEncoder.encodeJsonElement(JsonObject(mapOf(
                    "type" to JsonPrimitive("multi"),
                    "blocks" to JsonArray(value.blocks.map { block ->
                        when(block)
                        {
                            is ContentBlock.TextBlock -> JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("text"),
                                    "text" to JsonPrimitive(block.text)
                                )
                            )
                            is ContentBlock.ImageUrlBlock -> JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("image_url"),
                                    "image_url" to JsonPrimitive(block.url)
                                )
                            )
                        }
                    })
                )))
        }
    }
}

/**
 * Plain-string content serializer (OpenAI Chat Completions wire form).
 * Retained for explicit reference; the polymorphic serializer above does
 * not delegate to it.
 */
object MessageContentPlainSerializer : KSerializer<MessageContent.PlainContent>
{
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MessageContent.PlainContent", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): MessageContent.PlainContent =
        MessageContent.PlainContent(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: MessageContent.PlainContent)
    {
        encoder.encodeString(value.content)
    }
}

/**
 * Object-style content serializer (type discriminator). Retained for
 * explicit reference; the polymorphic serializer above does not delegate
 * to it directly.
 */
object MessageContentObjectSerializer : KSerializer<MessageContent>
{
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("MessageContent")

    override fun deserialize(decoder: Decoder): MessageContent
    {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw IllegalStateException("This serializer only works with JSON")
        val element = jsonDecoder.decodeJsonElement()
        val jsonObj: JsonObject = element.jsonObject
        val type = jsonObj["type"]?.jsonPrimitive?.content

        return when(type)
        {
            "text" -> MessageContent.TextContent(jsonObj["text"]?.jsonPrimitive?.content ?: "")
            "multi" -> MessageContent.TextContent("")
            null -> if(jsonObj.containsKey("text"))
            {
                MessageContent.TextContent(jsonObj["text"]?.jsonPrimitive?.content ?: "")
            }
            else
            {
                MessageContent.PlainContent(jsonObj.toString())
            }
            else -> MessageContent.PlainContent(jsonObj.toString())
        }
    }

    override fun serialize(encoder: Encoder, value: MessageContent)
    {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw IllegalStateException("This serializer only works with JSON")
        when(value)
        {
            is MessageContent.TextContent ->
            {
                val obj = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive(value.text)
                    )
                )
                jsonEncoder.encodeJsonElement(obj)
            }
            is MessageContent.PlainContent -> jsonEncoder.encodeString(value.content)
            is MessageContent.MultimodalContent ->
            {
                val blocks = JsonArray(
                    value.blocks.map { block ->
                        when(block)
                        {
                            is ContentBlock.TextBlock -> JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("text"),
                                    "text" to JsonPrimitive(block.text)
                                )
                            )
                            is ContentBlock.ImageUrlBlock -> JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("image_url"),
                                    "image_url" to JsonPrimitive(block.url)
                                )
                            )
                        }
                    }
                )
                val obj = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("multi"),
                        "blocks" to blocks
                    )
                )
                jsonEncoder.encodeJsonElement(obj)
            }
        }
    }
}

@Serializable
data class ChatMessage(
    val role: String,
    @Serializable(with = MessageContentPolymorphicSerializer::class)
    val content: MessageContent
)