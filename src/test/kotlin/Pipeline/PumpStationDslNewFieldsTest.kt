package com.TTT.Pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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

    /**
     * Verifies that the DSL surfaces the new judgeRunMode field, applies it through build(),
     * and the resulting station reports the active mode via getJudgeRunMode().
     */
    @Test
    fun testDslExposesJudgeRunMode()
    {
        runBlocking {
            val station = pumpStation("flag-judge-dsl") {
                dispatchAgent = Pipeline()
                judgeRunMode = PumpStationJudgeRunMode.FlagTriggered
                path("alpha") {
                    description = "alpha path"
                    risk = PathRiskLevel.Low
                    setInternalAgent(SgTestAgent(agentTag = "alpha-agent"))
                }
            }
            assertEquals(PumpStationJudgeRunMode.FlagTriggered, station.getJudgeRunMode())
            // Setter still works on the built station
            val returned = station.setJudgeRunMode(PumpStationJudgeRunMode.Always)
            assertSame(station, returned, "setJudgeRunMode must return this for chaining")
            assertEquals(PumpStationJudgeRunMode.Always, station.getJudgeRunMode())
        }
    }

    /**
     * Verifies that when judgeRunMode is left unset the DSL defaults to Always.
     */
    @Test
    fun testDslJudgeRunModeDefaultsToAlways()
    {
        runBlocking {
            val station = pumpStation("flag-judge-default") {
                dispatchAgent = Pipeline()
                path("alpha") {
                    description = "alpha path"
                    risk = PathRiskLevel.Low
                    setInternalAgent(SgTestAgent(agentTag = "alpha-agent"))
                }
            }
            assertEquals(PumpStationJudgeRunMode.Always, station.getJudgeRunMode())
        }
    }
}
