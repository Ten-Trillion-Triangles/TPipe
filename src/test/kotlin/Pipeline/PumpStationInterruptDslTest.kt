package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PumpStationInterruptDslTest
{
    @Test
    fun `interruptPolicy initialQueue seeds entries at construction`() = runTest {
        val station = pumpStation("dsl-test-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") {
                setExecutionFunction { content, _, _, _ -> content }
            }
            interruptPolicy {
                initialQueue[PumpStationPausePhase.BeforeJudge] = listOf(MultimodalContent(text = "preloaded"))
            }
        }
        assertEquals(1, station.interruptService.queueDepth(PumpStationPausePhase.BeforeJudge))
    }
}
