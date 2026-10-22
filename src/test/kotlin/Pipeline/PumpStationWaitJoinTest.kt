package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runtime behavior of the first-class join-all-paths mode. A MultiPath
 * dispatch fans out a batch of async paths; when
 * [PumpStation.setWaitAllPathsOnMultiDispatch] is enabled the harness blocks
 * the dispatch turn until every leg settles ([AllPathsSettled]), making
 * "wait for every path I called to return" a first-class mode. When disabled
 * (the default) the existing fire-and-collect-next-turn behavior is preserved.
 */
class PumpStationWaitJoinTest
{
    private fun multiDispatchScript(): String =
        """{"paths":[{"pathName":"a","inputData":{}},{"pathName":"b","inputData":{}}],"batchRationale":"both"}"""

    private fun buildJoinStation(join: Boolean, maxTurns: Int = 4): Pair<PumpStation, CountDownLatch>
    {
        val bothSettled = CountDownLatch(2)
        val station = buildTestStation(maxHarnessTurns = maxTurns)
        station.setJudgeAgent(Pipeline().apply { add(ScriptedTestPipe("judge", """{"isComplete": true, "shouldTerminate": false}""")) })
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe("dispatch", multiDispatchScript())) })
        station.setPathExecutionShape(PathExecutionShape.MultiPath)
        station.setWaitAllPathsOnMultiDispatch(join)
        station.addPath(
            PathObject().apply {
                pathName = "a"
                pathDescription = "leg a"
                setExecutionFunction { c, s, h, sum ->
                    delay(120)
                    bothSettled.countDown()
                    MultimodalContent(text = "resultA")
                }
            }
        )
        station.addPath(
            PathObject().apply {
                pathName = "b"
                pathDescription = "leg b"
                setExecutionFunction { c, s, h, sum ->
                    delay(120)
                    bothSettled.countDown()
                    MultimodalContent(text = "resultB")
                }
            }
        )
        return station to bothSettled
    }

    @Test
    fun joinBlocksUntilBothLegsSettle()
    {
        val (station, latch) = buildJoinStation(join = true)
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver { events.add(it) }

        runBlocking {
            station.executeLocal(MultimodalContent(text = "fan out"))
        }

        // The join must have released only after BOTH legs counted down.
        assertTrue(latch.count == 0L, "both legs must have settled")
        // The join emitted a WaitCompleted that was satisfied (not a timeout).
        val completed = events.filterIsInstance<WaitCompleted>().single()
        assertTrue(completed.satisfied)
        assertEquals(PumpStationWaitReason.Resolved, completed.reason)
        // A meaningful wait elapsed — the dispatch turn actually blocked for the
        // legs' duration rather than resolving instantly.
        assertTrue(completed.elapsedMs > 30L, "join should have waited for the legs: ${completed.elapsedMs}ms")
        assertEquals(0, station.inFlightPathCount())
    }

    @Test
    fun defaultOffPreservesFireAndCollect()
    {
        val (station, latch) = buildJoinStation(join = false, maxTurns = 6)
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver { events.add(it) }

        runBlocking {
            station.executeLocal(MultimodalContent(text = "fan out"))
        }

        // No join: no wait events emitted at all.
        assertTrue(events.none { it is WaitCompleted }, "default (fire-and-collect) must not join: ${events.filterIsInstance<WaitCompleted>()}")
        assertTrue(events.none { it is WaitStarted })
        // The run still completes normally (judge sees complete on a later turn).
        assertEquals(PumpStationExitReason.JudgeComplete, station.getTaskState().exitReason)
    }

    @Test
    fun joinTimeoutDegradesToWarnAndContinue()
    {
        // Two legs: one fast, one slow. A short default timeout means the join
        // degrades to warn + continue when the slow leg has not settled.
        val station = buildTestStation(maxHarnessTurns = 6)
        station.setJudgeAgent(Pipeline().apply { add(ScriptedTestPipe("judge", """{"isComplete": true, "shouldTerminate": false}""")) })
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe("dispatch", multiDispatchScript())) })
        station.setPathExecutionShape(PathExecutionShape.MultiPath)
        station.setWaitAllPathsOnMultiDispatch(true)
        station.setWaitDefaultTimeoutMs(60L) // shorter than the slow leg below
        station.addPath(
            PathObject().apply {
                pathName = "a"
                pathDescription = "fast leg"
                setExecutionFunction { _, _, _, _ -> MultimodalContent(text = "resultA") }
            }
        )
        station.addPath(
            PathObject().apply {
                pathName = "b"
                pathDescription = "slow leg"
                setExecutionFunction { _, _, _, _ ->
                    delay(5_000)
                    MultimodalContent(text = "resultB")
                }
            }
        )
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver { events.add(it) }

        val started = runBlocking {
            station.executeLocal(MultimodalContent(text = "fan out"))
            events.first { it is WaitCompleted } as WaitCompleted
        }

        // The slow leg outlives the join timeout; the join degrades to a
        // timeout (warn + continue) — the loop does NOT halt.
        assertEquals(PumpStationWaitReason.Timeout, started.reason)
        assertTrue(!started.satisfied)
        // The harness advanced (did not halt on the timeout).
        assertEquals(PumpStationExitReason.JudgeComplete, station.getTaskState().exitReason)
    }

    //=====================================WaitStarted / trace metadata=====================================

    @Test
    fun waitStartedEventIsEmittedWithSpecLabel()
    {
        val (station, _) = buildJoinStation(join = true)
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver { events.add(it) }
        runBlocking { station.executeLocal(MultimodalContent(text = "fan out")) }

        val started = events.filterIsInstance<WaitStarted>().single()
        assertEquals("AllPathsSettled", started.specLabel)
        assertTrue(started.waitName.startsWith("multi-join-"))
    }
}
