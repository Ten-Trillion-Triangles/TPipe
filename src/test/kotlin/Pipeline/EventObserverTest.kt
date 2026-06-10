package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class EventObserverTest
{
    @Test
    fun testSetEventObserverReceivesEvents()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)
        runBlocking {
            station.executeLocal(MultimodalContent(text = "test"))
        }
        // HarnessStarted is emitted at minimum (when runHarnessLoop is wired in Group M)
        // For now, this test will fail because executeLocal doesn't emit anything yet.
        // Mark it as the expected post-Group-M test.
        assertTrue(events.isNotEmpty(), "Event observer should have received at least one event")
    }
}
