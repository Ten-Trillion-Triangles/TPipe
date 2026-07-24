package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PumpStationInterruptServiceTest
{
    private fun content(text: String): MultimodalContent = MultimodalContent(text = text)

    @Test
    fun `drainForPhase returns the first queued entry and drains the rest into overflow`() = runTest {
        val service = PumpStationInterruptService()
        val phase = PumpStationPausePhase.BeforeJudge

        service.enqueue(phase, content("first"))
        service.enqueue(phase, content("second"))
        service.enqueue(phase, content("third"))

        val first = service.drainForPhase(phase)
        assertEquals("first", first?.text)
        val overflow = service.drainAllForPhase(phase)
        assertEquals(listOf("second", "third"), overflow.map { it.text })
    }

    @Test
    fun `drainForPhase returns only the first entry, leaving overflow in the queue`() = runTest {
        val service = PumpStationInterruptService()
        val phase = PumpStationPausePhase.AfterDispatch

        service.enqueue(phase, content("interrupt-A"))
        service.enqueue(phase, content("overflow-B"))
        service.enqueue(phase, content("overflow-C"))

        val first = service.drainForPhase(phase)
        assertEquals("interrupt-A", first?.text)
        assertEquals(2, service.queueDepth(phase), "overflow should remain in queue")
    }

    @Test
    fun `queueDepth reports pending count for phase`() = runTest {
        val service = PumpStationInterruptService()
        service.enqueue(PumpStationPausePhase.BeforeJudge, content("x"))
        service.enqueue(PumpStationPausePhase.BeforeJudge, content("y"))
        assertEquals(2, service.queueDepth(PumpStationPausePhase.BeforeJudge))
        assertEquals(0, service.queueDepth(PumpStationPausePhase.AfterDispatch))
    }
}
