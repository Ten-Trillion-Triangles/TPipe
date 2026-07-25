package com.TTT.Pipeline

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceEventType
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Runtime trace-emission tests for the interrupt feature. Pins the contract
 * that [PumpStation.injectInterruptForPhase] emits a
 * PUMP_STATION_INTERRUPT_FIRED trace event with the canonical envelope
 * (phase, wasRewound, injectionId, timestamp) in event.metadata["interrupt"]
 * so the [com.TTT.Debug.TraceVisualizer] can render the rewind-and-restart
 * decision as labeled rows in the pump HTML.
 */
class PumpStationInterruptRuntimeTest
{
    private fun buildStation(): PumpStation
    {
        return pumpStation("runtime-interrupt-test-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") {
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
    }

    @Test
    fun injectInterruptForPhaseEmitsTraceEventWithEnvelopeMetadata()
    {
        runTest {
            val station = buildStation()
            station.taskState.runId = "test-int-${System.nanoTime()}"
            station.enableTracing(TraceConfig(enabled = true))
            val phase = PumpStationPausePhase.BeforeJudge
            val runId = station.taskState.runId

            val traceBefore = PipeTracer.getAllTraces().values.flatten()
            val sizeBefore = traceBefore.size

            station.interrupt(phase, MultimodalContent(text = "drop the in-flight path and switch strategy"))

            // Call injectInterruptForPhase with a fresh snapshot. It will
            // throw PumpStationInterruptException after emitting the trace
            // event — catch it so the test can continue.
            val snapshot = PumpStationInterruptSnapshot(
                turnIndex = 0,
                latestContent = null,
                lastPathResult = null,
                selectedPathName = null,
                originalInput = MultimodalContent(text = "seed"),
                turnHistory = station.turnHistory.history.toList()
            )
            try
            {
                station.injectInterruptForPhase(phase, snapshot)
            }
            catch (e: PumpStationInterruptException)
            {
                // expected
            }

            val traceAfter = PipeTracer.getAllTraces().values.flatten()
            val allNew = traceAfter
                .filter { it.metadata["runId"] == runId }
            val interruptEvents = allNew.filter {
                it.eventType == TraceEventType.PUMP_STATION_INTERRUPT_FIRED
            }
            assertEquals(
                1, interruptEvents.size,
                "expected exactly 1 PUMP_STATION_INTERRUPT_FIRED event in trace; " +
                    "got ${interruptEvents.size} new events of type " +
                    TraceEventType.PUMP_STATION_INTERRUPT_FIRED.name
            )

            val ev = interruptEvents[0]
            assertEquals(runId, ev.metadata["runId"])
            @Suppress("UNCHECKED_CAST")
            val envelope = ev.metadata["interrupt"] as? Map<String, Any>
            assertNotNull(envelope, "PUMP_STATION_INTERRUPT_FIRED event missing " +
                "metadata['interrupt'] envelope; metadata keys: ${ev.metadata.keys}")
            assertEquals(phase.name, envelope!!["phase"])
            assertEquals(true, envelope["wasRewound"], "interrupts must be wasRewound=true")
            assertTrue((envelope["injectionId"] as? String)?.isNotBlank() == true,
                "envelope injectionId must be non-blank")
            assertTrue((envelope["timestamp"] as? Long) ?: 0L > 0L,
                "envelope timestamp must be positive epoch millis")
        }
    }
}