package com.TTT.Pipeline

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceEventType
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Runtime integration tests for the steering feature's runtime API
 * ([steer], [steerPersistent], [clearSteering]) and the [drainSteeringForPhase]
 * helper. These tests pin the behavior at the public surface level, without
 * spinning up a full harness loop.
 *
 * Three concerns covered:
 *   - Task 11: runtime steer() fires at next phase boundary without halting
 *   - Task 12: metadata provenance on every entry
 *   - Task 13: concurrent steer() calls drain without loss or duplication
 */
class PumpStationSteeringRuntimeTest
{
    private fun buildStation(): PumpStation
    {
        return pumpStation("runtime-test-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") {
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
    }

    //=====================================Task 11: runtime steer fires at next phase boundary=========

    @Test
    fun `steer enqueues one-shot that drains on the next phase boundary`() = runTest {
        val station = buildStation()
        val phase = PumpStationPausePhase.BeforeJudge

        station.steer(phase, "user just asked: focus on security")
        val drained = station.drainSteeringForPhase(phase)

        assertEquals(1, drained.size)
        assertEquals("user just asked: focus on security", drained[0].text)
    }

    @Test
    fun `steer with MultimodalContent overload preserves the content`() = runTest {
        val station = buildStation()
        val phase = PumpStationPausePhase.AfterDispatch
        val custom = MultimodalContent(text = "custom injected").apply {
            metadata["origin"] = "external-monitor"
        }

        station.steer(phase, custom)
        val drained = station.drainSteeringForPhase(phase)

        assertEquals(1, drained.size)
        assertEquals("custom injected", drained[0].text)
        assertEquals("external-monitor", drained[0].metadata["origin"])
    }

    @Test
    fun `steerPersistent sets overlay that fires on every drain`() = runTest {
        val station = buildStation()
        val phase = PumpStationPausePhase.BeforeJudge

        station.steerPersistent(phase, "always verify the user's last message")

        val firstDrain = station.drainSteeringForPhase(phase)
        assertEquals(1, firstDrain.size)
        assertEquals("always verify the user's last message", firstDrain[0].text)

        val secondDrain = station.drainSteeringForPhase(phase)
        assertEquals(1, secondDrain.size, "persistent overlay must survive the first drain")
    }

    @Test
    fun `clearSteering removes the persistent overlay`() = runTest {
        val station = buildStation()
        val phase = PumpStationPausePhase.BeforeJudge

        station.steerPersistent(phase, "always check the user")
        assertTrue(station.steeringService.hasPersistentOverlay(phase))

        station.clearSteering(phase)
        assertEquals(false, station.steeringService.hasPersistentOverlay(phase))

        val drained = station.drainSteeringForPhase(phase)
        assertTrue(drained.isEmpty())
    }

    @Test
    fun `drain is non-halt and can be called repeatedly without side effects on empty phases`() = runTest {
        val station = buildStation()
        val phase = PumpStationPausePhase.BeforeGoalValidation

        repeat(5) {
            val drained = station.drainSteeringForPhase(phase)
            assertTrue(drained.isEmpty(), "drain on empty phase must be empty")
        }
    }

    //=====================================Task 12: metadata provenance on every entry====================

    @Test
    fun `every drained entry carries metadata steering envelope with all four fields`() = runTest {
        val station = buildStation()
        val phase = PumpStationPausePhase.BeforeJudge

        station.steer(phase, "nudge 1")
        station.steer(phase, "nudge 2")

        val drained = station.drainSteeringForPhase(phase)
        assertEquals(2, drained.size)

        for ((index, entry) in drained.withIndex()) {
            val steeringEnvelope = entry.metadata["steering"] as? Map<*, *>
            assertNotNull(steeringEnvelope, "entry #$index must have a steering metadata envelope")

            // Phase must match the drain site
            assertEquals(phase.name, steeringEnvelope!!["phase"])

            // One-shot entries (after a persistent overlay would be at index 0, but
            // there is no overlay here) are all persistent=false
            assertEquals(false, steeringEnvelope["persistent"])

            // injectionId must be a non-empty String
            val injectionId = steeringEnvelope["injectionId"] as? String
            assertNotNull(injectionId, "injectionId must be a String")
            assertTrue(injectionId!!.isNotEmpty())

            // timestamp must be a Long
            val timestamp = steeringEnvelope["timestamp"]
            assertTrue(timestamp is Long, "timestamp must be a Long, got ${timestamp?.javaClass}")
        }
    }

    @Test
    fun `persistent overlay entry has persistent true in metadata envelope`() = runTest {
        val station = buildStation()
        val phase = PumpStationPausePhase.BeforeJudge

        station.steerPersistent(phase, "always check")
        val drained = station.drainSteeringForPhase(phase)

        assertEquals(1, drained.size)
        val envelope = drained[0].metadata["steering"] as Map<*, *>
        assertEquals(true, envelope["persistent"])
        assertEquals(phase.name, envelope["phase"])
    }

    @Test
    fun `one-shot entry has persistent false in metadata envelope`() = runTest {
        val station = buildStation()
        val phase = PumpStationPausePhase.BeforeJudge

        station.steer(phase, "one-shot nudge")
        val drained = station.drainSteeringForPhase(phase)

        assertEquals(1, drained.size)
        val envelope = drained[0].metadata["steering"] as Map<*, *>
        assertEquals(false, envelope["persistent"])
    }

    @Test
    fun `drain merges existing metadata with steering envelope without losing prior keys`() = runTest {
        val station = buildStation()
        val phase = PumpStationPausePhase.BeforeJudge
        val custom = MultimodalContent(text = "preserved").apply {
            metadata["prior-key"] = "prior-value"
        }

        station.steer(phase, custom)
        val drained = station.drainSteeringForPhase(phase)

        assertEquals(1, drained.size)
        // Prior metadata must still be present
        assertEquals("prior-value", drained[0].metadata["prior-key"])
        // Steering envelope must be added
        assertNotNull(drained[0].metadata["steering"])
    }

    //=====================================Task 13: concurrent steer calls drain without loss============

    @Test
    fun `100 concurrent steer calls drain without loss or duplication`() = runTest {
        val station = buildStation()
        val phase = PumpStationPausePhase.BeforeJudge
        val count = 100

        coroutineScope {
            val jobs = (1..count).map { i ->
                async { station.steer(phase, "concurrent nudge $i") }
            }
            jobs.awaitAll()
        }

        val drained = station.drainSteeringForPhase(phase)
        assertEquals(count, drained.size, "all $count concurrent steers must be drained exactly once")

        // Each entry's text must be unique (no duplication)
        val texts = drained.map { it.text }.toSet()
        assertEquals(count, texts.size, "no concurrent steer produced a duplicate entry")

        val secondDrain = station.drainSteeringForPhase(phase)
        assertTrue(secondDrain.isEmpty(), "queue must be empty after the first drain")
    }

    @Test
    fun `concurrent steerPersistent calls produce a single coherent overlay`() = runTest {
        val station = buildStation()
        val phase = PumpStationPausePhase.BeforeJudge
        val count = 50

        coroutineScope {
            val jobs = (1..count).map { i ->
                async { station.steerPersistent(phase, "concurrent overlay $i") }
            }
            jobs.awaitAll()
        }

        val drained = station.drainSteeringForPhase(phase)
        assertEquals(1, drained.size, "persistent overlay is single-valued regardless of concurrent sets")
        // The text must be one of the 50 candidates (last-writer-wins for ConcurrentHashMap.put)
        val text = drained[0].text
        assertTrue(text.startsWith("concurrent overlay "), "overlay text must be from the candidate set")
    }

    @Test
    fun `mixed concurrent steer and steerPersistent produce consistent drain order`() = runTest {
        val station = buildStation()
        val phase = PumpStationPausePhase.BeforeJudge

        coroutineScope {
            val persistJob = async { station.steerPersistent(phase, "OVERLAY") }
            val oneShotJobs = (1..20).map { i ->
                async { station.steer(phase, "ONE-SHOT-$i") }
            }
            persistJob.await()
            oneShotJobs.awaitAll()
        }

        val drained = station.drainSteeringForPhase(phase)
        assertEquals(21, drained.size, "expected 1 overlay + 20 one-shots")
        assertEquals("OVERLAY", drained[0].text, "persistent overlay must come first")
        // One-shots must be present (order between concurrent one-shots is not strictly FIFO,
        // but all 20 must be drained exactly once)
        val oneShots = drained.drop(1).map { it.text }.toSet()
        assertEquals(20, oneShots.size, "all 20 one-shots must be drained exactly once")
    }

    //=====================================Trace emission: envelope rendering========================

    @Test
    fun injectSteeringForPhaseEmitsTraceEventWithEnvelopeMetadata()
    {
        // Pin the trace-emission contract: when injectSteeringForPhase drains
        // an entry from the steering service, the harness MUST emit a
        // PUMP_STATION_STEERING_INJECTED event into the PipeTracer with the
        // canonical envelope (phase, persistent, injectionId, timestamp) in
        // event.metadata["steering"]. Without this emission, the visualizer
        // cannot render the envelope and operators can't see why a turn was
        // steered (gap closed: 2026-07-24).
        runTest {
            val station = buildStation()
            // Trace emission gates on tracingEnabledInternal AND a non-blank
            // taskState.runId. The buildStation() helper doesn't execute the
            // harness, so runId stays "" — explicitly set it so the new
            // PUMP_STATION_STEERING_INJECTED event reaches the PipeTracer.
            station.taskState.runId = "test-steer-${System.nanoTime()}"
            // Enable tracing so the new event reaches the PipeTracer.
            station.enableTracing(TraceConfig(enabled = true))
            val phase = PumpStationPausePhase.BeforeJudge
            val runId = station.taskState.runId

            // Snapshot the trace size BEFORE the inject so we can find the new event.
            val traceBefore = PipeTracer.getAllTraces().values.flatten()
            val sizeBefore = traceBefore.size

            station.steer(phase, "user just asked: focus on memory overhead, not throughput")
            station.injectSteeringForPhase(phase)

            val traceAfter = PipeTracer.getAllTraces().values.flatten()
            val newEvents = traceAfter
                .filter { it.metadata["runId"] == runId }
            val steeringEvents = newEvents.filter {
                it.eventType == TraceEventType.PUMP_STATION_STEERING_INJECTED
            }
            assertEquals(
                1, steeringEvents.size,
                "expected exactly 1 PUMP_STATION_STEERING_INJECTED event in trace; " +
                    "got ${steeringEvents.size} new events of type " +
                    TraceEventType.PUMP_STATION_STEERING_INJECTED.name
            )

            val ev = steeringEvents[0]
            assertEquals(runId, ev.metadata["runId"], "runId must match the harness's runId")
            // The envelope must be present in metadata["steering"] as a Map
            // with the four canonical fields.
            @Suppress("UNCHECKED_CAST")
            val envelope = ev.metadata["steering"] as? Map<String, Any>
            assertNotNull(envelope, "PUMP_STATION_STEERING_INJECTED event missing " +
                "metadata['steering'] envelope; metadata keys: ${ev.metadata.keys}")
            assertEquals(phase.name, envelope!!["phase"])
            assertEquals(false, envelope["persistent"], "one-shot steering must be persistent=false")
            assertTrue((envelope["injectionId"] as? String)?.isNotBlank() == true,
                "envelope injectionId must be non-blank")
            assertTrue((envelope["timestamp"] as? Long) ?: 0L > 0L,
                "envelope timestamp must be positive epoch millis")
        }
    }
}
