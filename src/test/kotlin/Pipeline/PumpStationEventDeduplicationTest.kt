package com.TTT.Pipeline

import com.TTT.Debug.PipeTracer
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PumpStationEventDeduplicationTest
{
    @Test
    fun normalLifecyclePublishesStartAndCompletionThroughTheSameFunnel()
    {
        val station = PumpStation()
        val events = mutableListOf<PumpStationEvent>()
        station.setRunIdForTest("normal-lifecycle")
        station.setEventObserver(events::add)

        runBlocking {
            station.runPreInitPhase(MultimodalContent(text = "input"))
            station.runFinalizationPhase()
        }

        assertEquals(1, events.filterIsInstance<HarnessStarted>().size)
        assertEquals(1, events.filterIsInstance<HarnessCompleted>().size)
        assertTrue(events.indexOfFirst { it is HarnessStarted } < events.indexOfFirst { it is HarnessCompleted })
    }

    @Test
    fun forceHaltPublishesEachEventExactlyOnceToLegacyObserver()
    {
        val station = PumpStation()
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)

        runBlocking {
            station.forceHalt(PumpStationExitReason.Error)
        }

        assertEquals(1, events.filterIsInstance<HarnessFailed>().size)
    }

    @Test
    fun forceHaltMirrorsThePublicationExactlyOnceToTracing()
    {
        val runId = "force-halt-trace"
        val station = PumpStation().enableTracing()
        station.setRunIdForTest(runId)
        PipeTracer.clearTrace(runId)

        runBlocking {
            station.forceHalt(PumpStationExitReason.Error)
        }

        assertEquals(1, PipeTracer.getTrace(runId).size)
    }

    @Test
    fun additiveObserverCanBeRemovedWithoutReplacingLegacyObserver()
    {
        val station = PumpStation()
        val legacy = mutableListOf<PumpStationEvent>()
        val additive = mutableListOf<PumpStationEvent>()
        station.setEventObserver(legacy::add)
        val subscription = station.addEventObserver(additive::add)

        runBlocking {
            station.forceHalt(PumpStationExitReason.Error)
        }
        subscription.close()
        runBlocking {
            station.forceHalt(PumpStationExitReason.Error)
        }

        assertEquals(2, legacy.filterIsInstance<HarnessFailed>().size)
        assertEquals(1, additive.filterIsInstance<HarnessFailed>().size)
        assertTrue(additive.isNotEmpty())
    }
}
