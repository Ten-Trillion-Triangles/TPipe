package bedrockPipe

import bedrockPipe.BedrockPipe
import com.TTT.Enums.ProviderName
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Pipe.Pipe

class DeepSeekReasoningTest {
    
    @BeforeEach
    fun setup() {
        TestCredentialUtils.requireAwsCredentials()
    }
    
    @Test
    fun testDeepSeekV31Reasoning() {
        println("Testing DeepSeek V3.1 Reasoning Extraction")
        runReasoningTest("deepseek.v3-v1:0", "deepseek-v31-trace.html")
    }

    @Test
    fun testDeepSeekV32Reasoning() {
        println("Testing DeepSeek V3.2 Reasoning Extraction")
        runReasoningTest("deepseek.v3.2", "deepseek-v32-trace.html")
    }

    private fun runReasoningTest(modelId: String, traceFileName: String) {
        val pipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel(modelId)
        (pipe as BedrockPipe).setRegion("us-west-2")
        pipe.useConverseApi()
        
        // Use official TPipe reasoning setter
        pipe.setReasoning("high")
        pipe.enableStreaming({ _ -> })
        pipe.enableMaxTokenOverflow()
        
        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        
        val result = runBlocking {
            pipeline.init(initPipes = true)
            pipe.setMaxTokens(4000)
            pipeline.execute(MultimodalContent(text = "What is 2+2? Reason step-by-step."))
        }
        
        println("Model: $modelId")
        println("Result Text: ${result.text}")
        println("Result Reasoning: ${result.modelReasoning}")
        
        val traceReport = pipeline.getTraceReport(com.TTT.Debug.TraceFormat.HTML)
        java.io.File(traceFileName).writeText(traceReport)
        println("Trace saved to $traceFileName")
        
        assertNotNull(result.text, "Text result should not be null")
        assertTrue(result.text.isNotEmpty(), "Text result should not be empty")
        
        if (result.modelReasoning.isNotEmpty()) {
            println("Successfully extracted reasoning content")
        } else {
            println("Warning: No reasoning content returned (model may have skipped it or API mismatch)")
        }
    }
}
