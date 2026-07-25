package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PumpStationUnknownPathLoopGuardTest
{
    @Test
    fun `consecutive UnknownPath dispatches halt the harness when limit is set`() {
        val station = pumpStation("unknown-path-halt-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            maxConsecutiveUnknownPaths = 3
            path("realPath") {
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ -> content }
            }
        }

        // First two UnknownPath dispatches: returns null (not halted yet).
        runBlocking {
            assertNull(station.runPathFlow(PathRequest(pathName = "flarble")),
                "First UnknownPath dispatch must not trip the guard")
            assertNull(station.runPathFlow(PathRequest(pathName = "baz")),
                "Second UnknownPath dispatch must not trip the guard")
        }

        // Third UnknownPath dispatch: trips the guard. runPathFlow now returns
        // the input content with terminatePipeline set, and taskState.exitReason
        // is LoopGuardTripped.
        val result = runBlocking { station.runPathFlow(PathRequest(pathName = "qux")) }
        assertNotNull(result, "Guard trip must return non-null content so the runTurn halt path fires")
        assertTrue(result.terminatePipeline,
            "Guard trip must set terminatePipeline=true on the result so the harness halts")
        assertEquals(PumpStationExitReason.LoopGuardTripped, station.taskState.exitReason,
            "exitReason must be LoopGuardTripped after the guard trips")
        assertEquals(PumpStationError.LoopGuardTriggered, station.taskState.lastError,
            "lastError must be LoopGuardTriggered after the guard trips")
    }

    @Test
    fun `counter resets when a resolved path runs`() {
        val station = pumpStation("unknown-path-reset-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            maxConsecutiveUnknownPaths = 3
            path("realPath") {
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ -> content }
            }
        }

        runBlocking {
            // Two UnknownPath dispatches: counter at 2.
            station.runPathFlow(PathRequest(pathName = "flarble"))
            station.runPathFlow(PathRequest(pathName = "baz"))
            assertEquals(2, station.consecutiveUnknownPathCount,
                "Counter should be 2 after two UnknownPath dispatches")

            // One resolved dispatch: counter resets to 0.
            val resolved = station.runPathFlow(PathRequest(pathName = "realPath"))
            assertNotNull(resolved, "Resolved path must return non-null content")
            assertFalse(resolved.terminatePipeline,
                "Resolved path must not signal termination")
            assertEquals(0, station.consecutiveUnknownPathCount,
                "Counter should reset to 0 after a resolved path runs")

            // Now 3 more UnknownPath dispatches should trip the guard (counter restarts from 0).
            station.runPathFlow(PathRequest(pathName = "x1"))
            station.runPathFlow(PathRequest(pathName = "x2"))
            val tripped = station.runPathFlow(PathRequest(pathName = "x3"))
            assertNotNull(tripped, "Third consecutive UnknownPath after reset must trip the guard")
            assertTrue(tripped.terminatePipeline, "Tripped result must set terminatePipeline=true")
        }
    }

    @Test
    fun `null maxConsecutiveUnknownPaths preserves unbounded behavior`() {
        val station = pumpStation("unknown-path-unbounded-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            // No maxConsecutiveUnknownPaths set — default null.
            path("realPath") {
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ -> content }
            }
        }

        runBlocking {
            // 5 UnknownPath dispatches; with no guard, all return null and the
            // counter increments without tripping.
            repeat(5) {
                val result = station.runPathFlow(PathRequest(pathName = "flarble-$it"))
                assertNull(result, "Without the guard, UnknownPath must return null (no termination)")
            }
            assertEquals(5, station.consecutiveUnknownPathCount,
                "Counter should accumulate all 5 unknown dispatches when the guard is null")
            assertNull(station.taskState.exitReason,
                "Without the guard, exitReason must not be set by UnknownPath dispatches")
        }
    }

    @Test
    fun `guard trip event names the dispatched path`() {
        val events = mutableListOf<PumpStationEvent>()
        val station = pumpStation("unknown-path-event-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            eventObserver = { ev -> synchronized(events) { events.add(ev) } }
            maxConsecutiveUnknownPaths = 1
            path("realPath") {
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ -> content }
            }
        }

        runBlocking {
            val result = station.runPathFlow(PathRequest(pathName = "specificName"))
            assertNotNull(result, "Single-dispatch limit must trip on the first call")
            assertTrue(result.terminatePipeline)
        }

        val tripEvent = synchronized(events) {
            events.filterIsInstance<LoopGuardTripped>().firstOrNull()
        }
        assertNotNull(tripEvent, "LoopGuardTripped event for maxConsecutiveUnknownPaths must be emitted")
        assertEquals("maxConsecutiveUnknownPaths", tripEvent.guard,
            "Trip event's guard field must name the loop-guard that fired")
        assertEquals("specificName", tripEvent.pathName,
            "LoopGuardTripped event must carry the actual dispatched path name")
        assertEquals(1, tripEvent.observed)
        assertEquals(1, tripEvent.limit)
    }
}
