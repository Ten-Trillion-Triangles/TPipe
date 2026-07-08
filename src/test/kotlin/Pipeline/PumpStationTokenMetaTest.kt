// PumpStation B7: Token funnel writes -1 sentinel instead of omitting the field.
// RED test asserts that no PUMP_STATION_* event in the trace carries a literal
// "-1" for inputTokens / outputTokens / totalTokens. The visualizer's
// readTokenField already handles missing keys (returns null and hides the
// chip), so omitting is strictly better than writing a misleading sentinel.

package com.TTT.Pipeline

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class PumpStationTokenMetaTest
{
    @Test
    fun tokenFieldsAreNotWrittenAsMinusOne()
    {
        val station = buildTestStation(maxHarnessTurns = 1)
        // Judge returns isComplete=true so the harness exits on turn 0
        // (skip-judge-on-first-turn is true by default — we need isComplete
        // to fire on the first judge call after turn 0).
        val judgePipe = ScriptedTestPipe(
            name = "judge",
            response = """{"isComplete": true, "shouldTerminate": false, "reason": "done"}"""
        )
        val judge = Pipeline().apply { add(judgePipe) }
        val dispatchPipe = ScriptedTestPipe(
            name = "dispatch",
            response = """{"pathName": "p1", "pathSchema": "{}"}"""
        )
        val dispatch = Pipeline().apply { add(dispatchPipe) }
        station.setJudgeAgent(judge)
        station.setDispatchAgent(dispatch)
        station.addPath(testPath("p1", returnText = "result"))

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
            station.executeLocal(MultimodalContent(text = "go"))
        }

        val traceId = station.getTraceId()
            ?: error("B7 RED: station has no traceId after executeLocal")
        val events = PipeTracer.getTrace(traceId)

        // Assert no event carries a literal "-1" sentinel for the token fields.
        // The funnel writes -1 as an Int via baseMetadata.put("inputTokens", -1).
        // The TraceEvent's metadata map stores Any, so we compare against the
        // Int value (not the string) to match the funnel's actual behavior.
        val offenders = events.filter { ev ->
            val m = ev.metadata
            m["inputTokens"] == -1 ||
                m["outputTokens"] == -1 ||
                m["totalTokens"] == -1
        }
        assertFalse(
            offenders.isNotEmpty(),
            "B7 RED: ${offenders.size} events carry a literal -1 token sentinel. " +
                "First offender: type=${offenders.firstOrNull()?.eventType}, " +
                "meta=${offenders.firstOrNull()?.metadata}"
        )
    }
}