package com.TTT.Pipeline

import com.TTT.Debug.PipeTracer
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Combined regression tests for Defects 20, 22, 24, 25: trace-event metadata
 * completeness in the PumpStation funnel.
 *
 * Each test pins one missing metadata field on a known event shape by routing
 * a constructed event through [tracePumpStationEvent] and reading it back out
 * of the global [PipeTracer].
 */
class PumpStationEventMetadataTest
{
    @Test
    fun harnessStartedMetadataCarriesOriginalInputPreview() = runBlocking {
        val station = PumpStation().enableTracing()
        station.taskState.runId = "harness-preview-test"
        station.setEventObserver { /* funnel still fires */ }
        val original = MultimodalContent(text = "long-running original input that operators care about")
        val event = HarnessStarted(
            runId = "harness-preview-test",
            turnIndex = 0,
            phase = PumpStationPhase.PreInit,
            originalInput = original
        )
        station.tracePumpStationEvent(event)
        val traceEvents = PipeTracer.getAllTraces()
        val list = traceEvents["harness-preview-test"] ?: emptyList()
        val harnessTrace = list.firstOrNull { it.eventType.name == "PUMP_STATION_STARTED" }
        assertNotNull(harnessTrace, "Defect 20: HarnessStarted must reach the trace layer.")
        val preview = harnessTrace.metadata["originalInputPreview"] as? String
        assertNotNull(preview, "Defect 20: HarnessStarted metadata must include originalInputPreview.")
        assertEquals(original.text, preview)
    }

    @Test
    fun harnessStartedMetadataClipsLongOriginalInputPreview() = runBlocking {
        val station = PumpStation().enableTracing()
        station.taskState.runId = "harness-clip-test"
        station.setEventObserver { /* funnel still fires */ }
        val longInput = "x".repeat(9000)
        val event = HarnessStarted(
            runId = "harness-clip-test",
            turnIndex = 0,
            phase = PumpStationPhase.PreInit,
            originalInput = MultimodalContent(text = longInput)
        )
        station.tracePumpStationEvent(event)
        val list = PipeTracer.getAllTraces()["harness-clip-test"] ?: emptyList()
        val harnessTrace = list.first { it.eventType.name == "PUMP_STATION_STARTED" }
        val preview = harnessTrace.metadata["originalInputPreview"] as? String
        assertNotNull(preview)
        assertTrue(
            preview.length <= 8 * 1024 + 3,
            "Defect 20: clipped preview must not exceed CONTENT_PREVIEW_MAX + ellipsis."
        )
        assertTrue(preview.endsWith("..."), "Defect 20: clipped preview should end with the ellipsis marker.")
    }

    @Test
    fun judgeStartedMetadataCarriesJudgeRunMode() = runBlocking {
        val station = PumpStation().setJudgeRunMode(PumpStationJudgeRunMode.Always).enableTracing()
        station.taskState.runId = "judge-mode-test"
        station.setEventObserver { /* funnel still fires */ }
        station.tracePumpStationEvent(JudgeStarted(runId = "judge-mode-test", turnIndex = 7))
        val list = PipeTracer.getAllTraces()["judge-mode-test"] ?: emptyList()
        val judgeTrace = list.first { it.eventType.name == "PUMP_STATION_JUDGE_STARTED" }
        assertEquals("Always", judgeTrace.metadata["judgeRunMode"])
        assertEquals(7, judgeTrace.metadata["turnIndex"])
    }

    @Test
    fun dispatchCompletedMetadataSerializesPathRequestAsJson() = runBlocking {
        val station = PumpStation().enableTracing()
        station.taskState.runId = "dispatch-json-test"
        station.setEventObserver { /* funnel still fires */ }
        val request = PathRequest(pathName = "research", pathSchema = "{}", pathSelectionRationale = "because")
        val event = DispatchCompleted(
            runId = "dispatch-json-test",
            turnIndex = 3,
            selectedPathName = "research",
            pathRequest = request,
            result = MultimodalContent(text = "ok")
        )
        station.tracePumpStationEvent(event)
        val list = PipeTracer.getAllTraces()["dispatch-json-test"] ?: emptyList()
        val dispatchTrace = list.first { it.eventType.name == "PUMP_STATION_DISPATCH_COMPLETED" }
        val json = dispatchTrace.metadata["pathRequest"] as? String
        assertNotNull(json, "Defect 24: DispatchCompleted metadata must include pathRequest.")
        assertTrue(
            json.contains("research") && json.contains("because") && json.contains("pathSelectionRationale"),
            "Defect 24: pathRequest metadata must be JSON-encoded with all fields. Got: $json"
        )
    }

    @Test
    fun pathSafetyCompletedMetadataEmitsApprovedAsInt() = runBlocking {
        val station = PumpStation().enableTracing()
        station.taskState.runId = "path-safety-int-test"
        station.setEventObserver { /* funnel still fires */ }
        val approved = PathSafetyCompleted(
            runId = "path-safety-int-test",
            turnIndex = 1,
            pathName = "p1",
            riskLevel = PathRiskLevel.High,
            approved = true,
            reason = null
        )
        val rejected = PathSafetyCompleted(
            runId = "path-safety-int-test",
            turnIndex = 2,
            pathName = "p1",
            riskLevel = PathRiskLevel.High,
            approved = false,
            reason = "denied"
        )
        station.tracePumpStationEvent(approved)
        station.tracePumpStationEvent(rejected)
        val list = PipeTracer.getAllTraces()["path-safety-int-test"] ?: emptyList()
        val safetyTraces = list.filter { it.eventType.name == "PUMP_STATION_PATH_SAFETY_COMPLETED" }
        assertEquals(2, safetyTraces.size, "Both safety events must reach the trace layer.")
        val approvedTrace = safetyTraces.first { (it.metadata["approved"] as? Boolean) == true }
        val rejectedTrace = safetyTraces.first { (it.metadata["approved"] as? Boolean) == false }
        assertEquals(1, approvedTrace.metadata["approvedAsInt"])
        assertEquals(0, rejectedTrace.metadata["approvedAsInt"])
    }
}