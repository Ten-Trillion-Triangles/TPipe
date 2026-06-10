package com.TTT.Pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class PumpStationDslNewFieldsTest
{
    @Test
    fun testDslExposesNewFields()
    {
        runBlocking {
            val station = pumpStation("test") {
                dispatchAgent = Pipeline()
                maxGoalFailAttempts = 7
                maxRawTurnHistorySize = 1500
                blowoutThreshold = 0.85
                memoryUpdateTimeoutMs = 45_000
                maxBlowoutRecoveries = 4
                maxRepairPromptTokens = 800
                path("alpha") {
                    description = "alpha path"
                    risk = PathRiskLevel.Medium
                    dispatchHint = "alpha hint"
                    setInternalAgent(SgTestAgent(agentTag = "alpha-agent"))
                }
            }
            station.P2PInit()
            assertEquals(7, station.getMaxGoalFailAttempts())
            assertEquals(1500, station.getMaxRawTurnHistorySize())
            assertEquals(0.85, station.getBlowoutThreshold(), 0.001)
            assertEquals(45_000L, station.getMemoryUpdateTimeoutMs())
        }
    }
}
