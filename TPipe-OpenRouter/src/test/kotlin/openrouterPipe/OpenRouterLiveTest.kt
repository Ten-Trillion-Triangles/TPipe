package openrouterPipe

import com.TTT.Pipeline.Pipeline
import com.TTT.Debug.TracingBuilder
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TraceDetailLevel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.TTT.Config.TPipeConfig
import com.TTT.Util.writeStringToFile
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Live integration test for TPipe-OpenRouter that makes a real API call and verifies tracing works.
 *
 * This test is disabled by default and must be enabled manually by removing the @Disabled annotation.
 * Set the OPENROUTER_API_KEY environment variable before running.
 *
 * To run: ./gradlew :TPipe-OpenRouter:test --tests "*.OpenRouterLiveTest"
 */
@Disabled("Live test — enable manually by removing @Disabled annotation")

class OpenRouterLiveTest {

    @Test
    fun testLiveApiCallWithTracing() = runBlocking {
        // 1. Read API key from env var, skip if not set
        val apiKey = System.getenv("OPENROUTER_API_KEY")
        if (apiKey.isNullOrBlank()) {
            println("OPENROUTER_API_KEY not set — skipping live test")
            return@runBlocking
        }

        // 2. Build tracing config — HTML to ~/.TPipe-Debug/traces/ + CONSOLE
        val traceConfig = TracingBuilder()
            .enabled()
            .detailLevel(TraceDetailLevel.VERBOSE)
            .outputFormat(TraceFormat.HTML)
            .autoExport(true, "~/.TPipe-Debug/traces/")
            .build()

        // 3. Create pipeline with OpenRouterPipe
        val pipeline = Pipeline()
            .enableTracing(traceConfig)

        val pipe = OpenRouterPipe()
            .setApiKey(apiKey)
            .setModel("openrouter/free")  // Router that selects from available free models
            .setTemperature(0.7)

        pipeline.add(pipe)

        // 4. Init and execute
        pipeline.init(true)
        val result = pipeline.execute("Say 'Hello, OpenRouter!' in exactly those words.")

        // 5. Assertions
        assertNotNull(result, "Response should not be null")
        assertTrue(result.isNotEmpty(), "Response should not be empty")

        // 6. Check HTML trace and save to file
        val htmlReport = pipeline.getTraceReport(TraceFormat.HTML)
        assertNotNull(htmlReport, "HTML trace report should not be null")
        assertTrue(htmlReport.contains("Hello, OpenRouter!"), "HTML trace should contain response content")

        // Save HTML trace to file
        val traceDir = TPipeConfig.getTraceDir()
        Files.createDirectories(Paths.get(traceDir))
        val traceFile = "$traceDir/openrouter-live-test-${System.currentTimeMillis()}.html"
        writeStringToFile(traceFile, htmlReport)
        println("HTML trace saved to: $traceFile")

        // 7. Check CONSOLE trace
        val consoleReport = pipeline.getTraceReport(TraceFormat.CONSOLE)
        assertNotNull(consoleReport, "CONSOLE trace report should not be null")
        assertTrue(consoleReport.isNotEmpty(), "CONSOLE trace should not be empty")

        // Save JSON trace
        val jsonReport = pipeline.getTraceReport(TraceFormat.JSON)
        if (!jsonReport.isNullOrBlank()) {
            val jsonFile = "$traceDir/openrouter-live-test-${System.currentTimeMillis()}.json"
            writeStringToFile(jsonFile, jsonReport)
            println("JSON trace saved to: $jsonFile")
        }

        // Save markdown trace
        val mdReport = pipeline.getTraceReport(TraceFormat.MARKDOWN)
        if (!mdReport.isNullOrBlank()) {
            val mdFile = "$traceDir/openrouter-live-test-${System.currentTimeMillis()}.md"
            writeStringToFile(mdFile, mdReport)
            println("Markdown trace saved to: $mdFile")
        }

        println("=== CONSOLE TRACE OUTPUT ===")
        println(consoleReport)
        println("=== END CONSOLE TRACE ===")
    }
}