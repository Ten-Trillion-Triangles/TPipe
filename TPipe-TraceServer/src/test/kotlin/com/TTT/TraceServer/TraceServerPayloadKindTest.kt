package com.TTT.TraceServer

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TraceServerPayloadKindTest {

    @Test
    fun tracePayloadDecodesLegacyV1ShapeWithoutKind() {
        val v1Json = """{"pipelineId":"p-1","htmlContent":"x","name":"x","status":"SUCCESS"}"""
        val decoded = Json.decodeFromString(TracePayload.serializer(), v1Json)
        assertNull(decoded.kind)
    }

    @Test
    fun traceSummaryEncodesKind() {
        val s = TraceSummary(id = "p-2", timestamp = 0L, name = "n", status = "SUCCESS", kind = "pumpstation")
        val json = Json.encodeToString(TraceSummary.serializer(), s)
        val decoded = Json.decodeFromString(TraceSummary.serializer(), json)
        assertEquals("pumpstation", decoded.kind)
    }

    @Test
    fun traceSummaryDecodesLegacyV1ShapeWithoutKind() {
        val v1Json = """{"id":"p-1","timestamp":0,"name":"n","status":"SUCCESS"}"""
        val decoded = Json.decodeFromString(TraceSummary.serializer(), v1Json)
        assertNull(decoded.kind)
    }
}