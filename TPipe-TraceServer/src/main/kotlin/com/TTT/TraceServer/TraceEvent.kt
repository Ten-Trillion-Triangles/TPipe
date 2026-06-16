package com.TTT.TraceServer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Single trace event submitted by an agent to the v2 event-streaming
 * endpoint (`POST /api/traces/{id}/events`).
 *
 * The wire shape is intentionally small and schema-less on the payload
 * side: any `JsonElement` is accepted as the [payload]. The agent supplies
 * a stable [type] (e.g. `llm_call`, `tool_invoke`, `state_transition`)
 * and the server stamps [ts] with its own clock so the dashboard can
 * render a consistent timeline.
 *
 * @property eventId a unique identifier (UUID) for the event. Required so
 *  the dashboard can deduplicate on replay after a server restart.
 * @property ts server-stamped wall-clock time in milliseconds since epoch.
 *  The server overwrites whatever the client sends; clients that want a
 *  client-side timestamp should embed it in [payload].
 * @property type a stable identifier for the event kind.
 * @property payload free-form JSON payload; the server stores it as-is.
 */
@Serializable
data class TraceEvent(
    val eventId: String,
    val ts: Long,
    val type: String,
    val payload: JsonElement
)
