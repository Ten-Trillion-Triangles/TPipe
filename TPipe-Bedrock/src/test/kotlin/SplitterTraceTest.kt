
import com.TTT.Config.TPipeConfig
import java.io.File
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.Splitter
import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class SplitterTraceTest {

    @Test
    fun testSplitterTraceVisibility() {
        // ... (setup code remains same until trace generation) ...
        try {
            TestCredentialUtils.requireAwsCredentials()
        } catch (e: Exception) {
            println("Warning: AWS credentials not found. Test will proceed but expect pipeline execution failure. Tracing verification should still work.")
            // Do not return, continue to verify setup and trace names
        }

        runBlocking(Dispatchers.IO) {
            println("Initializing Splitter Trace Test...")
            bedrockEnv.loadInferenceConfig()

            // 1. Create Pipes
            val narrativePipe = BedrockPipe()
            narrativePipe.setModel("deepseek.r1-v1:0")
            narrativePipe.setRegion("us-east-2")
            narrativePipe.setSystemPrompt("You are a creative writer. Write a one sentence story.")
            narrativePipe.pipeName = "Generator"
            narrativePipe.setMaxTokens(500)

            val assessmentPipe = BedrockPipe()
            assessmentPipe.setModel("deepseek.r1-v1:0")
            assessmentPipe.setRegion("us-east-2")
            assessmentPipe.setSystemPrompt("You are a critic. Rate the story 1-10.")
            assessmentPipe.pipeName = "Evaluator"
            assessmentPipe.setMaxTokens(500)

            // 2. Wrap in Pipelines
            val narrativePipeline = Pipeline()
            narrativePipeline.setPipelineName("Narrative")
            narrativePipeline.add(narrativePipe)

            val assessmentPipeline = Pipeline()
            assessmentPipeline.setPipelineName("Assessment")
            assessmentPipeline.add(assessmentPipe)

            // 3. Create Splitter
            val splitter = Splitter()
            splitter.addPipeline("narrative", narrativePipeline)
            splitter.addPipeline("assessment", assessmentPipeline)

            // 4. Set Input
            val inputContent = MultimodalContent("The brave toaster.")
            splitter.addContent("narrative", inputContent)
            splitter.addContent("assessment", inputContent)

            // 5. Enable Tracing
            val config = TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG)
            splitter.enableTracing(config)

            // 6. Init
            println("Initializing Splitter...")
            splitter.init(config)
            
            println("Narrative Pipe Name: ${narrativePipe.pipeName}")
            println("Assessment Pipe Name: ${assessmentPipe.pipeName}")
            
            assertTrue(narrativePipe.pipeName.startsWith("Narrative:"), "Narrative pipe name should be prefixed")
            assertTrue(assessmentPipe.pipeName.startsWith("Assessment:"), "Assessment pipe name should be prefixed")

            // 7. Execute
            println("Executing Splitter...")
            splitter.executeLocal(inputContent)

            // 8. Get Trace Report (JSON for validation)
            val traceReportJson = splitter.getTraceReport(TraceFormat.JSON)
            println("Trace Report JSON length: ${traceReportJson.length}")
            
            // 9. Get Trace Report (HTML for user visualization)
            val traceReportHtml = splitter.getTraceReport(TraceFormat.HTML)
            
            // Save HTML trace to file as requested
            val traceDir = File(TPipeConfig.getTraceDir())
            if (!traceDir.exists()) {
                traceDir.mkdirs()
            }
            
            val traceFile = File(traceDir, "splitter_trace_test.html")
            traceFile.writeText(traceReportHtml)
            println("HTML Trace report saved to: ${traceFile.absolutePath}")
            
            // 10. Verify Trace Content (using JSON for programmatic check)
            assertTrue(traceReportJson.contains("Narrative:Generator"), "Trace JSON should contain 'Narrative:Generator'")
            assertTrue(traceReportJson.contains("Assessment:Evaluator"), "Trace JSON should contain 'Assessment:Evaluator'")
            
            println("Splitter Trace Validation Successful!")
        }
    }
}
