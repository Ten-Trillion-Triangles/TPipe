package genericOpenAIPipe.env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Custom serializer for [MessageContent] that handles multiple JSON formats:
 * - Plain string: `"Hello!"` -> [MessageContent.PlainContent]
 * - Object format: `{"type": "text", "text": "..."}` -> [MessageContent.TextContent]
 */
object MessageContentPolymorphicSerializer : JsonContentPolymorphicSerializer<MessageContent>(MessageContent::class)
{
    override fun selectDeserializer(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.KSerializer<out MessageContent>
    {
        // Check if it's a plain string primitive first
        if(element is JsonPrimitive && element.isString) {
            return MessageContentPlainSerializer
        }
        // Otherwise it's an object with type discriminator
        return MessageContentObjectSerializer
    }
}

/**
 * Serializer for plain string content.
 */
object MessageContentPlainSerializer : kotlinx.serialization.KSerializer<MessageContent.PlainContent>
{
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("MessageContent.PlainContent", kotlinx.serialization.descriptors.PrimitiveKind.STRING)

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): MessageContent.PlainContent
    {
        return MessageContent.PlainContent(decoder.decodeString())
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: MessageContent.PlainContent)
    {
        encoder.encodeString(value.content)
    }
}

/**
 * Serializer for object-style content (type discriminator).
 */
object MessageContentObjectSerializer : kotlinx.serialization.KSerializer<MessageContent>
{
    override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("MessageContent")

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): MessageContent
    {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: throw IllegalStateException("This serializer only works with JSON")

        val element = jsonDecoder.decodeJsonElement()
        val jsonObj: JsonObject = element.jsonObject
        val type = jsonObj["type"]?.jsonPrimitive?.content

        return when(type)
        {
            "text" -> MessageContent.TextContent(jsonObj["text"]?.jsonPrimitive?.content ?: "")
            // For "multi" type, we don't have block data available, return empty TextContent
            "multi" -> MessageContent.TextContent("")
            // Plain TextContent format: {"text": "..."} without type discriminator
            null -> if(jsonObj.containsKey("text")) {
                MessageContent.TextContent(jsonObj["text"]?.jsonPrimitive?.content ?: "")
            } else {
                MessageContent.PlainContent(jsonObj.toString())
            }
            else -> MessageContent.PlainContent(jsonObj.toString())
        }
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: MessageContent)
    {
        val jsonEncoder = encoder as? kotlinx.serialization.json.JsonEncoder
            ?: throw IllegalStateException("This serializer only works with JSON")

        when(value)
        {
            is MessageContent.TextContent -> {
                jsonEncoder.encodeJsonElement(kotlinx.serialization.json.JsonObject(mapOf(
                    "type" to kotlinx.serialization.json.JsonPrimitive("text"),
                    "text" to kotlinx.serialization.json.JsonPrimitive(value.text)
                )))
            }
            is MessageContent.PlainContent -> jsonEncoder.encodeString(value.content)
            is MessageContent.MultimodalContent -> {
                jsonEncoder.encodeJsonElement(kotlinx.serialization.json.JsonObject(mapOf(
                    "type" to kotlinx.serialization.json.JsonPrimitive("multi"),
                    "blocks" to kotlinx.serialization.json.JsonArray(value.blocks.map { block ->
                        when(block)
                        {
                            is ContentBlock.TextBlock -> kotlinx.serialization.json.JsonObject(mapOf(
                                "type" to kotlinx.serialization.json.JsonPrimitive("text"),
                                "text" to kotlinx.serialization.json.JsonPrimitive(block.text)
                            ))
                            is ContentBlock.ImageUrlBlock -> kotlinx.serialization.json.JsonObject(mapOf(
                                "type" to kotlinx.serialization.json.JsonPrimitive("image_url"),
                                "image_url" to kotlinx.serialization.json.JsonPrimitive(block.url)
                            ))
                        }
                    })
                )))
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