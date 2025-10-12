package com.TTT.Serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.double

/**
 * Custom serializer that handles type coercion from Double/Float to Int during JSON deserialization.
 * LLM's seem to have major trouble with obey rules on providing ints in json schemas. So this will handle
 * coercion from double back to int.
 * 
 * Usage: Apply to Int fields that may receive Double values from JSON:
 * @Serializable(with = IntCoercionSerializer::class)
 * val weight: Int
 * 
 * Conversion behavior:
 * - Double/Float values are truncated to Int (0.9 becomes 0, 2.7 becomes 2)
 * - String representations of numbers are parsed and converted
 * - Invalid or non-numeric values default to 0
 * - Standard Int values pass through unchanged
 */
object IntCoercionSerializer : KSerializer<Int>
{
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IntCoercion", PrimitiveKind.INT)

    /**
     * Serialize Int value to JSON - standard encoding
     */
    override fun serialize(encoder: Encoder, value: Int)
    {
        encoder.encodeInt(value)
    }

    /**
     * Deserialize JSON value to Int with type coercion support
     */
    override fun deserialize(decoder: Decoder): Int
    {
        // Check if we're dealing with JSON input that may contain mixed types
        return when (val jsonDecoder = decoder as? JsonDecoder)
        {
            // Non-JSON decoder - use standard Int decoding
            null -> decoder.decodeInt()
            
            // JSON decoder - handle type coercion
            else -> {
                val element = jsonDecoder.decodeJsonElement()
                when (element)
                {
                    is JsonPrimitive -> {
                        when
                        {
                            // Handle string representations of numbers (e.g., "42" or "3.14")
                            element.isString -> element.content.toDoubleOrNull()?.toInt() ?: 0
                            
                            // Handle numeric values (Double, Float, Int) - convert to Int via Double
                            else -> element.double.toInt()
                        }
                    }
                    
                    // Non-primitive JSON elements (objects, arrays) default to 0
                    else -> 0
                }
            }
        }
    }
}
