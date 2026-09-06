package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PumpStationNextBoundaryControlTest
{
    @Test
    fun concurrentNextBoundaryProductionIsAtomicallyObservedByAReachedBoundary()
    {
        runBlocking {
            val service = PumpStationSteeringService()
            val start = CompletableDeferred<Unit>()
            val produced = 100
            val producer = async {
                start.await()
                repeat(produced) { index ->
                    service.enqueueNextBoundary(MultimodalContent(text = "steer-$index"))
                    yield()
                }
            }
            val boundaryDrain = async {
                start.await()
                buildList {
                    repeat(12) {
                        addAll(service.drainForBoundary(PumpStationPausePhase.BeforeJudge))
                        yield()
                    }
                }
            }

            start.complete(Unit)
            producer.await()
            val observed = boundaryDrain.await() +
                service.drainForBoundary(PumpStationPausePhase.BeforeJudge)

            assertEquals(produced, observed.size)
            assertEquals(
                (0 until produced).map { "steer-$it" }.toSet(),
                observed.map { it.text }.toSet()
            )
        }
    }

    @Test
    fun steeringNextBoundaryMergesWithExplicitEntriesInEnqueueOrder()
    {
        val service = PumpStationSteeringService()
        val phase = PumpStationPausePhase.BeforeJudge

        runBlocking {
            service.enqueueOneShot(phase, MultimodalContent(text = "explicit"))
            service.enqueueNextBoundary(MultimodalContent(text = "next"))
            assertEquals(
                listOf("explicit", "next"),
                service.drainForBoundary(phase).map { it.text }
            )
        }
    }

    @Test
    fun interruptNextBoundaryIsConsumedOnceAtTheReachedPhase()
    {
        val service = PumpStationInterruptService()
        val content = MultimodalContent(text = "interrupt")

        runBlocking {
            service.enqueueNextBoundary(content)
            assertEquals(listOf(content), service.drainAllForBoundary(PumpStationPausePhase.AfterDispatch))
            assertEquals(emptyList(), service.drainAllForBoundary(PumpStationPausePhase.AfterDispatch))
        }
    }

    @Test
    fun steeringNowIsInjectedAtTheReachedBoundaryExactlyOnce()
    {
        val station = PumpStation()
        station.setRunIdForTest("steering-boundary")

        runBlocking {
            station.steerNow("next boundary")
            station.injectSteeringForPhase(PumpStationPausePhase.AfterDispatch)
            station.injectSteeringForPhase(PumpStationPausePhase.AfterDispatch)
        }

        assertEquals(1, station.turnHistory.history.size)
        @Suppress("UNCHECKED_CAST")
        val envelope = station.turnHistory.history.single().content.metadata["steering"] as Map<String, Any>
        assertEquals(PumpStationPausePhase.AfterDispatch.name, envelope["phase"])
    }

    @Test
    fun interruptNowIsRewoundAtTheReachedBoundaryExactlyOnce()
    {
        val station = PumpStation()
        station.setRunIdForTest("interrupt-boundary")
        val snapshot = station.takeInterruptSnapshot()

        runBlocking {
            station.interruptNow("next boundary interrupt")
            val exception = assertFailsWith<PumpStationInterruptException> {
                station.injectInterruptForPhase(PumpStationPausePhase.BeforeExit, snapshot)
            }
            assertEquals("next boundary interrupt", exception.content.text)
            station.injectInterruptForPhase(PumpStationPausePhase.BeforeExit, snapshot)
        }
    }
}
