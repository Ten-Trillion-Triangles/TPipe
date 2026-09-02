package com.TTT.Debug

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TraceSinkTest
{
    @AfterEach
    fun cleanup()
    {
        PipeTracer.clearSinks()
        PipeTracer.clearTrace("trace-sink-test")
        PipeTracer.disable()
    }

    @Test
    fun deliversEventsWithoutChangingTraceHistoryWhenSinkFails()
    {
        val delivered = mutableListOf<TraceEvent>()
        PipeTracer.enable()
        PipeTracer.registerSink("capture") { _, event -> delivered += event }
        PipeTracer.registerSink("failing") { _, _ -> error("sink failure") }

        val event = TraceEvent(
            timestamp = 1L,
            pipeId = "pipe",
            pipeName = "Pipe",
            eventType = TraceEventType.PIPE_START,
            phase = TracePhase.INITIALIZATION,
            content = null,
            contextSnapshot = null
        )
        PipeTracer.addEvent("trace-sink-test", event)

        assertEquals(listOf(event), delivered)
        assertEquals(listOf(event), PipeTracer.getTrace("trace-sink-test"))
    }
}
