package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PumpStationInterruptIntegrationTest
{
    @Test
    fun `interrupt enqueued before executeLocal is observed by the harness on first BeforeJudge`() = runTest {
        val station = pumpStation("int-pre-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") {
                setExecutionFunction { content, _, _, _ -> content.also { it.passPipeline = true } }
            }
            interruptPolicy {
                initialQueue[PumpStationPausePhase.BeforeJudge] = listOf(
                    MultimodalContent(text = "preloaded-from-dsl")
                )
            }
        }

        station.executeLocal(MultimodalContent(text = "go"))

        // The preloaded interrupt should have fired on the first BeforeJudge
        // and landed in turnHistory with the canonical envelope.
        val interruptEntry = station.turnHistory.history.firstOrNull {
            it.content.metadata.containsKey("interrupt")
        }
        assertTrue(interruptEntry != null, "preloaded interrupt must fire and land in turnHistory with envelope")
    }

    @Test
    fun `first call to drainForPhase returns the entry, rest are overflow`() = runTest {
        val station = pumpStation("int-overflow-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") {
                setExecutionFunction { content, _, _, _ -> content }
            }
        }

        // Three interrupts for the same phase.
        station.interrupt(PumpStationPausePhase.BeforeJudge, "first-interrupt")
        station.interrupt(PumpStationPausePhase.BeforeJudge, "overflow-as-steer")
        station.interrupt(PumpStationPausePhase.BeforeJudge, "overflow-dropped-no-steering-configured")

        val first = station.interruptService.drainForPhase(PumpStationPausePhase.BeforeJudge)
        assertEquals("first-interrupt", first?.text)
        val overflow = station.interruptService.drainAllForPhase(PumpStationPausePhase.BeforeJudge)
        assertEquals(listOf("overflow-as-steer", "overflow-dropped-no-steering-configured"), overflow.map { it.text })
    }
}
