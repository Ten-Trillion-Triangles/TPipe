package com.TTT.Debug

import com.TTT.Pipeline.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Pins the contract that every container's `enableTracing(TraceConfig(maxHistory = N))`
 * propagates `N` to [PipeTracer.setMaxHistory]. The pre-2026-08-08 codebase honored this
 * in Pipeline and PumpStation only; the other 6 containers silently dropped the value.
 *
 * Each container has its own `enableTracing` overload — the test exercises every one
 * so a future regression that re-introduces the gap is caught immediately.
 */
class ContainerMaxHistoryPropagationTest {

    @BeforeEach
    fun setup() {
        PipeTracer.enable()
    }

    @AfterEach
    fun cleanup() {
        PipeTracer.disable()
    }

    @Test
    fun pipeline_enableTracing_propagatesMaxHistory() {
        val p = Pipeline().enableTracing(TraceConfig(maxHistory = 42))
        assertEquals(42, PipeTracer.getMaxHistoryForTest())
    }

    @Test
    fun pumpStation_enableTracing_propagatesMaxHistory() {
        val ps = PumpStation().enableTracing(TraceConfig(maxHistory = 17))
        assertEquals(17, PipeTracer.getMaxHistoryForTest())
    }

    @Test
    fun manifold_enableTracing_propagatesMaxHistory() {
        val m = Manifold().enableTracing(TraceConfig(maxHistory = 23))
        assertEquals(23, PipeTracer.getMaxHistoryForTest())
    }

    @Test
    fun splitter_enableTracing_propagatesMaxHistory() {
        val s = Splitter().enableTracing(TraceConfig(maxHistory = 31))
        assertEquals(31, PipeTracer.getMaxHistoryForTest())
    }

    @Test
    fun junction_enableTracing_propagatesMaxHistory() {
        val j = Junction().enableTracing(TraceConfig(maxHistory = 53))
        assertEquals(53, PipeTracer.getMaxHistoryForTest())
    }

    @Test
    fun distributionGrid_enableTracing_propagatesMaxHistory() {
        val g = DistributionGrid().enableTracing(TraceConfig(maxHistory = 67))
        assertEquals(67, PipeTracer.getMaxHistoryForTest())
    }

    @Test
    fun connector_enableTracing_propagatesMaxHistory() {
        val c = Connector().enableTracing(TraceConfig(maxHistory = 89))
        assertEquals(89, PipeTracer.getMaxHistoryForTest())
    }

    @Test
    fun multiConnector_enableTracing_propagatesMaxHistory() {
        val mc = MultiConnector().enableTracing(TraceConfig(maxHistory = 11))
        assertEquals(11, PipeTracer.getMaxHistoryForTest())
    }

    /**
     * Behavioral check: trace events past the configured limit are dropped.
     * Without this, a user who sets `maxHistory = 3` would see N+1 events in the trace —
     * a silent contract violation. This test pins the user-visible behavior, not just
     * the field-read.
     */
    @Test
    fun traceEventsBeyondMaxHistoryAreTruncated() {
        val limit = 3
        Pipeline().enableTracing(TraceConfig(maxHistory = limit))
        val pipelineId = "truncate-test"

        for (i in 0 until limit + 5) {
            PipeTracer.addEvent(pipelineId, TraceEvent(
                timestamp = i.toLong(),
                pipeId = "pipe-$i",
                pipeName = "Pipe$i",
                eventType = TraceEventType.PIPE_SUCCESS,
                phase = TracePhase.CLEANUP,
                content = null,
                contextSnapshot = null
            ))
        }

        val trace = PipeTracer.getTrace(pipelineId)
        assertEquals(limit, trace.size, "Trace should be trimmed to the configured maxHistory")
        // Oldest events dropped: the surviving trace starts at index 5, not 0
        assertEquals("Pipe5", trace.first().pipeName)
        assertEquals("Pipe${limit + 5 - 1}", trace.last().pipeName)
    }
}
