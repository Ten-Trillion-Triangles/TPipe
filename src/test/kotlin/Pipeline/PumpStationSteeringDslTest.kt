package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration tests for the `pumpStation { steeringPolicy { ... } }` DSL block.
 *
 * Verifies that:
 *   - The DSL block constructs a [PumpStationSteeringService] and propagates it to the built station
 *   - `persistentOverlay` calls seed the initial persistent overlays
 *   - `phaseBoundContent` calls seed the initial one-shot queue
 *   - The two coexist on the same phase
 *   - A station built without `steeringPolicy { }` has an empty default service
 */
class PumpStationSteeringDslTest
{
    /**
     * Build a minimal PumpStation with a no-op execution function and a bare
     * Pipeline dispatch agent. This lets the test exercise the steering DSL
     * without an LLM backing the path or the dispatch loop.
     */
    private fun buildMinimalStation(steeringBlock: PumpStationBuilder<*>.() -> Unit = {}): PumpStation
    {
        return pumpStation("test-station-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") {
                setExecutionFunction { content, _, _, _ -> content }
            }
            steeringBlock()
        }
    }

    //=====================================DSL propagation (Task 10)==============================================

    @Test
    fun `steeringPolicy DSL block propagates to built station`() {
        val station = buildMinimalStation {
            steeringPolicy {
                persistentOverlay(PumpStationPausePhase.BeforeJudge, "always check the user")
            }
        }

        assertNotNull(station.steeringService, "built station must have a non-null steeringService")
        assertTrue(station.steeringService.hasPersistentOverlay(PumpStationPausePhase.BeforeJudge))
    }

    @Test
    fun `persistentOverlay seeds the initial overlay visible at first drain`() = runTest {
        val station = buildMinimalStation {
            steeringPolicy {
                persistentOverlay(PumpStationPausePhase.BeforeJudge, "always check the user")
            }
        }

        val drained = station.drainSteeringForPhase(PumpStationPausePhase.BeforeJudge)
        assertEquals(1, drained.size)
        assertEquals("always check the user", drained[0].text)
    }

    @Test
    fun `phaseBoundContent seeds one-shot queue visible at first drain`() = runTest {
        val station = buildMinimalStation {
            steeringPolicy {
                phaseBoundContent(PumpStationPausePhase.AfterDispatch, "watch the async channel")
            }
        }

        val drained = station.drainSteeringForPhase(PumpStationPausePhase.AfterDispatch)
        assertEquals(1, drained.size)
        assertEquals("watch the async channel", drained[0].text)

        val secondDrain = station.drainSteeringForPhase(PumpStationPausePhase.AfterDispatch)
        assertTrue(secondDrain.isEmpty(), "initial one-shot must be consumed after first drain")
    }

    @Test
    fun `persistent and one-shot coexist on the same phase via DSL`() = runTest {
        val station = buildMinimalStation {
            steeringPolicy {
                persistentOverlay(PumpStationPausePhase.BeforeJudge, "OVERLAY")
                phaseBoundContent(PumpStationPausePhase.BeforeJudge, "ONE-SHOT-A")
                phaseBoundContent(PumpStationPausePhase.BeforeJudge, "ONE-SHOT-B")
            }
        }

        val drained = station.drainSteeringForPhase(PumpStationPausePhase.BeforeJudge)
        assertEquals(3, drained.size)
        assertEquals("OVERLAY", drained[0].text)
        assertEquals("ONE-SHOT-A", drained[1].text)
        assertEquals("ONE-SHOT-B", drained[2].text)
    }

    @Test
    fun `station without steeringPolicy has empty default service`() = runTest {
        val station = buildMinimalStation()

        assertNotNull(station.steeringService)
        val drained = station.drainSteeringForPhase(PumpStationPausePhase.BeforeJudge)
        assertTrue(drained.isEmpty())
    }

    @Test
    fun `persistentOverlay with MultimodalContent overload preserves the content`() = runTest {
        val customContent = MultimodalContent(text = "custom overlay").apply {
            metadata["custom-key"] = "custom-value"
        }

        val station = buildMinimalStation {
            steeringPolicy {
                persistentOverlay(PumpStationPausePhase.BeforeJudge, customContent)
            }
        }

        val drained = station.drainSteeringForPhase(PumpStationPausePhase.BeforeJudge)
        assertEquals(1, drained.size)
        assertEquals("custom overlay", drained[0].text)
        assertEquals("custom-value", drained[0].metadata["custom-key"])
        assertNotNull(drained[0].metadata["steering"], "drain must apply the steering envelope")
    }
}
