package com.TTT.AgentCore.agui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Small AG-UI event model owned by the AgentCore integration module.
 *
 * @param type AG-UI event type.
 * @param messageId Optional message identifier.
 * @param delta Optional streamed text delta.
 * @param done Whether this event terminates the stream.
 */
data class AgentCoreAgUiEvent(
    val type: String,
    val messageId: String? = null,
    val delta: String? = null,
    val done: Boolean = false
)

/** Encode AG-UI events as Server-Sent Events. */
object AgentCoreAgUiEventEncoder {
    /**
     * Return one `data:` frame for [event].
     *
     * @param event Event to encode.
     * @return One SSE data frame.
     */
    fun encode(event: AgentCoreAgUiEvent): String
    {
        val json = buildJsonObject {
            put("type", event.type)
            event.messageId?.let { put("messageId", it) }
            event.delta?.let { put("delta", it) }
            put("done", event.done)
        }
        return "data: $json\n\n"
    }
}
