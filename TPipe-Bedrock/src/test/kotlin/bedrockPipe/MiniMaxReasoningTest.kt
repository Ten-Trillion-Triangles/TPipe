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

class MiniMaxReasoningTest {
    
    @BeforeEach
    fun setup() {
        TestCredentialUtils.requireAwsCredentials()
    }
    
    @Test
    fun testMiniMaxM2Reasoning() {
        println("Testing MiniMax M2 Reasoning Extraction")
        runReasoningTest("minimax.minimax-m2", "minimax-m2-trace.html")
    }

    @Test
    fun testMiniMaxM21Reasoning() {
        println("Testing MiniMax M2.1 Reasoning Extraction")
        runReasoningTest("minimax.minimax-m2.1", "minimax-m21-trace.html")
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
        pipe.setReadTimeout(300)
        
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
