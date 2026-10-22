package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Activation paths for the wait / join state: the "main agent" puts the
 * station into a waiting state by calling [PumpStation.waitUntil] from inside
 * a path's [com.TTT.Pipeline.PathObject.setExecutionFunction] executionFunction
 * (or, equivalently, from a DITL hook), and an external condition or an
 * emitted [PumpStationEvent] wakes it.
 *
 *  - [onConditionExternalFlagWakesPath] — a foreground path blocks on
 *    [OnCondition] until external code flips a flag.
 *  - [onEventWakesPathWhenMatchingEventEmitted] — a path registers an
 *    [OnEvent] wait, and a matching [WaitCompleted] flowing through the event
 *    funnel (from a sibling wait) releases it — no manual release call.
 */
class PumpStationWaitActivationTest
{
    @Test
    fun onConditionExternalFlagWakesPath()
    {
        val station = buildTestStation(maxHarnessTurns = 3)
        val externalReady = AtomicBoolean(false)
        val pathSawSatisfied = AtomicBoolean(false)
        station.setJudgeAgent(Pipeline().apply { add(ScriptedTestPipe("judge", """{"isComplete": true, "shouldTerminate": false}""")) })
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe("dispatch", """{"pathName":"coord","pathSchema":"{}"}""")) })

        station.addPath(
            PathObject().apply {
                pathName = "coord"
                pathDescription = "coordinator that waits for external readiness"
                setExecutionFunction { content, stationRef, _, _ ->
                    // The path "activates" the wait state: the harness sleeps
                    // here until the external condition holds.
                    val wait = stationRef.waitUntil(
                        spec = OnCondition(predicate = { externalReady.get() }, pollIntervalMs = 5),
                        name = "coord-wait",
                        timeoutMs = 5_000
                    )
                    pathSawSatisfied.set(wait.satisfied)
                    MultimodalContent(text = "coord-done:${wait.satisfied}")
                }
            }
        )

        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver { events.add(it) }

        runBlocking {
            // External wake: flip the flag shortly after the run begins.
            val runJob = launch { station.executeLocal(MultimodalContent(text = "go")) }
            delay(40)
            externalReady.set(true)
            runJob.join()
        }

        assertTrue(pathSawSatisfied.get(), "the path must have observed the wait satisfying before it returned")
        val completed = events.filterIsInstance<WaitCompleted>().single { it.waitName == "coord-wait" }
        assertTrue(completed.satisfied)
        assertEquals(PumpStationWaitReason.Resolved, completed.reason)
    }

    @Test
    fun onEventWakesPathWhenMatchingEventEmitted()
    {
        val station = buildTestStation(maxHarnessTurns = 3)
        val outerSatisfied = AtomicBoolean(false)
        val outerIsResolved = AtomicBoolean(false)
        station.setJudgeAgent(Pipeline().apply { add(ScriptedTestPipe("judge", """{"isComplete": true, "shouldTerminate": false}""")) })
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe("dispatch", """{"pathName":"coord","pathSchema":"{}"}""")) })

        station.addPath(
            PathObject().apply {
                pathName = "coord"
                pathDescription = "coordinator that waits for a specific event"
                setExecutionFunction { content, stationRef, _, _ ->
                    // Register an OnEvent wait that fires when the "inner"
                    // wait's WaitCompleted event flows through the funnel.
                    // It is registered *before* the inner wait completes, so
                    // the matching event is guaranteed to be observed.
                    val outer = stationRef.requestWait(
                        spec = OnEvent(filter = { it is WaitCompleted && it.waitName == "inner" }),
                        name = "outer",
                        timeoutMs = 5_000
                    )
                    // The inner wait times out; its WaitCompleted("inner")
                    // emission is what releases the outer OnEvent wait — no
                    // manual release call involved.
                    stationRef.waitUntil(
                        spec = OnCondition(predicate = { false }, pollIntervalMs = 5),
                        name = "inner",
                        timeoutMs = 40
                    )
                    val outerResult = outer.awaitResolution(null)
                    outerSatisfied.set(outerResult.satisfied)
                    outerIsResolved.set(outerResult.reason == PumpStationWaitReason.Resolved)
                    MultimodalContent(text = "coord-event:${outerResult.satisfied}")
                }
            }
        )

        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver { events.add(it) }
        runBlocking { station.executeLocal(MultimodalContent(text = "go")) }

        // The inner wait times out (never satisfied) ...
        val inner = events.filterIsInstance<WaitCompleted>().single { it.waitName == "inner" }
        assertFalse(inner.satisfied)
        assertEquals(PumpStationWaitReason.Timeout, inner.reason)
        // ... and its emitted event is exactly what the outer OnEvent wait
        // resolves on — captured deterministically inside the path.
        assertTrue(outerSatisfied.get(), "outer OnEvent wait must be released by the matching event")
        assertTrue(outerIsResolved.get(), "outer OnEvent wait must resolve via Resolved, not a timeout")
    }
}
