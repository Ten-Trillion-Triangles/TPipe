package com.TTT.AgentCore.LiveSmoke

import aws.smithy.kotlin.runtime.content.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSmokeEvaluationFixturesTest
{
    @Test
    fun syntheticSpanIncludesRequiredOpenTelemetryTimestamps()
    {
        val fields = LiveSmokeEvaluationFixtures.syntheticSessionSpanFields(
            traceId = "trace-1",
            spanId = "span-1",
            sessionId = "session-1",
            startTimeUnixNano = 1_000L,
            endTimeUnixNano = 2_000L
        )

        assertEquals(Document.Number(1_000L), fields["startTimeUnixNano"])
        assertEquals(Document.Number(2_000L), fields["endTimeUnixNano"])
        assertEquals(
            Document.String("session-1"),
            (fields.getValue("attributes") as Document.Map)["session.id"]
        )
        assertTrue(fields.containsKey("traceId"))
        assertTrue(fields.containsKey("spanId"))
    }

    @Test
    fun batchClientTokensUseTheAgentCoreAllowedCharacterSet()
    {
        val token = LiveSmokeEvaluationFixtures.clientToken("tpipe_smoke_20260907_live2", "insights")

        assertEquals("tpipe-smoke-20260907-live2-insights", token)
        assertTrue(token.matches(Regex("[a-zA-Z0-9](-*[a-zA-Z0-9]){0,256}")))
    }
}
