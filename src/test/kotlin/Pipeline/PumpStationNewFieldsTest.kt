package com.TTT.Pipeline

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PumpStationNewFieldsTest
{
    @Test
    fun testNewFieldsDefaultValues()
    {
        val station = PumpStation()
        assertEquals(3, station.getMaxGoalFailAttempts())
        assertNull(station.getMaxRawTurnHistorySize())
        assertEquals(0.9, station.getBlowoutThreshold(), 0.001)
        assertEquals(30_000L, station.getMemoryUpdateTimeoutMs())
        assertEquals(3, station.getMaxBlowoutRecoveries())
        assertEquals(500, station.getMaxRepairPromptTokens())
    }

    @Test
    fun testSettersWorkAndReturnChainableSelf()
    {
        val station = PumpStation()
            .setMaxGoalFailAttempts(5)
            .setMaxRawTurnHistorySize(2000)
            .setBlowoutThreshold(0.95)
            .setMemoryUpdateTimeoutMs(60_000)
            .setMaxBlowoutRecoveries(5)
            .setMaxRepairPromptTokens(1000)
        assertEquals(5, station.getMaxGoalFailAttempts())
        assertEquals(2000, station.getMaxRawTurnHistorySize())
        assertEquals(0.95, station.getBlowoutThreshold(), 0.001)
    }

    @Test
    fun testGoalFailCountDefaultsToZero()
    {
        val station = PumpStation()
        val state = station.getTaskState()
        assertEquals(0, state.goalFailCount)
    }
}
