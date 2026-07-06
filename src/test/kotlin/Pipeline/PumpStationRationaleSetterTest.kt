package com.TTT.Pipeline

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PumpStationRationaleSetterTest
{
    @Test
    fun setRequirePathSelectionRationaleRoundTripsThroughPumpStation()
    {
        val station = buildScratchStationWithTracing("setRequirePathSelectionRationaleRoundTripsThroughPumpStation")
        // The mirror setter on PumpStation must propagate to both the public `failurePolicy`
        // instance and the private `requirePathSelectionRationale` mirror (verified via the
        // failurePolicy-merge copy at PumpStation.kt setFailurePolicy).
        val policy = PumpStationFailurePolicy()
        policy.requirePathSelectionRationale = false
        station.setFailurePolicy(policy)
        assertEquals(false, station.failurePolicy.requirePathSelectionRationale)
        assertTrue(
            station.taskState.runId.isNotBlank(),
            "Trace capture standard: station must have a runId."
        )
    }

    @Test
    fun defaultFailurePolicyOnStationMatchesOperatorDefault()
    {
        val station = buildScratchStationWithTracing("defaultFailurePolicyOnStationMatchesOperatorDefault")
        val p = station.failurePolicy
        assertTrue(
            p.requirePathSelectionRationale,
            "Station default MUST default to true so old callers get the new behavior."
        )
        assertTrue(
            station.taskState.runId.isNotBlank(),
            "Trace capture standard: station must have a runId."
        )
    }

    // Helper — inline for this task. Subsequent tasks (3.1+5.1) will refactor into
    // RationaleTestFixtures.kt.
    private fun buildScratchStationWithTracing(testName: String): PumpStation
    {
        val station = PumpStation()
        val traceDir = TPipeConfig.getTraceDir()
        val runId = "test-PumpStationRationaleSetterTest-${testName}-${System.currentTimeMillis()}"
        station.taskState.runId = runId
        station.enableTracing(
            TraceConfig(
                enabled = true,
                autoExport = true,
                exportPath = traceDir + "/" + runId,
                outputFormat = TraceFormat.HTML,
                detailLevel = TraceDetailLevel.DEBUG
            )
        )
        return station
    }
}