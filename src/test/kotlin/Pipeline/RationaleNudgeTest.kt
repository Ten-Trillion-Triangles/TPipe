package com.TTT.Pipeline

import com.TTT.Config.TPipeConfig
import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Pipe.MultimodalContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RationaleNudgeTest
{
    @Test
    fun emptyRationaleTriggersNudgeWhenPolicyOn()
    {
        val station = buildScratchStationWithTracing("emptyRationaleTriggersNudgeWhenPolicyOn")
        station.setRequirePathSelectionRationale(true)

        val request = PathRequest(
            pathName = "research",
            pathSchema = "{}",
            pathSelectionRationale = null
        )

        val before = station.turnHistory.history.size
        station.applyRationaleNudgeIfNeeded(request, request.pathSelectionRationale)
        val after = station.turnHistory.history.size

        assertTrue(after > before,
            "Nudge MUST fire when policy is ON and rationale is null/blank (history grew).")
        val lastText = station.turnHistory.history.last().content.text
        assertTrue(lastText.contains("pathSelectionRationale"),
            "Hint text MUST name the field so the LLM knows what to fix.")
        assertTrue(station.taskState.runId.isNotBlank(),
            "Trace capture standard: station must retain its runId.")
    }

    @Test
    fun noNudgeWhenPolicyIsOff()
    {
        val station = buildScratchStationWithTracing("noNudgeWhenPolicyIsOff")
        station.setRequirePathSelectionRationale(false)

        val request = PathRequest(pathName = "research", pathSchema = "{}")
        val before = station.turnHistory.history.size
        station.applyRationaleNudgeIfNeeded(request, request.pathSelectionRationale)
        val after = station.turnHistory.history.size

        assertEquals(before, after,
            "Nudge MUST be silent when policy is OFF (history unchanged).")
        assertTrue(station.taskState.runId.isNotBlank(),
            "Trace capture standard: station must retain its runId.")
    }

    @Test
    fun noNudgeWhenRationaleIsPopulated()
    {
        val station = buildScratchStationWithTracing("noNudgeWhenRationaleIsPopulated")
        station.setRequirePathSelectionRationale(true)

        val request = PathRequest(
            pathName = "research",
            pathSchema = "{}",
            pathSelectionRationale = "Picked research because the user asked for history of X."
        )
        val before = station.turnHistory.history.size
        station.applyRationaleNudgeIfNeeded(request, request.pathSelectionRationale)
        val after = station.turnHistory.history.size

        assertEquals(before, after,
            "Nudge MUST be silent when rationale is already populated (history unchanged).")
        assertTrue(station.taskState.runId.isNotBlank(),
            "Trace capture standard: station must retain its runId.")
    }

    private fun buildScratchStationWithTracing(testName: String): PumpStation
    {
        val station = PumpStation()
        val traceDir = TPipeConfig.getTraceDir()
        val runId = "test-RationaleNudgeTest-${testName}-${System.currentTimeMillis()}"
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
