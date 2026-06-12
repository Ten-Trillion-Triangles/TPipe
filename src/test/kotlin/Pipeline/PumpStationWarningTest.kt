package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the [HarnessWarning] advisory event. The advisory fires when the developer
 * has configured NONE of the three legitimate exit mechanisms:
 *
 *  1. Always-on judge (judgeAgent wired with default [PumpStationJudgeRunMode.Always])
 *  2. FlagTriggered judge (judgeRunMode = FlagTriggered + a path that calls requestJudgeNextTurn)
 *  3. Path returns passPipeline or terminatePipeline
 *
 * Plus the [PumpStationTaskState.maxHarnessTurns] must be > 1 (intentional single-turn configs don't warn).
 *
 * The advisory is non-blocking — the harness continues. Tests here just assert presence/absence
 * of the [HarnessWarning] event in the event log.
 */
class PumpStationWarningTest
{
    /**
     * Build a minimal station that has a dispatch agent (required) and one path (required).
     * The judge is OPTIONAL — the test decides whether to wire one.
     */
    private fun stationFor(maxHarnessTurns: Int): Pair<PumpStation, MutableList<PumpStationEvent>>
    {
        val station = buildTestStation(maxHarnessTurns = maxHarnessTurns)
        val dispatchPipe = ScriptedTestPipe(response = """{"pathName": "echo", "pathSchema": "{}"}""")
        val dispatch = Pipeline().apply { add(dispatchPipe) }
        station.setDispatchAgent(dispatch)
        station.addPath(testPath("echo"))
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)
        return station to events
    }

    @Test
    fun noJudge_alwaysMode_maxTurns10_firesAdvisory()
    {
        val (station, events) = stationFor(maxHarnessTurns = 10)
        // No judge wired. Default judgeRunMode is Always.
        // No path bound to requestJudgeNextTurn.
        // maxTurns=10 > 1.

        runBlocking { station.executeLocal(MultimodalContent(text = "hi")) }

        val warnings = events.filterIsInstance<HarnessWarning>()
        assertTrue(warnings.isNotEmpty(), "HarnessWarning should fire when no exit signal is configured")
        assertEquals(WarningCode.NoExitSignalConfigured, warnings.first().code)
        // All 4 mechanisms should be listed
        assertEquals(4, warnings.first().mechanisms.size)
    }

    @Test
    fun noJudge_flagTriggeredMode_doesNotFire()
    {
        val (station, events) = stationFor(maxHarnessTurns = 10)
        station.setJudgeRunMode(PumpStationJudgeRunMode.FlagTriggered)

        runBlocking { station.executeLocal(MultimodalContent(text = "hi")) }

        val warnings = events.filterIsInstance<HarnessWarning>()
        assertFalse(warnings.any { it.code == WarningCode.NoExitSignalConfigured },
            "Advisory should NOT fire when FlagTriggered mode is set")
    }

    @Test
    fun noJudge_alwaysMode_maxTurns1_doesNotFire()
    {
        val (station, events) = stationFor(maxHarnessTurns = 1)

        runBlocking { station.executeLocal(MultimodalContent(text = "hi")) }

        val warnings = events.filterIsInstance<HarnessWarning>()
        assertFalse(warnings.any { it.code == WarningCode.NoExitSignalConfigured },
            "Advisory should NOT fire when maxTurns=1 (intentional single-turn budget)")
    }

    @Test
    fun judgeConfigured_doesNotFire()
    {
        val (station, events) = stationFor(maxHarnessTurns = 10)
        val judgePipe = ScriptedTestPipe(response = """{"isComplete": true, "shouldTerminate": false}""")
        val judge = Pipeline().apply { add(judgePipe) }
        station.setJudgeAgent(judge)

        runBlocking { station.executeLocal(MultimodalContent(text = "hi")) }

        val warnings = events.filterIsInstance<HarnessWarning>()
        assertFalse(warnings.any { it.code == WarningCode.NoExitSignalConfigured },
            "Advisory should NOT fire when a judge is configured")
    }
}
