package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Service-level semantics for the PumpStation wait / join substrate. Exercises
 * [PumpStationWaitService] through the public [PumpStation] wait API:
 *  - [AllPathsSettled] blocking then release via [PumpStationWaitService.notifyPathSettled]
 *  - [AllPathsSettled] immediate resolution when nothing is in flight
 *  - [OnEvent] manual release and timeout (warn + continue)
 *  - [OnCondition] polled predicate resolution
 *  - [PumpStation.requestWait] / [PumpStation.waitUntil] activation +
 *    [PumpStation.releaseWait] / [PumpStation.cancelWait]
 *  - wait events ([WaitStarted] / [WaitCompleted])
 */
class PumpStationWaitServiceTest
{
    //=====================================AllPathsSettled=====================================

    @Test
    fun allPathsSettledResolvesImmediatelyWhenNothingInFlight()
    {
        val station = buildTestStation()
        val result = runBlocking {
            station.waitUntil(AllPathsSettled(batchName = "b"), "join-now")
        }
        assertTrue(result.satisfied)
        assertEquals(PumpStationWaitReason.Resolved, result.reason)
        assertEquals("AllPathsSettled", result.specLabel)
    }

    @Test
    fun allPathsSettledBlocksUntilLastLegSettles()
    {
        val station = buildTestStation()
        // Simulate two in-flight legs. The join must block until both settle.
        station.bumpInFlightPath(+1)
        station.bumpInFlightPath(+1)

        val handle = station.requestWait(AllPathsSettled(batchName = "batch"), "join")
        var result: PumpStationWaitResult? = null
        runBlocking {
            val job = launch {
                result = handle.awaitResolution(timeoutMs = 5_000)
            }
            // Let the awaiting coroutine register its suspension, then settle
            // the legs one at a time; the join must only release on the last.
            kotlinx.coroutines.delay(50)
            station.bumpInFlightPath(-1)
            station.waitService.notifyPathSettled(station)
            // Still one leg in flight — the join must NOT have resolved yet.
            assertEquals(null, result)
            station.bumpInFlightPath(-1)
            station.waitService.notifyPathSettled(station)
            job.join()
        }
        val settled = result!!
        assertTrue(settled.satisfied)
        assertEquals(PumpStationWaitReason.Resolved, settled.reason)
        assertEquals(0, station.inFlightPathCount())
    }

    //=====================================OnEvent / manual release=====================================

    @Test
    fun manualReleaseWakesSuspendedWait()
    {
        val station = buildTestStation()
        val handle = station.requestWait(OnEvent(filter = { false }), "ext", timeoutMs = 5_000)
        var result: PumpStationWaitResult? = null
        runBlocking {
            val job = launch {
                result = handle.awaitResolution(null)
            }
            kotlinx.coroutines.delay(50)
            station.releaseWait("ext")
            job.join()
        }
        assertTrue(result!!.satisfied)
        assertEquals(PumpStationWaitReason.Resolved, result!!.reason)
    }

    @Test
    fun cancelWaitSettlesWithCancelledReason()
    {
        val station = buildTestStation()
        val handle = station.requestWait(OnEvent(filter = { false }), "canc", timeoutMs = 5_000)
        val result = runBlocking {
            station.cancelWait("canc")
            handle.awaitResolution(null)
        }
        assertFalse(result.satisfied)
        assertEquals(PumpStationWaitReason.Cancelled, result.reason)
    }

    @Test
    fun onEventTimesOutWithoutRelease()
    {
        val station = buildTestStation()
        val result = runBlocking {
            station.waitUntil(OnEvent({ false }), "to", timeoutMs = 80)
        }
        assertFalse(result.satisfied)
        assertEquals(PumpStationWaitReason.Timeout, result.reason)
    }

    //=====================================OnCondition=====================================

    @Test
    fun onConditionResolvesOncePredicateHolds()
    {
        val station = buildTestStation()
        var calls = 0
        val result = runBlocking {
            station.waitUntil(
                OnCondition(predicate = { calls++; calls >= 3 }, pollIntervalMs = 5),
                "cond",
                timeoutMs = 5_000
            )
        }
        assertTrue(result.satisfied)
        assertEquals(PumpStationWaitReason.Resolved, result.reason)
        assertTrue(calls >= 3)
    }

    @Test
    fun onConditionTimesOutWhenPredicateNeverHolds()
    {
        val station = buildTestStation()
        val result = runBlocking {
            station.waitUntil(
                OnCondition(predicate = { false }, pollIntervalMs = 5),
                "cond2",
                timeoutMs = 80
            )
        }
        assertFalse(result.satisfied)
        assertEquals(PumpStationWaitReason.Timeout, result.reason)
    }

    //=====================================Activation via path / DITL / external code=====================================

    @Test
    fun waitUntilActivatedExternallyByReleaseWait()
    {
        // Represents a path executionFunction or DITL hook calling
        // stationRef.waitUntil(...) while external code releases it.
        val station = buildTestStation()
        runBlocking {
            val releaser = launch {
                kotlinx.coroutines.delay(60)
                station.releaseWait("subagent-join")
            }
            val result = station.waitUntil(OnEvent({ false }), "subagent-join", timeoutMs = 5_000)
            releaser.join()
            assertTrue(result.satisfied)
            assertEquals(PumpStationWaitReason.Resolved, result.reason)
        }
    }

    //=====================================Events=====================================

    @Test
    fun waitEventsAreEmitted()
    {
        val station = buildTestStation()
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver { events.add(it) }

        runBlocking {
            station.waitUntil(AllPathsSettled(), "evt", timeoutMs = 5_000)
        }

        val started = events.filterIsInstance<WaitStarted>().single()
        val completed = events.filterIsInstance<WaitCompleted>().single()
        assertEquals("evt", started.waitName)
        assertEquals("AllPathsSettled", started.specLabel)
        assertEquals("evt", completed.waitName)
        assertTrue(completed.satisfied)
        assertEquals(PumpStationWaitReason.Resolved, completed.reason)
    }

    //=====================================Spec labels / config defaults=====================================

    @Test
    fun specLabelsAreStable()
    {
        assertEquals("AllPathsSettled", AllPathsSettled("x").label())
        assertEquals("OnEvent", OnEvent({ true }).label())
        assertEquals("OnCondition", OnCondition().label())
    }

    @Test
    fun waitConfigurationDefaultsAreOff()
    {
        val cfg = PumpStationWaitConfiguration()
        assertFalse(cfg.joinAllPathsOnMultiDispatch)
        assertEquals(null, cfg.defaultTimeoutMs)
    }

    @Test
    fun defaultMultiDispatchJoinFlagIsOff()
    {
        // A minimal valid single-path station (no MultiPath dispatch) must never
        // enter the join path, so no Wait events leak into the event stream.
        val station = buildTestStation(maxHarnessTurns = 3)
        station.setJudgeAgent(Pipeline().apply { add(ScriptedTestPipe("judge", """{"isComplete": true, "shouldTerminate": false}""")) })
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe("dispatch", """{"pathName":"p1","pathSchema":"{}"}""")) })
        station.addPath(testPath("p1", returnText = "result1"))

        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver { events.add(it) }
        runBlocking {
            station.executeLocal(MultimodalContent(text = "noop"))
        }

        // No join was triggered (flag off, single-path dispatch).
        assertTrue(events.none { it is WaitCompleted })
        assertTrue(events.none { it is WaitStarted })
        // And the run completed through the judge.
        assertEquals(PumpStationExitReason.JudgeComplete, station.getTaskState().exitReason)
    }
}
