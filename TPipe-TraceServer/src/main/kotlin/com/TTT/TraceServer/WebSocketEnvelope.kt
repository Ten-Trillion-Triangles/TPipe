package com.TTT.TraceServer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * v2 sub-protocol envelope for the `/ws/traces` WebSocket. The shape is
 * additive: legacy clients that just listen for "summary" frames keep
 * working; new clients can `subscribe` to receive live events for one
 * pipeline and send `unsubscribe` to opt out.
 *
 * Wire shape (one JSON object per frame, no envelope wrapper):
 * ```
 * {"op":"summary","pipelineId":"...","timestamp":...,"name":"...","status":"..."}
 * {"op":"event","pipelineId":"...","eventId":"...","ts":...,"type":"...","payload":{...}}
 * {"op":"subscribe","pipelineId":"..."}
 * {"op":"unsubscribe","pipelineId":"..."}
 * {"op":"ack","op":"subscribe","pipelineId":"..."}
 * {"op":"error","message":"..."}
 * ```
 *
 * The `op` field is the discriminator; clients that don't understand an
 * op MUST drop the frame silently (additive protocol).
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("op")
sealed class WebSocketEnvelope {

    @Serializable
    @SerialName("summary")
    data class Summary(
        val pipelineId: String,
        val timestamp: Long,
        val name: String,
        val status: String
    ) : WebSocketEnvelope()

    @Serializable
    @SerialName("event")
    data class Event(
        val pipelineId: String,
        val eventId: String,
        val ts: Long,
        val type: String,
        val payload: kotlinx.serialization.json.JsonElement
    ) : WebSocketEnvelope()

    @Serializable
    @SerialName("subscribe")
    data class Subscribe(val pipelineId: String) : WebSocketEnvelope()

    @Serializable
    @SerialName("unsubscribe")
    data class Unsubscribe(val pipelineId: String) : WebSocketEnvelope()

    @Serializable
    @SerialName("ack")
    data class Ack(val op: String, val pipelineId: String) : WebSocketEnvelope()

    @Serializable
    @SerialName("error")
    data class ErrorMsg(val message: String) : WebSocketEnvelope()
}
