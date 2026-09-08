package com.TTT.AgentCore.LiveSmoke

import aws.smithy.kotlin.runtime.content.Document

/** Builds the smallest valid OpenTelemetry span accepted by AgentCore Evaluate. */
internal object LiveSmokeEvaluationFixtures
{
    /** Return a synthetic span with the required identity, session, and time fields. */
    fun syntheticSessionSpanFields(
        traceId: String,
        spanId: String,
        sessionId: String,
        startTimeUnixNano: Long,
        endTimeUnixNano: Long
    ): Map<String, Document> = mapOf(
        "traceId" to Document.String(traceId),
        "spanId" to Document.String(spanId),
        "name" to Document.String("agentcore-live-smoke"),
        "scope" to Document.Map(mapOf("name" to Document.String("tpipe.live-smoke"))),
        "startTimeUnixNano" to Document.Number(startTimeUnixNano),
        "endTimeUnixNano" to Document.Number(endTimeUnixNano),
        "attributes" to Document.Map(
            mapOf("session.id" to Document.String(sessionId))
        ),
        "status" to Document.Map(mapOf("code" to Document.String("OK")))
    )

    /** Convert a validated smoke run identifier into an AgentCore client token. */
    fun clientToken(runId: String, suffix: String): String = "$runId-$suffix".replace('_', '-')
}
