package com.TTT.Debug

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RemoteTraceDispatcherWireTest {

    @Test
    fun tracePayloadDecodesLegacyV1ShapeWithoutKindField() {
        // v1 callers serialize without `kind`. Decoder must accept.
        val v1Json = """{"pipelineId":"p-1","htmlContent":"<html/>","name":"x","status":"SUCCESS"}"""
        val decoded = Json.decodeFromString(TracePayload.serializer(), v1Json)
        assertEquals("p-1", decoded.pipelineId)
        assertNull(decoded.kind) // v1 → null on receive
    }

    @Test
    fun tracePayloadRoundTripsKindField() {
        val original = TracePayload(
            pipelineId = "p-2",
            htmlContent = "<html/>",
            name = "x",
            status = "SUCCESS",
            kind = "pumpstation",
        )
        val json = Json.encodeToString(TracePayload.serializer(), original)
        val decoded = Json.decodeFromString(TracePayload.serializer(), json)
        assertEquals("pumpstation", decoded.kind)
    }
}