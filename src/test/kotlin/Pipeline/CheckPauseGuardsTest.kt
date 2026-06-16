package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckPauseGuardsTest
{
    @Test
    fun testReturnsTrueWhenNotPaused()
    {
        val station = PumpStation()
        runBlocking {
            val canContinue = station.checkPauseGuards(PumpStationPausePhase.BeforeJudge)
            assertTrue(canContinue)
        }
    }

    @Test
    fun testReturnsFalseWhenExitReasonSet()
    {
        val station = PumpStation()
        station.taskState.exitReason = PumpStationExitReason.MaxTurnsHit
        runBlocking {
            assertFalse(station.checkPauseGuards(PumpStationPausePhase.BeforeJudge))
        }
    }

    @Test
    fun testWithDitlWrapRunsPreHook() = runBlocking {
        var preRan = false
        val result = withDitlWrap<String>(
            preHook = { preRan = true; "pre-input" },
            operation = { "result" },
            postHook = null
        )
        assertTrue(preRan)
        assertEquals("result", result)
    }
}
