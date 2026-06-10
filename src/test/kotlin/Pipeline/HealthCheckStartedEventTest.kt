package com.TTT.Pipeline

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthCheckStartedEventTest
{
    @Test
    fun testHealthCheckStartedEvent()
    {
        val event = HealthCheckStarted(
            runId = "ps-test",
            turnIndex = 3,
            phase = PumpStationPhase.HealthCheck
        )
        assertTrue(event is PumpStationEvent)
        assertEquals("ps-test", event.runId)
        assertEquals(3, event.turnIndex)
        assertEquals(PumpStationPhase.HealthCheck, event.phase)
    }
}
