package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PumpStationInterruptApiTest
{
    private fun buildStation(): PumpStation
    {
        return pumpStation("api-test-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") {
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
    }

    @Test
    fun `interrupt helper enqueues content to the service`() = runTest {
        val station = buildStation()
        val text = "external interrupt: switch paths"
        station.interrupt(PumpStationPausePhase.BeforeJudge, MultimodalContent(text = text))
        assertEquals(1, station.interruptService.queueDepth(PumpStationPausePhase.BeforeJudge))
    }

    @Test
    fun `interrupt text overload enqueues a MultimodalContent with the given text`() = runTest {
        val station = buildStation()
        station.interrupt(PumpStationPausePhase.AfterDispatch, "stop and switch")
        assertEquals(1, station.interruptService.queueDepth(PumpStationPausePhase.AfterDispatch))
    }

    @Test
    fun `PumpStation exposes interruptService property`() {
        val station = buildStation()
        assertNotNull(station.interruptService)
    }
}
