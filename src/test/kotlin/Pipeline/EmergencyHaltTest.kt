package com.TTT.Pipeline

import com.TTT.P2P.KillSwitch
import com.TTT.P2P.KillSwitchContext
import com.TTT.P2P.KillSwitchException
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmergencyHaltTest
{
    private class ScriptedPipe(private val response: String) : Pipe()
    {
        init { pipeName = "scripted" }
        override suspend fun generateText(promptInjector: String): String = response
        override fun truncateModuleContext(): Pipe = this
    }

    @Test
    fun testTripKillSwitchStopsLoopOnNextIteration()
    {
        runBlocking {
            val station = buildTestStation(maxHarnessTurns = 10)
            val judge = Pipeline().apply {
                add(ScriptedPipe("""{"isComplete": false, "shouldTerminate": false}"""))
            }
            val dispatch = Pipeline().apply {
                add(ScriptedPipe("""{"pathName": "p1", "pathSchema": "{}"}"""))
            }
            station.setJudgeAgent(judge).setDispatchAgent(dispatch)
            station.addPath(testPath("p1"))
            station.tripKillSwitch()
            station.executeLocal(MultimodalContent(text = "task"))
            assertEquals(PumpStationError.KillSwitchTripped, station.getTaskState().lastError)
        }
    }

    @Test
    fun testForceHaltSetsExitReason()
    {
        runBlocking {
            val station = buildTestStation()
            station.forceHalt(PumpStationExitReason.InterventionTerminated)
            assertEquals(PumpStationExitReason.InterventionTerminated, station.getTaskState().exitReason)
        }
    }

    /**
     * Regression: manual [PumpStation.tripKillSwitch] and the new auto-enforcement path
     * (token-limit trip via [KillSwitch.onTripped]) must remain independent. Manual trip
     * sets the soft-halt state without invoking the onTripped callback; auto-trip invokes
     * the callback (which throws) and the loop catches it to set the failure state.
     */
    @Test
    fun testManualAndAutoTripAreIndependent() = runBlocking {
        // ---- Auto-trip path: the callback is invoked, the loop catches the throw,
        //      and the harness state transitions to KillSwitchTripped. ----
        val captured = mutableListOf<KillSwitchContext>()
        val autoSwitch = KillSwitch(
            inputTokenLimit = 0, // any non-zero input trips
            onTripped = { ctx ->
                captured.add(ctx)
                throw KillSwitchException(ctx)
            }
        )
        val autoStation = buildTestStation(maxHarnessTurns = 3)
        val judgePipeline = Pipeline().apply {
            add(ScriptedPipe("""{"isComplete": false, "shouldTerminate": false}"""))
        }
        val dispatchPipeline = Pipeline().apply {
            add(ScriptedPipe("""{"pathName": "p1", "pathSchema": "{}"}"""))
        }
        autoStation
            .setJudgeAgent(judgePipeline)
            .setDispatchAgent(dispatchPipeline)
        autoStation.killSwitch = autoSwitch
        autoStation.addPath(testPath("p1"))
        assertFailsWith<KillSwitchException> {
            autoStation.executeLocal(MultimodalContent(text = "task"))
        }
        assertEquals(1, captured.size, "auto-trip should have invoked onTripped exactly once")
        assertEquals("input_exceeded", captured[0].reason)
        assertEquals(PumpStationError.KillSwitchTripped, autoStation.getTaskState().lastError)

        // ---- Manual trip path: the callback is NOT invoked, the soft-halt state is set. ----
        val manualCaptured = mutableListOf<KillSwitchContext>()
        val manualSwitch = KillSwitch(
            inputTokenLimit = 0,
            onTripped = { ctx ->
                manualCaptured.add(ctx)
                throw KillSwitchException(ctx)
            }
        )
        val manualStation = buildTestStation(maxHarnessTurns = 10)
        val judge2 = Pipeline().apply {
            add(ScriptedPipe("""{"isComplete": false, "shouldTerminate": false}"""))
        }
        val dispatch2 = Pipeline().apply {
            add(ScriptedPipe("""{"pathName": "p1", "pathSchema": "{}"}"""))
        }
        manualStation.setJudgeAgent(judge2).setDispatchAgent(dispatch2)
        manualStation.killSwitch = manualSwitch
        manualStation.addPath(testPath("p1"))
        // Manual trip BEFORE execute. The auto-trip path should NOT fire (because the
        // manual soft-halt short-circuits the loop on the next checkPauseGuards).
        manualStation.tripKillSwitch()
        manualStation.executeLocal(MultimodalContent(text = "task"))
        assertTrue(manualCaptured.isEmpty(), "manual trip must not invoke onTripped")
        assertEquals(PumpStationError.KillSwitchTripped, manualStation.getTaskState().lastError)
        assertEquals(
            PumpStationExitReason.KillSwitchTripped,
            manualStation.getTaskState().exitReason
        )
    }
}
