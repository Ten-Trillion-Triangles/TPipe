package com.TTT.AgentCore.Runtime.AgUi

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Byte-stable SSE and WebSocket encoding for AgentCore-local AG-UI events. */
object AgentCoreAgUiEventEncoder {
    /** Encode one event as a canonical SSE data frame. */
    fun encodeSse(event: AgentCoreAgUiEvent): String = "data: ${encodeJson(event)}\n\n"

    /** Encode one event as a WebSocket JSON frame. */
    fun encodeWebSocket(event: AgentCoreAgUiEvent): String = encodeJson(event)

    /** Encode only populated optional fields to keep the wire contract compact. */
    fun encodeJson(event: AgentCoreAgUiEvent): String = buildJsonObject {
        put("type", event.type)
        put("threadId", event.threadId)
        put("runId", event.runId)
        event.messageId?.let { put("messageId", it) }
        event.role?.let { put("role", it) }
        event.delta?.let { put("delta", it) }
        event.error?.let { put("message", it) }
    }.toString()
}
