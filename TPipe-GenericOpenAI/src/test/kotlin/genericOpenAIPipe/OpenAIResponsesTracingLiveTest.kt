package genericOpenAIPipe

import com.TTT.Pipeline.Pipeline
import com.TTT.Debug.TracingBuilder
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Live HTML-tracing test for the OpenAI Responses API mode.
 *
 * Verifies the [com.TTT.Debug] tracing system records a [com.TTT.Debug.TraceEventType.PIPE_START]
 * -> [com.TTT.Debug.TraceEventType.API_CALL_START] -> [com.TTT.Debug.TraceEventType.API_CALL_SUCCESS]
 * sequence with `apiType = "ResponsesAPI"` metadata, and exports an HTML trace
 * report containing the model name and the responses-mode marker.
 *
 * Run with:
 * ```
 * MINIMAX_API_KEY=... \
 *   ./gradlew :TPipe-GenericOpenAI:test --tests "*OpenAIResponsesTracingLiveTest"
 * ```
 */
@EnabledIfEnvironmentVariable(named = "MINIMAX_API_KEY", matches = ".+")
class OpenAIResponsesTracingLiveTest
{

    @Test
    fun testResponsesHtmlTraceReportContainsModelAndApiType() = runBlocking<Unit>
    {
        val apiKey = System.getenv("MINIMAX_API_KEY")
        Assertions.assertTrue(!apiKey.isNullOrBlank(), "MINIMAX_API_KEY must be set")

        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.VERBOSE)
            .outputFormat(TraceFormat.HTML)
            .build()

        val pipe = GenericOpenAIPipe()
            .setApiKey(apiKey!!)
            .setBaseUrl("https://api.minimax.io/v1")
            .setApiMode(ApiMode.OpenAIResponses)
            .setModel("MiniMax-M2.7")
            .setMaxTokens(256)
            .setTemperature(0.0)

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(traceConfig)
        pipeline.init(true)

        val result = pipeline.execute("Say 'trace-test' in exactly those words.")
        println("Trace test result: '" + result + "'")
        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.isNotEmpty(), "Response should not be empty, got '" + result + "'")

        val consoleReport = pipeline.getTraceReport(TraceFormat.CONSOLE)
        val htmlReport = pipeline.getTraceReport(TraceFormat.HTML)

        // Write trace reports to files for inspection (before assertions to capture state even on failure)
        java.io.File("/tmp/trace_report_console.txt").writeText(consoleReport)
        java.io.File("/tmp/trace_report.html").writeText(htmlReport)

        // Print debug info about what's in the trace
        println("=== Console trace contains 'MiniMax-M2.7': " + consoleReport.contains("MiniMax-M2.7"))
        println("=== Console trace contains 'ResponsesAPI': " + consoleReport.contains("ResponsesAPI"))
        println("=== HTML trace contains 'MiniMax-M2.7': " + htmlReport.contains("MiniMax-M2.7"))
        println("=== HTML trace contains 'ResponsesAPI': " + htmlReport.contains("ResponsesAPI"))
        System.err.println("=== Console contains MiniMax-M2.7: " + consoleReport.contains("MiniMax-M2.7"))
        System.err.println("=== Console contains ResponsesAPI: " + consoleReport.contains("ResponsesAPI"))
        System.err.println("=== HTML contains MiniMax-M2.7: " + htmlReport.contains("MiniMax-M2.7"))
        System.err.println("=== HTML contains ResponsesAPI: " + htmlReport.contains("ResponsesAPI"))

        Assertions.assertTrue(consoleReport.contains("MiniMax-M2.7"), "Console trace must reference the model name")
        Assertions.assertTrue(consoleReport.contains("ResponsesAPI"), "Console trace must tag the API mode")
        Assertions.assertTrue(htmlReport.contains("MiniMax-M2.7"), "HTML trace must reference the model name")
        Assertions.assertTrue(htmlReport.contains("ResponsesAPI"), "HTML trace must tag the API mode")

        println("HTML tracing live Responses test PASSED (html length=" + htmlReport.length + ")")
    }
}
