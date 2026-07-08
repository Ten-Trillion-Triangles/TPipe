package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the path-safety -> dispatch feedback channel. When path-safety rejects
 * a path, the rejection verdict (pathName + reason) must surface in the next
 * dispatch LLM's user prompt via turnHistory.
 *
 * Pins the F3 gap from the 2026-07-08 PumpStation triage: previously the dispatch
 * LLM had no signal that the previous path was rejected for safety, so it would
 * re-pick the same path and get rejected again until maxTurns.
 */
class PathSafetyDispatchFeedbackTest
{
    @Test
    fun rejectedPathNameAppearsInTurnHistoryAfterInvocation() = runBlocking<Unit>
    {
        // Two-path station: low-risk "gather" (no safety check), high-risk "report"
        // (safety function rejects it).
        val judgePipeline = pipelineReturning("""{"isComplete": false, "shouldTerminate": false}""")
        val dispatchPipeline = pipelineReturning("""{"pathName": "report", "inputData": {}}""")
        val station = buildTestStation(maxHarnessTurns = 3)
            .setJudgeAgent(judgePipeline)
            .setDispatchAgent(dispatchPipeline)

        // Wire safety function to reject 'report'.
        // pathSafetyFunction doesn't carry a reason — so the hint will use the
        // fallback "Rejected by path safety check" wording.
        station.setPathSafetyFunction { targetPath, _, _ ->
            targetPath.pathName != "report"  // approve gather, reject report
        }

        station.addPath(testPath("gather"))
        station.addPath(testPath("report").apply {
            // Set High risk so the safety check fires (gates on risk > Low).
            riskLevel = PathRiskLevel.High
        })

        // Run one full turn. dispatch picks 'report' (scripted), safety rejects,
        // invokePath returns input unchanged, turn continues.
        station.executeLocal(MultimodalContent(text = "test input"))

        // Assert: turnHistory now contains a [Path Safety] hint mentioning 'report'.
        val historyTexts = station.turnHistory.history.mapNotNull { it.content.text }
        val hint = historyTexts.firstOrNull { it.contains("[Path Safety]") }
        assertTrue(hint != null, "expected [Path Safety] hint in turnHistory; got: $historyTexts")
        assertTrue(hint!!.contains("report"),
            "expected hint to mention the rejected pathName 'report'; got: '$hint'")
        assertTrue(hint.contains("rejected"),
            "expected hint to say 'rejected'; got: '$hint'")
    }

    @Test
    fun approvedPathDoesNotAppendHint() = runBlocking<Unit>
    {
        // Two-path station: safety function approves everything.
        val judgePipeline = pipelineReturning("""{"isComplete": true, "shouldTerminate": false}""")
        val dispatchPipeline = pipelineReturning("""{"pathName": "report", "inputData": {}}""")
        val station = buildTestStation(maxHarnessTurns = 3)
            .setJudgeAgent(judgePipeline)
            .setDispatchAgent(dispatchPipeline)

        station.setPathSafetyFunction { _, _, _ -> true }  // approve all

        station.addPath(testPath("report").apply { riskLevel = PathRiskLevel.High })

        station.executeLocal(MultimodalContent(text = "test input"))

        // Assert: no [Path Safety] hint was appended (safety approved, nothing rejected).
        val historyTexts = station.turnHistory.history.mapNotNull { it.content.text }
        assertFalse(
            historyTexts.any { it.contains("[Path Safety]") },
            "did not expect a [Path Safety] hint when safety approved; got: $historyTexts"
        )
    }

    @Test
    fun rejectionReasonFromJsonContractSurfacesInHint() = runBlocking<Unit>
    {
        // Path-safety AGENT (LLM-style) returns {"safe": false, "reason": "..."}.
        // The hint must include that reason string.
        val judgePipeline = pipelineReturning("""{"isComplete": false, "shouldTerminate": false}""")
        val dispatchPipeline = pipelineReturning("""{"pathName": "report", "inputData": {}}""")
        val station = buildTestStation(maxHarnessTurns = 3)
            .setJudgeAgent(judgePipeline)
            .setDispatchAgent(dispatchPipeline)

        // Wire a pipeline that returns the JSON verdict for every call.
        val safetyAgent = pipelineReturning(
            """{"safe": false, "reason": "missing required field 'topic'"}"""
        )
        station.setPathSafetyAgent(safetyAgent)
        // PathSafety JSON contract is enabled by default.

        station.addPath(testPath("report").apply { riskLevel = PathRiskLevel.High })

        station.executeLocal(MultimodalContent(text = "test input"))

        // Assert: hint contains the actual reason string.
        val historyTexts = station.turnHistory.history.mapNotNull { it.content.text }
        val hint = historyTexts.firstOrNull { it.contains("[Path Safety]") }
        assertTrue(hint != null, "expected [Path Safety] hint in turnHistory; got: $historyTexts")
        assertTrue(hint!!.contains("missing required field 'topic'"),
            "expected hint to include the safety agent's reason; got: '$hint'")
    }

    // ---- helpers ----

    /**
     * Build a one-pipe Pipeline that returns [response] from generateText() for every
     * call. Mirrors the ScriptedPipe pattern at KillSwitchPumpStationTest.kt:38-43 but
     * lives here to keep the test self-contained (ScriptedPipe is private there).
     */
    private fun pipelineReturning(response: String): Pipeline
    {
        val pipe = object : Pipe()
        {
            init { pipeName = "scripted" }
            override suspend fun generateText(promptInjector: String): String = response
            override fun truncateModuleContext(): Pipe = this
        }
        return Pipeline().apply { add(pipe) }
    }
}