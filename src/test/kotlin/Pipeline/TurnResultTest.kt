package com.TTT.Pipeline

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnResultTest
{
    @Test
    fun testContinueResult()
    {
        val result: TurnResult = TurnResult.Continue
        assertTrue(result is TurnResult.Continue)
    }

    @Test
    fun testHaltResultCarriesReason()
    {
        val result = TurnResult.Halt(PumpStationExitReason.MaxTurnsHit)
        assertEquals(PumpStationExitReason.MaxTurnsHit, result.reason)
    }
}
