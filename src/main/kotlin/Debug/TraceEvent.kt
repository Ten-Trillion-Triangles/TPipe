package com.TTT.Debug

import com.TTT.Pipe.MultimodalContent
import com.TTT.Context.ContextWindow

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable
data class TraceEvent(
    val id: String = generateEventId(),
    val timestamp: Long,
    val pipeId: String,
    val pipeName: String,
    val eventType: TraceEventType,
    val phase: TracePhase,
    val content: MultimodalContent?,
    val contextSnapshot: ContextWindow?,
    @Serializable(with = MapAnySerializer::class)
    val metadata: Map<String, Any> = emptyMap(),
    @Transient
    val error: Throwable? = null
) {
    companion object {
        private var eventCounter = 0L
        private fun generateEventId(): String = "trace-event-${++eventCounter}"
    }
}

object MapAnySerializer : KSerializer<Map<String, Any>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MapAny", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Map<String, Any>) {
        val json = buildJsonObject {
            for ((k, v) in value) {
                putValue(k, v)
            }
        }
        encoder.encodeSerializableValue(JsonObject.serializer(), json)
    }

    private fun JsonObjectBuilder.putValue(k: String, v: Any?) {
        when (v) {
            is String -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            is Map<*, *> -> put(k, buildJsonObject { 
                @Suppress("UNCHECKED_CAST")
                (v as? Map<String, Any>)?.forEach { (mk, mv) -> putValue(mk, mv) } 
            })
            is List<*> -> put(k, buildJsonArray {
                v.forEach { addValue(it) }
            })
            null -> put(k, JsonNull)
            else -> put(k, v.toString())
        }
    }
    
    private fun JsonArrayBuilder.addValue(v: Any?) {
        when (v) {
            is String -> add(v)
            is Number -> add(v)
            is Boolean -> add(v)
            is Map<*, *> -> add(buildJsonObject {
                @Suppress("UNCHECKED_CAST")
                 (v as? Map<String, Any>)?.forEach { (mk, mv) -> putValue(mk, mv) }
            })
            null -> add(JsonNull)
            else -> add(v.toString())
        }
    }

    override fun deserialize(decoder: Decoder): Map<String, Any> {
        val json = decoder.decodeSerializableValue(JsonObject.serializer())
        return json.mapValues { it.value.toString() } // Simplification for now, as we mainly only write traces
    }
}