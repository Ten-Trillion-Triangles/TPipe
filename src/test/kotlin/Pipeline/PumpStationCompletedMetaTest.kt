// PumpStation B1: HarnessCompleted funnel drops exitReason + finalOutput.
// RED test asserts the converted TraceEvent for PUMP_STATION_COMPLETED
// carries both keys in its metadata, matching the HarnessFailed pattern
// at PumpStationHelpers.kt:118-124.

package com.TTT.Pipeline

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TraceEventType
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PumpStationCompletedMetaTest
{
    private lateinit var runId: String

    @BeforeEach
    fun setUp()
    {
        runId = "test-CompletedMeta-${System.nanoTime()}"
    }

    @AfterEach
    fun tearDown()
    {
        // best-effort cleanup; the runId is unique so no contention
    }

    @Test
    fun harnessCompletedCarriesExitReasonAndFinalOutput()
    {
        val station = buildTestStation(maxHarnessTurns = 5)
        // Wire a judge that returns isComplete=true so the harness exits
        // via the JudgeComplete path and emits HarnessCompleted.
        val judgePipe = ScriptedTestPipe(
            name = "judge",
            response = """{"isComplete": true, "shouldTerminate": false, "reason": "task done"}"""
        )
        val judge = Pipeline().apply { add(judgePipe) }
        // Wire a dispatch that picks the only registered path.
        val dispatchPipe = ScriptedTestPipe(
            name = "dispatch",
            response = """{"pathName": "p1", "pathSchema": "{}"}"""
        )
        val dispatch = Pipeline().apply { add(dispatchPipe) }
        station.setJudgeAgent(judge)
        station.setDispatchAgent(dispatch)
        station.addPath(testPath("p1", returnText = "the final brief"))

        // Enable tracing BEFORE executeLocal. The trace is keyed by the
        // runId generated inside P2PInit (NOT the one we set pre-call),
        // so we read station.getTraceId() AFTER executeLocal returns.
        station.enableTracing(
            TraceConfig(
                enabled = true,
                autoExport = false,
                exportPath = "",
                outputFormat = TraceFormat.HTML,
                detailLevel = TraceDetailLevel.DEBUG
            )
        )

        runBlocking {
            station.executeLocal(MultimodalContent(text = "do the thing"))
        }

        val actualRunId = station.getTraceId()
            ?: error("B1 RED: station has no traceId after executeLocal")
        val events = PipeTracer.getTrace(actualRunId)
        val completed = events.firstOrNull {
            it.eventType == TraceEventType.PUMP_STATION_COMPLETED
        }
        assertNotNull(completed, "B1 RED: harness never emitted PUMP_STATION_COMPLETED")
        assertNotNull(
            completed.metadata["exitReason"],
            "B1 RED: PUMP_STATION_COMPLETED metadata missing 'exitReason'. " +
                "Actual meta keys: ${completed.metadata.keys}"
        )
        assertNotNull(
            completed.metadata["finalOutput"],
            "B1 RED: PUMP_STATION_COMPLETED metadata missing 'finalOutput'. " +
                "Actual meta keys: ${completed.metadata.keys}"
        )
    }
}
